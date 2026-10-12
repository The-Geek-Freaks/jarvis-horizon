package ai.openclaw.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import java.net.URLDecoder
import java.net.URLEncoder

internal data class AudioInputDeviceOption(
  val key: String,
  val productName: String,
  val type: Int,
)

/** Owns one recorder and its preferred input for the full capture lifecycle. */
internal class AndroidAudioInputSession private constructor(
  private val audioManager: AudioManager,
  private val audioRecord: AudioRecord,
  private val communicationAudio: RealtimeCommunicationAudio?,
  private val ownsCommunicationAudio: Boolean,
  private val routePolicy: RealtimeAudioRoutePolicy,
  private val isCurrent: () -> Boolean,
  private val preferredInputKey: String?,
  private val onAppliedPreferredDeviceChanged: (String?) -> Unit,
  private val setPreferredDevice: (AudioDeviceInfo?) -> Boolean,
) : AutoCloseable {
  companion object {
    private const val tag = "AudioInput"
    private val DIAGNOSTIC_MILESTONES_MS = longArrayOf(1_000L, 5_000L, 10_000L)

    @SuppressLint("MissingPermission")
    fun open(
      context: Context,
      sampleRateHz: Int,
      frameBytes: Int,
      preferredDeviceKey: String? = null,
      onAppliedPreferredDeviceChanged: (String?) -> Unit = {},
      setPreferredDevice: ((AudioDeviceInfo?) -> Boolean)? = null,
      communication: Boolean = false,
      communicationLease: RealtimeCommunicationAudio? = null,
      isCurrent: () -> Boolean = { true },
      onFocusLost: () -> Unit = {},
      routePolicy: RealtimeAudioRoutePolicy = RealtimeAudioRoutePolicy.Communication,
    ): AndroidAudioInputSession {
      val minBuffer =
        AudioRecord.getMinBufferSize(
          sampleRateHz,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      if (minBuffer <= 0) {
        throw IllegalStateException("AudioRecord buffer unavailable")
      }
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      require(communication || communicationLease == null) { "communication lease requires communication capture" }
      val ownsCommunicationAudio = communication && communicationLease == null
      val communicationAudio =
        if (communication) {
          communicationLease ?: RealtimeCommunicationAudio.open(
            audioManager = audioManager,
            isCurrent = isCurrent,
            onFocusLost = onFocusLost,
            routePolicy = routePolicy,
          )
        } else {
          null
        }
      val audioRecord =
        try {
          AudioRecord
            .Builder()
            .setAudioSource(
              if (communication && routePolicy == RealtimeAudioRoutePolicy.Communication) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
              } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
              },
            )
            .setAudioFormat(
              AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build(),
            ).setBufferSizeInBytes(maxOf(minBuffer, frameBytes * 4))
            .build()
        } catch (error: RuntimeException) {
          if (ownsCommunicationAudio) communicationAudio?.close()
          throw error
        }
      return AndroidAudioInputSession(
        audioManager = audioManager,
        audioRecord = audioRecord,
        communicationAudio = communicationAudio,
        ownsCommunicationAudio = ownsCommunicationAudio,
        routePolicy = routePolicy,
        isCurrent = isCurrent,
        preferredInputKey = preferredDeviceKey,
        onAppliedPreferredDeviceChanged = onAppliedPreferredDeviceChanged,
        setPreferredDevice = setPreferredDevice ?: audioRecord::setPreferredDevice,
      ).also { session ->
        try {
          session.openRoute()
        } catch (err: RuntimeException) {
          session.close()
          throw err
        }
      }
    }

    fun listAvailableDevices(context: Context): List<AudioInputDeviceOption> {
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      return audioManager
        .getDevices(AudioManager.GET_DEVICES_INPUTS)
        .map { device ->
          AudioInputDeviceOption(
            key = audioInputDeviceKey(device),
            productName = device.productName.toString().trim(),
            type = device.type,
          )
        }.distinctBy(AudioInputDeviceOption::key)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AudioInputDeviceOption::productName).thenBy(AudioInputDeviceOption::type))
    }

    fun observeAvailableDevices(
      context: Context,
      onChanged: (List<AudioInputDeviceOption>) -> Unit,
    ): AutoCloseable {
      val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      val callback =
        object : AudioDeviceCallback() {
          override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onChanged(listAvailableDevices(context))
          }

          override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onChanged(listAvailableDevices(context))
          }
        }
      audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
      onChanged(listAvailableDevices(context))
      return AutoCloseable { audioManager.unregisterAudioDeviceCallback(callback) }
    }
  }

  private val lock = Any()
  // Ordinary non-Talk capture still owns a Bluetooth communication route while its recorder is
  // active. Talk receives a relay lease instead, so quiet recorder retirement cannot clear it.
  private val captureCommunicationRouteOwner = bluetoothCommunicationRoute.newOwner()
  private val callbackHandler = Handler(Looper.getMainLooper())
  private var closed = false
  private var callbackRegistered = false
  private var routingListenerRegistered = false
  private var requestedInput: AudioDeviceInfo? = null
  private var requestedCommunicationDevice: AudioDeviceInfo? = null
  private var selectedInput: AudioDeviceInfo? = null
  private var appliedPreferredInputKey: String? = null
  private var echoCanceler: AcousticEchoCanceler? = null

  @Volatile private var echoCancellationEnabled = false
  @Volatile private var loggedFirstRead = false
  @Volatile private var diagnosticFirstReadMs = 0L
  @Volatile private var diagnosticPeakMax = 0
  @Volatile private var diagnosticLevelMax = 0f
  @Volatile private var diagnosticMilestoneIndex = 0

  val canCaptureDuringPlayback: Boolean
    get() = routePolicy == RealtimeAudioRoutePolicy.Communication && echoCancellationEnabled && communicationAudio?.eligible == true

  private val deviceCallback =
    object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        refreshRouteSafely()
      }

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        refreshRouteSafely()
      }
    }
  private val routingChangedListener = AudioRouting.OnRoutingChangedListener { refreshActualRouteSafely() }
  internal val preferredInputType: Int?
    get() = synchronized(lock) { selectedInput?.type }

  internal val requestedInputType: Int?
    get() = synchronized(lock) { requestedInput?.type }

  internal val appliedPreferredDeviceKey: String?
    get() = synchronized(lock) { appliedPreferredInputKey }

  fun startRecording() {
    synchronized(lock) {
      check(!closed) { "audio input session closed" }
      if (!isCurrent()) throw CancellationException("audio capture replaced")
      audioRecord.addOnRoutingChangedListener(routingChangedListener, callbackHandler)
      routingListenerRegistered = true
      if (communicationAudio != null) {
        echoCanceler =
          runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioRecord.audioSessionId) else null
          }.getOrNull()
        echoCanceler?.let { effect ->
          effect.setEnableStatusListener { _, enabled ->
            synchronized(lock) { echoCancellationEnabled = !closed && enabled }
          }
          echoCancellationEnabled =
            runCatching {
              effect.setEnabled(true)
              effect.enabled
            }.getOrDefault(false)
        }
      }
    }
    audioRecord.startRecording()
    check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start recording" }
    refreshActualRouteSafely()
    Log.i(
      "OpenClawRealtimeCapture",
      "started source=${audioRecord.audioSource} rate=${audioRecord.sampleRate} route=${audioRecord.routedDevice?.type ?: "pending"}",
    )
    Log.d(tag, "capture started preferred=${preferredInputType ?: "default"} routed=${audioRecord.routedDevice?.type ?: "pending"}")
  }

  fun read(
    buffer: ByteArray,
    offset: Int,
    size: Int,
    metrics: TalkAudioLevel.Pcm16Metrics? = null,
  ): Int {
    val read = checkAudioRecordReadResult(audioRecord.read(buffer, offset, size))
    if (read > 0) {
      val frameMetrics = metrics
      val level: Float
      val peak: Int
      if (frameMetrics != null) {
        TalkAudioLevel.pcm16MetricsInto(buffer, offset, read, frameMetrics)
        level = frameMetrics.level
        peak = frameMetrics.peak
      } else {
        // Preserve the legacy read contract for non-realtime callers.
        level = TalkAudioLevel.pcm16Level(buffer, read)
        peak = pcm16Peak(buffer, offset, read)
      }
      if (!loggedFirstRead) {
        loggedFirstRead = true
        diagnosticFirstReadMs = SystemClock.elapsedRealtime()
        Log.i("OpenClawRealtimeCapture", "first read bytes=$read peak=$peak level=$level")
      }
      diagnosticPeakMax = maxOf(diagnosticPeakMax, peak)
      diagnosticLevelMax = maxOf(diagnosticLevelMax, level)
      logCaptureAggregate()
    }
    return read
  }

  private fun logCaptureAggregate() {
    if (diagnosticFirstReadMs == 0L || diagnosticMilestoneIndex >= DIAGNOSTIC_MILESTONES_MS.size) return
    val elapsed = SystemClock.elapsedRealtime() - diagnosticFirstReadMs
    while (
      diagnosticMilestoneIndex < DIAGNOSTIC_MILESTONES_MS.size &&
      elapsed >= DIAGNOSTIC_MILESTONES_MS[diagnosticMilestoneIndex]
    ) {
      val milestone = DIAGNOSTIC_MILESTONES_MS[diagnosticMilestoneIndex++]
      Log.i(
        "OpenClawRealtimeCapture",
        "aggregate after${milestone}ms peakMax=$diagnosticPeakMax levelMax=$diagnosticLevelMax",
      )
    }
  }

  private fun pcm16Peak(buffer: ByteArray, offset: Int, length: Int): Int {
    var peak = 0
    var index = offset
    val limit = offset + length - (length % 2)
    while (index < limit) {
      val sample = (buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)
      peak = maxOf(peak, kotlin.math.abs(sample.toShort().toInt()))
      index += 2
    }
    return peak
  }

  private fun openRoute() {
    if (
      routePolicy == RealtimeAudioRoutePolicy.Communication &&
        communicationAudio == null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !bluetoothCommunicationRoute.begin(captureCommunicationRouteOwner, isCurrent)
    ) {
      throw CancellationException("audio capture replaced")
    }
    audioManager.registerAudioDeviceCallback(deviceCallback, callbackHandler)
    synchronized(lock) { callbackRegistered = true }
    refreshRouteSafely()
  }

  private fun refreshRouteSafely() {
    try {
      refreshRoute()
    } catch (err: RuntimeException) {
      // Routing is a preference; default capture remains better than losing the voice session.
      Log.w(tag, "Bluetooth route update failed: ${err.message ?: err::class.simpleName}")
    }
  }

  private fun refreshRoute() {
    synchronized(lock) {
      if (closed || !isCurrent()) return
      val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
      val preferredInput = resolvePreferredAudioInput(inputs, preferredInputKey)
      if (preferredInput != null && applyRoute(inputs, preferredInput)) {
        return
      }
      // A rejected record preference may have set a Bluetooth communication route.
      // Recalculate automatic priority instead of retaining that rejected target.
      if (preferredInput != null) requestedCommunicationDevice = null
      setAppliedPreferredInputKey(null)
      applyRoute(inputs, null)
    }
  }

  private fun applyRoute(
    inputs: List<AudioDeviceInfo>,
    preferredInput: AudioDeviceInfo?,
  ): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      applyApi31Route(inputs, preferredInput)
    } else {
      applyLegacyRoute(inputs, preferredInput)
    }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun applyApi31Route(
    inputs: List<AudioDeviceInfo>,
    preferredInput: AudioDeviceInfo?,
  ): Boolean {
    if (routePolicy == RealtimeAudioRoutePolicy.MediaOutput) {
      if (sameDevice(requestedInput, preferredInput) && sameDevice(selectedInput, preferredInput)) return true
      requestedInput = preferredInput
      return if (setPreferredDevice(preferredInput)) {
        selectedInput = preferredInput
        true
      } else {
        selectedInput = null
        false
      }
    }
    val routedCommunicationDevice =
      if (communicationAudio != null) {
        communicationAudio.updateCommunicationRoute(preferredInput)
        null
      } else {
        val available = audioManager.availableCommunicationDevices
        val bluetoothDevice =
          if (preferredInput == null) {
            selectBluetoothDevice(available, requestedCommunicationDevice)
          } else {
            selectCommunicationDevice(available, preferredInput)
          }
        val selected = bluetoothCommunicationRoute.update(audioManager, captureCommunicationRouteOwner, bluetoothDevice, isCurrent)
        requestedCommunicationDevice = bluetoothDevice.takeIf { selected }
        requestedCommunicationDevice
      }
    val input = preferredInput ?: selectBluetoothInput(inputs, requestedInput, routedCommunicationDevice)
    if (sameDevice(requestedInput, input) && sameDevice(selectedInput, input)) return true
    requestedInput = input
    return if (setPreferredDevice(input)) {
      selectedInput = input
      Log.d(tag, "preferred input changed type=${input?.type ?: "default"}")
      true
    } else {
      selectedInput = null
      Log.w(tag, "preferred input rejected type=${input?.type ?: "default"}")
      false
    }
  }

  @Suppress("DEPRECATION")
  private fun applyLegacyRoute(
    inputs: List<AudioDeviceInfo>,
    preferredInput: AudioDeviceInfo?,
  ): Boolean {
    val input = preferredInput ?: selectBluetoothInput(inputs, requestedInput, null)
    if (sameDevice(requestedInput, input) && sameDevice(selectedInput, input)) return true
    requestedInput = input
    return if (setPreferredDevice(input)) {
      selectedInput = input
      Log.d(tag, "preferred input changed type=${input?.type ?: "default"}")
      true
    } else {
      selectedInput = null
      Log.w(tag, "preferred input rejected type=${input?.type ?: "default"}")
      false
    }
  }

  private fun setAppliedPreferredInputKey(value: String?) {
    if (appliedPreferredInputKey == value) return
    appliedPreferredInputKey = value
    onAppliedPreferredDeviceChanged(value)
  }

  private fun refreshActualRouteSafely() {
    try {
      refreshActualRoute()
    } catch (err: RuntimeException) {
      Log.w(tag, "audio route verification failed: ${err.message ?: err::class.simpleName}")
    }
  }

  private fun refreshActualRoute() {
    synchronized(lock) {
      if (closed || !isCurrent()) return
      val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
      val expectedInput = resolvePreferredAudioInput(inputs, preferredInputKey)
      if (expectedInput == null) {
        setAppliedPreferredInputKey(null)
        applyRoute(inputs, null)
        return
      }
      val routedInput = audioRecord.routedDevice
      if (sameDevice(routedInput, expectedInput)) {
        setAppliedPreferredInputKey(preferredInputKey)
        return
      }
      setAppliedPreferredInputKey(null)
    }
  }

  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      echoCancellationEnabled = false
      echoCanceler?.let { effect ->
        effect.setEnableStatusListener(null)
        runCatching { effect.release() }
      }
      echoCanceler = null
      if (callbackRegistered) {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        callbackRegistered = false
      }
      if (routingListenerRegistered) {
        runCatching { audioRecord.removeOnRoutingChangedListener(routingChangedListener) }
        routingListenerRegistered = false
      }
      runCatching { audioRecord.setPreferredDevice(null) }
      requestedInput = null
      selectedInput = null
      setAppliedPreferredInputKey(null)
      if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        runCatching { audioRecord.stop() }
      }
      runCatching { audioRecord.release() }
      if (ownsCommunicationAudio) {
        communicationAudio?.close()
      } else if (routePolicy == RealtimeAudioRoutePolicy.Communication) {
        bluetoothCommunicationRoute.close(audioManager, captureCommunicationRouteOwner)
      }
      requestedCommunicationDevice = null
    }
  }
}

