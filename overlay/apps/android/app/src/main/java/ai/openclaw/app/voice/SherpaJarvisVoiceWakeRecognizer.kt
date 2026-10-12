package ai.openclaw.app.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

internal enum class SherpaWakeEndpointKind {
  Endpoint,
  Expired,
}

internal data class SherpaWakeEndpoint(
  val kind: SherpaWakeEndpointKind,
  val endSample: Long,
)

/**
 * The VAD result is an endpoint decision only.  The KWS output is never used
 * as a speech boundary, so an inline command survives a delayed KWS result.
 */
internal class SherpaWakeEndpointController(
  private val sampleRateHz: Int,
  segmentStartSample: Long,
  private val trailingSilenceMs: Long = SHERPA_WAKE_TRAILING_SILENCE_MS,
  private val maxSegmentDurationMs: Long = SHERPA_WAKE_MAX_SEGMENT_DURATION_MS,
) {
  private var segmentStartSample = segmentStartSample
  private var consecutiveSilenceSamples = 0L
  private var observedSpeech = false
  private var keywordRebased = false

  init {
    require(sampleRateHz > 0)
    require(segmentStartSample >= 0)
  }

  /**
   * The VAD onset bounds only the pre-keyword search. Once KWS accepts its first marker,
   * give the actual wake utterance its own bounded command window. A duplicate marker must
   * never keep extending that window.
   */
  fun rebaseOnKeyword(keywordSegmentStartSample: Long): Boolean {
    if (keywordSegmentStartSample < 0L || keywordRebased) return false
    segmentStartSample = keywordSegmentStartSample
    consecutiveSilenceSamples = 0L
    observedSpeech = true
    keywordRebased = true
    return true
  }

  fun observe(firstSample: Long, endSample: Long, isSpeech: Boolean): SherpaWakeEndpoint? {
    if (endSample <= firstSample || firstSample < segmentStartSample) return null
    if (endSample - segmentStartSample > maxSegmentDurationMs * sampleRateHz / 1_000L) {
      return SherpaWakeEndpoint(SherpaWakeEndpointKind.Expired, endSample)
    }
    if (isSpeech) observedSpeech = true
    consecutiveSilenceSamples = if (!observedSpeech || isSpeech) 0L else consecutiveSilenceSamples + (endSample - firstSample)
    return if (observedSpeech && !isSpeech && consecutiveSilenceSamples >= trailingSilenceMs * sampleRateHz / 1_000L) {
      SherpaWakeEndpoint(SherpaWakeEndpointKind.Endpoint, endSample)
    } else {
      null
    }
  }
}

internal data class SherpaWakeAudioSnapshot(
  val firstSample: Long,
  val nextSample: Long,
  val pcm16: ByteArray,
  val sampleRateHz: Int,
) {
  fun audioBetween(startSample: Long, endSample: Long): VoiceWakeAudio? {
    if (startSample < firstSample || endSample < startSample || endSample > nextSample) return null
    val startOffset = ((startSample - firstSample) * 2L).toInt()
    val endOffset = ((endSample - firstSample) * 2L).toInt()
    return pcm16.copyOfRange(startOffset, endOffset).takeIf { it.isNotEmpty() }?.let { VoiceWakeAudio(it, sampleRateHz) }
  }
}

/** Bounded ordered PCM retained while one KWS result awaits the real endpoint. */
internal class SherpaWakeAudioBuffer(
  private val sampleRateHz: Int,
  private val maxDurationMs: Long = SHERPA_WAKE_MAX_SEGMENT_DURATION_MS,
) {
  private data class Chunk(val firstSample: Long, val pcm16: ByteArray)

  private val maxBytes = (sampleRateHz * 2L * maxDurationMs / 1_000L).toInt()
  private val chunks = ArrayDeque<Chunk>()
  private var retainedBytes = 0
  private var nextSample = 0L

  init {
    require(sampleRateHz > 0)
    require(maxBytes >= 2)
  }

  fun append(pcm16: ByteArray, length: Int = pcm16.size) {
    val validLength = length.coerceIn(0, pcm16.size) and -2
    if (validLength == 0) return
    val copy = pcm16.copyOf(validLength)
    chunks.addLast(Chunk(nextSample, copy))
    retainedBytes += copy.size
    nextSample += copy.size / 2L
    while (retainedBytes > maxBytes && chunks.isNotEmpty()) retainedBytes -= chunks.removeFirst().pcm16.size
  }

  fun snapshot(): SherpaWakeAudioSnapshot {
    val pcm16 = ByteArray(retainedBytes)
    var offset = 0
    chunks.forEach { chunk ->
      chunk.pcm16.copyInto(pcm16, offset)
      offset += chunk.pcm16.size
    }
    return SherpaWakeAudioSnapshot(chunks.firstOrNull()?.firstSample ?: nextSample, nextSample, pcm16, sampleRateHz)
  }

  fun nextSample(): Long = nextSample

  fun reset() {
    chunks.clear()
    retainedBytes = 0
    nextSample = 0L
  }

  fun appendQueuedTail(tail: VoskWakeQueuedTail) {
    tail.frames.forEach { append(it.pcm16) }
  }
}

/**
 * Starts the VAD segment before the reported token start for model context,
 * but hands that whole segment to Talk.  This is intentionally conservative:
 * deleting uncertain speech is worse than replaying the wake word once.
 */
internal fun sherpaWakeSegmentStartSample(
  keywordStartSample: Long,
  snapshot: SherpaWakeAudioSnapshot,
  contextMs: Long = SHERPA_WAKE_VAD_CONTEXT_MS,
): Long? {
  if (keywordStartSample < snapshot.firstSample || keywordStartSample >= snapshot.nextSample) return null
  val contextSamples = contextMs * snapshot.sampleRateHz / 1_000L
  return maxOf(snapshot.firstSample, keywordStartSample - contextSamples)
}

