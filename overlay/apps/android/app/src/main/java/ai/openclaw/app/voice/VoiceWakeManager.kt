package ai.openclaw.app.voice

import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.resolveNativeText
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

internal sealed interface VoiceWakeRecognitionEvent {
  data object Ready : VoiceWakeRecognitionEvent

  /** A recognizer confirmed its built-in wake phrase before an utterance finished. */
  data class Wake(
    val initialAudio: VoiceWakeAudio? = null,
  ) : VoiceWakeRecognitionEvent

  data class Transcript(
    val text: String,
    val isFinal: Boolean,
  ) : VoiceWakeRecognitionEvent

  data class Error(
    val code: Int,
  ) : VoiceWakeRecognitionEvent
}

// The actual Echo built-in-speaker test finalized the wake at 0.781906. Keep a small measured
// margin while still requiring the complete wake word from the unrestricted decoder.
private const val VOSK_FINAL_WAKE_CONFIDENCE = 0.75

/**
 * Vosk's unrestricted decoder can emit repeated `jarvis` partials for unrelated programme audio.
 * A completed word with its decoder confidence is the only wake authority; partials are retained
 * solely as a private diagnostic signal.
 */
internal class VoskWakePhraseGate(
  private val wakePhrase: String,
) {
  private var wakeEmitted = false
  private var partialCandidates = 0

  fun acceptPartial(hypothesis: String): Boolean {
    if (hypothesisContainsWakePhrase(hypothesis, "partial", wakePhrase)) partialCandidates += 1
    return false
  }

  fun acceptFinal(hypothesis: String): Boolean {
    val accepted = !wakeEmitted && finalizedHypothesisConfirmsWakePhrase(hypothesis, wakePhrase)
    if (accepted) wakeEmitted = true
    return accepted
  }

  fun drainPartialCandidateCount(): Int = partialCandidates.also { partialCandidates = 0 }

  fun reset() {
    wakeEmitted = false
    partialCandidates = 0
  }
}

internal fun hypothesisContainsWakePhrase(
  hypothesis: String,
  fieldName: String,
  wakePhrase: String,
): Boolean =
  runCatching {
    JSONObject(hypothesis)
      .optString(fieldName)
      .split(Regex("\\s+"))
      .any { token -> token.equals(wakePhrase, ignoreCase = true) }
  }.getOrDefault(false)

internal fun finalizedHypothesisConfirmsWakePhrase(
  hypothesis: String,
  wakePhrase: String,
): Boolean = (finalizedWakePhraseConfidence(hypothesis, wakePhrase) ?: 0.0) >= VOSK_FINAL_WAKE_CONFIDENCE

/** Returns only the configured wake-word score; it never exposes surrounding transcript text. */
internal fun finalizedWakePhraseConfidence(
  hypothesis: String,
  wakePhrase: String,
): Double? =
  runCatching {
    val words = JSONObject(hypothesis).optJSONArray("result") ?: return@runCatching null
    (0 until words.length()).firstNotNullOfOrNull { index ->
      val word = words.optJSONObject(index) ?: return@firstNotNullOfOrNull null
      word.takeIf { it.optString("word").equals(wakePhrase, ignoreCase = true) }?.optDouble("conf", 0.0)
    }
  }.getOrNull()

internal data class FinalizedWakeWord(
  val startSample: Long,
  val endSample: Long,
)

/** A bounded PCM view whose sample positions remain relative to the original Vosk stream. */
internal data class VoskWakeAudioSnapshot(
  val firstSample: Long,
  val nextSample: Long,
  val pcm16: ByteArray,
  val sampleRateHz: Int,
) {
  /**
   * Produces only the audio after a fully verified wake word. Refusing an
   * evicted wake is intentional: replaying a clipped command tail is worse
   * than asking the user to repeat it.
   */
  fun audioAfterWake(finalizedWake: FinalizedWakeWord?): VoiceWakeAudio? {
    finalizedWake ?: return null
    if (
      finalizedWake.startSample < firstSample ||
      finalizedWake.endSample < finalizedWake.startSample ||
      finalizedWake.endSample > nextSample
    ) return null
    val startOffsetBytes = ((finalizedWake.endSample - firstSample) * 2L).toInt()
    return pcm16
      .copyOfRange(startOffsetBytes, pcm16.size)
      .takeIf { it.isNotEmpty() }
      ?.let { VoiceWakeAudio(it, sampleRateHz) }
  }

  fun appendQueuedTail(tail: VoskWakeQueuedTail): VoskWakeAudioSnapshot {
    val tailBytes = tail.frames.sumOf { it.pcm16.size }
    if (tailBytes == 0) return this
    val combined = ByteArray(pcm16.size + tailBytes)
    pcm16.copyInto(combined)
    var destination = pcm16.size
    tail.frames.forEach { frame ->
      frame.pcm16.copyInto(combined, destination)
      destination += frame.pcm16.size
    }
    val maxBytes = (sampleRateHz * 2L * VOSK_WAKE_AUDIO_MAX_DURATION_MS / 1_000L).toInt()
    val discardedBytes = (combined.size - maxBytes).coerceAtLeast(0) and -2
    return VoskWakeAudioSnapshot(
      firstSample = firstSample + discardedBytes / 2L,
      nextSample = nextSample + tailBytes / 2L,
      pcm16 = combined.copyOfRange(discardedBytes, combined.size),
      sampleRateHz = sampleRateHz,
    )
  }

}

internal data class VoskPrefixVerificationRequest(
  val generation: Long,
  val snapshot: VoskWakeAudioSnapshot,
)

internal data class VoskPrefixWakeHandoff(
  val endpoint: VoskWakeAudioSnapshot,
  val finalizedWake: FinalizedWakeWord,
  val generation: Long,
)

/** Shared by recognizer sessions so a retired session cannot leave a second decoder running. */
internal class VoskPrefixVerifierSingleFlight {
  private val inFlight = AtomicBoolean(false)

  fun tryStart(): Boolean = inFlight.compareAndSet(false, true)

  fun finish() {
    inFlight.set(false)
  }
}

/**
 * Holds one independently verified partial candidate until the main Vosk
 * stream reaches its usual endpoint. A raw partial never wakes the device.
 */