/** Serializes Android's process-wide communication route across overlapping capture cleanup. */
internal class BluetoothCommunicationRoute {
  private var nextOwner = 0L
  private var latestOwner = 0L
  private var activeOwner: Long? = null

  @Synchronized
  fun newOwner(): Long = ++nextOwner

  @Synchronized
  fun begin(
    owner: Long,
    isCurrent: () -> Boolean,
  ): Boolean {
    if (!isCurrent()) return false
    if (owner > latestOwner) latestOwner = owner
    return true
  }

  @Synchronized
  fun update(
    audioManager: AudioManager,
    owner: Long,
    device: AudioDeviceInfo?,
    isCurrent: () -> Boolean,
  ): Boolean {
    if (!isCurrent() || owner < latestOwner || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    latestOwner = owner
    if (device == null || !Api31CommunicationRoute.set(audioManager, device)) {
      if (activeOwner != null) Api31CommunicationRoute.clear(audioManager)
      activeOwner = null
      return false
    }
    activeOwner = owner
    return true
  }

  @Synchronized
  fun close(
    audioManager: AudioManager,
    owner: Long,
  ) {
    if (activeOwner != owner || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    Api31CommunicationRoute.clear(audioManager)
    activeOwner = null
  }
}

internal val bluetoothCommunicationRoute = BluetoothCommunicationRoute()

private val legacyExternalCommunicationOutputs =
  setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_AUX_LINE,
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_HEARING_AID,
  )

internal fun isExternalCommunicationOutput(type: Int): Boolean =
  type in legacyExternalCommunicationOutputs ||
    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Api31CommunicationRoute.isBleOutput(type))

