package ai.openclaw.app.home

/**
 * The exact display values used by a Horizon home-room card. [key] is the configured room value
 * from Home Assistant import data; it is intentionally never inferred from an entity or its name.
 */
internal data class HorizonHomeRoomValue(
  /** Exact configured room text, or the exact entity id for an unassigned device card. */
  val key: String,
  /** Configured room text or HA's own display name; never inferred from an entity id. */
  val label: String,
  val isConfiguredRoom: Boolean,
  val level: Double?,
  val detail: String,
)

/** A confirmed, bounded visual transition. The renderer interpolates only [fromLevel] to [toLevel]. */
internal data class HorizonHomeRoomChange(
  val roomKey: String,
  /** Exact HA entity ids accepted in this event; no request-side names are carried here. */
  val changedEntityIds: Set<String>,
  val fromLevel: Double?,
  val toLevel: Double?,
  val startedAtMs: Long,
  val durationMs: Long = VALUE_ANIMATION_MS,
  val highlightTtlMs: Long = HIGHLIGHT_TTL_MS,
) {
  val endsAtMs: Long get() = startedAtMs + durationMs
  val highlightExpiresAtMs: Long get() = startedAtMs + highlightTtlMs

  companion object {
    const val VALUE_ANIMATION_MS = 1_400L
    const val HIGHLIGHT_TTL_MS = 9_000L
  }
}

/**
 * Keeps a visual baseline for the Horizon home card.
 *
 * Snapshots and reconnects only establish that baseline. A highlight may be emitted solely by
 * [onConfirmedStateChanged], after Home Assistant has published an exact configured entity change.
 */
internal class HorizonHomeChangeTracker {
  private var baselineEstablished = false
  private var rooms: Map<String, HorizonHomeRoomValue> = emptyMap()

  fun onSnapshot(devices: List<HomeDeviceState>) {
    rooms = horizonHomeRoomValues(devices).associateBy(HorizonHomeRoomValue::key)
    baselineEstablished = true
  }

  fun onReconnect(devices: List<HomeDeviceState>) = onSnapshot(devices)

  /**
   * Call only from the live HA `state_changed` event path, with the dashboard's already-confirmed
   * replacement list. It refuses unknown entities and identical visible values. Unassigned devices
   * remain one exact-entity card rather than being guessed into a room.
   */
  fun onConfirmedStateChanged(
    devices: List<HomeDeviceState>,
    changedEntityId: String,
    occurredAtMs: Long,
  ): HorizonHomeRoomChange? {
    val next = horizonHomeRoomValues(devices).associateBy(HorizonHomeRoomValue::key)
    if (!baselineEstablished) {
      rooms = next
      baselineEstablished = true
      return null
    }

    val changed = devices.firstOrNull { it.entityId == changedEntityId }
    val roomKey = changed?.let(::horizonHomeDisplayKey)
    val previous = roomKey?.let(rooms::get)
    val current = roomKey?.let(next::get)
    rooms = next
    if (roomKey == null || previous == null || current == null || previous.sameOperationalValueAs(current)) return null

    return HorizonHomeRoomChange(
      roomKey = roomKey,
      changedEntityIds = setOf(changedEntityId),
      fromLevel = previous.level,
      toLevel = current.level,
      startedAtMs = occurredAtMs,
    )
  }
}

/**
 * Shared source for the rendered room cards and transition tracker. The aggregate mirrors the
 * existing Horizon home card: available devices with levels average to the displayed room level.
 */
internal fun horizonHomeRoomValues(devices: List<HomeDeviceState>): List<HorizonHomeRoomValue> =
  devices
    .groupBy(::horizonHomeDisplayKey)
    .toSortedMap()
    .map { (key, group) ->
      val representative = group.first()
      val configuredRoom = representative.room?.trim()?.takeIf(String::isNotEmpty)
      val available = group.filter(HomeDeviceState::available)
      val levels = available.mapNotNull(HomeDeviceState::levelPercent)
      HorizonHomeRoomValue(
        key = key,
        label = configuredRoom ?: representative.name,
        isConfiguredRoom = configuredRoom != null,
        level = levels.takeIf { it.isNotEmpty() }?.average()?.div(100.0),
        detail = "${available.count { it.state.equals("on", ignoreCase = true) }}/${available.size} an",
      )
    }

/** An unassigned device is its own exact card; its friendly name never becomes a room key. */
private fun horizonHomeDisplayKey(device: HomeDeviceState): String =
  device.room?.trim()?.takeIf(String::isNotEmpty) ?: device.entityId

/** Friendly-name changes are presentation metadata; only rendered operational values may pulse. */
private fun HorizonHomeRoomValue.sameOperationalValueAs(other: HorizonHomeRoomValue): Boolean =
  level == other.level && detail == other.detail
