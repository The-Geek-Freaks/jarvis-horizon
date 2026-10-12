package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.NotificationForwardingPolicy
import ai.openclaw.app.home.HomeConnectionState
import ai.openclaw.app.home.HomeDashboardRepository
import ai.openclaw.app.home.CameraHlsSource
import ai.openclaw.app.home.CameraStreamFormat
import ai.openclaw.app.home.HomeDashboardState
import ai.openclaw.app.home.DoorCameraAction
import ai.openclaw.app.home.DoorCameraDisplayState
import ai.openclaw.app.home.DoorCameraLifecycle
import ai.openclaw.app.home.DoorCameraPresentation
import ai.openclaw.app.home.RingNotice
import ai.openclaw.app.home.RingCallPresentation
import ai.openclaw.app.home.RingCallSourcePhase
import ai.openclaw.app.home.ringCallPresentation
import ai.openclaw.app.home.ringCallPresentationExpiryAtMs
import ai.openclaw.app.node.DeviceNotificationListenerService
import ai.openclaw.app.node.latestAuthorizedAmazonNotice
import ai.openclaw.app.ui.horizon.JarvisHorizonLayout
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceEglRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Readable calendar panel for the fixed Jarvis layout. */
@Composable
fun JarvisHomePanel(
  viewModel: MainViewModel,
  repository: HomeDashboardRepository = HomeDashboardRepository.get(LocalContext.current),
  maxCalendarEvents: Int = Int.MAX_VALUE,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val state by repository.dashboard.collectAsState()
  val forwardingEnabled by viewModel.notificationForwardingEnabled.collectAsState()
  val forwardingMode by viewModel.notificationForwardingMode.collectAsState()
  val forwardingPackages by viewModel.notificationForwardingPackages.collectAsState()
  val quietHoursEnabled by viewModel.notificationForwardingQuietHoursEnabled.collectAsState()
  val quietStart by viewModel.notificationForwardingQuietStart.collectAsState()
  val quietEnd by viewModel.notificationForwardingQuietEnd.collectAsState()
  val snapshot by DeviceNotificationListenerService.snapshotUpdates.collectAsState()
  var policyNowEpochMs by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      val now = System.currentTimeMillis()
      delay(60_000L - (now % 60_000L))
      policyNowEpochMs = System.currentTimeMillis()
    }
  }
  val amazonNotice =
    latestAuthorizedAmazonNotice(
      snapshot = snapshot,
      policy =
        NotificationForwardingPolicy(
          enabled = forwardingEnabled,
          mode = forwardingMode,
          packages = forwardingPackages,
          quietHoursEnabled = quietHoursEnabled,
          quietStart = quietStart,
          quietEnd = quietEnd,
          maxEventsPerMinute = 1,
          sessionKey = null,
          selfPackageName = context.packageName,
        ),
      nowEpochMs = policyNowEpochMs,
    )
  val formatter = remember { DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault()) }
  // A compact HUD has a finite height. Rendering a selected prefix rather than
  // scrolling a clipped third row keeps every visible appointment intact.
  // An Amazon notice reserves enough room for one complete appointment too.
  val visibleEventLimit =
    (if (amazonNotice == null) maxCalendarEvents else minOf(maxCalendarEvents, 1))
      .coerceAtLeast(0)
  val visibleEvents = state.calendarEvents.take(visibleEventLimit)
  val hiddenEventCount = state.calendarEvents.size - visibleEvents.size
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("HEUTE", color = androidx.compose.ui.graphics.Color(0xFFE6F1FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
      if (state.connection != HomeConnectionState.Connected) {
        Text(text = homeConnectionLabel(state), color = androidx.compose.ui.graphics.Color(0xFFA9BDD2), fontSize = 11.sp)
      }
    }
    amazonNotice?.let { notice ->
      AmazonNotice(notice.title, notice.text ?: notice.subText)
    }
    when {
      state.calendarError != null -> Text(state.calendarError ?: "", color = androidx.compose.ui.graphics.Color(0xFFFFB4AB), fontSize = 12.sp)
      !state.calendarLoaded -> Text("Termine werden geladen…", color = androidx.compose.ui.graphics.Color(0xFFA9BDD2), fontSize = 13.sp)
      state.calendarEvents.isEmpty() -> Text("Keine Termine", color = androidx.compose.ui.graphics.Color(0xFFA9BDD2), fontSize = 13.sp)
      else -> visibleEvents.forEach { event ->
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text(
            text = if (event.start.isAllDay) "Ganztägig" else formatter.format(event.startsAt),
            color = androidx.compose.ui.graphics.Color(0xFFF6C878),
            fontSize = 12.sp,
          )
          Text(
            event.summary,
            color = androidx.compose.ui.graphics.Color(0xFFE6F1FF),
            fontSize = 15.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
        }
      }
    }
    if (state.calendarLoaded && state.calendarError == null && hiddenEventCount > 0) {
      Text(
        text = "+ $hiddenEventCount weitere Termine",
        color = androidx.compose.ui.graphics.Color(0xFFA9BDD2),
        fontSize = 11.sp,
        maxLines = 1,
      )
    }
  }
}

