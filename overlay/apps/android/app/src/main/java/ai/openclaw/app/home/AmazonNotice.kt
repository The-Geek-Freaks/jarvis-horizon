package ai.openclaw.app.home

import ai.openclaw.app.NotificationForwardingPolicy
import ai.openclaw.app.NotificationPackageFilterMode
import ai.openclaw.app.allowsPackage
import ai.openclaw.app.isWithinQuietHours
import ai.openclaw.app.node.AMAZON_ALEXA_PACKAGE
import ai.openclaw.app.node.AMAZON_SHOPPING_PACKAGE
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
 * A display-only Amazon notice admitted by the source Android notification policy.
 *
 * This intentionally carries no notification action, session key or instruction semantics. The
 * owner may transport it through Home Assistant, but endpoint presentation must never execute its
 * text as a Jarvis command.
 */
internal data class AmazonNotice(
  val id: String,
  val sourcePackage: String,
  val sourceNodeId: String,
  val title: String?,
  val text: String?,
  val subText: String?,
  val postedAt: Instant,
)

/** Source fields from one posted Android notification; removal events never create a notice. */
internal data class AmazonNoticeCandidate(
  val key: String,
  val packageName: String,
  val postTimeMs: Long,
  val title: String?,
  val text: String?,
  val subText: String?,
)

/**
 * Reuses the notification-forwarding consent as the source gate. This keeps arbitrary Android
 * notifications, native channel traffic and quiet-hour content out of the household event stream.
 */
internal fun admitAmazonNotice(
  candidate: AmazonNoticeCandidate,
  sourceNodeId: String,
  policy: NotificationForwardingPolicy,
  nowEpochMs: Long = System.currentTimeMillis(),
): AmazonNotice? {
  val normalizedPackage = candidate.packageName.trim()
  val normalizedNode = sourceNodeId.trim()
  val normalizedKey = candidate.key.trim()
  if (
    normalizedPackage !in AMAZON_NOTICE_PACKAGES ||
      normalizedNode.isEmpty() ||
      normalizedKey.isEmpty() ||
      candidate.postTimeMs <= 0L ||
      !policy.enabled ||
      policy.mode != NotificationPackageFilterMode.Allowlist ||
      !policy.allowsPackage(normalizedPackage) ||
      policy.isWithinQuietHours(nowEpochMs)
  ) {
    return null
  }
  val title = candidate.title.noticeTextOrNull()
  val text = candidate.text.noticeTextOrNull()
  val subText = candidate.subText.noticeTextOrNull()
  if (title == null && text == null && subText == null) return null
  val postedAt = runCatching { Instant.ofEpochMilli(candidate.postTimeMs) }.getOrNull() ?: return null
  return AmazonNotice(
    id = amazonNoticeId(normalizedNode, normalizedKey, candidate.postTimeMs),
    sourcePackage = normalizedPackage,
    sourceNodeId = normalizedNode,
    title = title,
    text = text,
    subText = subText,
    postedAt = postedAt,
  )
}

/**
 * Parses the exact `notifications.changed` wire payload emitted by
 * DeviceNotificationListenerService. Keeping this at the boundary makes a renamed/missing field
 * fail closed rather than silently turn a generic notification into a household notice.
 */
internal fun admitPostedAmazonNoticePayload(
  payloadJson: String?,
  sourceNodeId: String,
  policy: NotificationForwardingPolicy,
  nowEpochMs: Long = System.currentTimeMillis(),
): AmazonNotice? {
  val payload = runCatching { Json.parseToJsonElement(payloadJson ?: "").jsonObject }.getOrNull() ?: return null
  if (payload.primitiveContent("change")?.trim()?.lowercase() != "posted") return null
  val postTimeMs = payload.primitiveContent("postTimeMs")?.toLongOrNull() ?: return null
  return admitAmazonNotice(
    candidate =
      AmazonNoticeCandidate(
        key = payload.primitiveContent("key").orEmpty(),
        packageName = payload.primitiveContent("packageName").orEmpty(),
        postTimeMs = postTimeMs,
        title = payload.primitiveContent("title"),
        text = payload.primitiveContent("text"),
        subText = payload.primitiveContent("subText"),
      ),
    sourceNodeId = sourceNodeId,
    policy = policy,
    nowEpochMs = nowEpochMs,
  )
}

/** Builds the sole HA event payload; callers may only send it over an already-authenticated socket. */
internal fun AmazonNotice.toHomeAssistantEventData(): JsonObject =
  buildJsonObject {
    put("id", JsonPrimitive(id))
    put("sourcePackage", JsonPrimitive(sourcePackage))
    put("sourceNodeId", JsonPrimitive(sourceNodeId))
    put("postedAt", JsonPrimitive(postedAt.toString()))
    title?.let { put("title", JsonPrimitive(it)) }
    text?.let { put("text", JsonPrimitive(it)) }
    subText?.let { put("subText", JsonPrimitive(it)) }
  }

