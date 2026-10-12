@file:Suppress("DEPRECATION")

package ai.openclaw.app.update

import ai.openclaw.app.NodeForegroundService
import ai.openclaw.app.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Private-LAN, signed APK updater. The platform remains the final installation authority. */
object JarvisAppUpdateManager {
  private const val UPDATE_DIRECTORY = "jarvis-updates"
  private const val PREFERENCES = "jarvis-app-updates"
  private const val PENDING_UPDATE = "pending-update"
  private const val AUTOMATIC_ATTEMPT = "automatic-attempt"
  private const val ACTIVE_INSTALL_SESSION = "active-install-session"
  private const val PENDING_USER_ACTION = "pending-user-action"
  private const val CHECK_INTERVAL_MS = 15L * 60L * 1_000L
  private const val CHANNEL_ID = "jarvis.app.updates"
  private const val NOTIFICATION_ID = 41
  private const val INSTALL_STATUS_ACTION = "ai.openclaw.app.update.INSTALL_STATUS"
  private const val EXTRA_INSTALL_SESSION_ID = "jarvis-update-session-id"
  private const val EXTRA_AUTOMATIC_INSTALL = "jarvis-update-automatic"
  private const val EXTRA_UPDATE_VERSION_CODE = "jarvis-update-version-code"

  private val lock = Mutex()
  /** PackageInstaller sessions are process-wide; foreground taps and the polling loop share this guard. */
  private val sessionLock = Any()
  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
  @Volatile private var job: Job? = null
  @Volatile private var busy: (() -> Boolean)? = null

  fun start(
    context: Context,
    scope: CoroutineScope,
    isBusy: () -> Boolean,
  ) {
    busy = isBusy
    if (job?.isActive == true) return
    job = scope.launch(Dispatchers.IO) {
      while (true) {
        checkForUpdate(context.applicationContext, isBusy)
        delay(CHECK_INTERVAL_MS)
      }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
    busy = null
  }

  internal suspend fun checkNow(context: Context): JarvisAppUpdate? =
    checkForUpdate(context.applicationContext, busy ?: { false })

  private suspend fun checkForUpdate(
    context: Context,
    isBusy: () -> Boolean,
  ): JarvisAppUpdate? = lock.withLock {
    pruneObsoleteUpdateArtifacts(context)
    if (isBusy()) return null
    // Public builds ship with no embedded OTA trust key.
    val publicKey = ByteArray(0)
    if (publicKey.isEmpty()) return@withLock null
    val envelope = downloadText() ?: return null
    val update = parseVerifiedJarvisUpdate(envelope, publicKey) ?: return null
    val existing = readPending(context)
    val existingFile = updateFile(context, update.versionCode)
    val useCachedArtifact =
      existing?.sameArtifact(update) == true &&
        existingFile.isFile &&
        sha256(existingFile) == update.sha256 &&
        archiveMatchesInstalledApp(context, existingFile, update)
    val artifact = if (useCachedArtifact) existingFile else downloadApk(context, update) ?: return null
    if (!archiveMatchesInstalledApp(context, artifact, update)) {
      artifact.delete()
      clearPending(context)
      cancelReadyNotification(context)
      return null
    }
    if (!useCachedArtifact) persist(context, update)
    // Recreate the persistent retry affordance after an Android notification-service restart.
    postReadyNotification(context, update)
    tryAutomaticInstall(context, artifact, update, isBusy)
    update
  }

  /** Called by the notification activity only; do not interrupt an active Jarvis interaction. */
  fun installAvailable(context: Context): InstallRequestResult {
    val appContext = context.applicationContext
    return synchronized(sessionLock) {
      if (busy?.invoke() == true) return@synchronized InstallRequestResult.DeferredBusy
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
        return@synchronized InstallRequestResult.NeedsUnknownSources
      }
      val pending = readPending(appContext) ?: return@synchronized InstallRequestResult.NoVerifiedUpdate
      if (reconcilePendingUserAction(appContext, pending) != null) return@synchronized InstallRequestResult.PendingUserAction
      if (reconcileActiveInstallSession(appContext, pending) != null) return@synchronized InstallRequestResult.AwaitingPlatform
      val file = updateFile(appContext, pending.versionCode)
      if (!file.isFile || !archiveMatchesInstalledApp(appContext, file, pending) || sha256(file) != pending.sha256) {
        clearPending(appContext)
        file.delete()
        cancelReadyNotification(appContext)
        return@synchronized InstallRequestResult.NoVerifiedUpdate
      }
      if (busy?.invoke() == true) return@synchronized InstallRequestResult.DeferredBusy
      runCatching {
        requestPlatformInstall(appContext, file, pending, automatic = false) { sessionId ->
          persistActiveInstallSession(appContext, pending, sessionId, automatic = false)
        }
        InstallRequestResult.Started
      }.getOrElse {
        Log.w("JarvisUpdate", "Could not start update installer", it)
        InstallRequestResult.Failed
      }
    }
  }