/** Displays only the literal title/body supplied by Amazon; it does not classify a shipment state. */
@Composable
private fun AmazonNotice(
  title: String?,
  body: String?,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(androidx.compose.ui.graphics.Color(0xAA1B3B6F), RoundedCornerShape(12.dp))
        .padding(8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      "Amazon",
      color = androidx.compose.ui.graphics.Color(0xFFF6C878),
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
    )
    title?.let {
      Text(it, color = androidx.compose.ui.graphics.Color(0xFFE6F1FF), fontSize = 14.sp, maxLines = 2)
    }
    body?.let {
      Text(it, color = androidx.compose.ui.graphics.Color(0xFFB8C9DE), fontSize = 12.sp, maxLines = 3)
    }
  }
}

/** Render at the top-level Box of JarvisScreen/ShellScreen so a fresh Ring event can cover the display. */
@Composable
internal fun JarvisRingOverlay(
  repository: HomeDashboardRepository = HomeDashboardRepository.get(LocalContext.current),
  horizonLayout: JarvisHorizonLayout? = null,
  voiceIndicator: CameraVoiceIndicatorState,
  modifier: Modifier = Modifier,
) {
  val notice by repository.ringNotice.collectAsState()
  val display by repository.doorCameraDisplay.collectAsState()
  val callState by repository.ringCallState.collectAsState()
  var callNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
  val callExpiresAtMs = ringCallPresentationExpiryAtMs(callState)
  LaunchedEffect(callState.phase, callState.startedAtMs, callState.endedAtMs, callExpiresAtMs) {
    callNowMs = System.currentTimeMillis()
    while (callState.phase == RingCallSourcePhase.Connected) {
      delay(1_000L)
      callNowMs = System.currentTimeMillis()
    }
    callExpiresAtMs?.let { expiresAtMs ->
      delay((expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
      callNowMs = System.currentTimeMillis()
    }
  }
  val call = ringCallPresentation(callState, notice, display, callNowMs)
  notice?.let { active ->
    RingCameraOverlay(
      notice = active,
      display = display,
      call = call,
      onAction = repository::handleDoorCameraAction,
      horizonLayout = horizonLayout,
      voiceIndicator = voiceIndicator,
      modifier = modifier,
    )
  }
}

@Composable
private fun RingCameraOverlay(
  notice: RingNotice,
  display: DoorCameraDisplayState,
  call: RingCallPresentation?,
  onAction: (DoorCameraAction) -> Unit,
  horizonLayout: JarvisHorizonLayout?,
  voiceIndicator: CameraVoiceIndicatorState,
  modifier: Modifier,
) {
  // Failed/closed playback must reveal Horizon. Keeping an empty TextureView over a
  // fullscreen stage is indistinguishable from an app hang on the Echo.
  val cameraSurfaceActive = display.lifecycle !in setOf(DoorCameraLifecycle.Failed, DoorCameraLifecycle.Closed)
  val density = LocalDensity.current
  BoxWithConstraints(
    modifier = modifier.fillMaxSize(),
  ) {
    // On adaptive endpoints a rotated landscape screen is the useful camera
    // surface. Keep the requested PiP state unchanged so returning to portrait
    // immediately restores its original placement. The fixed Echo canvas never
    // takes this automatic path.
    val fullscreen = effectiveDoorCameraPresentation(
      requested = display.presentation,
      horizonLayout = horizonLayout,
      isLandscape = maxWidth > maxHeight,
      allowAutomaticLandscapeFullscreen = display.automaticLandscapeFullscreen,
    ) == DoorCameraPresentation.Fullscreen
    if (fullscreen && cameraSurfaceActive) {
      Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF020406)))
    }
    val cameraModifier = if (fullscreen) {
      Modifier.fillMaxSize()
    } else if (horizonLayout == JarvisHorizonLayout.EchoFixed) {
      // The local Horizon document has a fixed physical 960×480 stage. Convert
      // those source pixels through the current density instead of altering the
      // device density, which keeps camera and HTML precisely aligned.
      Modifier
        .align(Alignment.TopStart)
        .offset(x = with(density) { 324.toDp() }, y = with(density) { 56.toDp() })
        .size(width = with(density) { 612.toDp() }, height = with(density) { 336.toDp() })
    } else if (horizonLayout == JarvisHorizonLayout.Adaptive) {
      val stage = adaptiveHorizonCameraStage(maxWidth.value, maxHeight.value)
      Modifier
        .align(Alignment.TopStart)
        .offset(x = stage.left.dp, y = stage.top.dp)
        .size(width = stage.width.dp, height = stage.height.dp)
    } else {
      Modifier.align(Alignment.BottomEnd).padding(20.dp).size(width = 352.dp, height = 220.dp)
    }
    if (cameraSurfaceActive) key(notice.occurredAt) {
      val repository = HomeDashboardRepository.get(LocalContext.current)
      val hls by repository.cameraHlsSource.collectAsState()
      val cameraAudioEnabled by repository.cameraAudioEnabled.collectAsState()
      val currentHls = hls?.takeIf { it.occurredAt == notice.occurredAt }
      if (currentHls == null) RingWebRtcSurface(repository = repository, modifier = cameraModifier)
      else HlsCameraSurface(repository = repository, source = currentHls, audioEnabled = cameraAudioEnabled, modifier = cameraModifier)
    }
    if (!notice.cameraFirstFrame) {
      val image = remember(notice.snapshot) { notice.snapshot?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
      if (image != null) {
        androidx.compose.foundation.Image(
          bitmap = image,
          contentDescription = "Türkamera",
          modifier = cameraModifier,
          contentScale = ContentScale.Fit,
        )
      }
    }
    if (horizonLayout != null && display.lifecycle == DoorCameraLifecycle.Failed) notice.cameraError?.let { error ->
      // Keep any recovered snapshot visible; Horizon needs only a compact, readable failure fact.
      Box(modifier = cameraModifier, contentAlignment = Alignment.BottomCenter) {
        Surface(
          modifier = Modifier.padding(12.dp),
          shape = RoundedCornerShape(999.dp),
          color = androidx.compose.ui.graphics.Color(0xD9101A27),
          contentColor = androidx.compose.ui.graphics.Color(0xFFF6C878),
        ) {
          Text(error, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
        }
      }
    }
    if (horizonLayout != null && call != null) {
      val bannerStart = if (horizonLayout == JarvisHorizonLayout.EchoFixed) with(density) { 346.toDp() } else 28.dp
      val bannerTop = if (horizonLayout == JarvisHorizonLayout.EchoFixed) with(density) { 70.toDp() } else 64.dp
      RingCallVoiceBanner(
        presentation = call,
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(start = bannerStart, top = bannerTop),
      )
    }
    if (fullscreen) {
      CameraVoiceIndicator(
        state = voiceIndicator,
        modifier =
          Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
      )
    }
    // Horizon stays voice-first even in camera fullscreen. The existing
    // jarvis.display actions still control PiP, fullscreen and close.
    if (showsNativeRingControls(horizonLayout)) Surface(
      modifier = if (fullscreen) Modifier.align(Alignment.TopStart).padding(16.dp) else Modifier.align(Alignment.BottomEnd).padding(28.dp),
      shape = RoundedCornerShape(16.dp),
      color = androidx.compose.ui.graphics.Color(0xD9111B25),
      contentColor = androidx.compose.ui.graphics.Color(0xFFF0F8FF),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Column {
          Text(
            if (notice.manual && display.lifecycle == DoorCameraLifecycle.Live) "${display.camera?.name ?: "Kamera"} · Live" else if (notice.manual) "${display.camera?.name ?: "Kamera"} · Kamera" else "Es klingelt an der Haustür",
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
          )
          val detail = notice.cameraError ?: doorCameraDisplayLabel(display.lifecycle)
          Text(detail, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFB8C8D5))
        }
        IconButton(onClick = { onAction(if (fullscreen) DoorCameraAction.Minimize else DoorCameraAction.Fullscreen) }) {
          Icon(if (fullscreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull, contentDescription = if (fullscreen) "Türkamera minimieren" else "Türkamera vergrößern")
        }
        IconButton(onClick = { onAction(DoorCameraAction.Close) }) { Icon(Icons.Default.Close, contentDescription = "Türkamera schließen") }
      }
    }
  }
}

