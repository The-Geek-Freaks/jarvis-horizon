package ai.openclaw.app.ui.horizon

import ai.openclaw.app.JarvisHealthComponent
import ai.openclaw.app.JarvisHealthStatus
import ai.openclaw.app.home.HomeConnectionState
import ai.openclaw.app.home.HomeDashboardState
import ai.openclaw.app.home.HorizonTile
import ai.openclaw.app.home.HorizonPresentationState
import ai.openclaw.app.home.HorizonTileType
import ai.openclaw.app.home.AlexaAnnouncement
import ai.openclaw.app.home.HomeMediaPlayer
import ai.openclaw.app.home.HorizonHomeRoomChange
import ai.openclaw.app.home.horizonHomeRoomValues
import ai.openclaw.app.home.RingCallSourceState
import ai.openclaw.app.home.RingNotice
import ai.openclaw.app.home.DoorCameraDisplayState
import ai.openclaw.app.home.ringCallPresentation
import ai.openclaw.app.home.timers.JarvisSchedule
import ai.openclaw.app.home.timers.JarvisScheduleKind
import ai.openclaw.app.home.timers.JarvisScheduleState
import ai.openclaw.app.voice.JarvisToolExecution
import ai.openclaw.app.voice.JarvisToolPhase
import ai.openclaw.app.voice.VoiceConversationEntry
import ai.openclaw.app.voice.VoiceConversationRole
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Base64
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Maps observed native state into the deliberately data-only Horizon contract. */
internal fun horizonSnapshotFor(
  phase: Any,
  night: Boolean,
  wakeEnabled: Boolean,
  microphoneMuted: Boolean,
  gatewayConnected: Boolean,
  gatewayStatus: String,
  now: Date,
  dashboard: HomeDashboardState,
  conversation: List<VoiceConversationEntry>,
  health: List<JarvisHealthComponent>,
  tile: JsonObject? = null,
  modeOverride: String? = null,
): HorizonSnapshot {
  val locale = Locale.getDefault()
  val zone = ZoneId.systemDefault()
  val instantNow = Instant.now()
  val upcoming = dashboard.calendarEvents.firstOrNull { event ->
    event.endsAt?.isAfter(instantNow) ?: event.startsAt.isAfter(instantNow)
  }
  val clock = Calendar.getInstance().apply { time = now }
  val media = dashboard.mediaPlayers.firstOrNull { it.isPlaying } ?: dashboard.mediaPlayers.firstOrNull { it.available }
  val caption = conversation.lastOrNull()?.takeIf { it.text.isNotBlank() }
  return HorizonSnapshot(
    mode = modeOverride ?: horizonMode(phase, wakeEnabled, locale),
    night = night,
    muted = microphoneMuted,
    offline = !gatewayConnected,
    clockTime = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(now),
    clockDate = now.toInstant().atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", locale)),
    clockSeconds = clock.get(Calendar.SECOND),
    weatherTemperature = dashboard.weather?.temperatureCelsius?.let { "${formatTemperature(it)}${dashboard.weather.temperatureUnit ?: "°"}" },
    weatherSummary = dashboard.weather?.condition?.horizonWeatherLabel(),
    weatherLocation = dashboard.weather?.let { "Zuhause" },
    weatherSource = dashboard.weather?.takeIf { dashboard.weatherLoaded }?.let { "Home Assistant" },
    weatherWind = dashboard.weather?.windSpeed?.let { speed -> listOf(formatHorizonDecimal(speed), dashboard.weather.windSpeedUnit, dashboard.weather.windBearing).filterNotNull().joinToString(" ") },
    weatherHumidity = dashboard.weather?.humidityPercent?.let { "$it %" },
    weatherRain = dashboard.weather?.precipitation?.let { "${formatHorizonDecimal(it)} mm" },
    weatherUv = dashboard.weather?.uvIndex?.let(::formatHorizonDecimal),
    weatherForecast = kotlinx.serialization.json.buildJsonArray {
      dashboard.weather?.hourlyForecast?.take(12)?.forEach { hour ->
        add(buildJsonObject {
          put("time", hour.at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)))
          put("temperature", hour.temperature?.let(::JsonPrimitive) ?: JsonNull)
          put("summary", hour.condition.horizonWeatherLabel())
          put("rain", hour.precipitationProbability?.let(::JsonPrimitive) ?: JsonNull)
        })
      }
    },
    nextEventTime = upcoming?.let { if (it.start.isAllDay) "Heute" else it.startsAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", locale)) },
    nextEventTitle = upcoming?.summary,
    nextEventMeta = upcoming?.let { if (it.start.isAllDay) "Ganztägig" else "Kalender" },
    captionId = caption?.id,
    captionSpeaker = caption?.role?.let { if (it == VoiceConversationRole.User) "user" else "jarvis" },
    captionText = caption?.text?.trim()?.takeLast(2_400),
    captionStreaming = caption?.isStreaming == true,
    services = horizonServices(gatewayConnected, gatewayStatus, dashboard, health, now.time),
    media = media?.let {
      HorizonMedia(
        playing = it.isPlaying,
        title = it.title,
        artist = it.artist,
        position = it.positionSeconds,
        duration = it.durationSeconds,
        source = it.source ?: it.name,
        artworkDataUrl = it.horizonArtworkDataUrl(),
      )
    },
    tile = tile,
  )
}