internal class VoskPrefixWakeCoordinator(
  private val wakePhrase: String,
  private val stableFramesRequired: Int = 3,
) {
  private var generation = 0L
  private var stableFrames = 0
  private var verificationStarted = false
  private var request: VoskPrefixVerificationRequest? = null
  private var verifiedWake: FinalizedWakeWord? = null
  private var endpoint: VoskWakeAudioSnapshot? = null

  @Synchronized
  fun onPartial(
    hypothesis: String,
    snapshot: () -> VoskWakeAudioSnapshot,
  ): VoskPrefixVerificationRequest? {
    if (endpoint != null || verificationStarted) return null
    if (!hypothesisContainsWakePhrase(hypothesis, "partial", wakePhrase)) {
      stableFrames = 0
      return null
    }
    stableFrames += 1
    if (stableFrames < stableFramesRequired) return null
    verificationStarted = true
    return VoskPrefixVerificationRequest(generation, snapshot()).also { request = it }
  }

  /** Called at every ordinary main-recognizer endpoint. */
  @Synchronized
  fun onFinal(snapshot: VoskWakeAudioSnapshot): VoskPrefixWakeHandoff? {
    // A second endpoint before the independent decoder returned belongs to a
    // later utterance. It must not receive the earlier candidate's audio.
    if (endpoint != null) {
      Log.i("OpenClawVoskPrefix", "prefix stale discard reason=second_endpoint")
      resetForNextUtterance()
      return null
    }
    if (!verificationStarted) {
      resetForNextUtterance()
      return null
    }
    endpoint = snapshot
    return consumeVerifiedHandoff()
  }

  @Synchronized
  fun onVerificationComplete(
    candidate: VoskPrefixVerificationRequest,
    finalizedWake: FinalizedWakeWord?,
  ): VoskPrefixWakeHandoff? {
    if (candidate !== request || candidate.generation != generation) {
      Log.i("OpenClawVoskPrefix", "prefix stale discard reason=generation")
      return null
    }
    if (finalizedWake == null) {
      // Once the user has reached an endpoint, a failed verifier must release
      // the next utterance immediately instead of waiting for another final.
      if (endpoint != null) resetForNextUtterance()
      return null
    }
    verifiedWake = finalizedWake
    return consumeVerifiedHandoff()
  }

  @Synchronized
  fun discardCurrent() {
    if (verificationStarted) Log.i("OpenClawVoskPrefix", "prefix stale discard reason=session_retired")
    resetForNextUtterance()
  }

  private fun consumeVerifiedHandoff(): VoskPrefixWakeHandoff? {
    val finalEndpoint = endpoint ?: return null
    val finalWake = verifiedWake ?: return null
    return VoskPrefixWakeHandoff(finalEndpoint, finalWake, generation).also { resetForNextUtterance() }
  }

  private fun resetForNextUtterance() {
    generation += 1
    stableFrames = 0
    verificationStarted = false
    request = null
    verifiedWake = null
    endpoint = null
  }
}

/**
 * Verifies a stable partial on a private recognizer. Its timestamps start at
 * the snapshot boundary, so map the accepted word back to the main stream's
 * absolute sample clock before handing it to the endpoint snapshot.
 */
internal fun verifyVoskWakePrefix(
  model: Model,
  candidate: VoskPrefixVerificationRequest,
  wakePhrase: String,
  isSessionActive: () -> Boolean,
): FinalizedWakeWord? {
  if (!isSessionActive()) return null
  val snapshot = candidate.snapshot
  val verifier =
    runCatching {
      Recognizer(model, snapshot.sampleRateHz.toFloat()).also { it.setWords(true) }
    }.getOrNull() ?: return null
  try {
    var offset = 0
    while (offset < snapshot.pcm16.size && isSessionActive()) {
      val end = minOf(offset + VOSK_PREFIX_VERIFIER_FRAME_BYTES, snapshot.pcm16.size)
      val frame = snapshot.pcm16.copyOfRange(offset, end)
      verifier.acceptWaveForm(frame, frame.size)
      offset = end
    }
    if (!isSessionActive()) return null
    val result = verifier.finalResult
    if (!finalizedHypothesisConfirmsWakePhrase(result, wakePhrase)) return null
    val relative = finalizedWakeWord(result, wakePhrase, snapshot.sampleRateHz) ?: return null
    return FinalizedWakeWord(
      startSample = snapshot.firstSample + relative.startSample,
      endSample = snapshot.firstSample + relative.endSample,
    )
  } catch (_: Throwable) {
    return null
  } finally {
    runCatching { verifier.close() }
  }
}

private const val VOSK_PREFIX_VERIFIER_FRAME_BYTES = 3_200

/**
 * The Vosk final result gives absolute word timestamps from recognizer start.
 * Keep that parsing here so the capture buffer never needs to
 * retain or log decoded speech.
 */
internal fun finalizedWakeWord(
  hypothesis: String,
  wakePhrase: String,
  sampleRateHz: Int,
): FinalizedWakeWord? =
  runCatching {
    val words = JSONObject(hypothesis).optJSONArray("result") ?: return@runCatching null
    var wakeStartSeconds: Double? = null
    var wakeEndSeconds: Double? = null
    for (index in 0 until words.length()) {
      val word = words.optJSONObject(index) ?: continue
      if (word.optString("word").equals(wakePhrase, ignoreCase = true)) {
        wakeStartSeconds = word.optDouble("start", Double.NaN).takeIf { !it.isNaN() }
        wakeEndSeconds = word.optDouble("end", Double.NaN).takeIf { !it.isNaN() }
        break
      }
    }
    val start = wakeStartSeconds ?: return@runCatching null
    val end = wakeEndSeconds ?: return@runCatching null
    if (end < start) return@runCatching null
    FinalizedWakeWord(
      startSample = (start * sampleRateHz).toLong().coerceAtLeast(0L),
      endSample = (end * sampleRateHz).toLong().coerceAtLeast(0L),
    )
  }.getOrNull()

/**
 * Holds at most one unfinished Vosk stream segment. Absolute sample indices make a
 * Vosk word timestamp useful even after old PCM chunks have been evicted.
 */
internal class VoskWakeUtteranceBuffer(
  private val sampleRateHz: Int,
  maxDurationMs: Long = VOSK_WAKE_AUDIO_MAX_DURATION_MS,
) {
  private data class Chunk(
    val firstSample: Long,
    val pcm16: ByteArray,
  )

  private val maxBytes = (sampleRateHz * 2L * maxDurationMs / 1_000L).toInt()
  private val chunks = ArrayDeque<Chunk>()
  private var retainedBytes = 0
  private var nextSample = 0L

  init {
    require(sampleRateHz > 0)
    require(maxBytes >= 2)
  }

  fun append(pcm16: ByteArray, length: Int) {
    val validLength = length.coerceIn(0, pcm16.size) and -2
    if (validLength == 0) return
    val copy = pcm16.copyOf(validLength)
    chunks.addLast(Chunk(nextSample, copy))
    retainedBytes += copy.size
    nextSample += copy.size / 2L
    while (retainedBytes > maxBytes && chunks.isNotEmpty()) {
      retainedBytes -= chunks.removeFirst().pcm16.size
    }
  }

  fun takeAfterWake(finalizedWake: FinalizedWakeWord?): VoiceWakeAudio? {
    val output = snapshot().audioAfterWake(finalizedWake)
    clear()
    return output
  }

  fun snapshot(): VoskWakeAudioSnapshot {
    val firstSample = chunks.firstOrNull()?.firstSample ?: nextSample
    val output = ByteArray(retainedBytes)
    var destination = 0
    chunks.forEach { chunk ->
      chunk.pcm16.copyInto(output, destination)
      destination += chunk.pcm16.size
    }
    check(destination == output.size)
    return VoskWakeAudioSnapshot(
      firstSample = firstSample,
      nextSample = nextSample,
      pcm16 = output,
      sampleRateHz = sampleRateHz,
    )
  }

  fun clear() {
    chunks.clear()
    retainedBytes = 0
  }

  fun reset() {
    clear()
    nextSample = 0L
  }
}

private const val VOSK_WAKE_AUDIO_MAX_DURATION_MS = 12_000L