internal const val SHERPA_WAKE_TRAILING_SILENCE_MS = 500L
internal const val SHERPA_WAKE_MAX_SEGMENT_DURATION_MS = 12_000L
internal const val SHERPA_WAKE_VAD_CONTEXT_MS = 200L
internal const val SHERPA_WAKE_KWS_PREROLL_MS = 800L
internal const val SHERPA_WAKE_VAD_GAIN_HISTORY_MS = 300L
private const val SHERPA_WAKE_CAPTURE_FRAME_MS = 100L
private const val SHERPA_WAKE_BLOCKED_READ_TIMEOUT_MS = 5_000L
private const val SHERPA_WAKE_WATCHDOG_POLL_MS = 250L
// An onset can replay 800 ms of history plus the current 100-ms capture frame.
internal const val SHERPA_WAKE_KWS_INPUT_MAX_SAMPLES = 14_400
private const val SHERPA_WAKE_QUEUED_TAIL_MAX_MS = 4_000L
internal const val SHERPA_WAKE_AUDIO_RETENTION_MS =
  SHERPA_WAKE_MAX_SEGMENT_DURATION_MS + SHERPA_WAKE_KWS_PREROLL_MS +
    SHERPA_WAKE_VAD_CONTEXT_MS + SHERPA_WAKE_TRAILING_SILENCE_MS + SHERPA_WAKE_QUEUED_TAIL_MAX_MS
private const val SHERPA_WAKE_NATIVE_VAD_MAX_SPEECH_SECONDS = 13f

internal fun sherpaWakeKwsReplayStartSample(
  vadOnsetSample: Long,
  snapshot: SherpaWakeAudioSnapshot,
): Long? =
  (vadOnsetSample - SHERPA_WAKE_KWS_PREROLL_MS * snapshot.sampleRateHz / 1_000L)
    .coerceAtLeast(snapshot.firstSample)
    .takeIf { it < snapshot.nextSample }

internal fun sherpaWakeAudioSourceForCronos(isCronosEchoShow: Boolean): Int =
  if (isCronosEchoShow) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION

/** KWS-only wake implementation. Vosk is never loaded by this recognizer. */
internal class SherpaJarvisVoiceWakeRecognizer(
  context: Context,
  private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
  private val continuousKws: Boolean = false,
) : VoiceWakeRecognizer {
  private data class Active(
    val operationId: Long,
    val stream: SherpaWakeKeywordStream,
    val vad: Vad,
    val capture: SherpaWakeAudioCapture,
    val callbacks: VoiceWakeRecognitionSession,
  )

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val lock = Any()
  private val startLock = Any()
  private var latestOperationId = 0L
  private var spotter: SherpaWakeKeywordSpotter? = null
  private var loading = false
  private var pending: Pair<Long, (VoiceWakeRecognitionEvent) -> Unit>? = null
  private var active: Active? = null
  private var draining = false

  override val isAvailable = true

  override fun start(operationId: Long, onEvent: (VoiceWakeRecognitionEvent) -> Unit) {
    val (retired, existing) = synchronized(lock) {
      if (operationId <= latestOperationId) return
      latestOperationId = operationId
      val old = retireLocked()
      pending = operationId to onEvent
      old to spotter
    }
    if (retired != null) {
      closeRetired(retired)
      return
    }
    beginStart(operationId, existing)
  }

  private fun beginStart(operationId: Long, cached: SherpaWakeKeywordSpotter? = null) {
    val existing = synchronized(lock) {
      if (draining || pending?.first != operationId) return
      cached ?: spotter
    }
    if (existing != null) startListening(existing) else loadSpotter()
  }

  override fun stop(operationId: Long) {
    val retired = synchronized(lock) {
      if (operationId <= latestOperationId) return
      latestOperationId = operationId
      pending = null
      retireLocked()
    }
    retired?.let(::closeRetired)
  }

  private fun loadSpotter() {
    val shouldLoad = synchronized(lock) {
      if (loading || spotter != null || pending == null) false else { loading = true; true }
    }
    if (!shouldLoad) return
    Thread({
      val loaded = runCatching { SherpaWakeKeywordSpotter.create(appContext) }.getOrNull()
      val next = synchronized(lock) {
        loading = false
        if (loaded != null) spotter = loaded
        pending
      }
      if (loaded == null) next?.second?.invoke(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
      else if (next != null) beginStart(next.first)
    }, "OpenClawSherpaWakeLoad").start()
  }

  private fun startListening(loaded: SherpaWakeKeywordSpotter): Unit = synchronized(startLock) {
    val request = synchronized(lock) { if (draining || active != null) null else pending } ?: return
    val callbacks = VoiceWakeRecognitionSession(request.second)
    val stream = runCatching { loaded.createStream() }.getOrElse {
      callbacks.retire(); request.second(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT)); return
    }
    val vad = runCatching { Vad(appContext.assets, vadConfig()) }.getOrElse {
      stream.release(); callbacks.retire(); request.second(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT)); return
    }
    // Keep one complete bounded VAD utterance, causal KWS preroll and the decoder's queued tail.
    val buffer = SherpaWakeAudioBuffer(SAMPLE_RATE_HZ, SHERPA_WAKE_AUDIO_RETENTION_MS)
    val emitted = AtomicBoolean(false)
    var awaitingEndpoint: SherpaWakeEndpointController? = null
    var kwsReplayStartSample = -1L
    var kwsGain = 1f
    var kwsStreamStartSample = 0L
    val continuousKwsLimiter = SherpaWakeContinuousKwsLimiter(SHERPA_WAKE_CAPTURE_FRAME_MS)
    var continuousFallbackEndpoint = false
    var segmentStart = -1L
    var vadRemainder = ByteArray(0)
    val vadGainHistory = SherpaWakeVadGainHistory(
      SAMPLE_RATE_HZ * 2 * SHERPA_WAKE_VAD_GAIN_HISTORY_MS.toInt() / 1_000,
    )
    val vadSamples = FloatArray(VAD_WINDOW_SAMPLES)
    val kwsSamples = FloatArray(SHERPA_WAKE_KWS_INPUT_MAX_SAMPLES)
    var vadGateResetDuringFeed = false
    fun resetGate() {
      awaitingEndpoint = null
      kwsReplayStartSample = -1L
      kwsGain = 1f
      segmentStart = -1L
      vadRemainder = ByteArray(0)
      vadGainHistory.reset()
      vadGateResetDuringFeed = true
      vad.reset()
      // The native model is reused, but every VAD onset starts a clean KWS stream state.
      loaded.reset(stream)
      kwsStreamStartSample = buffer.nextSample()
      continuousKwsLimiter.reset()
      continuousFallbackEndpoint = false
    }
    fun resetToKws() {
      emitted.set(false)
      resetGate()
    }
    fun drainCompletedVadSegments() {
      while (!vad.empty()) vad.pop()
    }
    fun observeKws(rawPcm16: ByteArray) {
      val replayStart = kwsReplayStartSample
      if (replayStart < 0L || segmentStart >= 0L) return
      val gain = kwsGain
      val marker = sherpaWakeAcceptRawPcmInChunks(rawPcm16, gain, kwsSamples) { samples, sampleCount ->
        // Sherpa's marker remains relative to this already-reset native stream across chunks.
        // Do not add a chunk offset here.
        loaded.acceptAudio(stream, samples, sampleCount)
      } ?: return
      val snapshot = buffer.snapshot()
      val markerAbsolute = replayStart + marker
      val start = sherpaWakeSegmentStartSample(markerAbsolute, snapshot)
      if (start == null) {
        Log.w(TAG, "wake marker outside retained PCM marker=$marker absolute=$markerAbsolute first=${snapshot.firstSample} next=${snapshot.nextSample}; rearming")
        resetGate()
        return
      }
      segmentStart = start
      awaitingEndpoint?.rebaseOnKeyword(start)
      Log.i(TAG, "wake keyword detected markerSample=$marker absoluteMarkerSample=$markerAbsolute segmentStart=$start acceptedUntil=${snapshot.nextSample}")
    }
    fun observeContinuousKws(rawPcm16: ByteArray) {
      if (segmentStart >= 0L) return
      val marker =
        sherpaWakeAcceptRawPcmInChunks(rawPcm16, continuousKwsLimiter.nextGain(rawPcm16), kwsSamples) { samples, sampleCount ->
          loaded.acceptAudio(stream, samples, sampleCount)
        } ?: return
      val snapshot = buffer.snapshot()
      val markerAbsolute = kwsStreamStartSample + marker
      val start = sherpaWakeSegmentStartSample(markerAbsolute, snapshot)
      if (start == null) {
        Log.w(TAG, "wake continuous marker outside retained PCM marker=$marker absolute=$markerAbsolute first=${snapshot.firstSample} next=${snapshot.nextSample}; rearming")
        loaded.reset(stream)
        kwsStreamStartSample = snapshot.nextSample
        continuousKwsLimiter.reset()
        return
      }
      segmentStart = start
      // If native VAD never admits the keyword, this bounded fallback prevents a pending
      // candidate forever. The first admitted VAD frame replaces it with the native-silence path.
      awaitingEndpoint = SherpaWakeEndpointController(SAMPLE_RATE_HZ, start).also { it.rebaseOnKeyword(start) }
      continuousFallbackEndpoint = true
      Log.i(TAG, "wake keyword detected continuous markerSample=$marker absoluteMarkerSample=$markerAbsolute segmentStart=$start acceptedUntil=${snapshot.nextSample}")
    }
    fun beginKwsAtVadOnset(vadOnsetSample: Long) {
      val snapshot = buffer.snapshot()
      val replayStart = sherpaWakeKwsReplayStartSample(vadOnsetSample, snapshot) ?: return
      val replay = snapshot.audioBetween(replayStart, snapshot.nextSample)?.pcm16 ?: return
      loaded.reset(stream)
      kwsReplayStartSample = replayStart
      // Freeze KWS conditioning for this complete segment: changing gain mid-word rejected
      // a real Echo wake. VAD gain remains adaptive and retained/seed PCM stays raw.
      kwsGain = vadGainHistory.gain()
      awaitingEndpoint = SherpaWakeEndpointController(SAMPLE_RATE_HZ, vadOnsetSample, trailingSilenceMs = 0)
      observeKws(replay)
      Log.d(TAG, "wake VAD onset=$vadOnsetSample kwsReplayStart=$replayStart replayBytes=${replay.size} ${sherpaWakeLevelSummary(replay)} vadGain=${vadGainHistory.gain()}")
    }
    fun emitEndpoint(endpoint: SherpaWakeEndpoint, state: VoskWakeCaptureState) {
      // Native VAD owns the 500-ms silence decision. Its completed segment is only diagnostic
      // state here; Talk receives the original PCM retained in our bounded raw buffer.
      drainCompletedVadSegments()
      if (endpoint.kind == SherpaWakeEndpointKind.Expired || segmentStart < 0L) {
        val terminalReason =
          when {
            endpoint.kind == SherpaWakeEndpointKind.Expired && segmentStart >= 0L -> "hit-expired"
            endpoint.kind == SherpaWakeEndpointKind.Expired -> "no-hit-expired"
            else -> "no-hit-endpoint"
          }
        val snapshot = buffer.snapshot()
        snapshot.audioBetween(maxOf(kwsReplayStartSample, snapshot.firstSample), snapshot.nextSample)?.let { audio ->
          Log.d(TAG, "wake terminal=$terminalReason kind=${endpoint.kind} durationMs=${audio.pcm16.size * 1_000L / (SAMPLE_RATE_HZ * 2)} ${sherpaWakeLevelSummary(audio.pcm16)}")
        }
        if (endpoint.kind == SherpaWakeEndpointKind.Expired) Log.w(TAG, "wake terminal=$terminalReason; rearming without a clipped seed")
        resetGate()
        return
      }
      val tail = state.claimQueuedTail() ?: return
      buffer.appendQueuedTail(tail)
      val endpointSnapshot = buffer.snapshot()
      val audio = endpointSnapshot.audioBetween(segmentStart, endpoint.endSample)
      if (audio == null || !emitted.compareAndSet(false, true)) {
        state.abandonQueuedTail()
        resetGate()
        return
      }
      Log.i(TAG, "wake handoff segmentStart=$segmentStart endpoint=${endpoint.endSample} snapshotNext=${endpointSnapshot.nextSample} seedBytes=${audio.pcm16.size} queuePendingMs=${tail.pendingMs} generation=${state.generation}")
      mainHandler.post { if (state.isCurrent()) callbacks.emit(VoiceWakeRecognitionEvent.Wake(audio)) }
    }
    fun feedVad(pcm16: ByteArray, firstSample: Long, state: VoskWakeCaptureState) {
      val combined = ByteArray(vadRemainder.size + pcm16.size)
      vadRemainder.copyInto(combined)
      pcm16.copyInto(combined, vadRemainder.size)
      val combinedFirstSample = firstSample - vadRemainder.size / 2L
      var offset = 0
      vadGateResetDuringFeed = false
      while (offset + VAD_WINDOW_SAMPLES * 2 <= combined.size && !emitted.get()) {
        vadGainHistory.append(combined, offset, VAD_WINDOW_SAMPLES * 2)
        sherpaWakeVadSamplesInto(combined, offset, VAD_WINDOW_SAMPLES, vadGainHistory.gain(), vadSamples)
        vad.acceptWaveform(vadSamples)
        val speech = vad.isSpeechDetected()
        // Echo's continuous KWS does not await a VAD endpoint before a keyword candidate.
        // Completed native VAD segments are therefore diagnostic-only and must not accumulate.
        if (continuousKws) drainCompletedVadSegments()
        val start = combinedFirstSample + offset / 2L
        if (!continuousKws && awaitingEndpoint == null && speech) beginKwsAtVadOnset(start)
        if (continuousKws && continuousFallbackEndpoint && speech && segmentStart >= 0L) {
          awaitingEndpoint = SherpaWakeEndpointController(SAMPLE_RATE_HZ, segmentStart, trailingSilenceMs = 0).also { it.rebaseOnKeyword(segmentStart) }
          continuousFallbackEndpoint = false
        }
        awaitingEndpoint?.observe(start, start + VAD_WINDOW_SAMPLES, speech)?.let { emitEndpoint(it, state) }
        offset += VAD_WINDOW_SAMPLES * 2
        // A bounded no-hit/expired segment starts its next VAD stream on the following
        // capture callback; preserve its unprocessed raw samples in the monotonic buffer.
        if (vadGateResetDuringFeed) break
      }
      vadRemainder = combined.copyOfRange(offset, combined.size)
      vadGateResetDuringFeed = false
    }
    val capture = try { SherpaWakeAudioCapture(SAMPLE_RATE_HZ, audioSource, frame@{ pcm16, state ->
      if (emitted.get()) return@frame
      val firstSample = buffer.nextSample()
      buffer.append(pcm16)
      if (continuousKws) observeContinuousKws(pcm16)
      // After the conditioned onset replay, only each subsequent capture frame reaches KWS once.
      else if (kwsReplayStartSample >= 0L && segmentStart < 0L) observeKws(pcm16)
      feedVad(pcm16, firstSample, state)
    }, {
      resetToKws()
      Log.w(TAG, "wake discontinuity reset=true")
    }, {
      mainHandler.post { callbacks.emit(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT)) }
    }) } catch (_: Exception) {
      runCatching { stream.release() }
      runCatching { vad.release() }
      callbacks.retire()
      synchronized(lock) { if (pending?.first == request.first) pending = null }
      reportStartFailure(request)
      return
    }
    val session = Active(request.first, stream, vad, capture, callbacks)
    val admitted = synchronized(lock) {
      if (draining || active != null || pending?.first != request.first || latestOperationId != request.first) false else { active = session; pending = null; true }
    }
    if (!admitted) { session.close(); return }
    if (!capture.start()) {
      val retired = synchronized(lock) { if (active === session) retireLocked() else null }
      retired?.let { closeRetired(it) { reportStartFailure(request) } }
    }
    else {
      Log.i(TAG, "wake audio capture started source=$audioSource bufferFrames=${capture.bufferFrames()} queueCapacityFrames=40 queueCapacityMs=4000")
      callbacks.emit(VoiceWakeRecognitionEvent.Ready)
    }
  }

  private fun retireLocked(): Active? = active.also {
    active = null
    if (it != null) {
      draining = true
      it.callbacks.retire()
    }
  }

  private fun reportStartFailure(request: Pair<Long, (VoiceWakeRecognitionEvent) -> Unit>) {
    mainHandler.post {
      if (synchronized(lock) { latestOperationId == request.first }) {
        request.second(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
      }
    }
  }

  private fun closeRetired(retired: Active, afterClose: (() -> Unit)? = null) {
    retired.close {
      val latestPending = synchronized(lock) {
        draining = false
        pending?.first
      }
      afterClose?.invoke()
      latestPending?.let(::beginStart)
    }
  }

  private fun Active.close(afterClose: (() -> Unit)? = null) =
    capture.closeAfterWorker {
      runCatching { stream.release() }
      runCatching { vad.release() }
      afterClose?.invoke()
    }

  companion object {
    const val TAG = "OpenClawKws"
    const val SAMPLE_RATE_HZ = 16_000
    const val VAD_WINDOW_SAMPLES = 512
    const val VAD_SPEECH_THRESHOLD = 0.5f
    fun vadConfig() = VadModelConfig().apply {
      sileroVadModelConfig.model = "jarvis/wake-model/silero-vad.onnx"
      sileroVadModelConfig.threshold = VAD_SPEECH_THRESHOLD
      // Short standalone names must reach KWS; the full phoneme match still owns waking.
      // 250 ms rejected brief natural Jarvis calls before the keyword model saw them.
      sileroVadModelConfig.minSpeechDuration = 0.05f
      sileroVadModelConfig.minSilenceDuration = 0.5f
      // The app retains and bounds a complete twelve-second utterance itself.
      sileroVadModelConfig.maxSpeechDuration = SHERPA_WAKE_NATIVE_VAD_MAX_SPEECH_SECONDS
      sileroVadModelConfig.windowSize = VAD_WINDOW_SAMPLES
      sampleRate = SAMPLE_RATE_HZ
      numThreads = 1
    }
  }
}