/** A display fact only: it never requests, stops, or suppresses microphone capture. */
internal enum class CameraVoiceIndicatorTone { Ready, Listening, Paused, Disabled, Starting, Unavailable }

internal data class CameraVoiceIndicatorState(
  val title: String,
  val detail: String,
  val tone: CameraVoiceIndicatorTone,
)

internal fun cameraVoiceIndicatorState(
  talkCaptureAdmitted: Boolean,
  wakeListening: Boolean,
  wakeEnabled: Boolean,
  wakeStatus: String,
  talkSpeaking: Boolean,
): CameraVoiceIndicatorState {
  if (talkCaptureAdmitted) return CameraVoiceIndicatorState("HÖRT ZU", "Gespräch aktiv", CameraVoiceIndicatorTone.Listening)
  if (wakeListening) return CameraVoiceIndicatorState("JARVIS BEREIT", "Wakeword aktiv", CameraVoiceIndicatorTone.Ready)
  if (!wakeEnabled) return CameraVoiceIndicatorState("WAKEWORD AUS", "Mikrofon nicht bereit", CameraVoiceIndicatorTone.Disabled)
  if (talkSpeaking) return CameraVoiceIndicatorState("MIKRO PAUSIERT", "Jarvis spricht", CameraVoiceIndicatorTone.Paused)
  return when (wakeStatus.trim().lowercase()) {
    "paused" -> CameraVoiceIndicatorState("MIKRO PAUSIERT", "Wakeword pausiert", CameraVoiceIndicatorTone.Paused)
    "triggered" -> CameraVoiceIndicatorState("MIKRO PAUSIERT", "Befehl wird übergeben", CameraVoiceIndicatorTone.Paused)
    "starting…", "starting..." -> CameraVoiceIndicatorState("MIKRO STARTET", "Wakeword wird vorbereitet", CameraVoiceIndicatorTone.Starting)
    else -> CameraVoiceIndicatorState("WAKEWORD NICHT BEREIT", wakeStatus.ifBlank { "Mikrofon prüfen" }, CameraVoiceIndicatorTone.Unavailable)
  }
}

