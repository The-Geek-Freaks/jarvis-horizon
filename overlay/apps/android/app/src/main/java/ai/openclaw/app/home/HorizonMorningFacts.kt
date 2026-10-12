package ai.openclaw.app.home

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Read-only, bounded facts for one requested morning briefing; no response or playlist cache. */
internal fun horizonMorningFactsJson(
  dashboard: HomeDashboardState,
  now: Instant = Instant.now(),
  zone: ZoneId = ZoneId.systemDefault(),
): JsonObject = buildJsonObject {
  val connected = dashboard.connection == HomeConnectionState.Connected
  put("status", "ok")
  put("asOf", now.toString())
  put("timezone", zone.id)
  put("weather", buildJsonObject {
    val weather = dashboard.weather.takeIf { connected && dashboard.weatherLoaded }
    put("available", weather != null)
    if (weather != null) {
      put("condition", weather.condition)
      put("temperature", weather.temperatureCelsius?.let(::JsonPrimitive) ?: JsonNull)
      put("unit", weather.temperatureUnit)
      put("forecastAvailable", weather.forecastLoaded)
      put("hours", buildJsonArray {
        weather.hourlyForecast.filter { !it.at.isBefore(now) }.take(8).forEach { hour -> add(buildJsonObject {
          put("at", hour.at.toString()); put("condition", hour.condition)
          put("temperature", hour.temperature?.let(::JsonPrimitive) ?: JsonNull)
          put("rainPercent", hour.precipitationProbability?.let(::JsonPrimitive) ?: JsonNull)
        }) }
      })
    }
  })
  put("calendar", buildJsonObject {
    val available = connected && dashboard.calendarLoaded && dashboard.calendarError == null
    put("available", available)
    put("events", buildJsonArray {
      if (available) dashboard.calendarEvents.filter {
        (it.start.allDayDate ?: it.startsAt.atZone(zone).toLocalDate()) == now.atZone(zone).toLocalDate() &&
          (it.endsAt?.isAfter(now) ?: (it.start.isAllDay || !it.startsAt.isBefore(now)))
      }.take(8).forEach { event -> add(buildJsonObject {
        put("title", event.summary.take(160)); put("start", event.startsAt.toString())
        put("allDay", event.start.isAllDay)
      }) }
    })
  })
  put("music", buildJsonObject {
    val player = dashboard.mediaPlayers.firstOrNull { connected && dashboard.mediaLoaded && it.available && it.isPlaying }
    put("playing", player != null)
    if (player != null) {
      put("title", player.title); put("artist", player.artist); put("device", player.name)
    }
    put("startedByBriefing", false)
  })
}