internal fun horizonMode(phase: Any, wakeEnabled: Boolean, locale: Locale): String =
  when (phase.toString().substringAfterLast('.').lowercase(locale)) {
    "wakelistening" -> "idle"
    "listening" -> "listening"
    "thinking" -> "thinking"
    "speaking" -> "speaking"
    else -> if (wakeEnabled) "idle" else "idle"
  }

internal fun horizonPresentationMode(
  phase: Any,
  ringAlert: Boolean,
  alertActive: Boolean,
  toolRunning: Boolean,
  announcementActive: Boolean,
  ringConnected: Boolean = false,
): String {
  val voice = horizonMode(phase, wakeEnabled = true, Locale.GERMANY)
  return when {
    voice == "speaking" || voice == "listening" -> voice
    ringConnected -> "call"
    ringAlert -> "alert"
    alertActive -> "notify"
    toolRunning -> "working"
    announcementActive -> "notify"
    else -> voice
  }
}

private fun String.horizonWeatherLabel(): String =
  when (lowercase(Locale.ROOT)) {
    "sunny" -> "Sonnig"
    "clear-night" -> "Klar"
    "partlycloudy" -> "Wolkig"
    "cloudy" -> "Bedeckt"
    "rainy", "pouring" -> "Regen"
    "snowy" -> "Schnee"
    "fog" -> "Nebel"
    else -> this
  }

/** Keeps the exact node-supplied data shape while adding only display metadata. */
internal fun HorizonTile.toHorizonJson(nowMs: Long): JsonObject = buildJsonObject {
  put("id", id)
  put("type", type.name.lowercase())
  put("data", data)
  put("ttl", expiresAtMs?.minus(nowMs)?.coerceAtLeast(0)?.let(::JsonPrimitive) ?: JsonNull)
  put("expiresAtMs", expiresAtMs?.let(::JsonPrimitive) ?: JsonNull)
  put("sticky", sticky)
}

/** Execution completion time is owned by the repository, so snapshots cannot renew its TTL. */
internal fun HorizonPresentationState.toHorizonJson(nowMs: Long): JsonObject? {
  tile?.let { return it.toHorizonJson(nowMs) }
  val execution = executions.lastOrNull { it.exit == null }
    ?: executions.lastOrNull { exit ->
      val completed = exit.exit ?: return@lastOrNull false
      completed.code != 0 || nowMs - completed.completedAtMs <= 9_000
    }
    ?: return null
  return buildJsonObject {
    put("id", execution.id)
    put("type", "terminal")
    val exit = execution.exit
    put("sticky", exit?.code?.let { it != 0 } ?: true)
    if (exit?.code == 0) {
      put("ttl", (9_000 - (nowMs - exit.completedAtMs)).coerceAtLeast(0))
      put("expiresAtMs", exit.completedAtMs + 9_000)
    } else {
      put("ttl", JsonNull)
      put("expiresAtMs", JsonNull)
    }
    put("data", buildJsonObject {
      put("title", execution.title)
      put("exit", exit?.let { "EXIT ${it.code}" } ?: "LÄUFT")
      put("failed", exit?.code?.let { it != 0 } ?: false)
      exit?.elapsedMs?.takeIf { it >= 0 }?.let { put("durationMs", it) }
      put("steps", kotlinx.serialization.json.buildJsonArray {
        execution.steps.forEach { add(JsonPrimitive(it)) }
      })
      execution.lines.lastOrNull { it.progress != null }?.progress?.let { put("progress", it) }
      put("lines", kotlinx.serialization.json.buildJsonArray {
        execution.lines.forEach { line ->
          add(buildJsonObject {
            put("kind", when (line.kind) {
              ai.openclaw.app.home.HorizonExecutionLineKind.Command -> "cmd"
              ai.openclaw.app.home.HorizonExecutionLineKind.Output -> "out"
              ai.openclaw.app.home.HorizonExecutionLineKind.Error -> "err"
              ai.openclaw.app.home.HorizonExecutionLineKind.Warning -> "warn"
              else -> line.kind.name.lowercase()
            })
            put("text", line.text)
            line.step?.let { put("step", it) }
            line.progress?.let { put("progress", it) }
          })
        }
      })
    })
  }
}