internal fun pcm16ToFloat(source: ByteArray, offset: Int, samples: Int): FloatArray =
  FloatArray(samples) { index ->
    val byteIndex = offset + index * 2
    (((source[byteIndex].toInt() and 0xff) or (source[byteIndex + 1].toInt() shl 8)).toShort().toInt() / 32768.0f)
  }

/**
 * Keeps the exact trailing PCM bytes that the allocating implementation used
 * for VAD gain selection, without producing a new history array every 32 ms.
 */
internal class SherpaWakeVadGainHistory(private val capacityBytes: Int) {
  private val bytes = ByteArray(capacityBytes)
  private val gainRms = FloatArray(capacityBytes / SHERPA_WAKE_VAD_WINDOW_BYTES)
  private val gainPeaks = FloatArray(capacityBytes / SHERPA_WAKE_VAD_WINDOW_BYTES)
  private var gainFactStart = 0
  private var gainFactCount = 0
  private var size = 0

  init {
    require(capacityBytes > 0 && capacityBytes % 2 == 0)
  }

  fun append(source: ByteArray, offset: Int, length: Int) {
    require(offset >= 0 && length >= 0 && offset + length <= source.size)
    val validLength = length and -2
    if (validLength == 0) return
    if (validLength >= capacityBytes) {
      source.copyInto(bytes, 0, offset + validLength - capacityBytes, offset + validLength)
      size = capacityBytes
      rebuildGainFacts()
      return
    }
    val previousSize = size
    val drop = (previousSize + validLength - capacityBytes).coerceAtLeast(0)
    if (drop > 0) System.arraycopy(bytes, drop, bytes, 0, size - drop)
    val retained = size - drop
    source.copyInto(bytes, retained, offset, offset + validLength)
    size = retained + validLength
    // VAD always appends 512 PCM16 samples. Once the initial 320-sample trim
    // has aligned this bounded history, the fixed ring replaces one oldest
    // metric. Other input shapes rebuild from retained raw bytes exactly.
    if (
      previousSize == capacityBytes &&
        drop == SHERPA_WAKE_VAD_WINDOW_BYTES &&
        validLength == SHERPA_WAKE_VAD_WINDOW_BYTES &&
        gainFactCount == fullGainWindows()
    ) {
      measureGainFact(gainFactStart, (fullGainWindows() - 1) * SHERPA_WAKE_VAD_WINDOW_BYTES)
      gainFactStart = (gainFactStart + 1) % gainRms.size
    } else {
      rebuildGainFacts()
    }
  }