@Composable
private fun CameraVoiceIndicator(
  state: CameraVoiceIndicatorState,
  modifier: Modifier = Modifier,
) {
  val accent = when (state.tone) {
    CameraVoiceIndicatorTone.Ready, CameraVoiceIndicatorTone.Listening -> Color(0xFF05D5FF)
    CameraVoiceIndicatorTone.Paused, CameraVoiceIndicatorTone.Starting -> Color(0xFFF6C878)
    CameraVoiceIndicatorTone.Disabled, CameraVoiceIndicatorTone.Unavailable -> Color(0xFFFF6B6B)
  }
  Surface(
    modifier = modifier.semantics { contentDescription = "${state.title}. ${state.detail}" },
    shape = RoundedCornerShape(14.dp),
    color = Color(0xD908111B),
    contentColor = Color(0xFFF0F8FF),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.MicNone,
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size(16.dp),
      )
      Column(modifier = Modifier.padding(start = 7.dp)) {
        Text(state.title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(state.detail, color = Color(0xFFB8C8D5), fontSize = 10.sp, maxLines = 1)
      }
    }
  }
}

/** Native camera playback covers the WebView stage, so retain the live Ring fact above that player. */
@Composable
private fun RingCallVoiceBanner(
  presentation: RingCallPresentation,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = when (presentation.phase.name) {
      "Failed" -> androidx.compose.ui.graphics.Color(0xD92A101A)
      "Connected" -> androidx.compose.ui.graphics.Color(0xD90A2B25)
      else -> androidx.compose.ui.graphics.Color(0xD9111B25)
    },
    contentColor = androidx.compose.ui.graphics.Color(0xFFF0F8FF),
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
      Text(presentation.title, color = androidx.compose.ui.graphics.Color(0xFFF6C878), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      val duration = presentation.durationSeconds?.let { "%d:%02d".format(it / 60L, it % 60L) }
      Text(listOf(presentation.detail, duration).filterNotNull().joinToString(" · "), color = androidx.compose.ui.graphics.Color(0xFFC7D7E7), fontSize = 12.sp)
      presentation.hints.firstOrNull()?.let { hint ->
        Text(hint, color = androidx.compose.ui.graphics.Color(0xFF9FC4FF), fontSize = 11.sp)
      }
    }
  }
}

