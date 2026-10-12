package ai.openclaw.app.home

import ai.openclaw.app.MainActivity
import ai.openclaw.app.R
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One physical doorbell event, kept separate from manual camera presentation. */
internal data class RingAlert(
  val id: String,
  val occurredAt: Instant,
  val cameraName: String,
)

/**
 * Presents fresh physical Ring events immediately.  This intentionally does not share the Alexa
 * announcement queue: that queue waits for the door-camera overlay to close, while a doorbell
 * chime must happen while the overlay is being opened.
 */
internal class RingAlertController(
  private val scope: CoroutineScope,
  private val mayPlayAlert: () -> Boolean,
  private val acquireWakeSuppression: () -> Unit,
  private val releaseWakeSuppression: () -> Unit,
  private val playChime: suspend () -> Unit,
  private val publishNotification: (RingAlert) -> Boolean,
  private val now: () -> Instant = Instant::now,
) {
  private val lock = Any()
  private val rememberedIds = LinkedHashSet<String>()

  fun receive(notice: RingNotice, cameraName: String?) {
    if (notice.manual || !isFreshRingNotice(notice.occurredAt, now())) return
    val alert = RingAlert(
      id = "ring:${notice.occurredAt.toEpochMilli()}",
      occurredAt = notice.occurredAt,
      cameraName = cameraName?.trim().takeUnless { it.isNullOrEmpty() } ?: "Haustür",
    )
    synchronized(lock) {
      if (!rememberedIds.add(alert.id)) return
      while (rememberedIds.size > MAXIMUM_REMEMBERED_RING_IDS) rememberedIds.remove(rememberedIds.first())
    }
    val notificationPublished = runCatching { publishNotification(alert) }
      .onFailure { Log.w(TAG, "notification failed id=${alert.id}") }
      .getOrDefault(false)
    if (!notificationPublished) Log.w(TAG, "notification not delivered id=${alert.id}")
    if (!mayPlayAlert()) return
    scope.launch {
      acquireWakeSuppression()
      try {
        playChime()
        Log.i(TAG, "chime complete id=${alert.id}")
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        Log.w(TAG, "chime failed id=${alert.id}")
      } finally {
        releaseWakeSuppression()
      }
    }
  }

  private companion object {
    const val TAG = "JarvisRingAlert"
    const val MAXIMUM_REMEMBERED_RING_IDS = 128
  }
}

/** Ring notices are websocket events, so retained reconnect state is never allowed to replay. */
internal fun isFreshRingNotice(occurredAt: Instant, now: Instant): Boolean {
  val age = Duration.between(occurredAt, now)
  return age >= MAXIMUM_RING_FUTURE_SKEW.negated() && age <= MAXIMUM_RING_AGE
}

private val MAXIMUM_RING_FUTURE_SKEW: Duration = Duration.ofSeconds(15)
private val MAXIMUM_RING_AGE: Duration = Duration.ofSeconds(90)
private const val RING_ALERT_CHANNEL_ID = "openclaw.ring.alerts"

/** Media cues are never an exception to Android ringer or Do Not Disturb policy. */
internal fun mayPlayRingAlert(
  speakerEnabled: Boolean,
  voiceBusy: Boolean,
  ringerMode: Int,
  interruptionFilter: Int,
): Boolean =
  speakerEnabled &&
    !voiceBusy &&
    ringerMode == AudioManager.RINGER_MODE_NORMAL &&
    interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

/** Mirrors Android's otherwise silent notification-drop conditions for deterministic tests. */
internal fun canDeliverRingNotification(
  sdkInt: Int,
  permissionGranted: Boolean,
  notificationsEnabled: Boolean,
  existingChannelImportance: Int?,
): Boolean =
  (sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted) &&
    notificationsEnabled &&
    existingChannelImportance != NotificationManager.IMPORTANCE_NONE

/** A silent Android notification accompanies the local chime and returns to the existing Jarvis home. */
internal class RingAlertNotifier(
  private val context: Context,
  private val permissionGranted: () -> Boolean = {
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  },
  private val notificationsEnabled: () -> Boolean = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
  private val channelImportance: () -> Int? = {
    context.getSystemService(NotificationManager::class.java)
      .getNotificationChannel(RING_ALERT_CHANNEL_ID)
      ?.importance
  },
  private val post: (String, Int, android.app.Notification) -> Unit = { tag, id, notification ->
    NotificationManagerCompat.from(context).notify(tag, id, notification)
  },
) {
  fun show(alert: RingAlert): Boolean {
    if (!canDeliverRingNotification(Build.VERSION.SDK_INT, permissionGranted(), notificationsEnabled(), channelImportance())) {
      Log.i(TAG, "notification blocked")
      return false
    }
    ensureChannel()
    val launchHome = PendingIntent.getActivity(
      context,
      alert.id.hashCode(),
      Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val cameraText = "Kamera: ${alert.cameraName}"
    val notification = NotificationCompat.Builder(context, RING_ALERT_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setCategory(NotificationCompat.CATEGORY_EVENT)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setContentTitle("Es klingelt an der Haustür")
      .setContentText(cameraText)
      .setStyle(NotificationCompat.BigTextStyle().bigText(cameraText))
      .setContentIntent(launchHome)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .build()
    return runCatching {
      post(alert.id, notificationId, notification)
    }.onFailure {
      Log.w(TAG, "notification failed id=${alert.id}")
    }.isSuccess
  }

  private fun ensureChannel() {
    notificationManager().createNotificationChannel(
      NotificationChannel(RING_ALERT_CHANNEL_ID, "Türklingel", NotificationManager.IMPORTANCE_HIGH).apply {
        setSound(null, null)
        enableVibration(false)
      },
    )
  }

  private fun notificationManager(): NotificationManager = context.getSystemService(NotificationManager::class.java)

  private companion object {
    const val TAG = "JarvisRingAlert"
    const val notificationId = 42
  }
}
