package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.home.HorizonExecutionLineKind
import ai.openclaw.app.home.HorizonPresentationRepository
import ai.openclaw.app.home.HorizonPresentationUpdate
import ai.openclaw.app.home.HorizonTileType
import ai.openclaw.app.home.HomeDashboardState
import ai.openclaw.app.home.HomeConnectionState
import ai.openclaw.app.home.horizonMorningFactsJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Display-only node surface for the Horizon stage and its controlled execution transcript. */
interface HorizonPresentationHandler { suspend fun handleJarvisUi(paramsJson: String?): GatewaySession.InvokeResult }

class RepositoryHorizonPresentationHandler(
  private val repository: HorizonPresentationRepository,
  private val dashboard: () -> HomeDashboardState = { HomeDashboardState() },
  private val onExplicitUi: () -> Unit = {},
  private val onMorningBriefing: () -> Unit = {},
) : HorizonPresentationHandler {
  override suspend fun handleJarvisUi(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseJsonParamsObject(paramsJson) ?: return invalid("expected a Horizon presentation event")
    return when (parseJsonString(params, "t")?.trim()?.lowercase()) {
      "ui" -> handleUi(params)
      "exec" -> handleExec(params)
      "line" -> handleLine(params)
      "exit" -> handleExit(params)
      "status" -> {
        repository.expireIfDue()
        val tile = repository.presentation.value.tile
        GatewaySession.InvokeResult.ok(buildJsonObject {
          put("status", "ok")
          put("tile", tile?.let { buildJsonObject {
            put("id", it.id); put("show", it.type.name.lowercase()); put("data", it.data)
            put("expiresAtMs", it.expiresAtMs?.let(::JsonPrimitive) ?: JsonNull)
          } } ?: JsonNull)
        }.toString())
      }
      "briefing" -> {
        val facts = dashboard()
        if (facts.connection != HomeConnectionState.Connected) return nodeInvokeError("DATA_UNAVAILABLE", "Home Assistant is not connected")
        onMorningBriefing()
        GatewaySession.InvokeResult.ok(horizonMorningFactsJson(facts).toString())
      }
      else -> invalid("unsupported presentation event")
    }
  }

  private fun handleUi(params: JsonObject): GatewaySession.InvokeResult {
    val show = (params["show"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
    if (show == null || show == "null") {
      onExplicitUi()
      return result(repository.clear())
    }
    val type = TILE_TYPES[show] ?: return invalid("unsupported tile")
    val id = parseId(params, "id") ?: "tile-$show"
    val data = params["data"] as? JsonObject ?: JsonObject(emptyMap())
    if (data.toString().length > MAX_DATA_CHARS) return invalid("tile data is too large")
    val ttl = parseBoundedLong(params, "ttl", MIN_TTL_MS, MAX_TTL_MS)
      ?: return invalid("ttl must be between $MIN_TTL_MS and $MAX_TTL_MS")
    val sticky = parseJsonBooleanFlag(params, "sticky") ?: false
    onExplicitUi()
    return result(repository.show(id, type, data, ttl, sticky))
  }

  private fun handleExec(params: JsonObject): GatewaySession.InvokeResult {
    val id = parseId(params, "id") ?: return invalid("valid execution id is required")
    val title = parseBoundedText(params, "title", MAX_TITLE_CHARS) ?: return invalid("valid execution title is required")
    val steps = (params["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
      ?.take(MAX_STEPS)?.map { it.take(MAX_STEP_CHARS) } ?: emptyList()
    return result(repository.beginExecution(id, title, steps))
  }

  private fun handleLine(params: JsonObject): GatewaySession.InvokeResult {
    val id = parseId(params, "id") ?: return invalid("valid execution id is required")
    val kind = LINE_KINDS[parseJsonString(params, "kind")?.trim()?.lowercase()] ?: return invalid("unsupported execution line kind")
    val text = parseBoundedText(params, "text", MAX_LINE_CHARS) ?: return invalid("valid execution line text is required")
    val step = parseJsonString(params, "step")?.toIntOrNull()?.takeIf { it in 0 until MAX_STEPS }
    val progress = parseJsonDouble(params, "progress")?.takeIf { it in 0.0..1.0 }
    return result(repository.appendLine(id, kind, text, step, progress))
  }

  private fun handleExit(params: JsonObject): GatewaySession.InvokeResult {
    val id = parseId(params, "id") ?: return invalid("valid execution id is required")
    val code = parseJsonString(params, "code")?.toIntOrNull()?.takeIf { it in -255..255 } ?: return invalid("exit code is required")
    val elapsedMs = parseBoundedLong(params, "ms", 0, MAX_ELAPSED_MS) ?: return invalid("elapsed milliseconds are required")
    return result(repository.exitExecution(id, code, elapsedMs))
  }

  private fun result(update: HorizonPresentationUpdate): GatewaySession.InvokeResult = when (update) {
    is HorizonPresentationUpdate.Accepted -> GatewaySession.InvokeResult.ok(buildJsonObject { put("revision", update.revision); put("status", "accepted") }.toString())
    HorizonPresentationUpdate.NoChange -> GatewaySession.InvokeResult.ok("{\"status\":\"unchanged\"}")
    is HorizonPresentationUpdate.Rejected -> nodeInvokeError("PRESENTATION_CONFLICT", update.reason)
  }

  private fun invalid(message: String) = nodeInvokeError("INVALID_REQUEST", message)

  private fun parseId(params: JsonObject, key: String): String? =
    parseBoundedText(params, key, MAX_ID_CHARS)?.takeIf { ID.matches(it) }

  private fun parseBoundedText(params: JsonObject, key: String, max: Int): String? =
    parseJsonString(params, key)?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

  private fun parseBoundedLong(params: JsonObject, key: String, min: Long, max: Long): Long? =
    parseJsonString(params, key)?.toLongOrNull()?.takeIf { it in min..max }

  private companion object {
    const val MAX_ID_CHARS = 80
    const val MAX_TITLE_CHARS = 120
    const val MAX_STEP_CHARS = 96
    const val MAX_LINE_CHARS = 240
    const val MAX_STEPS = 12
    const val MAX_DATA_CHARS = 12_000
    const val MIN_TTL_MS = 1_000L
    const val MAX_TTL_MS = 86_400_000L
    const val MAX_ELAPSED_MS = 86_400_000L
    val ID = Regex("[A-Za-z0-9._:-]+")
    val TILE_TYPES = mapOf("weather" to HorizonTileType.Weather, "music" to HorizonTileType.Music, "camera" to HorizonTileType.Camera, "tasks" to HorizonTileType.Tasks, "terminal" to HorizonTileType.Terminal, "timer" to HorizonTileType.Timer, "home" to HorizonTileType.Home, "message" to HorizonTileType.Message)
    val LINE_KINDS = mapOf("cmd" to HorizonExecutionLineKind.Command, "out" to HorizonExecutionLineKind.Output, "ok" to HorizonExecutionLineKind.Ok, "err" to HorizonExecutionLineKind.Error, "warn" to HorizonExecutionLineKind.Warning, "done" to HorizonExecutionLineKind.Done)
  }
}
