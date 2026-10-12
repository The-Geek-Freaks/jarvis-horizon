package ai.openclaw.app.home

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A completed Alexa household announcement observed by the authenticated Home Assistant source. */
data class AlexaAnnouncement(
  val id: String,
  val sourceEntity: String,
  val sourceName: String,
  val text: String,
  val occurredAt: Instant,
)

/**
 * Extracts only the completed second step of Alexa's announcement interaction.
 *
 * Alexa Media Player first stores the spoken content and later changes the response to the
 * completion marker. Waiting for that marker avoids forwarding the command prompt or ordinary
 * speech. The returned id is stable across reconnects, but carries no user-visible text.
 */
internal fun parseAlexaAnnouncement(state: JsonObject, entityId: String): AlexaAnnouncement? {
  if (!entityId.startsWith("media_player.")) return null
  val attributes = state["attributes"] as? JsonObject ?: return null
  if (attributes["last_called"] !is JsonPrimitive) return null
  val occurredAt = parseAlexaLastCalledTimestamp(attributes["last_called_timestamp"]) ?: return null
  val text = attributes.primitiveContent("last_called_summary")?.trim()?.takeIf(String::isNotBlank) ?: return null
  if (!hasAlexaAnnouncementCompletion(attributes, occurredAt, text)) return null
  return AlexaAnnouncement(
    id = "$entityId:${occurredAt.toEpochMilli()}",
    sourceEntity = entityId,
    sourceName = attributes.primitiveContent("friendly_name")?.ifBlank { entityId } ?: entityId,
    text = text,
    occurredAt = occurredAt,
  )
}

/**
 * Accepts a fresh transition once. Comparing parsed old and new states (rather than just the
 * timestamp) admits Alexa's later completion update for a summary already seen in the prior state.
 */
internal class AlexaAnnouncementAdmission(private val maximumRememberedIds: Int = 128) {
  private val emittedIds = LinkedHashSet<String>()

  init {
    require(maximumRememberedIds > 0)
  }

  @Synchronized
  fun admit(
    newAnnouncement: AlexaAnnouncement?,
    oldAnnouncement: AlexaAnnouncement?,
    eventFiredAt: Instant?,
  ): AlexaAnnouncement? {
    val candidate = newAnnouncement ?: return null
    val firedAt = eventFiredAt ?: return null
    if (!isFreshAlexaAnnouncement(candidate.occurredAt, firedAt)) return null
    if (oldAnnouncement?.id == candidate.id || candidate.id in emittedIds) return null
    emittedIds += candidate.id
    while (emittedIds.size > maximumRememberedIds) emittedIds.remove(emittedIds.first())
    return candidate
  }
}

internal fun parseAlexaAnnouncementEvent(root: JsonObject, admission: AlexaAnnouncementAdmission): AlexaAnnouncement? {
  val event = root["event"] as? JsonObject ?: return null
  val data = event["data"] as? JsonObject ?: return null
  val entityId = data.primitiveContent("entity_id") ?: return null
  val newState = data["new_state"] as? JsonObject ?: return null
  val newAnnouncement = parseAlexaAnnouncement(newState, entityId)
  val oldAnnouncement = (data["old_state"] as? JsonObject)?.let { old -> parseAlexaAnnouncement(old, entityId) }
  val firedAt = event.primitiveContent("time_fired")?.toAlexaInstantOrNull()
  return admission.admit(newAnnouncement, oldAnnouncement, firedAt)
}

/**
 * Accepts Jarvis's explicit outgoing-announcement receipt rather than trying to infer it from
 * Alexa Media Player history. The authenticated HA event carries a distinct id namespace so it
 * cannot collide with physical Alexa interactions.
 */
