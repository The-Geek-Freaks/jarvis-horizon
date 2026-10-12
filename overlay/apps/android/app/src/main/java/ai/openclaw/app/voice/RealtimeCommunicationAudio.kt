package ai.openclaw.app.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One relay's request for Android's process-wide communication mode, audio focus and route.
 *
 * It deliberately outlives a recorder. Talk retires a quiet microphone while the relay can
 * still play its answer; tying this state to AudioRecord would make Android fall back to the
 * earpiece in the middle of that answer.
 */
internal class RealtimeCommunicationAudio private constructor(
  private val audioManager: AudioManager,
  private val routePolicy: RealtimeAudioRoutePolicy,
  private val isCurrent: () -> Boolean,
  private val onFocusLost: () -> Unit,
) : AutoCloseable {
  companion object {
    private val ownershipLock = Any()
    private var current: RealtimeCommunicationAudio? = null

    fun open(
      audioManager: AudioManager,
      isCurrent: () -> Boolean = { true },
      onFocusLost: () -> Unit = {},
      routePolicy: RealtimeAudioRoutePolicy = RealtimeAudioRoutePolicy.Communication,
    ): RealtimeCommunicationAudio =
      synchronized(ownershipLock) {
        // A cancelled opener must not displace the replacement capture while acquiring SDK state.
        if (!isCurrent()) throw CancellationException("audio capture replaced")
        current?.close()
        RealtimeCommunicationAudio(audioManager, routePolicy, isCurrent, onFocusLost).also { session ->
          current = session
          try {
            session.start()
          } catch (error: RuntimeException) {
            session.close()
            throw error
          }
        }
      }

    fun playbackAttributes(
      routePolicy: RealtimeAudioRoutePolicy = RealtimeAudioRoutePolicy.Communication,
    ): AudioAttributes =
      AudioAttributes
        .Builder()
        .setUsage(
          if (routePolicy == RealtimeAudioRoutePolicy.MediaOutput) {
            AudioAttributes.USAGE_MEDIA
          } else {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
          },
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
  }

  @Volatile private var closed = false

  private enum class Focus { Requesting, Granted, Lost }

  private val focus = AtomicReference(Focus.Requesting)

  @Volatile private var mode = AudioManager.MODE_NORMAL
  private var modeListenerRegistered = false
  private var modeRequested = false
  private val communicationRouteOwner = bluetoothCommunicationRoute.newOwner()
  private var legacySpeakerphoneWasEnabled: Boolean? = null
  private val handler = Handler(Looper.getMainLooper())
  private val focusRequest =
    AudioFocusRequest
      .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      .setAudioAttributes(playbackAttributes(routePolicy))
      .setWillPauseWhenDucked(true)
      .setOnAudioFocusChangeListener({ change ->
        if (!closed) {
          focus.set(if (change == AudioManager.AUDIOFOCUS_GAIN) Focus.Granted else Focus.Lost)
          Log.d("TalkAudio", "communication focus=$change")
          if (change != AudioManager.AUDIOFOCUS_GAIN) {
            handler.post {
              if (!closed && isCurrent()) onFocusLost()
            }
          }
        }
      }, handler)
      .build()

  // Mode is the effective platform value, not proof that this app owns every hardware route.
  val eligible: Boolean
    get() =
      !closed &&
        focus.get() == Focus.Granted &&
        (routePolicy == RealtimeAudioRoutePolicy.MediaOutput || mode == AudioManager.MODE_IN_COMMUNICATION)

  private fun start() {
    if (routePolicy == RealtimeAudioRoutePolicy.Communication && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Api31ModeListener.register(audioManager, handler) { value ->
        if (!closed) {
          mode = value
          Log.d("TalkAudio", "communication mode=$value")
        }
      }
      modeListenerRegistered = true
    }
    val granted = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    // A loss callback delivered during acquisition must not be overwritten by its return value.
    focus.compareAndSet(Focus.Requesting, if (granted) Focus.Granted else Focus.Lost)
    check(granted && focus.get() == Focus.Granted) { "audio focus unavailable" }
    if (routePolicy == RealtimeAudioRoutePolicy.Communication) {
      modeRequested = true
      audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
      mode = audioManager.mode
      check(bluetoothCommunicationRoute.begin(communicationRouteOwner, isCurrent)) { "audio capture replaced" }
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        @Suppress("DEPRECATION")
        run {
          legacySpeakerphoneWasEnabled = audioManager.isSpeakerphoneOn
          audioManager.isSpeakerphoneOn = true
        }
      }
      updateCommunicationRoute(null)
    }
  }

  /** Updates only the communication output. Input selection remains owned by AudioRecord. */
  internal fun updateCommunicationRoute(preferredInput: AudioDeviceInfo?) {
    if (closed || routePolicy != RealtimeAudioRoutePolicy.Communication || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val available = Api31CommunicationRoute.available(audioManager)
    val preferredOutput = preferredInput?.let { input -> selectCommunicationDevice(available, input) }
    val output =
      preferredOutput
        ?: available.firstOrNull { isExternalCommunicationOutput(it.type) }
        ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    bluetoothCommunicationRoute.update(audioManager, communicationRouteOwner, output, isCurrent)
  }

  override fun close() {
    synchronized(ownershipLock) {
      if (closed) return
      closed = true
      focus.set(Focus.Lost)
      if (modeListenerRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { Api31ModeListener.unregister(audioManager) }
      }
      runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
      if (current === this) {
        current = null
        if (routePolicy == RealtimeAudioRoutePolicy.Communication) {
          bluetoothCommunicationRoute.close(audioManager, communicationRouteOwner)
          legacySpeakerphoneWasEnabled?.let { wasEnabled ->
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = wasEnabled }
          }
          legacySpeakerphoneWasEnabled = null
        }
        // AOSP tracks mode requests per PID. NORMAL withdraws ours; replaying a saved global
        // mode would assert another app's request as our own after Talk has stopped.
        if (modeRequested) runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
      }
    }
  }
}

/** API 31 added AudioManager mode callbacks; keep their class references off the API 30 path. */
@RequiresApi(Build.VERSION_CODES.S)
private object Api31ModeListener {
  private val listeners = mutableMapOf<AudioManager, AudioManager.OnModeChangedListener>()

  @Synchronized
  fun register(
    audioManager: AudioManager,
    handler: Handler,
    onModeChanged: (Int) -> Unit,
  ) {
    unregister(audioManager)
    val listener = AudioManager.OnModeChangedListener(onModeChanged)
    audioManager.addOnModeChangedListener({ command -> handler.post(command) }, listener)
    listeners[audioManager] = listener
  }

  @Synchronized
  fun unregister(audioManager: AudioManager) {
    listeners.remove(audioManager)?.let { listener ->
      audioManager.removeOnModeChangedListener(listener)
    }
  }
}
