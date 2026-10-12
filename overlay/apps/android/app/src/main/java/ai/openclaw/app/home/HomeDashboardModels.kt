package ai.openclaw.app.home

import java.time.Instant
import java.util.LinkedHashSet
import java.time.LocalDate
import java.util.Locale

/** HA date values are all-day calendar facts, while dateTime values are timed instants. */
data class HomeCalendarTime(
  val instant: Instant,
  val allDayDate: LocalDate? = null,
) {
  val isAllDay: Boolean get() = allDayDate != null
}

data class HomeCalendarEvent(
  val entityId: String,
  val summary: String,
  val start: HomeCalendarTime,
  val end: HomeCalendarTime?,
) {
  val startsAt: Instant get() = start.instant
  val endsAt: Instant? get() = end?.instant
}

/** A deliberately small, observed weather fact from Home Assistant. */
data class HomeWeather(
  val entityId: String,
  val condition: String,
  val temperatureCelsius: Double? = null,
  val humidityPercent: Int? = null,
  /** Preserved in Home Assistant's native unit; the renderer must label it rather than convert it. */
  val temperatureUnit: String? = null,
  val windSpeed: Double? = null,
  val windBearing: String? = null,
  val windSpeedUnit: String? = null,
  val precipitation: Double? = null,
  val uvIndex: Double? = null,
  val hourlyForecast: List<HomeWeatherForecast> = emptyList(),
  val forecastLoaded: Boolean = false,
  val forecastError: String? = null,
)

/** Bounded hourly forecast facts returned by Home Assistant's weather service response. */
data class HomeWeatherForecast(
  val at: Instant,
  val condition: String,
  val temperature: Double? = null,
  val precipitationProbability: Int? = null,
)

/**
 * The observed state of one explicitly configured HA media player. Playback is
 * intentionally derived only from its current state; no client-side guess is
 * made about where audio is actually audible.
 */
data class HomeMediaPlayer(
  val entityId: String,
  val name: String,
  val state: String,
  val title: String? = null,
  val artist: String? = null,
  val source: String? = null,
  /** HA-relative or HTTPS artwork URL. Never carries an access token. */
  val artworkUrl: String? = null,
  /** Bounded artwork bytes fetched by the repository, never a credential-bearing URL request. */
  val artworkBytes: ByteArray? = null,
  /** Current HA playback position in seconds, including fractional positions. */
  val positionSeconds: Double? = null,
  /** Current HA media duration in seconds, including fractional durations. */
  val durationSeconds: Double? = null,
  /** HA timestamp that anchors [positionSeconds], in epoch milliseconds. */
  val positionUpdatedAtEpochMs: Long? = null,
  val available: Boolean = true,
) {
  val isPlaying: Boolean get() = state.equals("playing", ignoreCase = true)
}

/** A current fact from one explicitly configured Home Assistant entity. No room or state is inferred from its id. */
data class HomeDeviceState(
  val entityId: String,
  val kind: HomeDeviceKind,
  val name: String,
  val room: String? = null,
  val state: String,
  val available: Boolean,
  val levelPercent: Int? = null,
  val currentTemperature: Double? = null,
  val targetTemperature: Double? = null,
  val temperatureUnit: String? = null,
)

enum class HomeDeviceKind { Light, Switch, Climate }

enum class HomeConnectionState {
  MissingConfiguration,
  Connecting,
  Connected,
  Error,
}

data class HomeDashboardState(
  val connection: HomeConnectionState = HomeConnectionState.MissingConfiguration,
  val calendarEvents: List<HomeCalendarEvent> = emptyList(),
  val calendarLoaded: Boolean = false,
  val calendarError: String? = null,
  val weather: HomeWeather? = null,
  val weatherLoaded: Boolean = false,
  val weatherError: String? = null,
  val mediaPlayers: List<HomeMediaPlayer> = emptyList(),
  val mediaLoaded: Boolean = false,
  val mediaError: String? = null,
  val homeDevices: List<HomeDeviceState> = emptyList(),
  val homeDevicesLoaded: Boolean = false,
  val homeDevicesError: String? = null,
  val connectionError: String? = null,
)

data class RingNotice(
  val occurredAt: Instant,
  val manual: Boolean = false,
  val snapshot: ByteArray? = null,
  val cameraFirstFrame: Boolean = false,
  val cameraError: String? = null,
)

/** Admits a physical doorbell transition once, including across a local dismissal. */
internal class RingNoticeAdmission(private val maximumRememberedTimes: Int = 128) {
  private val admittedTimes = LinkedHashSet<Instant>()

  init {
    require(maximumRememberedTimes > 0)
  }

  @Synchronized
  fun admit(newTime: Instant, oldTime: Instant?, subscriptionStartedAt: Instant): Boolean {
    if (!shouldPresentRing(newTime, oldTime, subscriptionStartedAt) || !admittedTimes.add(newTime)) return false
    while (admittedTimes.size > maximumRememberedTimes) admittedTimes.remove(admittedTimes.first())
    return true
  }
}