  private fun downloadText(): String? =
    runCatching {
      val request = Request.Builder().url(JARVIS_UPDATE_MANIFEST_URL).get().build()
      http.newCall(request).execute().use { response ->
        if (!response.isSuccessful || response.header("Location") != null) return null
        response.body.string().takeIf { it.length <= 32 * 1024 }
      }
    }.getOrNull()

  private fun downloadApk(context: Context, update: JarvisAppUpdate): File? =
    runCatching {
      val target = updateFile(context, update.versionCode)
      val temporary = File(target.parentFile, "${target.name}.part")
      check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
      temporary.delete()
      val request = Request.Builder().url(update.apkUrl).get().build()
      http.newCall(request).execute().use { response ->
        if (!response.isSuccessful || response.header("Location") != null) return null
        val expectedLength = response.body.contentLength()
        if (expectedLength >= 0 && expectedLength != update.sizeBytes) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        response.body.byteStream().use { input ->
          temporary.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val size = input.read(buffer)
              if (size < 0) break
              written += size
              if (written > update.sizeBytes) return null
              digest.update(buffer, 0, size)
              output.write(buffer, 0, size)
            }
            output.flush()
          }
        }
        if (written != update.sizeBytes || digest.digest().toHex() != update.sha256) {
          temporary.delete()
          return null
        }
        target.delete()
        if (!temporary.renameTo(target)) {
          temporary.delete()
          return null
        }
      }
      target
    }.getOrNull()

  private fun tryAutomaticInstall(
    context: Context,
    apk: File,
    update: JarvisAppUpdate,
    isBusy: () -> Boolean,
  ) {
    synchronized(sessionLock) {
      if (reconcileActiveInstallSession(context, update) != null) return@synchronized
      val decision =
        automaticUpdateInstallDecision(
          sdkInt = Build.VERSION.SDK_INT,
          canRequestPackageInstalls =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls(),
          busy = isBusy(),
          alreadyAttemptedArtifact = reconcileAutomaticAttempt(context, update),
        )
      if (decision != AutomaticUpdateInstallDecision.Start) return@synchronized
      // Persist the artifact marker before opening a session. A crash cannot turn a completed
      // automatic attempt into a new install session on the next fifteen-minute poll.
      persistAutomaticAttempt(context, update, sessionId = null)
      var createdSessionId: Int? = null
      runCatching {
        requestPlatformInstall(context, apk, update, automatic = true, abortIfBusy = isBusy) { sessionId ->
          createdSessionId = sessionId
          persistAutomaticAttempt(context, update, sessionId)
          persistActiveInstallSession(context, update, sessionId, automatic = true)
        }
      }.onSuccess { committed ->
        if (!committed) {
          clearActiveInstallSession(context, update.versionCode, createdSessionId)
          clearAutomaticAttempt(context, update.versionCode, createdSessionId)
        }
      }.onFailure { error ->
        // A commit failure can be ambiguous after PackageInstaller accepted the session. Keep
        // both records and let getMySessions reconcile them instead of retrying a second APK.
        Log.w("JarvisUpdate", "Could not start automatic APK update", error)
      }
    }
  }

  private fun requestPlatformInstall(
    context: Context,
    apk: File,
    update: JarvisAppUpdate,
    automatic: Boolean,
    abortIfBusy: (() -> Boolean)? = null,
    onSessionCreated: ((Int) -> Unit)? = null,
  ): Boolean {
    val installer = context.packageManager.packageInstaller
    val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(update.packageName)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRequireUserAction(
          if (automatic) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
          else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
        )
      }
    }
    val sessionId = installer.createSession(parameters)
    var committed = false
    try {
      onSessionCreated?.invoke(sessionId)
      installer.openSession(sessionId).use { session ->
        apk.inputStream().buffered().use { input ->
          session.openWrite("base.apk", 0, update.sizeBytes).use { output ->
            input.copyTo(output)
            session.fsync(output)
          }
        }
        if (abortIfBusy?.invoke() == true) return@use
        val status = PendingIntent.getBroadcast(
          context,
          sessionId,
          Intent(context, JarvisAppUpdateInstallReceiver::class.java)
            .setAction(INSTALL_STATUS_ACTION)
            .putExtra(EXTRA_INSTALL_SESSION_ID, sessionId)
            .putExtra(EXTRA_AUTOMATIC_INSTALL, automatic)
            .putExtra(EXTRA_UPDATE_VERSION_CODE, update.versionCode),
          installStatusPendingIntentFlags(),
        )
        session.commit(status.intentSender)
        committed = true
      }
    } catch (error: Throwable) {
      installer.abandonSession(sessionId)
      throw error
    }
    if (!committed) installer.abandonSession(sessionId)
    return committed
  }

  internal fun onInstallStatus(context: Context, intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC_INSTALL, false)
    val versionCode = intent.getLongExtra(EXTRA_UPDATE_VERSION_CODE, -1L)
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
      val pending = readPending(context)
      val sessionId = intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, -1)
      val active = readActiveInstallSession(context)
      if (
        !pendingUserActionMatchesCurrent(
          pendingVersionCode = pending?.versionCode,
          callbackVersionCode = versionCode,
          activeVersionCode = active?.versionCode,
          callbackSessionId = sessionId,
          activeSessionId = active?.sessionId,
        ) || !matchesActiveInstallSession(context, versionCode, sessionId)
      ) {
        // A delayed callback from an older installer session must never replace the current
        // artifact's confirmation affordance or surface an unrelated system confirmation.
        Log.i("JarvisUpdate", "Ignoring stale pending-user-action (version=$versionCode session=$sessionId)")
        pending?.let { postReadyNotification(context, it) }
        return
      }
      if (!automatic && confirmation != null) {
        // The user deliberately tapped the update notification, so preserve the existing
        // immediate confirmation flow. Background polls never take this branch.
        runCatching { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
      } else if (confirmation != null && pending != null) {
        persistPendingUserAction(context, pending, sessionId, confirmation)
        postPendingUserActionNotification(context, pending, confirmation)
      } else {
        readPending(context)?.let { postReadyNotification(context, it) }
      }
      Log.i("JarvisUpdate", "APK update awaits user action (automatic=$automatic)")
      return
    }
    if (status == PackageInstaller.STATUS_SUCCESS) {
      val pending = readPending(context)
      if (pending?.versionCode == versionCode) {
        val sessionId = intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, -1).takeIf { it >= 0 }
        clearAutomaticAttempt(context, versionCode, sessionId)
        clearActiveInstallSession(context, versionCode, sessionId)
        clearPendingUserAction(context, versionCode, sessionId)
        updateFile(context, pending.versionCode).delete()
        clearPending(context)
        cancelReadyNotification(context)
        Log.i("JarvisUpdate", "APK update installed")
      } else {
        // An older session may finish after a newer signed artifact is cached. Its success
        // cannot consume the newer update's retry state or notification.
        Log.i("JarvisUpdate", "APK update installed for stale version $versionCode")
      }
    } else {
      Log.w("JarvisUpdate", "APK update rejected by platform: $status")
      clearActiveInstallSession(context, versionCode, intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, -1).takeIf { it >= 0 })
      clearPendingUserAction(context, versionCode, intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, -1).takeIf { it >= 0 })
      // Keep the automatic-attempt marker: a failed unattended install must not create a new
      // session on every poll. The visible notification remains the deliberate retry path.
      readPending(context)?.let { postReadyNotification(context, it) }
    }
  }

  private fun postReadyNotification(context: Context, update: JarvisAppUpdate) {
    reconcilePendingUserAction(context, update)?.let { pendingUserAction ->
      postPendingUserActionNotification(context, update, pendingUserAction.confirmation)
      return
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Jarvis-Updates", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val content = PendingIntent.getActivity(
      context,
      0,
      Intent(context, JarvisAppUpdateInstallActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Jarvis-Update bereit")
      .setContentText("Version ${update.versionName} installieren")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(content)
      .build()
    manager.notify(NOTIFICATION_ID, notification)
  }

  private fun postPendingUserActionNotification(
    context: Context,
    update: JarvisAppUpdate,
    confirmation: Intent,
  ) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Jarvis-Updates", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val content = PendingIntent.getActivity(
      context,
      update.versionCode.toInt(),
      confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    manager.notify(
      NOTIFICATION_ID,
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Jarvis-Update bestätigen")
        .setContentText("Version ${update.versionName} installieren")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(content)
        .build(),
    )
  }

  private fun cancelReadyNotification(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
  }

  private fun persist(context: Context, update: JarvisAppUpdate) {
    val value =
      updateJson.encodeToString(
        JsonObject.serializer(),
        JsonObject(
          mapOf(
            "packageName" to JsonPrimitive(update.packageName),
            "versionCode" to JsonPrimitive(update.versionCode),
            "versionName" to JsonPrimitive(update.versionName),
            "apkUrl" to JsonPrimitive(update.apkUrl),
            "sha256" to JsonPrimitive(update.sha256),
            "sizeBytes" to JsonPrimitive(update.sizeBytes),
          ),
        ),
      )
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(PENDING_UPDATE, value).apply()
  }

  private fun readPending(context: Context): JarvisAppUpdate? {
    val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(PENDING_UPDATE, null) ?: return null
    val root = runCatching { updateJson.parseToJsonElement(value).jsonObject }.getOrNull() ?: return null
    val packageName = root.stringOrNull("packageName") ?: return null
    val versionCode = root.stringOrNull("versionCode")?.toLongOrNull() ?: return null
    val versionName = root.stringOrNull("versionName") ?: return null
    val apkUrl = root.stringOrNull("apkUrl") ?: return null
    val sha256 = root.stringOrNull("sha256") ?: return null
    val sizeBytes = root.stringOrNull("sizeBytes")?.toLongOrNull() ?: return null
    return JarvisAppUpdate(packageName, versionCode, versionName, apkUrl, sha256, sizeBytes, emptySet())
  }

  private fun clearPending(context: Context) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(PENDING_UPDATE).apply()
  }

  private fun persistActiveInstallSession(
    context: Context,
    update: JarvisAppUpdate,
    sessionId: Int,
    automatic: Boolean,
  ) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(
        ACTIVE_INSTALL_SESSION,
        updateJson.encodeToString(
          JsonObject.serializer(),
          JsonObject(
            mapOf(
              "versionCode" to JsonPrimitive(update.versionCode),
              "sessionId" to JsonPrimitive(sessionId),
              "automatic" to JsonPrimitive(automatic),
            ),
          ),
        ),
      )
      .apply()
  }

  /** Reconciles a process-death-safe session record before creating a new installer session. */
  private fun reconcileActiveInstallSession(context: Context, update: JarvisAppUpdate): ActiveInstallSession? {
    val active = readActiveInstallSession(context) ?: return null
    val query = runCatching {
      context.packageManager.packageInstaller.mySessions.any { session ->
        session.sessionId == active.sessionId && session.appPackageName == update.packageName
      }
    }
    if (installerSessionLiveness(query.isSuccess, query.getOrDefault(false)) == InstallerSessionLiveness.Absent) {
      clearActiveInstallSession(context, active.versionCode, active.sessionId)
      return null
    }
    // A newly published manifest must wait for an already committed PackageInstaller session.
    // Clearing it just because its version differs would permit a parallel package install.
    return active
  }

  private fun matchesActiveInstallSession(context: Context, versionCode: Long, sessionId: Int): Boolean {
    if (sessionId < 0) return false
    val active = readActiveInstallSession(context)
    return active?.versionCode == versionCode && active.sessionId == sessionId &&
      runCatching {
        context.packageManager.packageInstaller.mySessions.any { session ->
          session.sessionId == sessionId && session.appPackageName == context.packageName
        }
      }.getOrDefault(false)
  }

  private fun readActiveInstallSession(context: Context): ActiveInstallSession? {
    val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ACTIVE_INSTALL_SESSION, null) ?: return null
    val root = runCatching { updateJson.parseToJsonElement(value).jsonObject }.getOrNull() ?: return null
    if (root.keys != setOf("versionCode", "sessionId", "automatic")) return null
    val versionCode = root.stringOrNull("versionCode")?.toLongOrNull() ?: return null
    val sessionId = root.stringOrNull("sessionId")?.toIntOrNull() ?: return null
    val automatic = root.stringOrNull("automatic")?.toBooleanStrictOrNull() ?: return null
    return ActiveInstallSession(versionCode, sessionId, automatic)
  }

  private fun clearActiveInstallSession(context: Context, onlyVersionCode: Long? = null, sessionId: Int? = null) {
    val existing = readActiveInstallSession(context)
    if (onlyVersionCode != null && existing?.versionCode != onlyVersionCode) return
    if (sessionId != null && existing?.sessionId != sessionId) return
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(ACTIVE_INSTALL_SESSION).apply()
  }

  private fun persistPendingUserAction(
    context: Context,
    update: JarvisAppUpdate,
    sessionId: Int,
    confirmation: Intent,
  ) {
    if (sessionId < 0) return
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(
        PENDING_USER_ACTION,
        updateJson.encodeToString(
          JsonObject.serializer(),
          JsonObject(
            mapOf(
              "versionCode" to JsonPrimitive(update.versionCode),
              "sessionId" to JsonPrimitive(sessionId),
              "intentUri" to JsonPrimitive(confirmation.toUri(0)),
            ),
          ),
        ),
      )
      .apply()
  }

  private fun readPendingUserAction(context: Context, update: JarvisAppUpdate): PendingUserAction? {
    val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(PENDING_USER_ACTION, null) ?: return null
    val root = runCatching { updateJson.parseToJsonElement(value).jsonObject }.getOrNull() ?: return null
    if (root.keys != setOf("versionCode", "sessionId", "intentUri")) return null
    val versionCode = root.stringOrNull("versionCode")?.toLongOrNull() ?: return null
    val sessionId = root.stringOrNull("sessionId")?.toIntOrNull() ?: return null
    val intentUri = root.stringOrNull("intentUri") ?: return null
    if (versionCode != update.versionCode) return null
    val confirmation = runCatching { Intent.parseUri(intentUri, 0) }.getOrNull() ?: return null
    return PendingUserAction(versionCode, sessionId, confirmation)
  }

  /**
   * Confirmation intents are only resumable while their exact installer session still exists.
   * A PackageInstaller query failure is deliberately not treated as absence: retaining the
   * notification is safer than creating a duplicate manual update session.
   */
  private fun reconcilePendingUserAction(context: Context, update: JarvisAppUpdate): PendingUserAction? {
    val userAction = readPendingUserAction(context, update) ?: return null
    val query = runCatching {
      context.packageManager.packageInstaller.mySessions.any { session ->
        session.sessionId == userAction.sessionId && session.appPackageName == update.packageName
      }
    }
    if (installerSessionLiveness(query.isSuccess, query.getOrDefault(false)) == InstallerSessionLiveness.Absent) {
      clearPendingUserAction(context, update.versionCode, userAction.sessionId)
      clearActiveInstallSession(context, update.versionCode, userAction.sessionId)
      return null
    }
    return userAction
  }

  fun launchPendingUserAction(context: Context): Boolean {
    val pending = readPending(context.applicationContext) ?: return false
    val confirmation = reconcilePendingUserAction(context.applicationContext, pending)?.confirmation ?: return false
    return runCatching {
      context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    }.getOrDefault(false)
  }

  private fun clearPendingUserAction(context: Context, onlyVersionCode: Long? = null, sessionId: Int? = null) {
    val pending = readPending(context) ?: return
    val existing = readPendingUserAction(context, pending)
    if (onlyVersionCode != null && existing?.versionCode != onlyVersionCode) return
    if (sessionId != null && existing?.sessionId != sessionId) return
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(PENDING_USER_ACTION).apply()
  }

  /** True means this exact signed artifact was already handed to PackageInstaller. */
  private fun reconcileAutomaticAttempt(context: Context, update: JarvisAppUpdate): Boolean {
    val attempt = readAutomaticAttempt(context) ?: return false
    if (!attempt.sameArtifact(update)) {
      clearAutomaticAttempt(context)
      return false
    }
    attempt.sessionId?.let { sessionId ->
      val active = runCatching {
        context.packageManager.packageInstaller.mySessions.any { session ->
          session.sessionId == sessionId && session.appPackageName == update.packageName
        }
      }.getOrDefault(false)
      Log.i("JarvisUpdate", "Automatic update attempt already recorded (session=$sessionId active=$active)")
    }
    return true
  }

  private fun persistAutomaticAttempt(context: Context, update: JarvisAppUpdate, sessionId: Int?) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(
        AUTOMATIC_ATTEMPT,
        updateJson.encodeToString(
          JsonObject.serializer(),
          JsonObject(
            buildMap {
              put("versionCode", JsonPrimitive(update.versionCode))
              put("sha256", JsonPrimitive(update.sha256))
              sessionId?.let { put("sessionId", JsonPrimitive(it)) }
            },
          ),
        ),
      )
      .apply()
  }

  private fun readAutomaticAttempt(context: Context): AutomaticUpdateAttempt? {
    val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(AUTOMATIC_ATTEMPT, null) ?: return null
    val root = runCatching { updateJson.parseToJsonElement(value).jsonObject }.getOrNull() ?: return null
    if ((root.keys - setOf("versionCode", "sha256", "sessionId")).isNotEmpty()) return null
    val versionCode = root.stringOrNull("versionCode")?.toLongOrNull() ?: return null
    val sha256 = root.stringOrNull("sha256") ?: return null
    val sessionId = root.stringOrNull("sessionId")?.toIntOrNull()
    return AutomaticUpdateAttempt(versionCode, sha256, sessionId)
  }

  private fun clearAutomaticAttempt(context: Context, onlyVersionCode: Long? = null, sessionId: Int? = null) {
    val existing = readAutomaticAttempt(context)
    if (onlyVersionCode != null && existing?.versionCode != onlyVersionCode) return
    if (sessionId != null && existing?.sessionId != sessionId) return
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(AUTOMATIC_ATTEMPT).apply()
  }

  private fun updateFile(context: Context, versionCode: Long): File = File(File(context.filesDir, UPDATE_DIRECTORY), "$versionCode.apk")

  private fun pruneObsoleteUpdateArtifacts(context: Context) {
    val installedVersionCode =
      runCatching { packageVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)) }.getOrNull()
        ?: return
    val removed = pruneInstalledJarvisUpdateArtifacts(File(context.filesDir, UPDATE_DIRECTORY), installedVersionCode)
    if (removed.isNotEmpty()) Log.i("JarvisUpdate", "Removed ${removed.size} obsolete cached APK(s)")
  }
}