/** Existing home facts take precedence over an optional node-provided presentation tile. */
internal fun horizonAutomaticTile(
  presentation: HorizonPresentationState,
  ringActive: Boolean,
  ringAlert: Boolean,
  alert: JarvisSchedule?,
  schedules: List<JarvisSchedule>,
  activeTools: List<JarvisToolExecution>,
  announcement: AlexaAnnouncement?,
  media: HomeMediaPlayer?,
  dashboard: HomeDashboardState,
  nowMs: Long,
  cameraName: String? = null,
  ringCall: RingCallSourceState = RingCallSourceState(),
  ringNotice: RingNotice? = null,
  cameraDisplay: DoorCameraDisplayState = DoorCameraDisplayState(),
  homeRoomChanges: List<HorizonHomeRoomChange> = emptyList(),
): JsonObject? {
  val call = ringCallPresentation(ringCall, ringNotice, cameraDisplay, nowMs)
  val activeHomeChanges = homeRoomChanges.filter { it.startedAtMs <= nowMs && it.highlightExpiresAtMs > nowMs }
  val mediaGeneration = horizonMediaPlaybackCycle.observe(media?.isPlaying == true)
  val runningTimer = schedules.firstOrNull { it.kind == JarvisScheduleKind.Timer && it.state in setOf(JarvisScheduleState.Scheduled, JarvisScheduleState.Paused) }
  // Do not advance a bounded tool card while Ring/timer/alert owns the stage.
  val automaticToolTile = lazy { horizonAutomaticToolTile(activeTools, nowMs) }
  return when {
  ringActive || call != null -> horizonTile(
    "camera", "door-camera:${ringCall.noticeAtMs ?: "manual"}",
    expiresAtMs = if (!ringActive) ringCall.endedAtMs?.plus(3_800) else null,
    sticky = ringActive,
  ) {
    put("source", if (ringAlert) "Haustürklingel" else "${cameraName ?: "Haustür"} · Kamera")
    call?.let { observed -> put("call", buildJsonObject {
      put("phase", observed.phase.name.lowercase())
      put("startedAtMs", ringCall.startedAtMs?.let(::JsonPrimitive) ?: JsonNull)
      put("endedAtMs", ringCall.endedAtMs?.let(::JsonPrimitive) ?: JsonNull)
      put("error", ringCall.error)
      put("title", observed.title)
      put("detail", observed.detail)
      put("hints", kotlinx.serialization.json.buildJsonArray { observed.hints.forEach { add(JsonPrimitive(it)) } })
    }) }
  }
  alert != null -> horizonTile("timer", alert.id, sticky = true) { put("remaining", "Fällig"); put("title", alert.name) }
  runningTimer != null -> {
    val timer = requireNotNull(runningTimer)
    horizonTile("timer", timer.id, sticky = true) {
      val remainingMs = if (timer.state == JarvisScheduleState.Paused) timer.pausedRemainingMs ?: 0L else (timer.dueAtEpochMs - nowMs).coerceAtLeast(0)
      put("remaining", "%02d:%02d".format(Locale.GERMANY, remainingMs / 60_000, (remainingMs / 1_000) % 60))
      put("title", timer.name)
      put("meta", if (timer.state == JarvisScheduleState.Paused) "Pausiert" else "Läuft")
      timer.durationMs?.takeIf { it > 0 }?.let { put("progress", (remainingMs.toDouble() / it).coerceIn(0.0, 1.0)) }
      put("hints", kotlinx.serialization.json.buildJsonArray {
        add(JsonPrimitive(if (timer.state == JarvisScheduleState.Paused) "Fortsetzen" else "Pause"))
        add(JsonPrimitive("Plus eine Minute")); add(JsonPrimitive("Stopp"))
      })
    }
  }
  automaticToolTile.value != null -> requireNotNull(automaticToolTile.value)
  announcement != null -> horizonTile("message", announcement.id, ttl = 20_000) { put("text", announcement.text); put("source", announcement.sourceName) }
  activeHomeChanges.isNotEmpty() && presentation.tile?.type != HorizonTileType.Message -> {
    val latest = activeHomeChanges.maxBy { it.startedAtMs }
    HorizonTile("ha-home:${latest.startedAtMs}", HorizonTileType.Home, JsonObject(emptyMap()), latest.highlightExpiresAtMs, false)
      .toHydratedHorizonJson(dashboard, nowMs, activeHomeChanges)
  }
  presentation.tile != null -> presentation.tile.toHydratedHorizonJson(dashboard, nowMs, activeHomeChanges)
  presentation.toHorizonJson(nowMs) != null -> presentation.toHorizonJson(nowMs)
  media?.isPlaying == true -> {
    val mediaId = "${media.entityId}:${media.title.orEmpty()}:${media.source.orEmpty()}:$mediaGeneration"
    horizonTile("music", mediaId, expiresAtMs = horizonTransientDeadline(mediaId, nowMs, 8_000)) { put("title", media.title ?: media.source ?: media.name); put("artist", media.artist); put("source", media.source ?: media.name); put("artworkDataUrl", media.horizonArtworkDataUrl()?.let(::JsonPrimitive) ?: JsonNull) }
  }
  else -> presentation.toHorizonJson(nowMs)
}
}

