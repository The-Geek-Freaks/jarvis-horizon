package ai.openclaw.app.ui.horizon

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import ai.openclaw.app.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Horizon renderer is intentionally a local document. It has no Javascript
 * interface, no network privileges and receives facts through evaluateJavascript
 * only after its own asset document has completed loading.
 */
private const val HORIZON_ORIGIN = "https://appassets.androidplatform.net"
private const val HORIZON_PAGE = "$HORIZON_ORIGIN/assets/jarvis/horizon/index.html"
private const val ARCHOS_HELLO_DEVICE = "ac70hello"

/**
 * The Archos Hello's PowerVR WebView compositor repeatedly rasterizes the
 * static Horizon depth stack while the orb advances (measured near 650 ms p50).
 * The document opt-in keeps its seven fixed background layers until WebView dies.
 */
internal fun usesCachedHorizonBackdrop(device: String): Boolean =
  device.equals(ARCHOS_HELLO_DEVICE, ignoreCase = true)

/** Fixed physical Show canvas versus the same document's responsive phone layout. */
internal enum class JarvisHorizonLayout {
  EchoFixed,
  Adaptive,
}

/**
 * Horizon only renders one live waveform at a time. Keeping that ownership
 * explicit prevents the inactive PCM meter from crossing into WebView and
 * waking its JavaScript bridge during a conversation.
 */
internal enum class HorizonAudioSource(val wireName: String) {
  User("user"),
  Jarvis("jarvis"),
  Silent("user"),
}

internal data class HorizonAudioLevel(
  val source: HorizonAudioSource,
  val value: Float,
) {
  val normalizedValue: Float
    get() = if (source == HorizonAudioSource.Silent) 0f else value.coerceIn(0f, 1f)
}

internal fun jarvisHorizonPageUrl(
  layout: JarvisHorizonLayout,
  device: String = "",
): String {
  val query = listOfNotNull(
    "layout=adaptive".takeIf { layout == JarvisHorizonLayout.Adaptive },
    "backdrop=cached".takeIf { usesCachedHorizonBackdrop(device) },
  ).joinToString("&")
  return if (query.isEmpty()) HORIZON_PAGE else "$HORIZON_PAGE?$query"
}

internal data class HorizonSnapshot(
  val mode: String,
  val night: Boolean,
  val muted: Boolean,
  val offline: Boolean,
  val clockTime: String,
  val clockDate: String,
  val clockSeconds: Int,
  val weatherTemperature: String? = null,
  val weatherSummary: String? = null,
  val weatherLocation: String? = null,
  val weatherSource: String? = null,
  val weatherWind: String? = null,
  val weatherHumidity: String? = null,
  val weatherRain: String? = null,
  val weatherUv: String? = null,
  val weatherForecast: JsonElement? = null,
  val nextEventTime: String? = null,
  val nextEventTitle: String? = null,
  val nextEventMeta: String? = null,
  val captionId: String? = null,
  val captionSpeaker: String? = null,
  val captionText: String? = null,
  val captionStreaming: Boolean = false,
  val services: List<HorizonService> = emptyList(),
  val media: HorizonMedia? = null,
  val tile: JsonObject? = null,
)

internal data class HorizonService(val id: String, val status: String, val detail: String)

internal data class HorizonMedia(
  val playing: Boolean,
  val title: String? = null,
  val artist: String? = null,
  val position: Double? = null,
  val duration: Double? = null,
  val source: String? = null,
  val volume: Int? = null,
  val artworkDataUrl: String? = null,
)

internal fun HorizonSnapshot.toJson(): JsonObject = buildJsonObject {
  put("mode", mode)
  put("night", night)
  put("muted", muted)
  put("offline", offline)
  put("clock", buildJsonObject {
    put("time", clockTime)
    put("date", clockDate)
    put("seconds", clockSeconds)
  })
  put("weather", buildJsonObject {
    put("temperature", weatherTemperature.jsonOrNull())
    put("summary", weatherSummary.jsonOrNull())
    put("location", weatherLocation.jsonOrNull())
    put("updated", JsonNull)
    put("source", weatherSource.jsonOrNull())
    put("wind", weatherWind.jsonOrNull())
    put("humidity", weatherHumidity.jsonOrNull())
    put("rain", weatherRain.jsonOrNull())
    put("uv", weatherUv.jsonOrNull())
    put("forecast", weatherForecast ?: JsonNull)
  })
  put("nextEvent", if (nextEventTitle == null) JsonNull else buildJsonObject {
    put("time", nextEventTime.jsonOrNull())
    put("title", nextEventTitle)
    put("meta", nextEventMeta.jsonOrNull())
  })
  put("caption", if (captionText.isNullOrBlank()) JsonNull else buildJsonObject {
    put("id", captionId.jsonOrNull())
    put("speaker", captionSpeaker.jsonOrNull())
    put("text", captionText)
    put("streaming", captionStreaming)
  })
  put("services", buildJsonArray {
    services.forEach { service -> add(buildJsonObject {
      put("id", service.id)
      put("status", service.status)
      put("detail", service.detail)
    }) }
  })
  put("media", media?.toJson() ?: JsonNull)
  put("tile", tile ?: JsonNull)
}

