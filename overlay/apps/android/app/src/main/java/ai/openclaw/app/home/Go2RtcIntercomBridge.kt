package ai.openclaw.app.home

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.VideoSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One-use Home Assistant WebRTC proxy route for the fixed local Ring source. */
internal data class Go2RtcIntercomRoute(val webSocketUrl: String, val cameraEntity: String)

/**
 * Speaks the installed custom WebRTC integration's go2rtc WS framing:
 * webrtc/offer and webrtc/answer carry SDP in `value`; candidates are raw
 * candidate strings. The Home Assistant link is ephemeral and is created by
 * the repository with its existing authenticated REST client.
 */
internal class Go2RtcIntercomBridge(
  private val client: OkHttpClient,
  private val route: Go2RtcIntercomRoute,
  private val session: RingWebRtcSession,
  private val renderer: VideoSink,
  private val onFailure: () -> Unit,
) : WebSocketListener() {
  private val json = Json { ignoreUnknownKeys = true }
  private val callbackHandler = Handler(Looper.getMainLooper())
  private var socket: WebSocket? = null
  @Volatile private var closed = false

  fun connect() {
    socket = client.newWebSocket(Request.Builder().url(route.webSocketUrl).build(), this)
  }

  fun sendOffer(sdp: String) {
    if (closed || socket?.send(message("webrtc/offer", sdp)) != true) fail()
  }

  fun sendCandidate(candidate: IceCandidate) {
    if (!closed && socket?.send(message("webrtc/candidate", candidate.sdp)) != true) fail()
  }

  override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
    if (closed) return
    session.setSession("jarvis-go2rtc")
    session.attach(renderer, emptyList(), null)
  }

  override fun onMessage(webSocket: WebSocket, text: String) {
    if (closed) return
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
    val type = root["type"]?.jsonPrimitive?.content.orEmpty()
    val value = root["value"]?.jsonPrimitive?.content.orEmpty()
    when (type) {
      "webrtc/answer" -> {
        if (value.isBlank()) fail() else {
          session.acceptAnswer(value)
        }
      }
      "webrtc/candidate" -> if (value.isNotBlank()) session.addCandidate(IceCandidate("0", 0, value))
      "error" -> fail()
    }
  }

  override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) = fail()

  fun close() {
    if (closed) return
    closed = true
    socket?.close(1000, "camera closed")
    socket = null
  }

  private fun fail() {
    if (closed) return
    closed = true
    socket?.close(1011, "intercom failed")
    socket = null
    // sendOffer/sendCandidate may run on a native WebRTC observer stack.
    // Repository failure handling can dispose the peer, so unwind first.
    callbackHandler.post { onFailure() }
  }

  private fun message(type: String, value: String): String =
    "{\"type\":\"$type\",\"value\":${json.encodeToString(String.serializer(), value)}}"
}