/** Removes only completed, numeric APK cache entries older than the installed app. */
internal fun pruneInstalledJarvisUpdateArtifacts(directory: File, installedVersionCode: Long): List<File> =
  directory.listFiles().orEmpty().filter { candidate ->
    candidate.isFile &&
      candidate.name.removeSuffix(".apk").toLongOrNull()?.let { versionCode ->
        candidate.name == "$versionCode.apk" && versionCode < installedVersionCode
      } == true &&
      candidate.delete()
  }

internal fun installStatusPendingIntentFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int =
  PendingIntent.FLAG_UPDATE_CURRENT or if (sdkInt >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

enum class InstallRequestResult { Started, PendingUserAction, AwaitingPlatform, DeferredBusy, NeedsUnknownSources, NoVerifiedUpdate, Failed }

internal enum class AutomaticUpdateInstallDecision { Start, UnsupportedPlatform, UnknownSourcesDisabled, Busy, AlreadyAttempted }

internal fun automaticUpdateInstallDecision(
  sdkInt: Int,
  canRequestPackageInstalls: Boolean,
  busy: Boolean,
  alreadyAttemptedArtifact: Boolean,
): AutomaticUpdateInstallDecision =
  when {
    sdkInt < Build.VERSION_CODES.S -> AutomaticUpdateInstallDecision.UnsupportedPlatform
    !canRequestPackageInstalls -> AutomaticUpdateInstallDecision.UnknownSourcesDisabled
    busy -> AutomaticUpdateInstallDecision.Busy
    alreadyAttemptedArtifact -> AutomaticUpdateInstallDecision.AlreadyAttempted
    else -> AutomaticUpdateInstallDecision.Start
  }

/** A PackageInstaller callback may only own the currently cached artifact and its live session. */
internal fun pendingUserActionMatchesCurrent(
  pendingVersionCode: Long?,
  callbackVersionCode: Long,
  activeVersionCode: Long?,
  callbackSessionId: Int,
  activeSessionId: Int?,
): Boolean =
  pendingVersionCode == callbackVersionCode &&
    activeVersionCode == callbackVersionCode &&
    callbackSessionId >= 0 &&
    activeSessionId == callbackSessionId

internal enum class InstallerSessionLiveness { Present, Absent, Unknown }

internal fun installerSessionLiveness(
  querySucceeded: Boolean,
  matchingSessionFound: Boolean,
): InstallerSessionLiveness =
  when {
    !querySucceeded -> InstallerSessionLiveness.Unknown
    matchingSessionFound -> InstallerSessionLiveness.Present
    else -> InstallerSessionLiveness.Absent
  }

private data class AutomaticUpdateAttempt(
  val versionCode: Long,
  val sha256: String,
  val sessionId: Int?,
) {
  fun sameArtifact(update: JarvisAppUpdate): Boolean = versionCode == update.versionCode && sha256 == update.sha256
}

private data class ActiveInstallSession(
  val versionCode: Long,
  val sessionId: Int,
  val automatic: Boolean,
)

private data class PendingUserAction(
  val versionCode: Long,
  val sessionId: Int,
  val confirmation: Intent,
)

class JarvisAppUpdateInstallActivity : android.app.Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var installJob: Job? = null
  private var launchedUnknownSourcesSettings = false

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) launchedUnknownSourcesSettings = savedInstanceState.getBoolean("unknown-sources-settings")
  }

  override fun onResume() {
    super.onResume()
    if (installJob?.isActive == true) return
    if (launchedUnknownSourcesSettings) {
      launchedUnknownSourcesSettings = false
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        finish()
        return
      }
    }
    installJob =
      scope.launch {
        val result = withContext(Dispatchers.IO) {
          JarvisAppUpdateManager.installAvailable(this@JarvisAppUpdateInstallActivity)
        }
        handleInstallRequestResult(result)
      }
  }

  private fun handleInstallRequestResult(result: InstallRequestResult) {
    when (result) {
      InstallRequestResult.NeedsUnknownSources -> {
        launchedUnknownSourcesSettings = true
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(android.net.Uri.parse("package:$packageName")))
      }

      InstallRequestResult.DeferredBusy -> {
        android.widget.Toast.makeText(this, "Nach dem Gespräch erneut versuchen", android.widget.Toast.LENGTH_SHORT).show()
        finish()
      }

      InstallRequestResult.PendingUserAction -> {
        JarvisAppUpdateManager.launchPendingUserAction(this)
        finish()
      }

      InstallRequestResult.AwaitingPlatform -> {
        android.widget.Toast.makeText(this, "Update wird vorbereitet", android.widget.Toast.LENGTH_SHORT).show()
        finish()
      }

      else -> finish()
    }
  }

  override fun onSaveInstanceState(outState: android.os.Bundle) {
    outState.putBoolean("unknown-sources-settings", launchedUnknownSourcesSettings)
    super.onSaveInstanceState(outState)
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}

class JarvisAppUpdateInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    JarvisAppUpdateManager.onInstallStatus(context.applicationContext, intent)
  }
}

class JarvisAppUpdateReplacementReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) NodeForegroundService.start(context.applicationContext)
  }
}

private fun archiveMatchesInstalledApp(context: Context, file: File, update: JarvisAppUpdate): Boolean {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    archiveMatchesInstalledAppPOrNewer(context, file, update)
  } else {
    archiveMatchesInstalledAppPreP(context, file, update)
  }
}

@android.annotation.TargetApi(Build.VERSION_CODES.P)
private fun archiveMatchesInstalledAppPOrNewer(context: Context, file: File, update: JarvisAppUpdate): Boolean {
  val manager = context.packageManager
  val flags = PackageManager.GET_SIGNING_CERTIFICATES
  val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
  if (!archiveMetadataMatchesUpdate(archive.packageName, archive.longVersionCode, update)) return false
  val installed = runCatching { manager.getPackageInfo(update.packageName, flags) }.getOrNull() ?: return false
  if (installed.longVersionCode >= update.versionCode) return false
  val archiveSigners = signingDigestSet(archive)
  val installedSigners = signingDigestSet(installed)
  if (archiveSigners.isNotEmpty()) return archiveSignerSetsMatchInstalledApp(
    sdkInt = Build.VERSION.SDK_INT,
    modernArchiveSigners = archiveSigners,
    modernInstalledSigners = installedSigners,
    legacyArchiveSigners = emptySet(),
    legacyInstalledSigners = emptySet(),
  )

  if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) {
    Log.w("JarvisUpdate", "archive rejected: no modern signer")
    return false
  }

  val legacyArchive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
  if (legacyArchive == null || legacyArchive.packageName != update.packageName || legacyArchive.longVersionCode != update.versionCode) {
    Log.w("JarvisUpdate", "archive rejected: legacy metadata")
    return false
  }
  val legacyInstalled = runCatching {
    manager.getPackageInfo(update.packageName, PackageManager.GET_SIGNATURES)
  }.getOrNull() ?: return false
  val matches = archiveSignerSetsMatchInstalledApp(
    sdkInt = Build.VERSION.SDK_INT,
    modernArchiveSigners = archiveSigners,
    modernInstalledSigners = installedSigners,
    legacyArchiveSigners = legacySigningDigestSet(legacyArchive),
    legacyInstalledSigners = legacySigningDigestSet(legacyInstalled),
  )
  if (!matches) Log.w("JarvisUpdate", "archive rejected: API 28 legacy signer")
  return matches
}