/** Converts AudioRecord's negative return codes into capture-session failures. */
internal fun checkAudioRecordReadResult(result: Int): Int {
  if (result >= 0) return result
  val label =
    when (result) {
      AudioRecord.ERROR -> "ERROR"
      AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
      AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
      AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
      else -> "code=$result"
    }
  throw IllegalStateException("microphone read failed: $label")
}

private fun selectBluetoothDevice(
  devices: List<AudioDeviceInfo>,
  current: AudioDeviceInfo? = null,
): AudioDeviceInfo? {
  current
    ?.takeIf { candidate ->
      bluetoothPriority(candidate.type) != null && devices.any { sameDevice(it, candidate) }
    }?.let { return it }
  return devices
    .asSequence()
    .mapNotNull { device -> bluetoothPriority(device.type)?.let { priority -> priority to device } }
    .minWithOrNull(compareBy<Pair<Int, AudioDeviceInfo>> { it.first }.thenBy { it.second.id })
    ?.second
}

private fun selectBluetoothInput(
  devices: List<AudioDeviceInfo>,
  current: AudioDeviceInfo?,
  communicationDevice: AudioDeviceInfo?,
): AudioDeviceInfo? {
  if (communicationDevice == null) return selectBluetoothDevice(devices, current)
  val candidates = devices.filter { it.type == communicationDevice.type }
  current?.takeIf { candidate -> candidates.any { sameDevice(it, candidate) } }?.let { return it }
  val address = communicationDevice.compatAddress().trim()
  if (address.isNotEmpty()) {
    candidates.firstOrNull { it.compatAddress() == address }?.let { return it }
  }
  // setCommunicationDevice chooses the matching source; only override it when unambiguous.
  return candidates.singleOrNull()
}

