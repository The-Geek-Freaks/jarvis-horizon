package ai.openclaw.app.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Conversation-close policy for the device-owned part of a live Jarvis session.
 *
 * It deliberately consumes only final, narrow farewell utterances. A gateway may
 * already have begun a consult when its transcript reaches Android, so this is a
 * local relay-close policy, not a claim that it can retract upstream work.
 */
internal object JarvisConversationLifecycle {
  const val QUIET_TIMEOUT_MS = 7_000L
  const val STALLED_TIMEOUT_MS = 90_000L
  const val INPUT_SPEECH_RMS_THRESHOLD = 0.003
  const val INPUT_SPEECH_CONSECUTIVE_FRAMES = 3
  const val INPUT_SPEECH_TAIL_MS = 1_200L
  const val INPUT_SPEECH_MAX_GRACE_MS = 30_000L
  /** A physically drained reply keeps its normal seven-second silence window. */
  const val POST_ASSISTANT_FOLLOW_UP_MS = QUIET_TIMEOUT_MS
  const val POST_ASSISTANT_FOLLOW_UP_MAX_UTTERANCE_MS = 15_000L
  const val POST_ASSISTANT_FOLLOW_UP_FINAL_GRACE_MS = 2_000L

  fun nextInputSpeechFrames(
    previousFrames: Int,
    captureAdmitted: Boolean,
    rms: Double,
  ): Int =
    if (captureAdmitted && rms >= INPUT_SPEECH_RMS_THRESHOLD) {
      (previousFrames + 1).coerceAtMost(INPUT_SPEECH_CONSECUTIVE_FRAMES)
    } else {
      0
    }

  private fun normalize(transcript: String): String =
      Normalizer.normalize(transcript, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("['’]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

  fun isExplicitFarewell(transcript: String): Boolean {
    val normalized = normalize(transcript)
    return normalized.matches(
      Regex(
        "(?:danke jarvis(?: das (?:wars|war es))?(?: bis spater)?|(?:jarvis )?das (?:wars|war es)(?: bis spater)?|(?:jarvis )?bis spater|(?:jarvis )?geh schlafen|jarvis (?:hor auf|schluss|ende|stopp))",
      ),
    )
  }

  fun isSleepRequest(transcript: String): Boolean =
    normalize(transcript).matches(Regex("(?:jarvis )?geh schlafen"))

  /** Partial text is useful only when it introduces another recognized word. */
  fun transcriptWordCount(transcript: String): Int =
    normalize(transcript).split(' ').count { it.isNotEmpty() }

  fun closeReason(
    idleForMs: Long,
    userSpeechActive: Boolean,
    audibleAssistantOutput: Boolean,
    pendingRealTool: Boolean,
    awaitingReply: Boolean,
    postAssistantFollowUpExpired: Boolean = false,
  ): JarvisConversationCloseReason? {
    // The finite post-reply window owns this boundary. A real audible reply may finish,
    // but microphone level and partial text cannot extend capture.
    if (audibleAssistantOutput) return null
    // A visible follow-up window is over after its bounded silence period, but a relay-owned
    // consult may still need the transport for its final answer. The caller must
    // detach its microphone in that case; it must not close the relay here.
    if (postAssistantFollowUpExpired && (pendingRealTool || awaitingReply)) return null
    if (postAssistantFollowUpExpired) return JarvisConversationCloseReason.Idle
    // Pending work retires only the microphone at the quiet boundary. The
    // manager keeps the relay for its bounded 90-second completion window.
    if (pendingRealTool || awaitingReply) return null
    if (idleForMs >= QUIET_TIMEOUT_MS) return JarvisConversationCloseReason.Idle
    return null
  }

  /**
   * A consult may still need its relay for the final audible answer, but the microphone
   * must not remain open while that work happens. This is intentionally independent of
   * RMS and partial transcription events.
   */
  fun shouldDetachMicrophone(
    idleForMs: Long,
    audibleAssistantOutput: Boolean,
    pendingRealTool: Boolean,
    awaitingReply: Boolean,
    postAssistantFollowUpExpired: Boolean,
  ): Boolean =
    !audibleAssistantOutput &&
      (pendingRealTool || awaitingReply) &&
      (idleForMs >= QUIET_TIMEOUT_MS || postAssistantFollowUpExpired)
}

internal enum class JarvisConversationCloseReason {
  ExplicitFarewell,
  Idle,
  Stalled,
}

/** A compact hook for the sleep UI; it carries no transcript, command, or tool data. */
internal data class JarvisConversationLifecycleState(
  val active: Boolean,
  val closingReason: JarvisConversationCloseReason? = null,
  val sleepRequested: Boolean = false,
)
