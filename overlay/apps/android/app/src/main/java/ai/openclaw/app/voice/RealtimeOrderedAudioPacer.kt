package ai.openclaw.app.voice

import android.os.SystemClock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Preserves the beginning of a wake utterance while the provider media path comes up.
 *
 * Gateway-relay GPT-Live buffers pre-media PCM, but deliberately retains only five seconds.
 * Ordered wake PCM is therefore released only after its provider-ready event and no more than
 * [maxLookaheadMs] ahead of its source clock. Frames captured after that ordered startup handoff
 * use their own low-latency path.
 */
internal class RealtimeOrderedAudioPacer(
  private val providerReady: Deferred<Unit>,
  private val nowMs: () -> Long = SystemClock::elapsedRealtime,
  private val maxLookaheadMs: Long = MAX_LOOKAHEAD_MS,
  private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
) {
  private var segmentStarted = false
  private var sourceEndAtMs = 0L

  suspend fun awaitAdmission(durationMs: Long) {
    require(durationMs > 0L)
    if (!segmentStarted) {
      try {
        withTimeout(readyTimeoutMs) { providerReady.await() }
      } catch (error: TimeoutCancellationException) {
        throw IllegalStateException("Realtime provider did not become ready within ${readyTimeoutMs}ms", error)
      }
      segmentStarted = true
      sourceEndAtMs = nowMs()
    }
    val admissionStartedAtMs = nowMs()
    // A delayed dispatcher must not turn a dormant source clock into a burst of
    // historic PCM. Reserve from wall time when its historic source is behind.
    val plannedSourceEndAtMs = maxOf(sourceEndAtMs + durationMs, admissionStartedAtMs + durationMs)
    val waitMs = plannedSourceEndAtMs - maxLookaheadMs - admissionStartedAtMs
    if (waitMs > 0L) delay(waitMs)
    // A late resumption after delay needs the same rebase. Otherwise a second
    // admission can immediately flush the time the dispatcher was unavailable.
    sourceEndAtMs = maxOf(plannedSourceEndAtMs, nowMs() + durationMs)
  }

  /** A post-output retained tail is a new source segment, never historic wake backlog. */
  fun beginNewSegment() {
    if (segmentStarted) sourceEndAtMs = nowMs()
  }

  companion object {
    const val MAX_LOOKAHEAD_MS = 500L
    // The relay create RPC may return before the provider media session. Keep the same hard
    // upper bound as that create request rather than dropping an intact wake utterance early.
    const val READY_TIMEOUT_MS = 15_000L
  }
}
