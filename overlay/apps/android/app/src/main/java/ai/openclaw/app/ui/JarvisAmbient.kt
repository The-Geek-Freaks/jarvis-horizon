package ai.openclaw.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal const val DEFAULT_JARVIS_AMBIENT_TIMEOUT_MS = 60_000L
internal const val DEFAULT_JARVIS_AMBIENT_NIGHT_BRIGHTNESS = 0.05f
internal const val DEFAULT_JARVIS_AMBIENT_DAY_BRIGHTNESS = 0.12f

internal enum class JarvisAmbientMode { Awake, Ambient }

internal data class JarvisAmbientSettings(
  val timeoutMs: Long = DEFAULT_JARVIS_AMBIENT_TIMEOUT_MS,
  val nightBrightness: Float = DEFAULT_JARVIS_AMBIENT_NIGHT_BRIGHTNESS,
) {
  val sanitized: JarvisAmbientSettings
    get() = copy(
      timeoutMs = timeoutMs.takeIf { it in supportedJarvisAmbientTimeoutsMs } ?: DEFAULT_JARVIS_AMBIENT_TIMEOUT_MS,
      nightBrightness = nightBrightness.coerceIn(0.03f, 0.20f),
    )
}

internal val supportedJarvisAmbientTimeoutsMs = setOf(30_000L, 60_000L, 120_000L, 300_000L)

internal data class JarvisAmbientInput(
  val nowMs: Long,
  val lastActivityMs: Long,
  val blocksAmbient: Boolean = false,
  val forceAmbient: Boolean = false,
  val settings: JarvisAmbientSettings = JarvisAmbientSettings(),
)

/** Pure timing policy. Ambient light controls brightness only; it never decides presence. */
internal fun jarvisAmbientMode(input: JarvisAmbientInput): JarvisAmbientMode {
  if (input.blocksAmbient) return JarvisAmbientMode.Awake
  if (input.forceAmbient) return JarvisAmbientMode.Ambient
  val elapsedMs = (input.nowMs - input.lastActivityMs).coerceAtLeast(0L)
  return if (elapsedMs >= input.settings.sanitized.timeoutMs) JarvisAmbientMode.Ambient else JarvisAmbientMode.Awake
}

internal fun jarvisAmbientBrightness(lux: Float?, settings: JarvisAmbientSettings): Float =
  if (lux != null && lux >= 20f) DEFAULT_JARVIS_AMBIENT_DAY_BRIGHTNESS else settings.sanitized.nightBrightness

internal class JarvisAmbientSettingsStore(context: Context) {
  private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun load(): JarvisAmbientSettings =
    JarvisAmbientSettings(
      timeoutMs = preferences.getLong(KEY_TIMEOUT_MS, DEFAULT_JARVIS_AMBIENT_TIMEOUT_MS),
      nightBrightness = preferences.getFloat(KEY_NIGHT_BRIGHTNESS, DEFAULT_JARVIS_AMBIENT_NIGHT_BRIGHTNESS),
    ).sanitized

  fun save(settings: JarvisAmbientSettings) {
    val sanitized = settings.sanitized
    preferences.edit()
      .putLong(KEY_TIMEOUT_MS, sanitized.timeoutMs)
      .putFloat(KEY_NIGHT_BRIGHTNESS, sanitized.nightBrightness)
      .apply()
  }

  private companion object {
    const val PREFERENCES_NAME = "jarvis_ambient"
    const val KEY_TIMEOUT_MS = "timeout_ms"
    const val KEY_NIGHT_BRIGHTNESS = "night_brightness"
  }
}

/** Emits null when this ROM exposes no ambient-light sensor. It never synthesizes a lux value. */
@Composable
internal fun rememberJarvisAmbientLightLux(): Float? {
  val context = LocalContext.current.applicationContext
  var rawLux by remember { mutableFloatStateOf(Float.NaN) }
  DisposableEffect(context) {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    if (manager == null || sensor == null) {
      onDispose {}
    } else {
      val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          rawLux = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: Float.NaN
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
      }
      manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
      onDispose { manager.unregisterListener(listener) }
    }
  }
  return rawLux.takeIf { it.isFinite() }
}
