package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.R
import ai.openclaw.app.JarvisHealthComponent
import ai.openclaw.app.JarvisHealthStatus
import ai.openclaw.app.unknownJarvisHealth
import ai.openclaw.app.home.DoorCameraLifecycle
import ai.openclaw.app.home.HomeDashboardRepository
import ai.openclaw.app.home.HorizonPresentationRepository
import ai.openclaw.app.home.timers.JarvisScheduleRepository
import ai.openclaw.app.home.timers.JarvisScheduleKind
import ai.openclaw.app.home.timers.JarvisScheduleState
import ai.openclaw.app.voice.JarvisToolExecution
import ai.openclaw.app.voice.JarvisToolPhase
import ai.openclaw.app.voice.VoiceConversationEntry
import ai.openclaw.app.voice.VoiceConversationRole
import ai.openclaw.app.i18n.resolveNativeText
import ai.openclaw.app.ui.horizon.HorizonMedia
import ai.openclaw.app.ui.horizon.HorizonAudioLevel
import ai.openclaw.app.ui.horizon.HorizonAudioSource
import ai.openclaw.app.ui.horizon.HorizonService
import ai.openclaw.app.ui.horizon.HorizonSnapshot
import ai.openclaw.app.ui.horizon.JarvisHorizonLayout
import ai.openclaw.app.ui.horizon.JarvisHorizonWebView
import ai.openclaw.app.ui.horizon.horizonAutomaticTile
import ai.openclaw.app.ui.horizon.horizonPresentationMode
import ai.openclaw.app.ui.horizon.horizonSnapshotFor
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.os.Build
import android.media.AudioManager
import android.hardware.SensorPrivacyManager
import android.view.Window
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.DateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private enum class JarvisPhase(
  val color: Color,
) {
  Speaking(Color(0xFF9BEAF2)),
  Thinking(Color(0xFFEFC78C)),
  Listening(Color(0xFF73DFE2)),
  WakeListening(Color(0xFF9BEAF2)),
  Idle(Color(0xFF73C9D5)),
}

private val silentHorizonAudioLevel = MutableStateFlow(0f)

private fun JarvisPhase.horizonAudioSource(): HorizonAudioSource =
  when (this) {
    JarvisPhase.Speaking -> HorizonAudioSource.Jarvis
    JarvisPhase.Listening, JarvisPhase.WakeListening -> HorizonAudioSource.User
    JarvisPhase.Thinking, JarvisPhase.Idle -> HorizonAudioSource.Silent
  }

/**
 * Horizon art ships at phone quality. The Show's 960px display cannot reveal that
 * many source pixels, and sampling it here prevents oversized GL textures from
 * turning an otherwise light HUD frame into a missed-vsync frame.
 */
@Composable
internal fun rememberJarvisHorizonBitmap(
  @DrawableRes resourceId: Int,
  maxEdgePx: Int = Int.MAX_VALUE,
): ImageBitmap {
  val resources = LocalContext.current.resources
  return remember(resources, resourceId, maxEdgePx) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(resources, resourceId, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxEdgePx || bounds.outHeight / sample > maxEdgePx) {
      sample *= 2
    }
    requireNotNull(
      BitmapFactory.decodeResource(
        resources,
        resourceId,
        BitmapFactory.Options().apply {
          inSampleSize = sample
          inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        },
      ),
    ) { "Missing Horizon artwork resource $resourceId" }.asImageBitmap()
  }
}

@Composable
internal fun rememberJarvisSmallPhysicalDisplay(): Boolean {
  val metrics = LocalContext.current.resources.displayMetrics
  return maxOf(metrics.widthPixels, metrics.heightPixels) <= 1_024
}

/** The full Horizon canvas is designed for the fixed 960×480 CRONOS panel. */
internal fun jarvisHorizonLayoutFor(
  device: String,
  maximumPhysicalEdgePx: Int,
): JarvisHorizonLayout =
  if (maximumPhysicalEdgePx <= 1_024 && device.equals("cronos", ignoreCase = true)) {
    JarvisHorizonLayout.EchoFixed
  } else {
    JarvisHorizonLayout.Adaptive
  }

/** All Android Jarvis surfaces share Horizon; only CRONOS keeps the physical 960×480 stage. */
@Composable
internal fun rememberJarvisHorizonLayout(): JarvisHorizonLayout {
  val metrics = LocalContext.current.resources.displayMetrics
  return remember(metrics.widthPixels, metrics.heightPixels, Build.DEVICE) {
    jarvisHorizonLayoutFor(Build.DEVICE, maxOf(metrics.widthPixels, metrics.heightPixels))
  }
}

@Composable
internal fun rememberJarvisHorizonEchoTarget(): Boolean =
  rememberJarvisHorizonLayout() == JarvisHorizonLayout.EchoFixed
