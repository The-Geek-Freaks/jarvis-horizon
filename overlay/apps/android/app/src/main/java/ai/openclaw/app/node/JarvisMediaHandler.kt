package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SPOTIFY_PACKAGE = "com.spotify.music"

internal enum class JarvisMediaPlaybackState { Playing, Paused, Stopped, Buffering, Error, Unknown }

internal data class JarvisMediaSnapshot(
  val playback: JarvisMediaPlaybackState,
  val loginRequired: Boolean,
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
)

internal interface JarvisMediaSession {
  val packageName: String
  fun snapshot(): JarvisMediaSnapshot
  fun play()
  fun pause()
  fun skipNext()
  fun skipPrevious()
}

internal interface JarvisMediaSessionSource {
  fun activeSessions(): List<JarvisMediaSession>
}

internal interface JarvisMusicVolume {
  fun fraction(): Double?
  fun setFraction(value: Double): Double?
}

/** Local-only Spotify session control. It never reports a requested state as an achieved state. */
class JarvisMediaHandler internal constructor(
  private val listenerAccessEnabled: () -> Boolean,
  private val sessions: JarvisMediaSessionSource,
  private val volume: JarvisMusicVolume,
) {
  constructor(context: Context) : this(
    listenerAccessEnabled = { DeviceNotificationListenerService.isAccessEnabled(context) },
    sessions = AndroidJarvisMediaSessionSource(context.applicationContext),
    volume = AndroidJarvisMusicVolume(context.applicationContext),
  )

  suspend fun handleJarvisMedia(paramsJson: String?): GatewaySession.InvokeResult {
    val params = parseJsonParamsObject(paramsJson)
      ?: return nodeInvokeError("INVALID_REQUEST", "expected action: status, play, pause, next, previous, or volume")
    val action = parseJsonString(params, "action")?.trim()?.lowercase()
      ?: return nodeInvokeError("INVALID_REQUEST", "missing action")
    if (action !in ACTIONS) return nodeInvokeError("INVALID_REQUEST", "unsupported media action")
    if (!listenerAccessEnabled()) return nodeInvokeError("MEDIA_SESSION_ACCESS_REQUIRED", "enable notification listener access")
    val session = sessions.activeSessions().firstOrNull { it.packageName == SPOTIFY_PACKAGE }
      ?: return nodeInvokeError("SPOTIFY_SESSION_UNAVAILABLE", "open Spotify on this device")
    val before = session.snapshot()
    if (before.loginRequired) return nodeInvokeError("SPOTIFY_LOGIN_REQUIRED", "sign in to Spotify on this device")

    val requestedVolume =
      if (action == "volume") {
        parseJsonDouble(params, "volume")?.takeIf { it in 0.0..1.0 }
          ?: return nodeInvokeError("INVALID_REQUEST", "volume must be between 0 and 1")
      } else {
        null
      }
    try {
      when (action) {
        "play" -> session.play()
        "pause" -> session.pause()
        "next" -> session.skipNext()
        "previous" -> session.skipPrevious()
        "volume" -> volume.setFraction(checkNotNull(requestedVolume))
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      return nodeInvokeError("SPOTIFY_CONTROL_FAILED", "media session command failed")
    }
    return GatewaySession.InvokeResult.ok(mediaPayload(action, session.snapshot(), volume.fraction()))
  }

  private fun mediaPayload(action: String, snapshot: JarvisMediaSnapshot, volumeFraction: Double?): String =
    buildJsonObject {
      put("provider", "spotify")
      put("action", action)
      put("status", statusFor(snapshot))
      put("playbackState", snapshot.playback.name.lowercase())
      volumeFraction?.let { put("localMusicVolume", it) }
      snapshot.title?.let { put("title", it) }
      snapshot.artist?.let { put("artist", it) }
      snapshot.album?.let { put("album", it) }
    }.toString()

  private fun statusFor(snapshot: JarvisMediaSnapshot): String =
    when {
      snapshot.loginRequired -> "login_required"
      snapshot.playback == JarvisMediaPlaybackState.Error -> "error"
      else -> "ready"
    }

  private companion object {
    val ACTIONS = setOf("status", "play", "pause", "next", "previous", "volume")
  }
}

private class AndroidJarvisMediaSessionSource(context: Context) : JarvisMediaSessionSource {
  private val appContext = context.applicationContext
  private val manager = appContext.getSystemService(MediaSessionManager::class.java)

  override fun activeSessions(): List<JarvisMediaSession> =
    try {
      manager?.getActiveSessions(ComponentName(appContext, DeviceNotificationListenerService::class.java))
        ?.map(::AndroidJarvisMediaSession)
        .orEmpty()
    } catch (_: SecurityException) {
      emptyList()
    }
}

private class AndroidJarvisMediaSession(private val controller: MediaController) : JarvisMediaSession {
  override val packageName: String get() = controller.packageName

  override fun snapshot(): JarvisMediaSnapshot {
    val state = controller.playbackState
    val error = state?.errorMessage?.toString()?.lowercase().orEmpty()
    val metadata = controller.metadata
    return JarvisMediaSnapshot(
      playback = playbackState(state?.state),
      loginRequired = isSpotifyLoginRequired(error),
      title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.ifEmpty { null },
      artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.ifEmpty { null },
      album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.ifEmpty { null },
    )
  }

  override fun play() = controller.transportControls.play()
  override fun pause() = controller.transportControls.pause()
  override fun skipNext() = controller.transportControls.skipToNext()
  override fun skipPrevious() = controller.transportControls.skipToPrevious()

  private fun playbackState(state: Int?): JarvisMediaPlaybackState =
    when (state) {
      PlaybackState.STATE_PLAYING -> JarvisMediaPlaybackState.Playing
      PlaybackState.STATE_PAUSED -> JarvisMediaPlaybackState.Paused
      PlaybackState.STATE_STOPPED -> JarvisMediaPlaybackState.Stopped
      PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> JarvisMediaPlaybackState.Buffering
      PlaybackState.STATE_ERROR -> JarvisMediaPlaybackState.Error
      else -> JarvisMediaPlaybackState.Unknown
    }
}

internal fun isSpotifyLoginRequired(errorMessage: String?): Boolean {
  val message = errorMessage?.lowercase().orEmpty()
  return message.contains("melde dich an") ||
    message.contains("bitte anmelden") ||
    message.contains("login required") ||
    message.contains("sign in") ||
    message.contains("log in") ||
    message.contains("not logged in")
}

private class AndroidJarvisMusicVolume(context: Context) : JarvisMusicVolume {
  private val audio = context.getSystemService(AudioManager::class.java)

  override fun fraction(): Double? {
    val manager = audio ?: return null
    val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return maximum.takeIf { it > 0 }?.let { manager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / it }
  }

  override fun setFraction(value: Double): Double? {
    val manager = audio ?: return null
    val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (maximum <= 0) return null
    manager.setStreamVolume(AudioManager.STREAM_MUSIC, (maximum * value).roundToInt(), 0)
    return fraction()
  }
}