/** Native HA/calendar facts fill blank visual requests; message and terminal content stay agent-owned. */
private fun HorizonTile.toHydratedHorizonJson(dashboard: HomeDashboardState, nowMs: Long, homeChanges: List<HorizonHomeRoomChange> = emptyList()): JsonObject {
  if (data.isNotEmpty() || type !in setOf(HorizonTileType.Weather, HorizonTileType.Tasks, HorizonTileType.Home, HorizonTileType.Music)) return toHorizonJson(nowMs)
  val zone = ZoneId.systemDefault()
  val hydrated = when (type) {
    HorizonTileType.Weather -> buildJsonObject {
      val weather = dashboard.weather
      put("temperature", weather?.temperatureCelsius?.let { JsonPrimitive("${formatTemperature(it)}${weather.temperatureUnit ?: "°"}") } ?: JsonNull)
      put("summary", weather?.condition?.let { JsonPrimitive(it.horizonWeatherLabel()) } ?: JsonNull)
      put("wind", weather?.windSpeed?.let { JsonPrimitive("$it ${weather.windSpeedUnit.orEmpty()}".trim()) } ?: JsonNull)
      put("rain", weather?.precipitation?.let(::JsonPrimitive) ?: JsonNull)
      put("humidity", weather?.humidityPercent?.let(::JsonPrimitive) ?: JsonNull)
      put("uv", weather?.uvIndex?.let(::JsonPrimitive) ?: JsonNull)
      put("location", weather?.let { JsonPrimitive("Zuhause") } ?: JsonNull)
      put("updated", JsonNull)
      put("source", weather?.takeIf { dashboard.weatherLoaded }?.let { JsonPrimitive("Home Assistant") } ?: JsonNull)
      put("forecast", kotlinx.serialization.json.buildJsonArray {
        weather?.hourlyForecast?.take(12)?.forEach { hour -> add(buildJsonObject {
          put("time", hour.at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)))
          put("temperature", hour.temperature?.let(::JsonPrimitive) ?: JsonNull)
          put("summary", hour.condition.horizonWeatherLabel())
          put("rain", hour.precipitationProbability?.let(::JsonPrimitive) ?: JsonNull)
        }) }
      })
    }
    HorizonTileType.Tasks -> buildJsonObject {
      put("items", kotlinx.serialization.json.buildJsonArray {
        dashboard.calendarEvents.take(4).forEach { event -> add(buildJsonObject {
          put("time", if (event.start.isAllDay) "Heute" else event.startsAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)))
          put("title", event.summary)
          put("meta", if (event.start.isAllDay) "Ganztägig" else "Kalender")
        }) }
      })
    }
    HorizonTileType.Home -> buildJsonObject {
      val changes = homeChanges.associateBy { it.roomKey }
      val rooms = horizonHomeRoomValues(dashboard.homeDevices).sortedByDescending { changes[it.key]?.startedAtMs ?: 0 }
      put("rooms", kotlinx.serialization.json.buildJsonArray {
        rooms.take(4).forEach { room -> add(buildJsonObject {
          put("id", room.key)
          put("name", room.label)
          put("level", room.level?.let(::JsonPrimitive) ?: JsonNull)
          put("detail", room.detail)
          val change = changes[room.key]
          put("changed", change != null)
          change?.let {
            put("previousLevel", it.fromLevel?.let(::JsonPrimitive) ?: JsonNull)
            put("changedAtMs", it.startedAtMs)
          }
        }) }
      })
      if (rooms.size > 4) put("meta", "+${rooms.size - 4} weitere Bereiche")
    }
    HorizonTileType.Music -> buildJsonObject {
      val player = dashboard.mediaPlayers.firstOrNull { it.isPlaying } ?: dashboard.mediaPlayers.firstOrNull { it.available }
      put("title", (player?.title ?: player?.source ?: player?.name)?.let(::JsonPrimitive) ?: JsonNull)
      put("artist", player?.artist?.let(::JsonPrimitive) ?: JsonNull)
      put("source", (player?.source ?: player?.name)?.let(::JsonPrimitive) ?: JsonNull)
      put("artworkDataUrl", player?.horizonArtworkDataUrl()?.let(::JsonPrimitive) ?: JsonNull)
      val progress = player?.positionSeconds?.let { position -> player.durationSeconds?.takeIf { it > 0 }?.let { position / it * 100 } }
      put("progress", progress?.let(::JsonPrimitive) ?: JsonNull)
    }
    else -> data
  }
  return buildJsonObject {
    put("id", id)
    put("type", type.name.lowercase())
    put("data", hydrated)
    put("ttl", expiresAtMs?.minus(nowMs)?.coerceAtLeast(0)?.let(::JsonPrimitive) ?: JsonNull)
    put("expiresAtMs", expiresAtMs?.let(::JsonPrimitive) ?: JsonNull)
    put("sticky", sticky)
  }
}