internal fun selectCommunicationDevice(
  devices: List<AudioDeviceInfo>,
  input: AudioDeviceInfo,
): AudioDeviceInfo? {
  if (bluetoothPriority(input.type) == null) return null
  val candidates = devices.filter { it.type == input.type }
  val address = input.compatAddress().trim()
  return candidates.firstOrNull { address.isNotEmpty() && it.compatAddress() == address } ?: candidates.singleOrNull()
}

internal fun audioInputDeviceKey(device: AudioDeviceInfo): String = audioInputDeviceKey(device.type, device.compatAddress(), device.productName.toString())

private fun AudioDeviceInfo.compatAddress(): String =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Api28AudioDeviceAddress.get(this) else ""

@RequiresApi(Build.VERSION_CODES.P)
private object Api28AudioDeviceAddress {
  fun get(device: AudioDeviceInfo): String = device.address
}

internal fun resolvePreferredAudioInput(
  devices: List<AudioDeviceInfo>,
  preferredDeviceKey: String?,
): AudioDeviceInfo? = preferredDeviceKey?.let { key -> devices.firstOrNull { audioInputDeviceKey(it) == key } }

internal fun audioInputDeviceKey(
  type: Int,
  address: String,
  productName: String,
): String {
  // AudioDeviceInfo.id is per-boot; persist routing attributes and re-resolve each session.
  // Fields are URL-encoded so the key stays XML-safe in SharedPreferences; a raw
  // control-char separator can corrupt the whole plain prefs file on reload.
  return listOf(type.toString(), address, productName).joinToString("|") { URLEncoder.encode(it, "UTF-8") }
}