  fun reset() {
    size = 0
    gainFactStart = 0
    gainFactCount = 0
  }

  fun gain(): Float {
    var maximumWindowRms = 0f
    var maximumAbs = 0f
    repeat(gainFactCount) { relativeIndex ->
      val index = (gainFactStart + relativeIndex) % gainRms.size
      maximumWindowRms = maxOf(maximumWindowRms, gainRms[index])
      maximumAbs = maxOf(maximumAbs, gainPeaks[index])
    }
    return sherpaWakeVadGainFromMaximums(maximumWindowRms, maximumAbs)
  }

  internal fun snapshotForTest(): ByteArray = bytes.copyOf(size)

  private fun fullGainWindows(): Int = size / SHERPA_WAKE_VAD_WINDOW_BYTES

  private fun rebuildGainFacts() {
    gainFactStart = 0
    gainFactCount = fullGainWindows()
    repeat(gainFactCount) { index ->
      measureGainFact(index, index * SHERPA_WAKE_VAD_WINDOW_BYTES)
    }
  }

  private fun measureGainFact(destinationIndex: Int, sourceOffset: Int) {
    var squareSum = 0.0
    var peak = 0f
    repeat(SherpaJarvisVoiceWakeRecognizer.VAD_WINDOW_SAMPLES) { index ->
      val byteIndex = sourceOffset + index * 2
      val sample = ((bytes[byteIndex].toInt() and 0xff) or (bytes[byteIndex + 1].toInt() shl 8)).toShort().toInt() / 32768f
      squareSum += sample * sample
      peak = maxOf(peak, abs(sample))
    }
    gainRms[destinationIndex] = sqrt(squareSum / SherpaJarvisVoiceWakeRecognizer.VAD_WINDOW_SAMPLES).toFloat()
    gainPeaks[destinationIndex] = peak
  }
}

private const val SHERPA_WAKE_VAD_WINDOW_BYTES = SherpaJarvisVoiceWakeRecognizer.VAD_WINDOW_SAMPLES * 2

/**
 * Derives a bounded, peak-safe gain from causal raw history. It is recomputed for each VAD
 * window; the retained PCM and relay seed remain raw even when the KWS inference view uses it.
 */
