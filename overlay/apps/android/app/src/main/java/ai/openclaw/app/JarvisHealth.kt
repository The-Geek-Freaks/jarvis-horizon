package ai.openclaw.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal enum class JarvisHealthStatus { Ready, Degraded, Unreachable, Unknown }

internal data class JarvisHealthComponent(
  val id: String,
  val label: String,
  val status: JarvisHealthStatus = JarvisHealthStatus.Unknown,
  val detail: String = "Ungeprüft",
  val checkedAtMs: Long? = null,
) {
  fun isStale(nowMs: Long): Boolean = checkedAtMs == null || nowMs - checkedAtMs > 150_000 || checkedAtMs > nowMs + 5_000
}

internal data class JarvisHealthSnapshot(val components: List<JarvisHealthComponent>)

private val jarvisHealthLabels = linkedMapOf("qmd" to "QMD", "cube" to "CUBE", "lancedb" to "LANCE", "hippo" to "HIPPO")

internal fun parseJarvisHealth(payload: String): JarvisHealthSnapshot {
  val root = Json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid Jarvis status")
  val rows = root["components"] as? JsonArray ?: error("Missing Jarvis components")
  val byId = rows.mapNotNull { it as? JsonObject }.associateBy { (it["id"] as? JsonPrimitive)?.contentOrNull }
  return JarvisHealthSnapshot(jarvisHealthLabels.map { (id, label) ->
    val row = byId[id]
    val timestamp = (row?.get("checkedAtMs") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    JarvisHealthComponent(
      id = id,
      label = label,
      status = if (timestamp == null) JarvisHealthStatus.Unknown else when ((row["state"] as? JsonPrimitive)?.contentOrNull) {
        "ready" -> JarvisHealthStatus.Ready
        "degraded" -> JarvisHealthStatus.Degraded
        "unreachable" -> JarvisHealthStatus.Unreachable
        else -> JarvisHealthStatus.Unknown
      },
      detail = (row?.get("detail") as? JsonPrimitive)?.contentOrNull?.take(90) ?: "Ungeprüft",
      checkedAtMs = timestamp,
    )
  })
}

internal fun unknownJarvisHealth(): List<JarvisHealthComponent> = jarvisHealthLabels.map { (id, label) -> JarvisHealthComponent(id, label) }