internal fun audioInputDeviceOptionFromKey(key: String): AudioInputDeviceOption? {
  val parts = key.split("|", limit = 3).map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: return null }
  if (parts.size != 3) return null
  return AudioInputDeviceOption(
    key = key,
    productName = parts[2],
    type = parts[0].toIntOrNull() ?: return null,
  )
}

private fun bluetoothPriority(type: Int): Int? =
  when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Api31CommunicationRoute.blePriority(type) else null
  }

/** API 31 communication-device routing and BLE device types are unavailable on Android 11. */
@RequiresApi(Build.VERSION_CODES.S)
internal object Api31CommunicationRoute {
  fun available(audioManager: AudioManager): List<AudioDeviceInfo> = audioManager.availableCommunicationDevices

  fun set(
    audioManager: AudioManager,
    device: AudioDeviceInfo,
  ): Boolean = audioManager.setCommunicationDevice(device)

  fun clear(audioManager: AudioManager) {
    audioManager.clearCommunicationDevice()
  }

  fun isBleOutput(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER

  fun blePriority(type: Int): Int? =
    when (type) {
      AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
      else -> null
    }
}

private fun sameDevice(
  left: AudioDeviceInfo?,
  right: AudioDeviceInfo?,
): Boolean = left?.id == right?.id && left?.type == right?.type
