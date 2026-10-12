@file:Suppress("DEPRECATION")

package ai.openclaw.app.home

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import org.webrtc.VideoFrame

enum class CameraStreamFormat { Hls, Mp4 }

/** Default bound from a request until a normal camera renderer produces its first frame. */
internal const val DEFAULT_CAMERA_FIRST_FRAME_TIMEOUT_MILLIS = 15_000L

/**
 * The private Ctronics MP4 bridge starts a fresh H264/AAC ffmpeg producer on demand.
 * Its renderer has the same bounded thirty-second first-frame window as the native
 * player, while all ordinary HA HLS/WebRTC sources retain the shorter default.
 */
internal const val DIRECT_CTRONICS_MP4_FIRST_FRAME_TIMEOUT_MILLIS = 30_000L

/** Ephemeral stream URL; deliberately no data-class toString that could expose the stream token. */
class CameraHlsSource(
  val url: String,
  val occurredAt: Instant,
  val format: CameraStreamFormat = CameraStreamFormat.Hls,
  val firstFrameTimeoutMillis: Long = DEFAULT_CAMERA_FIRST_FRAME_TIMEOUT_MILLIS,
)

private const val DIRECT_CAMERA_ENTITY_PREFIX = "camera."
private const val GO2RTC_API_PORT = 1984

/**
 * A view-only camera route derives its stream name from the configured camera entity.
 * It never supplies a host, credentials, or intercom session; unavailable go2rtc endpoints
 * fall back to the caller's ordinary camera source.
 */
internal fun directCameraMp4Url(base: HttpUrl, cameraEntityId: String, viewOnly: Boolean): String? {
  if (
    !viewOnly ||
    !cameraEntityId.startsWith(DIRECT_CAMERA_ENTITY_PREFIX) ||
     cameraEntityId.length == DIRECT_CAMERA_ENTITY_PREFIX.length ||
     base.scheme != "http"
  ) return null
  return base.newBuilder()
    .username("")
    .password("")
    .port(GO2RTC_API_PORT)
    .encodedPath("/api/stream.mp4")
    .query(null)
    .addQueryParameter("src", cameraEntityId.removePrefix(DIRECT_CAMERA_ENTITY_PREFIX))
    .build()
    .toString()
}

internal fun directCameraMp4Source(
  base: HttpUrl,
  cameraEntityId: String,
  occurredAt: Instant,
  viewOnly: Boolean,
): CameraHlsSource? = directCameraMp4Url(base, cameraEntityId, viewOnly)?.let {
  CameraHlsSource(
    url = it,
    occurredAt = occurredAt,
    format = CameraStreamFormat.Mp4,
    firstFrameTimeoutMillis = DIRECT_CTRONICS_MP4_FIRST_FRAME_TIMEOUT_MILLIS,
  )
}

/**
 * Admit the direct Ctronics handoff only for the exact view which is still
 * awaiting its first frame. Attaching a renderer promotes the current view to
 * [DoorCameraLifecycle.Connecting] before the capability response returns, so
 * [DoorCameraLifecycle.Requested] alone is too narrow here.
 */
internal fun canPublishDirectCameraSource(
  source: CameraHlsSource,
  cameraEntityId: String,
  notice: RingNotice?,
  display: DoorCameraDisplayState,
): Boolean =
  notice?.occurredAt == source.occurredAt &&
    display.camera?.entityId == cameraEntityId &&
    display.lifecycle in setOf(DoorCameraLifecycle.Requested, DoorCameraLifecycle.Connecting)

/** The direct fragmented-MP4 producer owns a separate, 30-second first-frame guard. */
private fun isPendingDirectCtronicsFirstFrame(
  source: CameraHlsSource?,
  notice: RingNotice?,
  display: DoorCameraDisplayState,
): Boolean {
  val directSource = source ?: return false
  return directSource.format == CameraStreamFormat.Mp4 &&
    directSource.firstFrameTimeoutMillis == DIRECT_CTRONICS_MP4_FIRST_FRAME_TIMEOUT_MILLIS &&
    canPublishDirectCameraSource(directSource, DIRECT_CAMERA_ENTITY_PREFIX, notice, display)
}

