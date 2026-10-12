package ai.openclaw.app.home.timers

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JarvisScheduleKind { Timer, Alarm }

enum class JarvisScheduleState { Scheduled, Paused, Due, Cancelled }

data class JarvisSchedule(
  val id: String,
  val kind: JarvisScheduleKind,
  val name: String,
  val dueAtEpochMs: Long,
  val state: JarvisScheduleState,
  val durationMs: Long? = null,
  /** Remaining timer duration captured by a durable pause. Null for alarms and running timers. */
  val pausedRemainingMs: Long? = null,
  /** Changes every time the platform delivery is (re)armed. */
  val deliveryToken: String = "",
)

/** The only audio owner for schedule alerts. Install a TalkMode-aware owner before starting voice audio. */
interface JarvisScheduleAlertController {
  fun play(schedule: JarvisSchedule)

  fun stop(scheduleId: String)
}

/** Pulsed alert audio. The foreground alert service installs this as the live owner. */
internal class PulsingScheduleAlertController(
  context: Context,
  private val afterStop: () -> Unit = {},
) : JarvisScheduleAlertController {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private var ringtone: Ringtone? = null
  private var playingId: String? = null
  private var pulse: Runnable? = null

  override fun play(schedule: JarvisSchedule) {
    if (playingId == schedule.id && pulse != null) return
    stop(playingId.orEmpty())
    playingId = schedule.id
    val repeatingPulse =
      object : Runnable {
        override fun run() {
          if (playingId != schedule.id) return
          ringtone?.stop()
          ringtone = RingtoneManager.getRingtone(appContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            ?.also { it.play() }
          mainHandler.postDelayed({
            if (playingId == schedule.id) ringtone?.stop()
          }, PULSE_SOUND_MS)
          mainHandler.postDelayed(this, PULSE_PERIOD_MS)
        }
      }
    pulse = repeatingPulse
    mainHandler.post(repeatingPulse)
  }

  override fun stop(scheduleId: String) {
    if (scheduleId.isNotBlank() && scheduleId != playingId) return
    pulse?.let(mainHandler::removeCallbacks)
    pulse = null
    ringtone?.stop()
    ringtone = null
    playingId = null
    afterStop()
  }

  private companion object {
    const val PULSE_SOUND_MS = 900L
    const val PULSE_PERIOD_MS = 4_900L
  }
}

internal object SilentScheduleAlertController : JarvisScheduleAlertController {
  override fun play(schedule: JarvisSchedule) = Unit

  override fun stop(scheduleId: String) = Unit
}

/**
 * Local owner for one-shot Jarvis timers and alarms. `at` input is either an ISO-8601 instant
 * with an offset (for example `2026-10-01T07:30:00+02:00`) or a local `HH:mm` time in the
 * device zone; a local time that has already passed means tomorrow.
 */
class JarvisScheduleRepository internal constructor(
  context: Context,
  databaseName: String = "jarvis-schedules.db",
) {
  private val appContext = context.applicationContext
  private val database = ScheduleDatabase(appContext, databaseName)
  private val alarmManager = checkNotNull(appContext.getSystemService(AlarmManager::class.java))
  private val lock = Any()
  // A receiver must hand audio ownership to JarvisScheduleAlertService. Keeping this silent
  // prevents a transient receiver process from starting a ringtone that it cannot keep alive.
  private var alerts: JarvisScheduleAlertController = SilentScheduleAlertController
  private var voiceActive = false
  private val _schedules = MutableStateFlow(readAll())
  val schedules: StateFlow<List<JarvisSchedule>> = _schedules.asStateFlow()
  private val _activeAlert = MutableStateFlow<JarvisSchedule?>(_schedules.value.firstOrNull { it.state == JarvisScheduleState.Due })
  val activeAlert: StateFlow<JarvisSchedule?> = _activeAlert.asStateFlow()

  init {
    reschedulePending()
    synchronized(lock) { reconcileAlert() }
  }

  /** Replaces the default ringtone owner; the old owner is stopped before the handoff. */
  fun setAlertController(controller: JarvisScheduleAlertController, playActive: Boolean = true) = synchronized(lock) {
    alerts.stop(_activeAlert.value?.id.orEmpty())
    alerts = controller
    if (playActive && !voiceActive) _activeAlert.value?.let(controller::play)
  }

  /** Call only after a foreground alert owner is running. */
  fun resumeDueAlert() = synchronized(lock) { reconcileAlert(forcePlay = true) }

  /**
   * Talk mode calls this before its acknowledgement/capture and after it returns idle.
   * An overdue timer stays visible and resumes exactly once after the spoken exchange.
   */
  fun setVoiceActive(active: Boolean) {
    synchronized(lock) {
      if (voiceActive == active) return@synchronized
      voiceActive = active
      if (active) {
        _activeAlert.value?.let { alerts.stop(it.id) }
      } else {
        reconcileAlert(forcePlay = true)
      }
    }
  }

  fun setTimer(durationMs: Long, name: String = "Timer", nowMs: Long = System.currentTimeMillis()): JarvisSchedule {
    require(durationMs in MIN_TIMER_MS..MAX_TIMER_MS) { "durationMs must be between 1 second and 24 hours" }
    return create(JarvisScheduleKind.Timer, name, nowMs + durationMs, durationMs)
  }

  fun setAlarm(at: String, name: String = "Alarm", nowMs: Long = System.currentTimeMillis()): JarvisSchedule =
    create(JarvisScheduleKind.Alarm, name, parseAlarmAt(at, nowMs), null)

  fun cancel(id: String): JarvisSchedule? = transition(id, JarvisScheduleState.Cancelled, null)

  fun stop(id: String): JarvisSchedule? = cancel(id)

  fun snooze(id: String, durationMs: Long = DEFAULT_SNOOZE_MS, nowMs: Long = System.currentTimeMillis()): JarvisSchedule? {
    require(durationMs in MIN_TIMER_MS..MAX_TIMER_MS) { "snoozeMs must be between 1 second and 24 hours" }
    synchronized(lock) {
      val current = find(id) ?: return null
      if (current.state != JarvisScheduleState.Due) return current
      cancelPlatform(current)
      val next = current.copy(
        dueAtEpochMs = nowMs + durationMs,
        state = JarvisScheduleState.Scheduled,
        deliveryToken = newDeliveryToken(),
      )
      write(next)
      schedule(next)
      reconcileAlert()
      return next
    }
  }

  /** Pauses only a running timer. Alarm clocks remain absolute-time events. */
  fun pause(id: String, nowMs: Long = System.currentTimeMillis()): JarvisSchedule? = synchronized(lock) {
    val current = find(id) ?: return null
    if (current.kind != JarvisScheduleKind.Timer || current.state != JarvisScheduleState.Scheduled) return current
    val remaining = (current.dueAtEpochMs - nowMs).coerceAtLeast(MIN_TIMER_MS)
    cancelPlatform(current)
    val paused = current.copy(state = JarvisScheduleState.Paused, pausedRemainingMs = remaining, deliveryToken = newDeliveryToken())
    write(paused)
    reconcileAlert()
    paused
  }

  /** Resumes a timer from its saved remaining duration and arms a fresh delivery token. */
  fun resume(id: String, nowMs: Long = System.currentTimeMillis()): JarvisSchedule? = synchronized(lock) {
    val current = find(id) ?: return null
    if (current.kind != JarvisScheduleKind.Timer || current.state != JarvisScheduleState.Paused) return current
    val remaining = current.pausedRemainingMs?.coerceIn(MIN_TIMER_MS, MAX_TIMER_MS) ?: return current
    val resumed = current.copy(dueAtEpochMs = nowMs + remaining, state = JarvisScheduleState.Scheduled, pausedRemainingMs = null, deliveryToken = newDeliveryToken())
    write(resumed)
    schedule(resumed)
    reconcileAlert()
    resumed
  }

  /** Adds time to a running or paused timer without claiming any external action occurred. */
  fun extend(id: String, durationMs: Long = DEFAULT_EXTEND_MS): JarvisSchedule? {
    require(durationMs in MIN_TIMER_MS..MAX_TIMER_MS) { "durationMs must be between 1 second and 24 hours" }
    return synchronized(lock) {
      val current = find(id) ?: return null
      if (current.kind != JarvisScheduleKind.Timer || current.state !in setOf(JarvisScheduleState.Scheduled, JarvisScheduleState.Paused)) return current
      val extended =
        if (current.state == JarvisScheduleState.Paused) {
          current.copy(pausedRemainingMs = (current.pausedRemainingMs ?: MIN_TIMER_MS).plus(durationMs).coerceAtMost(MAX_TIMER_MS))
        } else {
          cancelPlatform(current)
          current.copy(dueAtEpochMs = current.dueAtEpochMs + durationMs, deliveryToken = newDeliveryToken())
        }
      write(extended)
      if (extended.state == JarvisScheduleState.Scheduled) schedule(extended)
      reconcileAlert()
      extended
    }
  }

  /** Called by the manifest receiver. Repeated broadcasts do not restart audio or change state. */
  fun deliver(id: String, deliveryToken: String? = null): JarvisSchedule? = synchronized(lock) {
    val current = find(id) ?: return null
    // A cancelled platform broadcast from before a snooze has a different token.
    if (deliveryToken != null && deliveryToken != current.deliveryToken) return current
    if (current.state != JarvisScheduleState.Scheduled) return current
    val due = current.copy(state = JarvisScheduleState.Due)
    write(due)
    cancelPlatform(due)
    reconcileAlert()
    due
  }

  /** Reinstalls future alarms after boot/package replacement and delivers a missed one once. */
  fun reschedulePending(nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
    readAll().filter { it.state == JarvisScheduleState.Scheduled }.forEach { schedule ->
      val armed = if (schedule.deliveryToken.isBlank()) schedule.copy(deliveryToken = newDeliveryToken()).also(::write) else schedule
      if (armed.dueAtEpochMs <= nowMs) deliver(armed.id, armed.deliveryToken) else schedule(armed)
    }
    reconcileAlert()
  }

  private fun create(kind: JarvisScheduleKind, rawName: String, dueAt: Long, durationMs: Long?): JarvisSchedule = synchronized(lock) {
    require(dueAt > System.currentTimeMillis()) { "schedule must be in the future" }
    val schedule =
      JarvisSchedule(
        id = UUID.randomUUID().toString(),
        kind = kind,
        name = rawName.trim().ifBlank { kind.name },
        dueAtEpochMs = dueAt,
        state = JarvisScheduleState.Scheduled,
        durationMs = durationMs,
        deliveryToken = newDeliveryToken(),
      )
    write(schedule)
    schedule(schedule)
    reconcileAlert()
    schedule
  }

  private fun transition(id: String, state: JarvisScheduleState, dueAt: Long?): JarvisSchedule? = synchronized(lock) {
    val current = find(id) ?: return null
    if (current.state == state) return current
    val next = current.copy(state = state, dueAtEpochMs = dueAt ?: current.dueAtEpochMs)
    write(next)
    cancelPlatform(next)
    reconcileAlert()
    next
  }

  @SuppressLint("ScheduleExactAlarm")
  private fun schedule(value: JarvisSchedule) {
    val pending = pendingIntent(value)
    if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, value.dueAtEpochMs, pending)
    } else {
      // Exact-alarm access is user-controlled on Android 12+. The durable record remains exact;
      // this fallback preserves delivery when that access has not been granted.
      alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, value.dueAtEpochMs, pending)
    }
  }

  private fun cancelPlatform(value: JarvisSchedule) {
    val pending = pendingIntent(value)
    alarmManager.cancel(pending)
    pending.cancel()
  }

  private fun pendingIntent(value: JarvisSchedule): PendingIntent = PendingIntent.getBroadcast(
    appContext,
    value.id.hashCode(),
    Intent(appContext, JarvisScheduleReceiver::class.java)
      .setAction(ACTION_DUE)
      // PendingIntent identity includes data. The token prevents a stale pre-snooze
      // delivery from becoming the newly armed alarm.
      .setData(scheduleUri(value.id, value.deliveryToken))
      .putExtra(EXTRA_ID, value.id)
      .putExtra(EXTRA_DELIVERY_TOKEN, value.deliveryToken),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  private fun find(id: String): JarvisSchedule? = readAll().firstOrNull { it.id == id }

  private fun readAll(): List<JarvisSchedule> = database.readableDatabase.rawQuery(
    "SELECT id, kind, name, due_at, state, duration_ms, paused_remaining_ms, delivery_token FROM schedules ORDER BY due_at ASC", null,
  ).use { cursor ->
    buildList {
      while (cursor.moveToNext()) {
        add(
          JarvisSchedule(
            id = cursor.getString(0),
            kind = JarvisScheduleKind.valueOf(cursor.getString(1)),
            name = cursor.getString(2),
            dueAtEpochMs = cursor.getLong(3),
            state = JarvisScheduleState.valueOf(cursor.getString(4)),
            durationMs = if (cursor.isNull(5)) null else cursor.getLong(5),
            pausedRemainingMs = if (cursor.isNull(6)) null else cursor.getLong(6),
            deliveryToken = cursor.getString(7).orEmpty(),
          ),
        )
      }
    }
  }

  private fun write(value: JarvisSchedule) {
    database.writableDatabase.execSQL(
      "INSERT OR REPLACE INTO schedules(id, kind, name, due_at, state, duration_ms, paused_remaining_ms, delivery_token) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      arrayOf<Any?>(value.id, value.kind.name, value.name, value.dueAtEpochMs, value.state.name, value.durationMs, value.pausedRemainingMs, value.deliveryToken),
    )
  }

  private fun reconcileAlert(forcePlay: Boolean = false) {
    val all = readAll()
    _schedules.value = all
    val previous = _activeAlert.value
    val next = all.firstOrNull { it.state == JarvisScheduleState.Due }
    if (previous?.id != next?.id) previous?.let { alerts.stop(it.id) }
    _activeAlert.value = next
    if (!voiceActive && next != null && (forcePlay || previous?.id != next.id)) alerts.play(next)
  }

  private fun newDeliveryToken(): String = UUID.randomUUID().toString()

  private fun scheduleUri(id: String, token: String): Uri =
    Uri.Builder().scheme("jarvis").authority("schedule").appendPath(id).appendPath(token).build()

  companion object {
    const val ACTION_DUE = "ai.openclaw.app.action.JARVIS_SCHEDULE_DUE"
    const val EXTRA_ID = "schedule_id"
    const val EXTRA_DELIVERY_TOKEN = "schedule_delivery_token"
    const val MIN_TIMER_MS = 1_000L
    const val MAX_TIMER_MS = 24 * 60 * 60 * 1_000L
    const val DEFAULT_SNOOZE_MS = 10 * 60 * 1_000L
    const val DEFAULT_EXTEND_MS = 60 * 1_000L
    @Volatile private var instance: JarvisScheduleRepository? = null

    fun get(context: Context): JarvisScheduleRepository = instance ?: synchronized(this) {
      instance ?: JarvisScheduleRepository(context.applicationContext).also { instance = it }
    }
  }
}

class JarvisScheduleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == JarvisScheduleRepository.ACTION_DUE) {
      intent.getStringExtra(JarvisScheduleRepository.EXTRA_ID)?.let { id ->
        JarvisScheduleAlertService.startDue(
          context,
          id,
          intent.getStringExtra(JarvisScheduleRepository.EXTRA_DELIVERY_TOKEN),
        )
      }
    }
  }
}

class JarvisScheduleBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
      val schedules = JarvisScheduleRepository.get(context)
      schedules.reschedulePending()
      if (schedules.activeAlert.value != null) {
        // Android 15 blocks media-playback foreground services from BOOT_COMPLETED. The
        // supported Echo target is Android 11; leave the durable Due record visible there.
        if (Build.VERSION.SDK_INT >= 35 && intent.action == Intent.ACTION_BOOT_COMPLETED) {
          Log.w("JarvisSchedule", "Android 15 blocks boot-started media playback alerts")
        } else {
          JarvisScheduleAlertService.startResume(context)
        }
      }
    }
  }
}

private class ScheduleDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 3) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE schedules (id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, " +
        "name TEXT NOT NULL, due_at INTEGER NOT NULL, state TEXT NOT NULL, duration_ms INTEGER, paused_remaining_ms INTEGER, delivery_token TEXT NOT NULL)",
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) db.execSQL("ALTER TABLE schedules ADD COLUMN delivery_token TEXT NOT NULL DEFAULT ''")
    if (oldVersion < 3) db.execSQL("ALTER TABLE schedules ADD COLUMN paused_remaining_ms INTEGER")
  }
}

internal fun parseAlarmAt(value: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
  val raw = value.trim()
  runCatching { return Instant.parse(raw).toEpochMilli() }
  runCatching { return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
  val local = runCatching { LocalTime.parse(raw) }.getOrElse {
    throw IllegalArgumentException("at must be ISO-8601 with offset or local HH:mm")
  }
  val now = Instant.ofEpochMilli(nowMs).atZone(zone)
  var candidate = LocalDateTime.of(now.toLocalDate(), local).atZone(zone).toInstant().toEpochMilli()
  if (candidate <= nowMs) candidate = LocalDateTime.of(now.toLocalDate().plusDays(1), local).atZone(zone).toInstant().toEpochMilli()
  return candidate
}
