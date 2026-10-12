package ai.openclaw.app.home

import ai.openclaw.app.NotificationForwardingPolicy
import ai.openclaw.app.NotificationPackageFilterMode
import ai.openclaw.app.isWithinQuietHours
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.LinkedHashSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A display-only preview of an incoming WhatsApp or Telegram notification.
 *
 * Native OpenClaw channels retain their own conversation and reply ownership. This record is
 * deliberately limited to a fresh notification preview for the household Horizon surface; it
 * carries no notification key, action, read receipt, or reply capability.
 */
internal data class IncomingMessageNotice(
  val id: String,
  val sourcePackage: String,
  val sourceNodeId: String,
  val channel: String,
  val sender: String,
  val text: String,
  val postedAt: Instant,
)

internal data class IncomingMessageNoticeCandidate(
  val key: String,
  val packageName: String,
  val postTimeMs: Long,
  val sender: String?,
  val text: String?,
)

private val incomingMessageChannels =
  mapOf(
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "org.telegram.messenger" to "Telegram",
  )

/** The generic notification outbox intentionally keeps these native channels out. */
internal fun isIncomingMessagePackage(packageName: String): Boolean = packageName.trim() in incomingMessageChannels

/**
 * Uses the existing notification consent without routing native channel notifications into the
 * generic Gateway outbox. Messages need an explicit Allowlist entry; a blocklist can never make
 * their private previews household-visible.
 */
internal fun allowsIncomingMessagePresentation(
  policy: NotificationForwardingPolicy,
  packageName: String,
  nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
  val normalized = packageName.trim()
  return policy.enabled &&
    policy.mode == NotificationPackageFilterMode.Allowlist &&
    normalized in incomingMessageChannels &&
    normalized in policy.packages &&
    !policy.isWithinQuietHours(nowEpochMs)
}

internal fun admitIncomingMessageNotice(
  candidate: IncomingMessageNoticeCandidate,
  sourceNodeId: String,
  policy: NotificationForwardingPolicy,
  nowEpochMs: Long = System.currentTimeMillis(),
): IncomingMessageNotice? {
  val sourcePackage = candidate.packageName.trim()
  val sourceNode = sourceNodeId.trim()
  val key = candidate.key.trim()
  val channel = incomingMessageChannels[sourcePackage] ?: return null
  if (
    sourceNode.isEmpty() ||
      key.isEmpty() ||
      candidate.postTimeMs <= 0L ||
      !allowsIncomingMessagePresentation(policy, sourcePackage, nowEpochMs)
  ) {
    return null
  }
  val sender = candidate.sender.messageTextOrNull() ?: return null
  val text = candidate.text.messageTextOrNull() ?: return null
  val postedAt = runCatching { Instant.ofEpochMilli(candidate.postTimeMs) }.getOrNull() ?: return null
  return IncomingMessageNotice(
    id = incomingMessageNoticeId(sourceNode, key, candidate.postTimeMs),
    sourcePackage = sourcePackage,
    sourceNodeId = sourceNode,
    channel = channel,
    sender = sender,
    text = text,
    postedAt = postedAt,
  )
}

/** Parses only the posted-listener payload. Notification removals cannot create a notice. */
internal fun admitPostedIncomingMessageNoticePayload(
  payloadJson: String?,
  sourceNodeId: String,
  policy: NotificationForwardingPolicy,
  nowEpochMs: Long = System.currentTimeMillis(),
): IncomingMessageNotice? {
  val payload = runCatching { Json.parseToJsonElement(payloadJson ?: "").jsonObject }.getOrNull() ?: return null
  if (payload.primitiveContent("change")?.trim()?.lowercase() != "posted") return null
  return admitIncomingMessageNotice(
    IncomingMessageNoticeCandidate(
      key = payload.primitiveContent("key").orEmpty(),
      packageName = payload.primitiveContent("packageName").orEmpty(),
      postTimeMs = payload.primitiveContent("postTimeMs")?.toLongOrNull() ?: return null,
      sender = payload.primitiveContent("title"),
      text = payload.primitiveContent("text"),
    ),
    sourceNodeId = sourceNodeId,
    policy = policy,
    nowEpochMs = nowEpochMs,
  )
}

