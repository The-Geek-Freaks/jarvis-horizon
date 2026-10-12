package ai.openclaw.app.voice

import kotlinx.coroutines.CompletableDeferred

/**
 * Bounds audio accepted by the gateway socket but not yet acknowledged by its RPC waiter.
 */
internal class RealtimeAudioIngressBudget(
  private val maxPendingMs: Long = MAX_PENDING_MS,
) {
  internal class Reservation internal constructor(
    internal val owner: Any,
    internal val ticket: Long,
  )

  private data class PendingReservation(
    val durationMs: Long,
  )

  private var nextTicket = 0L
  private var pendingMs = 0L
  private val ownerToken = Any()
  private val pendingReservations = mutableMapOf<Long, PendingReservation>()
  private var capacityChanged = CompletableDeferred<Unit>()

  /**
   * Immediately reserves a frame when capacity is available. Live microphone frames may be
   * dropped when this returns null because, once queued behind a stalled socket, they are stale.
   */
  @Synchronized
  fun tryReserve(durationMs: Long): Reservation? {
    if (durationMs <= 0L || durationMs > maxPendingMs - pendingMs) return null
    val ticket = ++nextTicket
    pendingReservations[ticket] = PendingReservation(durationMs = durationMs)
    pendingMs += durationMs
    return Reservation(owner = ownerToken, ticket = ticket)
  }

  /**
   * Awaits capacity for ordered wake seed/startup/tail audio. Cancellation while waiting owns no
   * reservation, so retiring its capture coroutine cannot leak capacity into a replacement relay.
   */
  suspend fun reserve(durationMs: Long): Reservation {
    require(durationMs in 1..maxPendingMs) { "audio frame duration must fit the ingress budget" }
    while (true) {
      val waitFor =
        synchronized(this) {
          tryReserve(durationMs)?.let { return it }
          capacityChanged
        }
      waitFor.await()
    }
  }

  /** Idempotently releases the exact accepted frame. */
  @Synchronized
  fun release(reservation: Reservation) {
    if (reservation.owner !== ownerToken) return
    val pending = pendingReservations.remove(reservation.ticket) ?: return
    pendingMs = (pendingMs - pending.durationMs).coerceAtLeast(0L)
    capacityChanged.complete(Unit)
    capacityChanged = CompletableDeferred()
  }

  @Synchronized
  internal fun pendingDurationMs(): Long = pendingMs

  private companion object {
    const val MAX_PENDING_MS = 3_000L
  }
}
