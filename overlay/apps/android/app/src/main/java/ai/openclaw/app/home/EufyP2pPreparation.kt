package ai.openclaw.app.home

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

enum class EufyP2pState { Active, Preparing, NeedsStart }

/**
 * HA's `streaming` camera state can mean either RTSP or P2P.  The bridge's
 * exact companion provider sensor makes the distinction; only P2P is reused.
 */
internal fun eufyP2pState(cameraState: String, providerState: String?, hasLoopbackPublisher: Boolean = false): EufyP2pState = when {
  // An installed provider sensor is authoritative over the legacy URL hint.
  providerState != null && providerState.equals("P2P", ignoreCase = true) && cameraState.equals("streaming", ignoreCase = true) -> EufyP2pState.Active
  providerState != null && providerState.equals("P2P", ignoreCase = true) && cameraState.equals("preparing", ignoreCase = true) -> EufyP2pState.Preparing
  providerState == null && hasLoopbackPublisher && cameraState.equals("streaming", ignoreCase = true) -> EufyP2pState.Active
  else -> EufyP2pState.NeedsStart
}

/**
 * Legacy Eufy bridges lack a provider sensor for some cameras. Their exact
 * stream-url entity is accepted only when it is a credential-free loopback
 * go2rtc publisher with the serial-shaped path the bridge creates. The raw
 * URL never leaves this predicate or the parsed Home Assistant catalog.
 */
internal fun isEufyLoopbackPublisherUrl(value: String?): Boolean {
  val uri = value?.let { raw -> runCatching { URI(raw) }.getOrNull() } ?: return false
  return uri.scheme.equals("rtsp", ignoreCase = true) &&
    uri.host == "127.0.0.1" &&
    uri.port == 8554 &&
    uri.userInfo == null &&
    uri.query == null &&
    uri.fragment == null &&
    uri.path?.removePrefix("/")?.matches(Regex("[A-Za-z0-9_-]{8,80}")) == true
}

/**
 * A short-lived, generation-owned Eufy readiness transaction.  The bridge
 * accepts button.press before its publisher has frames; HA's camera state is
 * therefore polled until it reports `streaming`, never guessed after a sleep.
 */
internal class EufyP2pPreparation(
  private val http: OkHttpClient,
  private val nowNanos: () -> Long = System::nanoTime,
  private val waitForPoll: suspend (Long) -> Unit = { delay(it) },
) {
  suspend fun prepare(
    baseUrl: HttpUrl,
    token: String,
    cameraEntityId: String,
    providerEntityId: String?,
    startButtonEntityId: String?,
    maxWaitMillis: Long,
    stillCurrent: () -> Boolean,
    registerCall: (Call) -> Boolean,
    unregisterCall: (Call) -> Unit,
  ): Boolean {
    require(maxWaitMillis > 0)
    val deadlineNanos = nowNanos() + TimeUnit.MILLISECONDS.toNanos(maxWaitMillis)
    if (!stillCurrent()) return false
    if (startButtonEntityId != null && !postStart(baseUrl, token, cameraEntityId, deadlineNanos, stillCurrent, registerCall, unregisterCall)) return false
    while (stillCurrent()) {
      if (remainingMillis(deadlineNanos) <= 0L) return false
      when (readEntityState(baseUrl, token, cameraEntityId, deadlineNanos, stillCurrent, registerCall, unregisterCall)) {
        "streaming" -> {
          // Legacy Eufy catalogs can legitimately lack the provider sensor.
          // This fallback is only reached after this generation's authorized
          // P2P service start returned success; it never reuses an unknown stream.
          if (providerEntityId == null || readEntityState(baseUrl, token, providerEntityId, deadlineNanos, stillCurrent, registerCall, unregisterCall)
            .equals("P2P", ignoreCase = true)) return true
        }
        null -> return false
      }
      val remaining = remainingMillis(deadlineNanos)
      if (remaining <= 0L) return false
      // Condition-based polling: every delay is followed by a fresh HA state
      // read, and never used as a presumed camera-start completion.
      waitForPoll(minOf(POLL_INTERVAL_MILLIS, remaining))
    }
    return false
  }

  private fun postStart(
    baseUrl: HttpUrl,
    token: String,
    cameraEntityId: String,
    deadlineNanos: Long,
    stillCurrent: () -> Boolean,
    registerCall: (Call) -> Boolean,
    unregisterCall: (Call) -> Unit,
  ): Boolean {
    val request = Request.Builder()
      // The integration service owns HA's camera lifecycle: it clears stale
      // HLS/source state before starting the Eufy publisher. A direct UI
      // button press bypasses that lifecycle.
      .url(baseUrl.newBuilder().addPathSegments("api/services/eufy_security/start_p2p_livestream").build())
      .header("Authorization", "Bearer $token")
      .header("Content-Type", JSON_MEDIA_TYPE.toString())
      .post("{\"entity_id\":\"${cameraEntityId.jsonEscape()}\"}".toRequestBody(JSON_MEDIA_TYPE))
      .build()
    return executeBoolean(request, deadlineNanos, stillCurrent, registerCall, unregisterCall) { response -> response.isSuccessful } ?: false
  }

  private fun readEntityState(
    baseUrl: HttpUrl,
    token: String,
    entityId: String,
    deadlineNanos: Long,
    stillCurrent: () -> Boolean,
    registerCall: (Call) -> Boolean,
    unregisterCall: (Call) -> Unit,
  ): String? {
    val request = Request.Builder()
      .url(baseUrl.newBuilder().addPathSegments("api/states").addPathSegment(entityId).build())
      .header("Authorization", "Bearer $token")
      .build()
    return executeBoolean(request, deadlineNanos, stillCurrent, registerCall, unregisterCall) { response ->
      if (!response.isSuccessful) null else runCatching {
        Json.parseToJsonElement(response.body.bytes().decodeToString()).jsonObject["state"]
          .let { it as? JsonPrimitive }?.contentOrNull?.lowercase()
      }.getOrNull()
    }
  }

  private fun <T> executeBoolean(
    request: Request,
    deadlineNanos: Long,
    stillCurrent: () -> Boolean,
    registerCall: (Call) -> Boolean,
    unregisterCall: (Call) -> Unit,
    result: (okhttp3.Response) -> T?,
  ): T? {
    if (!stillCurrent()) return null
    val remaining = remainingMillis(deadlineNanos)
    if (remaining <= 0L) return null
    // A service call can legitimately take longer than the repository's
    // ordinary 20s read timeout while the bridge waits for a video track.
    // Keep every network phase inside the one preparation deadline instead.
    val call = http.newBuilder()
      .readTimeout(remaining, TimeUnit.MILLISECONDS)
      .callTimeout(remaining, TimeUnit.MILLISECONDS)
      .build()
      .newCall(request)
    if (!registerCall(call)) {
      call.cancel()
      return null
    }
    return try {
      if (!stillCurrent()) null else call.execute().use(result)
    } catch (_: Exception) {
      null
    } finally {
      unregisterCall(call)
    }
  }

  private fun remainingMillis(deadlineNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos()).coerceAtLeast(0L)

  private companion object {
    private const val POLL_INTERVAL_MILLIS = 150L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

private fun String.jsonEscape(): String = buildString(length) {
  this@jsonEscape.forEach { char ->
    when (char) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> append(char)
    }
  }
}
