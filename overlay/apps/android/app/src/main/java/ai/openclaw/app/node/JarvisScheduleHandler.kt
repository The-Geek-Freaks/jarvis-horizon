package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.home.timers.JarvisSchedule
import ai.openclaw.app.home.timers.JarvisScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Narrow local node surface for Jarvis timers and one-shot alarms. */
interface JarvisScheduleHandler {
  suspend fun handleJarvisSchedule(paramsJson: String?): GatewaySession.InvokeResult
}

class LocalJarvisScheduleHandler(private val schedules: JarvisScheduleRepository) : JarvisScheduleHandler {
  override suspend fun handleJarvisSchedule(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseJsonParamsObject(paramsJson)
      ?: return nodeInvokeError("INVALID_REQUEST", "expected action: set_timer, set_alarm, list, cancel, stop, snooze, pause, resume, or extend")
    val action = parseJsonString(params, "action")?.trim()?.lowercase()
      ?: return nodeInvokeError("INVALID_REQUEST", "missing action")
    return withContext(Dispatchers.IO) {
      try {
        when (action) {
          "set_timer" -> {
            val durationMs = parseJsonString(params, "durationMs")?.toLongOrNull()
              ?: return@withContext nodeInvokeError("INVALID_REQUEST", "durationMs is required")
            GatewaySession.InvokeResult.ok(scheduleResult(schedules.setTimer(durationMs, parseJsonString(params, "name").orEmpty())))
          }
          "set_alarm" -> {
            val at = parseJsonString(params, "at") ?: return@withContext nodeInvokeError("INVALID_REQUEST", "at is required")
            GatewaySession.InvokeResult.ok(scheduleResult(schedules.setAlarm(at, parseJsonString(params, "name").orEmpty())))
          }
          "list", "status" -> GatewaySession.InvokeResult.ok(listResult(schedules.schedules.value, schedules.activeAlert.value?.id))
          "cancel", "stop" -> {
            val id = resolveId(action, parseJsonString(params, "id"))
              ?: return@withContext nodeInvokeError("INVALID_REQUEST", if (action == "stop") "id is required unless exactly one due alert or active timer exists" else "id is required")
            val schedule = if (action == "cancel") schedules.cancel(id) else schedules.stop(id)
            if (schedule == null) nodeInvokeError("NOT_FOUND", "schedule not found") else GatewaySession.InvokeResult.ok(scheduleResult(schedule))
          }
          "snooze" -> {
            val id = resolveId(action, parseJsonString(params, "id"))
              ?: return@withContext nodeInvokeError("INVALID_REQUEST", "id is required unless exactly one alert is due")
            val durationMs = parseJsonString(params, "durationMs")?.toLongOrNull() ?: JarvisScheduleRepository.DEFAULT_SNOOZE_MS
            schedules.snooze(id, durationMs)?.let { GatewaySession.InvokeResult.ok(scheduleResult(it)) }
              ?: nodeInvokeError("NOT_FOUND", "schedule not found")
          }
          "pause", "resume", "extend" -> {
            val id = resolveTimerId(parseJsonString(params, "id"))
              ?: return@withContext nodeInvokeError("INVALID_REQUEST", "id is required unless exactly one timer can be changed")
            val current = schedules.schedules.value.firstOrNull { it.id == id }
              ?: return@withContext nodeInvokeError("NOT_FOUND", "schedule not found")
            val compatible =
              current.kind == ai.openclaw.app.home.timers.JarvisScheduleKind.Timer &&
                when (action) {
                  "pause" -> current.state in setOf(
                    ai.openclaw.app.home.timers.JarvisScheduleState.Scheduled,
                    ai.openclaw.app.home.timers.JarvisScheduleState.Paused,
                  )
                  "resume" -> current.state in setOf(
                    ai.openclaw.app.home.timers.JarvisScheduleState.Paused,
                    ai.openclaw.app.home.timers.JarvisScheduleState.Scheduled,
                  )
                  else -> current.state in setOf(
                    ai.openclaw.app.home.timers.JarvisScheduleState.Scheduled,
                    ai.openclaw.app.home.timers.JarvisScheduleState.Paused,
                  )
                }
            if (!compatible) return@withContext nodeInvokeError("INVALID_TIMER_STATE", "timer cannot be $action in its current state")
            val schedule = when (action) {
              "pause" -> schedules.pause(id)
              "resume" -> schedules.resume(id)
              else -> schedules.extend(id, parseJsonString(params, "durationMs")?.toLongOrNull() ?: JarvisScheduleRepository.DEFAULT_EXTEND_MS)
            }
            when {
              schedule == null -> nodeInvokeError("NOT_FOUND", "schedule not found")
              action == "pause" && schedule.state != ai.openclaw.app.home.timers.JarvisScheduleState.Paused -> nodeInvokeError("INVALID_TIMER_STATE", "only a running timer can be paused")
              action == "resume" && schedule.state != ai.openclaw.app.home.timers.JarvisScheduleState.Scheduled -> nodeInvokeError("INVALID_TIMER_STATE", "only a paused timer can be resumed")
              else -> GatewaySession.InvokeResult.ok(scheduleResult(schedule))
            }
          }
          else -> nodeInvokeError("INVALID_REQUEST", "unsupported schedule action")
        }
      } catch (error: IllegalArgumentException) {
        nodeInvokeError("INVALID_REQUEST", error.message ?: "invalid schedule")
      }
    }
  }