/**
 * A camera that Home Assistant currently exposes.  The catalog is discovered at
 * request time, so adding a camera in Home Assistant does not require another
 * Android release.  Aliases are derived only from the entity id and HA's own
 * display name; room names are never guessed by the client.
 */
data class HomeCamera(
  val entityId: String,
  val name: String,
  val available: Boolean,
  val supportsLive: Boolean,
  val aliases: Set<String>,
  /**
   * An optional Home-Assistant-advertised command that asks the Eufy bridge to
   * create its P2P publisher before HA requests HLS. It is never guessed from
   * a display name alone.
   */
  val p2pStartButton: String? = null,
  /** Present only when this fresh HA catalog exposes the exact provider sensor. */
  val p2pProviderEntityId: String? = null,
  /**
   * P2P readiness is trusted only when the exact companion provider sensor
   * says P2P. A generic camera `streaming` state can still be RTSP.
   */
  val p2pState: EufyP2pState = EufyP2pState.NeedsStart,
)

sealed interface HomeCameraSelection {
  data class Selected(val camera: HomeCamera) : HomeCameraSelection
  data class Ambiguous(val cameras: List<HomeCamera>) : HomeCameraSelection
  data class Unavailable(val camera: HomeCamera) : HomeCameraSelection
  data object Missing : HomeCameraSelection
}

/** Resolves only a unique, pre-derived alias. Partial or fuzzy room matching is intentionally absent. */
internal fun selectHomeCamera(cameras: List<HomeCamera>, requested: String): HomeCameraSelection {
  val normalized = normalizeHomeCameraAlias(requested)
  if (normalized.isEmpty()) return HomeCameraSelection.Missing
  val matches = cameras.filter { normalized in it.aliases }
  if (matches.size != 1) return if (matches.isEmpty()) HomeCameraSelection.Missing else HomeCameraSelection.Ambiguous(matches)
  return matches.single().let { if (it.available) HomeCameraSelection.Selected(it) else HomeCameraSelection.Unavailable(it) }
}

internal fun normalizeHomeCameraAlias(value: String): String =
  value
    .lowercase(Locale.ROOT)
    .replace('ä', 'a')
    .replace('ö', 'o')
    .replace('ü', 'u')
    .replace('ß', 's')
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

enum class DoorCameraAction { Open, Fullscreen, Minimize, Close }

enum class DoorCameraPresentation { PiP, Fullscreen }

enum class DoorCameraLifecycle { Requested, Connecting, Live, Snapshot, Failed, Closed }

/** Local display fact for UI and voice actions; Live is published only after the renderer's first frame. */
data class DoorCameraDisplayState(
  val presentation: DoorCameraPresentation = DoorCameraPresentation.PiP,
  val lifecycle: DoorCameraLifecycle = DoorCameraLifecycle.Closed,
  val manual: Boolean = false,
  /** The exact HA entity selected for the current renderer, never an inferred fallback. */
  val camera: HomeCamera? = null,
  /** A user minimising the camera must win over the responsive landscape default. */
  val automaticLandscapeFullscreen: Boolean = true,
)

/** A request watchdog is specific to one source event and can never fail a newer/live/closed view. */
internal fun isCurrentRequestedCamera(
  expectedNoticeAt: Instant,
  notice: RingNotice?,
  display: DoorCameraDisplayState,
): Boolean = notice?.occurredAt == expectedNoticeAt && display.lifecycle == DoorCameraLifecycle.Requested

/** A physical-ring deadline may close only the event that armed it, never a manual or replacement view. */
internal fun isCurrentPhysicalRing(
  expectedNoticeAt: Instant,
  scheduledNoticeAt: Instant?,
  notice: RingNotice?,
): Boolean = scheduledNoticeAt == expectedNoticeAt && notice?.manual == false && notice.occurredAt == expectedNoticeAt

internal fun doorCameraActionState(
  current: DoorCameraDisplayState,
  action: DoorCameraAction,
): DoorCameraDisplayState = when (action) {
  DoorCameraAction.Open -> DoorCameraDisplayState(
    presentation = DoorCameraPresentation.PiP,
    lifecycle = DoorCameraLifecycle.Requested,
    manual = true,
    camera = current.camera,
    automaticLandscapeFullscreen = true,
  )
  DoorCameraAction.Fullscreen -> current.copy(presentation = DoorCameraPresentation.Fullscreen)
  DoorCameraAction.Minimize -> current.copy(
    presentation = DoorCameraPresentation.PiP,
    automaticLandscapeFullscreen = false,
  )
  DoorCameraAction.Close -> current.copy(lifecycle = DoorCameraLifecycle.Closed)
}
