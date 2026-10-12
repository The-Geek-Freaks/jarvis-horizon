package ai.openclaw.app.home

import ai.openclaw.app.MainActivity
import ai.openclaw.app.R
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val actionOpenAlexaAnnouncement = "ai.openclaw.app.action.OPEN_ALEXA_ANNOUNCEMENT"
internal const val actionConsumeAlexaAnnouncement = "ai.openclaw.app.action.CONSUME_ALEXA_ANNOUNCEMENT"
private const val extraAlexaAnnouncementId = "ai.openclaw.app.extra.ALEXA_ANNOUNCEMENT_ID"
private const val extraAlexaAnnouncementSource = "ai.openclaw.app.extra.ALEXA_ANNOUNCEMENT_SOURCE"
private const val extraAlexaAnnouncementText = "ai.openclaw.app.extra.ALEXA_ANNOUNCEMENT_TEXT"
private const val extraAlexaAnnouncementLaunchToken = "ai.openclaw.app.extra.ALEXA_ANNOUNCEMENT_LAUNCH_TOKEN"
private const val announcementChannelId = "openclaw.alexa.announcements"
private const val announcementNotificationTagPrefix = "openclaw.alexa.announcement."
private const val announcementNotificationId = 41
private const val maximumQueuedAnnouncements = 16
private const val maximumRememberedAnnouncementIds = 128
private const val deferredSpeakTimeoutMs = 90_000L
private const val cardMinimumLifetimeMs = 30_000L
private const val cardCharactersPerSecond = 14L
private const val cardMaximumLifetimeMs = 90_000L

/**
 * Delivers a completed Alexa household announcement as content. It deliberately does not send
 * that text through an agent session: an announcement is not a Jarvis instruction.
 */
internal class AlexaAnnouncementController(
  private val scope: CoroutineScope,
  private val shouldAutoSpeak: () -> Boolean,
  private val speakerEnabled: () -> Boolean,
  private val isVoiceBusy: () -> Boolean,
  private val acquireWakeSuppression: () -> Unit,
  private val releaseWakeSuppression: () -> Unit,
  private val playChime: suspend () -> Unit,
  private val speak: suspend (String) -> Unit,
  private val publishNotification: (AlexaAnnouncement, Boolean) -> Unit,
  private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
  private val delayFor: suspend (Long) -> Unit = ::delay,
) {
  private val lock = Any()
  private val rememberedIds = LinkedHashSet<String>()
  private val queue = ArrayDeque<AlexaAnnouncement>()
  private val deliveryMutex = Mutex()
  private var worker: Job? = null
  private var expiry: Job? = null
  private val _announcement = MutableStateFlow<AlexaAnnouncement?>(null)
  val announcement: StateFlow<AlexaAnnouncement?> = _announcement.asStateFlow()

  fun receive(value: AlexaAnnouncement) {
    val sanitized = value.sanitized() ?: return
    synchronized(lock) {
      if (!rememberedIds.add(sanitized.id)) return
      while (rememberedIds.size > maximumRememberedAnnouncementIds) rememberedIds.remove(rememberedIds.first())
      if (queue.size >= maximumQueuedAnnouncements) {
        // Retain the newest household information instead of replacing the visible card.
        queue.removeFirst()
        Log.w(TAG, "queue overflow; oldest announcement dropped")
      }
      queue.addLast(sanitized)
      publishVisible(sanitized)
      runCatching { publishNotification(sanitized, shouldAutoSpeak()) }
        .onFailure { Log.w(TAG, "notification failed id=${sanitized.id}") }
      Log.i(TAG, "receive id=${sanitized.id}")
      if (worker?.isActive != true) worker = scope.launch { deliverQueued() }
    }
  }

  /** Notification content is trusted only as a local UI payload and is never routed to the model. */
  fun presentFromNotification(value: AlexaAnnouncement) {
    value.sanitized()?.let(::publishVisible)
  }

  fun dismiss() {
    expiry?.cancel()
    _announcement.value = null
  }

  fun replay() {
    val current = _announcement.value ?: return
    publishVisible(current)
    scope.launch {
      deliveryMutex.withLock {
        speakNow(current, deferForConversation = false)
      }
    }
  }

  private suspend fun deliverQueued() {
    while (true) {
      val next = synchronized(lock) {
        queue.removeFirstOrNull().also { next ->
          // Clear before releasing the lock so a concurrent receive cannot strand an item
          // behind this worker's final return.
          if (next == null) worker = null
        }
      } ?: return
      deliveryMutex.withLock {
        if (shouldAutoSpeak() && speakerEnabled()) {
          speakNow(next, deferForConversation = true)
        }
      }
    }
  }

  private suspend fun speakNow(
    value: AlexaAnnouncement,
    deferForConversation: Boolean,
  ) {
    if (!speakerEnabled()) return
    if (deferForConversation) {
      val deadline = elapsedRealtime() + deferredSpeakTimeoutMs
      while (isVoiceBusy() && elapsedRealtime() < deadline) delayFor(250)
      if (isVoiceBusy()) {
        Log.i(TAG, "defer-expired id=${value.id}")
        return
      }
    }
    // The existing TTS hooks pause the legacy microphone; this covers local wake recognition too.
    // Re-check after suppression so a live GPT-Live turn can never be interrupted by this notice.
    acquireWakeSuppression()
    try {
      if (isVoiceBusy()) {
        Log.i(TAG, "defer-busy id=${value.id}")
        return
      }
      try {
        playChime()
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        // A cosmetic cue must never suppress the household announcement itself.
        Log.w(TAG, "chime error id=${value.id}")
      }
      publishVisible(value)
      Log.i(TAG, "start id=${value.id}")
      speak(value.text)
      Log.i(TAG, "complete id=${value.id}")
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      Log.w(TAG, "error id=${value.id}")
    } finally {
      releaseWakeSuppression()
    }
  }

  private fun publishVisible(value: AlexaAnnouncement) {
    _announcement.value = value
    expiry?.cancel()
    val lifetime = (value.text.length.coerceAtLeast(1) * 1_000L / cardCharactersPerSecond)
      .coerceIn(cardMinimumLifetimeMs, cardMaximumLifetimeMs)
    expiry = scope.launch {
      delayFor(lifetime)
      if (_announcement.value?.id == value.id) _announcement.value = null
    }
  }

  private fun AlexaAnnouncement.sanitized(): AlexaAnnouncement? {
    val id = id.trim().takeIf(String::isNotEmpty) ?: return null
    val text = text.trim().takeIf(String::isNotEmpty) ?: return null
    return copy(id = id, sourceName = sourceName.trim().ifEmpty { sourceEntity }, text = text)
  }

  private companion object {
    const val TAG = "JarvisAnnouncement"
  }
}