internal fun sherpaWakeVadGain(pcm16: ByteArray, offset: Int = 0, length: Int = pcm16.size - offset): Float {
  require(offset >= 0 && length >= 0 && offset + length <= pcm16.size)
  var maximumWindowRms = 0f
  var maximumAbs = 0f
  val endOffset = offset + (length and -2)
  var windowOffset = offset
  while (windowOffset + SHERPA_WAKE_VAD_WINDOW_BYTES <= endOffset) {
    var squareSum = 0.0
    var peak = 0f
    repeat(SherpaJarvisVoiceWakeRecognizer.VAD_WINDOW_SAMPLES) { index ->
      val byteIndex = windowOffset + index * 2
      val sample = ((pcm16[byteIndex].toInt() and 0xff) or (pcm16[byteIndex + 1].toInt() shl 8)).toShort().toInt() / 32768f
      squareSum += sample * sample
      peak = maxOf(peak, abs(sample))
    }
    maximumWindowRms = maxOf(
      maximumWindowRms,
      sqrt(squareSum / SherpaJarvisVoiceWakeRecognizer.VAD_WINDOW_SAMPLES).toFloat(),
    )
    maximumAbs = maxOf(maximumAbs, peak)
    windowOffset += SHERPA_WAKE_VAD_WINDOW_BYTES
  }
  return sherpaWakeVadGainFromMaximums(maximumWindowRms, maximumAbs)
}

private fun sherpaWakeVadGainFromMaximums(maximumWindowRms: Float, maximumAbs: Float): Float {
  if (maximumWindowRms == 0f) return 1f
  val requestedGain = (0.06f / maximumWindowRms).coerceIn(1f, 16f)
  val peakSafeGain = if (maximumAbs == 0f) 16f else 0.95f / maximumAbs
  return minOf(requestedGain, peakSafeGain).coerceAtLeast(1f)
}

internal fun sherpaWakeVadSamples(source: ByteArray, offset: Int, samples: Int, gain: Float): FloatArray =
  FloatArray(samples).also { destination -> sherpaWakeVadSamplesInto(source, offset, samples, gain, destination) }

internal fun sherpaWakeVadSamplesInto(
  source: ByteArray,
  offset: Int,
  samples: Int,
  gain: Float,
  destination: FloatArray,
) {
  require(samples >= 0 && samples <= destination.size)
  require(offset >= 0 && offset + samples * 2 <= source.size)
  repeat(samples) { index ->
    val byteIndex = offset + index * 2
    val raw = ((source[byteIndex].toInt() and 0xff) or (source[byteIndex + 1].toInt() shl 8)).toShort().toInt() / 32768f
    destination[index] = (raw * gain).coerceIn(-1f, 1f)
  }
}

/**
 * Conditions only the KWS inference view with the causal, peak-safe gain already selected for
 * VAD. The retained PCM and Talk seed remain raw so this cannot alter the spoken command.
 */
/**
 * Feeds one retained raw replay through a bounded conversion buffer. Sherpa retains stream
 * position across calls, so [accept] returns its native cumulative marker unchanged.
 */
internal fun sherpaWakeAcceptRawPcmInChunks(
  rawPcm16: ByteArray,
  gain: Float,
  scratch: FloatArray,
  accept: (FloatArray, Int) -> Long?,
): Long? {
  require(rawPcm16.size % 2 == 0)
  require(scratch.isNotEmpty())
  val totalSamples = rawPcm16.size / 2
  var offsetSamples = 0
  while (offsetSamples < totalSamples) {
    val sampleCount = minOf(scratch.size, totalSamples - offsetSamples)
    sherpaWakeKwsSamplesInto(rawPcm16, offsetSamples * 2, sampleCount, gain, scratch)
    accept(scratch, sampleCount)?.let { return it }
    offsetSamples += sampleCount
  }
  return null
}

internal fun sherpaWakeKwsSamplesInto(
  source: ByteArray,
  offset: Int,
  samples: Int,
  gain: Float,
  destination: FloatArray,
) {
  require(samples >= 0 && samples <= destination.size)
  require(offset >= 0 && offset + samples * 2 <= source.size)
  repeat(samples) { index ->
    val byteIndex = offset + index * 2
    val raw = ((source[byteIndex].toInt() and 0xff) or (source[byteIndex + 1].toInt() shl 8)).toShort().toInt() / 32768f
    destination[index] = (raw * gain).coerceIn(-1f, 1f)
  }
}

/** Causal Echo-only KWS conditioning: each frame uses its own peak and prior gain only. */
internal class SherpaWakeContinuousKwsLimiter(
  private val frameDurationMs: Long,
  private val recoveryTauMs: Double = 3_000.0,
) {
  private var previousGain = 16f

  fun nextGain(pcm16: ByteArray): Float {
    var peak = 0
    var offset = 0
    while (offset + 1 < pcm16.size) {
      val sample = ((pcm16[offset].toInt() and 0xff) or (pcm16[offset + 1].toInt() shl 8)).toShort().toInt()
      peak = maxOf(peak, abs(sample))
      offset += 2
    }
    val peakSafe = if (peak == 0) 16f else 0.95f / (peak.toFloat() / 32_768f)
    val recovered = previousGain * exp(frameDurationMs / recoveryTauMs).toFloat()
    return minOf(peakSafe, 16f, recovered).also { previousGain = it }
  }

  fun reset() {
    previousGain = 16f
  }
}

/** Negative reads mean a dead recorder; isolated empty reads may recover without replacement. */
internal class SherpaWakeReadHealth(private val maxEmptyReadMs: Long = 2_000L) {
  private var emptySinceMs: Long? = null

  fun requiresRestart(count: Int, nowMs: Long): Boolean {
    if (count < 0) return true
    if (count > 0) {
      emptySinceMs = null
      return false
    }
    val since = emptySinceMs ?: nowMs.also { emptySinceMs = it }
    return nowMs - since >= maxEmptyReadMs
  }
}

/**
 * Owns one capture generation's no-PCM deadline. It is deliberately separate from
 * [SherpaWakeReadHealth]: zero-length reads retain their existing short recovery behavior,
 * while a read that never returns needs a watchdog outside the capture thread.
 */
