package ai.openclaw.app.home

/**
 * Read-only intercom fact owned by HomeDashboardRepository.  The presentation
 * consumes it but never advances it: Ring transport callbacks remain the only
 * authority for connection, failure, and close state.
 */
internal enum class RingCallSourcePhase {
  Idle,
  Ringing,
  Connecting,
  Connected,
  Declined,
  Ended,
  Failed,
}

internal data class RingCallSourceState(
  val phase: RingCallSourcePhase = RingCallSourcePhase.Idle,
  /** Identity of the physical Ring event, never a manually opened camera. */
  val noticeAtMs: Long? = null,
  /** Connection time, set only by the successful intercom transport callback. */
  val startedAtMs: Long? = null,
  /** Terminal time, used only to bound decline/end/failure presentation. */
  val endedAtMs: Long? = null,
  /** Fixed local transport error text; this is never a remote error payload. */
  val error: String? = null,
)

internal enum class RingCallPresentationPhase {
  Ringing,
  Connecting,
  Connected,
  Declined,
  Ended,
  Failed,
}

/** Small, renderer-safe Ring banner model. There is deliberately no door-open action. */
internal data class RingCallPresentation(
  val phase: RingCallPresentationPhase,
  val title: String,
  val detail: String,
  val hints: List<String>,
  val durationSeconds: Long? = null,
  val sticky: Boolean,
  val ttlMs: Long? = null,
)

/**
 * Converts actual Ring and intercom facts into the Horizon's call projection.
 * A stale transport event cannot attach itself to a newer doorbell notification,
 * and manual camera opens intentionally do not become doorbell banners.
 */
internal fun ringCallPresentation(
  source: RingCallSourceState,
  notice: RingNotice?,
  display: DoorCameraDisplayState,
  nowMs: Long,
): RingCallPresentation? {
  if (source.phase == RingCallSourcePhase.Idle || source.noticeAtMs == null) return null
  val terminal = source.phase in TERMINAL_PHASES
  if (terminal) {
    val endedAt = source.endedAtMs ?: return null
    val expiresAt = ringCallPresentationExpiryAtMs(source) ?: return null
    if (nowMs !in endedAt..expiresAt) return null
  } else {
    val currentNotice = notice ?: return null
    if (currentNotice.manual || currentNotice.occurredAt.toEpochMilli() != source.noticeAtMs) return null
    if (display.lifecycle == DoorCameraLifecycle.Closed) return null
  }

  return when (source.phase) {
    RingCallSourcePhase.Ringing -> RingCallPresentation(
      phase = RingCallPresentationPhase.Ringing,
      title = "Es klingelt",
      detail = "Haustür",
      hints = listOf("Sag „Annehmen“", "Sag „Ablehnen“"),
      sticky = true,
    )
    RingCallSourcePhase.Connecting -> RingCallPresentation(
      phase = RingCallPresentationPhase.Connecting,
      title = "Verbinde Haustür",
      detail = "Sprechanlage",
      hints = listOf("Bitte warten"),
      sticky = true,
    )
    RingCallSourcePhase.Connected -> RingCallPresentation(
      phase = RingCallPresentationPhase.Connected,
      title = "Verbunden",
      detail = "Sprechanlage",
      // The intercom exclusively owns the microphone. Do not promise a spoken hangup.
      hints = listOf("Max. 30 Sekunden · endet automatisch"),
      durationSeconds = source.startedAtMs?.let { ((nowMs - it).coerceAtLeast(0L) / 1_000L) },
      sticky = true,
    )
    RingCallSourcePhase.Declined -> terminalPresentation(
      RingCallPresentationPhase.Declined,
      "Klingeln abgelehnt",
      "Haustür",
    )
    RingCallSourcePhase.Ended -> terminalPresentation(
      RingCallPresentationPhase.Ended,
      "Gespräch beendet",
      "Haustür",
    )
    RingCallSourcePhase.Failed -> terminalPresentation(
      RingCallPresentationPhase.Failed,
      "Verbindung fehlgeschlagen",
      source.error?.takeIf { it in SAFE_ERROR_DETAILS } ?: "Live-Kamera konnte nicht verbunden werden",
      listOf("Sag „Haustür anzeigen“"),
    )
    RingCallSourcePhase.Idle -> null
  }
}

/** The UI schedules one final render at this deadline instead of retaining a terminal banner. */
internal fun ringCallPresentationExpiryAtMs(source: RingCallSourceState): Long? =
  source.endedAtMs?.takeIf { source.phase in TERMINAL_PHASES }?.plus(TERMINAL_VISIBLE_MS)

private fun terminalPresentation(
  phase: RingCallPresentationPhase,
  title: String,
  detail: String,
  hints: List<String> = emptyList(),
): RingCallPresentation =
  RingCallPresentation(
    phase = phase,
    title = title,
    detail = detail,
    hints = hints,
    sticky = false,
    ttlMs = TERMINAL_VISIBLE_MS,
  )

private val TERMINAL_PHASES = setOf(
  RingCallSourcePhase.Declined,
  RingCallSourcePhase.Ended,
  RingCallSourcePhase.Failed,
)
private const val TERMINAL_VISIBLE_MS = 3_800L
private val SAFE_ERROR_DETAILS = setOf("Live-Kamera konnte nicht verbunden werden")