@Composable
internal fun JarvisScreen(
  viewModel: MainViewModel,
  onOpenChat: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val talkEnabled by viewModel.talkModeEnabled.collectAsState()
  val talkListening by viewModel.talkModeListening.collectAsState()
  val talkAwaitingAgent by viewModel.talkAwaitingAgent.collectAsState()
  val talkSpeaking by viewModel.talkModeSpeaking.collectAsState()
  val wakeListening by viewModel.voiceWakeIsListening.collectAsState()
  val talkFailureNotice by viewModel.talkFailureNotice.collectAsState()
  val conversation by viewModel.talkConversation.collectAsState()
  val activeTools by viewModel.talkActiveToolExecutions.collectAsState()
  val conversationLifecycle by viewModel.talkConversationLifecycle.collectAsState()
  val alexaAnnouncement by viewModel.alexaAnnouncement.collectAsState()
  val healthState by viewModel.jarvisHealthState.collectAsState()
  val gateway by viewModel.gatewayConnectionDisplay.collectAsState()
  val wakeEnabled by viewModel.voiceWakeEnabled.collectAsState()
  val ambientLux = rememberJarvisAmbientLightLux()
  val context = LocalContext.current
  val horizonLayout = rememberJarvisHorizonLayout()
  // Both supported layouts are Horizon. Keep the predicate explicit so a future
  // legacy layout can retain the old native path without duplicating cards now.
  val horizonTarget = horizonLayout in JarvisHorizonLayout.entries
  val homeRepository = remember(context) { HomeDashboardRepository.get(context) }
  val horizonPresentationRepository = remember(context) { HorizonPresentationRepository.get(context) }
  val horizonPresentation by horizonPresentationRepository.presentation.collectAsState()
  val homeDashboard by homeRepository.dashboard.collectAsState()
  val scheduleRepository = remember(context) { JarvisScheduleRepository.get(context) }
  val schedules by scheduleRepository.schedules.collectAsState()
  val scheduleAlert by scheduleRepository.activeAlert.collectAsState()
  val hasSchedules = schedules.any { it.state != JarvisScheduleState.Cancelled }
  val ringNotice by homeRepository.ringNotice.collectAsState()
  val doorCameraDisplay by homeRepository.doorCameraDisplay.collectAsState()
  val ringCallState by homeRepository.ringCallState.collectAsState()
  val homeRoomChanges by homeRepository.homeRoomChanges.collectAsState()
  val ambientStore = remember(context) { JarvisAmbientSettingsStore(context) }
  var ambientSettings by remember(context) { androidx.compose.runtime.mutableStateOf(ambientStore.load()) }
  var showAmbientSettings by remember { androidx.compose.runtime.mutableStateOf(false) }
  var explicitSleepRequested by remember { androidx.compose.runtime.mutableStateOf(false) }
  var handledSleepRequest by remember { androidx.compose.runtime.mutableStateOf(false) }
  val phase =
    remember(talkSpeaking, talkAwaitingAgent, talkListening, wakeListening) {
      when {
        talkSpeaking -> JarvisPhase.Speaking
        talkAwaitingAgent -> JarvisPhase.Thinking
        talkListening -> JarvisPhase.Listening
        wakeListening -> JarvisPhase.WakeListening
        else -> JarvisPhase.Idle
      }
    }
  val horizonAudioSource = phase.horizonAudioSource()
  val horizonAudioFlow = remember(viewModel, horizonAudioSource) {
    when (horizonAudioSource) {
      HorizonAudioSource.User -> viewModel.talkInputAudioLevel
      HorizonAudioSource.Jarvis -> viewModel.talkOutputAudioLevel
      HorizonAudioSource.Silent -> silentHorizonAudioLevel
    }
  }
  val level by horizonAudioFlow.collectAsState()
  val horizonAudioLevel = HorizonAudioLevel(horizonAudioSource, level)
  val runtimeNotice =
    when {
      !gateway.isConnected -> "Gateway nicht verbunden"
      talkFailureNotice != null -> {
        val detail = talkFailureNotice?.text.orEmpty()
        if (detail.contains("not connected", ignoreCase = true) || detail.contains("disconnected", ignoreCase = true)) {
          "Verbindung unterbrochen. Sag bitte erneut „Jarvis“."
        } else {
          detail
        }
      }
      else -> null
    }
  var now by remember { androidx.compose.runtime.mutableStateOf(Date()) }
  var interactionGeneration by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
  var ambientNowMs by remember { androidx.compose.runtime.mutableLongStateOf(SystemClock.elapsedRealtime()) }
  var lastActivityMs by remember { androidx.compose.runtime.mutableLongStateOf(SystemClock.elapsedRealtime()) }
  val hasActiveTool = activeTools.any { it.phase == JarvisToolPhase.Running }
  val hasPlayingMedia = homeDashboard.mediaPlayers.any { it.available && it.isPlaying }
  val hasActiveRing = ringNotice != null || doorCameraDisplay.lifecycle != DoorCameraLifecycle.Closed
  val hasHorizonTimer = horizonTarget && schedules.any {
    it.kind == JarvisScheduleKind.Timer && it.state in setOf(JarvisScheduleState.Scheduled, JarvisScheduleState.Paused)
  }
  val blocksAmbient =
    talkListening ||
      talkAwaitingAgent ||
      talkSpeaking ||
      conversationLifecycle.active ||
      hasActiveTool ||
      hasActiveRing ||
      homeRoomChanges.any { it.highlightExpiresAtMs > now.time } ||
      hasHorizonTimer ||
      (horizonTarget && (horizonPresentation.tile != null || horizonPresentation.executions.any { it.exit == null })) ||
      alexaAnnouncement != null ||
      scheduleAlert != null ||
      showAmbientSettings
  val ambientMode = jarvisAmbientMode(
    JarvisAmbientInput(
      nowMs = ambientNowMs,
      lastActivityMs = lastActivityMs,
      blocksAmbient = blocksAmbient,
      forceAmbient = explicitSleepRequested,
      settings = ambientSettings,
    ),
  )
  val lowLight = ambientMode == JarvisAmbientMode.Ambient
  val idleAlpha by animateFloatAsState(if (lowLight) 0.36f else 1f, tween(1_600), label = "idle-dim")
  val conversationChangedAt = remember(conversation) { SystemClock.elapsedRealtime() }
  val showConversation = talkEnabled || talkListening || talkAwaitingAgent || talkSpeaking ||
    (conversation.isNotEmpty() && ambientNowMs - conversationChangedAt < 45_000L)
  val restoreBrightness = {
    interactionGeneration += 1
    lastActivityMs = SystemClock.elapsedRealtime()
    explicitSleepRequested = false
  }

  LaunchedEffect(conversationLifecycle.active, conversationLifecycle.sleepRequested) {
    if (conversationLifecycle.active) {
      explicitSleepRequested = false
      handledSleepRequest = false
    } else if (conversationLifecycle.sleepRequested && !handledSleepRequest) {
      // The manager flips this only after audio retirement. It remains local so
      // a touch or new conversation can always wake the display again.
      explicitSleepRequested = true
      handledSleepRequest = true
    }
  }

  LaunchedEffect(blocksAmbient) {
    // Both entering and leaving a live interaction are visible activity. In
    // particular, a long-running tool must not fall straight into ambient when
    // it completes. This is not a user wake action, so it must not consume a
    // just-finished explicit sleep request.
    interactionGeneration += 1
    lastActivityMs = SystemClock.elapsedRealtime()
  }

  LaunchedEffect(horizonPresentation.revision) {
    if (horizonTarget && horizonPresentation.revision > 0 &&
      (horizonPresentation.tile != null || horizonPresentation.executions.any { it.exit == null })) {
      lastActivityMs = SystemClock.elapsedRealtime()
      explicitSleepRequested = false
    }
  }

  LaunchedEffect(schedules) {
    if (hasHorizonTimer) {
      lastActivityMs = SystemClock.elapsedRealtime()
      explicitSleepRequested = false
    }
  }

  LaunchedEffect(horizonTarget) {
    while (true) {
      now = Date()
      // Horizon's physical seconds indicator is part of the fixed display;
      // its once-per-second fact update is still far below audio-level traffic.
      delay(if (horizonTarget) 1_000 else 15_000)
    }
  }

  LaunchedEffect(horizonPresentationRepository) {
    // Tiles are intentionally foreground-only. This gives their TTL a precise,
    // bounded expiry even when no Home Assistant event arrives afterwards.
    while (true) {
      horizonPresentationRepository.expireIfDue()
      viewModel.advanceHorizonPresentation(System.currentTimeMillis())
      delay(1_000)
    }
  }

  LaunchedEffect(Unit) {
    while (true) {
      ambientNowMs = SystemClock.elapsedRealtime()
      delay(1_000)
    }
  }

  JarvisAmbientWindowBrightness(
    window = context.findActivity()?.window,
    active = lowLight,
    ambientBrightness = jarvisAmbientBrightness(ambientLux, ambientSettings),
  )

  LaunchedEffect(gateway.isConnected) {
    if (!gateway.isConnected) return@LaunchedEffect
    while (true) {
      viewModel.refreshJarvisHealth()
      delay(60_000)
    }
  }

  if (horizonTarget) {
    val currentSnapshot = remember(
      phase,
      lowLight,
      wakeEnabled,
      gateway,
      now,
      ambientNowMs,
      homeDashboard,
      conversation,
      healthState,
      horizonPresentation,
      activeTools,
      schedules,
      scheduleAlert,
      ringNotice,
      doorCameraDisplay,
      ringCallState,
      homeRoomChanges,
      alexaAnnouncement,
    ) {
      horizonSnapshotFor(
        phase = phase,
        night = lowLight,
        wakeEnabled = wakeEnabled,
        microphoneMuted = jarvisMicrophoneMuted(context),
        gatewayConnected = gateway.isConnected,
        gatewayStatus = gateway.statusText,
        now = now,
        dashboard = homeDashboard,
        conversation = if (ambientNowMs - conversationChangedAt < 20_000L) conversation else emptyList(),
        health = healthState.summary?.components ?: unknownJarvisHealth(),
        tile = horizonAutomaticTile(
          presentation = horizonPresentation,
          ringActive = hasActiveRing,
          ringAlert = ringNotice?.takeUnless { it.manual } != null,
          alert = scheduleAlert,
          schedules = schedules,
          activeTools = activeTools,
          announcement = alexaAnnouncement,
          media = homeDashboard.mediaPlayers.firstOrNull { it.isPlaying },
          dashboard = homeDashboard,
          nowMs = now.time,
          cameraName = doorCameraDisplay.camera?.name,
          ringCall = ringCallState,
          ringNotice = ringNotice,
          cameraDisplay = doorCameraDisplay,
          homeRoomChanges = homeRoomChanges,
        ),
        modeOverride = horizonPresentationMode(
          phase = phase,
          ringAlert = ringNotice?.takeUnless { it.manual } != null,
          alertActive = scheduleAlert != null,
          toolRunning = hasActiveTool || horizonPresentation.executions.any { it.exit == null },
          announcementActive = alexaAnnouncement != null,
          ringConnected = ringCallState.phase == ai.openclaw.app.home.RingCallSourcePhase.Connected,
        ),
      )
    }
    BoxWithConstraints(
      modifier = modifier.fillMaxSize().background(Color(0xFF07101D)).testTag("jarvis-horizon-screen"),
    ) {
      // JarvisRingOverlay applies the identical adaptive-only presentation
      // override. Pause the covered WebView compositor while native camera
      // decode owns the full landscape surface; its state is retained for the
      // instant return to portrait/PiP.
      val cameraFullscreenCovered =
        doorCameraDisplay.lifecycle !in setOf(DoorCameraLifecycle.Failed, DoorCameraLifecycle.Closed) &&
          effectiveDoorCameraPresentation(
            requested = doorCameraDisplay.presentation,
            horizonLayout = horizonLayout,
            isLandscape = maxWidth > maxHeight,
            allowAutomaticLandscapeFullscreen = doorCameraDisplay.automaticLandscapeFullscreen,
          ) == ai.openclaw.app.home.DoorCameraPresentation.Fullscreen
      JarvisHorizonWebView(
        snapshot = currentSnapshot,
        audioLevel = horizonAudioLevel,
        layout = horizonLayout,
        paused = cameraFullscreenCovered,
        modifier = Modifier.fillMaxSize(),
      )
    }
    return
  }

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(Color(0xFF0A0F1E))
        .testTag("jarvis-screen")
        .combinedClickable(onClick = restoreBrightness),
  ) {
    val viewportWidth = maxWidth
    val viewportHeight = maxHeight
    // The center remains the one visual anchor. Its cap still leaves the
    // existing tool lane and health strip enough room on a 960x480 Show.
    val orbSize = minOf(viewportHeight * 0.58f, 250.dp)
    // Reserve the clock block, HUD footer, and a small visual gap before sizing
    // the calendar. The previous fixed formula allowed the calendar frame to
    // consume footer space on shorter windows.
    val calendarHeight = (viewportHeight - 216.dp).coerceIn(0.dp, 150.dp)
    val calendarEventLimit = if (calendarHeight >= 300.dp) 2 else 1
    val showCalendar = calendarHeight >= 92.dp
    Crossfade(
      targetState = lowLight,
      animationSpec = tween(durationMillis = 1_000),
      label = "jarvis-ambient-composition",
      modifier = Modifier.fillMaxSize(),
    ) { ambient ->
      if (ambient) {
        JarvisAmbientComposition(
          now = now,
          nextEvent = homeDashboard.calendarEvents.firstOrNull { event -> event.endsAt?.isAfter(java.time.Instant.now()) ?: event.startsAt.isAfter(java.time.Instant.now()) },
          modifier = Modifier.fillMaxSize(),
        )
      } else if (viewportWidth < 650.dp) {
        Box(Modifier.fillMaxSize()) {
          JarvisHudBackdrop(Modifier.fillMaxSize())
          Column(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            JarvisContext(
              viewModel = viewModel,
              now = now,
              calendarHeight = null,
              calendarEventLimit = 1,
              compact = true,
              onOpenSettings = { restoreBrightness(); showAmbientSettings = true },
            )
            Box(Modifier.fillMaxWidth().heightIn(min = 226.dp, max = 278.dp), contentAlignment = Alignment.Center) {
              JarvisOrb(
                phase = phase, level = level, taskActive = talkAwaitingAgent || hasActiveTool, mediaPlaying = hasPlayingMedia,
                dimmed = idleAlpha, modifier = Modifier.size(minOf(viewportWidth * 0.56f, 228.dp)),
              )
              JarvisToolFeed(
                tools = if (runtimeNotice == null) activeTools else emptyList(),
                modifier = Modifier.align(Alignment.BottomCenter),
              )
            }
            runtimeNotice?.let { Text(it, color = Color(0xFFFFC56E), fontSize = 12.sp) }
            if (hasSchedules) JarvisSchedulePanel(repository = scheduleRepository)
            if (showConversation) {
              JarvisConversationPanel(
                entries = jarvisConversationForDisplay(conversation),
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
              )
            } else {
              JarvisMediaPanel(repository = homeRepository, modifier = Modifier.fillMaxWidth())
              JarvisHomePanel(
                viewModel = viewModel,
                maxCalendarEvents = 1,
                modifier = Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState()),
              )
            }
          }
          JarvisHealthHud(
            components = healthState.summary?.components ?: unknownJarvisHealth(),
            connected = gateway.isConnected,
            gatewayError = healthState.errorText?.resolveNativeText(),
            nowMs = now.time,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
          )
        }
      } else {
        Box(modifier = Modifier.fillMaxSize()) {
          JarvisHudBackdrop(modifier = Modifier.fillMaxSize())

        Box(
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .padding(start = 16.dp, top = 18.dp)
          .width(196.dp),
    ) {
    JarvisContext(
        viewModel = viewModel,
        now = now,
        calendarHeight = calendarHeight.takeIf { showCalendar && it > 0.dp },
        calendarEventLimit = calendarEventLimit,
        onOpenSettings = {
          restoreBrightness()
          showAmbientSettings = true
        },
        modifier = Modifier.padding(8.dp).fillMaxWidth(),
      )
    }

    if (showConversation || hasSchedules || homeDashboard.weather != null || homeDashboard.mediaPlayers.any { it.available }) {
      Box(
        modifier =
          Modifier
            .align(Alignment.TopEnd)
            .padding(top = 18.dp, end = 16.dp)
            .width(236.dp)
            .heightIn(max = viewportHeight - 58.dp),
      ) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
          if (hasSchedules) {
            JarvisSchedulePanel(repository = scheduleRepository)
            Spacer(Modifier.height(10.dp))
          }
          if (showConversation) {
            JarvisConversationPanel(
              entries = jarvisConversationForDisplay(conversation),
              modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            )
          } else {
            JarvisMediaPanel(repository = homeRepository)
          }
        }
      }
    }

    Box(
      modifier =
        Modifier
          .align(Alignment.Center)
          .size(orbSize)
          .semantics { contentDescription = "JARVIS-Audioaktivität. Tippen zum Aufhellen." },
    ) {
      JarvisOrb(
        phase = phase,
        level = level,
        taskActive = talkAwaitingAgent || hasActiveTool,
        mediaPlaying = hasPlayingMedia,
        dimmed = idleAlpha,
        modifier = Modifier.fillMaxSize().combinedClickable(onClick = restoreBrightness),
      )
    }

    JarvisToolFeed(
      tools = if (runtimeNotice == null) activeTools else emptyList(),
      modifier = Modifier.align(Alignment.Center).offset(y = (orbSize / 2) + 8.dp),
    )
    JarvisHealthHud(
      components = healthState.summary?.components ?: unknownJarvisHealth(),
      connected = gateway.isConnected,
      gatewayError = healthState.errorText?.resolveNativeText(),
      nowMs = now.time,
      modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth(),
    )
        runtimeNotice?.let { notice ->
          Text(
            text = notice,
            color = Color(0xFFFFC56E),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp).width(260.dp),
          )
        }
        }
      }
    }

    if (showAmbientSettings) {
      JarvisAmbientSettingsDialog(
        settings = ambientSettings,
        onSettingsChanged = { updated ->
          ambientSettings = updated.sanitized
          ambientStore.save(ambientSettings)
          restoreBrightness()
        },
        onDismiss = {
          showAmbientSettings = false
          restoreBrightness()
        },
        onOpenFullSettings = {
          showAmbientSettings = false
          restoreBrightness()
          onOpenSettings()
        },
      )
    }

    // Ring and a firing alarm remain safety-critical overlays. Do not place a
    // normal household notice over either one.
    alexaAnnouncement?.takeIf { !hasActiveRing && scheduleAlert == null }?.let { announcement ->
      JarvisAnnouncementPanel(
        announcement = announcement,
        onReplay = viewModel::replayAlexaAnnouncement,
        onDismiss = viewModel::dismissAlexaAnnouncement,
        maxHeight =
          if (viewportWidth < 650.dp) {
            minOf(188.dp, viewportHeight * 0.38f)
          } else {
            (viewportHeight - 100.dp).coerceAtLeast(160.dp)
          },
        modifier =
          if (viewportWidth < 650.dp) {
            Modifier
              .align(Alignment.BottomCenter)
              .padding(start = 22.dp, end = 22.dp, bottom = 46.dp)
              .widthIn(max = 540.dp)
          } else {
            Modifier
              .align(Alignment.TopEnd)
              .padding(top = 18.dp, end = 16.dp)
              .widthIn(max = minOf(250.dp, viewportWidth * 0.28f))
          },
      )
    }
  }
}