/**
 * Small owned replacement for Vosk's SpeechService. It deliberately keeps the
 * same 16 kHz VOICE_RECOGNITION source, while retaining only the active
 * utterance so a confirmed combined wake-and-command can be handed to Talk.
 */
internal data class VoskWakeCaptureState(
  val generation: Long,
  val pendingMs: Long,
  val isCurrent: () -> Boolean,
  val claimQueuedTail: () -> VoskWakeQueuedTail?,
  val abandonQueuedTail: () -> Boolean,
)

internal fun canEmitVoskWakeCallback(
  captureRunning: Boolean,
  captureClosed: Boolean,
  generationCurrent: Boolean,
): Boolean = captureRunning && !captureClosed && generationCurrent

internal data class VoskWakeQueuedFrame(
  val generation: Long,
  val pcm16: ByteArray,
)

internal data class VoskWakeQueuedTail(
  val generation: Long,
  val pendingMs: Long,
  val frames: List<VoskWakeQueuedFrame>,
)

/**
 * Keeps capture independent from Vosk decoding. Overflow starts a fresh stream:
 * no PCM from before the gap can be decoded or handed to Talk after the gap.
 */
internal class VoskWakeDecodeQueue(
  private val capacityFrames: Int,
  private val frameDurationMs: Long,
) {
  private val ownershipLock = Any()
  private val queue = ArrayBlockingQueue<VoskWakeQueuedFrame>(capacityFrames)
  private val generation = AtomicLong(0L)
  private var handoffClaimed = false

  /** @return true when the old stream was discarded due to a bounded overflow. */
  fun enqueue(pcm16: ByteArray): Boolean =
    synchronized(ownershipLock) {
      if (handoffClaimed) return@synchronized false
      val currentGeneration = generation.get()
      if (queue.offer(VoskWakeQueuedFrame(currentGeneration, pcm16))) return@synchronized false
      val nextGeneration = generation.incrementAndGet()
      queue.clear()
      handoffClaimed = false
      check(queue.offer(VoskWakeQueuedFrame(nextGeneration, pcm16))) { "Wake decode queue unexpectedly unavailable" }
      true
    }

  fun poll(timeoutMs: Long): VoskWakeQueuedFrame? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

  fun isCurrent(frameGeneration: Long): Boolean = frameGeneration == generation.get()

  fun currentGeneration(): Long = generation.get()

  fun pendingMs(): Long = queue.size.toLong() * frameDurationMs

  fun runIfCurrent(frameGeneration: Long, block: () -> Unit): Boolean =
    synchronized(ownershipLock) {
      if (frameGeneration != generation.get() || handoffClaimed) return@synchronized false
      block()
      true
    }

  /** Atomically retains every already-captured same-generation frame for the wake seed. */
  fun claimQueuedTail(frameGeneration: Long): VoskWakeQueuedTail? =
    synchronized(ownershipLock) {
      if (frameGeneration != generation.get() || handoffClaimed) return@synchronized null
      handoffClaimed = true
      val frames = mutableListOf<VoskWakeQueuedFrame>()
      val pendingMs = queue.size.toLong() * frameDurationMs
      queue.drainTo(frames)
      VoskWakeQueuedTail(frameGeneration, pendingMs, frames)
    }

  fun abandonQueuedTail(frameGeneration: Long): Boolean =
    synchronized(ownershipLock) {
      if (frameGeneration != generation.get() || !handoffClaimed) return@synchronized false
      handoffClaimed = false
      true
    }

  fun clear() {
    queue.clear()
  }
}

/** Ensures native resources close exactly once after both capture and decode stop. */
internal class VoskWakeCaptureCloseBarrier {
  private val lock = Any()
  private var captureFinished = true
  private var decoderFinished = true
  private var onStopped: (() -> Unit)? = null

  fun markStarted() = synchronized(lock) {
    captureFinished = false
    decoderFinished = false
  }

  fun markCaptureFinished() = synchronized(lock) { captureFinished = true }

  fun markDecoderFinished() = synchronized(lock) { decoderFinished = true }

  fun setOnStopped(callback: () -> Unit) = synchronized(lock) { onStopped = callback }

  fun takeCompletionIfFinished(): (() -> Unit)? =
    synchronized(lock) {
      if (!captureFinished || !decoderFinished) null else onStopped.also { onStopped = null }
    }
}