/** CSS-viewport coordinates supplied by the Horizon adaptive layout contract. */
internal data class HorizonCameraStage(
  val left: Float,
  val top: Float,
  val width: Float,
  val height: Float,
)

internal fun adaptiveHorizonCameraStage(
  viewportWidth: Float,
  viewportHeight: Float,
): HorizonCameraStage =
  if (viewportWidth > viewportHeight && viewportHeight <= 500f) {
    HorizonCameraStage(
      left = 270f,
      top = 52f,
      width = (viewportWidth - 284f).coerceAtLeast(0f),
      height = 240f,
    )
  } else {
    HorizonCameraStage(
      left = 14f,
      top = 360f,
      width = (viewportWidth - 28f).coerceAtLeast(0f),
      height = minOf(340f, (viewportHeight - 430f).coerceAtLeast(0f)),
    )
  }

internal fun showsNativeRingControls(horizonLayout: JarvisHorizonLayout?): Boolean = horizonLayout == null

/**
 * Landscape is an automatic presentation override for the responsive Horizon
 * canvas only. It must not alter the source-owned manual/PiP state.
 */
internal fun effectiveDoorCameraPresentation(
  requested: DoorCameraPresentation,
  horizonLayout: JarvisHorizonLayout?,
  isLandscape: Boolean,
  allowAutomaticLandscapeFullscreen: Boolean = true,
): DoorCameraPresentation =
  if (
    requested == DoorCameraPresentation.PiP &&
    horizonLayout == JarvisHorizonLayout.Adaptive &&
    isLandscape &&
    allowAutomaticLandscapeFullscreen
  ) {
    DoorCameraPresentation.Fullscreen
  } else {
    requested
  }

private fun doorCameraDisplayLabel(lifecycle: DoorCameraLifecycle): String = when (lifecycle) {
  DoorCameraLifecycle.Requested -> "Kamera angefordert…"
  DoorCameraLifecycle.Connecting -> "Kamera wird verbunden…"
  DoorCameraLifecycle.Live -> "Live-Kamera"
  DoorCameraLifecycle.Snapshot -> "Aktuelles Kamerabild"
  DoorCameraLifecycle.Failed -> "Live-Kamera konnte nicht verbunden werden"
  DoorCameraLifecycle.Closed -> "Kamera geschlossen"
}

@Composable
private fun HlsCameraSurface(repository: HomeDashboardRepository, source: CameraHlsSource, audioEnabled: Boolean, modifier: Modifier) {
  val context = LocalContext.current
  val view = remember(source) { HlsCameraView(context, source, repository, audioEnabled) }
  SideEffect { view.setAudioEnabled(audioEnabled) }
  DisposableEffect(view) { onDispose { view.release() } }
  AndroidView(
    factory = { view },
    modifier = modifier.background(androidx.compose.ui.graphics.Color.Black),
  )
}