@Suppress("DEPRECATION")
private fun jarvisMicrophoneMuted(context: Context): Boolean {
  val audioMuted = context.getSystemService(AudioManager::class.java)?.isMicrophoneMute == true
  val privacyMuted = runCatching {
    val manager = context.getSystemService("sensor_privacy") ?: return@runCatching false
    val method = manager.javaClass.getMethod("isSensorPrivacyEnabled", requireNotNull(Int::class.javaPrimitiveType))
    method.invoke(manager, 1) as? Boolean ?: false
  }.getOrDefault(false)
  return audioMuted || privacyMuted
}

@Composable
private fun JarvisToolFeed(
  tools: List<JarvisToolExecution>,
  modifier: Modifier = Modifier,
) {
  var renderedTools by remember { androidx.compose.runtime.mutableStateOf(emptyList<JarvisToolExecution>()) }
  LaunchedEffect(tools) {
    if (tools.isNotEmpty()) renderedTools = tools
  }
  val orderedTools =
    renderedTools
      .sortedWith(compareBy<JarvisToolExecution> { it.phase != JarvisToolPhase.Running }.thenByDescending { it.changedAtMs })
  // On the compact Show layout the work strip ends immediately above the health
  // footer. One detailed row fits at its existing type size; two do not.
  val visibleTools =
    if (orderedTools.firstOrNull()?.detail != null) {
      orderedTools.take(1)
    } else {
      orderedTools.take(2)
    }
  AnimatedVisibility(
    modifier = modifier,
    visible = tools.isNotEmpty(),
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(420)),
  ) {
    Column(
      modifier = Modifier.padding(top = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      visibleTools.forEach { tool ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "${tool.phase.jarvisToolMark()} ${tool.name}",
            color = tool.phase.jarvisToolColor(),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
          )
          tool.detail?.let { detail ->
            Text(
              text = detail,
              color = tool.phase.jarvisToolColor().copy(alpha = 0.82f),
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
              modifier = Modifier.widthIn(max = 236.dp),
            )
          }
        }
      }
    }
  }
}