private fun HorizonMedia.toJson(): JsonObject = buildJsonObject {
  put("playing", playing)
  put("title", title.jsonOrNull())
  put("artist", artist.jsonOrNull())
  put("position", position?.let(::JsonPrimitive) ?: JsonNull)
  put("duration", duration?.let(::JsonPrimitive) ?: JsonNull)
  put("source", source.jsonOrNull())
  put("volume", volume?.let(::JsonPrimitive) ?: JsonNull)
  put("artworkDataUrl", artworkDataUrl.jsonOrNull())
}

private fun String?.jsonOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

/** Keeps the native-to-WebView bridge small while the clock advances every second. */
internal sealed interface HorizonSnapshotUpdate {
  val payload: JsonObject

  data class Full(override val payload: JsonObject) : HorizonSnapshotUpdate

  data class Clock(override val payload: JsonObject) : HorizonSnapshotUpdate
}

internal class HorizonSnapshotTransport {
  private var lastNonClockSnapshot: HorizonSnapshot? = null

  fun next(snapshot: HorizonSnapshot): HorizonSnapshotUpdate {
    val nonClockSnapshot = snapshot.copy(clockTime = "", clockDate = "", clockSeconds = 0)
    if (nonClockSnapshot != lastNonClockSnapshot) {
      lastNonClockSnapshot = nonClockSnapshot
      return HorizonSnapshotUpdate.Full(snapshot.toJson())
    }
    return HorizonSnapshotUpdate.Clock(
      buildJsonObject {
        put(
          "clock",
          buildJsonObject {
            put("time", snapshot.clockTime)
            put("date", snapshot.clockDate)
            put("seconds", snapshot.clockSeconds)
          },
        )
      },
    )
  }
}

@Composable
internal fun JarvisHorizonWebView(
  snapshot: HorizonSnapshot,
  audioLevel: HorizonAudioLevel,
  layout: JarvisHorizonLayout,
  paused: Boolean = false,
  modifier: Modifier = Modifier,
) {
  var rendererGeneration by remember { mutableIntStateOf(0) }
  var rendererRecoveryCount by remember { mutableIntStateOf(0) }
  val controller = remember(layout, rendererGeneration) { HorizonWebController() }
  val transport = remember(controller) { HorizonSnapshotTransport() }
  key(layout, rendererGeneration) {
    AndroidView(
      factory = { context ->
        createHorizonWebView(
          context = context,
          pageUrl = jarvisHorizonPageUrl(layout, device = Build.DEVICE),
          onReady = controller::markReady,
          onRendererGone = {
            // A dead renderer must not spin the small Show indefinitely. A
            // later screen recreation gives it a fresh bounded attempt.
            if (rendererRecoveryCount < 2) {
              rendererRecoveryCount += 1
              rendererGeneration += 1
            }
          },
        ).also(controller::attach)
      },
      update = { webView ->
        controller.attach(webView)
      },
      onRelease = controller::destroy,
      modifier = modifier,
    )
  }
  LaunchedEffect(controller, snapshot) {
    when (val update = transport.next(snapshot)) {
      is HorizonSnapshotUpdate.Full -> controller.updateFull(update.payload.toString())
      is HorizonSnapshotUpdate.Clock -> controller.updateClock(update.payload.toString())
    }
  }
  LaunchedEffect(controller, snapshot.mode) {
    when (snapshot.mode) {
      "listening" -> controller.event("{type:'wake'}")
      "success" -> controller.event("{type:'success'}")
    }
  }
  LaunchedEffect(controller, audioLevel) {
    controller.updateAudioLevel(audioLevel)
  }
  LaunchedEffect(controller, paused) {
    controller.setPaused(paused)
  }
  DisposableEffect(controller) { onDispose { controller.destroy() } }
}