internal class SherpaWakeCaptureWatchdog(private val maxNoPcmMs: Long = SHERPA_WAKE_BLOCKED_READ_TIMEOUT_MS) {
  private val nextGeneration = AtomicLong(0L)
  private val activeGeneration = AtomicLong(0L)
  private val lastPositiveReadMs = AtomicLong(Long.MIN_VALUE)

  fun begin(nowMs: Long): Long {
    val generation = nextGeneration.incrementAndGet()
    lastPositiveReadMs.set(nowMs)
    activeGeneration.set(generation)
    return generation
  }

  fun recordPositiveRead(generation: Long, nowMs: Long) {
    if (activeGeneration.get() == generation) lastPositiveReadMs.set(nowMs)
  }

  fun requiresRecovery(generation: Long, nowMs: Long, captureRunning: Boolean): Boolean =
    captureRunning && activeGeneration.get() == generation &&
      nowMs - lastPositiveReadMs.get() >= maxNoPcmMs

  fun cancel(generation: Long) {
    activeGeneration.compareAndSet(generation, 0L)
  }
}

private fun sherpaWakeLevelSummary(pcm16: ByteArray): String {
  var sum = 0.0
  var peak = 0
  var offset = 0
  while (offset + 1 < pcm16.size) {
    val sample = ((pcm16[offset].toInt() and 0xff) or (pcm16[offset + 1].toInt() shl 8)).toShort().toInt()
    sum += sample.toDouble() * sample
    peak = maxOf(peak, abs(sample))
    offset += 2
  }
  val rms = sqrt(sum / (pcm16.size / 2).coerceAtLeast(1)).toInt()
  return "rawRms=$rms rawPeak=$peak"
}

private class SherpaWakeCaptureHealth(startedAtMs: Long) {
  data class Snapshot(
    val frames: Long,
    val bytes: Long,
    val rms: Int,
    val peak: Int,
    val allZeroFrames: Long,
    val maxPositiveReadGapMs: Long,
  )

  private var windowStartedAtMs = startedAtMs
  private var lastPositiveReadAtMs: Long? = null
  private var frames = 0L
  private var bytes = 0L
  private var samples = 0L
  private var squaredSamples = 0L
  private var peak = 0
  private var allZeroFrames = 0L
  private var maxPositiveReadGapMs = 0L

  /** Returns one aggregate per five seconds; it never retains raw PCM. */
  fun observe(pcm16: ByteArray, length: Int, nowMs: Long): Snapshot? {
    lastPositiveReadAtMs?.let { previous ->
      maxPositiveReadGapMs = maxOf(maxPositiveReadGapMs, nowMs - previous)
    }
    lastPositiveReadAtMs = nowMs
    frames += 1
    bytes += length
    var framePeak = 0
    var offset = 0
    val limit = length - (length % 2)
    while (offset < limit) {
      val sample = ((pcm16[offset].toInt() and 0xff) or (pcm16[offset + 1].toInt() shl 8)).toShort().toInt()
      val magnitude = abs(sample)
      framePeak = maxOf(framePeak, magnitude)
      peak = maxOf(peak, magnitude)
      squaredSamples += sample.toLong() * sample
      samples += 1
      offset += 2
    }
    if (framePeak == 0) allZeroFrames += 1
    if (nowMs - windowStartedAtMs < 5_000L) return null
    val snapshot =
      Snapshot(
        frames = frames,
        bytes = bytes,
        rms = sqrt(squaredSamples.toDouble() / samples.coerceAtLeast(1L)).toInt(),
        peak = peak,
        allZeroFrames = allZeroFrames,
        maxPositiveReadGapMs = maxPositiveReadGapMs,
      )
    windowStartedAtMs = nowMs
    frames = 0L
    bytes = 0L
    samples = 0L
    squaredSamples = 0L
    peak = 0
    allZeroFrames = 0L
    maxPositiveReadGapMs = 0L
    return snapshot
  }
}