/** Prevents retained HA events and reconnect replay from replacing a current Horizon card. */
internal class AmazonNoticeAdmission(private val maximumRememberedIds: Int = 128) {
  private val emittedIds = LinkedHashSet<String>()

  init {
    require(maximumRememberedIds > 0)
  }

  @Synchronized
  fun admit(notice: AmazonNotice?, eventFiredAt: Instant?): AmazonNotice? {
    val candidate = notice ?: return null
    val firedAt = eventFiredAt ?: return null
    val age = Duration.between(candidate.postedAt, firedAt)
    if (age < MAXIMUM_AMAZON_NOTICE_FUTURE_SKEW.negated() || age > MAXIMUM_AMAZON_NOTICE_AGE) return null
    if (!emittedIds.add(candidate.id)) return null
    while (emittedIds.size > maximumRememberedIds) emittedIds.remove(emittedIds.first())
    return candidate
  }
}

/** Reserves an accepted source notification before its one HA send, without retaining an inbox. */
internal class AmazonNoticeSourceDeduplicator(private val maximumRememberedIds: Int = 128) {
  private val sentIds = LinkedHashSet<String>()

  init {
    require(maximumRememberedIds > 0)
  }

  @Synchronized
  fun reserve(id: String): Boolean {
    if (!sentIds.add(id)) return false
    while (sentIds.size > maximumRememberedIds) sentIds.remove(sentIds.first())
    return true
  }

  /** A failed socket handoff was not a delivery; permit a later real notification callback. */
  @Synchronized
  fun release(id: String) {
    sentIds.remove(id)
  }
}

/** Parses only the exact private HA event shape emitted by [toHomeAssistantEventData]. */
internal fun parseJarvisAmazonNoticeEvent(root: JsonObject, admission: AmazonNoticeAdmission): AmazonNotice? {
  val event = root["event"] as? JsonObject ?: return null
  if (event.primitiveContent("event_type") != JARVIS_AMAZON_NOTICE_EVENT_TYPE) return null
  val data = event["data"] as? JsonObject ?: return null
  val id = data.primitiveContent("id")?.takeIf(AMAZON_NOTICE_ID::matches) ?: return null
  val sourcePackage = data.primitiveContent("sourcePackage")?.takeIf { it in AMAZON_NOTICE_PACKAGES } ?: return null
  val sourceNodeId = data.primitiveContent("sourceNodeId")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SOURCE_NODE_ID_CHARS } ?: return null
  val postedAt = data.primitiveContent("postedAt")?.toAmazonInstantOrNull() ?: return null
  val title = data.primitiveContent("title").noticeTextOrNull()
  val text = data.primitiveContent("text").noticeTextOrNull()
  val subText = data.primitiveContent("subText").noticeTextOrNull()
  if (title == null && text == null && subText == null) return null
  val firedAt = event.primitiveContent("time_fired")?.toAmazonInstantOrNull()
  return admission.admit(
    AmazonNotice(id, sourcePackage, sourceNodeId, title, text, subText, postedAt),
    firedAt,
  )
}

private fun amazonNoticeId(sourceNodeId: String, key: String, postTimeMs: Long): String =
  MessageDigest
    .getInstance("SHA-256")
    .digest("$sourceNodeId\u0000$key\u0000$postTimeMs".toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String?.noticeTextOrNull(): String? =
  this?.trim()?.take(MAX_AMAZON_NOTICE_TEXT_CHARS)?.takeIf { it.isNotEmpty() }

private fun String.toAmazonInstantOrNull(): Instant? =
  runCatching { Instant.parse(this) }.getOrNull()
    ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

private fun JsonObject.primitiveContent(key: String): String? =
  (this[key] as? JsonPrimitive)?.contentOrNull

internal const val JARVIS_AMAZON_NOTICE_EVENT_TYPE = "jarvis_amazon_notice"
private val AMAZON_NOTICE_PACKAGES = setOf(AMAZON_SHOPPING_PACKAGE, AMAZON_ALEXA_PACKAGE)
private val AMAZON_NOTICE_ID = Regex("[a-f0-9]{64}")
private const val MAX_AMAZON_NOTICE_TEXT_CHARS = 512
private const val MAX_SOURCE_NODE_ID_CHARS = 128
// Echo nodes have been observed about two minutes ahead of the HA host. This keeps an otherwise
// fresh local source event deliverable while the short maximum age still rejects stale replays.
private val MAXIMUM_AMAZON_NOTICE_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
private val MAXIMUM_AMAZON_NOTICE_AGE: Duration = Duration.ofSeconds(90)