private fun horizonTile(type: String, id: String, ttl: Long? = null, expiresAtMs: Long? = null, sticky: Boolean = false, data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
  buildJsonObject { put("id", id); put("type", type); put("ttl", ttl?.let(::JsonPrimitive) ?: JsonNull); put("expiresAtMs", expiresAtMs?.let(::JsonPrimitive) ?: JsonNull); put("sticky", sticky); put("data", buildJsonObject(data)) }

private object horizonTransientTileDeadline {
  private var id: String? = null
  private var expiresAtMs: Long = 0
  @Synchronized fun deadline(nextId: String, nowMs: Long, durationMs: Long): Long {
    if (id != nextId) { id = nextId; expiresAtMs = nowMs + durationMs }
    return expiresAtMs
  }
}

private fun horizonTransientDeadline(id: String, nowMs: Long, durationMs: Long): Long = horizonTransientTileDeadline.deadline(id, nowMs, durationMs)

/**
 * Projects the active voice consult's bounded, privacy-filtered tool history.
 * Tool run IDs and arguments never leave TalkModeManager; this surface receives
 * only its four controlled labels/details and real monotonic durations.
 */
private fun horizonAutomaticToolTile(activeTools: List<JarvisToolExecution>, nowMs: Long): JsonObject? {
  val history = activeTools.sortedBy { it.startedAtMs ?: it.changedAtMs }.takeLast(4)
  if (history.isEmpty()) return null
  val newest = history.maxByOrNull { it.changedAtMs } ?: return null
  val running = history.any { it.phase == JarvisToolPhase.Running }
  val expiresAtMs = if (running) null else horizonToolActivityDeadline.expiresAt(newest, nowMs) ?: return null
  val failed = !running && newest.phase == JarvisToolPhase.Failed
  return horizonTile(
    type = "terminal",
    id = "voice-tools-${history.first().toolCallId}",
    expiresAtMs = expiresAtMs,
    sticky = running,
  ) {
    put("title", if (history.size == 1) newest.name else "Ausführung · ${history.size} Schritte")
    put("status", if (running) "running" else newest.phase.name.lowercase())
    put("failed", failed)
    newest.horizonDurationMs()?.let { put("durationMs", it) }
    put("lines", kotlinx.serialization.json.buildJsonArray {
      history.forEach { tool ->
        add(buildJsonObject {
          put("kind", tool.horizonLineKind())
          put("text", tool.detail ?: tool.name)
          tool.horizonDurationMs()?.let { put("durationMs", it) }
        })
      }
    })
  }
}

private fun JarvisToolExecution.horizonLineKind(): String =
  when (phase) {
    JarvisToolPhase.Running -> "cmd"
    JarvisToolPhase.Completed -> "done"
    JarvisToolPhase.Failed -> "err"
    JarvisToolPhase.Unknown -> "warn"
  }

private fun JarvisToolExecution.horizonDurationMs(): Long? =
  startedAtMs?.let { started -> (changedAtMs - started).takeIf { it >= 0 && phase != JarvisToolPhase.Running } }

/**
 * Tool timestamps are monotonic Android times, while a Horizon snapshot uses
 * wall-clock milliseconds.  Anchor a settled tool exactly once when it first
 * reaches the renderer, then keep its absolute deadline stable across refreshes.
 */
private object horizonToolActivityDeadline {
  private const val settledToolVisibleMillis = 9_000L
  private var eventId: String? = null
  private var expiresAtMs = 0L

  @Synchronized fun expiresAt(tool: JarvisToolExecution, nowMs: Long): Long? {
    val nextId = "${tool.toolCallId}:${tool.phase}:${tool.changedAtMs}"
    if (nextId != eventId) {
      eventId = nextId
      expiresAtMs = nowMs + settledToolVisibleMillis
    }
    return expiresAtMs.takeIf { nowMs < it }
  }
}

/** A stopped→playing transition makes replay of the same track a new visual event. */
private object horizonMediaPlaybackCycle {
  private var wasPlaying = false
  private var generation = 0L
  @Synchronized fun observe(isPlaying: Boolean): Long {
    if (isPlaying && !wasPlaying) generation += 1
    wasPlaying = isPlaying
    return generation
  }
}

private fun formatTemperature(value: Double): String =
  if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.US, value)