internal fun selectCameraTransport(root: JsonObject): String? {
  if (root["success"]?.jsonPrimitive?.content != "true") return null
  val result = root["result"] as? JsonObject ?: return null
  val types = (result["frontend_stream_types"] as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
  return when {
    // Generic HA/go2rtc sources can advertise WebRTC without a usable native ICE
    // route on this LAN. Prefer Android's native HLS player where HA exposes it;
    // native-only cameras such as Ring retain their working WebRTC path.
    "hls" in types -> "hls"
    "web_rtc" in types -> "web_rtc"
    else -> null
  }
}

internal fun resolveCameraHlsUrl(base: HttpUrl, path: String?): String? {
  val url = path?.let(base::resolve) ?: return null
  return url.takeIf { sameOrigin(base, it.toString()) && it.encodedPath.startsWith("/api/hls/") && it.username.isEmpty() && it.password.isEmpty() }?.toString()
}

internal fun parseHomeIceCandidate(candidate: JsonObject): org.webrtc.IceCandidate? {
  val value = (candidate["candidate"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
  // Browser/HA ICE permits a null mid. WebRTC's Android JNI requires a non-null
  // Java string; the m-line index identifies the bundled media section instead.
  val mid = (candidate["sdpMid"] as? JsonPrimitive)?.contentOrNull.orEmpty()
  val index = (candidate["sdpMLineIndex"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
  if (index < 0) return null
  return org.webrtc.IceCandidate(mid, index, value)
}

private const val ARTWORK_TIMEOUT_MILLIS = 10_000L
private const val ARTWORK_MAX_BYTES = 2 * 1024 * 1024
private const val ARTWORK_CACHE_MAX_BYTES = 6 * 1024 * 1024

/** Private Home Assistant data source for the fixed Jarvis display. */
class HomeDashboardRepository private constructor(context: Context) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = homeJson
  // HA bearer credentials are intentionally never replayed through redirects.
  private val http = OkHttpClient.Builder()
    .readTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
  // Covers hosted by a music provider are deliberately fetched without HA credentials.
  private val publicArtworkHttp = OkHttpClient.Builder()
    .connectTimeout(ARTWORK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    .readTimeout(ARTWORK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
  private val artworkCache = object : LruCache<String, ByteArray>(ARTWORK_CACHE_MAX_BYTES) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
  }
  private val preferences: SharedPreferences by lazy { encryptedPreferences(appContext) }
  @Volatile private var socket: WebSocket? = null
  private val liveFactsGeneration = LiveFactsGenerationGuard()
  private val announcementAdmission = AlexaAnnouncementAdmission()
  // HA can replay the same physical state_changed event after reconnect or after the user
  // has dismissed it. A timestamp is the source's stable identity for a Ring event.
  private val ringAdmission = RingNoticeAdmission()
  private val announcementEvents = Channel<AlexaAnnouncement>(capacity = ALEXA_ANNOUNCEMENT_BUFFER_CAPACITY)
  private val amazonNoticeAdmission = AmazonNoticeAdmission()
  private val amazonNoticeEvents = Channel<AmazonNotice>(capacity = AMAZON_NOTICE_BUFFER_CAPACITY)
  private val incomingMessageAdmission = IncomingMessageNoticeAdmission()
  private val incomingMessageEvents = Channel<IncomingMessageNotice>(capacity = 16)
  private val homeChangeTracker = HorizonHomeChangeTracker()
  private val _homeRoomChanges = MutableStateFlow<List<HorizonHomeRoomChange>>(emptyList())
  internal val homeRoomChanges: StateFlow<List<HorizonHomeRoomChange>> = _homeRoomChanges.asStateFlow()
  private val nextMessageId = java.util.concurrent.atomic.AtomicInteger(1)
  private var subscriptionStartedAt: Instant? = null
  private var pendingJarvisAnnouncementSubscriptionId: Int? = null
  private var reconnectAttempt = 0
  private var reconnectJob: Job? = null
  @Volatile private var lastForecastFetchAtMs = 0L
  private val webRtcLock = Any()
  private var webRtc: RingWebRtcSession? = null
  private var go2RtcIntercomBridge: Go2RtcIntercomBridge? = null
  private var webRtcGeneration = 0L
  private var webRtcNoticeAt: Instant? = null
  private var webRtcSessionId: String? = null
  private var pendingWebRtcConfigId: Int? = null
  private var pendingWebRtcOfferId: Int? = null
  private var pendingCameraCapabilitiesId: Int? = null
  private var pendingHlsStreamId: Int? = null
  private var webRtcDeadlineJob: Job? = null
  private var requestedCameraDeadlineJob: Job? = null
  private var requestedCameraNoticeAt: Instant? = null
  private var physicalRingDeadlineJob: Job? = null
  private var physicalRingNoticeAt: Instant? = null
  private var intercomTimeoutJob: Job? = null
  private var webRtcRenderer: VideoSink? = null
  private var webRtcMode = RingWebRtcMode.ViewOnly
  private var p2pPreparationJob: Job? = null
  private var pendingP2pCall: Call? = null

  private val _dashboard = MutableStateFlow(HomeDashboardState())
  val dashboard: StateFlow<HomeDashboardState> = _dashboard.asStateFlow()
  private val _ringNotice = MutableStateFlow<RingNotice?>(null)
  val ringNotice: StateFlow<RingNotice?> = _ringNotice.asStateFlow()
  // Only transport callbacks below advance this fact. Horizon and native overlays consume it.
  private val _ringCallState = MutableStateFlow(RingCallSourceState())
  internal val ringCallState: StateFlow<RingCallSourceState> = _ringCallState.asStateFlow()
  private val _doorCameraDisplay = MutableStateFlow(DoorCameraDisplayState())
  val doorCameraDisplay: StateFlow<DoorCameraDisplayState> = _doorCameraDisplay.asStateFlow()
  private val _cameraHlsSource = MutableStateFlow<CameraHlsSource?>(null)
  val cameraHlsSource: StateFlow<CameraHlsSource?> = _cameraHlsSource.asStateFlow()
  // Default closed until NodeRuntime publishes the device speaker/voice policy.
  // This avoids a newly opened live camera leaking audio while a wake reply is starting.
  private val _cameraAudioEnabled = MutableStateFlow(false)
  val cameraAudioEnabled: StateFlow<Boolean> = _cameraAudioEnabled.asStateFlow()
  /** A single runtime consumer routes completed household announcements to local presentation. */
  val announcements: Flow<AlexaAnnouncement> = announcementEvents.receiveAsFlow()
  /** Fresh, display-only Amazon notices received from another admitted household endpoint. */
  internal val amazonNotices: Flow<AmazonNotice> = amazonNoticeEvents.receiveAsFlow()
  internal val incomingMessages: Flow<IncomingMessageNotice> = incomingMessageEvents.receiveAsFlow()

  init {
    scope.launch {
      val imported = runCatching { importPrivateConfigurationIfPresent() }.getOrElse { false }
      if (!imported) return@launch
      val configured = runCatching { configuration() }.getOrElse {
        liveFactsGeneration.update {
          _dashboard.value =
            _dashboard.value.copy(
              connection = HomeConnectionState.Error,
              connectionError = "Home Assistant configuration unavailable",
            )
        }
        return@launch
      }
      if (configured == null) return@launch
      refreshCalendar()
      // Weather forecasts are throttled. Fetching before the WebSocket is authenticated
      // cannot publish the result, but would still consume the five-minute forecast window.
      // `auth_ok` always refreshes live facts for this generation.
      connectWebSocket()
      while (true) {
        delay(CALENDAR_REFRESH_MILLIS)
        refreshCalendar()
        refreshLiveFacts()
      }
    }
  }

  fun refresh() {
    scope.launch {
      refreshCalendar()
      refreshLiveFacts()
    }
  }

  /**
   * Publishes a source-admitted display notice through the existing authenticated HA socket.
   * A missing/replaced socket is an intentional non-delivery: source-local notification UI remains
   * authoritative and callers must not claim cross-device presentation.
   */
  internal fun publishAmazonNotice(notice: AmazonNotice): Boolean {
    if (_dashboard.value.connection != HomeConnectionState.Connected) return false
    val currentSocket = socket ?: return false
    val requestId = nextMessageId.getAndIncrement()
    return currentSocket.send(
      buildJsonObject {
        put("id", JsonPrimitive(requestId))
        put("type", JsonPrimitive("fire_event"))
        put("event_type", JsonPrimitive(JARVIS_AMAZON_NOTICE_EVENT_TYPE))
        put("event_data", notice.toHomeAssistantEventData())
      }.toString(),
    )
  }

  /** Display-only household fanout; message text is never submitted as an agent instruction. */
  internal fun publishIncomingMessage(notice: IncomingMessageNotice): Boolean {
    if (_dashboard.value.connection != HomeConnectionState.Connected) return false
    val currentSocket = socket ?: return false
    return currentSocket.send(buildJsonObject {
      put("id", nextMessageId.getAndIncrement())
      put("type", "fire_event")
      put("event_type", JARVIS_INCOMING_MESSAGE_EVENT_TYPE)
      put("event_data", notice.toHomeAssistantEventData())
    }.toString())
  }

  fun dismissRing() {
    synchronized(webRtcLock) {
      dismissRingLocked()
    }
  }

  /** Must run under [webRtcLock] so expiry cannot tear down a replacement camera. */
  private fun dismissRingLocked() {
    finishActiveRingCallOnDismiss()
    cancelRequestedCameraDeadlineLocked()
    cancelPhysicalRingDeadlineLocked()
    _cameraHlsSource.value = null
    _ringNotice.value = null
    _doorCameraDisplay.value = DoorCameraDisplayState(lifecycle = DoorCameraLifecycle.Closed)
    closeWebRtcLocked()
  }

  private fun finishActiveRingCallOnDismiss() {
    val source = _ringCallState.value
    if (source.phase in setOf(RingCallSourcePhase.Idle, RingCallSourcePhase.Declined, RingCallSourcePhase.Ended, RingCallSourcePhase.Failed)) return
    val notice = _ringNotice.value ?: return
    if (notice.manual || source.noticeAtMs != notice.occurredAt.toEpochMilli()) return
    _ringCallState.value = source.copy(
      phase = if (source.phase == RingCallSourcePhase.Connected) RingCallSourcePhase.Ended else RingCallSourcePhase.Declined,
      endedAtMs = System.currentTimeMillis(),
      error = null,
    )
  }

  private fun markRingCallFailed(noticeAt: Instant?) {
    val source = _ringCallState.value
    if (noticeAt == null || source.noticeAtMs != noticeAt.toEpochMilli() || source.phase in setOf(RingCallSourcePhase.Idle, RingCallSourcePhase.Declined, RingCallSourcePhase.Ended, RingCallSourcePhase.Failed)) return
    _ringCallState.value = source.copy(
      phase = RingCallSourcePhase.Failed,
      endedAtMs = System.currentTimeMillis(),
      error = "Live-Kamera konnte nicht verbunden werden",
    )
  }

  /** Opens the real HA door camera without inventing a doorbell event. */
  fun openDoorCamera() {
    // A manually requested camera is not a doorbell call and cancels any old call banner.
    _ringCallState.value = RingCallSourceState()
    val config = configuration()
    val camera = config?.cameraEntity?.let(::configuredCamera)
    if (config == null || camera == null) {
      _doorCameraDisplay.value = DoorCameraDisplayState(lifecycle = DoorCameraLifecycle.Failed)
      return
    }
    val occurredAt = Instant.now()
    synchronized(webRtcLock) {
      cancelPhysicalRingDeadlineLocked()
      closeWebRtcLocked()
      _cameraHlsSource.value = null
      _doorCameraDisplay.value = doorCameraActionState(_doorCameraDisplay.value.copy(camera = camera), DoorCameraAction.Open)
      scheduleRequestedCameraDeadline(occurredAt)
      _ringNotice.value = RingNotice(occurredAt = occurredAt, manual = true)
    }
  }

  /** Returns all cameras currently advertised by HA; discovery is deliberately request-scoped. */
  suspend fun listCameras(): Result<List<HomeCamera>> = withContext(Dispatchers.IO) {
    val config = configuration() ?: return@withContext Result.failure(IllegalStateException("Home Assistant configuration unavailable"))
    fetchCameraCatalog(config)
  }

  /**
   * Opens a unique selected camera without changing the configured Ring doorbell route.
   * Missing, unavailable and ambiguous selections have no display side effect.
   */
  suspend fun openCamera(requested: String, fullscreen: Boolean): HomeCameraSelection {
    if (requested.length !in 1..160) return HomeCameraSelection.Missing
    val catalog = listCameras().getOrElse { return HomeCameraSelection.Missing }
    return when (val selection = selectHomeCamera(catalog, requested)) {
      is HomeCameraSelection.Selected -> {
        // A manually selected source must never retain a prior physical Ring call state.
        _ringCallState.value = RingCallSourceState()
        val occurredAt = Instant.now()
        synchronized(webRtcLock) {
          cancelPhysicalRingDeadlineLocked()
          closeWebRtcLocked()
          _cameraHlsSource.value = null
          _doorCameraDisplay.value = DoorCameraDisplayState(
            presentation = if (fullscreen) DoorCameraPresentation.Fullscreen else DoorCameraPresentation.PiP,
            lifecycle = DoorCameraLifecycle.Requested,
            manual = true,
            camera = selection.camera,
          )
          scheduleRequestedCameraDeadline(occurredAt)
          _ringNotice.value = RingNotice(occurredAt = occurredAt, manual = true)
        }
        configuration()?.let { config -> scope.launch { loadSnapshot(config, occurredAt, selection.camera.entityId) } }
        selection
      }
      else -> selection
    }
  }

  /** Applies a local voice/display action and returns the resulting observed request state. */
  fun handleDoorCameraAction(action: DoorCameraAction): DoorCameraDisplayState {
    if (action == DoorCameraAction.Open) {
      openDoorCamera()
      return _doorCameraDisplay.value
    }
    if (_doorCameraDisplay.value.lifecycle == DoorCameraLifecycle.Closed) return _doorCameraDisplay.value
    val next = doorCameraActionState(_doorCameraDisplay.value, action)
    _doorCameraDisplay.value = next
    if (action == DoorCameraAction.Close) dismissRing()
    return _doorCameraDisplay.value
  }

  fun attachWebRtcRenderer(renderer: VideoSink) {
    webRtcRenderer = renderer
    startWebRtc()
  }

  /**
   * Starts a new send/receive camera session only after the voice owner has
   * explicitly admitted [microphone]. A normal camera view remains receive
   * only. Closing/minimizing the camera releases the lease through the session.
   */
  /** Validates the fixed, locally staged Ring source before microphone admission. */
  internal suspend fun prepareDoorIntercom(): Go2RtcIntercomRoute? = withContext(Dispatchers.IO) {
    val config = configuration() ?: return@withContext null
    val selected = _doorCameraDisplay.value.camera ?: return@withContext null
    // This source is intentionally bound to the configured Ring doorbell only.
    if (selected.entityId != config.cameraEntity || !selected.supportsLive || webRtcRenderer == null) return@withContext null
    val linkId = java.util.UUID.randomUUID().toString().replace("-", "")
    val endpoint = config.baseHttpUrl.newBuilder().addPathSegments("api/services/webrtc/create_link").build()
    val body = "{\"link_id\":\"$linkId\",\"url\":\"$GO2RTC_RING_INTERCOM_SOURCE\",\"open_limit\":2,\"time_to_live\":30}"
      .toRequestBody(JSON_MEDIA_TYPE)
    val created = runCatching {
      http.newCall(authenticatedRequest(endpoint, config).newBuilder().post(body).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
    if (!created) return@withContext null
    val httpUrl = config.baseHttpUrl.newBuilder().addPathSegments("api/webrtc/ws")
      .addQueryParameter("embed", "1").addQueryParameter("url", linkId).build()
    // OkHttp performs the WebSocket upgrade on an HTTP(S) Request; HttpUrl
    // itself deliberately rejects ws/wss in Builder.scheme().
    val route = Go2RtcIntercomRoute(httpUrl.toString(), selected.entityId)
    if (validateGo2RtcIntercomRoute(route)) route else null
  }

  private suspend fun validateGo2RtcIntercomRoute(route: Go2RtcIntercomRoute): Boolean {
    val answer = CompletableDeferred<Boolean>()
    lateinit var bridge: Go2RtcIntercomBridge
    val session = RingWebRtcSession(
      appContext,
      signal = { bridge.sendOffer(it) },
      onError = { answer.complete(false) },
    )
    session.onRemoteDescriptionAccepted = { answer.complete(true) }
    bridge = Go2RtcIntercomBridge(
      client = http,
      route = route,
      session = session,
      renderer = object : VideoSink { override fun onFrame(frame: VideoFrame?) = Unit },
      onFailure = { answer.complete(false) },
    )
    return try {
      bridge.connect()
      withTimeoutOrNull(GO2RTC_INTERCOM_PREFLIGHT_TIMEOUT_MILLIS) { answer.await() } == true
    } finally {
      bridge.close()
      session.close()
    }
  }

  internal fun startDoorIntercom(route: Go2RtcIntercomRoute, microphone: RingIntercomMicrophoneLease): Boolean {
    val display = _doorCameraDisplay.value
    if (display.lifecycle == DoorCameraLifecycle.Closed || display.camera?.entityId != route.cameraEntity || webRtcRenderer == null || _ringNotice.value == null) {
      microphone.release()
      return false
    }
    val started = startGo2RtcIntercom(route, microphone)
    if (started) scheduleIntercomTimeout()
    return started
  }

  /** Stops talkback but keeps the current camera view available as receive-only video/audio. */
  fun stopDoorIntercom(): Boolean {
    val display = _doorCameraDisplay.value
    val shouldResumeView = synchronized(webRtcLock) {
      if (!webRtcMode.offersMicrophone()) return false
      val source = _ringCallState.value
      if (source.phase in setOf(RingCallSourcePhase.Connecting, RingCallSourcePhase.Connected) && source.noticeAtMs == webRtcNoticeAt?.toEpochMilli()) {
        _ringCallState.value = source.copy(phase = RingCallSourcePhase.Ended, endedAtMs = System.currentTimeMillis(), error = null)
      }
      closeWebRtcLocked()
      display.lifecycle != DoorCameraLifecycle.Closed && display.camera != null && webRtcRenderer != null && socket != null
    }
    if (shouldResumeView) startWebRtc()
    return true
  }

  private fun scheduleIntercomTimeout() {
    synchronized(webRtcLock) {
      intercomTimeoutJob?.cancel()
      intercomTimeoutJob = scope.launch {
        delay(INTERCOM_MAX_DURATION_MILLIS)
        stopDoorIntercom()
      }
    }
  }

  fun detachWebRtcRenderer(renderer: VideoSink) {
    if (webRtcRenderer === renderer) {
      finishActiveRingCallOnDismiss()
      webRtcRenderer = null
      closeWebRtc()
    }
  }

  fun onWebRtcFirstFrame(renderer: VideoSink) {
    synchronized(webRtcLock) {
      if (webRtcRenderer !== renderer) return
      val occurredAt = webRtcNoticeAt ?: return
      val current = _ringNotice.value ?: return
      val display = _doorCameraDisplay.value
      if (current.occurredAt == occurredAt && !current.cameraFirstFrame && display.lifecycle !in setOf(DoorCameraLifecycle.Failed, DoorCameraLifecycle.Closed)) {
        _ringNotice.value = current.copy(cameraFirstFrame = true, cameraError = null)
        _doorCameraDisplay.value = display.copy(lifecycle = DoorCameraLifecycle.Live)
        webRtcDeadlineJob?.cancel()
        webRtcDeadlineJob = null
      }
    }
  }

  fun onHlsFirstFrame(source: CameraHlsSource) {
    synchronized(webRtcLock) {
      if (_cameraHlsSource.value !== source) return
      val current = _ringNotice.value ?: return
      val display = _doorCameraDisplay.value
      if (current.occurredAt != source.occurredAt || display.lifecycle in setOf(DoorCameraLifecycle.Failed, DoorCameraLifecycle.Closed)) return
      _ringNotice.value = current.copy(cameraFirstFrame = true, cameraError = null)
      _doorCameraDisplay.value = display.copy(lifecycle = DoorCameraLifecycle.Live)
      cancelRequestedCameraDeadlineLocked(source.occurredAt)
    }
  }

  fun onHlsFailure(source: CameraHlsSource) {
    synchronized(webRtcLock) {
      if (_cameraHlsSource.value !== source) return
      val current = _ringNotice.value ?: return
      if (current.occurredAt != source.occurredAt) return
      _ringNotice.value = current.copy(cameraFirstFrame = false, cameraError = "Live-Kamera konnte nicht verbunden werden")
      _doorCameraDisplay.value = _doorCameraDisplay.value.copy(presentation = DoorCameraPresentation.PiP, lifecycle = DoorCameraLifecycle.Failed)
      _cameraHlsSource.value = null
      cancelRequestedCameraDeadlineLocked(source.occurredAt)
      markRingCallFailed(current.occurredAt)
    }
  }

  /** Applies the already-resolved device audio policy to both live transports. */
  fun setCameraAudioEnabled(enabled: Boolean) {
    if (_cameraAudioEnabled.value == enabled) return
    _cameraAudioEnabled.value = enabled
    synchronized(webRtcLock) { webRtc?.setAudioEnabled(enabled) }
  }

  private fun startWebRtc(
    mode: RingWebRtcMode = RingWebRtcMode.ViewOnly,
    intercomMicrophone: RingIntercomMicrophoneLease? = null,
  ): Boolean {
    val config = configuration()
    val renderer = webRtcRenderer
    val activeSocket = socket
    val selected = _doorCameraDisplay.value.camera
    val camera = selected?.entityId
    if (config == null || renderer == null || activeSocket == null || camera == null) {
      intercomMicrophone?.release()
      reportWebRtcFailure()
      return false
    }
    if (!selected.supportsLive) {
      // The image load owns readiness; requested does not yet mean an image exists.
      intercomMicrophone?.release()
      return false
    }
    val notice = _ringNotice.value ?: run {
      intercomMicrophone?.release()
      return false
    }
    val generation: Long
    val session: RingWebRtcSession
    synchronized(webRtcLock) {
      closeWebRtcLocked()
      generation = ++webRtcGeneration
      webRtcNoticeAt = notice.occurredAt
      session = RingWebRtcSession(
        appContext,
        signal = { offer -> sendWebRtcOffer(generation, camera, offer) },
        onError = { reportWebRtcFailure(generation) },
        mode = mode,
        intercomMicrophone = intercomMicrophone,
      ).also { created ->
        created.onLocalCandidate = { sessionId, candidate -> sendWebRtcCandidate(generation, sessionId, candidate) }
        created.setAudioEnabled(_cameraAudioEnabled.value)
      }
      webRtc = session
      webRtcMode = mode
      cancelRequestedCameraDeadlineLocked(notice.occurredAt)
      _ringNotice.value = _ringNotice.value?.copy(cameraFirstFrame = false, cameraError = null)
      _doorCameraDisplay.value = _doorCameraDisplay.value.copy(lifecycle = DoorCameraLifecycle.Connecting)
      val p2pButton = selected.p2pStartButton
      if (p2pButton != null && selected.p2pState != EufyP2pState.Active) {
        // The bridge exposes no device-scoped lease. Keep the one preparation
        // command bounded; only this generation may continue to HA streaming.
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
          val started = EufyP2pPreparation(http).prepare(
            baseUrl = config.baseHttpUrl,
            token = config.token,
            cameraEntityId = camera,
            providerEntityId = selected.p2pProviderEntityId,
            startButtonEntityId = if (selected.p2pState == EufyP2pState.NeedsStart) p2pButton else null,
            maxWaitMillis = P2P_PREPARATION_TIMEOUT_MILLIS,
            stillCurrent = { synchronized(webRtcLock) { isCurrentCameraGenerationLocked(generation, camera) } },
            registerCall = { call ->
              synchronized(webRtcLock) {
                if (!isCurrentCameraGenerationLocked(generation, camera)) false else {
                  pendingP2pCall = call
                  true
                }
              }
            },
            unregisterCall = { call -> synchronized(webRtcLock) { if (pendingP2pCall === call) pendingP2pCall = null } },
          )
          synchronized(webRtcLock) {
            if (p2pPreparationJob === job) p2pPreparationJob = null
            if (!isCurrentCameraGenerationLocked(generation, camera)) return@synchronized
            if (!started || !requestCameraCapabilitiesLocked(generation, camera)) reportWebRtcFailure(generation)
          }
        }
        p2pPreparationJob = job
        job.start()
      } else if (!requestCameraCapabilitiesLocked(generation, camera)) {
        reportWebRtcFailure(generation)
      }
    }
    return true
  }

  private fun startGo2RtcIntercom(route: Go2RtcIntercomRoute, microphone: RingIntercomMicrophoneLease): Boolean {
    val renderer = webRtcRenderer ?: run { microphone.release(); return false }
    val generation: Long
    synchronized(webRtcLock) {
      closeWebRtcLocked()
      generation = ++webRtcGeneration
      webRtcNoticeAt = _ringNotice.value?.occurredAt
      lateinit var bridge: Go2RtcIntercomBridge
      val session = RingWebRtcSession(
        appContext,
        signal = { bridge.sendOffer(it) },
        onError = { reportWebRtcFailure(generation) },
        mode = RingWebRtcMode.Intercom,
        intercomMicrophone = microphone,
      )
      session.onConnected = {
        synchronized(webRtcLock) {
          if (webRtcGeneration == generation) {
            webRtcDeadlineJob?.cancel()
            webRtcDeadlineJob = null
            val source = _ringCallState.value
            if (source.phase == RingCallSourcePhase.Connecting && source.noticeAtMs == webRtcNoticeAt?.toEpochMilli()) {
              _ringCallState.value = source.copy(phase = RingCallSourcePhase.Connected, startedAtMs = System.currentTimeMillis())
            }
          }
        }
      }
      bridge = Go2RtcIntercomBridge(
        client = http,
        route = route,
        session = session,
        renderer = renderer,
        onFailure = { reportWebRtcFailure(generation) },
      )
      session.onLocalCandidate = { _, candidate -> bridge.sendCandidate(candidate) }
      session.setAudioEnabled(_cameraAudioEnabled.value)
      webRtc = session
      go2RtcIntercomBridge = bridge
      webRtcMode = RingWebRtcMode.Intercom
      _ringNotice.value?.takeUnless(RingNotice::manual)?.let { physicalNotice ->
        _ringCallState.value = RingCallSourceState(
          phase = RingCallSourcePhase.Connecting,
          noticeAtMs = physicalNotice.occurredAt.toEpochMilli(),
        )
      }
      _ringNotice.value = _ringNotice.value?.copy(cameraFirstFrame = false, cameraError = null)
      _doorCameraDisplay.value = _doorCameraDisplay.value.copy(lifecycle = DoorCameraLifecycle.Connecting)
      webRtcDeadlineJob = scope.launch {
        delay(WEB_RTC_TIMEOUT_MILLIS)
        reportWebRtcFailure(generation)
      }
      bridge.connect()
    }
    return true
  }

  /** Must run under [webRtcLock]. The live deadline starts after P2P preparation. */
  private fun requestCameraCapabilitiesLocked(generation: Long, camera: String): Boolean {
    if (!isCurrentCameraGenerationLocked(generation, camera)) return false
    val activeSocket = socket ?: return false
    val id = nextMessageId.getAndIncrement()
    pendingCameraCapabilitiesId = id
    if (!activeSocket.send("{\"id\":$id,\"type\":\"camera/capabilities\",\"entity_id\":\"${camera.jsonEscape()}\"}")) {
      pendingCameraCapabilitiesId = null
      return false
    }
    webRtcDeadlineJob?.cancel()
    webRtcDeadlineJob = scope.launch {
      delay(WEB_RTC_TIMEOUT_MILLIS)
      reportWebRtcFailure(generation)
    }
    return true
  }

  /** Must run under [webRtcLock]. */
  private fun isCurrentCameraGenerationLocked(generation: Long, camera: String): Boolean =
    generation == webRtcGeneration && webRtc != null && _doorCameraDisplay.value.camera?.entityId == camera

  private fun sendWebRtcOffer(generation: Long, camera: String, offer: String) {
    synchronized(webRtcLock) {
      if (generation != webRtcGeneration || webRtc == null) return
      val activeSocket = socket ?: run { reportWebRtcFailure(generation); return }
      val id = nextMessageId.getAndIncrement()
      pendingWebRtcOfferId = id
      if (!activeSocket.send("{\"id\":$id,\"type\":\"camera/webrtc/offer\",\"entity_id\":\"${camera.jsonEscape()}\",\"offer\":\"${offer.jsonEscape()}\"}")) {
        reportWebRtcFailure(generation)
      }
    }
  }

  private fun sendWebRtcCandidate(generation: Long, sessionId: String, candidate: org.webrtc.IceCandidate) {
    synchronized(webRtcLock) {
      if (generation != webRtcGeneration || webRtcSessionId != sessionId) return
      val activeSocket = socket ?: return
      val id = nextMessageId.getAndIncrement()
      if (!activeSocket.send("{\"id\":$id,\"type\":\"camera/webrtc/candidate\",\"session_id\":\"${sessionId.jsonEscape()}\",\"candidate\":{\"candidate\":\"${candidate.sdp.jsonEscape()}\",\"sdpMid\":\"${candidate.sdpMid?.jsonEscape().orEmpty()}\",\"sdpMLineIndex\":${candidate.sdpMLineIndex}}}")) {
        reportWebRtcFailure(generation)
      }
    }
  }

  private fun closeWebRtc() = synchronized(webRtcLock) { closeWebRtcLocked() }

  /** Bounds the visible Requested state even when no renderer is ever attached. */
  private fun scheduleRequestedCameraDeadline(
    noticeAt: Instant,
    timeoutMillis: Long = DEFAULT_CAMERA_FIRST_FRAME_TIMEOUT_MILLIS,
  ) {
    require(timeoutMillis > 0L)
    synchronized(webRtcLock) {
      requestedCameraDeadlineJob?.cancel()
      requestedCameraNoticeAt = noticeAt
      requestedCameraDeadlineJob = scope.launch {
        delay(timeoutMillis)
        failRequestedCamera(noticeAt)
      }
    }
  }

  /** Must run under [webRtcLock]. */
  private fun cancelRequestedCameraDeadlineLocked(noticeAt: Instant? = null) {
    if (noticeAt != null && requestedCameraNoticeAt != noticeAt) return
    requestedCameraDeadlineJob?.cancel()
    requestedCameraDeadlineJob = null
    requestedCameraNoticeAt = null
  }

  /** Physical Ring calls have one bounded lifetime from event admission, independent of transport updates. */
  private fun schedulePhysicalRingDeadline(noticeAt: Instant) {
    cancelPhysicalRingDeadlineLocked()
    physicalRingNoticeAt = noticeAt
    physicalRingDeadlineJob = scope.launch {
      delay(PHYSICAL_RING_CAMERA_TIMEOUT_MILLIS)
      dismissPhysicalRingWhenCurrent(noticeAt)
    }
  }

  /** Must run under [webRtcLock]. */
  private fun cancelPhysicalRingDeadlineLocked(noticeAt: Instant? = null) {
    if (noticeAt != null && physicalRingNoticeAt != noticeAt) return
    physicalRingDeadlineJob?.cancel()
    physicalRingDeadlineJob = null
    physicalRingNoticeAt = null
  }

  private fun dismissPhysicalRingWhenCurrent(noticeAt: Instant) {
    synchronized(webRtcLock) {
      val notice = _ringNotice.value
      if (!isCurrentPhysicalRing(noticeAt, physicalRingNoticeAt, notice)) return
      dismissRingLocked()
    }
  }

  /** A Request without a renderer or first stream transition must never leave a fullscreen black surface. */
  private fun failRequestedCamera(noticeAt: Instant) {
    synchronized(webRtcLock) {
      val current = _ringNotice.value ?: return
      val display = _doorCameraDisplay.value
      val directSource = _cameraHlsSource.value
      val directFirstFramePending =
        directSource?.occurredAt == noticeAt &&
          isPendingDirectCtronicsFirstFrame(directSource, current, display)
      if (!isCurrentRequestedCamera(noticeAt, current, display) && !directFirstFramePending) return
      Log.w(HOME_LOG_TAG, "Camera request timed out before connection")
      _ringNotice.value = current.copy(cameraError = "Live-Kamera konnte nicht verbunden werden")
      _doorCameraDisplay.value = display.copy(presentation = DoorCameraPresentation.PiP, lifecycle = DoorCameraLifecycle.Failed)
      _cameraHlsSource.value = null
      cancelRequestedCameraDeadlineLocked(noticeAt)
      markRingCallFailed(noticeAt)
      closeWebRtcLocked()
    }
  }

  private fun closeWebRtcLocked() {
    webRtcGeneration++
    // A close/switch must make the preparation request unable to publish a
    // stream for the former camera. The bridge's producer remains bounded by
    // its own 30-second TTL; this client never stops a possibly shared stream.
    p2pPreparationJob?.cancel()
    p2pPreparationJob = null
    pendingP2pCall?.cancel()
    pendingP2pCall = null
    webRtcDeadlineJob?.cancel()
    webRtcDeadlineJob = null
    intercomTimeoutJob?.cancel()
    intercomTimeoutJob = null
    pendingWebRtcConfigId = null
    pendingWebRtcOfferId = null
    pendingCameraCapabilitiesId = null
    pendingHlsStreamId = null
    webRtcSessionId = null
    webRtcNoticeAt = null
    go2RtcIntercomBridge?.close()
    go2RtcIntercomBridge = null
    webRtc?.close()
    webRtc = null
    webRtcMode = RingWebRtcMode.ViewOnly
  }

  private fun reportWebRtcFailure(expectedGeneration: Long? = null) {
    synchronized(webRtcLock) {
      if (expectedGeneration != null && expectedGeneration != webRtcGeneration) return
      val occurredAt = webRtcNoticeAt
      val current = _ringNotice.value
      val failedNotice = current?.takeIf { (occurredAt == null || it.occurredAt == occurredAt) && !it.cameraFirstFrame }
      if (failedNotice != null) {
        _ringNotice.value = failedNotice.copy(cameraError = "Live-Kamera konnte nicht verbunden werden")
        _doorCameraDisplay.value = _doorCameraDisplay.value.copy(presentation = DoorCameraPresentation.PiP, lifecycle = DoorCameraLifecycle.Failed)
        cancelRequestedCameraDeadlineLocked(failedNotice.occurredAt)
        markRingCallFailed(failedNotice.occurredAt)
        _cameraHlsSource.value = null
      }
      // A failed view must not retain a transport behind the Horizon overlay.
      if (failedNotice != null || webRtcMode.offersMicrophone()) closeWebRtcLocked()
    }
  }

  private suspend fun importPrivateConfigurationIfPresent(): Boolean = withContext(Dispatchers.IO) {
    val source = File(appContext.filesDir, IMPORT_FILE_NAME)
    if (source.isFile && !importFullConfiguration(source)) return@withContext false
    val devicesSource = File(appContext.filesDir, HOME_DEVICES_IMPORT_FILE_NAME)
    if (devicesSource.isFile && !importHomeDevicesPatch(devicesSource)) return@withContext false
    true
  }

  private fun importFullConfiguration(source: File): Boolean {
    val imported = runCatching { parseImport(source.readText()) }.getOrElse {
      liveFactsGeneration.update {
        _dashboard.value =
          _dashboard.value.copy(
            connection = HomeConnectionState.Error,
            connectionError = "Home Assistant import is invalid",
          )
      }
      return false
    }
    // commit is synchronous: only remove the ADB-delivered input after the encrypted store is durable.
    if (!preferences.edit().putString(CONFIG_KEY, json.encodeToString(HomeImport.serializer(), imported)).commit()) {
      liveFactsGeneration.update {
        _dashboard.value =
          _dashboard.value.copy(
            connection = HomeConnectionState.Error,
            connectionError = "Home Assistant import could not be saved",
          )
      }
      return false
    }
    source.delete()
    return true
  }

  /** Merges only explicitly provisioned Horizon device rows into the existing encrypted HA config. */
  private fun importHomeDevicesPatch(source: File): Boolean {
    val patch = runCatching { parseHomeDevicesImport(source.readText()) }.getOrElse {
      liveFactsGeneration.update {
        _dashboard.value = _dashboard.value.copy(connection = HomeConnectionState.Error, connectionError = "Home Assistant devices import is invalid")
      }
      return false
    }
    val existing = configuration()
    if (existing == null) {
      liveFactsGeneration.update {
        _dashboard.value = _dashboard.value.copy(connection = HomeConnectionState.Error, connectionError = "Home Assistant devices import needs existing configuration")
      }
      return false
    }
    val merged = existing.copy(homeDevices = patch.homeDevices)
    if (!preferences.edit().putString(CONFIG_KEY, json.encodeToString(HomeImport.serializer(), merged)).commit()) {
      liveFactsGeneration.update {
        _dashboard.value = _dashboard.value.copy(connection = HomeConnectionState.Error, connectionError = "Home Assistant devices import could not be saved")
      }
      return false
    }
    source.delete()
    return true
  }

  private fun configuration(): HomeImport? =
    preferences.getString(CONFIG_KEY, null)?.let { encoded -> runCatching { json.decodeFromString(HomeImport.serializer(), encoded) }.getOrNull() }

  private suspend fun refreshCalendar() = withContext(Dispatchers.IO) {
    val config = configuration()
    if (config == null) {
      liveFactsGeneration.update {
        _dashboard.value = _dashboard.value.copy(connection = HomeConnectionState.MissingConfiguration)
      }
      return@withContext
    }
    val now = ZonedDateTime.now()
    val start = now.toInstant().toString()
    val end = now.plusHours(24).toInstant().toString()
    val events = mutableListOf<HomeCalendarEvent>()
    var error: String? = null
    config.calendarEntities.forEach { entity ->
      val endpoint = config.baseHttpUrl.newBuilder().addPathSegments("api/calendars").addPathSegment(entity)
        .addQueryParameter("start", start).addQueryParameter("end", end).build()
      runCatching {
        http.newCall(authenticatedRequest(endpoint, config)).execute().use { response ->
          if (!response.isSuccessful) error("Calendar HTTP ${response.code}")
          parseCalendarEvents(entity, response.body.bytes())
        }
      }.onSuccess(events::addAll).onFailure { failure -> error = failure.message ?: "Calendar unavailable" }
    }
    liveFactsGeneration.update {
      _dashboard.value = _dashboard.value.copy(
        calendarEvents = events.sortedBy(HomeCalendarEvent::startsAt),
        calendarLoaded = true,
        calendarError = error,
      )
    }
  }

  /** Reads only the fixed display entities; media commands remain voice-owned. */
  private suspend fun refreshLiveFacts(
    expectedGeneration: Long = liveFactsGeneration.snapshot(),
  ) = withContext(Dispatchers.IO) {
    val config = configuration() ?: return@withContext
    val weather = fetchEntity(config, config.weatherEntity).map { state -> parseHomeWeather(state) }
    val forecast = if (isForecastRefreshDue()) fetchHourlyForecast(config) else null
    val players = config.mediaEntities.map { entity ->
      fetchEntity(config, entity).map { state -> parseHomeMediaPlayer(state, baseUrl = config.baseHttpUrl) }
    }
    val devices = config.homeDevices.map { device ->
      fetchEntity(config, device.entityId).map { state -> parseHomeDeviceState(state, device) }
    }
    val weatherFailure = weather.exceptionOrNull()
    val playerFailures = players.mapNotNull { it.exceptionOrNull() }
    val deviceFailures = devices.mapNotNull { it.exceptionOrNull() }
    // The network calls above deliberately run without a lock. Publish only if
    // this is still the same authenticated WebSocket generation; otherwise a
    // stale REST completion could resurrect facts cleared on disconnect.
    liveFactsGeneration.publishIfCurrent(expectedGeneration) {
      if (_dashboard.value.connection != HomeConnectionState.Connected) {
        return@publishIfCurrent false
      }
      _dashboard.value = _dashboard.value.copy(
        weather = weather.getOrNull()?.withForecastResult(forecast, _dashboard.value.weather),
        weatherLoaded = weather.isSuccess,
        weatherError = weatherFailure?.message,
        mediaPlayers = players.mapNotNull { player -> player.getOrNull() },
        mediaLoaded = playerFailures.isEmpty(),
        mediaError = playerFailures.firstOrNull()?.message,
        homeDevices = devices.mapNotNull { it.getOrNull() },
        homeDevicesLoaded = deviceFailures.isEmpty(),
        homeDevicesError = deviceFailures.firstOrNull()?.message,
      )
      homeChangeTracker.onSnapshot(_dashboard.value.homeDevices)
      requestVisibleMediaArtwork(expectedGeneration, config)
      true
    }
  }

  private fun fetchEntity(config: HomeImport, entityId: String): Result<JsonObject> = runCatching {
    val endpoint =
      config.baseHttpUrl.newBuilder().addPathSegments("api/states").addPathSegment(entityId).build()
    http.newCall(authenticatedRequest(endpoint, config)).execute().use { response ->
      if (!response.isSuccessful) error("$entityId HTTP ${response.code}")
      json.parseToJsonElement(response.body.bytes().decodeToString()).jsonObject
    }
  }

  private fun fetchCameraCatalog(config: HomeImport): Result<List<HomeCamera>> = runCatching {
    val endpoint = config.baseHttpUrl.newBuilder().addPathSegments("api/states").build()
    http.newCall(authenticatedRequest(endpoint, config)).execute().use { response ->
      if (!response.isSuccessful) error("camera catalog HTTP ${response.code}")
      parseHomeCameraCatalog(json.parseToJsonElement(response.body.bytes().decodeToString()).jsonArray)
    }
  }

  /**
   * Weather forecasts are not entity attributes. This read-only service call is bounded to once
   * per five minutes and leaves current weather intact when the configured integration declines
   * hourly forecasts or the HA version does not support response-returning services.
   */
  private fun fetchHourlyForecast(config: HomeImport): Result<List<HomeWeatherForecast>> = runCatching {
    val endpoint = config.baseHttpUrl.newBuilder().addPathSegments("api/services/weather/get_forecasts")
      .addQueryParameter("return_response", null).build()
    val body = "{\"entity_id\":\"${config.weatherEntity.jsonEscape()}\",\"type\":\"hourly\"}"
      .toRequestBody(JSON_MEDIA_TYPE)
    http.newCall(
      Request.Builder().url(endpoint).header("Authorization", "Bearer ${config.token}")
        .header("Content-Type", "application/json").post(body).build(),
    ).execute().use { response ->
      if (!response.isSuccessful) error("hourly forecast HTTP ${response.code}")
      parseHourlyForecastResponse(json.parseToJsonElement(response.body.bytes().decodeToString()).jsonObject, config.weatherEntity)
    }
  }.onSuccess { forecast ->
    Log.i(HOME_LOG_TAG, "Hourly forecast loaded: entity=${config.weatherEntity}, count=${forecast.size}")
  }.onFailure { error ->
    // Never log response bodies: Home Assistant output can contain provider-specific details.
    Log.w(HOME_LOG_TAG, "Hourly forecast unavailable: entity=${config.weatherEntity}, ${error.javaClass.simpleName}: ${error.message?.take(120)}")
  }

  private fun isForecastRefreshDue(nowMs: Long = System.currentTimeMillis()): Boolean {
    if (nowMs - lastForecastFetchAtMs < FORECAST_REFRESH_MILLIS) return false
    lastForecastFetchAtMs = nowMs
    return true
  }

  private fun connectWebSocket() {
    val config = configuration() ?: return
    val webSocketUrl = config.baseHttpUrl.newBuilder().addPathSegments("api/websocket").build()
    val previousSocket = socket
    val generation = liveFactsGeneration.begin {
      homeChangeTracker.onReconnect(emptyList())
      _homeRoomChanges.value = emptyList()
      _dashboard.value = _dashboard.value.copy(connection = HomeConnectionState.Connecting, connectionError = null)
    }
    previousSocket?.close(1000, "reconnect")
    subscriptionStartedAt = null
    pendingJarvisAnnouncementSubscriptionId = null
    socket =
      http.newWebSocket(
        Request.Builder().url(webSocketUrl).build(),
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) =
            handleWebSocketMessage(webSocket, config, generation, text)

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) =
            scheduleReconnect(webSocket, generation, t.message)

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            scheduleReconnect(webSocket, generation, reason.ifBlank { "Disconnected" })
        },
      )
  }

  private fun handleWebSocketMessage(
    webSocket: WebSocket,
    config: HomeImport,
    generation: Long,
    text: String,
  ) {
    if (socket !== webSocket || !liveFactsGeneration.isCurrent(generation)) return
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
    when (root["type"]?.jsonPrimitive?.content) {
      "auth_required" -> webSocket.send("{\"type\":\"auth\",\"access_token\":\"${config.token.jsonEscape()}\"}")
      "auth_ok" -> {
        reconnectAttempt = 0
        val markedConnected =
          liveFactsGeneration.publishIfCurrent(generation) {
            _dashboard.value = _dashboard.value.copy(connection = HomeConnectionState.Connected, connectionError = null)
            true
          }
        if (!markedConnected) return
        subscriptionStartedAt = Instant.now()
        val id = nextMessageId.getAndIncrement()
        webSocket.send("{\"id\":$id,\"type\":\"subscribe_events\",\"event_type\":\"state_changed\"}")
        val announcementSubscriptionId = nextMessageId.getAndIncrement()
        if (webSocket.send("{\"id\":$announcementSubscriptionId,\"type\":\"subscribe_events\",\"event_type\":\"jarvis_announcement\"}")) {
          pendingJarvisAnnouncementSubscriptionId = announcementSubscriptionId
        } else {
          Log.w(HOME_LOG_TAG, "Jarvis announcement subscription was not sent")
        }
        val amazonSubscriptionId = nextMessageId.getAndIncrement()
        if (!webSocket.send("{\"id\":$amazonSubscriptionId,\"type\":\"subscribe_events\",\"event_type\":\"$JARVIS_AMAZON_NOTICE_EVENT_TYPE\"}")) {
          Log.w(HOME_LOG_TAG, "Amazon notice subscription was not sent")
        }
        val incomingMessageSubscriptionId = nextMessageId.getAndIncrement()
        if (!webSocket.send("{\"id\":$incomingMessageSubscriptionId,\"type\":\"subscribe_events\",\"event_type\":\"$JARVIS_INCOMING_MESSAGE_EVENT_TYPE\"}")) {
          Log.w(HOME_LOG_TAG, "Incoming message subscription was not sent")
        }
        scope.launch { refreshLiveFacts(generation) }
      }
      "auth_invalid" -> markAuthenticationFailed(generation)
      "event" -> {
        handleHomeAssistantEvent(config, generation, root)
        handleWebRtcEvent(root)
      }
      "result" -> {
        handleJarvisAnnouncementSubscriptionResult(root)
        handleCameraStreamResult(root)
      }
    }
  }

  private fun handleHomeAssistantEvent(config: HomeImport, generation: Long, root: JsonObject) {
    liveFactsGeneration.publishIfCurrent(generation) {
      if (_dashboard.value.connection != HomeConnectionState.Connected) {
        return@publishIfCurrent false
      }
      parseJarvisAnnouncementEvent(root, announcementAdmission)?.let(::enqueueAlexaAnnouncement)
      parseJarvisAmazonNoticeEvent(root, amazonNoticeAdmission)?.let { notice ->
        if (!amazonNoticeEvents.trySend(notice).isSuccess) Log.w(HOME_LOG_TAG, "Amazon notice buffer full")
      }
      parseJarvisIncomingMessageNoticeEvent(root, incomingMessageAdmission)?.let { notice ->
        if (!incomingMessageEvents.trySend(notice).isSuccess) Log.w(HOME_LOG_TAG, "Incoming message buffer full")
      }
      handleCurrentStateChanged(config, root)
      true
    }
  }

  private fun handleJarvisAnnouncementSubscriptionResult(root: JsonObject) {
    val id = root["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return
    if (id != pendingJarvisAnnouncementSubscriptionId) return
    pendingJarvisAnnouncementSubscriptionId = null
    if (root["success"]?.jsonPrimitive?.content != "true") {
      Log.w(HOME_LOG_TAG, "Jarvis announcement subscription was rejected")
    }
  }

  /** Runs under [liveFactsGeneration] so a queued WebSocket event cannot revive cleared HUD facts. */
  private fun handleCurrentStateChanged(config: HomeImport, root: JsonObject) {
    val event = root["event"]?.jsonObject ?: return
    val data = event["data"]?.jsonObject ?: return
    val entityId = data["entity_id"]?.jsonPrimitive?.content ?: return
    val newState = data["new_state"]?.jsonObject ?: return
    parseAlexaAnnouncementEvent(root, announcementAdmission)?.let(::enqueueAlexaAnnouncement)
    if (entityId == config.weatherEntity) {
      _dashboard.value = _dashboard.value.copy(
        weather = parseHomeWeather(newState, entityId).copy(
          hourlyForecast = _dashboard.value.weather?.hourlyForecast.orEmpty(),
          forecastLoaded = _dashboard.value.weather?.forecastLoaded == true,
          forecastError = _dashboard.value.weather?.forecastError,
        ),
        weatherLoaded = true,
        weatherError = null,
      )
      return
    }
    if (entityId in config.mediaEntities) {
      val updated = parseHomeMediaPlayer(newState, entityId, config.baseHttpUrl)
      _dashboard.value = _dashboard.value.copy(
        mediaPlayers = (_dashboard.value.mediaPlayers.filterNot { it.entityId == entityId } + updated).sortedBy(HomeMediaPlayer::name),
        mediaLoaded = true,
        mediaError = null,
      )
      requestVisibleMediaArtwork(liveFactsGeneration.snapshot(), config)
      return
    }
    val configuredDevice = config.homeDevices.firstOrNull { it.entityId == entityId }
    if (configuredDevice != null) {
      val updated = parseHomeDeviceState(newState, configuredDevice)
      _dashboard.value = _dashboard.value.copy(
        homeDevices = (_dashboard.value.homeDevices.filterNot { it.entityId == entityId } + updated).sortedBy(HomeDeviceState::name),
        homeDevicesLoaded = true,
        homeDevicesError = null,
      )
      val eventAt = event["time_fired"]?.jsonPrimitive?.contentOrNull?.toInstantOrNull()?.toEpochMilli()
        ?: System.currentTimeMillis()
      homeChangeTracker.onConfirmedStateChanged(_dashboard.value.homeDevices, entityId, eventAt)?.let { change ->
        _homeRoomChanges.value = (_homeRoomChanges.value.filter {
          it.roomKey != change.roomKey && it.highlightExpiresAtMs > System.currentTimeMillis()
        } + change).takeLast(32)
      }
      return
    }
    if (entityId != config.doorbellEntity) return
    if (!isDoorbellRingState(newState)) return
    val oldState = data["old_state"]?.jsonObject
    val newTime = stateTimestamp(newState) ?: return
    val oldTime = oldState?.let(::stateTimestamp)
    val began = subscriptionStartedAt ?: return
    if (!ringAdmission.admit(newTime, oldTime, began)) return
    val camera = config.cameraEntity ?: return
    synchronized(webRtcLock) {
      closeWebRtcLocked()
      _cameraHlsSource.value = null
      // Publish the complete display state before the notice triggers Compose to attach a renderer.
      // Publishing the notice first can attach against a null/old camera and leave Requested stuck.
      _doorCameraDisplay.value = DoorCameraDisplayState(
        presentation = DoorCameraPresentation.Fullscreen,
        lifecycle = DoorCameraLifecycle.Requested,
        camera = configuredCamera(camera),
      )
      _ringCallState.value = RingCallSourceState(
        phase = RingCallSourcePhase.Ringing,
        noticeAtMs = newTime.toEpochMilli(),
      )
      scheduleRequestedCameraDeadline(newTime)
      _ringNotice.value = RingNotice(occurredAt = newTime)
      schedulePhysicalRingDeadline(newTime)
    }
    scope.launch { loadSnapshot(config, newTime, camera) }
  }

  private fun enqueueAlexaAnnouncement(announcement: AlexaAnnouncement) {
    if (announcementEvents.trySend(announcement).isSuccess) return
    // The local presentation owner must remain responsive; overflow is explicit and never logs text.
    Log.w(HOME_LOG_TAG, "Alexa announcement queue is full; announcement was not enqueued")
  }

  private suspend fun loadSnapshot(config: HomeImport, occurredAt: Instant, camera: String) = withContext(Dispatchers.IO) {
    val endpoint = config.baseHttpUrl.newBuilder().addPathSegments("api/camera_proxy").addPathSegment(camera).build()
    val snapshot = runCatching {
      http.newCall(authenticatedRequest(endpoint, config)).execute().use { response ->
        if (!response.isSuccessful) error("Snapshot HTTP ${response.code}")
        response.body.bytes()
      }
    }.getOrNull()
    synchronized(webRtcLock) {
      val current = _ringNotice.value
      val display = _doorCameraDisplay.value
      if (current != null && current.occurredAt == occurredAt && display.camera?.entityId == camera) {
        _ringNotice.value = current.copy(snapshot = snapshot)
        if (display.camera.supportsLive == false) {
          _doorCameraDisplay.value = display.copy(lifecycle = if (snapshot != null) DoorCameraLifecycle.Snapshot else DoorCameraLifecycle.Failed)
        }
      }
    }
  }

  private fun handleCameraStreamResult(root: JsonObject) {
    val id = root["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
    synchronized(webRtcLock) {
      if (id == pendingCameraCapabilitiesId) {
        pendingCameraCapabilitiesId = null
        val camera = _doorCameraDisplay.value.camera?.entityId ?: return
        val config = configuration() ?: return
        if (root["success"]?.jsonPrimitive?.content != "true") {
          reportWebRtcFailure(webRtcGeneration)
          return
        }
        directCameraMp4Source(
          base = config.baseHttpUrl,
          cameraEntityId = camera,
          occurredAt = webRtcNoticeAt ?: run {
            reportWebRtcFailure(webRtcGeneration)
            return
          },
          viewOnly = !webRtcMode.offersMicrophone(),
        )?.let { source ->
          // This one mapping bypasses only HA's video-only HLS mux. The bridge is
          // still private to the configured HA host and has no microphone route.
          // The direct producer has its own bounded startup window. Never revive a
          // request which the shorter initial WebRTC guard has already retired.
          if (!canPublishDirectCameraSource(source, camera, _ringNotice.value, _doorCameraDisplay.value)) {
            return
          }
          webRtcDeadlineJob?.cancel()
          webRtcDeadlineJob = null
          _cameraHlsSource.value = source
          scheduleRequestedCameraDeadline(source.occurredAt, source.firstFrameTimeoutMillis)
          return
        }
        val transport = selectCameraTransport(root)
        // An HLS session has no WebRTC microphone path. Do not retain an
        // intercom lease and accidentally turn a view-only source into an
        // unbounded local capture attempt.
        if (webRtcMode.offersMicrophone() && transport != "web_rtc") {
          closeWebRtcLocked()
          reportWebRtcFailure(webRtcGeneration)
          return
        }
        val next = nextMessageId.getAndIncrement()
        val type = when (transport) {
          "web_rtc" -> { pendingWebRtcConfigId = next; "camera/webrtc/get_client_config" }
          "hls" -> { pendingHlsStreamId = next; "camera/stream" }
          else -> { reportWebRtcFailure(webRtcGeneration); return }
        }
        if (socket?.send("{\"id\":$next,\"type\":\"$type\",\"entity_id\":\"${camera.jsonEscape()}\"}") != true) reportWebRtcFailure(webRtcGeneration)
        return
      }
      if (id == pendingHlsStreamId) {
        pendingHlsStreamId = null
        val config = configuration() ?: return
        val path = root["result"]?.let { it as? JsonObject }?.get("url")?.jsonPrimitive?.contentOrNull
        val url = resolveCameraHlsUrl(config.baseHttpUrl, path)
        val occurredAt = webRtcNoticeAt
        if (root["success"]?.jsonPrimitive?.content != "true" || url == null || occurredAt == null) {
          reportWebRtcFailure(webRtcGeneration)
        } else {
          // HA HLS URLs carry their own stream token. Never forward the HA bearer to MediaPlayer.
          _cameraHlsSource.value = CameraHlsSource(url, occurredAt)
        }
        return
      }
      if (id == pendingWebRtcConfigId) {
        pendingWebRtcConfigId = null
        if (root["success"]?.jsonPrimitive?.content != "true") {
          reportWebRtcFailure(webRtcGeneration)
          return
        }
        val renderer = webRtcRenderer ?: return
        val session = webRtc ?: return
        session.attach(renderer, parseIceServers(root), parseWebRtcDataChannelName(root))
        return
      }
      if (id == pendingWebRtcOfferId) {
        pendingWebRtcOfferId = null
        if (root["success"]?.jsonPrimitive?.content != "true") reportWebRtcFailure(webRtcGeneration)
      }
    }
  }

  private fun handleWebRtcEvent(root: JsonObject) {
    val event = root["event"]?.jsonObject ?: return
    when (event["type"]?.jsonPrimitive?.content) {
      "session" -> event["session_id"]?.jsonPrimitive?.contentOrNull?.let { sessionId ->
        synchronized(webRtcLock) {
          if (webRtcSessionId == null) {
            webRtcSessionId = sessionId
            webRtc?.setSession(sessionId)
          }
        }
      }
      "answer" -> event["answer"]?.jsonPrimitive?.contentOrNull?.let { answer ->
        if (isCurrentWebRtcEvent(event)) webRtc?.acceptAnswer(answer)
      }
      "candidate" -> event["candidate"]?.jsonObject?.let { candidate ->
        val parsed = parseHomeIceCandidate(candidate) ?: return@let
        if (isCurrentWebRtcEvent(event)) webRtc?.addCandidate(parsed)
      }
      "error" -> if (isCurrentWebRtcEvent(event)) reportWebRtcFailure()
    }
  }

  private fun isCurrentWebRtcEvent(event: JsonObject): Boolean {
    val eventSessionId = event["session_id"]?.jsonPrimitive?.contentOrNull
    return synchronized(webRtcLock) { webRtc != null && (eventSessionId == null || eventSessionId == webRtcSessionId) }
  }

  private fun scheduleReconnect(owner: WebSocket, generation: Long, message: String?) {
    if (socket !== owner || reconnectJob?.isActive == true) return
    if (configuration() == null) return
    reportWebRtcFailure()
    closeWebRtc()
    val invalidated =
      liveFactsGeneration.invalidateIfCurrent(generation) {
        socket = null
        clearLiveFacts(HomeConnectionState.Error, message ?: "Home Assistant disconnected")
      }
    if (!invalidated) return
    val waitMillis = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
    reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
    reconnectJob = scope.launch { delay(waitMillis); connectWebSocket() }
  }

  private fun markAuthenticationFailed(generation: Long) {
    liveFactsGeneration.invalidateIfCurrent(generation) {
      socket = null
      clearLiveFacts(HomeConnectionState.Error, "Home Assistant authentication failed")
    }
  }

  /** Called only while [liveFactsGeneration] holds its lock. */
  private fun clearLiveFacts(connection: HomeConnectionState, error: String) {
    _homeRoomChanges.value = emptyList()
    homeChangeTracker.onReconnect(emptyList())
    _dashboard.value = _dashboard.value.copy(
      connection = connection,
      connectionError = error,
      weather = null,
      weatherLoaded = false,
      weatherError = null,
      mediaPlayers = emptyList(),
      mediaLoaded = false,
      mediaError = null,
      homeDevices = emptyList(),
      homeDevicesLoaded = false,
      homeDevicesError = null,
    )
  }

  private fun authenticatedRequest(url: HttpUrl, config: HomeImport): Request {
    check(sameOrigin(config.baseHttpUrl, url.toString()))
    return Request.Builder().url(url).header("Authorization", "Bearer ${config.token}").build()
  }

  /** Fetch only the two dashboard-visible covers; failures leave media facts intact. */
  private fun requestVisibleMediaArtwork(expectedGeneration: Long, config: HomeImport) {
    scope.launch {
      val candidates =
        _dashboard.value.mediaPlayers
          .filter(HomeMediaPlayer::available)
          .sortedWith(compareByDescending(HomeMediaPlayer::isPlaying).thenBy(HomeMediaPlayer::name))
          .take(2)
          .mapNotNull { player -> player.artworkUrl?.let { url -> player.entityId to url } }
      if (candidates.isEmpty()) return@launch
      val loaded = candidates.mapNotNull { (entityId, url) ->
        fetchMediaArtwork(url, config)?.let { bytes -> Triple(entityId, url, bytes) }
      }
      if (loaded.isEmpty()) return@launch
      liveFactsGeneration.publishIfCurrent(expectedGeneration) {
        val current = _dashboard.value
        _dashboard.value = current.copy(
          mediaPlayers = current.mediaPlayers.map { player ->
            loaded.firstOrNull { (entityId, url, _) -> entityId == player.entityId && url == player.artworkUrl }
              ?.let { (_, _, bytes) -> player.copy(artworkBytes = bytes) }
              ?: player
          },
        )
        true
      }
    }
  }

  private fun fetchMediaArtwork(url: String, config: HomeImport): ByteArray? {
    artworkCache.get(url)?.let { return it }
    val target = url.toHttpUrlOrNull() ?: return null
    val sameHomeAssistant = sameOrigin(config.baseHttpUrl, url)
    if (!sameHomeAssistant && target.scheme != "https") return null
    val request = mediaArtworkRequest(target, config.baseHttpUrl, config.token)
    val client = if (sameHomeAssistant) http else publicArtworkHttp
    val bytes = runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        if (response.body.contentType()?.type != "image") return@use null
        readArtworkBody(response.body)
      }
    }.getOrNull() ?: return null
    artworkCache.put(url, bytes)
    return bytes
  }

  companion object {
    private const val IMPORT_FILE_NAME = "jarvis-home-import.json"
    private const val HOME_DEVICES_IMPORT_FILE_NAME = "jarvis-home-devices-import.json"
    private const val CONFIG_KEY = "home-assistant-config"
    private const val CALENDAR_REFRESH_MILLIS = 10 * 60 * 1_000L
    private const val FORECAST_REFRESH_MILLIS = 5 * 60 * 1_000L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val WEB_RTC_TIMEOUT_MILLIS = 15_000L
    private const val PHYSICAL_RING_CAMERA_TIMEOUT_MILLIS = 30_000L
    private const val GO2RTC_INTERCOM_PREFLIGHT_TIMEOUT_MILLIS = 8_000L
    private const val GO2RTC_RING_INTERCOM_SOURCE = "jarvis_ring_intercom"
    // Talkback has no local wakeword while the microphone publishes to the doorbell.
    // It always tears down on close/error/disconnect and also has a hard bound.
    private const val INTERCOM_MAX_DURATION_MILLIS = 30_000L
    // Eufy's registered start service waits for a real video track. A cold
    // S3 start has been observed at 26.7s, so this deadline leaves a bounded
    // margin while the UI remains in its existing Connecting state.
    private const val P2P_PREPARATION_TIMEOUT_MILLIS = 35_000L
    private const val ALEXA_ANNOUNCEMENT_BUFFER_CAPACITY = 8
    private const val AMAZON_NOTICE_BUFFER_CAPACITY = 8
    private const val HOME_LOG_TAG = "JarvisHome"
    @Volatile private var instance: HomeDashboardRepository? = null

    fun get(context: Context): HomeDashboardRepository = instance ?: synchronized(this) {
      instance ?: HomeDashboardRepository(context.applicationContext).also { instance = it }
    }

    private fun encryptedPreferences(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
      context,
      "jarvis.home.secure",
      MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }
}

@kotlinx.serialization.Serializable
private data class HomeImport(
  val baseUrl: String,
  val token: String,
  val calendarEntities: List<String>,
  val doorbellEntity: String,
  val cameraEntity: String? = null,
  val weatherEntity: String = "",
  val mediaEntities: List<String> = DEFAULT_MEDIA_ENTITIES,
  val homeDevices: List<HomeConfiguredDevice> = emptyList(),
) {
  init {
    require(baseUrl.toHttpUrlOrNull() != null && weatherEntity.isNotBlank())
    require(token.isNotBlank() && doorbellEntity.isNotBlank())
    require(mediaEntities.all(String::isNotBlank))
    require(homeDevices.size <= MAX_HOME_DEVICES)
    require(homeDevices.map(HomeConfiguredDevice::entityId).distinct().size == homeDevices.size)
  }
}

/** Explicit dashboard configuration is the only source of room labels and entities shown on Horizon. */
@kotlinx.serialization.Serializable
data class HomeConfiguredDevice(
  val entityId: String,
  val room: String? = null,
) {
  init {
    require(entityId.startsWith("light.") || entityId.startsWith("switch.") || entityId.startsWith("climate."))
    require(room == null || room.isNotBlank())
  }
}

@kotlinx.serialization.Serializable
internal data class HomeDevicesImport(val homeDevices: List<HomeConfiguredDevice>) {
  init { require(homeDevices.size <= MAX_HOME_DEVICES) }
}


private const val MAX_HOME_DEVICES = 32
private const val MAX_HOME_CAMERAS = 64
private val DEFAULT_MEDIA_ENTITIES = emptyList<String>()
private val HomeImport.baseHttpUrl: HttpUrl
  get() = checkNotNull(baseUrl.toHttpUrlOrNull())

private val homeJson = Json { ignoreUnknownKeys = true }

/**
 * Serializes generation invalidation and fact publication without holding a
 * lock across Home Assistant I/O. A stale callback cannot pass its check and
 * publish after a disconnect has advanced the generation.
 */
internal class LiveFactsGenerationGuard {
  private val lock = Any()
  private var generation = 0L

  fun snapshot(): Long = synchronized(lock) { generation }

  fun begin(block: () -> Unit): Long = synchronized(lock) {
    generation += 1L
    block()
    generation
  }

  fun isCurrent(expected: Long): Boolean = synchronized(lock) { generation == expected }

  fun update(block: () -> Unit) {
    synchronized(lock) { block() }
  }

  fun publishIfCurrent(expected: Long, block: () -> Boolean): Boolean = synchronized(lock) {
    if (generation != expected) false else block()
  }

  fun invalidateIfCurrent(expected: Long, block: () -> Unit): Boolean = synchronized(lock) {
    if (generation != expected) return@synchronized false
    generation += 1L
    block()
    true
  }
}

private fun parseImport(raw: String): HomeImport {
  val parsed = homeJson.decodeFromString(HomeImport.serializer(), raw)
  val url = checkNotNull(parsed.baseUrl.toHttpUrlOrNull())
  require(url.scheme == "http" || url.scheme == "https")
  return parsed.copy(baseUrl = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/"))
}

internal fun parseHomeDevicesImport(raw: String): HomeDevicesImport =
  homeJson.decodeFromString(HomeDevicesImport.serializer(), raw)

internal fun parseCalendarEvents(entityId: String, body: ByteArray): List<HomeCalendarEvent> =
  homeJson.parseToJsonElement(body.decodeToString()).jsonArray
    .mapNotNull { element ->
      val item = element.jsonObject
      val start = parseCalendarTimestamp(item["start"]) ?: return@mapNotNull null
      HomeCalendarEvent(
        entityId = entityId,
        summary = item["summary"]?.jsonPrimitive?.content?.ifBlank { "Termin" } ?: "Termin",
        start = start,
        end = parseCalendarTimestamp(item["end"]),
      )
    }

/** Projects only stable, already observed HA state for the non-interactive Horizon home tile. */
internal fun parseHomeDeviceState(state: JsonObject, configured: HomeConfiguredDevice): HomeDeviceState {
  val domain = configured.entityId.substringBefore('.')
  val kind = when (domain) {
    "light" -> HomeDeviceKind.Light
    "switch" -> HomeDeviceKind.Switch
    "climate" -> HomeDeviceKind.Climate
    else -> error("unsupported configured Home Assistant domain")
  }
  val attributes = state["attributes"] as? JsonObject
  val rawState = (state["state"] as? JsonPrimitive)?.contentOrNull.orEmpty()
  val available = !rawState.equals("unavailable", ignoreCase = true) && !rawState.equals("unknown", ignoreCase = true)
  val level = (attributes?.get("brightness") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
    ?.takeIf { it in 0.0..255.0 }?.let { (it / 255.0 * 100.0).roundToInt() }
  val currentTemperature = (attributes?.get("current_temperature") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
  val targetTemperature = (attributes?.get("temperature") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
  val unit = (attributes?.get("temperature_unit") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 8 }
  val friendlyName = (attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
  return HomeDeviceState(
    entityId = configured.entityId,
    kind = kind,
    name = friendlyName ?: configured.entityId,
    room = configured.room,
    state = rawState.take(40),
    available = available,
    levelPercent = if (kind == HomeDeviceKind.Light) level else null,
    currentTemperature = if (kind == HomeDeviceKind.Climate) currentTemperature else null,
    targetTemperature = if (kind == HomeDeviceKind.Climate) targetTemperature else null,
    temperatureUnit = if (kind == HomeDeviceKind.Climate) unit else null,
  )
}

/** Projects the HA camera domain into a bounded, dynamically discovered voice catalog. */
internal fun parseHomeCameraCatalog(states: List<JsonElement>): List<HomeCamera> =
  states.mapNotNull { element ->
    val state = element as? JsonObject ?: return@mapNotNull null
    val entityId = (state["entity_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.startsWith("camera.") } ?: return@mapNotNull null
    val attributes = state["attributes"] as? JsonObject
    val name = (attributes?.get("friendly_name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(96) ?: entityId
    val rawState = (state["state"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val supported = (attributes?.get("supported_features") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
    val aliases = cameraAliases(entityId, name)
    val startButton = p2pStartButtonForCamera(entityId, states)
    val providerEntityId = p2pProviderEntityIdForCamera(entityId)?.takeIf { candidate ->
      states.asSequence().mapNotNull { it as? JsonObject }
        .any { (it["entity_id"] as? JsonPrimitive)?.contentOrNull == candidate }
    }
    val hasLoopbackPublisher = providerEntityId == null && hasP2pLoopbackPublisherForCamera(entityId, states)
    HomeCamera(
      entityId = entityId,
      name = name,
      available = !rawState.equals("unavailable", ignoreCase = true) && !rawState.equals("unknown", ignoreCase = true),
      // Home Assistant's CameraEntityFeature.STREAM bit is 2.  A missing feature value remains
      // non-selectable instead of assuming a camera proxy can provide a live WebRTC stream.
      supportsLive = supported and 2L != 0L,
      aliases = aliases,
      p2pStartButton = startButton,
      p2pProviderEntityId = providerEntityId,
      p2pState = if (startButton == null) EufyP2pState.NeedsStart else eufyP2pState(
        cameraState = rawState,
        providerState = providerEntityId?.let { p2pProviderStateForCamera(entityId, states) },
        hasLoopbackPublisher = hasLoopbackPublisher,
      ),
    )
  }.sortedWith(compareBy(HomeCamera::name, HomeCamera::entityId)).take(MAX_HOME_CAMERAS)

/**
 * A P2P start is opt-in only when the current HA catalog advertises the exact
 * button derived from the camera entity and the integration itself labels it
 * as a start command. `unknown` is valid for HA button entities; only an
 * explicit unavailable state rejects the command.
 */
internal fun p2pStartButtonForCamera(cameraEntityId: String, states: List<JsonElement>): String? {
  val slug = cameraEntityId.removePrefix("camera.")
  if (slug.isBlank() || cameraEntityId == slug) return null
  val expectedEntityId = "button.${slug}_start_p2p_stream"
  val state = states.asSequence().mapNotNull { it as? JsonObject }
    .firstOrNull { (it["entity_id"] as? JsonPrimitive)?.contentOrNull == expectedEntityId } ?: return null
  val rawState = (state["state"] as? JsonPrimitive)?.contentOrNull.orEmpty()
  if (rawState.equals("unavailable", ignoreCase = true)) return null
  val name = ((state["attributes"] as? JsonObject)?.get("friendly_name") as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
  return expectedEntityId.takeIf { name.endsWith("Start P2P Stream", ignoreCase = true) }
}

/** Reads only the exact companion sensor emitted by the Eufy bridge. */
internal fun p2pProviderStateForCamera(cameraEntityId: String, states: List<JsonElement>): String? {
  val expectedEntityId = p2pProviderEntityIdForCamera(cameraEntityId) ?: return null
  val state = states.asSequence().mapNotNull { it as? JsonObject }
    .firstOrNull { (it["entity_id"] as? JsonPrimitive)?.contentOrNull == expectedEntityId } ?: return null
  return (state["state"] as? JsonPrimitive)?.contentOrNull
}

internal fun p2pProviderEntityIdForCamera(cameraEntityId: String): String? {
  val slug = cameraEntityId.removePrefix("camera.")
  return if (slug.isBlank() || cameraEntityId == slug) null else "sensor.${slug}_stream_provider"
}

/** Legacy fallback for bridges that expose only `sensor.<slug>_stream_url`. */
internal fun hasP2pLoopbackPublisherForCamera(cameraEntityId: String, states: List<JsonElement>): Boolean {
  val slug = cameraEntityId.removePrefix("camera.")
  if (slug.isBlank() || cameraEntityId == slug) return false
  val expectedEntityId = "sensor.${slug}_stream_url"
  val state = states.asSequence().mapNotNull { it as? JsonObject }
    .firstOrNull { (it["entity_id"] as? JsonPrimitive)?.contentOrNull == expectedEntityId } ?: return false
  return isEufyLoopbackPublisherUrl((state["state"] as? JsonPrimitive)?.contentOrNull)
}

private fun configuredCamera(entityId: String): HomeCamera = HomeCamera(
  entityId = entityId,
  name = entityId,
  available = true,
  supportsLive = true,
  aliases = cameraAliases(entityId, entityId),
)

private fun cameraAliases(entityId: String, name: String): Set<String> {
  val values = linkedSetOf<String>()
  fun add(value: String) = normalizeHomeCameraAlias(value).takeIf(String::isNotEmpty)?.let(values::add)
  add(entityId)
  add(entityId.removePrefix("camera."))
  add(name)
  // Individual HA-derived name/entity words cover spoken names such as "Eufy" while
  // selectHomeCamera still rejects the request when more than one camera shares that word.
  (entityId.removePrefix("camera.") + " " + name)
    .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
    .split(Regex("[^A-Za-z0-9ÄÖÜäöüß]+"))
    .map(::normalizeHomeCameraAlias)
    .filter { it.length >= 3 }
    .forEach { values.add(it) }
  return values
}

internal fun parseHomeWeather(state: JsonObject, entityId: String? = null): HomeWeather {
  val attributes = state["attributes"] as? JsonObject
  return HomeWeather(
    entityId = entityId ?: state["entity_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    condition = state["state"]?.jsonPrimitive?.contentOrNull?.ifBlank { "unbekannt" } ?: "unbekannt",
    temperatureCelsius = attributes?.get("temperature")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
    humidityPercent = attributes?.get("humidity")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
    temperatureUnit = (attributes?.get("temperature_unit") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 8 },
    windSpeed = (attributes?.get("wind_speed") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
    windBearing = (attributes?.get("wind_bearing") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 12 },
    windSpeedUnit = (attributes?.get("wind_speed_unit") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 12 },
    precipitation = (attributes?.get("precipitation") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
    uvIndex = (attributes?.get("uv_index") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
  )
}

private fun HomeWeather.withForecastResult(
  result: Result<List<HomeWeatherForecast>>?,
  previous: HomeWeather?,
): HomeWeather = when (result) {
  null -> copy(
    hourlyForecast = previous?.hourlyForecast.orEmpty(),
    forecastLoaded = previous?.forecastLoaded == true,
    forecastError = previous?.forecastError,
  )
  else -> result.fold(
    onSuccess = { forecast -> copy(hourlyForecast = forecast, forecastLoaded = true, forecastError = null) },
    onFailure = { error -> copy(hourlyForecast = previous?.hourlyForecast.orEmpty(), forecastLoaded = previous?.forecastLoaded == true, forecastError = error.message ?: "hourly forecast unavailable") },
  )
}

/** Strictly decodes HA's `weather.get_forecasts?return_response` envelope. */
internal fun parseHourlyForecastResponse(response: JsonObject, entityId: String): List<HomeWeatherForecast> {
  val forecast = ((response["service_response"] as? JsonObject)?.get(entityId) as? JsonObject)
    ?.get("forecast") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
  return forecast.mapNotNull { element ->
    val item = element as? JsonObject ?: return@mapNotNull null
    val timestamp = (item["datetime"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    val at = runCatching { Instant.parse(timestamp) }.recoverCatching { OffsetDateTime.parse(timestamp).toInstant() }.getOrNull()
      ?: return@mapNotNull null
    val probability = (item["precipitation_probability"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 0..100 }
    HomeWeatherForecast(
      at = at,
      condition = ((item["condition"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } ?: "unknown").take(40),
      temperature = (item["temperature"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
      precipitationProbability = probability,
    )
  }.take(12)
}

internal fun parseHomeMediaPlayer(
  state: JsonObject,
  entityId: String? = null,
  baseUrl: HttpUrl? = null,
): HomeMediaPlayer {
  val attributes = state["attributes"] as? JsonObject
  val rawState = state["state"]?.jsonPrimitive?.contentOrNull?.ifBlank { "unknown" } ?: "unknown"
  return HomeMediaPlayer(
    entityId = entityId ?: state["entity_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    name = attributes?.get("friendly_name")?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Mediengerät",
    state = rawState,
    title = attributes?.get("media_title")?.jsonPrimitive?.contentOrNull?.ifBlank { null },
    artist = attributes?.get("media_artist")?.jsonPrimitive?.contentOrNull?.ifBlank { null },
    source = attributes?.get("source")?.jsonPrimitive?.contentOrNull?.ifBlank { null },
    artworkUrl = homeMediaArtworkUrl(baseUrl, (attributes?.get("entity_picture") as? JsonPrimitive)?.contentOrNull),
    positionSeconds = parseMediaSeconds(attributes?.get("media_position")),
    durationSeconds = parseMediaSeconds(attributes?.get("media_duration")),
    positionUpdatedAtEpochMs = (attributes?.get("media_position_updated_at") as? JsonPrimitive)?.contentOrNull
      ?.let { timestamp -> runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull() },
    available = !rawState.equals("unavailable", ignoreCase = true),
  )
}

/**
 * HA-relative artwork remains on the configured origin. External covers require
 * HTTPS and are deliberately fetched without the HA bearer credential.
 */
internal fun homeMediaArtworkUrl(baseUrl: HttpUrl?, entityPicture: String?): String? {
  val base = baseUrl ?: return null
  val raw = entityPicture?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val candidate = raw.toHttpUrlOrNull() ?: base.resolve(raw) ?: return null
  return candidate.takeIf { sameOrigin(base, it.toString()) || it.scheme == "https" }?.toString()
}

private fun parseMediaSeconds(value: JsonElement?): Double? =
  (value as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

/** Same-origin media receives the HA credential; external HTTPS never does. */
internal fun mediaArtworkRequest(url: HttpUrl, homeBaseUrl: HttpUrl, bearerToken: String): Request {
  val builder = Request.Builder().url(url).header("Accept", "image/*")
  if (sameOrigin(homeBaseUrl, url.toString())) builder.header("Authorization", "Bearer $bearerToken")
  else require(url.scheme == "https")
  return builder.build()
}

private fun readArtworkBody(body: okhttp3.ResponseBody): ByteArray? {
  if (body.contentLength() > ARTWORK_MAX_BYTES) return null
  val buffer = Buffer()
  val source = body.source()
  val limit = ARTWORK_MAX_BYTES.toLong() + 1L
  while (buffer.size < limit) {
    val remaining = limit - buffer.size
    if (source.read(buffer, remaining) == -1L) break
  }
  return buffer.takeIf { it.size <= ARTWORK_MAX_BYTES }?.readByteArray()
}

internal fun sameOrigin(baseUrl: HttpUrl, candidate: String): Boolean {
  val candidateUrl = candidate.toHttpUrlOrNull() ?: return false
  return baseUrl.scheme == candidateUrl.scheme && baseUrl.host == candidateUrl.host && baseUrl.port == candidateUrl.port
}

/** HA sends one or more TURN/STUN URLs plus optional short-lived TURN credentials. */
internal fun parseIceServers(message: JsonObject): List<PeerConnection.IceServer> =
  message["result"]?.jsonObject?.get("configuration")?.jsonObject?.get("iceServers")?.jsonArray.orEmpty()
    .flatMap { element ->
      val server = element as? JsonObject ?: return@flatMap emptyList()
      val urls = when (val value = server["urls"]) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        else -> (value as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
      }
      urls.map { url ->
        PeerConnection.IceServer.builder(url).apply {
          server["username"]?.jsonPrimitive?.contentOrNull?.let(::setUsername)
          server["credential"]?.jsonPrimitive?.contentOrNull?.let(::setPassword)
        }.createIceServer()
      }
    }

/** The optional HA-provided label must be offered verbatim; inventing one changes the SDP contract. */
internal fun parseWebRtcDataChannelName(message: JsonObject): String? =
  message["result"]?.jsonObject?.get("dataChannel")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

internal fun shouldPresentRing(newTime: Instant, oldTime: Instant?, subscriptionStartedAt: Instant): Boolean =
  newTime > subscriptionStartedAt && newTime != oldTime

internal fun isDoorbellRingState(state: JsonObject): Boolean =
  state["attributes"]?.jsonObject?.get("event_type")?.jsonPrimitive?.content == "ring"

internal fun parseCalendarTimestamp(value: JsonElement?): HomeCalendarTime? {
  when (value) {
    is JsonObject -> {
      value["dateTime"]?.jsonPrimitive?.contentOrNull?.toTimestampInstantOrNull()?.let {
        return HomeCalendarTime(instant = it)
      }
      value["date"]?.jsonPrimitive?.contentOrNull?.toAllDayCalendarTimeOrNull()?.let { return it }
    }
    is JsonPrimitive -> {
      value.contentOrNull?.toAllDayCalendarTimeOrNull()?.let { return it }
      value.contentOrNull?.toTimestampInstantOrNull()?.let { return HomeCalendarTime(instant = it) }
    }
    else -> Unit
  }
  return null
}

internal fun stateTimestamp(state: JsonObject): Instant? = (state["state"] as? JsonPrimitive)?.contentOrNull?.toInstantOrNull()

private fun String.toInstantOrNull(): Instant? = runCatching {
  Instant.parse(this)
}.recoverCatching {
  OffsetDateTime.parse(this).toInstant()
}.recoverCatching {
  LocalDate.parse(this).atStartOfDay(ZoneId.systemDefault()).toInstant()
}.getOrNull()

private fun String.toTimestampInstantOrNull(): Instant? = runCatching {
  Instant.parse(this)
}.recoverCatching {
  OffsetDateTime.parse(this).toInstant()
}.getOrNull()

private fun String.toAllDayCalendarTimeOrNull(): HomeCalendarTime? = runCatching { LocalDate.parse(this) }
  .getOrNull()
  ?.let { allDayDate ->
    HomeCalendarTime(
      instant = allDayDate.atStartOfDay(ZoneId.systemDefault()).toInstant(),
      allDayDate = allDayDate,
    )
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
