package ai.openclaw.app.home.timers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.openclaw.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Durable owner for an overdue Jarvis timer. A broadcast receiver starts this service and
 * returns immediately; the foreground lifetime, notification, and pulsed audio live here.
 */
class JarvisScheduleAlertService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mainHandler = Handler(Looper.getMainLooper())
  private lateinit var schedules: JarvisScheduleRepository

  override fun onCreate() {
    super.onCreate()
    schedules = JarvisScheduleRepository.get(applicationContext)
    schedules.setAlertController(
      PulsingScheduleAlertController(applicationContext) {
        // Reconciliation updates activeAlert after calling stop. Defer this check one main-loop
        // turn so a second simultaneously-due item can take ownership without service churn.
        mainHandler.post {
          if (schedules.activeAlert.value == null) stopSelf()
        }
      },
      playActive = false,
    )
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, notification("Timer wird aktiviert"))
    val id = intent?.getStringExtra(JarvisScheduleRepository.EXTRA_ID)
    val token = intent?.getStringExtra(JarvisScheduleRepository.EXTRA_DELIVERY_TOKEN)
    scope.launch {
      if (id != null) {
        schedules.deliver(id, token)
      } else {
        schedules.reschedulePending()
      }
      val due = schedules.activeAlert.value
      withContext(Dispatchers.Main) {
        if (due == null) {
          stopSelf(startId)
        } else {
          startForeground(NOTIFICATION_ID, notification("${due.name} ist fällig"))
          schedules.resumeDueAlert()
        }
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    if (::schedules.isInitialized) schedules.setAlertController(SilentScheduleAlertController, playActive = false)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      // A new channel id deliberately migrates installs that already created the former HIGH
      // importance channel. The in-app panel and the service-owned pulse are the alert; this
      // is only the Android-required persistent service indicator.
      NotificationChannel(CHANNEL_ID, "JARVIS Timer", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Fällige JARVIS Timer und Wecker"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
      },
    )
  }

  private fun notification(text: String) =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("JARVIS Timer")
      .setContentText(text)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()

  companion object {
    private const val CHANNEL_ID = "jarvis_schedule_alerts_v2"
    private const val NOTIFICATION_ID = 4_214

    fun startDue(context: Context, id: String, token: String?) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, JarvisScheduleAlertService::class.java)
          .setAction("ai.openclaw.app.action.JARVIS_SCHEDULE_ALERT")
          .putExtra(JarvisScheduleRepository.EXTRA_ID, id)
          .putExtra(JarvisScheduleRepository.EXTRA_DELIVERY_TOKEN, token),
      )
    }

    fun startResume(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, JarvisScheduleAlertService::class.java))
    }
  }
}