private fun JarvisToolPhase.jarvisToolMark(): String =
  when (this) {
    JarvisToolPhase.Running -> "◌"
    JarvisToolPhase.Completed -> "✓"
    JarvisToolPhase.Failed -> "!"
    JarvisToolPhase.Unknown -> "?"
  }

private fun JarvisToolPhase.jarvisToolColor(): Color =
  when (this) {
    JarvisToolPhase.Running -> Color(0xFFE7C47B)
    JarvisToolPhase.Completed -> Color(0xFFDCE9D5)
    JarvisToolPhase.Failed -> Color(0xFFFFB79A)
    JarvisToolPhase.Unknown -> Color(0xFFC3CCD2)
  }

@Composable
private fun JarvisAmbientSettingsDialog(
  settings: JarvisAmbientSettings,
  onSettingsChanged: (JarvisAmbientSettings) -> Unit,
  onDismiss: () -> Unit,
  onOpenFullSettings: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Ambientmodus") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
      ) {
        Text("Nach Inaktivität abdunkeln")
        listOf(
          listOf(30_000L to "30 s", 60_000L to "1 min"),
          listOf(120_000L to "2 min", 300_000L to "5 min"),
        ).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { (timeout, label) ->
              Button(onClick = { onSettingsChanged(settings.copy(timeoutMs = timeout)) }, modifier = Modifier.weight(1f)) {
                Text(if (settings.timeoutMs == timeout) "✓ $label" else label)
              }
            }
          }
        }
        Text("Nacht-Helligkeit: ${(settings.nightBrightness * 100).toInt()} %")
        Slider(
          value = settings.nightBrightness,
          onValueChange = { onSettingsChanged(settings.copy(nightBrightness = it)) },
          valueRange = 0.03f..0.20f,
        )
        Text("Der Lichtsensor wählt nur die Dimmstufe. Ohne Anwesenheitssensor bleibt die Aktivitätszeit maßgeblich.", fontSize = 12.sp, color = Color(0xFF9FAEBC))
      }
    },
    confirmButton = { Button(onClick = onDismiss) { Text("Fertig") } },
    dismissButton = { Button(onClick = onOpenFullSettings) { Text("Weitere Einstellungen") } },
  )
}

@Composable
private fun JarvisAmbientWindowBrightness(
  window: Window?,
  active: Boolean,
  ambientBrightness: Float,
) {
  DisposableEffect(window, active, ambientBrightness) {
    if (window == null) return@DisposableEffect onDispose {}
    val previousBrightness = window.attributes.screenBrightness
    if (active) {
      window.attributes = window.attributes.apply { screenBrightness = ambientBrightness }
    }
    onDispose {
      window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
    }
  }
}

private fun Context.findActivity(): Activity? {
  var current: Context = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return current as? Activity
}