/** Native HLS decode shares the exact camera stage with WebRTC and never captures audio focus. */
private class HlsCameraView(
  context: android.content.Context,
  private val source: CameraHlsSource,
  private val repository: HomeDashboardRepository,
  initialAudioEnabled: Boolean,
) : TextureView(context), TextureView.SurfaceTextureListener {
  private var player: ExoPlayer? = null
  private var surface: android.view.Surface? = null
  private val timeout = Runnable { repository.onHlsFailure(source); stopPlayback() }
  private var released = false
  private var audioEnabled = initialAudioEnabled
  private var videoWidth = 0
  private var videoHeight = 0
  private var pixelWidthHeightRatio = 1f

  init {
    surfaceTextureListener = this
  }

  override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
    if (released) return
    surface = android.view.Surface(texture)
    // HA emits fragmented MP4 + LL-HLS (including HEVC). The Android 11 platform
    // MediaPlayer rejects this playlist with MEDIA_ERROR_MALFORMED; Media3 already
    // owns supported HLS playback in the app and uses the device's native decoder.
    val media = ExoPlayer.Builder(context).build()
    player = media
    media.setVideoSurface(surface)
    // HLS cameras can include AAC/Opus audio.  Let Media3 select and render the
    // audio track at normal media volume; leaving focus unmanaged preserves the
    // current Bluetooth/media route and lets an active Jarvis reply retain its
    // own communication focus.
    media.volume = if (audioEnabled) 1f else 0f
    media.addListener(object : Player.Listener {
      override fun onTracksChanged(tracks: Tracks) {
        // The HLS source URL carries its own access token, so diagnostics must
        // identify formats only. This separates a camera that publishes video
        // only from local volume/arbitration failures on older endpoints.
        android.util.Log.i(
          "JarvisCamera",
          "Camera ${source.format} tracks audio=${selectedTrackMimeTypes(tracks, C.TRACK_TYPE_AUDIO)} " +
            "video=${selectedTrackMimeTypes(tracks, C.TRACK_TYPE_VIDEO)}",
        )
      }

      override fun onVideoSizeChanged(videoSize: VideoSize) {
        videoWidth = videoSize.width
        videoHeight = videoSize.height
        pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
        applyVideoTransform()
      }

      override fun onRenderedFirstFrame() {
        if (!released && player === media) {
          removeCallbacks(timeout)
          repository.onHlsFirstFrame(source)
        }
      }
      override fun onPlayerError(error: PlaybackException) {
        if (!released && player === media) {
          android.util.Log.w("JarvisCamera", "HLS playback failed: ${error.errorCodeName}")
          repository.onHlsFailure(source)
          stopPlayback()
        }
      }
      override fun onPlaybackStateChanged(playbackState: Int) {
        if (!released && player === media && playbackState == Player.STATE_ENDED) {
          repository.onHlsFailure(source)
          stopPlayback()
        }
      }
    })
    try {
      val mimeType = when (source.format) {
        CameraStreamFormat.Hls -> MimeTypes.APPLICATION_M3U8
        CameraStreamFormat.Mp4 -> MimeTypes.VIDEO_MP4
      }
      media.setMediaItem(MediaItem.Builder().setUri(source.url).setMimeType(mimeType).build())
      media.prepare()
      media.playWhenReady = true
      postDelayed(timeout, source.firstFrameTimeoutMillis)
    } catch (_: Exception) { repository.onHlsFailure(source); release() }
  }

  fun release() {
    released = true
    stopPlayback()
  }

  /** Keeps the decoder and camera stream alive while Jarvis temporarily owns speech/capture. */
  fun setAudioEnabled(enabled: Boolean) {
    if (audioEnabled == enabled) return
    audioEnabled = enabled
    player?.volume = if (enabled) 1f else 0f
  }

  private fun stopPlayback() {
    removeCallbacks(timeout)
    val previous = player
    player = null
    previous?.release()
    surface?.release()
    surface = null
  }

  override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { stopPlayback(); return true }
  override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
    applyVideoTransform()
  }
  override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

  /** Letterbox in the existing native surface: never crop or stretch a live camera. */
  private fun applyVideoTransform() {
    val fit = cameraTextureFit(
      surfaceWidth = width,
      surfaceHeight = height,
      videoWidth = videoWidth,
      videoHeight = videoHeight,
      pixelWidthHeightRatio = pixelWidthHeightRatio,
    )
    val matrix = Matrix().apply {
      setScale(fit.scaleX, fit.scaleY, width / 2f, height / 2f)
    }
    setTransform(matrix)
  }
}