private class VoskWakeAudioCapture(
  private val recognizer: Recognizer,
  private val sampleRateHz: Int,
  private val onFinal: (String, VoskWakeCaptureState) -> Unit,
  private val onPartial: (String, VoskWakeCaptureState) -> Unit,
  private val onAudio: (ByteArray, Int) -> Unit,
  private val onDiscontinuity: () -> Unit,
  private val onError: () -> Unit,
) {
  private val running = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  private val closeBarrier = VoskWakeCaptureCloseBarrier()
  private val diagnosticsLock = Any()
  private val audioRecord: AudioRecord
  private val captureBufferFrames: Int
  private val queue = VoskWakeDecodeQueue(VOSK_WAKE_DECODE_QUEUE_CAPACITY_FRAMES, VOSK_WAKE_CAPTURE_FRAME_MS.toLong())
  @Volatile private var worker: Thread? = null
  @Volatile private var decoderWorker: Thread? = null
  private var diagnosticSamples = 0L
  private var diagnosticSquareSum = 0L
  private var diagnosticPeak = 0
  private var diagnosticCaptureStartedAtMs = 0L
  private var diagnosticReadTotalMs = 0L
  private var diagnosticReadMaxMs = 0L
  private var diagnosticDecodeTotalMs = 0L
  private var diagnosticDecodeMaxMs = 0L
  private var diagnosticQueueDrops = 0L
  private var reportedOneSecond = false
  private var reportedFiveSeconds = false

  init {
    val frameBytes = sampleRateHz * 2 * VOSK_WAKE_CAPTURE_FRAME_MS / 1_000
    val minimumBuffer = AudioRecord.getMinBufferSize(
      sampleRateHz,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    check(minimumBuffer > 0) { "Wake AudioRecord buffer unavailable" }
    audioRecord =
      AudioRecord
        .Builder()
        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(
          AudioFormat
            .Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build(),
        ).setBufferSizeInBytes(maxOf(minimumBuffer, frameBytes * 4))
        .build()
    captureBufferFrames = audioRecord.bufferSizeInFrames
  }

  fun start(): Boolean {
    if (closed.get() || !running.compareAndSet(false, true)) return false
    return try {
      audioRecord.startRecording()
      check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Wake microphone did not start" }
      diagnosticCaptureStartedAtMs = SystemClock.elapsedRealtime()
      closeBarrier.markStarted()
      Thread(::decodeLoop, "OpenClawVoskWakeDecode").also { thread ->
        decoderWorker = thread
        thread.start()
      }
      Thread(::captureLoop, "OpenClawVoskWake").also { thread ->
        worker = thread
        thread.start()
      }
      true
    } catch (_: Throwable) {
      running.set(false)
      runCatching { audioRecord.stop() }
      decoderWorker?.interrupt()
      worker?.interrupt()
      if (worker == null || worker?.state == Thread.State.NEW) closeBarrier.markCaptureFinished()
      if (decoderWorker == null || decoderWorker?.state == Thread.State.NEW) closeBarrier.markDecoderFinished()
      // The owner calls closeAfterWorker after this failure. Do not release the
      // native recognizer or microphone while a just-started worker can still run.
      finishCloseIfNeeded()
      false
    }
  }

  private fun captureLoop() {
    val frame = ByteArray(sampleRateHz * 2 * VOSK_WAKE_CAPTURE_FRAME_MS / 1_000)
    try {
      while (running.get() && !closed.get()) {
        val readStartedAtMs = SystemClock.elapsedRealtime()
        val count = audioRecord.read(frame, 0, frame.size)
        val readMs = SystemClock.elapsedRealtime() - readStartedAtMs
        if (count <= 0) continue
        val captured = frame.copyOf(count)
        enqueue(captured)
        recordInputDiagnostics(frame, count, readMs)
      }
    } catch (_: Throwable) {
      if (running.get() && !closed.get()) {
        running.set(false)
        runCatching { audioRecord.stop() }
        onError()
      }
    } finally {
      running.set(false)
      closeBarrier.markCaptureFinished()
      decoderWorker?.interrupt()
      finishCloseIfNeeded()
    }
  }

  private fun enqueue(pcm16: ByteArray) {
    if (queue.enqueue(pcm16)) synchronized(diagnosticsLock) { diagnosticQueueDrops += 1 }
  }

  private fun decodeLoop() {
    var decoderGeneration = queue.currentGeneration()
    try {
      while (running.get() && !closed.get()) {
        val queued = queue.poll(VOSK_WAKE_DECODE_POLL_MS) ?: continue
        if (!queue.isCurrent(queued.generation)) continue
        if (queued.generation != decoderGeneration) {
          recognizer.reset()
          onDiscontinuity()
          decoderGeneration = queued.generation
        }
        onAudio(queued.pcm16, queued.pcm16.size)
        val state =
          VoskWakeCaptureState(
            generation = queued.generation,
            pendingMs = pendingQueueMs(),
            isCurrent = {
              canEmitVoskWakeCallback(
                captureRunning = running.get(),
                captureClosed = closed.get(),
                generationCurrent = queue.isCurrent(queued.generation),
              )
            },
            claimQueuedTail = { if (closed.get()) null else queue.claimQueuedTail(queued.generation) },
            abandonQueuedTail = { queue.abandonQueuedTail(queued.generation) },
          )
        val decodeStartedAtMs = SystemClock.elapsedRealtime()
        val hasFinalResult = recognizer.acceptWaveForm(queued.pcm16, queued.pcm16.size)
        if (!queue.isCurrent(queued.generation)) continue
        queue.runIfCurrent(queued.generation) {
          if (hasFinalResult) onFinal(recognizer.result, state) else onPartial(recognizer.partialResult, state)
        }
        recordDecodeDuration(SystemClock.elapsedRealtime() - decodeStartedAtMs)
      }
    } catch (_: Throwable) {
      if (running.get() && !closed.get()) {
        running.set(false)
        runCatching { audioRecord.stop() }
        onError()
      }
    } finally {
      closeBarrier.markDecoderFinished()
      finishCloseIfNeeded()
    }
  }

  /**
   * Native recognition may only close after the AudioRecord worker has exited.
   * A blocked read can outlive the bounded join, so its finally block owns the
   * deferred close rather than racing a native callback stack.
   */
  fun closeAfterWorker(onStopped: () -> Unit) {
    if (!closed.compareAndSet(false, true)) return
    closeBarrier.setOnStopped(onStopped)
    running.set(false)
    runCatching { audioRecord.stop() }
    queue.clear()
    val thread = worker
    if (thread != null && thread !== Thread.currentThread()) {
      runCatching { thread.join(VOSK_WAKE_CAPTURE_STOP_JOIN_MS) }
    }
    val decoder = decoderWorker
    decoder?.interrupt()
    if (decoder != null && decoder !== Thread.currentThread()) {
      runCatching { decoder.join(VOSK_WAKE_CAPTURE_STOP_JOIN_MS) }
    }
    finishCloseIfNeeded()
  }

  private fun finishCloseIfNeeded() {
    val completed = closeBarrier.takeCompletionIfFinished() ?: return
    runCatching { audioRecord.release() }
    runCatching { completed() }
  }

  /** Emits only bounded level statistics; decoded speech and PCM remain private. */
  private fun recordInputDiagnostics(pcm16: ByteArray, length: Int, readMs: Long) {
    synchronized(diagnosticsLock) {
      if (reportedFiveSeconds) return
      val validLength = length.coerceIn(0, pcm16.size) and -2
      for (offset in 0 until validLength step 2) {
        val sample = ((pcm16[offset].toInt() and 0xff) or (pcm16[offset + 1].toInt() shl 8)).toShort().toInt()
        val magnitude = if (sample < 0) -sample else sample
        diagnosticPeak = maxOf(diagnosticPeak, magnitude)
        diagnosticSquareSum += sample.toLong() * sample.toLong()
      }
      diagnosticSamples += validLength / 2
      diagnosticReadTotalMs += readMs
      diagnosticReadMaxMs = maxOf(diagnosticReadMaxMs, readMs)
      val seconds = diagnosticSamples / sampleRateHz
      val checkpoint =
        when {
          !reportedOneSecond && seconds >= 1L -> {
            reportedOneSecond = true
            "one_second"
          }

          !reportedFiveSeconds && seconds >= 5L -> {
            reportedFiveSeconds = true
            "five_seconds"
          }

          else -> return
        }
      val rms = sqrt(diagnosticSquareSum.toDouble() / diagnosticSamples)
      val pcmElapsedMs = diagnosticSamples * 1_000L / sampleRateHz
      val wallVsPcmMs = (SystemClock.elapsedRealtime() - diagnosticCaptureStartedAtMs) - pcmElapsedMs
      Log.i(
        "OpenClawVosk",
        "wake input level checkpoint=$checkpoint samples=$diagnosticSamples peak=$diagnosticPeak rms=${String.format(Locale.US, "%.1f", rms)} queuePendingMs=${pendingQueueMs()} queueDrops=$diagnosticQueueDrops readTotalMs=$diagnosticReadTotalMs readMaxMs=$diagnosticReadMaxMs decodeTotalMs=$diagnosticDecodeTotalMs decodeMaxMs=$diagnosticDecodeMaxMs wallVsPcmMs=$wallVsPcmMs",
      )
    }
  }

  private fun recordDecodeDuration(decodeMs: Long) {
    synchronized(diagnosticsLock) {
      if (reportedFiveSeconds) return
      diagnosticDecodeTotalMs += decodeMs
      diagnosticDecodeMaxMs = maxOf(diagnosticDecodeMaxMs, decodeMs)
    }
  }

  fun pendingQueueMs(): Long = queue.pendingMs()

  fun bufferFrames(): Int = captureBufferFrames

  private companion object {
    const val VOSK_WAKE_CAPTURE_FRAME_MS = 100
    // Four seconds exceeds the measured 3.097-second cold backlog without becoming unbounded.
    const val VOSK_WAKE_DECODE_QUEUE_CAPACITY_FRAMES = 40
    const val VOSK_WAKE_DECODE_POLL_MS = 100L
    const val VOSK_WAKE_CAPTURE_STOP_JOIN_MS = 2_000L
  }
}

/** Offline, fixed-phrase recognizer for devices without an Android RecognitionService. */
internal class VoskJarvisVoiceWakeRecognizer(
  context: Context,
) : VoiceWakeRecognizer {
  private data class PendingStart(
    val operationId: Long,
    val onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  )

  private data class ActiveSession(
    val operationId: Long,
    val recognizer: Recognizer,
    val capture: VoskWakeAudioCapture,
    val callbacks: VoiceWakeRecognitionSession,
    val prefixSessionActive: AtomicBoolean,
  )

  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val lock = Any()
  private var latestOperationId = 0L
  private var pendingStart: PendingStart? = null
  private var modelLoading = false
  private var model: Model? = null
  private var active: ActiveSession? = null
  private val prefixVerifierSingleFlight = VoskPrefixVerifierSingleFlight()

  override val isAvailable: Boolean = true

  override fun start(
    operationId: Long,
    onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  ) {
    Log.i("OpenClawVosk", "wake start operation=$operationId cachedModel=${model != null}")
    var retired: ActiveSession? = null
    val loadedModel =
      synchronized(lock) {
        if (operationId <= latestOperationId) return
        latestOperationId = operationId
        pendingStart = PendingStart(operationId, onEvent)
        retired = retireActiveLocked()
        model
      }
    retireActive(retired)
    if (loadedModel != null) startListening(loadedModel) else loadModelIfNeeded()
  }

  override fun stop(operationId: Long) {
    val retired =
      synchronized(lock) {
        if (operationId <= latestOperationId) return
        latestOperationId = operationId
        pendingStart = null
        retireActiveLocked()
      }
    retireActive(retired)
  }

  private fun loadModelIfNeeded() {
    val shouldLoad =
      synchronized(lock) {
        if (model != null || modelLoading || pendingStart == null) return
        modelLoading = true
        true
      }
    if (!shouldLoad) return
    StorageService.unpack(
      appContext,
      MODEL_ASSET_DIRECTORY,
      MODEL_STORAGE_DIRECTORY,
      { loadedModel ->
        Log.i("OpenClawVosk", "wake model unpacked")
        synchronized(lock) {
          model = loadedModel
          modelLoading = false
        }
        startListening(loadedModel)
      },
      {
        Log.w("OpenClawVosk", "wake model unpack failed")
        val pending =
          synchronized(lock) {
            modelLoading = false
            pendingStart.also { pendingStart = null }
          }
        pending?.onEvent(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
      },
    )
  }

  private fun startListening(loadedModel: Model) {
    val pending = synchronized(lock) { pendingStart } ?: return
    val callbacks = VoiceWakeRecognitionSession(pending.onEvent)
    val recognizer =
      try {
        Recognizer(loadedModel, SAMPLE_RATE_HZ).also { it.setWords(true) }
      } catch (_: Exception) {
        callbacks.retire()
        pending.onEvent(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
        return
      }
    val utteranceBuffer = VoskWakeUtteranceBuffer(SAMPLE_RATE_HZ.toInt())
    val wakePhraseGate = VoskWakePhraseGate(WAKE_PHRASE)
    val prefixWake = VoskPrefixWakeCoordinator(WAKE_PHRASE)
    val prefixSessionActive = AtomicBoolean(true)
    var capturePendingMs: () -> Long = { 0L }
    fun dispatchPrefixHandoff(handoff: VoskPrefixWakeHandoff, captureState: VoskWakeCaptureState) {
      if (!captureState.isCurrent()) return
      val tail = captureState.claimQueuedTail() ?: return
      // The final endpoint snapshot, not the earlier verification prefix,
      // contains the whole command. Refuse rather than replay a clipped tail.
      val endpoint = handoff.endpoint.appendQueuedTail(tail)
      val initialAudio = endpoint.audioAfterWake(handoff.finalizedWake) ?: return
      val seedDurationMs = initialAudio.pcm16.size * 1_000L / (initialAudio.sampleRateHz * 2L)
      Log.i(
        "OpenClawVoskPrefix",
        "prefix handoff snapshotFirst=${endpoint.firstSample} snapshotNext=${endpoint.nextSample} wakeStart=${handoff.finalizedWake.startSample} wakeEnd=${handoff.finalizedWake.endSample} seedBytes=${initialAudio.pcm16.size} seedDurationMs=$seedDurationMs prefixGeneration=${handoff.generation} captureGeneration=${captureState.generation} queuePendingMs=${tail.pendingMs}",
      )
      mainHandler.post {
        if (captureState.isCurrent()) callbacks.emit(VoiceWakeRecognitionEvent.Wake(initialAudio))
      }
    }
    val capture =
      try {
        VoskWakeAudioCapture(
          recognizer = recognizer,
          sampleRateHz = SAMPLE_RATE_HZ.toInt(),
          onFinal = final@{ hypothesis, captureState ->
            // A short wake phrase may produce one or many partials; only its finalized word is
            // safe to use. Log counts, never the user's raw speech or transcript.
            val confidence = finalizedWakePhraseConfidence(hypothesis, WAKE_PHRASE)
            val accepted = wakePhraseGate.acceptFinal(hypothesis)
            Log.i(
              "OpenClawVosk",
              "wake final accepted=$accepted confidence=${confidence ?: "none"} partialCandidates=${wakePhraseGate.drainPartialCandidateCount()}",
            )
            if (accepted) {
              val tail = captureState.claimQueuedTail() ?: return@final
              prefixWake.discardCurrent()
              // Build and clear the bounded PCM handoff on the capture worker,
              // then dispatch ownership to main like the previous SpeechService.
              val finalizedWake = finalizedWakeWord(hypothesis, WAKE_PHRASE, SAMPLE_RATE_HZ.toInt())
              tail.frames.forEach { frame -> utteranceBuffer.append(frame.pcm16, frame.pcm16.size) }
              val endpoint = utteranceBuffer.snapshot()
              val initialAudio = endpoint.audioAfterWake(finalizedWake)
              utteranceBuffer.clear()
              val seedDurationMs =
                initialAudio?.let { audio -> audio.pcm16.size * 1_000L / (audio.sampleRateHz * 2L) } ?: 0L
              Log.i(
                "OpenClawVosk",
                "wake handoff endpoint=final snapshotFirst=${endpoint.firstSample} snapshotNext=${endpoint.nextSample} wakeStart=${finalizedWake?.startSample ?: -1} wakeEnd=${finalizedWake?.endSample ?: -1} seedBytes=${initialAudio?.pcm16?.size ?: 0} seedDurationMs=$seedDurationMs generation=${captureState.generation} queuePendingMs=${tail.pendingMs}",
              )
              mainHandler.post {
                if (captureState.isCurrent()) callbacks.emit(VoiceWakeRecognitionEvent.Wake(initialAudio))
              }
            } else {
              val handoff = prefixWake.onFinal(utteranceBuffer.snapshot())
              utteranceBuffer.clear()
              handoff?.let {
                Log.i("OpenClawVoskPrefix", "prefix handoff endpoint=final")
                dispatchPrefixHandoff(it, captureState)
              }
            }
          },
          onPartial = partial@{ hypothesis, captureState ->
            // Never wake from a partial: video audio can repeat an unrestricted-decoder candidate.
            wakePhraseGate.acceptPartial(hypothesis)
            val candidate = prefixWake.onPartial(hypothesis, utteranceBuffer::snapshot)
            if (candidate == null) return@partial
            if (!prefixVerifierSingleFlight.tryStart()) {
              // A retired session's verifier is still draining. Do not leave
              // this utterance wedged behind it; a later stable candidate can
              // start once the single-flight slot is released.
              prefixWake.discardCurrent()
              return@partial
            }
            val verificationStartedAtMs = SystemClock.elapsedRealtime()
            val candidateDurationMs = candidate.snapshot.pcm16.size * 1_000L / (candidate.snapshot.sampleRateHz * 2L)
            Log.i("OpenClawVoskPrefix", "prefix candidate started durationMs=$candidateDurationMs")
            // Decoding the prefix can take seconds on the Show. It runs away
            // from the AudioRecord worker and only contributes after the main
            // recognizer has reached an ordinary endpoint.
            Thread(
              {
                try {
                  val verifiedWake = verifyVoskWakePrefix(
                    loadedModel,
                    candidate,
                    WAKE_PHRASE,
                    prefixSessionActive::get,
                  )
                  if (prefixSessionActive.get() && captureState.isCurrent()) {
                    val elapsedMs = SystemClock.elapsedRealtime() - verificationStartedAtMs
                    Log.i("OpenClawVoskPrefix", "prefix verification verified=${verifiedWake != null} elapsedMs=$elapsedMs")
                    val handoff = prefixWake.onVerificationComplete(candidate, verifiedWake)
                    handoff?.let {
                      Log.i("OpenClawVoskPrefix", "prefix handoff endpoint=verification")
                      dispatchPrefixHandoff(it, captureState)
                    }
                  } else {
                    Log.i("OpenClawVoskPrefix", "prefix stale discard reason=session_or_generation_inactive")
                  }
                } finally {
                  prefixVerifierSingleFlight.finish()
                }
              },
              "OpenClawVoskPrefix",
            ).start()
          },
          onAudio = utteranceBuffer::append,
          onDiscontinuity = {
            utteranceBuffer.reset()
            wakePhraseGate.reset()
            prefixWake.discardCurrent()
            Log.i("OpenClawVosk", "wake discontinuity reset=true")
          },
          onError = {
            prefixSessionActive.set(false)
            prefixWake.discardCurrent()
            utteranceBuffer.clear()
            mainHandler.post {
              callbacks.emit(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
            }
          },
        )
      } catch (_: Exception) {
        callbacks.retire()
        recognizer.close()
        pending.onEvent(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
        return
      }
    capturePendingMs = capture::pendingQueueMs
    val session = ActiveSession(pending.operationId, recognizer, capture, callbacks, prefixSessionActive)
    val admitted = synchronized(lock) {
      if (pendingStart?.operationId != pending.operationId || latestOperationId != pending.operationId) {
        false
      } else {
        active = session
        pendingStart = null
        true
      }
    }
    if (!admitted) {
      callbacks.retire()
      capture.closeAfterWorker(recognizer::close)
      return
    }
    val started = capture.start()
    if (!started) {
      val retired = synchronized(lock) {
        if (active === session) retireActiveLocked() else null
      }
      retireActive(retired)
      if (retired != null) callbacks.emit(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
    } else {
      Log.i(
        "OpenClawVosk",
        "wake audio capture started bufferFrames=${capture.bufferFrames()} queueCapacityFrames=40 queueCapacityMs=4000",
      )
      callbacks.emit(VoiceWakeRecognitionEvent.Ready)
    }
  }

  private fun retireActiveLocked(): ActiveSession? {
    val current = active ?: return null
    active = null
    current.prefixSessionActive.set(false)
    current.callbacks.retire()
    return current
  }

  private fun retireActive(current: ActiveSession?) {
    current ?: return
    // The capture owns AudioRecord and joins its worker before the native recognizer closes.
    runCatching { current.capture.closeAfterWorker(current.recognizer::close) }
  }

  private companion object {
    const val SAMPLE_RATE_HZ = 16_000f
    const val WAKE_PHRASE = "jarvis"
    const val MODEL_ASSET_DIRECTORY = "vosk-model-small-en-us-0.15"
    const val MODEL_STORAGE_DIRECTORY = "openclaw-vosk-en-us-0.15"
  }
}

internal interface VoiceWakeRecognizer {
  val isAvailable: Boolean

  fun start(
    operationId: Long,
    onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  )

  fun stop(operationId: Long)
}

internal class VoiceWakeRecognitionSession(
  private val onEvent: (VoiceWakeRecognitionEvent) -> Unit,
) {
  private val active = AtomicBoolean(true)

  fun emit(event: VoiceWakeRecognitionEvent) {
    if (active.get()) onEvent(event)
  }

  fun retire() {
    active.set(false)
  }
}

internal class AndroidOnDeviceVoiceWakeRecognizer(
  context: Context,
) : VoiceWakeRecognizer {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  override val isAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Api31OnDeviceSpeech.isAvailable(appContext)
  private val latestOperationId = AtomicLong(0)
  private val platformOwnerOperationId = AtomicLong(0)
  private var recognizer: SpeechRecognizer? = null
  private var recognitionSession: VoiceWakeRecognitionSession? = null

  override fun start(
    operationId: Long,
    onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  ) {
    if (!claimOperation(operationId)) return
    platformOwnerOperationId.set(operationId)
    if (operationId != latestOperationId.get()) {
      platformOwnerOperationId.compareAndSet(operationId, 0)
      return
    }
    runOnMain {
      if (operationId != latestOperationId.get()) {
        platformOwnerOperationId.compareAndSet(operationId, 0)
        return@runOnMain
      }
      retireRecognizer()
      platformOwnerOperationId.set(operationId)
      if (!isAvailable) {
        platformOwnerOperationId.compareAndSet(operationId, 0)
        onEvent(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
        return@runOnMain
      }
      val session = VoiceWakeRecognitionSession(onEvent)
      try {
        val active = createRecognizer(session)
        recognitionSession = session
        recognizer = active
        active.startListening(recognizerIntent())
      } catch (_: Throwable) {
        session.retire()
        retireRecognizer()
        if (operationId == latestOperationId.get()) {
          onEvent(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_CLIENT))
        }
      }
    }
  }

  override fun stop(operationId: Long) {
    if (!claimOperation(operationId)) return
    if (platformOwnerOperationId.get() == 0L) return
    runOnMainSync {
      if (operationId == latestOperationId.get()) retireRecognizer()
    }
  }

  private fun claimOperation(operationId: Long): Boolean {
    while (true) {
      val current = latestOperationId.get()
      if (operationId <= current) return false
      if (latestOperationId.compareAndSet(current, operationId)) return true
    }
  }

  private fun createRecognizer(session: VoiceWakeRecognitionSession): SpeechRecognizer =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Api31OnDeviceSpeech.create(appContext).also { active ->
      active.setRecognitionListener(
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {
            session.emit(VoiceWakeRecognitionEvent.Ready)
          }

          override fun onResults(results: Bundle?) {
            bestTranscript(results)?.let { session.emit(VoiceWakeRecognitionEvent.Transcript(it, isFinal = true)) }
              ?: session.emit(VoiceWakeRecognitionEvent.Error(SpeechRecognizer.ERROR_NO_MATCH))
          }

          override fun onPartialResults(partialResults: Bundle?) {
            bestTranscript(partialResults)?.let {
              session.emit(VoiceWakeRecognitionEvent.Transcript(it, isFinal = false))
            }
          }

          override fun onError(error: Int) {
            session.emit(VoiceWakeRecognitionEvent.Error(error))
          }

          override fun onBeginningOfSpeech() = Unit

          override fun onRmsChanged(rmsdB: Float) = Unit

          override fun onBufferReceived(buffer: ByteArray?) = Unit

          override fun onEndOfSpeech() = Unit

          override fun onEvent(
            eventType: Int,
            params: Bundle?,
          ) = Unit
        },
      )
    } else {
      error("On-device speech recognition requires Android 12")
    }

  private fun retireRecognizer() {
    // Retire callback ownership before cancel/destroy. Some recognizers emit a
    // late result or error after cancellation; it must not enter a new session.
    recognitionSession?.retire()
    recognitionSession = null
    platformOwnerOperationId.set(0)
    val active = recognizer
    recognizer = null
    runCatching { active?.cancel() }
    runCatching { active?.destroy() }
  }

  private fun runOnMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
  }

  private fun runOnMainSync(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
      return
    }
    val completed = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    check(
      mainHandler.post {
        try {
          action()
        } catch (err: Throwable) {
          failure.set(err)
        } finally {
          completed.countDown()
        }
      },
    ) { "main looper unavailable" }
    completed.await()
    failure.get()?.let { throw it }
  }

  private fun recognizerIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

  private fun bestTranscript(bundle: Bundle?): String? =
    bundle
      ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      ?.firstOrNull()
      ?.trim()
      ?.takeIf(String::isNotEmpty)
}

/** Keeps Android 12-only SpeechRecognizer symbols out of Android 11 execution. */
@RequiresApi(Build.VERSION_CODES.S)
private object Api31OnDeviceSpeech {
  fun isAvailable(context: Context): Boolean =
    runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

  fun create(context: Context): SpeechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
}

internal enum class VoiceWakeSuppressionReason {
  Camera,
  Dictation,
  GatewaySync,
  VoiceCapture,
  VoiceNote,
  VoiceReplySpeech,
  AnnouncementSpeech,
  MessageSpeech,
}

internal class VoiceWakeManager(
  private val context: Context,
  private val scope: CoroutineScope,
  private val recognizer: VoiceWakeRecognizer,
  initialTriggerWords: List<String>,
  private val onCommand: suspend (VoiceWakeMatch) -> Boolean,
  private val restartDelayMs: Long = 350L,
  private val hasRecordAudioPermission: () -> Boolean = {
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  },
) {
  private sealed interface RecognizerAction {
    val operationId: Long

    data class Start(
      override val operationId: Long,
      val sessionGeneration: Long,
    ) : RecognizerAction

    data class Stop(
      override val operationId: Long,
      val afterStop: (() -> Unit)? = null,
    ) : RecognizerAction
  }

  private val lock = Any()
  private var enabled = false
  private var foreground = false
  private var sessionGeneration = 0L
  private var sessionActive = false
  private var commandInFlight = false
  private var commandJob: Job? = null
  private var restartJob: Job? = null
  private var recognizerOperationId = 0L
  private val suppressionReasons = mutableSetOf<VoiceWakeSuppressionReason>()
  private val suppressionRevisions = mutableMapOf<VoiceWakeSuppressionReason, Long>()
  private var triggerWords = VoiceWakePreferences.sanitizeTriggerWords(initialTriggerWords)
  private var lastLoggedDecision: String? = null

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

  private val _statusText = MutableStateFlow<NativeText>(nativeText("Off"))
  val statusText: StateFlow<String> = _statusText.resolveNativeText()

  private val _lastTriggeredCommand = MutableStateFlow<String?>(null)
  val lastTriggeredCommand: StateFlow<String?> = _lastTriggeredCommand.asStateFlow()

  val isAvailable: Boolean
    get() = recognizer.isAvailable

  fun setEnabled(value: Boolean) {
    val action =
      synchronized(lock) {
        enabled = value
        reconcileLocked()
      }
    performRecognizerAction(action)
  }

  fun setForeground(value: Boolean) {
    val action =
      synchronized(lock) {
        foreground = value
        reconcileLocked()
      }
    performRecognizerAction(action)
  }

  fun setSuppressed(
    reason: VoiceWakeSuppressionReason,
    suppressed: Boolean,
    revision: Long? = null,
    preserveDispatchedCommand: Boolean = false,
  ) {
    val action =
      synchronized(lock) {
        if (revision != null) {
          val currentRevision = suppressionRevisions[reason] ?: 0L
          if (revision <= currentRevision) return
          suppressionRevisions[reason] = revision
        }
        if (suppressed) suppressionReasons += reason else suppressionReasons -= reason
        reconcileLocked(
          preserveDispatchedCommand =
            reason == VoiceWakeSuppressionReason.VoiceCapture && suppressed && preserveDispatchedCommand,
        )
      }
    performRecognizerAction(action)
  }

  fun updateTriggerWords(words: List<String>) {
    synchronized(lock) {
      triggerWords = VoiceWakePreferences.sanitizeTriggerWords(words)
    }
  }

  fun refreshPermission() {
    val action = synchronized(lock) { reconcileLocked() }
    performRecognizerAction(action)
  }

  fun shutdown() {
    val action =
      synchronized(lock) {
        enabled = false
        foreground = false
        val pendingAction = stopSessionLocked(force = true)
        _statusText.value = nativeText("Off")
        pendingAction
      }
    performRecognizerAction(action)
  }

  private fun reconcileLocked(preserveDispatchedCommand: Boolean = false): RecognizerAction? {
    val blockedStatus = blockedStatusLocked()
    if (blockedStatus != null) {
      val action = stopSessionLocked(
        force = !enabled || !foreground,
        preserveDispatchedCommand = preserveDispatchedCommand,
      )
      _statusText.value = blockedStatus
      logDecisionLocked("blocked=${wakeBlockReasonLocked()}")
      return action
    }
    if (!sessionActive && !commandInFlight && restartJob?.isActive != true) {
      logDecisionLocked("starting")
      return startSessionLocked()
    }
    logDecisionLocked("active=$sessionActive command=$commandInFlight restarting=${restartJob?.isActive == true}")
    return null
  }

  private fun wakeBlockReasonLocked(): String =
    when {
      !enabled -> "disabled"
      !recognizer.isAvailable -> "recognizer-unavailable"
      !hasRecordAudioPermission() -> "microphone-permission"
      !foreground -> "background"
      suppressionReasons.isNotEmpty() -> "suppressed:${suppressionReasons.sortedBy { it.name }.joinToString(",")}"
      else -> "none"
    }

  private fun logDecisionLocked(decision: String) {
    if (lastLoggedDecision == decision) return
    lastLoggedDecision = decision
    Log.i("OpenClawVoiceWake", "wake $decision")
  }

  private fun blockedStatusLocked(): NativeText? =
    when {
      !enabled -> nativeText("Off")
      !recognizer.isAvailable -> nativeText("On-device speech recognition unavailable")
      !hasRecordAudioPermission() -> nativeText("Microphone permission required")
      !foreground || suppressionReasons.isNotEmpty() -> nativeText("Paused")
      else -> null
    }

  private fun startSessionLocked(): RecognizerAction {
    sessionGeneration += 1
    val generation = sessionGeneration
    sessionActive = true
    _isListening.value = false
    _statusText.value = nativeText("Starting…")
    return RecognizerAction.Start(nextRecognizerOperationIdLocked(), generation)
  }

  private fun handleRecognitionEvent(
    generation: Long,
    event: VoiceWakeRecognitionEvent,
  ) {
    val action =
      synchronized(lock) {
        if (generation != sessionGeneration || !sessionActive) return
        when (event) {
          VoiceWakeRecognitionEvent.Ready -> {
            _isListening.value = true
            _statusText.value = nativeText("Listening")
            null
          }

          is VoiceWakeRecognitionEvent.Wake -> handleWakeLocked(event.initialAudio)

          is VoiceWakeRecognitionEvent.Transcript -> {
            // Android partials routinely stop mid-command. Only a final transcript
            // has the recognizer's end-of-utterance boundary and is safe to dispatch.
            if (!event.isFinal) return
            val transcriptAction = handleTranscriptLocked(event.text)
            if (transcriptAction == null) {
              sessionActive = false
              _isListening.value = false
              scheduleRestartLocked()
            }
            transcriptAction
          }

          is VoiceWakeRecognitionEvent.Error -> {
            sessionActive = false
            _isListening.value = false
            if (event.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
              _statusText.value = nativeText("Microphone permission required")
            } else if (event.code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
              _statusText.value = nativeText("Device language not supported")
            } else if (event.code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
              _statusText.value = nativeText("On-device language model unavailable")
            } else {
              scheduleRestartLocked(delayMs = retryDelayMs(event.code))
            }
            null
          }
        }
      }
    performRecognizerAction(action)
  }

  private fun handleTranscriptLocked(transcript: String): RecognizerAction? {
    if (commandInFlight) return null
    val match = VoiceWakePhraseMatcher.match(transcript, triggerWords) ?: return null
    sessionGeneration += 1
    val commandGeneration = sessionGeneration
    sessionActive = false
    commandInFlight = true
    _isListening.value = false
    _lastTriggeredCommand.value = match.command
    _statusText.value = nativeText("Triggered")
    val action = RecognizerAction.Stop(nextRecognizerOperationIdLocked())
    val pendingCommand =
      scope.launch(start = CoroutineStart.LAZY) {
        var delivered = false
        try {
          delivered = onCommand(match)
        } finally {
          synchronized(lock) {
            if (commandGeneration == sessionGeneration) {
              commandJob = null
              commandInFlight = false
              scheduleRestartLocked()
              if (!delivered) _statusText.value = nativeText("Gateway unavailable")
            }
          }
        }
      }
    commandJob = pendingCommand
    return action.copy(
      afterStop = {
        val shouldStart = synchronized(lock) { commandGeneration == sessionGeneration && commandInFlight && commandJob === pendingCommand }
        if (shouldStart) pendingCommand.start()
      },
    )
  }

  private fun handleWakeLocked(initialAudio: VoiceWakeAudio?): RecognizerAction? {
    if (commandInFlight) return null
    sessionGeneration += 1
    val commandGeneration = sessionGeneration
    sessionActive = false
    commandInFlight = true
    _isListening.value = false
    _lastTriggeredCommand.value = "Jarvis"
    _statusText.value = nativeText("Triggered")
    val action = RecognizerAction.Stop(nextRecognizerOperationIdLocked())
    val pendingCommand =
      scope.launch(start = CoroutineStart.LAZY) {
        var delivered = false
        try {
          delivered = onCommand(VoiceWakeMatch("Jarvis", "", initialAudio))
        } finally {
          synchronized(lock) {
            if (commandGeneration == sessionGeneration) {
              commandJob = null
              commandInFlight = false
              scheduleRestartLocked()
              if (!delivered) _statusText.value = nativeText("Gateway unavailable")
            }
          }
        }
      }
    commandJob = pendingCommand
    return action.copy(
      afterStop = {
        val shouldStart = synchronized(lock) { commandGeneration == sessionGeneration && commandInFlight && commandJob === pendingCommand }
        if (shouldStart) pendingCommand.start()
      },
    )
  }

  private fun scheduleRestartLocked(delayMs: Long = restartDelayMs) {
    restartJob?.cancel()
    val blockedStatus = blockedStatusLocked()
    if (blockedStatus != null) {
      _statusText.value = blockedStatus
      return
    }
    _statusText.value = nativeText("Starting…")
    restartJob =
      scope.launch {
        delay(delayMs)
        val action =
          synchronized(lock) {
            restartJob = null
            reconcileLocked()
          }
        performRecognizerAction(action)
      }
  }

  private fun retryDelayMs(errorCode: Int): Long =
    when (errorCode) {
      SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 15_000L

      SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
      SpeechRecognizer.ERROR_SERVER,
      SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
      SpeechRecognizer.ERROR_AUDIO,
      SpeechRecognizer.ERROR_CLIENT,
      -> 1_500L

      else -> restartDelayMs
    }

  private fun stopSessionLocked(
    force: Boolean,
    preserveDispatchedCommand: Boolean = false,
  ): RecognizerAction? {
    restartJob?.cancel()
    restartJob = null
    if (!preserveDispatchedCommand) {
      commandJob?.cancel()
      commandJob = null
      commandInFlight = false
      sessionGeneration += 1
    }
    val wasActive = sessionActive
    sessionActive = false
    _isListening.value = false
    return if (force || wasActive) RecognizerAction.Stop(nextRecognizerOperationIdLocked()) else null
  }

  private fun nextRecognizerOperationIdLocked(): Long {
    recognizerOperationId += 1
    return recognizerOperationId
  }

  private fun performRecognizerAction(action: RecognizerAction?) {
    when (action) {
      is RecognizerAction.Start -> {
        Log.i("OpenClawVoiceWake", "recognizer start operation=${action.operationId}")
        recognizer.start(action.operationId) { event ->
          handleRecognitionEvent(action.sessionGeneration, event)
        }
      }

      is RecognizerAction.Stop -> {
        Log.i("OpenClawVoiceWake", "recognizer stop operation=${action.operationId}")
        recognizer.stop(action.operationId)
        action.afterStop?.invoke()
      }

      null -> {}
    }
  }
}

internal class PreviewVoiceWakeRecognizer : VoiceWakeRecognizer {
  override val isAvailable: Boolean = true

  override fun start(
    operationId: Long,
    onEvent: (VoiceWakeRecognitionEvent) -> Unit,
  ) {
    onEvent(VoiceWakeRecognitionEvent.Ready)
  }

  override fun stop(operationId: Long) = Unit
}