private fun archiveMatchesInstalledAppPreP(context: Context, file: File, update: JarvisAppUpdate): Boolean {
  val manager = context.packageManager
  val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES) ?: return false
  if (!archiveMetadataMatchesUpdate(archive.packageName, archive.versionCode.toLong(), update)) return false
  val installed = runCatching {
    manager.getPackageInfo(update.packageName, PackageManager.GET_SIGNATURES)
  }.getOrNull() ?: return false
  if (installed.versionCode.toLong() >= update.versionCode) return false
  val matches = archiveSignerSetsMatchInstalledApp(
    sdkInt = Build.VERSION.SDK_INT,
    modernArchiveSigners = emptySet(),
    modernInstalledSigners = emptySet(),
    legacyArchiveSigners = legacySigningDigestSet(archive),
    legacyInstalledSigners = legacySigningDigestSet(installed),
  )
  if (!matches) Log.w("JarvisUpdate", "archive rejected: pre-P legacy signer")
  return matches
}

internal fun archiveMetadataMatchesUpdate(
  archivePackageName: String,
  archiveVersionCode: Long,
  update: JarvisAppUpdate,
): Boolean = archivePackageName == update.packageName && archiveVersionCode == update.versionCode

internal fun archiveSignerSetsMatchInstalledApp(
  sdkInt: Int,
  modernArchiveSigners: Set<String>,
  modernInstalledSigners: Set<String>,
  legacyArchiveSigners: Set<String>,
  legacyInstalledSigners: Set<String>,
  archiveMetadataMatches: Boolean = true,
): Boolean {
  if (!archiveMetadataMatches) return false
  if (modernArchiveSigners.isNotEmpty()) {
    return modernArchiveSigners == modernInstalledSigners
  }
  return sdkInt <= Build.VERSION_CODES.O_MR1 &&
    legacyArchiveSigners.size == 1 &&
    legacyInstalledSigners.size == 1 &&
    legacyArchiveSigners == legacyInstalledSigners ||
    sdkInt == Build.VERSION_CODES.P &&
    modernInstalledSigners.size == 1 &&
    legacyArchiveSigners.size == 1 &&
    legacyInstalledSigners.size == 1 &&
    legacyArchiveSigners == legacyInstalledSigners &&
    legacyArchiveSigners == modernInstalledSigners
}