/** The view is already surface-sized; scale its decoded texture inside it to retain the source aspect. */
internal data class CameraTextureFit(val scaleX: Float, val scaleY: Float)

internal fun cameraTextureFit(
  surfaceWidth: Int,
  surfaceHeight: Int,
  videoWidth: Int,
  videoHeight: Int,
  pixelWidthHeightRatio: Float = 1f,
): CameraTextureFit {
  if (surfaceWidth <= 0 || surfaceHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
    return CameraTextureFit(1f, 1f)
  }
  val ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
  val videoAspect = videoWidth.toDouble() * ratio / videoHeight
  val fittedWidth = minOf(surfaceWidth.toDouble(), surfaceHeight * videoAspect)
  val fittedHeight = minOf(surfaceHeight.toDouble(), surfaceWidth / videoAspect)
  return CameraTextureFit(
    scaleX = (fittedWidth / surfaceWidth).toFloat(),
    scaleY = (fittedHeight / surfaceHeight).toFloat(),
  )
}

private fun selectedTrackMimeTypes(tracks: Tracks, type: Int): List<String> =
  tracks.groups.flatMap { group ->
    if (group.type != type) emptyList()
    else (0 until group.length).mapNotNull { index ->
      group.getTrackFormat(index).sampleMimeType?.takeIf { group.isTrackSelected(index) }
    }
  }

@Composable
private fun RingWebRtcSurface(repository: HomeDashboardRepository, modifier: Modifier) {
  val context = LocalContext.current
  val eglBase = remember { EglBase.create() }
  val renderer = remember(context) { TextureWebRtcRenderer(context, repository::onWebRtcFirstFrame) }
  DisposableEffect(renderer, eglBase) {
    onDispose {
      repository.detachWebRtcRenderer(renderer)
      renderer.release()
      eglBase.release()
    }
  }
  AndroidView(
    factory = {
      renderer.apply {
        init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
          override fun onFirstFrameRendered() = repository.onWebRtcFirstFrame(renderer)
          override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) = Unit
        })
        repository.attachWebRtcRenderer(renderer)
      }
    },
    modifier = modifier,
  )
}

/** A TextureView-backed sink keeps the native camera pixels inside the PiP bounds. */
private class TextureWebRtcRenderer(
  context: android.content.Context,
  private val onPresentedFrame: (VideoSink) -> Unit,
) : TextureView(context), VideoSink, TextureView.SurfaceTextureListener {
  private val renderer = SurfaceEglRenderer("jarvis-door-camera")
  private var displaySurface: android.view.Surface? = null

  init {
    surfaceTextureListener = this
  }

  fun init(eglContext: EglBase.Context, events: RendererCommon.RendererEvents) {
    renderer.init(eglContext, events, EglBase.CONFIG_PLAIN, GlRectDrawer())
    surfaceTexture?.let(::createSurface)
  }

  fun release() {
    surfaceTextureListener = null
    releaseSurface()
    renderer.release()
  }

  override fun onFrame(frame: VideoFrame) = renderer.onFrame(frame)

  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
    createSurface(surface)
    updateLayoutAspectRatio(width, height)
  }

  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
    updateLayoutAspectRatio(width, height)
  }

  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
    releaseSurface()
    return true
  }

  // SurfaceEglRenderer reports its first frame only once per renderer. The
  // TextureView survives view -> intercom -> view, so report actual presented
  // frames too; the repository admits readiness only while it is still pending.
  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = onPresentedFrame(this)

  private fun createSurface(texture: SurfaceTexture) {
    if (displaySurface != null) return
    displaySurface = android.view.Surface(texture).also(renderer::createEglSurface)
  }

  private fun releaseSurface() {
    val surface = displaySurface ?: return
    displaySurface = null
    renderer.releaseEglSurface(surface::release)
  }

  private fun updateLayoutAspectRatio(width: Int, height: Int) {
    if (width > 0 && height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height)
  }
}

private fun homeConnectionLabel(state: HomeDashboardState): String = when (state.connection) {
  HomeConnectionState.Connected -> "Live"
  HomeConnectionState.Connecting -> "Verbinde…"
  HomeConnectionState.Error -> state.connectionError ?: "Nicht verbunden"
  HomeConnectionState.MissingConfiguration -> "Nicht eingerichtet"
}