internal fun alexaAnnouncementLaunchIntent(
  context: Context,
  announcement: AlexaAnnouncement,
): Intent =
  Intent(context, AlexaAnnouncementLaunchActivity::class.java)
    .setAction(actionOpenAlexaAnnouncement)
    .putExtra(extraAlexaAnnouncementId, announcement.id)
    .putExtra(extraAlexaAnnouncementSource, announcement.sourceName)
    .putExtra(extraAlexaAnnouncementText, announcement.text)

private fun parseAlexaAnnouncementTrampolineIntent(intent: Intent?): AlexaAnnouncement? {
  if (intent?.action != actionOpenAlexaAnnouncement) return null
  val id = intent.getStringExtra(extraAlexaAnnouncementId)?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
  val source = intent.getStringExtra(extraAlexaAnnouncementSource)?.trim().takeIf { !it.isNullOrEmpty() } ?: "Alexa"
  val text = intent.getStringExtra(extraAlexaAnnouncementText)?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
  return AlexaAnnouncement(id = id, sourceEntity = "notification", sourceName = source, text = text, occurredAt = java.time.Instant.now())
}

private object AlexaAnnouncementLaunchStore {
  private const val capacity = 32
  private val entries = LinkedHashMap<String, AlexaAnnouncement>()

  @Synchronized
  fun put(value: AlexaAnnouncement): String {
    val token = UUID.randomUUID().toString()
    while (entries.size >= capacity) entries.entries.iterator().run { next(); remove() }
    entries[token] = value
    return token
  }

  @Synchronized
  fun take(token: String?): AlexaAnnouncement? = token?.let(entries::remove)
}

internal fun parseAlexaAnnouncementLaunchIntent(intent: Intent?): AlexaAnnouncement? {
  if (intent?.action != actionConsumeAlexaAnnouncement) return null
  return AlexaAnnouncementLaunchStore.take(intent.getStringExtra(extraAlexaAnnouncementLaunchToken))
}

/** Private trampoline prevents another app from injecting notification body text into the exported MainActivity. */
class AlexaAnnouncementLaunchActivity : Activity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
      finish()
      return
    }
    val value = parseAlexaAnnouncementTrampolineIntent(intent)
    if (value != null) {
      startActivity(
        Intent(this, MainActivity::class.java)
          .setAction(actionConsumeAlexaAnnouncement)
          .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
          .putExtra(extraAlexaAnnouncementLaunchToken, AlexaAnnouncementLaunchStore.put(value)),
      )
    }
    finish()
  }
}

/** A private big-text notification accompanies hands-free playback on Jarvis wall terminals. */
internal class AlexaAnnouncementNotifier(private val context: Context) {
  fun show(value: AlexaAnnouncement, silent: Boolean) {
    if (!canPost()) {
      Log.i("JarvisAnnouncement", "notification blocked")
      return
    }
    ensureChannel(silent)
    val contentIntent = PendingIntent.getActivity(
      context,
      value.id.hashCode(),
      alexaAnnouncementLaunchIntent(context, value),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, if (silent) silentAnnouncementChannelId else announcementChannelId)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setCategory(NotificationCompat.CATEGORY_MESSAGE)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setContentTitle("Alexa-Ansage von ${value.sourceName}")
      .setContentText(value.text)
      .setSubText(if (silent) null else "Tippen zum Anhören")
      .setStyle(NotificationCompat.BigTextStyle().bigText(value.text))
      .setContentIntent(contentIntent)
      .setAutoCancel(true)
      .setOnlyAlertOnce(silent)
      .setSilent(silent)
      .build()
    context.getSystemService(NotificationManager::class.java)
      .notify(announcementNotificationTagPrefix + value.id, announcementNotificationId, notification)
  }

  private fun canPost(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

  private fun ensureChannel(silent: Boolean) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(
        if (silent) silentAnnouncementChannelId else announcementChannelId,
        if (silent) "Alexa-Ansagen auf Display" else "Alexa-Ansagen",
        if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        if (silent) {
          setSound(null, null)
          enableVibration(false)
        }
      },
    )
  }

  private companion object {
    const val silentAnnouncementChannelId = "openclaw.alexa.announcements.display"
  }
}