internal fun parseJarvisAnnouncementEvent(root: JsonObject, admission: AlexaAnnouncementAdmission): AlexaAnnouncement? {
  val event = root["event"] as? JsonObject ?: return null
  if (event.primitiveContent("event_type") != JARVIS_ANNOUNCEMENT_EVENT_TYPE) return null
  val data = event["data"] as? JsonObject ?: return null
  val eventId = data.primitiveContent("id")?.let { raw -> runCatching { UUID.fromString(raw).toString() }.getOrNull() } ?: return null
  val text = data.primitiveContent("text")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAXIMUM_JARVIS_ANNOUNCEMENT_TEXT_LENGTH } ?: return null
  if (data.primitiveContent("sourceName") != JARVIS_ANNOUNCEMENT_SOURCE_NAME) return null
  val occurredAt = data.primitiveContent("occurredAt")?.toAlexaInstantOrNull() ?: return null
  val firedAt = event.primitiveContent("time_fired")?.toAlexaInstantOrNull()
  return admission.admit(
    AlexaAnnouncement(
      id = "$JARVIS_ANNOUNCEMENT_SOURCE_ENTITY:$eventId",
      sourceEntity = JARVIS_ANNOUNCEMENT_SOURCE_ENTITY,
      sourceName = JARVIS_ANNOUNCEMENT_SOURCE_NAME,
      text = text,
      occurredAt = occurredAt,
    ),
    oldAnnouncement = null,
    eventFiredAt = firedAt,
  )
}

internal fun isFreshAlexaAnnouncement(occurredAt: Instant, eventFiredAt: Instant): Boolean {
  val age = Duration.between(occurredAt, eventFiredAt)
  return age >= MAXIMUM_ALEXA_ANNOUNCEMENT_FUTURE_SKEW.negated() && age <= MAXIMUM_ALEXA_ANNOUNCEMENT_AGE
}

internal fun isAlexaAnnouncementCompletion(response: String?): Boolean =
  response
    ?.trim()
    ?.lowercase()
    ?.replace(Regex("[\\p{P}\\s]+"), "")
    ?.let { normalized -> normalized == "ankündigung" || normalized == "ansage" }
    ?: false

private fun hasAlexaAnnouncementCompletion(
  attributes: JsonObject,
  occurredAt: Instant,
  summary: String,
): Boolean {
  if (isAlexaAnnouncementCompletion(attributes.primitiveContent("last_called_response"))) return true
  val records = attributes["history_records"] as? JsonArray ?: return false
  return records.any { recordElement ->
    val record = recordElement as? JsonObject ?: return@any false
    parseAlexaLastCalledTimestamp(record["timestamp"]) == occurredAt &&
      record.primitiveContent("summary")?.trim() == summary &&
      isAlexaAnnouncementCompletion(record.primitiveContent("response"))
  }
}

private fun parseAlexaLastCalledTimestamp(value: kotlinx.serialization.json.JsonElement?): Instant? {
  val epochMillis = (value as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
  return runCatching { Instant.ofEpochMilli(epochMillis) }.getOrNull()
}

private fun String.toAlexaInstantOrNull(): Instant? =
  runCatching { Instant.parse(this) }.getOrNull()
    // Android 11's Instant parser rejects HA's +00:00 suffix; its offset parser accepts it.
    ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

private fun JsonObject.primitiveContent(key: String): String? =
  (this[key] as? JsonPrimitive)?.contentOrNull

private val MAXIMUM_ALEXA_ANNOUNCEMENT_FUTURE_SKEW: Duration = Duration.ofSeconds(15)
private val MAXIMUM_ALEXA_ANNOUNCEMENT_AGE: Duration = Duration.ofSeconds(90)
private const val JARVIS_ANNOUNCEMENT_EVENT_TYPE = "jarvis_announcement"
private const val JARVIS_ANNOUNCEMENT_SOURCE_ENTITY = "jarvis"
private const val JARVIS_ANNOUNCEMENT_SOURCE_NAME = "Jarvis"
private const val MAXIMUM_JARVIS_ANNOUNCEMENT_TEXT_LENGTH = 2_000
