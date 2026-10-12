package ai.openclaw.app.home

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Small, display-only event store for the Horizon surface.  It deliberately owns no action
 * execution: a node command can describe a confirmed display event, but cannot manufacture a
 * home/device action by calling this repository.
 */
class HorizonPresentationRepository private constructor() {
  private val lock = Any()
  private var nextRevision = 1L
  private val closedExecutionIds = LinkedHashSet<String>()
  private val _presentation = MutableStateFlow(HorizonPresentationState())
  val presentation: StateFlow<HorizonPresentationState> = _presentation.asStateFlow()

  fun show(
    id: String,
    type: HorizonTileType,
    data: JsonObject,
    ttlMs: Long?,
    sticky: Boolean,
    nowMs: Long = System.currentTimeMillis(),
  ): HorizonPresentationUpdate = synchronized(lock) {
    expireLocked(nowMs)
    val expiresAtMs = if (sticky) null else ttlMs?.let { nowMs + it }
    publishLocked(_presentation.value.copy(tile = HorizonTile(id, type, data, expiresAtMs, sticky)))
    HorizonPresentationUpdate.Accepted(_presentation.value.revision)
  }

  fun clear(nowMs: Long = System.currentTimeMillis()): HorizonPresentationUpdate = synchronized(lock) {
    expireLocked(nowMs)
    if (_presentation.value.tile == null) return HorizonPresentationUpdate.NoChange
    publishLocked(_presentation.value.copy(tile = null))
    HorizonPresentationUpdate.Accepted(_presentation.value.revision)
  }

  fun beginExecution(id: String, title: String, steps: List<String>, nowMs: Long = System.currentTimeMillis()): HorizonPresentationUpdate = synchronized(lock) {
    expireLocked(nowMs)
    if (id in closedExecutionIds || _presentation.value.executions.any { it.id == id }) {
      return HorizonPresentationUpdate.Rejected("execution id is already owned or closed")
    }
    val retained = _presentation.value.executions.toMutableList()
    if (retained.size >= MAX_EXECUTIONS) {
      val completed = retained.indexOfFirst { it.exit != null }
      if (completed < 0) return HorizonPresentationUpdate.Rejected("too many active executions")
      retained.removeAt(completed)
    }
    val execution = HorizonExecution(id = id, title = title, steps = steps)
    publishLocked(_presentation.value.copy(executions = retained + execution))
    HorizonPresentationUpdate.Accepted(_presentation.value.revision)
  }

  fun appendLine(
    id: String,
    kind: HorizonExecutionLineKind,
    text: String,
    step: Int?,
    progress: Double?,
    nowMs: Long = System.currentTimeMillis(),
  ): HorizonPresentationUpdate = synchronized(lock) {
    expireLocked(nowMs)
    val execution = _presentation.value.executions.firstOrNull { it.id == id }
      ?: return HorizonPresentationUpdate.Rejected("unknown or closed execution id")
    if (execution.exit != null) return HorizonPresentationUpdate.Rejected("execution is already closed")
    val line = HorizonExecutionLine(kind, text, step, progress)
    val replacement = execution.copy(lines = (execution.lines + line).takeLast(MAX_LINES))
    publishLocked(_presentation.value.copy(executions = _presentation.value.executions.map { if (it.id == id) replacement else it }))
    HorizonPresentationUpdate.Accepted(_presentation.value.revision)
  }

  fun exitExecution(id: String, code: Int, elapsedMs: Long, nowMs: Long = System.currentTimeMillis()): HorizonPresentationUpdate = synchronized(lock) {
    expireLocked(nowMs)
    val execution = _presentation.value.executions.firstOrNull { it.id == id }
      ?: return HorizonPresentationUpdate.Rejected("unknown or closed execution id")
    if (execution.exit != null) return HorizonPresentationUpdate.Rejected("execution is already closed")
    val replacement = execution.copy(exit = HorizonExecutionExit(code, elapsedMs, completedAtMs = nowMs))
    closedExecutionIds += id
    while (closedExecutionIds.size > MAX_CLOSED_IDS) closedExecutionIds.remove(closedExecutionIds.first())
    publishLocked(_presentation.value.copy(executions = _presentation.value.executions.map { if (it.id == id) replacement else it }))
    HorizonPresentationUpdate.Accepted(_presentation.value.revision)
  }

  /** Called by a foreground renderer before drawing so expiring a tile never needs a background service. */
  fun expireIfDue(nowMs: Long = System.currentTimeMillis()) = synchronized(lock) { expireLocked(nowMs) }

  private fun expireLocked(nowMs: Long) {
    val tile = _presentation.value.tile ?: return
    if (tile.expiresAtMs != null && tile.expiresAtMs <= nowMs) publishLocked(_presentation.value.copy(tile = null))
  }

  private fun publishLocked(next: HorizonPresentationState) {
    _presentation.value = next.copy(revision = nextRevision++)
  }

  companion object {
    private const val MAX_EXECUTIONS = 4
    private const val MAX_LINES = 24
    private const val MAX_CLOSED_IDS = 64
    @Volatile private var instance: HorizonPresentationRepository? = null

    fun get(@Suppress("UNUSED_PARAMETER") context: Context): HorizonPresentationRepository =
      instance ?: synchronized(this) { instance ?: HorizonPresentationRepository().also { instance = it } }

    internal fun createForTests(): HorizonPresentationRepository = HorizonPresentationRepository()
  }
}

data class HorizonPresentationState(
  val revision: Long = 0,
  val tile: HorizonTile? = null,
  val executions: List<HorizonExecution> = emptyList(),
)

data class HorizonTile(
  val id: String,
  val type: HorizonTileType,
  val data: JsonObject,
  val expiresAtMs: Long?,
  val sticky: Boolean,
)

enum class HorizonTileType { Weather, Music, Camera, Tasks, Terminal, Timer, Home, Message }

data class HorizonExecution(
  val id: String,
  val title: String,
  val steps: List<String>,
  val lines: List<HorizonExecutionLine> = emptyList(),
  val exit: HorizonExecutionExit? = null,
)

data class HorizonExecutionLine(
  val kind: HorizonExecutionLineKind,
  val text: String,
  val step: Int?,
  val progress: Double?,
)

enum class HorizonExecutionLineKind { Command, Output, Ok, Error, Warning, Done }
data class HorizonExecutionExit(val code: Int, val elapsedMs: Long, val completedAtMs: Long)

sealed class HorizonPresentationUpdate {
  data class Accepted(val revision: Long) : HorizonPresentationUpdate()
  data class Rejected(val reason: String) : HorizonPresentationUpdate()
  object NoChange : HorizonPresentationUpdate()
}