@Composable
private fun JarvisHealthHud(
  components: List<JarvisHealthComponent>,
  connected: Boolean,
  gatewayError: String?,
  nowMs: Long,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier) {
    if (maxWidth < 480.dp) {
      Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        var index = 0
        while (index < components.size) {
          Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(2) { offset ->
              components.getOrNull(index + offset)?.let { component ->
                JarvisHealthCell(component, connected, gatewayError, nowMs, Modifier.weight(1f))
              } ?: Spacer(Modifier.weight(1f))
            }
          }
          index += 2
        }
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        components.forEach { component ->
          JarvisHealthCell(component, connected, gatewayError, nowMs, Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun JarvisHealthCell(
  component: JarvisHealthComponent,
  connected: Boolean,
  gatewayError: String?,
  nowMs: Long,
  modifier: Modifier = Modifier,
) {
  val presentation = component.jarvisHealthPresentation(connected, gatewayError, nowMs)
  Column(
    modifier = modifier.jarvisHudFrame(7.dp, surfaceAlpha = 0.48f).padding(horizontal = 7.dp, vertical = 3.dp),
  ) {
    Text(
      text = "${component.label} · ${presentation.status}",
      color = presentation.color,
      fontSize = 10.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.25.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    presentation.detail?.let { detail ->
      Text(
        text = detail.substringBefore(';'),
        color = Color(0xFFAAB5BD),
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = detail },
      )
    }
  }
}

private data class JarvisHealthPresentation(
  val status: String,
  val detail: String?,
  val color: Color,
)

private fun JarvisHealthComponent.jarvisHealthPresentation(
  connected: Boolean,
  gatewayError: String?,
  nowMs: Long,
): JarvisHealthPresentation =
  when {
    !connected -> JarvisHealthPresentation("nicht verbunden", null, Color(0xFFFFB79A))
    gatewayError != null -> JarvisHealthPresentation("gestört", gatewayError, Color(0xFFFFC56E))
    checkedAtMs == null -> JarvisHealthPresentation("unbekannt", detail, Color(0xFFC3CCD2))
    isStale(nowMs) -> JarvisHealthPresentation("veraltet", detail, Color(0xFFFFC56E))
    status == JarvisHealthStatus.Ready -> JarvisHealthPresentation("geprüft", detail, Color(0xFFDCE9D5))
    status == JarvisHealthStatus.Degraded -> JarvisHealthPresentation("gestört", detail, Color(0xFFFFC56E))
    status == JarvisHealthStatus.Unreachable -> JarvisHealthPresentation("nicht erreichbar", detail, Color(0xFFFFB79A))
    else -> JarvisHealthPresentation("unbekannt", detail, Color(0xFFC3CCD2))
  }

@Composable
private fun JarvisHudBackdrop(modifier: Modifier = Modifier) {
  val nebula =
    rememberJarvisHorizonBitmap(
      resourceId = R.drawable.jarvis_horizon_nebula,
      maxEdgePx = if (rememberJarvisSmallPhysicalDisplay()) 1_024 else Int.MAX_VALUE,
    )
  Box(
    modifier = modifier.drawWithCache {
      val backdrop =
        Brush.radialGradient(
          colors = listOf(Color(0xFF1B3B6F).copy(alpha = 0.34f), Color(0xFF10294D).copy(alpha = 0.16f), Color(0xFF0A0F1E).copy(alpha = 0.96f)),
          center = Offset(size.width / 2f, size.height / 2f),
          radius = size.minDimension * 0.94f,
        )
      onDrawBehind {
        drawRect(brush = backdrop)
        var index = 0
        while (index < jarvisBackdropStars.size) {
          val x = jarvisBackdropStars[index] * size.width
          val y = jarvisBackdropStars[index + 1] * size.height
          val gold = index % 6 == 0
          drawCircle(
            color = (if (gold) Color(0xFFF6C878) else Color(0xFFE6F1FF)).copy(alpha = if (gold) 0.30f else 0.20f),
            radius = if (gold) 1.2.dp.toPx() else 0.8.dp.toPx(),
            center = Offset(x, y),
          )
          index += 2
        }
        // Peripheral traces suggest depth but remain below the hero and never form a visible grid.
        drawArc(
          color = Color(0xFF73DFE2).copy(alpha = 0.055f),
          startAngle = 198f,
          sweepAngle = 78f,
          useCenter = false,
          topLeft = Offset(-size.width * 0.15f, size.height * 0.08f),
          size = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.92f),
          style = Stroke(width = 1.dp.toPx()),
        )
        drawArc(
          color = Color(0xFFF6C878).copy(alpha = 0.045f),
          startAngle = 292f,
          sweepAngle = 62f,
          useCenter = false,
          topLeft = Offset(size.width * 0.62f, size.height * 0.08f),
          size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.86f),
          style = Stroke(width = 1.dp.toPx()),
        )
      }
    },
  ) {
    Image(
      bitmap = nebula,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      alpha = 0.48f,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

/** Normalized positions stay stable across Echo and phone sizes; this is a cached backdrop, never an animation. */
private val jarvisBackdropStars = floatArrayOf(
  0.08f, 0.16f, 0.16f, 0.30f, 0.24f, 0.10f, 0.31f, 0.68f,
  0.39f, 0.22f, 0.47f, 0.08f, 0.54f, 0.76f, 0.61f, 0.18f,
  0.69f, 0.52f, 0.76f, 0.12f, 0.83f, 0.71f, 0.91f, 0.27f,
)

private fun Modifier.jarvisHudFrame(radius: Dp = 11.dp, surfaceAlpha: Float = 1f): Modifier =
  drawWithCache {
    val radiusPx = radius.toPx()
    val border = Color(0xFF9ED6E6).copy(alpha = 0.20f * surfaceAlpha)
    val surface =
      Brush.linearGradient(
        colors = listOf(Color(0xFF1B3B6F).copy(alpha = 0.42f * surfaceAlpha), Color(0xFF0A0F1E).copy(alpha = 0.76f * surfaceAlpha)),
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height),
      )
    onDrawBehind {
      drawRoundRect(brush = surface, cornerRadius = CornerRadius(radiusPx, radiusPx))
      drawRoundRect(color = border, cornerRadius = CornerRadius(radiusPx, radiusPx), style = Stroke(width = 1.dp.toPx()))
    }
  }

/** The dim state is intentionally a different surface, not the normal HUD at lower opacity. */
@Composable
private fun JarvisAmbientComposition(
  now: Date,
  nextEvent: ai.openclaw.app.home.HomeCalendarEvent?,
  modifier: Modifier = Modifier,
) {
  val locale = Locale.getDefault()
  val time = remember(now, locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(now) }
  val date = remember(now, locale) { DateFormat.getDateInstance(DateFormat.FULL, locale).format(now) }
  val eventTimeFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEE HH:mm", locale).withZone(ZoneId.systemDefault()) }
  Box(
    modifier = modifier.background(Color(0xFF080B10)).semantics { contentDescription = "JARVIS Ambientmodus. Tippen zum Aufhellen." },
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(horizontal = 28.dp),
    ) {
      Text("JARVIS", color = Color(0xFFB08B4B), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
      Text(time, color = Color(0xFFD8DDE0), fontSize = 62.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
      Text(date, color = Color(0xFF77828A), fontSize = 14.sp, textAlign = TextAlign.Center)
      JarvisAmbientOrb(modifier = Modifier.padding(top = 14.dp).size(118.dp))
      nextEvent?.let { event ->
        val prefix = if (event.start.isAllDay) "Heute" else eventTimeFormatter.format(event.startsAt)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
          Text("NÄCHSTER TERMIN", color = Color(0xFF8C7346), fontSize = 10.sp, letterSpacing = 1.7.sp)
          Text("$prefix · ${event.summary}", color = Color(0xFFB8C0C4), fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
      }
    }
  }
}

/** No timer or animation loop here: this is the deliberately near-static ambient signature. */
@Composable
private fun JarvisAmbientOrb(modifier: Modifier = Modifier) {
  val smallDisplay = rememberJarvisSmallPhysicalDisplay()
  val coreBitmap = rememberJarvisHorizonBitmap(R.drawable.jarvis_horizon_core_v2, if (smallDisplay) 512 else Int.MAX_VALUE)
  // The current Horizon core already contains depth. On the Show the former
  // extra layer costs a full 1254px texture for detail below a physical pixel.
  val depthBitmap = if (smallDisplay) null else rememberJarvisHorizonBitmap(R.drawable.jarvis_horizon_core)
  val projection = remember { JarvisOrbProjection(jarvisPointCloud.size) }
  Canvas(modifier = modifier) {
    drawJarvisOrb(phase = JarvisPhase.Idle, level = 0f, motion = 0f, dimmed = 0.42f, projection = projection, coreBitmap = coreBitmap, depthBitmap = depthBitmap)
  }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun JarvisContext(
  viewModel: MainViewModel,
  now: Date,
  calendarHeight: Dp?,
  calendarEventLimit: Int,
  compact: Boolean = false,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val locale = Locale.getDefault()
  val time = remember(now, locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(now) }
  val date = remember(now, locale) { DateFormat.getDateInstance(DateFormat.FULL, locale).format(now) }
  Column(
    modifier =
      modifier
        .then(if (compact) Modifier.fillMaxWidth() else Modifier)
        .combinedClickable(onClick = {}, onLongClick = onOpenSettings)
        .semantics { contentDescription = "JARVIS. Gedrückt halten für Optionen." },
    horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
  ) {
    Text(text = "JARVIS", color = Color(0xFFF6C878), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
    Spacer(Modifier.height(if (compact) 6.dp else 4.dp))
    if (!compact) {
      Text(text = time, color = Color(0xFFE6F1FF), fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.2).sp)
      Spacer(Modifier.height(1.dp))
      Text(text = date, color = Color(0xFFA9BDD2), fontSize = 12.sp)
      Spacer(Modifier.height(7.dp))
    }
    Text(text = jarvisGreeting(now), color = Color(0xFFF6C878), fontSize = 17.sp, fontWeight = FontWeight.Medium)
    Text(
      text = if (compact) "$time · $date" else "Hier sind deine wichtigsten Termine für heute.",
      color = Color(0xFFB8C9DE),
      fontSize = 11.sp,
      lineHeight = 14.sp,
      textAlign = if (compact) TextAlign.Center else TextAlign.Start,
    )
    calendarHeight?.let { maxHeight ->
      JarvisHomePanel(
        viewModel = viewModel,
        maxCalendarEvents = calendarEventLimit,
        modifier = Modifier.padding(top = 10.dp).jarvisHudFrame().padding(9.dp).heightIn(max = maxHeight),
      )
    }
  }
}

private fun jarvisGreeting(now: Date): String =
  when (Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen!"
    in 11..17 -> "Guten Tag!"
    else -> "Guten Abend!"
  }

@Composable
internal fun JarvisConversationPanel(
  entries: List<VoiceConversationEntry>,
  modifier: Modifier = Modifier,
) {
  val scrollState = rememberScrollState()
  var followsNewest by remember { androidx.compose.runtime.mutableStateOf(true) }
  var userDragInProgress by remember { androidx.compose.runtime.mutableStateOf(false) }
  var entryIdAtDragStart by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
  var contentHeight by remember { androidx.compose.runtime.mutableStateOf(0) }
  val activeEntry = entries.lastOrNull()
  val latestEntryId by rememberUpdatedState(activeEntry?.id)
  val followThresholdPx = with(LocalDensity.current) { 24.dp.roundToPx() }
  LaunchedEffect(scrollState) {
    scrollState.interactionSource.interactions.collect { interaction ->
      when (interaction) {
        is DragInteraction.Start -> {
          userDragInProgress = true
          entryIdAtDragStart = latestEntryId
          // A reader who drags within this turn keeps their place while its text streams.
          followsNewest = false
        }
        is DragInteraction.Stop,
        is DragInteraction.Cancel -> {
          userDragInProgress = false
          followsNewest =
            jarvisConversationFollowsAfterDrag(
              activeEntryIdBeforeDrag = entryIdAtDragStart,
              activeEntryIdAfterDrag = latestEntryId,
              endedAtNewest = scrollState.maxValue - scrollState.value <= followThresholdPx,
            )
          entryIdAtDragStart = null
        }
      }
    }
  }
  LaunchedEffect(latestEntryId) {
    // A fresh spoken turn is more useful than an old reading position.
    followsNewest = true
  }
  LaunchedEffect(activeEntry?.id, activeEntry?.text, activeEntry?.isStreaming, contentHeight, followsNewest, userDragInProgress) {
    if (followsNewest && !userDragInProgress) {
      scrollState.scrollTo(scrollState.maxValue)
    }
  }
  Column(
    modifier = modifier.verticalScroll(scrollState),
    horizontalAlignment = Alignment.Start,
  ) {
    Column(modifier = Modifier.onSizeChanged { contentHeight = it.height }) {
      entries.forEachIndexed { index, entry ->
        Text(
          text = entry.text,
          color = if (entry.role == VoiceConversationRole.User) Color(0xFFFFD58E) else Color(0xFFE3F1F8),
          fontSize = 18.sp,
          lineHeight = 23.sp,
          fontWeight = if (entry.role == VoiceConversationRole.User) FontWeight.Medium else FontWeight.Normal,
          textAlign = TextAlign.Start,
        )
        if (entry.isStreaming) Text(text = "…", color = Color(0xFFFFD58E), fontSize = 16.sp)
        if (index < entries.lastIndex) Spacer(Modifier.height(14.dp))
      }
    }
  }
}

@Composable
private fun JarvisOrb(
  phase: JarvisPhase,
  level: Float,
  taskActive: Boolean,
  mediaPlaying: Boolean = false,
  dimmed: Float,
  modifier: Modifier = Modifier,
) {
  val animatedLevel by animateFloatAsState(targetValue = level, animationSpec = tween(140), label = "orb-level")
  val smallDisplay = rememberJarvisSmallPhysicalDisplay()
  val coreBitmap = rememberJarvisHorizonBitmap(R.drawable.jarvis_horizon_core_v2, if (smallDisplay) 512 else Int.MAX_VALUE)
  // Keep the additional depth texture for the QHD panel; the 960px Show
  // receives the same visual volume from core_v2 plus its procedural filaments.
  val depthBitmap = if (smallDisplay) null else rememberJarvisHorizonBitmap(R.drawable.jarvis_horizon_core)
  val projection = remember { JarvisOrbProjection(jarvisPointCloud.size) }
  val frameIntervalNanos =
    remember(phase, taskActive) {
      when {
        phase == JarvisPhase.Listening || phase == JarvisPhase.WakeListening || phase == JarvisPhase.Speaking -> 1_000_000_000L / 30L
        phase == JarvisPhase.Thinking || taskActive -> 1_000_000_000L / 20L
        else -> 1_000_000_000L / 15L
      }
    }
  var motionNanos by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
  LaunchedEffect(frameIntervalNanos) {
    var lastFrameNanos = Long.MIN_VALUE
    while (true) {
      androidx.compose.runtime.withFrameNanos { frameNanos ->
        if (lastFrameNanos == Long.MIN_VALUE || frameNanos - lastFrameNanos >= frameIntervalNanos) {
          motionNanos = frameNanos
          lastFrameNanos = frameNanos
        }
      }
    }
  }
  Canvas(modifier = modifier.testTag("jarvis-orb")) {
    drawJarvisOrb(
      phase = phase,
      level = animatedLevel,
      motion = motionNanos * 0.000000001f,
      dimmed = dimmed,
      taskActive = taskActive,
      mediaPlaying = mediaPlaying,
      projection = projection,
      coreBitmap = coreBitmap,
      depthBitmap = depthBitmap,
    )
  }
}

private fun DrawScope.drawJarvisOrb(
  phase: JarvisPhase,
  level: Float,
  motion: Float,
  dimmed: Float,
  taskActive: Boolean = false,
  mediaPlaying: Boolean = false,
  projection: JarvisOrbProjection = JarvisOrbProjection(jarvisPointCloud.size),
  coreBitmap: ImageBitmap? = null,
  depthBitmap: ImageBitmap? = null,
) {
  val center = this.center
  val activity = if (phase == JarvisPhase.Speaking) level else level * 0.72f
  val energy = (activity * 0.92f) + (sin(motion.toDouble() * 0.64).toFloat() + 1f) * 0.018f
  // The shell remains comfortably inside the Canvas on the 960 x 480 Echo,
  // while its 0.74 diameter keeps Horizon's orb as the visual anchor.
  val baseRadius = size.minDimension * (0.37f + energy * 0.035f)
  val cyan = phase.color
  val gold = Color(0xFFF6C878)
  if (taskActive || phase == JarvisPhase.Thinking) {
    drawJarvisTaskFog(center = center, radius = baseRadius, motion = motion, gold = gold, energy = energy, dimmed = dimmed)
  }
  // Home Assistant reports playback on a separate device. This is a quiet state orbit,
  // intentionally not a claimed waveform or an inferred audio level.
  if (mediaPlaying && phase == JarvisPhase.Idle) {
    drawArc(
      color = Color(0xFF73DFE2).copy(alpha = 0.18f * dimmed),
      startAngle = motion * 9f,
      sweepAngle = 72f,
      useCenter = false,
      topLeft = Offset(center.x - baseRadius * 1.04f, center.y - baseRadius * 1.04f),
      size = androidx.compose.ui.geometry.Size(baseRadius * 2.08f, baseRadius * 2.08f),
      style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
    )
  }
  // Back halves establish depth before the translucent sphere is painted.
  drawJarvisOrbit(center, baseRadius, -32f, motion * 22f, 18f, false, gold, dimmed, energy)
  drawJarvisOrbit(center, baseRadius, 55f, motion * -16f, 126f, false, gold, dimmed, energy)
  drawJarvisOrbit(center, baseRadius, 8f, motion * 13f, 238f, false, gold, dimmed, energy)
  drawCircle(
    brush = Brush.radialGradient(
      listOf(
        Color(0xFF071426).copy(alpha = 0.74f * dimmed),
        Color(0xFF102C55).copy(alpha = 0.58f * dimmed),
        Color(0xFF1B70A0).copy(alpha = (0.18f + energy * 0.12f) * dimmed),
        Color.Transparent,
      ),
      center,
      baseRadius * 1.10f,
    ),
    radius = baseRadius * 1.10f,
    center = center,
  )
  drawCircle(
    color = Color(0xFF86E7F2).copy(alpha = (0.20f + energy * 0.14f) * dimmed),
    radius = baseRadius * 0.88f,
    center = center,
    style = Stroke(width = 1.25.dp.toPx()),
  )
  // A restrained upper-left crescent supplies the Horizon shell's light source.
  drawArc(
    color = Color(0xFFE6F1FF).copy(alpha = (0.52f + energy * 0.16f) * dimmed),
    startAngle = 202f,
    sweepAngle = 86f,
    useCenter = false,
    topLeft = Offset(center.x - baseRadius * 0.88f, center.y - baseRadius * 0.88f),
    size = androidx.compose.ui.geometry.Size(baseRadius * 1.76f, baseRadius * 1.76f),
    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
  )
  drawCircle(
    brush = Brush.radialGradient(
      listOf(Color(0xFFE6F1FF).copy(alpha = (0.18f + energy * 0.10f) * dimmed), Color(0xFF73DFE2).copy(alpha = 0.04f * dimmed), Color.Transparent),
      Offset(center.x - baseRadius * 0.37f, center.y - baseRadius * 0.40f),
      baseRadius * 0.42f,
    ),
    radius = baseRadius * 0.46f,
    center = Offset(center.x - baseRadius * 0.30f, center.y - baseRadius * 0.32f),
  )
  val textureDiameter = (baseRadius * 2f / 0.76f * (1f + energy * 0.025f)).toInt()
  depthBitmap?.let { bitmap ->
    drawImage(
      image = bitmap,
      dstOffset = IntOffset((center.x - textureDiameter / 2f).toInt(), (center.y - textureDiameter / 2f).toInt()),
      dstSize = IntSize(textureDiameter, textureDiameter),
      alpha = (0.18f + energy * 0.05f) * dimmed,
    )
  }
  coreBitmap?.let { bitmap ->
    // The source is transparent around a sphere that occupies roughly 76% of its square.
    // It provides the deep blue holographic volume; all live state remains procedural above it.
    drawImage(
      image = bitmap,
      dstOffset = IntOffset((center.x - textureDiameter / 2f).toInt(), (center.y - textureDiameter / 2f).toInt()),
      dstSize = IntSize(textureDiameter, textureDiameter),
      alpha = (0.90f + energy * 0.06f) * dimmed,
    )
  }
  drawPointCloud(center = center, radius = baseRadius * 0.82f, motion = motion, energy = energy, gold = cyan, dimmed = dimmed, projection = projection)
  // The front halves and their sparse travelling glints sit above the body.
  drawJarvisOrbit(center, baseRadius, -32f, motion * 22f, 18f, true, gold, dimmed, energy)
  drawJarvisOrbit(center, baseRadius, 55f, motion * -16f, 126f, true, gold, dimmed, energy)
  drawJarvisOrbit(center, baseRadius, 8f, motion * 13f, 238f, true, gold, dimmed, energy)
}

/** One physical ellipse, painted as a rear or front half. No retained collections are allocated per frame. */
private fun DrawScope.drawJarvisOrbit(
  center: Offset,
  sphereRadius: Float,
  rotationDegrees: Float,
  travelDegrees: Float,
  phaseDegrees: Float,
  front: Boolean,
  gold: Color,
  dimmed: Float,
  energy: Float,
) {
  val orbitRadius = sphereRadius * 1.25f
  val ovalWidth = orbitRadius * 2f
  val ovalHeight = orbitRadius * 0.60f
  val topLeft = Offset(center.x - ovalWidth / 2f, center.y - ovalHeight / 2f)
  val orbitSize = androidx.compose.ui.geometry.Size(ovalWidth, ovalHeight)
  val brightStart = if (front) 2f else 182f
  val positionDegrees = (travelDegrees + phaseDegrees) % 360f
  rotate(degrees = rotationDegrees, pivot = center) {
    drawArc(
      color = gold.copy(alpha = 0.055f * dimmed),
      startAngle = brightStart,
      sweepAngle = 176f,
      useCenter = false,
      topLeft = topLeft,
      size = orbitSize,
      style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
      color = gold.copy(alpha = (if (front) 0.40f + energy * 0.20f else 0.16f + energy * 0.10f) * dimmed),
      startAngle = brightStart,
      sweepAngle = 176f,
      useCenter = false,
      topLeft = topLeft,
      size = orbitSize,
      style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
    )
  }
  if (front) {
    val angleRadians = positionDegrees * PI.toFloat() / 180f
    val localX = cos(angleRadians.toDouble()).toFloat() * ovalWidth / 2f
    val localY = sin(angleRadians.toDouble()).toFloat() * ovalHeight / 2f
    val rotationRadians = rotationDegrees * PI.toFloat() / 180f
    val glint = Offset(
      center.x + localX * cos(rotationRadians.toDouble()).toFloat() - localY * sin(rotationRadians.toDouble()).toFloat(),
      center.y + localX * sin(rotationRadians.toDouble()).toFloat() + localY * cos(rotationRadians.toDouble()).toFloat(),
    )
    drawCircle(color = gold.copy(alpha = (0.08f + energy * 0.16f) * dimmed), radius = (6f + energy * 4f).dp.toPx(), center = glint)
    drawCircle(color = Color(0xFFFFF1CE).copy(alpha = (0.64f + energy * 0.25f) * dimmed), radius = (1.5f + energy).dp.toPx(), center = glint)
  }
}

/** Two bounded low-alpha gradients give work in progress a quiet, physical presence without blur layers. */
private fun DrawScope.drawJarvisTaskFog(
  center: Offset,
  radius: Float,
  motion: Float,
  gold: Color,
  energy: Float,
  dimmed: Float,
) {
  val driftX = sin(motion.toDouble() * 0.19).toFloat() * radius * 0.22f
  val driftY = cos(motion.toDouble() * 0.16).toFloat() * radius * 0.14f
  val primaryCenter = Offset(center.x + driftX, center.y - radius * 0.18f + driftY)
  val secondaryCenter = Offset(center.x - driftX * 0.72f, center.y + radius * 0.24f - driftY)
  val alpha = (0.042f + energy * 0.035f) * dimmed
  drawOval(
    brush = Brush.radialGradient(listOf(gold.copy(alpha = alpha), Color.Transparent), primaryCenter, radius * 1.28f),
    topLeft = Offset(center.x - radius * 1.28f, center.y - radius * 0.92f),
    size = androidx.compose.ui.geometry.Size(radius * 2.56f, radius * 1.84f),
  )
  drawOval(
    brush = Brush.radialGradient(listOf(gold.copy(alpha = alpha * 0.68f), Color.Transparent), secondaryCenter, radius),
    topLeft = Offset(center.x - radius, center.y - radius * 0.48f),
    size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 1.52f),
  )
  // The active task owns this ribbon: it advances at a distinct slow cadence from audio glints.
  drawArc(
    color = gold.copy(alpha = (0.14f + energy * 0.18f) * dimmed),
    startAngle = motion * 27f,
    sweepAngle = 74f + energy * 34f,
    useCenter = false,
    topLeft = Offset(center.x - radius * 1.18f, center.y - radius * 0.54f),
    size = androidx.compose.ui.geometry.Size(radius * 2.36f, radius * 1.08f),
    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
  )
}

private data class JarvisPoint(
  val x: Float,
  val y: Float,
  val z: Float,
  val next: Int,
  val cross: Int,
)

/** Retained mutable projection workspace: the Canvas owns it on the UI thread. */
private class JarvisOrbProjection(count: Int) {
  val x = FloatArray(count)
  val y = FloatArray(count)
  val depth = FloatArray(count)
}

private val jarvisPointCloud: List<JarvisPoint> = buildList {
  val count = 180
  val goldenAngle = PI * (3.0 - sqrt(5.0))
  repeat(count) { index ->
    val z = 1.0 - (2.0 * (index + 0.5) / count)
    val radial = sqrt(1.0 - (z * z))
    val angle = goldenAngle * index
    add(
      JarvisPoint(
        x = (cos(angle) * radial).toFloat(),
        y = (sin(angle) * radial).toFloat(),
        z = z.toFloat(),
        next = (index + 1) % count,
        cross = (index + 13) % count,
      ),
    )
  }
}

private fun DrawScope.drawPointCloud(
  center: androidx.compose.ui.geometry.Offset,
  radius: Float,
  motion: Float,
  energy: Float,
  gold: Color,
  dimmed: Float,
  projection: JarvisOrbProjection,
) {
  val cosY = cos(motion.toDouble() * 0.42).toFloat()
  val sinY = sin(motion.toDouble() * 0.42).toFloat()
  val cosX = cos(motion.toDouble() * 0.27).toFloat()
  val sinX = sin(motion.toDouble() * 0.27).toFloat()
  jarvisPointCloud.forEachIndexed { index, point ->
    val rotatedX = point.x * cosY - point.z * sinY
    val rotatedZ = point.x * sinY + point.z * cosY
    val rotatedY = point.y * cosX - rotatedZ * sinX
    val depth = (rotatedZ * cosX + point.y * sinX).coerceIn(-1f, 1f)
    val scale = 0.73f + (depth + 1f) * 0.27f
    projection.x[index] = center.x + rotatedX * radius * scale
    projection.y[index] = center.y + rotatedY * radius * scale
    projection.depth[index] = depth
  }
  jarvisPointCloud.forEachIndexed { index, point ->
    val depth = projection.depth[index]
    val position = Offset(projection.x[index], projection.y[index])
    // Sparse near-neighbour filaments preserve depth without turning the orb
    // into a flat network graph on the small Echo display.
    if (index < point.next && index % 9 == 0) {
      drawLine(
        gold.copy(alpha = (0.008f + (depth + 1f) * 0.012f + energy * 0.012f) * dimmed),
        position,
        Offset(projection.x[point.next], projection.y[point.next]),
        strokeWidth = radius * 0.0035f,
      )
    }
    if (index < point.cross && index % 45 == 0) {
      drawLine(
        gold.copy(alpha = (0.008f + energy * 0.010f) * dimmed),
        position,
        Offset(projection.x[point.cross], projection.y[point.cross]),
        strokeWidth = radius * 0.003f,
      )
    }
  }
  repeat(2) { pass ->
    jarvisPointCloud.indices.forEach { index ->
      val depth = projection.depth[index]
      if ((pass == 0) != (depth < 0f)) return@forEach
      val depthScale = 0.55f + (depth + 1f) * 0.45f
      drawCircle(
        gold.copy(alpha = (0.045f + depthScale * 0.20f + energy * 0.055f) * dimmed),
        radius = radius * (0.0030f + depthScale * 0.0048f),
        center = Offset(projection.x[index], projection.y[index]),
      )
    }
  }
}