@android.annotation.TargetApi(Build.VERSION_CODES.P)
private fun signingDigestSet(info: PackageInfo): Set<String> =
  info.signingInfo?.apkContentsSigners?.map { signature -> sha256(signature.toByteArray()) }?.toSet().orEmpty()

private fun legacySigningDigestSet(info: PackageInfo): Set<String> =
  info.signatures?.map { signature -> sha256(signature.toByteArray()) }?.toSet().orEmpty()

private fun packageVersionCode(info: PackageInfo): Long =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageVersionCodePOrNewer(info) else info.versionCode.toLong()

@android.annotation.TargetApi(Build.VERSION_CODES.P)
private fun packageVersionCodePOrNewer(info: PackageInfo): Long = info.longVersionCode

private fun sha256(file: File): String = file.inputStream().use { input ->
  val digest = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  while (true) {
    val size = input.read(buffer)
    if (size < 0) break
    digest.update(buffer, 0, size)
  }
  digest.digest().toHex()
}

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun JarvisAppUpdate.sameArtifact(other: JarvisAppUpdate): Boolean =
  packageName == other.packageName &&
    versionCode == other.versionCode &&
    sha256 == other.sha256 &&
    sizeBytes == other.sizeBytes

private fun JsonObject.stringOrNull(name: String): String? =
  (this[name] as? JsonPrimitive)?.contentOrNull

private val updateJson = Json { ignoreUnknownKeys = false; isLenient = false }