internal class HorizonWebController(
  private val evaluateOverride: ((String) -> Unit)? = null,
) {
  private var view: WebView? = null
  private var ready = false
  private var fullSnapshot: String? = null
  private var clockSnapshot: String? = null
  private var audioLevel = HorizonAudioLevel(HorizonAudioSource.Silent, 0f)
  private var paused = false

  fun attach(webView: WebView) {
    if (view === webView) return
    view = webView
    ready = false
    if (paused) webView.onPause()
  }

  fun markReady() {
    // WebView may report the same completed page more than once. The current
    // state was already delivered, so a duplicate callback must not recreate
    // a silent-level bridge allocation. attach() resets this for a real reload.
    if (ready) return
    ready = true
    // A renderer can finish loading while a full-screen camera is already
    // covering it. Keep the current state locally; resume sends one fresh
    // snapshot instead of waking a hidden scene only to pause it again.
    if (paused) {
      setDocumentPaused(true)
      return
    }
    fullSnapshot?.let(::sendSnapshot)
    clockSnapshot?.let(::sendSnapshot)
    sendLevel(audioLevel)
  }

  fun updateFull(value: String) {
    fullSnapshot = value
    clockSnapshot = null
    if (ready && !paused) sendSnapshot(value)
  }

  fun updateClock(value: String) {
    clockSnapshot = value
    if (ready && !paused) sendSnapshot(value)
  }

  fun updateAudioLevel(next: HorizonAudioLevel) {
    val normalized = next.copy(value = next.normalizedValue)
    // Same visible source and amplitude cannot alter the orb. Avoid a fresh
    // evaluateJavascript allocation while preserving the newest real value.
    if (audioLevel.source.wireName == normalized.source.wireName &&
      audioLevel.normalizedValue == normalized.normalizedValue) return
    audioLevel = normalized
    if (ready && !paused) sendLevel(normalized)
  }

  fun event(value: String) {
    // Wake/success pulses are momentary. Replaying them after a full-screen
    // camera closes would show an event that already happened behind it.
    if (ready && !paused) evaluate("window.JarvisHorizon&&window.JarvisHorizon.event($value);")
  }

  fun setPaused(value: Boolean) {
    if (paused == value) return
    paused = value
    if (value) {
      setDocumentPaused(true)
      view?.onPause()
    } else {
      view?.onResume()
      if (ready) {
        fullSnapshot?.let(::sendSnapshot)
        clockSnapshot?.let(::sendSnapshot)
        sendLevel(audioLevel)
        setDocumentPaused(false)
      }
    }
  }

  fun destroy(webView: WebView? = view) {
    if (view === webView) {
      ready = false
      view = null
    }
    webView?.apply {
      stopLoading()
      loadUrl("about:blank")
      destroy()
    }
  }

  private fun sendSnapshot(value: String) = evaluate("window.JarvisHorizon&&window.JarvisHorizon.update($value);")
  private fun sendLevel(level: HorizonAudioLevel) {
    evaluate("window.JarvisHorizon&&window.JarvisHorizon.level({src:${JsonPrimitive(level.source.wireName)},v:${level.normalizedValue}});")
  }
  private fun evaluate(script: String) = evaluateOverride?.invoke(script) ?: view?.evaluateJavascript(script, null)
  private fun setDocumentPaused(value: Boolean) =
    evaluate("window.JarvisHorizon&&window.JarvisHorizon.setPaused($value);")
}

@SuppressLint("SetJavaScriptEnabled")
private fun createHorizonWebView(
  context: Context,
  pageUrl: String,
  onReady: () -> Unit,
  onRendererGone: () -> Unit,
): WebView {
  val loader = WebViewAssetLoader.Builder()
    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
    .build()
  return WebView(context).apply {
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    setBackgroundColor(Color.TRANSPARENT)
    // Keep the window's hardware acceleration without forcing a second,
    // offscreen texture layer around WebView's own compositor.
    setLayerType(View.LAYER_TYPE_NONE, null)
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    overScrollMode = View.OVER_SCROLL_NEVER
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = false
      allowFileAccess = false
      allowContentAccess = false
      blockNetworkLoads = true
      loadsImagesAutomatically = true
      mediaPlaybackRequiresUserGesture = true
      useWideViewPort = true
      loadWithOverviewMode = true
      setSupportZoom(false)
      builtInZoomControls = false
      displayZoomControls = false
    }
    webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.toString().startsWith(HORIZON_ORIGIN).not()

      override fun onPageFinished(view: WebView, url: String) {
        if (url == pageUrl) onReady()
      }

      override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone()
        return true
      }
    }
    loadUrl(pageUrl)
  }
}
