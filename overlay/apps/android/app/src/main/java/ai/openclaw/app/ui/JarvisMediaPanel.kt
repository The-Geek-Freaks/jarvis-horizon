package ai.openclaw.app.ui

import ai.openclaw.app.R
import ai.openclaw.app.home.HomeDashboardRepository
import ai.openclaw.app.home.HomeMediaPlayer
import ai.openclaw.app.home.HomeWeather
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Compact read-only summary of explicit Home Assistant weather and media facts. */
@Composable
fun JarvisMediaPanel(
  repository: HomeDashboardRepository,
  modifier: Modifier = Modifier,
) {
  val dashboard by repository.dashboard.collectAsState()
  val players = dashboard.mediaPlayers
    .filter(HomeMediaPlayer::available)
    .sortedWith(compareByDescending(HomeMediaPlayer::isPlaying).thenBy(HomeMediaPlayer::name))
    .take(1)
  if (dashboard.weather == null && players.isEmpty()) return

  Column(
    modifier =
      modifier
        .widthIn(max = 236.dp)
        .heightIn(max = 180.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    dashboard.weather?.let { WeatherCard(it) }
    players.forEach { MediaCard(it) }
  }
}

@Composable
private fun WeatherCard(weather: HomeWeather) {
  val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
  val night = weather.condition.equals("clear-night", ignoreCase = true) || hour >= 20 || hour < 6
  val weatherArtwork =
    rememberJarvisHorizonBitmap(
      resourceId = if (night) R.drawable.jarvis_weather_night else R.drawable.jarvis_weather_day,
      maxEdgePx = if (rememberJarvisSmallPhysicalDisplay()) 512 else Int.MAX_VALUE,
    )
  Box(
    modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(11.dp)).horizonGlass(),
  ) {
    Image(
      bitmap = weatherArtwork,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      alpha = 0.56f,
      modifier = Modifier.fillMaxSize(),
    )
    Row(
      modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      WeatherGlyph(weather.condition, Modifier.size(36.dp))
      Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("WETTER", color = Color(0xFFF6C878), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
          text = weather.temperatureCelsius?.let { "${formatTemperature(it)}°" } ?: "–",
          color = Color(0xFFE6F1FF),
          fontSize = 25.sp,
          lineHeight = 27.sp,
          fontWeight = FontWeight.Light,
        )
        val detail = buildString {
          append(weather.condition.jarvisConditionLabel())
          weather.humidityPercent?.let { append(" · $it %") }
        }
        Text(detail, color = Color(0xFFE6F1FF).copy(alpha = 0.76f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun MediaCard(player: HomeMediaPlayer) {
  val title = player.title ?: player.source ?: player.state.jarvisStateLabel()
  val detail = player.artist ?: player.name
  val artwork by produceState<ImageBitmap?>(initialValue = null, player.artworkBytes) {
    value = player.artworkBytes?.let { bytes ->
      withContext(Dispatchers.Default) { decodeJarvisArtwork(bytes) }
    }
  }
  Column(
    modifier = Modifier.fillMaxWidth().horizonGlass().padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
      artwork?.let { image ->
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(42.dp))
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = if (player.isPlaying) "JETZT LÄUFT" else "AUDIO",
          color = Color(0xFFF6C878),
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.sp,
        )
        Text(title, color = Color(0xFFE6F1FF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = Color(0xFFA9BDD2), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    MediaProgress(player)
  }
}

@Composable
private fun MediaProgress(player: HomeMediaPlayer) {
  val anchoredPosition = player.positionSeconds
  val duration = player.durationSeconds
  if (anchoredPosition == null || duration == null || duration <= 0.0) return
  val nowEpochMs by rememberMediaProgressClock(player)
  val elapsedSeconds =
    if (player.isPlaying && player.positionUpdatedAtEpochMs != null) {
      ((nowEpochMs - player.positionUpdatedAtEpochMs).coerceAtLeast(0L) / 1_000.0)
    } else {
      0.0
    }
  val position = (anchoredPosition + elapsedSeconds).coerceAtMost(duration)
  val fraction = (position / duration).coerceIn(0.0, 1.0).toFloat()
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(formatMediaTime(position), color = Color(0xFFA9BDD2), fontSize = 9.sp)
    Box(modifier = Modifier.weight(1f).heightIn(min = 2.dp).background(Color(0x334E80A7), RoundedCornerShape(2.dp))) {
      Box(modifier = Modifier.fillMaxWidth(fraction).heightIn(min = 2.dp).background(Color(0xFFF6C878), RoundedCornerShape(2.dp)))
    }
    Text(formatMediaTime(duration), color = Color(0xFFA9BDD2), fontSize = 9.sp)
  }
}

@Composable
private fun rememberMediaProgressClock(player: HomeMediaPlayer): androidx.compose.runtime.State<Long> {
  var nowEpochMs by remember(player.entityId, player.positionSeconds, player.durationSeconds, player.positionUpdatedAtEpochMs) {
    mutableLongStateOf(System.currentTimeMillis())
  }
  LaunchedEffect(player.isPlaying, player.positionSeconds, player.durationSeconds, player.positionUpdatedAtEpochMs) {
    val start = player.positionSeconds
    val duration = player.durationSeconds
    val anchor = player.positionUpdatedAtEpochMs
    if (!player.isPlaying || start == null || duration == null || anchor == null) return@LaunchedEffect
    while (start + ((System.currentTimeMillis() - anchor).coerceAtLeast(0L) / 1_000.0) < duration) {
      nowEpochMs = System.currentTimeMillis()
      delay(1_000L)
    }
  }
  return rememberUpdatedState(nowEpochMs)
}

/** Decode repository-cached artwork off the UI thread and bound decoded pixels, not only compressed bytes. */
private fun decodeJarvisArtwork(bytes: ByteArray): ImageBitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
  return BitmapFactory.decodeByteArray(
    bytes,
    0,
    bytes.size,
    BitmapFactory.Options().apply { inSampleSize = sample },
  )?.asImageBitmap()
}

private fun formatMediaTime(seconds: Double): String {
  val total = seconds.toInt().coerceAtLeast(0)
  return "%d:%02d".format(total / 60, total % 60)
}

private fun Modifier.horizonGlass(): Modifier =
  background(
    Brush.verticalGradient(
      colors = listOf(Color(0xFF1B3B6F).copy(alpha = 0.30f), Color(0xFF0A0F1E).copy(alpha = 0.68f)),
    ),
    RoundedCornerShape(11.dp),
  )
    .border(1.dp, Color(0x359ED6E6), RoundedCornerShape(11.dp))

/** A small condition mark from the real Home Assistant condition, never a stock scene or forecast. */
@Composable
private fun WeatherGlyph(condition: String, modifier: Modifier = Modifier) {
  val normalized = condition.lowercase()
  Canvas(modifier = modifier) {
    val glyphCenter = center
    val sun = Color(0xFFF6C878)
    val cloud = Color(0xFFA9BDD2)
    when {
      normalized == "clear-night" -> {
        drawCircle(sun.copy(alpha = 0.90f), size.minDimension * 0.25f, Offset(glyphCenter.x + size.minDimension * 0.08f, glyphCenter.y - size.minDimension * 0.06f))
        drawCircle(Color(0xFF1B3B6F), size.minDimension * 0.25f, Offset(glyphCenter.x + size.minDimension * 0.17f, glyphCenter.y - size.minDimension * 0.14f))
      }
      normalized == "sunny" || normalized == "clear" -> {
        drawCircle(sun, size.minDimension * 0.19f, glyphCenter)
        repeat(8) { index ->
          val angle = index * Math.PI / 4.0
          val start = Offset(glyphCenter.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.28f, glyphCenter.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.28f)
          val end = Offset(glyphCenter.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.42f, glyphCenter.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.42f)
          drawLine(sun, start, end, strokeWidth = 1.3.dp.toPx())
        }
      }
      else -> {
        drawCircle(sun.copy(alpha = 0.92f), size.minDimension * 0.16f, Offset(glyphCenter.x + size.minDimension * 0.13f, glyphCenter.y - size.minDimension * 0.16f))
        drawCircle(cloud.copy(alpha = 0.94f), size.minDimension * 0.21f, Offset(glyphCenter.x - size.minDimension * 0.06f, glyphCenter.y + size.minDimension * 0.08f))
        drawCircle(cloud, size.minDimension * 0.27f, Offset(glyphCenter.x + size.minDimension * 0.15f, glyphCenter.y + size.minDimension * 0.10f))
        drawLine(cloud, Offset(glyphCenter.x - size.minDimension * 0.29f, glyphCenter.y + size.minDimension * 0.25f), Offset(glyphCenter.x + size.minDimension * 0.42f, glyphCenter.y + size.minDimension * 0.25f), strokeWidth = size.minDimension * 0.18f)
      }
    }
  }
}

private fun formatTemperature(value: Double): String =
  if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(java.util.Locale.GERMANY, value)

private fun String.jarvisConditionLabel(): String =
  when (lowercase()) {
    "sunny" -> "Sonnig"
    "clear-night" -> "Klar"
    "partlycloudy" -> "Wolkig"
    "cloudy" -> "Bedeckt"
    "rainy", "pouring" -> "Regen"
    "snowy" -> "Schnee"
    "fog" -> "Nebel"
    else -> this
  }

private fun String.jarvisStateLabel(): String =
  when (lowercase()) {
    "playing" -> "Wiedergabe"
    "paused" -> "Pausiert"
    "idle" -> "Bereit"
    else -> this
  }