/** Reuses the 222 queue/generation contract while KWS decoding stays off AudioRecord. */
private class SherpaWakeAudioCapture(
  private val sampleRateHz: Int,
  private val audioSource: Int,
  private val onFrame: (ByteArray, VoskWakeCaptureState) -> Unit,
  private val onDiscontinuity: () -> Unit,
  private val onError: () -> Unit,
) {
  private val running = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private val closeBarrier = VoskWakeCaptureCloseBarrier()
  private val queue = VoskWakeDecodeQueue(capacityFrames = 40, frameDurationMs = 100L)
  private val liveness = SherpaWakeCaptureWatchdog()
  private val record: AudioRecord
  private var captureWorker: Thread? = null
  private var decoderWorker: Thread? = null
  private var watchdogWorker: Thread? = null
  @Volatile private var watchdogGeneration = 0L

  init {
    val frameBytes = sampleRateHz * 2 * SHERPA_WAKE_CAPTURE_FRAME_MS.toInt() / 1_000
    val minimum = AudioRecord.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0) { "Wake AudioRecord buffer unavailable" }
    record =
      AudioRecord.Builder()
        .setAudioSource(audioSource)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRateHz).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
        .setBufferSizeInBytes(maxOf(minimum, frameBytes * 4))
        .build()
  }

  fun start(): Boolean {
    if (closed.get() || !running.compareAndSet(false, true)) return false
    return try {
      record.startRecording()
      check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
      closeBarrier.markStarted()
      val generation = liveness.begin(SystemClock.elapsedRealtime())
      watchdogGeneration = generation
      Thread(::decodeLoop, "OpenClawSherpaWakeDecode").also { decoderWorker = it; it.start() }
      Thread({ captureLoop(generation) }, "OpenClawSherpaWakeCapture").also { captureWorker = it; it.start() }
      // AudioRecord.read() can block without returning zero or an error. This low-frequency
      // owner-only observer never opens a second recorder and reuses fail() for recovery.
      Thread({ watchdogLoop(generation) }, "OpenClawSherpaWakeWatchdog").also { watchdogWorker = it; it.start() }
      true
    } catch (_: Throwable) {
      running.set(false)
      liveness.cancel(watchdogGeneration)
      runCatching { record.stop() }
      captureWorker?.interrupt()
      decoderWorker?.interrupt()
      watchdogWorker?.interrupt()
      if (captureWorker == null || captureWorker?.state == Thread.State.NEW) closeBarrier.markCaptureFinished()
      if (decoderWorker == null || decoderWorker?.state == Thread.State.NEW) closeBarrier.markDecoderFinished()
      finishClose()
      false
    }
  }

  fun bufferFrames(): Int = record.bufferSizeInFrames

  private fun captureLoop(generation: Long) {
    val frame = ByteArray(sampleRateHz * 2 * SHERPA_WAKE_CAPTURE_FRAME_MS.toInt() / 1_000)
    val readHealth = SherpaWakeReadHealth()
    // This is intentionally opt-in: it proves whether a healthy AudioRecord is returning
    // silence after Talk without retaining PCM or changing the capture/VAD path.
    val captureHealth =
      SherpaWakeCaptureHealth(SystemClock.elapsedRealtime()).takeIf {
        Log.isLoggable(SherpaJarvisVoiceWakeRecognizer.TAG, Log.DEBUG)
      }
    try {
      while (running.get() && !closed.get()) {
        val count = record.read(frame, 0, frame.size)
        // stop() may finish a blocked read with an error: that is an expected retirement.
        if (!running.get() || closed.get()) break
        val now = SystemClock.elapsedRealtime()
        if (readHealth.requiresRestart(count, now)) {
          Log.w(SherpaJarvisVoiceWakeRecognizer.TAG, "wake recorder failed read=$count state=${record.recordingState}; rearming")
          fail()
          break
        }
        if (count == 0) {
          Thread.sleep(20L)
          continue
        }
        liveness.recordPositiveRead(generation, now)
        captureHealth?.observe(frame, count, now)?.let { health ->
          val clientSilenced =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              runCatching { record.activeRecordingConfiguration?.isClientSilenced }.getOrNull()
            } else {
              null
            }
          Log.d(
            SherpaJarvisVoiceWakeRecognizer.TAG,
            "wake capture-health generation=$generation routedDeviceType=${record.routedDevice?.type ?: "none"} recordingState=${record.recordingState} clientSilenced=${clientSilenced ?: "unknown"} frames=${health.frames} bytes=${health.bytes} rawRms=${health.rms} rawPeak=${health.peak} allZeroFrames=${health.allZeroFrames} maxPositiveReadGapMs=${health.maxPositiveReadGapMs}",
          )
        }
        queue.enqueue(frame.copyOf(count))
      }
    } catch (_: Throwable) { fail() } finally {
      liveness.cancel(generation)
      running.set(false); closeBarrier.markCaptureFinished(); decoderWorker?.interrupt(); finishClose()
    }
  }

  private fun watchdogLoop(generation: Long) {
    try {
      while (running.get() && !closed.get()) {
        Thread.sleep(SHERPA_WAKE_WATCHDOG_POLL_MS)
        if (!liveness.requiresRecovery(generation, SystemClock.elapsedRealtime(), running.get() && !closed.get())) continue
        Log.w(SherpaJarvisVoiceWakeRecognizer.TAG, "wake recorder stalled without PCM for ${SHERPA_WAKE_BLOCKED_READ_TIMEOUT_MS}ms; rearming")
        fail()
        return
      }
    } catch (_: InterruptedException) {
      // closeAfterWorker owns cancellation; no error is emitted for a retired capture.
    } finally {
      liveness.cancel(generation)
    }
  }

  private fun decodeLoop() {
    var generation = queue.currentGeneration()
    try {
      while (running.get() && !closed.get()) {
        val frame = queue.poll(100) ?: continue
        if (!queue.isCurrent(frame.generation)) continue
        if (frame.generation != generation) { onDiscontinuity(); generation = frame.generation }
        val state = VoskWakeCaptureState(
          generation = frame.generation,
          pendingMs = queue.pendingMs(),
          isCurrent = { canEmitVoskWakeCallback(running.get(), closed.get(), queue.isCurrent(frame.generation)) },
          claimQueuedTail = { if (closed.get()) null else queue.claimQueuedTail(frame.generation) },
          abandonQueuedTail = { queue.abandonQueuedTail(frame.generation) },
        )
        // Native KWS/VAD work must never hold the queue ownership lock.  The
        // producer can therefore continue during a slow decoder call; the
        // callback state fences any generation that changes meanwhile.
        if (queue.isCurrent(frame.generation)) onFrame(frame.pcm16, state)
      }
    } catch (error: Throwable) {
      if (running.get() && !closed.get()) {
        Log.w(SherpaJarvisVoiceWakeRecognizer.TAG, "wake decoder failed: ${error.message ?: error::class.java.simpleName}")
      }
      fail()
    } finally {
      closeBarrier.markDecoderFinished(); finishClose()
    }
  }

  private fun fail() {
    if (running.getAndSet(false) && !closed.get()) { runCatching { record.stop() }; onError() }
  }

  fun closeAfterWorker(onStopped: () -> Unit) {
    if (!closed.compareAndSet(false, true)) return
    closeBarrier.setOnStopped(onStopped)
    liveness.cancel(watchdogGeneration)
    running.set(false)
    runCatching { record.stop() }
    queue.clear()
    captureWorker?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    watchdogWorker?.interrupt()
    watchdogWorker?.takeIf { it !== Thread.currentThread() }?.join(500)
    decoderWorker?.interrupt()
    decoderWorker?.takeIf { it !== Thread.currentThread() }?.join(2_000)
    finishClose()
  }

  private fun finishClose() {
    closeBarrier.takeCompletionIfFinished()?.let { completion -> runCatching { record.release() }; completion() }
  }
}