internal fun IncomingMessageNotice.toHomeAssistantEventData(): JsonObject =
  buildJsonObject {
    put("id", JsonPrimitive(id))
    put("sourcePackage", JsonPrimitive(sourcePackage))
    put("sourceNodeId", JsonPrimitive(sourceNodeId))
    put("channel", JsonPrimitive(channel))
    put("sender", JsonPrimitive(sender))
    put("text", JsonPrimitive(text))
    put("postedAt", JsonPrimitive(postedAt.toString()))
  }

/** Rejects retained HA events and reconnect replays before a current message card can be replaced. */
internal class IncomingMessageNoticeAdmission(private val maximumRememberedIds: Int = 128) {
  private val emittedIds = LinkedHashSet<String>()

  init {
    require(maximumRememberedIds > 0)
  }

  @Synchronized
  fun admit(notice: IncomingMessageNotice?, eventFiredAt: Instant?): IncomingMessageNotice? {
    val candidate = notice ?: return null
    val firedAt = eventFiredAt ?: return null
    val age = Duration.between(candidate.postedAt, firedAt)
    if (age < MAXIMUM_INCOMING_MESSAGE_FUTURE_SKEW.negated() || age > MAXIMUM_INCOMING_MESSAGE_AGE) return null
    if (!emittedIds.add(candidate.id)) return null
    while (emittedIds.size > maximumRememberedIds) emittedIds.remove(emittedIds.first())
    return candidate
  }
}

/** Reserves a source notification until the one HA socket handoff reports success. */
internal class IncomingMessageNoticeSourceDeduplicator(private val maximumRememberedIds: Int = 128) {
  private val ids = LinkedHashSet<String>()

  init {
    require(maximumRememberedIds > 0)
  }

  @Synchronized fun reserve(id: String): Boolean {
    if (!ids.add(id)) return false
    while (ids.size > maximumRememberedIds) ids.remove(ids.first())
    return true
  }

  @Synchronized fun release(id: String) {
    ids.remove(id)
  }
}

internal fun parseJarvisIncomingMessageNoticeEvent(
  root: JsonObject,
  admission: IncomingMessageNoticeAdmission,
): IncomingMessageNotice? {
  val event = root["event"] as? JsonObject ?: return null
  if (event.primitiveContent("event_type") != JARVIS_INCOMING_MESSAGE_EVENT_TYPE) return null
  val data = event["data"] as? JsonObject ?: return null
  val id = data.primitiveContent("id")?.takeIf(INCOMING_MESSAGE_NOTICE_ID::matches) ?: return null
  val sourcePackage = data.primitiveContent("sourcePackage")?.takeIf(::isIncomingMessagePackage) ?: return null
  val channel = incomingMessageChannels[sourcePackage] ?: return null
  if (data.primitiveContent("channel") != channel) return null
  val sourceNodeId = data.primitiveContent("sourceNodeId")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SOURCE_NODE_ID_CHARS } ?: return null
  val sender = data.primitiveContent("sender").messageTextOrNull() ?: return null
  val text = data.primitiveContent("text").messageTextOrNull() ?: return null
  val postedAt = data.primitiveContent("postedAt")?.toIncomingMessageInstantOrNull() ?: return null
  val firedAt = event.primitiveContent("time_fired")?.toIncomingMessageInstantOrNull()
  return admission.admit(
    IncomingMessageNotice(id, sourcePackage, sourceNodeId, channel, sender, text, postedAt),
    firedAt,
  )
}

private fun incomingMessageNoticeId(sourceNodeId: String, key: String, postTimeMs: Long): String =
  MessageDigest.getInstance("SHA-256")
    .digest("$sourceNodeId\u0000$key\u0000$postTimeMs".toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String?.messageTextOrNull(): String? =
  this?.trim()?.take(MAX_INCOMING_MESSAGE_TEXT_CHARS)?.takeIf { it.isNotEmpty() }

private fun String.toIncomingMessageInstantOrNull(): Instant? =
  runCatching { Instant.parse(this) }.getOrNull()
    ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

private fun JsonObject.primitiveContent(key: String): String? =
  (this[key] as? JsonPrimitive)?.contentOrNull

internal const val JARVIS_INCOMING_MESSAGE_EVENT_TYPE = "jarvis_incoming_message"
private val INCOMING_MESSAGE_NOTICE_ID = Regex("[a-f0-9]{64}")
private const val MAX_INCOMING_MESSAGE_TEXT_CHARS = 512
private const val MAX_SOURCE_NODE_ID_CHARS = 128
private val MAXIMUM_INCOMING_MESSAGE_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
private val MAXIMUM_INCOMING_MESSAGE_AGE: Duration = Duration.ofSeconds(90)