  private fun resolveTimerId(requested: String?): String? {
    if (!requested.isNullOrBlank()) return requested
    return schedules.schedules.value.filter { it.kind == ai.openclaw.app.home.timers.JarvisScheduleKind.Timer && it.state in setOf(ai.openclaw.app.home.timers.JarvisScheduleState.Scheduled, ai.openclaw.app.home.timers.JarvisScheduleState.Paused) }.singleOrNull()?.id
  }

  private fun resolveId(action: String, requested: String?): String? {
    if (!requested.isNullOrBlank()) return requested
    if (action != "stop" && action != "snooze") return null
    val due = schedules.schedules.value.filter { it.state == ai.openclaw.app.home.timers.JarvisScheduleState.Due }
    if (due.size == 1) return due.single().id
    if (due.isNotEmpty()) return null
    // Horizon's spoken “Stopp” targets a single actual timer when no alert is firing.
    // Never choose among several running/paused timers.
    return schedules.schedules.value
      .filter {
        it.kind == ai.openclaw.app.home.timers.JarvisScheduleKind.Timer &&
          it.state in setOf(
            ai.openclaw.app.home.timers.JarvisScheduleState.Scheduled,
            ai.openclaw.app.home.timers.JarvisScheduleState.Paused,
          )
      }
      .singleOrNull()
      ?.id
  }
}

private fun scheduleResult(schedule: JarvisSchedule): String = buildJsonObject {
  put("id", JsonPrimitive(schedule.id)); put("kind", JsonPrimitive(schedule.kind.name.lowercase()))
  put("name", JsonPrimitive(schedule.name)); put("dueAtEpochMs", JsonPrimitive(schedule.dueAtEpochMs))
  put("state", JsonPrimitive(schedule.state.name.lowercase())); schedule.durationMs?.let { put("durationMs", JsonPrimitive(it)) }
  schedule.pausedRemainingMs?.let { put("pausedRemainingMs", JsonPrimitive(it)) }
}.toString()

private fun listResult(schedules: List<JarvisSchedule>, activeId: String?): String = buildJsonObject {
  activeId?.let { put("activeAlertId", JsonPrimitive(it)) }
  put("schedules", buildJsonArray { schedules.forEach { add(kotlinx.serialization.json.Json.parseToJsonElement(scheduleResult(it))) } })
}.toString()