private fun formatHorizonDecimal(value: Double): String =
  if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.GERMANY, value)

/** Encodes a small local cover at most once per observed artwork byte-array. */
private fun HomeMediaPlayer.horizonArtworkDataUrl(): String? = horizonArtworkDataUrl(artworkBytes)
internal fun horizonArtworkDataUrl(bytes: ByteArray?): String? = horizonArtworkDataUrlCache.get(bytes)

private object horizonArtworkDataUrlCache {
  private const val maxArtworkBytes = 256 * 1024
  private var source: ByteArray? = null
  private var encoded: String? = null

  @Synchronized fun get(bytes: ByteArray?): String? {
    if (bytes == null || bytes.size > maxArtworkBytes) return null
    if (bytes === source) return encoded
    source = bytes
    val mime = artworkMimeType(bytes) ?: return null
    encoded = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    return encoded
  }
}

private fun artworkMimeType(bytes: ByteArray): String? = when {
  bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
  bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
  bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) && bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) -> "image/webp"
  else -> null
}

private fun horizonServices(
  gatewayConnected: Boolean,
  gatewayStatus: String,
  dashboard: HomeDashboardState,
  health: List<JarvisHealthComponent>,
  nowMs: Long,
): List<HorizonService> = buildList {
  add(HorizonService("gateway", if (gatewayConnected) "ok" else "error", gatewayStatus.ifBlank { "Offline" }))
  add(
    HorizonService(
      "home",
      when (dashboard.connection) {
        HomeConnectionState.Connected -> "ok"
        HomeConnectionState.Connecting -> "warn"
        HomeConnectionState.Error -> "error"
        HomeConnectionState.MissingConfiguration -> "stale"
      },
      dashboard.connectionError ?: dashboard.connection.name.lowercase(),
    ),
  )
  health.forEach { component ->
    add(
      HorizonService(
        component.id,
        when {
          component.isStale(nowMs) -> "stale"
          component.status == JarvisHealthStatus.Ready -> "ok"
          component.status == JarvisHealthStatus.Degraded -> "warn"
          component.status == JarvisHealthStatus.Unreachable -> "error"
          else -> "stale"
        },
        component.detail,
      ),
    )
  }
}
