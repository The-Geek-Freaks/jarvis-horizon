package ai.openclaw.app.voice

import ai.openclaw.app.asJsonStringOrNull
import ai.openclaw.app.gateway.ChatSendAck
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.TalkSessionCancelOutputResult
import ai.openclaw.app.gateway.chatSendAckHistorySinceSeconds
import ai.openclaw.app.gateway.parseChatSendAck
import ai.openclaw.app.i18n.LocaleResolvingStateFlow
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.joinedNativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.resolveNativeText
import ai.openclaw.app.node.asObjectOrNull
import ai.openclaw.app.node.parseJsonParamsObject
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

data class TalkPttStartPayload(
  val captureId: String,
) {
  fun toJson(): String = """{"captureId":"$captureId"}"""
}

data class TalkPttStopPayload(
  val captureId: String,
  val transcript: String?,
  val status: String,
) {
  fun toJson(): String =
    buildJsonObject {
      put("captureId", JsonPrimitive(captureId))
      if (transcript != null) {
        put("transcript", JsonPrimitive(transcript))
      }
      put("status", JsonPrimitive(status))
    }.toString()
}

internal enum class JarvisToolPhase {
  Running,
  Completed,
  Failed,
  Unknown,
}

/** Privacy-preserving projection of a concrete Jarvis-agent tool. */
internal data class JarvisToolExecution(
  val toolCallId: String,
  val name: String,
  val phase: JarvisToolPhase,
  val changedAtMs: Long,
  val detail: String? = null,
  /** Monotonic timestamp observed only when this concrete tool actually started. */
  val startedAtMs: Long? = null,
)

internal sealed interface TalkPttOnceStart {
  data class Busy(
    val payload: TalkPttStopPayload,
  ) : TalkPttOnceStart

  data class Started(
    val captureId: String,
    val completion: CompletableDeferred<TalkPttStopPayload>,
  ) : TalkPttOnceStart
}

internal suspend fun requestPhoneRealtimeSessionWithLanguageFallback(
  language: String?,
  request: suspend (language: String?) -> String,
): String =
  try {
    request(language)
  } catch (err: GatewayRequestRejected) {
    if (language == null || !err.gatewayError.isUnsupportedSessionLanguageParam()) {
      throw err
    }
    request(null)
  }

private enum class TalkStatusState {
  Off,
  Active,
  TalkFailure,
}

private class TalkStatusOwner(
  val responseTurnId: String? = null,
)

private data class TalkStatus(
  val text: NativeText,
  val state: TalkStatusState,
  val awaitingAgent: Boolean = false,
  val owner: TalkStatusOwner = TalkStatusOwner(),
  val route: TalkModeRoute? = null,
  val failureAcknowledged: Boolean = false,
)

/** Identity is the failure's status owner, retained across its relay close event. */
internal data class TalkFailureNotice(
  val text: String,
  val owner: Any,
)

private data class TalkConfigCache(
  val value: TalkModeGatewayConfigState = TalkModeGatewayConfigParser.parse(null),
  val loaded: Boolean = false,
)

private class PushToTalkAudioSource(
  val readDescriptor: ParcelFileDescriptor,
  private val writeStream: ParcelFileDescriptor.AutoCloseOutputStream,
  private val audioRecord: AudioRecord,
) {
  private var finishRequested = false
  private var inputFinished = false
  private var descriptorClosed = false
  var pumpJob: Job? = null

  @Synchronized
  fun requestFinish() {
    if (finishRequested) return
    finishRequested = true
    runCatching { audioRecord.stop() }
  }

  // close() must wait for a pump already inside release, not just observe its claim.
  @Synchronized
  fun finishFromPump() {
    if (inputFinished) return
    inputFinished = true
    runCatching { audioRecord.stop() }
    runCatching { audioRecord.release() }
    runCatching { writeStream.close() }
  }

  @Synchronized
  fun close() {
    requestFinish()
    pumpJob?.cancel()
    finishFromPump()
    pumpJob = null
    if (!descriptorClosed) {
      descriptorClosed = true
      runCatching { readDescriptor.close() }
    }
  }
}

private sealed interface PushToTalkRecognitionRung {
  val candidate: PushToTalkRecognitionCandidate

  data class RawAudioSegmented(
    val source: PushToTalkAudioSource,
  ) : PushToTalkRecognitionRung {
    override val candidate = PushToTalkRecognitionCandidate.RawAudioSegmented
  }

  data object SilenceSegmented : PushToTalkRecognitionRung {
    override val candidate = PushToTalkRecognitionCandidate.SilenceSegmented
  }

  data object RestartingSingleSession : PushToTalkRecognitionRung {
    override val candidate = PushToTalkRecognitionCandidate.RestartingSingleSession
  }
}

/**
 * Holds microphone PCM captured while an already-owned wake seed is being appended.
 * The relay drains this queue before it admits live frames, so a user can speak as
 * soon as Android has opened the recorder without overtaking the retained wake tail.
 */
internal enum class RealtimeStartupPcmOffer {
  Buffered,
  Live,
  Overflow,
  Cancelled,
}

internal class RealtimeStartupPcmQueue<T>(
  private val maxFrames: Int,
) {
  private enum class State {
    Buffering,
    Draining,
    Live,
    Cancelled,
  }

  private val lock = Any()
  private val frames = ArrayDeque<T>()
  private var state = State.Buffering

  fun offer(frame: T): RealtimeStartupPcmOffer =
    synchronized(lock) {
      when (state) {
        State.Buffering,
        State.Draining,
        -> {
          if (frames.size >= maxFrames) RealtimeStartupPcmOffer.Overflow
          else {
            frames.addLast(frame)
            RealtimeStartupPcmOffer.Buffered
          }
        }

        State.Live -> RealtimeStartupPcmOffer.Live
        State.Cancelled -> RealtimeStartupPcmOffer.Cancelled
      }
    }

  fun beginDrain() {
    synchronized(lock) {
      if (state == State.Buffering) state = State.Draining
    }
  }

  fun takeForDrain(): T? =
    synchronized(lock) {
      if (state != State.Draining) return@synchronized null
      if (frames.isNotEmpty()) return@synchronized frames.removeFirst()
      state = State.Live
      null
    }

  fun cancel() {
    synchronized(lock) {
      frames.clear()
      state = State.Cancelled
    }
  }
}

class TalkModeManager internal constructor(
  private val context: Context,
  private val scope: CoroutineScope,
  private val session: GatewaySession,
  private val isConnected: () -> Boolean,
  private val gatewayStableId: () -> String? = { null },
  private val preferredAudioInputDevice: () -> String? = { null },
  private val onAppliedAudioInputChanged: (String?) -> Unit = {},
  private val onBeforeSpeak: suspend () -> Unit = {},
  private val onAfterSpeak: suspend () -> Unit = {},
  private val captureRelayStopNotification: () -> ((isCurrent: () -> Boolean) -> Unit) = { {} },
  private val talkSpeakClient: TalkSpeechSynthesizing = TalkSpeakClient(session = session),
  private val talkAudioPlayer: TalkAudioPlaying = TalkAudioPlayer(context),
  private val realtimeCaptureDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val realtimePlaybackDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val realtimeMarkAcknowledger: (suspend (sessionId: String, markName: String) -> Unit)? = null,
  private val resolveAgentSessionKey: (String) -> String = { "" },
  private val onFinalVoiceTranscript: (String) -> Unit = {},
  private val onVoiceAgentCompleted: () -> Unit = {},
  private val onDirectVoiceAssistantTranscriptCompleted: () -> Unit = {},
  private val onVoiceSpeechDrained: () -> Unit = {},
  private val onVoiceConversationClosed: () -> Unit = {},
  private val onFinalVoiceAssistantSegment: () -> Unit = {},
) {
  companion object {
    private const val tag = "TalkMode"
    private const val realtimeSampleRateHz = 24_000
    private const val realtimeAudioFrameMs = 100
    private const val realtimeAudioFrameBytes = realtimeSampleRateHz * 2 * realtimeAudioFrameMs / 1000
    private const val maxWakeSeedDurationMs = 12_000L
    // Provider-ready is bounded at 15s and retained wake PCM can take another 12s to
    // pace. Thirty seconds preserves that whole initial source without overflow.
    private const val maxWakeStartupFrames = 300
    private const val chatFinalWaitMs = 45_000L
    private const val maxCachedRunCompletions = 128
    private const val MAX_VISIBLE_TOOL_ACTIVITY = 4
    private const val MAX_TRACKED_TOOL_ACTIVITY = 8
    private const val TOOL_ACTIVITY_SETTLE_MILLIS = 9_000L
    private const val TOOL_ACTIVITY_STALE_MILLIS = JarvisConversationLifecycle.STALLED_TIMEOUT_MS
    private const val maxPendingRealtimeConsultRequests = 8
    // A provider can deliver the final PCM callbacks for one response after it has admitted
    // the next response. Keep a small, session-local ownership history so those callbacks
    // cannot be rebound to newer work.
    private const val maxRetainedRealtimeOutputOwners = 16
    private const val maxConversationEntries = 40
    private const val realtimeUserFinalRewriteGraceMs = 1_500L
    private const val pushToTalkSampleRateHz = 16_000
    private const val pushToTalkReleaseGraceMs = 5_000L
    private const val pushToTalkReleaseDrainTimeoutMs = 6_000L
    private const val pushToTalkRestartDelayMs = 200L
    private const val audiblePcmLevel = RealtimePlayout.AUDIBLE_LEVEL
    private const val audiblePcmHangoverMs = 320L
    // The Show 5 Gen2 has no usable AEC. Keep a bounded post-playhead tail so the first words
    // spoken after Jarvis finishes can be sent once room reverberation has settled.
    private const val noAecEchoTailMs = 900L
    private const val maxPostOutputTailFrames = 9

    internal fun shouldCaptureDuringRealtimePlayback(
      playbackAudible: Boolean,
      canCaptureDuringPlayback: Boolean,
    ): Boolean = !playbackAudible || canCaptureDuringPlayback
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private var gatewayWorkJob = SupervisorJob()
  private var gatewayWorkScope = CoroutineScope(scope.coroutineContext + gatewayWorkJob)
  private val gatewayGeneration = AtomicLong()
  private val configCache = AtomicReference(TalkConfigCache())
  private val configReloadMutex = Mutex()

  init {
    scope.coroutineContext[Job]?.invokeOnCompletion { gatewayWorkJob.cancel() }
  }

  private val json = Json { ignoreUnknownKeys = true }
  private val _isEnabled = MutableStateFlow(false)
  val isEnabled: StateFlow<Boolean> = _isEnabled

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  /**
   * True only after the active microphone has produced a live frame that the realtime relay may
   * admit. This deliberately differs from [isListening]: a no-AEC output, local wake receipt,
   * cancellation boundary, or capture replacement can leave the recorder/listener alive while
   * its PCM must not yet be accepted.
   */
  private val _isCaptureAdmitted = MutableStateFlow(false)
  val isCaptureAdmitted: StateFlow<Boolean> = _isCaptureAdmitted

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking

  private val _inputAudioLevel = MutableStateFlow(0f)
  val inputAudioLevel: StateFlow<Float> = _inputAudioLevel

  private val _outputAudioLevel = MutableStateFlow(0f)
  val outputAudioLevel: StateFlow<Float> = _outputAudioLevel

  private val playbackLock = Any()

  private val status = MutableStateFlow(TalkStatus(text = nativeText("Off"), state = TalkStatusState.Off))
  private val currentStatus: TalkStatus get() = status.value
  val statusText: StateFlow<String> =
    LocaleResolvingStateFlow(status) {
      joinedNativeText(" — ", listOfNotNull(it.text, it.route?.description)).resolveNativeText()
    }
  val awaitingAgent: StateFlow<Boolean> = LocaleResolvingStateFlow(status) { it.awaitingAgent }

  /** The current unacknowledged failure; dismissal leaves the diagnostic status intact. */
  internal val failureNotice: StateFlow<TalkFailureNotice?> =
    LocaleResolvingStateFlow(status) { status ->
      status
        .takeIf { it.state == TalkStatusState.TalkFailure && !it.failureAcknowledged }
        ?.let { TalkFailureNotice(it.text.resolveNativeText(), it.owner) }
    }

  private fun setStatus(
    text: NativeText,
    state: TalkStatusState = TalkStatusState.Active,
    awaitingAgent: Boolean = false,
    route: TalkModeRoute? = currentStatus.route,
  ) {
    setStatus(TalkStatus(text = text, state = state, awaitingAgent = awaitingAgent, route = route))
  }

  private fun setStatus(next: TalkStatus) {
    status.value = if (next.state == TalkStatusState.Active) next else next.copy(route = null)
  }

  private fun setTalkFailure(text: NativeText) {
    setStatus(text, state = TalkStatusState.TalkFailure)
  }

  /** Dismisses this failure across Chat recreation while retaining its terminal status. */
  internal fun acknowledgeFailure(notice: TalkFailureNotice) {
    synchronized(realtimeCapturePauseLock) {
      status.update { current ->
        if (current.state == TalkStatusState.TalkFailure && current.owner === notice.owner) {
          current.copy(failureAcknowledged = true)
        } else {
          current
        }
      }
    }
  }

  private val _conversation = MutableStateFlow<List<VoiceConversationEntry>>(emptyList())
  val conversation: StateFlow<List<VoiceConversationEntry>> = _conversation

  private val _conversationLifecycle = MutableStateFlow(JarvisConversationLifecycleState(active = false))
  /** UI hook for the relay's privacy lifecycle; no speech content is exposed here. */
  internal val conversationLifecycle: StateFlow<JarvisConversationLifecycleState> = _conversationLifecycle
  private var conversationIdleJob: Job? = null
  private var conversationLastActivityAtMs = 0L
  private var conversationUserSpeechUntilMs = 0L
  private var conversationInputSpeechUntilMs = 0L
  private var conversationInputSpeechGraceDeadlineMs = 0L
  private var conversationInputSpeechBudgetExhausted = false
  private var conversationAssistantAudibleOutputSeen = false
  private var conversationPostAssistantFollowUpDeadlineMs = 0L
  private var conversationPostAssistantFollowUpSpeechStartedAtMs = 0L
  private var conversationPostAssistantFollowUpSpeechUntilMs = 0L
  private var conversationPartialTranscriptMaxWordCount = 0
  private var conversationUnfinalizedUtteranceDeadlineMs = 0L
  /** A pending consult retains its relay, but its microphone lease has expired. */
  private var realtimeMicDetachedForQuiet = false
  /** A finished consult may still be waiting for the provider to produce audible PCM. */
  private var realtimeAwaitingConsultPlayback = false
  /** True from an accepted lifecycle start until its terminal event. */
  private var realtimeVoiceAgentLifecycleActive = false
  /** Prevent a progress sentence from completing a card while this turn has an agent consult. */
  private var realtimeVoiceIntentHasAgentLifecycle = false
  /** Native admission begins before lifecycle telemetry; retain the relay for that narrow gap. */
  private val realtimePendingConsultRequestIds = LinkedHashSet<String>()
  private var realtimePendingConsultOverflow = false
  /** Recorder-accepted startup PCM is still draining after the physical microphone closes. */
  private var realtimeOrderedIngressDraining = false
  /** A physical reply drained while that finite source still owns the relay ordering. */
  private var realtimeFollowUpAwaitingOrderedIngress = false
  /** Replacement capture is opening for the already-running physical handoff window. */
  private var realtimeFollowUpAwaitingCaptureReady = false
  /** Bounds cleanup after old ordered PCM finishes after the physical follow-up window. */
  private var realtimePostDrainFinalizationDeadlineMs = 0L
  /** A final delayed transcript may await its reply after quiet capture detaches. */
  private var realtimeLateDetachedUserFinalPending = false
  private val realtimeRelayToolExpiryJobs = LinkedHashMap<String, Job>()

  private val agentToolLock = Any()
  private val activeAgentTools = LinkedHashMap<String, JarvisToolExecution>()
  private val agentToolExpiryJobs = LinkedHashMap<String, Job>()
  private val agentToolStaleJobs = LinkedHashMap<String, Job>()
  private val _activeToolExecutions = MutableStateFlow<List<JarvisToolExecution>>(emptyList())
  /** No raw arguments, output, agent/run IDs, or error text enter this UI-facing state. */
  internal val activeToolExecutions: StateFlow<List<JarvisToolExecution>> = _activeToolExecutions

  private fun publishActiveToolsLocked() {
    _activeToolExecutions.value = activeAgentTools.values.toList().takeLast(MAX_VISIBLE_TOOL_ACTIVITY)
  }

  private fun clearActiveTools(runId: String? = null) {
    synchronized(agentToolLock) {
      val keys = if (runId == null) activeAgentTools.keys.toList() else activeAgentTools.keys.filter { it.startsWith("$runId:") }
      keys.forEach { key ->
        activeAgentTools.remove(key)
        agentToolExpiryJobs.remove(key)?.cancel()
        agentToolStaleJobs.remove(key)?.cancel()
      }
      publishActiveToolsLocked()
    }
  }

  private fun publishToolActivity(
    key: String,
    toolCallId: String,
    name: String,
    phase: JarvisToolPhase,
    detail: String? = null,
  ) {
    synchronized(agentToolLock) {
      agentToolExpiryJobs.remove(key)?.cancel()
      agentToolStaleJobs.remove(key)?.cancel()
      while (activeAgentTools.size >= MAX_TRACKED_TOOL_ACTIVITY && key !in activeAgentTools) {
        val oldest = activeAgentTools.entries.firstOrNull()?.key ?: break
        activeAgentTools.remove(oldest)
        agentToolExpiryJobs.remove(oldest)?.cancel()
        agentToolStaleJobs.remove(oldest)?.cancel()
      }
      val previous = activeAgentTools[key]
      val changedAtMs = SystemClock.elapsedRealtime()
      activeAgentTools[key] = JarvisToolExecution(
        toolCallId = toolCallId,
        name = name,
        phase = phase,
        changedAtMs = changedAtMs,
        detail = detail ?: previous?.detail,
        startedAtMs = if (phase == JarvisToolPhase.Running) changedAtMs else previous?.startedAtMs,
      )
      publishActiveToolsLocked()
      if (phase == JarvisToolPhase.Running) {
        agentToolStaleJobs[key] =
          scope.launch {
            delay(TOOL_ACTIVITY_STALE_MILLIS)
            val stale = synchronized(agentToolLock) { activeAgentTools[key]?.takeIf { it.phase == JarvisToolPhase.Running } }
            if (stale != null) publishToolActivity(key, stale.toolCallId, stale.name, JarvisToolPhase.Unknown)
          }
      } else {
        agentToolExpiryJobs[key] =
          scope.launch {
            delay(TOOL_ACTIVITY_SETTLE_MILLIS)
            synchronized(agentToolLock) {
              if (activeAgentTools[key]?.phase == phase) {
                activeAgentTools.remove(key)
                agentToolExpiryJobs.remove(key)
                publishActiveToolsLocked()
              }
            }
          }
      }
    }
  }

  private fun settleActiveToolsForRun(runId: String) {
    val running = synchronized(agentToolLock) { activeAgentTools.filterKeys { it.startsWith("$runId:") }.toMap() }
    running.forEach { (key, tool) ->
      if (tool.phase == JarvisToolPhase.Running) publishToolActivity(key, tool.toolCallId, tool.name, JarvisToolPhase.Unknown)
    }
  }

  private fun markConversationActivityLocked() {
    if (realtimeSessionId == null) return
    conversationLastActivityAtMs = SystemClock.elapsedRealtime()
    conversationIdleJob?.cancel()
    _conversationLifecycle.value = JarvisConversationLifecycleState(active = true)
  }

  private fun scheduleConversationIdleCheckLocked() {
    val sessionId = realtimeSessionId ?: return
    conversationIdleJob?.cancel()
    val startedAtMs = conversationLastActivityAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
    conversationLastActivityAtMs = startedAtMs
    conversationIdleJob =
      gatewayWorkScope.launch {
        while (true) {
          val now = SystemClock.elapsedRealtime()
          val elapsed = now - startedAtMs
          val postAssistantFollowUpDeadlineMs = conversationPostAssistantFollowUpDeadlineMs
          val postAssistantFollowUpRemainingMs = (postAssistantFollowUpDeadlineMs - now).coerceAtLeast(0L)
          val postAssistantFollowUpSpeechRemainingMs = (conversationPostAssistantFollowUpSpeechUntilMs - now).coerceAtLeast(0L)
          val postDrainFinalizationRemainingMs = (realtimePostDrainFinalizationDeadlineMs - now).coerceAtLeast(0L)
          val unfinalizedUtteranceRemainingMs = (conversationUnfinalizedUtteranceDeadlineMs - now).coerceAtLeast(0L)
          val waitMs =
            when {
              realtimeMicDetachedForQuiet && realtimePostDrainFinalizationDeadlineMs > 0L && postDrainFinalizationRemainingMs > 0L ->
                minOf(
                  postDrainFinalizationRemainingMs,
                  (JarvisConversationLifecycle.STALLED_TIMEOUT_MS - elapsed).coerceAtLeast(0L),
                )
              realtimeMicDetachedForQuiet && realtimePostDrainFinalizationDeadlineMs > 0L -> 0L
              realtimeMicDetachedForQuiet && elapsed < JarvisConversationLifecycle.STALLED_TIMEOUT_MS ->
                JarvisConversationLifecycle.STALLED_TIMEOUT_MS - elapsed
              realtimeMicDetachedForQuiet -> 0L
              realtimeFollowUpAwaitingCaptureReady && elapsed < JarvisConversationLifecycle.STALLED_TIMEOUT_MS ->
                JarvisConversationLifecycle.STALLED_TIMEOUT_MS - elapsed
              realtimeFollowUpAwaitingCaptureReady -> 0L
              postAssistantFollowUpDeadlineMs > 0L && postAssistantFollowUpRemainingMs > 0L -> postAssistantFollowUpRemainingMs
              postAssistantFollowUpDeadlineMs > 0L && postAssistantFollowUpSpeechRemainingMs > 0L -> postAssistantFollowUpSpeechRemainingMs
              postAssistantFollowUpDeadlineMs > 0L -> 1_000L
              realtimeAudible &&
                (elapsed >= JarvisConversationLifecycle.QUIET_TIMEOUT_MS ||
                  (conversationUnfinalizedUtteranceDeadlineMs > 0L && unfinalizedUtteranceRemainingMs == 0L)) -> 1_000L
              conversationUnfinalizedUtteranceDeadlineMs > 0L && unfinalizedUtteranceRemainingMs == 0L && !realtimeAudible -> 0L
              elapsed >= JarvisConversationLifecycle.QUIET_TIMEOUT_MS && !realtimeAudible -> 0L
              conversationUnfinalizedUtteranceDeadlineMs > 0L && unfinalizedUtteranceRemainingMs > 0L && elapsed < JarvisConversationLifecycle.QUIET_TIMEOUT_MS ->
                minOf(unfinalizedUtteranceRemainingMs, JarvisConversationLifecycle.QUIET_TIMEOUT_MS - elapsed)
              conversationUnfinalizedUtteranceDeadlineMs > 0L && unfinalizedUtteranceRemainingMs > 0L -> unfinalizedUtteranceRemainingMs
              conversationUnfinalizedUtteranceDeadlineMs > 0L -> 0L
              elapsed < JarvisConversationLifecycle.QUIET_TIMEOUT_MS -> JarvisConversationLifecycle.QUIET_TIMEOUT_MS - elapsed
              elapsed < JarvisConversationLifecycle.STALLED_TIMEOUT_MS -> JarvisConversationLifecycle.STALLED_TIMEOUT_MS - elapsed
              else -> 1_000L
            }
          delay(waitMs)
          var obsolete = false
          var detached = false
          val closeReason: JarvisConversationCloseReason? =
            synchronized(realtimeCapturePauseLock) {
              if (realtimeSessionId != sessionId || conversationLastActivityAtMs != startedAtMs) {
                obsolete = true
                return@synchronized null
              }
              val idleForMs = SystemClock.elapsedRealtime() - startedAtMs
              val unfinalizedUtteranceExpired =
                conversationUnfinalizedUtteranceDeadlineMs > 0L &&
                  SystemClock.elapsedRealtime() >= conversationUnfinalizedUtteranceDeadlineMs
              val pendingRealTool =
                realtimeRelayToolExpiryJobs.isNotEmpty() ||
                  synchronized(agentToolLock) { activeAgentTools.values.any { it.phase == JarvisToolPhase.Running } }
              val awaitingReply =
                currentStatus.awaitingAgent ||
                  realtimeAwaitingConsultPlayback ||
                  realtimeOutputPresentationPending ||
                  realtimePhysicalAudibleDrainPending ||
                  realtimeFollowUpAwaitingOrderedIngress ||
                  realtimeFollowUpAwaitingCaptureReady ||
                  hasPendingRealtimeConsultAdmissionLocked() ||
                  realtimeAgentCoordinator.hasPendingWork(sessionId)
              // A detached capture keeps the relay long enough to deliver a consult's
              // final reply. It never reopens the microphone on its own and has the
              // existing 90-second transport bound if that reply never arrives.
              if (realtimeMicDetachedForQuiet) {
                if (
                  realtimePostDrainFinalizationDeadlineMs > 0L &&
                    SystemClock.elapsedRealtime() >= realtimePostDrainFinalizationDeadlineMs
                ) {
                  // Consume this one short post-drain check. If real work is still pending,
                  // the next iteration uses the existing 90-second detached relay bound.
                  realtimePostDrainFinalizationDeadlineMs = 0L
                  if (!pendingRealTool && !awaitingReply && !realtimeLateDetachedUserFinalPending) {
                    return@synchronized JarvisConversationCloseReason.Idle
                  }
                }
                return@synchronized if (idleForMs >= JarvisConversationLifecycle.STALLED_TIMEOUT_MS) {
                  JarvisConversationCloseReason.Stalled
                  } else {
                  null
                  }
              }
              // A post-reply microphone is in the narrow interval after the ordered source
              // drained but before AudioRecord confirms it is live. It must not be retired by
              // the old request's quiet timestamp; the existing 90-second relay bound remains.
              if (realtimeFollowUpAwaitingCaptureReady) {
                return@synchronized if (idleForMs >= JarvisConversationLifecycle.STALLED_TIMEOUT_MS) {
                  JarvisConversationCloseReason.Stalled
                } else {
                  null
                }
              }
              val postAssistantFollowUpExpired =
                conversationPostAssistantFollowUpDeadlineMs > 0L &&
                  SystemClock.elapsedRealtime() >= conversationPostAssistantFollowUpDeadlineMs &&
                  SystemClock.elapsedRealtime() >= conversationPostAssistantFollowUpSpeechUntilMs
              // The physical reply grants its own quiet window. An utterance admitted
              // inside it also owns its bounded final/speech grace; the older request's
              // general quiet timestamp must not retire that microphone early.
              if (conversationPostAssistantFollowUpDeadlineMs > 0L && !postAssistantFollowUpExpired) {
                return@synchronized null
              }
              if (unfinalizedUtteranceExpired && !realtimeAudible) {
                // A provider may already have delegated a partial utterance. Its
                // transcription deadline retires capture, not the pending answer.
                if (pendingRealTool || awaitingReply) {
                  detached = detachRealtimeCaptureForQuietLocked()
                  return@synchronized null
                }
                return@synchronized JarvisConversationCloseReason.Idle
              }
              if (JarvisConversationLifecycle.shouldDetachMicrophone(
                  idleForMs = idleForMs,
                  audibleAssistantOutput = realtimeAudible,
                  pendingRealTool = pendingRealTool,
                  awaitingReply = awaitingReply,
                  postAssistantFollowUpExpired = postAssistantFollowUpExpired,
                )
              ) {
                detached = detachRealtimeCaptureForQuietLocked()
                return@synchronized null
              }
              JarvisConversationLifecycle.closeReason(
                idleForMs = idleForMs,
                userSpeechActive =
                  (realtimeUserEntryId != null && SystemClock.elapsedRealtime() < conversationUserSpeechUntilMs) ||
                    SystemClock.elapsedRealtime() < minOf(conversationInputSpeechUntilMs, conversationInputSpeechGraceDeadlineMs),
                audibleAssistantOutput = realtimeAudible,
                pendingRealTool = pendingRealTool,
                awaitingReply = awaitingReply,
                postAssistantFollowUpExpired = postAssistantFollowUpExpired,
              )
            }
          if (obsolete) return@launch
          // Keep this single watchdog alive after capture is detached: the retained
          // relay still needs its bounded transport cleanup if a result never arrives.
          if (detached) continue
          if (closeReason != null) {
            closeRealtimeConversation(sessionId, closeReason)
            return@launch
          }
        }
      }
  }

  /** Capture frames may arrive every 100ms; they must not keep cancelling a due close check. */
  private fun ensureConversationIdleCheckLocked() {
    if (realtimeSessionId != null && conversationIdleJob?.isActive != true) {
      scheduleConversationIdleCheckLocked()
    }
  }

  private fun trackRealtimeRelayToolLocked(callId: String) {
    realtimeRelayToolExpiryJobs.remove(callId)?.cancel()
    realtimeRelayToolExpiryJobs[callId] =
      gatewayWorkScope.launch {
        delay(JarvisConversationLifecycle.STALLED_TIMEOUT_MS)
        synchronized(realtimeCapturePauseLock) {
          realtimeRelayToolExpiryJobs.remove(callId)
          scheduleConversationIdleCheckLocked()
        }
      }
  }

  private fun finishRealtimeRelayToolLocked(callId: String) {
    realtimeRelayToolExpiryJobs.remove(callId)?.cancel()
    scheduleConversationIdleCheckLocked()
  }

  private fun clearRealtimeRelayToolsLocked() {
    realtimeRelayToolExpiryJobs.values.forEach { it.cancel() }
    realtimeRelayToolExpiryJobs.clear()
  }

  private fun closeRealtimeConversation(
    sessionId: String,
    reason: JarvisConversationCloseReason,
    sleepRequested: Boolean = false,
  ) {
    var stopped: (() -> Unit)? = null
    synchronized(realtimeCapturePauseLock) {
      if (realtimeSessionId != sessionId) return
      Log.i(tag, "realtime conversation close reason=$reason")
      conversationIdleJob?.cancel()
      conversationIdleJob = null
      _conversationLifecycle.value = JarvisConversationLifecycleState(active = false, closingReason = reason, sleepRequested = sleepRequested)
      if (reason == JarvisConversationCloseReason.ExplicitFarewell) discardUnfinishedRealtimeAssistantEntry()
      stopRequested = true
      stopRealtimeRelay(preserveStatus = true)
      setStatus(nativeText("Off"), state = TalkStatusState.Off)
      stopped = disableRealtimeModeLocked()
    }
    // NodeRuntime owns the wake-listener rearm after the actual audio retirement boundary.
    stopped?.invoke()
    onVoiceConversationClosed()
  }

  /** A deliberate close must not leave a provider filler fragment in the next wake's history. */
  private fun discardUnfinishedRealtimeAssistantEntry() {
    val entryId = realtimeAssistantEntryId ?: return
    val current = _conversation.value
    val index = current.indexOfFirst { it.id == entryId && it.role == VoiceConversationRole.Assistant && it.isStreaming }
    if (index >= 0) {
      _conversation.value = current.toMutableList().also { it.removeAt(index) }
    }
    realtimeAssistantEntryId = null
  }

  private fun recordRealtimeInputSpeech(sessionId: String) {
    synchronized(realtimeCapturePauseLock) {
      if (realtimeSessionId != sessionId || !_isEnabled.value || stopRequested) return
      val now = SystemClock.elapsedRealtime()
      val postAssistantFollowUpDeadlineMs = conversationPostAssistantFollowUpDeadlineMs
      if (
        postAssistantFollowUpDeadlineMs > 0L &&
          (conversationPostAssistantFollowUpSpeechStartedAtMs > 0L || now <= postAssistantFollowUpDeadlineMs)
      ) {
        if (conversationPostAssistantFollowUpSpeechStartedAtMs == 0L) {
          conversationPostAssistantFollowUpSpeechStartedAtMs = now
        }
        // A sentence may naturally run longer than the handoff. Renew only the
        // utterance that started in it, then cap continuous room audio absolutely.
        val utteranceDeadlineMs =
          conversationPostAssistantFollowUpSpeechStartedAtMs +
            JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MAX_UTTERANCE_MS
        conversationPostAssistantFollowUpSpeechUntilMs = maxOf(
          conversationPostAssistantFollowUpSpeechUntilMs,
          minOf(
            now + JarvisConversationLifecycle.INPUT_SPEECH_TAIL_MS + JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_FINAL_GRACE_MS,
            utteranceDeadlineMs,
          ),
        )
      }
      if (conversationInputSpeechBudgetExhausted) return
      if (conversationInputSpeechGraceDeadlineMs == 0L) {
        conversationInputSpeechGraceDeadlineMs = now + JarvisConversationLifecycle.INPUT_SPEECH_MAX_GRACE_MS
      }
      if (now >= conversationInputSpeechGraceDeadlineMs) {
        conversationInputSpeechUntilMs = 0L
        conversationInputSpeechBudgetExhausted = true
      } else {
        conversationInputSpeechUntilMs = now + JarvisConversationLifecycle.INPUT_SPEECH_TAIL_MS
      }
      // The tail protects an utterance before cloud transcription arrives. It never
      // advances the conversation baseline: continuous noise gets only one short grace.
      ensureConversationIdleCheckLocked()
    }
  }

  /** Caller holds [realtimeCapturePauseLock]. The timer begins at physical output drain. */
  private fun hasPendingRealtimeConsultAdmissionLocked(): Boolean =
    realtimePendingConsultRequestIds.isNotEmpty() || realtimePendingConsultOverflow

  private fun armPostAssistantFollowUpLocked() {
    if (
      !conversationAssistantAudibleOutputSeen ||
        currentStatus.awaitingAgent ||
        hasPendingRealtimeConsultAdmissionLocked() ||
        realtimeAwaitingConsultPlayback ||
        realtimeSessionId?.let(realtimeAgentCoordinator::hasPendingWork) == true ||
        realtimeRelayToolExpiryJobs.isNotEmpty()
    ) return
    // The privacy window starts at physical output drain, even while finite startup PCM
    // remains ordered ahead of replacement capture. That ingress remains transport-owned.
    conversationAssistantAudibleOutputSeen = false
    conversationPostAssistantFollowUpDeadlineMs =
      SystemClock.elapsedRealtime() + JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS
    conversationPostAssistantFollowUpSpeechStartedAtMs = 0L
    conversationPostAssistantFollowUpSpeechUntilMs = 0L
    if (realtimeOrderedIngressDraining) {
      realtimeFollowUpAwaitingOrderedIngress = true
      return
    }
    resumeRealtimeCaptureAfterQuietLocked()
  }

  /** Caller holds [realtimeCapturePauseLock]. Keeps relay ownership for a pending result. */
  private fun detachRealtimeCaptureForQuietLocked(): Boolean {
    if (realtimeMicDetachedForQuiet || realtimeSessionId == null) return false
    realtimeMicDetachedForQuiet = true
    onAppliedAudioInputChanged(null)
    audioRetirement.retire(realtimeCaptureJob, realtimeAudioInput)
    realtimeAudioInput = null
    realtimeCaptureJob = null
    realtimeCaptureReady = null
    _isListening.value = false
    _isCaptureAdmitted.value = false
    Log.i(tag, "realtime microphone detached while draining recorder-owned relay PCM")
    return true
  }

  /** A completed audible reply gets the existing bounded follow-up capture window. */
  private fun resumeRealtimeCaptureAfterQuietLocked(
    armPostAssistantFollowUpAfterReady: Boolean = false,
  ): Boolean {
    if (!realtimeMicDetachedForQuiet) return false
    if (realtimeOrderedIngressDraining) {
      Log.d(tag, "post-reply microphone wait: recorder-owned startup PCM is still draining")
      return false
    }
    val sessionId = realtimeSessionId ?: return false
    if (!_isEnabled.value || stopRequested || realtimeCapturePause != null) return false
    realtimeMicDetachedForQuiet = false
    _isListening.value = true
    setStatus(nativeText("Listening"))
    startRealtimeCaptureLocked(sessionId, armPostAssistantFollowUpAfterReady = armPostAssistantFollowUpAfterReady)
    Log.d(tag, "realtime microphone resumed for post-reply handoff")
    return true
  }

  /** Caller holds [realtimeCapturePauseLock] after the replacement AudioRecord is live. */
  private fun armPostAssistantFollowUpAfterCaptureReadyLocked(
    sessionId: String,
    inputGeneration: Long,
    ready: Deferred<Unit>,
  ) {
    if (
      !realtimeFollowUpAwaitingCaptureReady ||
        !ready.isCompleted ||
        ready.isCancelled ||
        realtimeSessionId != sessionId ||
        audioInputGeneration.get() != inputGeneration ||
        realtimeCaptureReady !== ready ||
        realtimeMicDetachedForQuiet ||
        !_isEnabled.value ||
        stopRequested
    ) return
    realtimeFollowUpAwaitingCaptureReady = false
    armPostAssistantFollowUpLocked()
    scheduleConversationIdleCheckLocked()
  }

  private var recognizer: SpeechRecognizer? = null
  private var restartJob: Job? = null
  private var stopRequested = false

  @Volatile private var listeningMode = false
  private var activePttCaptureId: String? = null
  private var pttAutoStopEnabled = false
  private var pttTimeoutJob: Job? = null
  private var pttCompletion: CompletableDeferred<TalkPttStopPayload>? = null
  private var pttRecognitionRung: PushToTalkRecognitionRung? = null
  private var pttReleaseCompletion: CompletableDeferred<Unit>? = null
  private val pttFinalSegments = mutableListOf<String>()
  private var pttLivePartial = ""

  private var silenceJob: Job? = null
  private val silenceWindowMs get() = configCache.get().value.silenceTimeoutMs
  private var lastTranscript: String = ""
  private var lastHeardAtMs: Long? = null
  private var lastSpokenText: String? = null

  // Interrupt-on-speech is disabled by default: starting a SpeechRecognizer during
  // TTS creates an audio session conflict on some OEMs. Can be enabled via gateway talk config.
  private val interruptOnSpeech get() = configCache.get().value.interruptOnSpeech ?: false
  private var mainSessionKey: String = "main"
  private fun activeTalkSessionKey(): String =
    resolveTalkSessionKey(
      mainSessionKey = mainSessionKey,
      configuredAgentId = configCache.get().value.agentId,
      resolveAgentSessionKey = resolveAgentSessionKey,
    )
  private val speechLocale get() = configCache.get().value.speechLocale
  private val realtimeRelayModelSupported get() = configCache.get().value.realtimeRelayModelSupported
  internal val isRealtimeRelayConfigured: Boolean
    get() = configCache.get().loaded && realtimeRelayModelSupported

  @Volatile private var pendingRunId: String? = null
  private var pendingFinal: CompletableDeferred<Boolean>? = null
  private val completedRunsLock = Any()
  private val completedRunStates = LinkedHashMap<String, Boolean>()
  private val completedRunTexts = LinkedHashMap<String, String>()
  private val startGeneration = AtomicLong(0L)
  private var relayStopNotification: ((() -> Boolean) -> Unit) = {}
  private val audioInputGeneration = AtomicLong(0L)

  @Volatile private var realtimeSessionId: String? = null
  private var realtimeSessionKey: String? = null
  private var realtimeRequestLease: GatewaySession.RequestLease? = null
  private var realtimeProviderReady: CompletableDeferred<Unit>? = null
  // A fast provider can emit ready between the create RPC response and our session commit.
  // Retain only a few short-lived IDs, then consume one exact match at commit.
  private val earlyRealtimeReadyAtMs = LinkedHashMap<String, Long>()
  private var realtimeVoiceChange: RealtimeVoiceChange? = null

  /**
   * PCM from the same utterance that confirmed a local wake. It belongs to one
   * subsequently-created relay only; it is never retained across stop, gateway
   * changes, or a failed start.
   */
  private var pendingRealtimeWakeSeed: VoiceWakeAudio? = null
  private var pendingRealtimeWakeStartedAtMs: Long? = null
  private var pendingRealtimeWakeCaptureStartGate: Deferred<Unit>? = null
  private var realtimeWakeStartedAtMs: Long? = null

  private class RealtimeVoiceChange(
    val id: String,
    val originalSessionId: String,
    val sessionKey: String,
    val voice: String,
    val lease: GatewaySession.RequestLease,
    val generation: Long,
    val gatewayGeneration: Long,
  ) {
    var replacementSessionId: String? = null
  }

  private var realtimeCaptureJob: Job? = null
  private var realtimeCaptureReady: Deferred<Unit>? = null
  private var realtimeAppendJob: Job? = null
  private val realtimeCapturePauseLock = Any()
  internal val audioRetirement = AudioRetirement(scope)

  private class RealtimeCaptureChanged : CancellationException("Microphone changed before voice confirmation")

  @Volatile private var realtimeCapturePause: RealtimeCapturePause? = null

  private val finishingPttLock = Any()

  @Volatile private var finishingPttCaptureId: String? = null

  @Volatile private var finishingPttJob: Job? = null

  private val realtimeAgentCoordinator =
    RealtimeAgentCoordinator(
      parentScope = scope,
      requestGateway = ::requestGateway,
      onWorking = { session ->
        synchronized(realtimeCapturePauseLock) {
          if (realtimeSessionId == session.relaySessionId) {
            setStatus(nativeText("Thinking…"), awaitingAgent = true)
          }
        }
      },
      onError = { _, message -> Log.w(tag, message) },
      onUnhandledCompletion = { completion ->
        handleNonRealtimeAgentChatEvent(
          sessionKey = completion.sessionKey,
          runId = completion.runId,
          state = completion.state,
          message = completion.message,
        )
      },
    )
  private var realtimeUserEntryId: String? = null
  private var realtimeUserEntryAwaitingFinal = false
  private var realtimeUserEntryAwaitingFinalStartedAtMs: Long? = null
  private var realtimeAssistantEntryId: String? = null

  @Volatile private var realtimeAudioInput: AndroidAudioInputSession? = null

  // This is relay-owned, unlike [realtimeAudioInput]. A quiet microphone may be retired while
  // the already admitted relay still has spoken PCM to deliver.
  @Volatile private var realtimeCommunicationAudio: RealtimeCommunicationAudio? = null

  @Volatile private var localMediaPlaybackActive = false

  @Volatile private var realtimePlayoutSession: RealtimePlayout.Session? = null
  private val realtimePlayoutDelegate =
    lazy {
      RealtimePlayout(scope, realtimePlaybackDispatcher, realtimeSampleRateHz)
    }
  private val realtimePlayout by realtimePlayoutDelegate

  @Volatile private var pendingRealtimeOutputClear: CompletableDeferred<String?>? = null

  private data class RealtimeOutputTurn(
    val id: String?,
    val statusOwner: TalkStatusOwner,
  )

  @Volatile private var realtimeOutputTurn: RealtimeOutputTurn? = null
  /**
   * Session-local output ownership indexed by provider turn ID. [realtimeOutputTurn] remains
   * the currently active turn for cancellation; this bounded history only serves late PCM and
   * terminal callbacks that still belong to an older physical playback.
   *
   * All accesses happen while [realtimeCapturePauseLock] is held.
   */
  private val realtimeOutputOwners = LinkedHashMap<String, TalkStatusOwner>()
  private val realtimeOutputCancellationMutex = Mutex()

  @Volatile
  private var realtimeOutputSuppressed = false

  @Volatile
  private var playbackEnabled = true
  private val playbackGeneration = AtomicLong(0L)

  private enum class PlaybackPhase {
    Preparing,
    Playing,
  }

  private data class PlaybackLease(
    val token: Long,
    val job: Job,
    var phase: PlaybackPhase = PlaybackPhase.Preparing,
    var releaseAudioFocus: (() -> Unit)? = null,
  )

  private var localPlayback: PlaybackLease? = null
  private var realtimePlaying = false
  private var realtimeAudible = false
  private var realtimeAudibleUntilMs = 0L
  private var realtimeAudibleStatusOwner: Any? = null
  private var realtimeAudibleExpiryJob: Job? = null
  private var realtimeOutputPresentationPending = false
  /** Keeps the relay through the gap from final audible playhead presentation to physical drain. */
  private var realtimePhysicalAudibleDrainPending = false
  private var realtimeOutputEpoch = 0L
  private var realtimePostOutputTail: RealtimePostOutputTail? = null

  private sealed interface RealtimeCaptureIngress {
    data class Live(
      val outputEpoch: Long,
      val frameStartedAtMs: Long,
      val frame: ByteArray,
    ) : RealtimeCaptureIngress

    data class PostOutputTail(
      val outputEpoch: Long,
      val frames: List<ByteArray>,
    ) : RealtimeCaptureIngress

    data object RetainedTailFrame : RealtimeCaptureIngress
  }

  private data class RealtimePostOutputTail(
    val sessionId: String,
    val inputGeneration: Long,
    val outputEpoch: Long,
    val beganAtMs: Long,
    val untilMs: Long,
    val frames: ArrayDeque<ByteArray> = ArrayDeque(),
  )

  /** One wake owns this hold; release admits only frames that began after the local ACK ended. */
  private data class RealtimeWakeCaptureHold(
    val token: Long,
    var releaseAtMs: Long? = null,
  )

  private var nextRealtimeWakeCaptureHoldToken = 0L
  private var realtimeWakeCaptureHold: RealtimeWakeCaptureHold? = null
  private val systemSpeech = SystemSpeechSpeaker(context)

  fun setMainSessionKey(sessionKey: String?) {
    val trimmed = sessionKey?.trim().orEmpty()
    if (trimmed.isEmpty()) return
    mainSessionKey = trimmed
  }

  fun setEnabled(enabled: Boolean) {
    if (_isEnabled.value == enabled) return
    _isEnabled.value = enabled
    if (enabled) {
      Log.d(tag, "enabled")
      start()
    } else {
      Log.d(tag, "disabled")
      stop()
    }
  }

  /** Arms PCM from a confirmed local wake for the immediately following relay. */
  internal fun setNextRealtimeWakeSeed(
    audio: VoiceWakeAudio?,
    wakeStartedAtMs: Long? = null,
    captureStartGate: Deferred<Unit>? = null,
  ) {
    synchronized(realtimeCapturePauseLock) {
      pendingRealtimeWakeSeed =
        audio
          ?.takeIf { it.pcm16.isNotEmpty() && it.sampleRateHz > 0 }
          ?.let { source ->
            val maximumBytes = ((source.sampleRateHz.toLong() * 2L * maxWakeSeedDurationMs) / 1_000L).toInt()
            VoiceWakeAudio(source.pcm16.copyOf(minOf(source.pcm16.size, maximumBytes) and -2), source.sampleRateHz)
          }
      pendingRealtimeWakeStartedAtMs = wakeStartedAtMs
      pendingRealtimeWakeCaptureStartGate = captureStartGate
    }
  }

  /** Arms a one-shot live-PCM hold while a local wake acknowledgement is audible. */
  internal fun armRealtimeWakeCaptureHold(): Long =
    synchronized(realtimeCapturePauseLock) {
      (++nextRealtimeWakeCaptureHoldToken).also { token ->
        realtimeWakeCaptureHold = RealtimeWakeCaptureHold(token)
        _isCaptureAdmitted.value = false
      }
    }

  /** Releases only the owning wake; overlapping AudioRecord frames remain rejected. */
  internal fun releaseRealtimeWakeCaptureHold(
    token: Long,
    releaseAtMs: Long = SystemClock.elapsedRealtime(),
  ): Boolean =
    synchronized(realtimeCapturePauseLock) {
      val hold = realtimeWakeCaptureHold ?: return@synchronized false
      if (hold.token != token) return@synchronized false
      hold.releaseAtMs = releaseAtMs
      true
    }

  internal fun cancelRealtimeWakeCaptureHold(token: Long) {
    synchronized(realtimeCapturePauseLock) {
      if (realtimeWakeCaptureHold?.token == token) realtimeWakeCaptureHold = null
    }
  }

  /** Stops continuous, one-shot, or push-to-talk capture regardless of the enabled flag. */
  fun stopAllCapture(
    failure: NativeText? = null,
    preserveRealtimeWakeCaptureHold: Boolean = false,
  ) {
    _isEnabled.value = false
    stop(preserveRealtimeWakeCaptureHold)
    failure?.let(::setTalkFailure)
  }

  /** Cancels work carrying voice/session data before a replacement gateway can connect. */
  fun onGatewayScopeChanging() {
    onVoiceConversationClosed()
    synchronized(realtimeCapturePauseLock) {
      realtimeVoiceAgentLifecycleActive = false
      realtimeVoiceIntentHasAgentLifecycle = false
      gatewayGeneration.incrementAndGet()
      pendingRealtimeWakeSeed = null
      pendingRealtimeWakeStartedAtMs = null
      pendingRealtimeWakeCaptureStartGate = null
      realtimeWakeStartedAtMs = null
      stopRealtimeRelay(closeSession = false)
    }
    realtimeAgentCoordinator.resetTransport()
    clearActiveTools()
    configCache.set(TalkConfigCache())
    gatewayWorkJob.cancel()
    gatewayWorkJob = SupervisorJob()
    gatewayWorkScope = CoroutineScope(scope.coroutineContext + gatewayWorkJob)
    _conversation.value = emptyList()
  }

  private suspend fun requestGateway(
    method: String,
    paramsJson: String?,
    timeoutMs: Long = 15_000,
  ): String {
    val gatewayId = gatewayStableId()?.trim()?.takeIf { it.isNotEmpty() }
    return if (gatewayId == null) {
      session.request(method, paramsJson, timeoutMs)
    } else {
      session.requestForEndpoint(gatewayId, method, paramsJson, timeoutMs)
    }
  }

  internal val activePushToTalkCaptureId: String?
    get() = activePttCaptureId

  internal val finishingPushToTalkCaptureId: String?
    get() = finishingPttCaptureId

  suspend fun beginPushToTalk(
    allowNewCapture: Boolean,
    canStartCapture: () -> Boolean = { true },
  ): TalkPttStartPayload =
    startPushToTalk(
      allowNewCapture = allowNewCapture,
      canStartCapture = canStartCapture,
      completion = null,
    ).payload

  private sealed interface PushToTalkStartResult {
    val payload: TalkPttStartPayload

    data class Started(
      override val payload: TalkPttStartPayload,
    ) : PushToTalkStartResult

    data class Existing(
      override val payload: TalkPttStartPayload,
    ) : PushToTalkStartResult
  }

  private data class ClearedPushToTalkCapture(
    val transcript: String,
    val completion: CompletableDeferred<TalkPttStopPayload>?,
  )

  private data class RealtimeCapturePause(
    // Null while relay creation is still in flight. Keeping the PTT turn here
    // prevents a late relay response from opening a second microphone capture.
    val sessionId: String?,
    val pttCaptureId: String,
    val restartRelay: Boolean = false,
  )

  private enum class RealtimeCaptureResume {
    Skipped,
    Resumed,
    Restart,
    Disconnected,
  }

  private suspend fun startPushToTalk(
    allowNewCapture: Boolean,
    canStartCapture: () -> Boolean,
    completion: CompletableDeferred<TalkPttStopPayload>?,
    autoStopAfterMs: Long? = null,
  ): PushToTalkStartResult {
    if (!allowNewCapture) {
      // A background retry may reconcile an existing capture, but must never create one.
      return activePttCaptureId
        ?.let(::TalkPttStartPayload)
        ?.let { PushToTalkStartResult.Existing(it) }
        ?: throw IllegalStateException("NODE_BACKGROUND_UNAVAILABLE: command requires foreground")
    }
    // PTT begin is idempotent so gateway retries don't start multiple recognizers.
    activePttCaptureId?.let {
      if (pttReleaseCompletion == null) {
        return PushToTalkStartResult.Existing(TalkPttStartPayload(captureId = it))
      }
    }
    finishingPttCaptureId?.let {
      throw IllegalStateException("PTT_BUSY: previous push-to-talk turn is still finishing")
    }
    if (!isConnected()) {
      setStatus(nativeText("Gateway not connected"))
      throw IllegalStateException("UNAVAILABLE: Gateway not connected")
    }

    val micOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!micOk) {
      setStatus(nativeText("Microphone permission required"))
      throw IllegalStateException("MIC_PERMISSION_REQUIRED: grant Microphone permission")
    }
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      setStatus(nativeText("Speech recognizer unavailable"))
      throw IllegalStateException("UNAVAILABLE: Speech recognizer unavailable")
    }

    val captureId = UUID.randomUUID().toString()
    val captureGeneration = startGeneration.get()
    return try {
      withContext(Dispatchers.Main) {
        val hasPendingRelease = pttReleaseCompletion != null
        if (hasPendingRelease) {
          drainPushToTalkReleaseBeforeBegin()
        }
        activePttCaptureId?.let {
          if (!hasPendingRelease) {
            return@withContext PushToTalkStartResult.Existing(TalkPttStartPayload(captureId = it))
          }
        }
        finishingPttCaptureId?.let {
          throw IllegalStateException("PTT_BUSY: previous push-to-talk turn is still finishing")
        }
        if (captureGeneration != startGeneration.get() || !canStartCapture()) {
          throw IllegalStateException("NODE_BACKGROUND_UNAVAILABLE: command requires foreground")
        }
        cancelActivePlayback()
        // Capture outside pauseLock: Gateway enqueue takes its lifecycle lock before this owner lock.
        val lease = session.captureRequestLease(gatewayStableId())
        val finishPause =
          synchronized(realtimeCapturePauseLock) {
            if (captureGeneration != startGeneration.get() || !canStartCapture()) {
              throw IllegalStateException("NODE_BACKGROUND_UNAVAILABLE: command requires foreground")
            }
            pttTimeoutJob?.cancel()
            pttTimeoutJob = null
            pttAutoStopEnabled = false
            silenceJob?.cancel()
            silenceJob = null
            listeningMode = false
            _isListening.value = false
            stopRequested = false
            retireRecognizer()
            closePushToTalkRung()
            pttReleaseCompletion = null
            pttFinalSegments.clear()
            pttLivePartial = ""
            lastTranscript = ""
            lastHeardAtMs = null
            activePttCaptureId = captureId
            pttCompletion = completion
            prepareRealtimeCapturePause(captureId, lease)
          }
        try {
          // PTT owns the microphone until its turn finishes. Waiting here prevents
          // SpeechRecognizer from racing the realtime AudioRecord teardown.
          withContext(NonCancellable) {
            finishPause()
          }
          synchronized(realtimeCapturePauseLock) {
            if (
              activePttCaptureId != captureId ||
              captureGeneration != startGeneration.get() ||
              !canStartCapture() ||
              stopRequested
            ) {
              throw IllegalStateException("NODE_BACKGROUND_UNAVAILABLE: command requires foreground")
            }
            recognizer =
              SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(recognitionListener(captureId, it))
              }
            startPushToTalkRecognition(captureId)
            if (autoStopAfterMs != null) {
              pttAutoStopEnabled = true
              // Install one-shot jobs before yielding to lifecycle changes. Otherwise a
              // background stop can run between capture startup and job registration.
              startSilenceMonitor(captureId)
              pttTimeoutJob =
                gatewayWorkScope.launch {
                  delay(autoStopAfterMs)
                  if (pttAutoStopEnabled) {
                    endPushToTalk(captureId)
                  }
                }
            }
          }
        } catch (err: Throwable) {
          if (clearPushToTalkRecognition(captureId) != null) {
            synchronized(realtimeCapturePauseLock) {
              if (captureGeneration == startGeneration.get()) {
                setStatus(if (_isEnabled.value) nativeText("Listening") else nativeText("Ready"))
              }
            }
            resumeRealtimeCaptureAfterPushToTalk(captureId)
          }
          completion?.cancel()
          throw err
        }
        PushToTalkStartResult.Started(TalkPttStartPayload(captureId = captureId))
      }
    } catch (err: Throwable) {
      withContext(NonCancellable) {
        cancelPushToTalk(captureId)
      }
      throw err
    }
  }

  /** Stops push-to-talk capture and queues the transcript for gateway chat. */
  suspend fun endPushToTalk(): TalkPttStopPayload {
    val captureId = activePttCaptureId ?: UUID.randomUUID().toString()
    return endPushToTalk(captureId)
  }

  internal suspend fun endPushToTalk(captureId: String): TalkPttStopPayload =
    try {
      withContext(Dispatchers.Main) {
        awaitPushToTalkRelease(captureId)
        val cleared =
          clearPushToTalkRecognition(captureId)
            ?: return@withContext TalkPttStopPayload(captureId = captureId, transcript = null, status = "idle")
        val transcript = cleared.transcript

        if (transcript.isEmpty()) {
          return@withContext finishClearedPushToTalk(captureId, cleared, status = "empty")
        }

        if (!isConnected()) {
          return@withContext finishClearedPushToTalk(
            captureId,
            cleared,
            status = "offline",
            transcript = transcript,
            statusText = nativeText("Gateway not connected"),
          )
        }

        setStatus(nativeText("Thinking…"), awaitingAgent = true)
        lateinit var finishingJob: Job
        finishingJob =
          // Gateway-scoped so a switch drops the stale finalize; the NonCancellable
          // finally still resumes capture when the scope cancels this job.
          gatewayWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
              finalizeTranscript(transcript)
            } finally {
              withContext(NonCancellable + Dispatchers.Main) {
                resumeRealtimeCaptureAfterPushToTalk(captureId)
                clearFinishingPushToTalk(captureId, finishingJob)
              }
            }
          }
        // Cancellation can win before a lazy coroutine enters its body, in which
        // case its Main-confined finally block never runs. Clear only the exact
        // owner here, then resume its microphone on Main if the parent is live.
        finishingJob.invokeOnCompletion {
          if (clearFinishingPushToTalk(captureId, finishingJob)) {
            scope.launch(Dispatchers.Main.immediate) {
              resumeRealtimeCaptureAfterPushToTalk(captureId)
            }
          }
        }
        // Publish the job before it can run so stop() cannot clear ownership while
        // an untracked finalizer still uses shared chat and playback state.
        synchronized(finishingPttLock) {
          finishingPttCaptureId = captureId
          finishingPttJob = finishingJob
          finishingJob.start()
        }
        finishPushToTalk(
          TalkPttStopPayload(captureId = captureId, transcript = transcript, status = "queued"),
          cleared.completion,
        )
      }
    } catch (err: CancellationException) {
      // Mirror the normal termination tail: resume realtime capture, restore status, and
      // resolve the PTT completion so a cancelled end (gateway drop) cannot leave Talk
      // paused or an awaiter stuck behind the release wait.
      withContext(NonCancellable + Dispatchers.Main) {
        val cleared = clearPushToTalkRecognition(captureId)
        if (cleared != null) {
          finishClearedPushToTalk(
            captureId,
            cleared,
            status = "cancelled",
            transcript = cleared.transcript.ifEmpty { null },
          )
        }
      }
      throw err
    }

  /** Cancels push-to-talk capture without sending the current transcript. */
  suspend fun cancelPushToTalk(): TalkPttStopPayload {
    val captureId = activePttCaptureId ?: UUID.randomUUID().toString()
    return cancelPushToTalk(captureId)
  }

  internal suspend fun cancelPushToTalk(captureId: String): TalkPttStopPayload =
    withContext(Dispatchers.Main) {
      val cleared =
        clearPushToTalkRecognition(captureId)
          ?: return@withContext TalkPttStopPayload(captureId = captureId, transcript = null, status = "idle")
      finishClearedPushToTalk(captureId, cleared, status = "cancelled")
    }

  /** Starts a bounded one-shot PTT turn that auto-stops on silence or timeout. */
  internal suspend fun beginPushToTalkOnce(
    maxDurationMs: Long = 12_000L,
    canStartCapture: () -> Boolean = { true },
  ): TalkPttOnceStart {
    val busyCaptureId = activePttCaptureId ?: finishingPttCaptureId
    if (busyCaptureId != null) {
      return TalkPttOnceStart.Busy(
        TalkPttStopPayload(
          captureId = busyCaptureId,
          transcript = null,
          status = "busy",
        ),
      )
    }

    val completion = CompletableDeferred<TalkPttStopPayload>()
    return when (
      val start =
        startPushToTalk(
          allowNewCapture = true,
          canStartCapture = canStartCapture,
          completion = completion,
          autoStopAfterMs = maxDurationMs,
        )
    ) {
      is PushToTalkStartResult.Existing -> {
        TalkPttOnceStart.Busy(
          TalkPttStopPayload(
            captureId = start.payload.captureId,
            transcript = null,
            status = "busy",
          ),
        )
      }

      is PushToTalkStartResult.Started -> {
        TalkPttOnceStart.Started(
          captureId = start.payload.captureId,
          completion = completion,
        )
      }
    }
  }

  /** Waits for a started one-shot turn without keeping NodeRuntime preparation locked. */
  internal suspend fun awaitPushToTalkOnce(start: TalkPttOnceStart): TalkPttStopPayload =
    when (start) {
      is TalkPttOnceStart.Busy -> {
        start.payload
      }

      is TalkPttOnceStart.Started -> {
        try {
          start.completion.await()
        } catch (err: Throwable) {
          withContext(NonCancellable) {
            cancelPushToTalk(start.captureId)
          }
          throw err
        }
      }
    }

  /** When true, play TTS for all final chat responses (even ones we didn't initiate). */
  @Volatile var ttsOnAllResponses = false

  fun playTtsForText(text: String) {
    val playbackToken = cancelActivePlayback()
    invalidateConfig()
    gatewayWorkScope.launch {
      ensureConfigLoaded()
      playAssistant(text, playbackToken)
    }
  }

  fun handleGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    if (event == "config.changed") {
      invalidateConfig()
      return
    }
    if (event == "talk.event") {
      // This is the first timestamp owned by TalkMode after the gateway callback reaches it.
      // It intentionally does not claim to measure wire or provider arrival time.
      handleRealtimeTalkEvent(payloadJson, talkEventReceivedAtMs = SystemClock.elapsedRealtime())
      return
    }
    if (event == "talk.voice.change") {
      handleRealtimeVoiceChange(payloadJson)
      return
    }
    if (event == "agent" || event == "session.tool") {
      handleRealtimeAgentToolEvent(payloadJson)
      return
    }
    if (ttsOnAllResponses) {
      Log.d(tag, "gateway event: $event")
    }
    if (event != "chat") return
    val obj = parseJsonParamsObject(payloadJson) ?: return
    val runId = obj["runId"].asJsonStringOrNull() ?: return
    val state = obj["state"].asJsonStringOrNull() ?: return

    val eventSession = obj["sessionKey"]?.asJsonStringOrNull()
    val activeRealtimeAgentRun = realtimeAgentCoordinator.ownsAgentEvent(runId, eventSession)
    // Consults use the acknowledged agent target, which can differ from the
    // voice key. Ordinary chat keeps its session privacy filter below.
    val ownedRealtimeRun =
      realtimeAgentCoordinator.handleChatEvent(
        sessionKey = eventSession,
        runId = runId,
        state = state,
        message = obj["message"],
      )
    if (ownedRealtimeRun) {
      if (state == "final" || state == "aborted" || state == "error") {
        settleActiveToolsForRun(runId)
        synchronized(realtimeCapturePauseLock) {
          clearRealtimeRelayToolsLocked()
          // A completed consult may still hand audio to this relay, but it must not
          // renew the microphone's final-user-transcript lease.
          scheduleConversationIdleCheckLocked()
        }
      }
      return
    }

    if (activeRealtimeAgentRun && (state == "final" || state == "aborted" || state == "error")) {
      settleActiveToolsForRun(runId)
      synchronized(realtimeCapturePauseLock) { clearRealtimeRelayToolsLocked() }
    }

    handleNonRealtimeAgentChatEvent(
      sessionKey = eventSession,
      runId = runId,
      state = state,
      message = obj["message"],
    )
  }

  /** Projects only concrete, accepted-consult tool events from the active voice relay. */
  private fun handleRealtimeAgentToolEvent(payloadJson: String?) {
    val payload = parseJsonParamsObject(payloadJson) ?: return
    val runId = payload["runId"].asJsonStringOrNull() ?: return
    val data = payload["data"].asObjectOrNull() ?: return
    val eventTimestampMs =
      (listOf(payload["ts"].asJsonStringOrNull()) + listOf("startedAt", "createdAt", "timestamp").map { field -> data[field].asJsonStringOrNull() })
        .asSequence()
        .mapNotNull { it?.toLongOrNull() }
        .firstOrNull { value -> value >= 100_000_000_000L }
    val sessionKey = payload["sessionKey"]?.asJsonStringOrNull()
    if (payload["stream"].asJsonStringOrNull() == "lifecycle") {
      val phase = data["phase"].asJsonStringOrNull()?.lowercase() ?: return
      val accepted =
        when (phase) {
          "start" -> realtimeAgentCoordinator.beginGatewayRelayConsult(runId, sessionKey, eventTimestampMs)
          "end", "final", "error", "aborted" -> realtimeAgentCoordinator.endGatewayRelayConsult(runId, sessionKey)
          else -> false
        }
      if (accepted) {
        if (phase in setOf("end", "final", "error", "aborted")) settleActiveToolsForRun(runId)
        Log.d(tag, "realtime consult lifecycle accepted phase=$phase")
        synchronized(realtimeCapturePauseLock) {
          if (phase == "start") {
            realtimeVoiceAgentLifecycleActive = true
            realtimeVoiceIntentHasAgentLifecycle = true
          }
          // Agent completion acknowledges text generation, not physical voice delivery.
          // Silence packets must not let the quiet timer cut off the pending answer.
          if (phase == "end" || phase == "final") {
            realtimeVoiceAgentLifecycleActive = false
            realtimeAwaitingConsultPlayback = true
            onVoiceAgentCompleted()
            // A progress sentence may have physically drained before the consult finishes.
            // Its follow-up window cannot own the still-pending final answer.
            conversationPostAssistantFollowUpDeadlineMs = 0L
            conversationPostAssistantFollowUpSpeechStartedAtMs = 0L
            conversationPostAssistantFollowUpSpeechUntilMs = 0L
          }
          if (phase == "error" || phase == "aborted") {
            realtimeVoiceAgentLifecycleActive = false
            realtimeAwaitingConsultPlayback = false
          }
          scheduleConversationIdleCheckLocked()
        }
      }
      return
    }
    if (payload["stream"].asJsonStringOrNull() != "tool") return
    if (!realtimeAgentCoordinator.ownsAgentEvent(runId, sessionKey, eventTimestampMs)) return
    val callId = data["toolCallId"].asJsonStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return
    val key = "$runId:$callId"
    when (data["phase"].asJsonStringOrNull()) {
      "start" -> {
        val rawName = data["name"].asJsonStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return
        val detail = jarvisToolDetail(rawName, data["args"].asObjectOrNull())
        publishToolActivity(key, callId, jarvisToolDisplayName(rawName), JarvisToolPhase.Running, detail)
      }
      "result" -> {
        val current = synchronized(agentToolLock) { activeAgentTools[key] } ?: return
        val failed =
          !data["toolErrorSummary"].asJsonStringOrNull().isNullOrBlank() ||
            data["isError"].asBooleanOrNull() == true
        val phase = if (failed) JarvisToolPhase.Failed else JarvisToolPhase.Completed
        publishToolActivity(key, callId, current.name, phase)
      }
      "end" -> {
        // `end` alone does not establish success or failure and must not erase a result.
        val current = synchronized(agentToolLock) { activeAgentTools[key] } ?: return
        if (current.phase == JarvisToolPhase.Running) {
          publishToolActivity(key, callId, current.name, JarvisToolPhase.Unknown)
        }
      }
    }
  }

  private fun handleNonRealtimeAgentChatEvent(
    sessionKey: String?,
    runId: String,
    state: String,
    message: JsonElement?,
  ) {
    val activeSession = activeTalkSessionKey()
    if (sessionKey != null && sessionKey != activeSession) return

    // If this is a response we initiated, handle normally below.
    // Otherwise, if ttsOnAllResponses, finish streaming TTS on terminal events.
    val pending = pendingRunId
    val knownRun = pending == runId || hasRunCompletion(runId)
    if (!knownRun) {
      // A live realtime relay owns speech, including gateway-run consult answers;
      // local TTS would repeat them on a media stream the mic's AEC does not cancel.
      if (ttsOnAllResponses && state == "final" && realtimeSessionId == null) {
        val text = ChatEventText.assistantTextFromMessage(message)
        if (!text.isNullOrBlank()) {
          playTtsForText(text)
        }
      }
      return
    }
    Log.d(tag, "chat event arrived runId=$runId state=$state pendingRunId=$pendingRunId")
    val terminal =
      when (state) {
        "final" -> true
        "aborted", "error" -> false
        else -> null
      } ?: return
    // Cache text from final event so we never need to poll chat.history
    if (terminal) {
      val text = ChatEventText.assistantTextFromMessage(message)
      if (!text.isNullOrBlank()) {
        synchronized(completedRunsLock) {
          completedRunTexts[runId] = text
          while (completedRunTexts.size > maxCachedRunCompletions) {
            completedRunTexts.entries.firstOrNull()?.let { completedRunTexts.remove(it.key) }
          }
        }
      }
    }
    cacheRunCompletion(runId, terminal)

    if (runId != pendingRunId) return
    pendingFinal?.complete(terminal)
    pendingFinal = null
    pendingRunId = null
  }

  internal suspend fun runE2eRealtimeTurn(
    userText: String,
    assistantText: String,
    timeoutMs: Long,
  ) {
    if (!_isEnabled.value) {
      setEnabled(true)
    }
    val sessionId = awaitRealtimeSessionId(timeoutMs)
    handleGatewayEvent("talk.event", realtimeTranscriptPayload(sessionId = sessionId, role = "user", text = userText))
    handleGatewayEvent("talk.event", realtimeTranscriptPayload(sessionId = sessionId, role = "assistant", text = assistantText))
  }

  /** Enables or disables local assistant audio playback and stops active audio when disabled. */
  fun setPlaybackEnabled(enabled: Boolean) {
    synchronized(playbackLock) {
      if (playbackEnabled == enabled) return
      playbackEnabled = enabled
    }
    if (!enabled) {
      stopRealtimePlayback()
      cancelActivePlayback()
    }
  }

  suspend fun refreshConfig() {
    invalidateConfig()
    ensureConfigLoaded()
  }

  /** Uses a current, gateway-scoped configuration without forcing an extra wake-path RPC. */
  internal suspend fun ensureRealtimeConfig() {
    ensureConfigLoaded()
  }

  internal suspend fun resolveRealtimeLanguageHint(requestedLanguage: String?): String? {
    ensureConfigLoaded()
    return resolveRealtimeTranscriptionLanguageHint(
      configuredLocaleTag = speechLocale,
      requestedLanguage = requestedLanguage,
      deviceLocaleTag = Locale.getDefault().toLanguageTag(),
    )
  }

  suspend fun speakAssistantReply(text: String) {
    if (!playbackEnabled) return
    val playbackToken = cancelActivePlayback()
    ensureConfigLoaded()
    playAssistant(text, playbackToken)
  }

  private fun start() {
    if (realtimeSessionId != null || realtimeCaptureJob?.isActive == true) return
    if (scope.coroutineContext[Job]?.isActive == false) return
    val notifyStopped = captureRelayStopNotification()
    val generation =
      synchronized(realtimeCapturePauseLock) {
        relayStopNotification = notifyStopped
        stopRequested = false
        listeningMode = true
        startGeneration.incrementAndGet()
      }
    Log.d(tag, "start")
    gatewayWorkScope.launch {
      try {
        audioRetirement.await()
        ensureConfigLoaded()
        if (generation != startGeneration.get() || !_isEnabled.value || stopRequested) return@launch
        val route = configCache.get().value.route
        if (route == TalkModeRoute.RealtimeRelay) {
          startRealtimeRelay(generation)
        } else {
          startNativeTalk(generation, route)
        }
      } catch (err: Throwable) {
        if (err is CancellationException) return@launch
        Log.w(tag, "start failed: ${err.message ?: err::class.simpleName}")
        disableRealtimeModeAndNotifyOwner(generation, nativeText("Start failed: \$message", err.message ?: err::class.simpleName.orEmpty()))
      }
    }
  }

  private fun stop(preserveRealtimeWakeCaptureHold: Boolean = false) {
    onVoiceConversationClosed()
    val cancelled =
      synchronized(realtimeCapturePauseLock) {
        realtimeVoiceAgentLifecycleActive = false
        realtimeVoiceIntentHasAgentLifecycle = false
        stopRequested = true
        listeningMode = false
        activePttCaptureId = null
        startGeneration.incrementAndGet()
        val jobs =
          listOfNotNull(
            synchronized(finishingPttLock) { finishingPttJob },
            pttCompletion,
            pttReleaseCompletion,
            pttTimeoutJob,
            restartJob,
            silenceJob,
            pendingFinal,
          )
        pttAutoStopEnabled = false
        pttCompletion = null
        pttReleaseCompletion = null
        pttTimeoutJob = null
        restartJob = null
        silenceJob = null
        closePushToTalkRung()
        pttFinalSegments.clear()
        pttLivePartial = ""
        lastTranscript = ""
        lastHeardAtMs = null
        _isListening.value = false
        setStatus(nativeText("Off"), state = TalkStatusState.Off)
        stopRealtimeRelay(preserveWakeCaptureHold = preserveRealtimeWakeCaptureHold)
        pendingRunId = null
        pendingFinal = null
        synchronized(completedRunsLock) {
          completedRunStates.clear()
          completedRunTexts.clear()
        }
        retireRecognizer()
        jobs
      }
    // Completion handlers and SystemSpeech's beforeSpeak callback can reenter capture ownership.
    cancelled.forEach { it.cancel() }
    cancelActivePlayback()
    systemSpeech.shutdown()
  }

  private fun retireRecognizer() =
    synchronized(realtimeCapturePauseLock) {
      val retired = recognizer ?: return@synchronized
      recognizer = null
      val completion = CompletableDeferred<Unit>()
      audioRetirement.retire(cleanup = completion)
      val cleanup =
        Runnable {
          try {
            retired.cancel()
            retired.destroy()
            completion.complete(Unit)
          } catch (error: Exception) {
            completion.completeExceptionally(error)
          }
        }
      if (Looper.myLooper() == mainHandler.looper) {
        cleanup.run()
      } else if (!mainHandler.post(cleanup)) {
        completion.completeExceptionally(IllegalStateException("Speech recognizer cleanup unavailable"))
      }
    }

  private suspend fun awaitRealtimeSessionId(timeoutMs: Long): String =
    withTimeout(timeoutMs) {
      while (true) {
        realtimeSessionId?.let { return@withTimeout it }
        val status = currentStatus
        if (!_isEnabled.value && status.state != TalkStatusState.Off) {
          throw IllegalStateException(status.text.resolveNativeText())
        }
        delay(100L)
      }
      error("unreachable")
    }

  private suspend fun startRealtimeRelay(
    generation: Long,
    change: RealtimeVoiceChange? = null,
  ) {
    if (!isConnected()) {
      Log.w(tag, "realtime start: gateway not connected")
      disableRealtimeModeAndNotifyOwner(generation, nativeText("Gateway not connected"))
      return
    }

    val micOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!micOk) {
      Log.w(tag, "realtime start: microphone permission required")
      disableRealtimeModeAndNotifyOwner(generation, nativeText("Microphone permission required"))
      return
    }

    ensureConfigLoaded()
    cancelActivePlayback()
    synchronized(realtimeCapturePauseLock) {
      if (generation != startGeneration.get() || stopRequested) throw CancellationException("realtime talk stopped while connecting")
      if (activePttCaptureId == null) retireRecognizer()
    }
    audioRetirement.await()

    synchronized(realtimeCapturePauseLock) {
      if (generation != startGeneration.get() || !_isEnabled.value || stopRequested) throw CancellationException("realtime talk stopped while connecting")
      setStatus(nativeText("Connecting…"), awaitingAgent = true, route = TalkModeRoute.RealtimeRelay)
    }
    val language = realtimeTranscriptionLanguage(resolvedSpeechLocaleTag())
    val lease = change?.lease ?: session.captureRequestLease(gatewayStableId()) ?: error("Gateway not connected")
    val supportsVoiceSelection = listOf("talk.voice.get", "talk.voice.set", "talk.voice.complete").all(lease::supportsMethod)
    val transportGeneration = change?.gatewayGeneration ?: gatewayGeneration.get()
    val sessionKey = change?.sessionKey ?: activeTalkSessionKey()
    val create: suspend (String?) -> String = { requestedLanguage ->
      val params =
        buildJsonObject {
          put("sessionKey", JsonPrimitive(sessionKey))
          put("mode", JsonPrimitive("realtime"))
          put("transport", JsonPrimitive("gateway-relay"))
          put("brain", JsonPrimitive("agent-consult"))
          if (supportsVoiceSelection) put("capabilities", JsonArray(listOf(JsonPrimitive("voice-selection"))))
          if (change != null) {
            put("voiceChangeId", JsonPrimitive(change.id))
            put("voice", JsonPrimitive(change.voice))
          }
          requestedLanguage?.let { put("language", JsonPrimitive(it)) }
        }
      lease.request("talk.session.create", params.toString(), timeoutMs = 15_000) { enqueue ->
        synchronized(realtimeCapturePauseLock) {
          if (generation != startGeneration.get() || transportGeneration != gatewayGeneration.get() || !_isEnabled.value || stopRequested) {
            throw CancellationException("realtime talk stopped while connecting")
          }
          if (change != null) assertVoiceChangeCurrent(change)
          enqueue()
        }
      }
    }
    // A replacement is a single claimed operation; never retry it as a fresh call.
    val payload = if (change == null) requestPhoneRealtimeSessionWithLanguageFallback(language, create) else create(language)
    val root = json.parseToJsonElement(payload).asObjectOrNull()
    val relaySession = root?.get("relaySessionId").asJsonStringOrNull()
    val sessionId = relaySession ?: root?.get("sessionId").asJsonStringOrNull()
    if (sessionId.isNullOrBlank()) {
      throw IllegalStateException("talk.session.create returned no session id")
    }
    val admitted =
      synchronized(realtimeCapturePauseLock) {
        if (generation != startGeneration.get() || transportGeneration != gatewayGeneration.get() || !lease.isCurrent() || !_isEnabled.value || stopRequested || (change != null && realtimeVoiceChange !== change)) {
          return@synchronized false
        }
        // Session publication and capture installation are one transition. PTT
        // therefore either blocks startup or detaches every installed capture job.
        realtimeAgentCoordinator.beginSession(
          RealtimeAgentSession(
            relaySessionId = sessionId,
            sessionKey = sessionKey,
          ),
        )
        realtimeSessionId = sessionId
        realtimeSessionKey = sessionKey
        realtimeRequestLease = lease
        val providerReady = CompletableDeferred<Unit>()
        realtimeProviderReady = providerReady
        consumeEarlyRealtimeReadyLocked(sessionId)?.let { providerReady.complete(Unit) }
        realtimeMicDetachedForQuiet = false
        // Consume before installing capture. A later relay, including one after
        // cancellation, must never receive PCM from this wake utterance.
        val wakeSeed = pendingRealtimeWakeSeed.also { pendingRealtimeWakeSeed = null }
        val wakeStartedAtMs = pendingRealtimeWakeStartedAtMs.also { pendingRealtimeWakeStartedAtMs = null }
        val wakeCaptureStartGate = pendingRealtimeWakeCaptureStartGate.also { pendingRealtimeWakeCaptureStartGate = null }
        realtimeWakeStartedAtMs = wakeStartedAtMs
        logWakeLatency("relay-created", wakeStartedAtMs)
        if (change != null) change.replacementSessionId = sessionId
        realtimePlayoutSession = createRealtimePlayoutSession(sessionId, lease)
        val pause = realtimeCapturePause
        if (pause != null) {
          realtimeCapturePause = pause.copy(sessionId = sessionId)
          realtimeOutputSuppressed = true
        } else {
          realtimeOutputSuppressed = false
          _isListening.value = true
          if (change == null) setStatus(nativeText("Listening"))
          startRealtimeCaptureLocked(sessionId, wakeSeed, wakeStartedAtMs, wakeCaptureStartGate, providerReady)
        }
        markConversationActivityLocked()
        scheduleConversationIdleCheckLocked()
        true
      }
    if (!admitted) {
      // Stop never owned this session; the creator must close it even if its job was cancelled.
      withContext(NonCancellable) {
        runCatching { lease.request("talk.session.close", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) }.toString()) }
      }
      throw CancellationException("realtime talk stopped while connecting")
    }
    Log.d(tag, "realtime session ready relaySessionId=$sessionId")
  }

  private fun assertVoiceChangeCurrent(change: RealtimeVoiceChange) {
    check(
      realtimeVoiceChange === change && change.generation == startGeneration.get() &&
        change.gatewayGeneration == gatewayGeneration.get() && change.lease.isCurrent() &&
        _isEnabled.value && !stopRequested,
    ) { "Voice change is no longer active" }
  }

  /** Caller holds [realtimeCapturePauseLock]. */
  private fun rememberEarlyRealtimeReadyLocked(sessionId: String) {
    val now = SystemClock.elapsedRealtime()
    earlyRealtimeReadyAtMs.entries.removeAll { now - it.value > RealtimeOrderedAudioPacer.READY_TIMEOUT_MS }
    while (earlyRealtimeReadyAtMs.size >= 8) {
      earlyRealtimeReadyAtMs.entries.firstOrNull()?.key?.let(earlyRealtimeReadyAtMs::remove) ?: break
    }
    earlyRealtimeReadyAtMs[sessionId] = now
  }

  /** Caller holds [realtimeCapturePauseLock]. */
  private fun consumeEarlyRealtimeReadyLocked(sessionId: String): Long? {
    val now = SystemClock.elapsedRealtime()
    earlyRealtimeReadyAtMs.entries.removeAll { now - it.value > RealtimeOrderedAudioPacer.READY_TIMEOUT_MS }
    return earlyRealtimeReadyAtMs.remove(sessionId)
  }

  private fun handleRealtimeVoiceChange(payloadJson: String?) {
    val event = parseJsonParamsObject(payloadJson) ?: return
    val id = event["changeId"].asJsonStringOrNull()?.takeIf(String::isNotBlank) ?: return
    val originalId = event["voiceSessionId"].asJsonStringOrNull()?.takeIf(String::isNotBlank) ?: return
    val sessionKey = event["sessionKey"].asJsonStringOrNull()?.takeIf(String::isNotBlank) ?: return
    val voice = event["voice"].asJsonStringOrNull()?.takeIf(String::isNotBlank) ?: return
    var stopped: (() -> Unit)? = null
    val change =
      synchronized(realtimeCapturePauseLock) {
        if (event["phase"].asJsonStringOrNull() == "cancelled") {
          val pending = realtimeVoiceChange
          if (pending?.id == id && pending.originalSessionId == originalId && pending.sessionKey == sessionKey) {
            realtimeVoiceChange = null
            setTalkFailure(nativeText("Talk failed: Voice change was cancelled. Start Talk again."))
            stopRealtimeRelay(preserveStatus = true)
            stopped = disableRealtimeModeLocked()
          }
          return@synchronized null
        }
        if (event["phase"].asJsonStringOrNull() != "requested" || realtimeVoiceChange != null ||
          realtimeSessionId != originalId || realtimeSessionKey != sessionKey ||
          !_isEnabled.value || stopRequested || !realtimeRelayModelSupported
        ) {
          return@synchronized null
        }
        val lease = realtimeRequestLease?.takeIf { it.isCurrent() } ?: return@synchronized null
        RealtimeVoiceChange(id, originalId, sessionKey, voice, lease, startGeneration.get(), gatewayGeneration.get()).also {
          realtimeVoiceChange = it
        }
      }
    stopped?.invoke()
    if (change == null) return
    gatewayWorkScope.launch {
      var sourceCloseQueued = false

      suspend fun closeSource() {
        change.lease.request("talk.session.close", buildJsonObject { put("sessionId", JsonPrimitive(change.originalSessionId)) }.toString()) { enqueue ->
          enqueue()
          sourceCloseQueued = true
        }
      }
      try {
        withTimeout(70_000) {
          synchronized(realtimeCapturePauseLock) {
            assertVoiceChangeCurrent(change)
            stopRealtimeRelay(closeSession = false, preserveVoiceChange = true)
            setStatus(nativeText("Connecting…"), awaitingAgent = true)
          }
          // Closing the source first lets the Gateway seed the replacement from its final history.
          // The pending voice-change owner preserves accepted work; output cancellation would abort it.
          closeSource()
          audioRetirement.await()
          synchronized(realtimeCapturePauseLock) { assertVoiceChangeCurrent(change) }
          startRealtimeRelay(change.generation, change)
          completeRealtimeVoiceChange(change)
          synchronized(realtimeCapturePauseLock) {
            assertVoiceChangeCurrent(change)
            realtimeVoiceChange = null
            if (!isRealtimeCapturePaused()) setStatus(nativeText("Listening"))
          }
        }
      } catch (error: Throwable) {
        // Preserve the pending handoff while closing a source whose request never reached the socket.
        if (!sourceCloseQueued) withContext(NonCancellable) { runCatching { closeSource() } }
        val notify =
          synchronized(realtimeCapturePauseLock) {
            if (realtimeVoiceChange !== change) return@synchronized null
            setTalkFailure(nativeText("Talk failed: Could not change voice. Start Talk again."))
            stopRealtimeRelay(preserveStatus = true)
            disableRealtimeModeLocked()
          }
        notify?.invoke()
        if (error is CancellationException) throw error
        Log.w(tag, "realtime voice change failed: ${error.message}")
      }
    }
  }

  private suspend fun completeRealtimeVoiceChange(change: RealtimeVoiceChange) {
    while (true) {
      val ready =
        synchronized(realtimeCapturePauseLock) {
          assertVoiceChangeCurrent(change)
          if (realtimeCapturePause != null) null else checkNotNull(realtimeCaptureReady) { "Replacement microphone is unavailable" }
        }
      try {
        ready?.await()
      } catch (error: Throwable) {
        currentCoroutineContext().ensureActive()
        synchronized(realtimeCapturePauseLock) {
          assertVoiceChangeCurrent(change)
          if (realtimeCaptureReady === ready && realtimeCapturePause == null) throw error
        }
        continue
      }
      val params =
        buildJsonObject {
          put("changeId", JsonPrimitive(change.id))
          put("voiceSessionId", JsonPrimitive(checkNotNull(change.replacementSessionId)))
          put("outcome", JsonPrimitive("ready"))
        }
      try {
        val response =
          change.lease.request("talk.voice.complete", params.toString(), timeoutMs = 70_000) { enqueue ->
            synchronized(realtimeCapturePauseLock) {
              assertVoiceChangeCurrent(change)
              val currentReady = realtimeCaptureReady
              // PTT may replace the recorder while this request waits for the socket.
              if (realtimeCapturePause == null && (currentReady == null || !currentReady.isCompleted || currentReady.isCancelled || realtimeAudioInput == null)) {
                throw RealtimeCaptureChanged()
              }
              enqueue()
            }
          }
        check(
          json
            .parseToJsonElement(response)
            .asObjectOrNull()
            ?.get("ok")
            .asBooleanOrNull() == true,
        ) { "Voice change was not confirmed" }
        return
      } catch (_: RealtimeCaptureChanged) {
        currentCoroutineContext().ensureActive()
      }
    }
  }

  private fun cancelRealtimeVoiceChange() {
    val change = realtimeVoiceChange ?: return
    realtimeVoiceChange = null
    gatewayWorkScope.launch {
      runCatching {
        val params =
          buildJsonObject {
            put("changeId", JsonPrimitive(change.id))
            change.replacementSessionId?.let { put("voiceSessionId", JsonPrimitive(it)) }
            put("outcome", JsonPrimitive("failed"))
            put("error", JsonPrimitive("Android voice call stopped before the voice change completed"))
          }
        change.lease.request("talk.voice.complete", params.toString(), timeoutMs = 5_000)
      }
    }
  }

  private suspend fun startNativeTalk(
    generation: Long,
    route: TalkModeRoute,
  ) {
    if (!isConnected()) {
      disableRealtimeModeAndNotifyOwner(generation, nativeText("Gateway not connected"))
      return
    }
    val micOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (!micOk) {
      disableRealtimeModeAndNotifyOwner(generation, nativeText("Microphone permission required"))
      return
    }
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      disableRealtimeModeAndNotifyOwner(generation, nativeText("Speech recognizer unavailable"))
      return
    }
    synchronized(realtimeCapturePauseLock) {
      if (generation != startGeneration.get() || !_isEnabled.value || stopRequested) return
      retireRecognizer()
    }
    audioRetirement.await()
    withContext(Dispatchers.Main) {
      synchronized(realtimeCapturePauseLock) {
        if (generation != startGeneration.get() || !_isEnabled.value || stopRequested) return@withContext
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(recognitionListener(null, it)) }
        // Record the admitted route, not a later config refresh, for this native speech loop.
        setStatus(nativeText("Listening"), route = route)
        startListeningInternal(markListening = true)
        startSilenceMonitor()
      }
    }
  }

  private fun disableRealtimeModeAndNotifyOwner(
    generation: Long,
    status: NativeText,
  ) {
    val stopped =
      synchronized(realtimeCapturePauseLock) {
        if (generation != startGeneration.get()) return
        setTalkFailure(status)
        stopRealtimeRelay(closeSession = false, preserveStatus = true)
        disableRealtimeModeLocked()
      }
    stopped?.invoke()
  }

  /** Returned claim is checked by the outer capture owner under its own lock. */
  private fun disableRealtimeModeLocked(): (() -> Unit)? {
    if (!_isEnabled.value) return null
    realtimeVoiceAgentLifecycleActive = false
    realtimeVoiceIntentHasAgentLifecycle = false
    onVoiceConversationClosed()
    _isEnabled.value = false
    _isListening.value = false
    _isCaptureAdmitted.value = false
    val generation = startGeneration.get()
    val notify = relayStopNotification
    return { notify { synchronized(realtimeCapturePauseLock) { startGeneration.get() == generation && !_isEnabled.value } } }
  }

  private fun failRealtimeRelay(
    sessionId: String,
    message: String,
    owner: RealtimePlayout.Session? = null,
    inputGeneration: Long? = null,
  ) {
    val stopped =
      synchronized(realtimeCapturePauseLock) {
        if (realtimeSessionId != sessionId ||
          (owner != null && realtimePlayoutSession !== owner) ||
          (inputGeneration != null && audioInputGeneration.get() != inputGeneration)
        ) {
          return
        }
        setTalkFailure(nativeText("Talk failed: \$message", message))
        stopRealtimeRelay(preserveStatus = true)
        disableRealtimeModeLocked()
      }
    stopped?.invoke()
  }

  private fun handleRealtimeAudioAppendFailure(
    sessionId: String,
    inputGeneration: Long,
    error: GatewaySession.ErrorShape,
  ) {
    if (error.code != "UNAVAILABLE" || error.message != "request timeout") {
      failRealtimeRelay(sessionId, error.message, inputGeneration = inputGeneration)
      return
    }
    synchronized(realtimeCapturePauseLock) {
      if (realtimeSessionId != sessionId || audioInputGeneration.get() != inputGeneration) return
      // A timed-out append may already have reached the provider. Stop uploading
      // rather than replaying it, but retain the answer through the bounded relay.
      detachRealtimeCaptureForQuietLocked()
      realtimeAwaitingConsultPlayback = true
      scheduleConversationIdleCheckLocked()
      status.update { it.copy(text = nativeText("Verbindung verzögert – Antwort wird noch empfangen.")) }
      Log.w(tag, "realtime append acknowledgement timed out; microphone detached, pending reply retained")
    }
  }

  private fun logWakeLatency(
    stage: String,
    wakeStartedAtMs: Long?,
    detail: String = "",
  ) {
    wakeStartedAtMs ?: return
    Log.i("OpenClawWakeLatency", "stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - wakeStartedAtMs}$detail")
  }

  private fun realtimeCloseStatus(reason: String?): TalkStatus =
    when (reason) {
      null, "completed" -> {
        TalkStatus(text = nativeText("Off"), state = TalkStatusState.Off)
      }

      "error" -> {
        TalkStatus(
          text = nativeText("Talk failed: Realtime provider closed unexpectedly."),
          state = TalkStatusState.TalkFailure,
        )
      }

      else -> {
        TalkStatus(
          text = nativeText("Talk failed: Realtime provider closed: \$reason", reason),
          state = TalkStatusState.TalkFailure,
        )
      }
    }

  /** Caller holds [realtimeCapturePauseLock] so PTT cannot miss newly installed jobs. */
  @SuppressLint("MissingPermission")
  private fun startRealtimeCaptureLocked(
    sessionId: String,
    wakeSeed: VoiceWakeAudio? = null,
    wakeStartedAtMs: Long? = null,
    captureStartGate: Deferred<Unit>? = null,
    providerReady: Deferred<Unit>? = realtimeProviderReady,
    armPostAssistantFollowUpAfterReady: Boolean = false,
  ) {
    _isCaptureAdmitted.value = false
    realtimePostOutputTail = null
    val ready = CompletableDeferred<Unit>()
    realtimeCaptureReady = ready
    val lease = realtimeRequestLease
    val routePolicy = realtimePlayoutSession?.routePolicy ?: RealtimeAudioRoutePolicy.Communication
    audioRetirement.retire(realtimeCaptureJob, realtimeAudioInput)
    realtimeAudioInput = null
    realtimeAppendJob?.cancel()
    val inputGeneration = audioInputGeneration.incrementAndGet()
    onAppliedAudioInputChanged(null)
    val droppedLiveFrames = AtomicLong(0L)
    val ingressBudget = RealtimeAudioIngressBudget()
    val backpressureDrops = AtomicLong(0L)
    // The initial source can contain up to twelve seconds of pre-wake handoff PCM. Keep it
    // locally until provider-ready, then feed at bounded lookahead so GPT-Live's five-second
    // pre-media ring never discards the beginning of the command.
    val orderedAudioPacer = providerReady?.let(::RealtimeOrderedAudioPacer)
    realtimeOrderedIngressDraining = orderedAudioPacer != null
    val audioFrames =
      Channel<RealtimeCaptureIngress.Live>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = {
          val undelivered = droppedLiveFrames.incrementAndGet()
          if (undelivered == 1L) {
            Log.w(tag, "realtime live PCM queue has its first undelivered 100ms frame")
          }
        },
      )
    // A completed post-output tail is one bounded packet. It has an independent, suspending
    // admission path so a tail can never fall out of the live DROP_OLDEST channel.
    val postOutputTails = Channel<RealtimeCaptureIngress.PostOutputTail>(capacity = 1)
    val startupFrames = RealtimeStartupPcmQueue<RealtimeCaptureIngress.Live>(maxWakeStartupFrames)
    realtimeAppendJob =
      gatewayWorkScope.launch(realtimeCaptureDispatcher) {
        // The wake recognizer already owns these samples. Send them before the
          // newly opened microphone can append a later utterance. The recorder
          // starts concurrently, but its bounded startup frames remain ordered
          // behind this seed and are never sent through DROP_OLDEST.
        try {
          val seed = wakeSeed?.let(::resampleWakeSeedToRealtimePcm)
          var offset = 0
          while (seed != null && offset < seed.size) {
            val end = minOf(offset + realtimeAudioFrameBytes, seed.size)
            appendRealtimeAudioFrame(
              sessionId,
              inputGeneration,
              lease,
              seed.copyOfRange(offset, end),
              requireCaptureAdmission = false,
              ingressBudget = ingressBudget,
              backpressureDrops = backpressureDrops,
              preserveOrderedFrame = true,
              orderedAudioPacer = orderedAudioPacer,
            )
            offset = end
          }
          val seedSourceBytes = wakeSeed?.pcm16?.size ?: 0
          val seedSourceRateHz = wakeSeed?.sampleRateHz ?: 0
          val seedSourceDurationMs =
            if (seedSourceRateHz > 0) seedSourceBytes.toLong() * 1_000L / (seedSourceRateHz * 2L) else 0L
          val seedDurationMs = seed?.size?.toLong()?.times(1_000L)?.div(realtimeSampleRateHz * 2L) ?: 0L
          logWakeLatency(
            "seed-appended",
            wakeStartedAtMs,
            " sourceBytes=$seedSourceBytes sourceRateHz=$seedSourceRateHz sourceDurationMs=$seedSourceDurationMs resampledBytes=${seed?.size ?: 0} resampledDurationMs=$seedDurationMs",
          )
          startupFrames.beginDrain()
          while (true) {
            val ingress = startupFrames.takeForDrain() ?: break
            try {
              appendRealtimeAudioFrame(
                sessionId,
                inputGeneration,
                lease,
                ingress.frame,
                requireCaptureAdmission = true,
                expectedOutputEpoch = ingress.outputEpoch,
                expectedCaptureStartedAtMs = ingress.frameStartedAtMs,
                ingressBudget = ingressBudget,
                backpressureDrops = backpressureDrops,
                preserveOrderedFrame = true,
                orderedAudioPacer = orderedAudioPacer,
              )
            } catch (_: CancellationException) {
              // A playout epoch can supersede one queued frame while this relay and
              // recorder remain current. Discard that frame, but do not strand the
              // ordered drain or cancel the still-live recorder.
              currentCoroutineContext().ensureActive()
              synchronized(realtimeCapturePauseLock) {
                if (realtimeSessionId != sessionId || audioInputGeneration.get() != inputGeneration) {
                  throw CancellationException("realtime relay replaced")
                }
              }
            }
          }
          finishRealtimeOrderedIngress(sessionId, inputGeneration)
        } catch (_: CancellationException) {
          startupFrames.cancel()
          return@launch
        } catch (err: Throwable) {
          Log.w(tag, "realtime wake seed failed: ${err.message ?: err::class.simpleName}")
          failRealtimeRelay(sessionId, err.message ?: err::class.simpleName ?: "wake seed failed", inputGeneration = inputGeneration)
          startupFrames.cancel()
          return@launch
        }
        var liveFramesClosed = false
        var postOutputTailsClosed = false
        while (!liveFramesClosed || !postOutputTailsClosed) {
          val ingress =
            select<RealtimeCaptureIngress?> {
              if (!postOutputTailsClosed) {
                postOutputTails.onReceiveCatching { received ->
                  if (received.isClosed) {
                    postOutputTailsClosed = true
                    null
                  } else {
                    received.getOrThrow()
                  }
                }
              }
              if (!liveFramesClosed) {
                audioFrames.onReceiveCatching { received ->
                  if (received.isClosed) {
                    liveFramesClosed = true
                    null
                  } else {
                    received.getOrThrow()
                  }
                }
              }
            } ?: continue
          if (realtimeSessionId != sessionId || audioInputGeneration.get() != inputGeneration) continue
          try {
            when (ingress) {
              is RealtimeCaptureIngress.Live -> {
                if (!shouldAppendRealtimeCapturedFrame(ingress.frame.size, ingress.frameStartedAtMs)) continue
                appendRealtimeAudioFrame(
                  sessionId,
                  inputGeneration,
                  lease,
                  ingress.frame,
                  requireCaptureAdmission = true,
                  expectedOutputEpoch = ingress.outputEpoch,
                  expectedCaptureStartedAtMs = ingress.frameStartedAtMs,
                  ingressBudget = ingressBudget,
                  backpressureDrops = backpressureDrops,
                )
              }

              is RealtimeCaptureIngress.PostOutputTail -> {
                orderedAudioPacer?.beginNewSegment()
                for (frame in ingress.frames) {
                  appendRealtimeAudioFrame(
                    sessionId,
                    inputGeneration,
                    lease,
                    frame,
                    requireCaptureAdmission = true,
                    expectedOutputEpoch = ingress.outputEpoch,
                    ingressBudget = ingressBudget,
                    backpressureDrops = backpressureDrops,
                    preserveOrderedFrame = true,
                    orderedAudioPacer = orderedAudioPacer,
                  )
                }
              }

              RealtimeCaptureIngress.RetainedTailFrame -> Unit
            }
          } catch (_: CancellationException) {
            // A rejected frame does not end capture; actual job cancellation does.
            currentCoroutineContext().ensureActive()
          } catch (err: Throwable) {
            Log.w(tag, "realtime appendAudio failed: ${err.message ?: err::class.simpleName}")
            failRealtimeRelay(sessionId, err.message ?: err::class.simpleName ?: "request failed", inputGeneration = inputGeneration)
          }
        }
      }
    realtimeCaptureJob =
      gatewayWorkScope.launch(realtimeCaptureDispatcher) {
        var audioInput: AndroidAudioInputSession? = null
        val captureJob = coroutineContext[Job]
        val isCurrent = { captureJob?.isActive == true && audioInputGeneration.get() == inputGeneration && realtimeSessionId == sessionId }
        try {
          audioRetirement.await()
          captureStartGate?.await()
          val captureGateReleasedAtMs = SystemClock.elapsedRealtime()
          logWakeLatency("capture-gate-released", wakeStartedAtMs)
          currentCoroutineContext().ensureActive()
          if (!isCurrent()) return@launch
          val frameBytes = realtimeAudioFrameBytes
          Log.i("OpenClawRealtimeCapture", "opening input rate=$realtimeSampleRateHz frameBytes=$frameBytes")
          val communicationAudio =
            synchronized(realtimeCapturePauseLock) {
              if (!isCurrent()) throw CancellationException("realtime capture replaced")
              realtimeCommunicationAudio ?: RealtimeCommunicationAudio.open(
                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                isCurrent = {
                  realtimeSessionId == sessionId &&
                    _isEnabled.value &&
                    !stopRequested
                },
                // Focus belongs to the relay, not this particular recorder: quiet detach may
                // retain already-read PCM while an answer is still live.
                onFocusLost = { failRealtimeRelay(sessionId, "audio focus lost") },
                routePolicy = routePolicy,
              ).also { realtimeCommunicationAudio = it }
            }
          val communicationLeaseReadyAtMs = SystemClock.elapsedRealtime()
          logWakeLatency("communication-lease-ready", wakeStartedAtMs)
          val openedAudioInput =
            AndroidAudioInputSession.open(
              context,
              realtimeSampleRateHz,
              frameBytes,
              preferredAudioInputDevice(),
              { key ->
                if (isCurrent()) onAppliedAudioInputChanged(key)
              },
              communication = true,
              communicationLease = communicationAudio,
              routePolicy = routePolicy,
              isCurrent = isCurrent,
              onFocusLost = { failRealtimeRelay(sessionId, "audio focus lost", inputGeneration = inputGeneration) },
            )
          val inputConstructedAtMs = SystemClock.elapsedRealtime()
          logWakeLatency("input-constructed", wakeStartedAtMs)
          Log.i("OpenClawRealtimeCapture", "input constructed")
          audioInput = openedAudioInput
          synchronized(realtimeCapturePauseLock) {
            if (!isCurrent()) return@launch
            realtimeAudioInput = openedAudioInput
          }
          val buffer = ByteArray(frameBytes)
          val inputMetrics = TalkAudioLevel.Pcm16Metrics()
          var inputSpeechFrames = 0
          Log.i("OpenClawRealtimeCapture", "starting AudioRecord")
          audioInput.startRecording()
          val recorderStartedAtMs = SystemClock.elapsedRealtime()
          logWakeLatency("recorder-started", wakeStartedAtMs)
          Log.i(
            "OpenClawRealtimeCapture",
            "startup timing gateToLeaseMs=${communicationLeaseReadyAtMs - captureGateReleasedAtMs} leaseToInputMs=${inputConstructedAtMs - communicationLeaseReadyAtMs} inputToRecordMs=${recorderStartedAtMs - inputConstructedAtMs} gateToRecordMs=${recorderStartedAtMs - captureGateReleasedAtMs}",
          )
          ready.complete(Unit)
           if (armPostAssistantFollowUpAfterReady) {
             synchronized(realtimeCapturePauseLock) {
               armPostAssistantFollowUpAfterCaptureReadyLocked(sessionId, inputGeneration, ready)
             }
           }
          while (isCurrent() && _isEnabled.value) {
            // AudioRecord fills one 100ms buffer synchronously. Keep its start boundary so a
            // callback delivered while read() blocks cannot replay the final assistant phoneme.
            val frameStartedAtMs = SystemClock.elapsedRealtime()
            val read = audioInput.read(buffer, 0, buffer.size, metrics = inputMetrics)
            if (read <= 0) continue
            // Quiet detach can cancel AudioRecord while its blocking read is returning.
            // Do not classify or queue that final physical-recorder frame.
            if (!isCurrent()) break
            val rawInputRms = inputMetrics.rms
            _inputAudioLevel.value =
              TalkAudioLevel.smoothed(_inputAudioLevel.value, inputMetrics.level)
            val ingress = classifyRealtimeCapturedFrame(sessionId, inputGeneration, frameStartedAtMs, buffer, read)
            // Room tail PCM can be loud without being a new person speaking. It is forwarded
            // after the no-AEC tail, but only fresh live frames may extend local follow-up time.
            val captureAdmitted = ingress is RealtimeCaptureIngress.Live
            inputSpeechFrames =
              JarvisConversationLifecycle.nextInputSpeechFrames(
                previousFrames = inputSpeechFrames,
                captureAdmitted = captureAdmitted,
                rms = rawInputRms,
              )
            if (inputSpeechFrames >= JarvisConversationLifecycle.INPUT_SPEECH_CONSECUTIVE_FRAMES) {
              recordRealtimeInputSpeech(sessionId)
            }
            when (ingress) {
              is RealtimeCaptureIngress.Live ->
                when (startupFrames.offer(ingress)) {
                  RealtimeStartupPcmOffer.Buffered -> Unit
                  RealtimeStartupPcmOffer.Live -> {
                    check(audioFrames.trySend(ingress).isSuccess) { "realtime live PCM queue closed" }
                  }

                  RealtimeStartupPcmOffer.Overflow -> error("realtime wake startup PCM queue overflow")
                  RealtimeStartupPcmOffer.Cancelled -> throw CancellationException("realtime wake startup PCM cancelled")
                }
              is RealtimeCaptureIngress.PostOutputTail -> postOutputTails.send(ingress)
              RealtimeCaptureIngress.RetainedTailFrame, null -> Unit
            }
          }
        } catch (err: Throwable) {
          if (err is CancellationException) throw err
          ready.completeExceptionally(err)
          Log.w(tag, "realtime capture failed: ${err.message ?: err::class.simpleName}")
          failRealtimeRelay(sessionId, err.message ?: err::class.simpleName ?: "capture failed", inputGeneration = inputGeneration)
        } finally {
          val retainRecorderOwnedFrames =
            synchronized(realtimeCapturePauseLock) {
              realtimeSessionId == sessionId &&
                audioInputGeneration.get() == inputGeneration &&
                realtimeMicDetachedForQuiet &&
                realtimeAppendJob?.isActive == true
            }
          if (!retainRecorderOwnedFrames) startupFrames.cancel()
          audioFrames.close()
          postOutputTails.close()
          droppedLiveFrames.get().takeIf { it > 0L }?.let { undelivered ->
            Log.w(tag, "realtime live PCM queue retired with ${undelivered * realtimeAudioFrameMs}ms across $undelivered undelivered frames")
          }
          backpressureDrops.get().takeIf { it > 0L }?.let { dropped ->
            Log.w(tag, "realtime gateway backpressure dropped ${dropped * realtimeAudioFrameMs}ms across $dropped live frames")
          }
          synchronized(realtimeCapturePauseLock) {
            if (realtimeAudioInput === audioInput) {
              realtimeAudioInput = null
              _isCaptureAdmitted.value = false
            }
          }
          audioInput?.close()
        }
      }
    realtimeCaptureJob?.invokeOnCompletion {
      // A PTT pause can retire the recorder before it starts; its owner intentionally owns input.
      if (it is CancellationException) {
        ready.complete(Unit)
      } else if (!ready.isCompleted) {
        ready.completeExceptionally(it ?: IllegalStateException("Microphone closed before readiness"))
      }
    }
  }

  /**
   * The recorder can have been physically retired by the quiet timer while its already-read
   * startup frames drain. Only after that finite source is delivered may a completed reply
   * re-open a new microphone generation.
   */
  private fun finishRealtimeOrderedIngress(sessionId: String, inputGeneration: Long) {
    synchronized(realtimeCapturePauseLock) {
      if (realtimeSessionId != sessionId || audioInputGeneration.get() != inputGeneration) return
      realtimeOrderedIngressDraining = false
      val awaitingOrderedIngress = realtimeFollowUpAwaitingOrderedIngress
      realtimeFollowUpAwaitingOrderedIngress = false
      val postAssistantDeadlineMs = conversationPostAssistantFollowUpDeadlineMs
      val now = SystemClock.elapsedRealtime()
      if (realtimeMicDetachedForQuiet && postAssistantDeadlineMs > now) {
        // Resume only for the remainder of the physical drain's existing window.
        resumeRealtimeCaptureAfterQuietLocked()
      } else if (realtimeMicDetachedForQuiet && awaitingOrderedIngress && postAssistantDeadlineMs > 0L) {
        // Ordered PCM may finish after capture's privacy boundary. Give actual provider
        // completion a small bounded chance, then retire quiet old audio without waiting 90s.
        realtimePostDrainFinalizationDeadlineMs =
          now + RealtimeOrderedAudioPacer.MAX_LOOKAHEAD_MS +
            JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_FINAL_GRACE_MS
      }
      scheduleConversationIdleCheckLocked()
    }
  }

  /** Caller holds [realtimeCapturePauseLock]. These ownership changes cannot become valid again. */
  private fun isRealtimeAudioFrameIrreversiblySupersededLocked(
    sessionId: String,
    inputGeneration: Long,
    expectedOutputEpoch: Long?,
  ): Boolean =
    realtimeSessionId != sessionId ||
      audioInputGeneration.get() != inputGeneration ||
      (expectedOutputEpoch != null && realtimeOutputEpoch != expectedOutputEpoch)

  /** Caller holds [realtimeCapturePauseLock]. The socket enqueue repeats this after suspension. */
  private fun isRealtimeAudioFrameAdmittedLocked(
    sessionId: String,
    inputGeneration: Long,
    frameSize: Int,
    requireCaptureAdmission: Boolean,
    expectedOutputEpoch: Long?,
    expectedCaptureStartedAtMs: Long?,
  ): Boolean =
    realtimeSessionId == sessionId &&
      audioInputGeneration.get() == inputGeneration &&
      (expectedOutputEpoch == null ||
        (realtimeOutputEpoch == expectedOutputEpoch && !realtimeOutputPresentationPending)) &&
      (!requireCaptureAdmission || shouldAppendRealtimeCapturedFrame(frameSize, expectedCaptureStartedAtMs))

  private suspend fun appendRealtimeAudioFrame(
    sessionId: String,
    inputGeneration: Long,
    lease: GatewaySession.RequestLease?,
    frame: ByteArray,
    requireCaptureAdmission: Boolean,
    expectedOutputEpoch: Long? = null,
    expectedCaptureStartedAtMs: Long? = null,
    ingressBudget: RealtimeAudioIngressBudget,
    backpressureDrops: AtomicLong,
    preserveOrderedFrame: Boolean = false,
    orderedAudioPacer: RealtimeOrderedAudioPacer? = null,
  ) {
    if (frame.isEmpty()) return
    // A replacement relay or output epoch can invalidate a queue of 100 ms startup frames.
    // Reject only ownership changes that cannot become valid again before pacing. Presentation
    // and capture admission remain definitive enqueue-time checks because they are transient.
    synchronized(realtimeCapturePauseLock) {
      if (
        isRealtimeAudioFrameIrreversiblySupersededLocked(
          sessionId,
          inputGeneration,
          expectedOutputEpoch,
        )
      ) {
        throw CancellationException("realtime audio frame superseded")
      }
    }
    val durationMs = ((frame.size.toLong() * 1_000L + realtimeSampleRateHz * 2L - 1L) / (realtimeSampleRateHz * 2L)).coerceAtLeast(1L)
    val reservation =
      if (preserveOrderedFrame) ingressBudget.reserve(durationMs) else ingressBudget.tryReserve(durationMs)
    if (reservation == null) {
      if (backpressureDrops.incrementAndGet() == 1L) {
        Log.w(tag, "realtime gateway audio budget full; dropping fresh live frames without closing relay")
        synchronized(realtimeCapturePauseLock) {
          if (realtimeSessionId == sessionId && audioInputGeneration.get() == inputGeneration) {
            status.update { it.copy(text = nativeText("Audioverbindung stockt – bitte kurz wiederholen.")) }
          }
        }
      }
      return
    }
    try {
    if (preserveOrderedFrame) orderedAudioPacer?.awaitAdmission(durationMs)
    val audioBase64 = Base64.encodeToString(frame, Base64.NO_WRAP)
    val params =
      buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
        put("audioBase64", JsonPrimitive(audioBase64))
        put("timestamp", JsonPrimitive(SystemClock.elapsedRealtime()))
      }
    session.sendRequestFrameForEndpoint(
      lease?.endpointStableId,
      "talk.session.appendAudio",
      params.toString(),
      timeoutMs = 8_000,
      onComplete = { ingressBudget.release(reservation) },
      withEnqueue = { enqueue ->
        // Cancellation or a new relay can claim capture while a frame waits
        // for the socket. The seed deliberately bypasses the live playback
        // gate, but it still has the same relay/generation ownership boundary.
        synchronized(realtimeCapturePauseLock) {
          if (
            !isRealtimeAudioFrameAdmittedLocked(
              sessionId,
              inputGeneration,
              frame.size,
              requireCaptureAdmission,
              expectedOutputEpoch,
              expectedCaptureStartedAtMs,
            )
          ) {
            throw CancellationException("realtime audio frame superseded")
          }
          check(lease?.isCurrent() == true) { "realtime audio connection changed" }
          enqueue()
        }
      },
    ) { error ->
      Log.w(tag, "realtime appendAudio failed: ${error.message}")
      handleRealtimeAudioAppendFailure(sessionId, inputGeneration, error)
    }
    } catch (error: Throwable) {
      // No async waiter owns a request rejected before enqueue. Tickets are idempotent.
      ingressBudget.release(reservation)
      throw error
    }
  }

  /** Deterministic mono PCM16 resampling for the one-shot 16 kHz wake handoff. */
  internal fun resampleWakeSeedToRealtimePcm(source: VoiceWakeAudio): ByteArray {
    if (source.sampleRateHz == realtimeSampleRateHz) return source.pcm16.copyOf()
    val inputFrames = source.pcm16.size / 2
    if (inputFrames == 0) return ByteArray(0)
    val outputFrames = ((inputFrames.toLong() * realtimeSampleRateHz) / source.sampleRateHz).toInt().coerceAtLeast(1)
    val output = ByteArray(outputFrames * 2)
    for (outputIndex in 0 until outputFrames) {
      val numerator = outputIndex.toLong() * source.sampleRateHz
      val left = (numerator / realtimeSampleRateHz).toInt().coerceAtMost(inputFrames - 1)
      val fraction = (numerator % realtimeSampleRateHz).toInt()
      val right = (left + 1).coerceAtMost(inputFrames - 1)
      val leftSample = pcm16LittleEndianSample(source.pcm16, left)
      val rightSample = pcm16LittleEndianSample(source.pcm16, right)
      val sample = leftSample + ((rightSample - leftSample) * fraction) / realtimeSampleRateHz
      output[outputIndex * 2] = sample.toByte()
      output[outputIndex * 2 + 1] = (sample shr 8).toByte()
    }
    return output
  }

  private fun pcm16LittleEndianSample(pcm16: ByteArray, index: Int): Int =
    (pcm16[index * 2].toInt() and 0xff) or (pcm16[index * 2 + 1].toInt() shl 8)

  /** Caller holds [realtimeCapturePauseLock]; the callback is driven by the physical playhead. */
  private fun markRealtimeAudibleOutputPendingLocked() {
    if (realtimeAudioInput?.canCaptureDuringPlayback == true) {
      realtimeOutputPresentationPending = false
      realtimePostOutputTail = null
      return
    }
    _isCaptureAdmitted.value = false
    if (realtimeOutputPresentationPending && realtimePostOutputTail == null) return
    realtimeOutputEpoch += 1
    realtimeOutputPresentationPending = true
    realtimePostOutputTail = null
  }

  /** Caller holds [realtimeCapturePauseLock]; the callback is driven by the physical playhead. */
  private fun beginRealtimePostOutputTailLocked(sessionId: String) {
    if (realtimeAudioInput?.canCaptureDuringPlayback == true) {
      realtimeOutputPresentationPending = false
      realtimePostOutputTail = null
      return
    }
    _isCaptureAdmitted.value = false
    if (!realtimeOutputPresentationPending || realtimeSessionId != sessionId || localMediaPlaybackActive) return
    realtimeOutputPresentationPending = false
    val beganAtMs = SystemClock.elapsedRealtime()
    val untilMs = beganAtMs + noAecEchoTailMs
    realtimePostOutputTail =
      RealtimePostOutputTail(
        sessionId = sessionId,
        inputGeneration = audioInputGeneration.get(),
        outputEpoch = realtimeOutputEpoch,
        beganAtMs = beganAtMs,
        untilMs = untilMs,
      )
    Log.d(tag, "realtime post-output tail armed epoch=$realtimeOutputEpoch durationMs=$noAecEchoTailMs")
  }

  /**
   * Keeps only mic PCM which begins after the actual AudioTrack playhead crossed the final
   * audible frame. Those frames wait through the bounded no-AEC room tail, then enter the one
   * append owner as a single ordered packet before fresh live input.
   */
  private fun classifyRealtimeCapturedFrame(
    sessionId: String,
    inputGeneration: Long,
    frameStartedAtMs: Long,
    source: ByteArray,
    length: Int,
  ): RealtimeCaptureIngress? =
    synchronized(realtimeCapturePauseLock) {
      if (
        length <= 0 ||
          realtimeSessionId != sessionId ||
          audioInputGeneration.get() != inputGeneration ||
          realtimeMicDetachedForQuiet
      ) return@synchronized null
      if (pendingRealtimeOutputClear != null || realtimeCapturePause != null || localMediaPlaybackActive) {
        realtimePostOutputTail = null
        _isCaptureAdmitted.value = false
        return@synchronized null
      }
      if (!isRealtimeWakeCaptureFrameAdmittedLocked(frameStartedAtMs)) {
        _isCaptureAdmitted.value = false
        return@synchronized null
      }
      if (realtimeAudioInput?.canCaptureDuringPlayback == true) {
        realtimeOutputPresentationPending = false
        realtimePostOutputTail = null
        _isCaptureAdmitted.value = _isListening.value
        return@synchronized RealtimeCaptureIngress.Live(realtimeOutputEpoch, frameStartedAtMs, source.copyOf(length))
      }
      if (realtimeOutputPresentationPending) {
        _isCaptureAdmitted.value = false
        return@synchronized null
      }
      val now = SystemClock.elapsedRealtime()
      val tail = realtimePostOutputTail
      if (tail != null) {
        if (tail.sessionId != sessionId || tail.inputGeneration != inputGeneration || tail.outputEpoch != realtimeOutputEpoch) {
          realtimePostOutputTail = null
          return@synchronized null
        }
        if (frameStartedAtMs < tail.beganAtMs) {
          _isCaptureAdmitted.value = false
          return@synchronized null
        }
        if (tail.frames.size < maxPostOutputTailFrames) tail.frames.addLast(source.copyOf(length))
        if (now < tail.untilMs) {
          _isCaptureAdmitted.value = false
          return@synchronized RealtimeCaptureIngress.RetainedTailFrame
        }
        realtimePostOutputTail = null
        _isCaptureAdmitted.value = false
        return@synchronized RealtimeCaptureIngress.PostOutputTail(tail.outputEpoch, tail.frames.toList())
      }
      _isCaptureAdmitted.value = _isListening.value
      RealtimeCaptureIngress.Live(realtimeOutputEpoch, frameStartedAtMs, source.copyOf(length))
    }

  private fun isRealtimeWakeCaptureFrameAdmittedLocked(frameStartedAtMs: Long): Boolean {
    val hold = realtimeWakeCaptureHold ?: return true
    val releaseAtMs = hold.releaseAtMs ?: return false
    if (frameStartedAtMs < releaseAtMs) return false
    realtimeWakeCaptureHold = null
    return true
  }

  private fun shouldAppendRealtimeCapturedFrame(length: Int): Boolean =
    shouldAppendRealtimeCapturedFrame(length, frameStartedAtMs = null)

  private fun shouldAppendRealtimeCapturedFrame(
    length: Int,
    frameStartedAtMs: Long?,
  ): Boolean =
    length > 0 &&
      pendingRealtimeOutputClear == null &&
      realtimeCapturePause == null &&
      !localMediaPlaybackActive &&
      (frameStartedAtMs == null || isRealtimeWakeCaptureFrameAdmittedLocked(frameStartedAtMs)) &&
      (realtimeAudioInput?.canCaptureDuringPlayback == true ||
        (!realtimeOutputPresentationPending && realtimePostOutputTail == null))

  // Playback retains the response that admitted it, even after newer work starts.
  private fun realtimeOutputStatusOwner(turnId: String?): TalkStatusOwner {
    if (turnId.isNullOrBlank()) {
      return currentStatus.owner.also { realtimeOutputTurn = RealtimeOutputTurn(null, it) }
    }
    realtimeOutputOwners[turnId]?.let { return it }

    val current = currentStatus
    // A typed provider turn is authoritative only after responseStarted has admitted that same
    // turn. Retain the old no-responseStarted behavior for an otherwise idle legacy relay, but
    // never let an unrecognized typed callback adopt pending consult work: it may be delayed PCM
    // from an evicted, earlier turn.
    val ownsCurrentStatus =
      current.owner.responseTurnId == turnId ||
        (!current.awaitingAgent && current.owner.responseTurnId == null)
    // Unknown old callbacks can arrive after their bounded history was evicted. Give those
    // callbacks a detached owner: audible playback remains correct, while status completion
    // cannot clear the current response.
    val owner = if (ownsCurrentStatus) current.owner else TalkStatusOwner(turnId)
    if (realtimeOutputOwners.size >= maxRetainedRealtimeOutputOwners) {
      realtimeOutputOwners.entries.iterator().also { iterator ->
        if (iterator.hasNext()) {
          iterator.next()
          iterator.remove()
        }
      }
    }
    realtimeOutputOwners[turnId] = owner
    if (ownsCurrentStatus) realtimeOutputTurn = RealtimeOutputTurn(turnId, owner)
    return owner
  }

  private fun realtimeOutputStatusOwnerForTurn(turnId: String?): TalkStatusOwner? =
    turnId?.takeIf(String::isNotBlank)?.let { id ->
      realtimeOutputOwners[id] ?: realtimeOutputTurn?.takeIf { it.id == id }?.statusOwner
    }

  private fun handleRealtimeTalkEvent(
    payloadJson: String?,
    talkEventReceivedAtMs: Long,
  ) {
    val obj = parseJsonParamsObject(payloadJson) ?: return
    val sessionId = obj["relaySessionId"].asJsonStringOrNull() ?: obj["sessionId"].asJsonStringOrNull()
    var stopped: (() -> Unit)? = null
    var afterDispatch: (() -> Unit)? = null
    var explicitFarewellSessionId: String? = null
    var explicitFarewellSleepRequested = false
    synchronized(realtimeCapturePauseLock) {
      val currentSessionId = realtimeSessionId
      val type = obj["type"].asJsonStringOrNull()
      if (currentSessionId == null || sessionId != currentSessionId) {
        if (type == "ready" && !sessionId.isNullOrBlank()) rememberEarlyRealtimeReadyLocked(sessionId)
        return
      }
      val owner = realtimePlayoutSession
      val turnId = obj["talkEvent"].asObjectOrNull()?.get("turnId").asJsonStringOrNull()
      when (type) {
        "consult.pending" -> {
          val requestId = obj["requestId"].asJsonStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return
          when (obj["phase"].asJsonStringOrNull()) {
            "start" -> {
              if (requestId !in realtimePendingConsultRequestIds) {
                if (realtimePendingConsultRequestIds.size < maxPendingRealtimeConsultRequests) {
                  realtimePendingConsultRequestIds += requestId
                } else {
                  realtimePendingConsultOverflow = true
                  Log.w(tag, "realtime pending consult admission overflow")
                }
              }
              scheduleConversationIdleCheckLocked()
            }
            "end" -> {
              realtimePendingConsultRequestIds.remove(requestId)
              scheduleConversationIdleCheckLocked()
            }
          }
        }

        "ready" -> {
          realtimeProviderReady?.complete(Unit)
          if (realtimeVoiceChange != null) return
          if (isRealtimeCapturePaused() || realtimeMicDetachedForQuiet) return
          _isListening.value = true
          setStatus(nativeText("Listening"))
        }

        "inputAudio" -> {
          if (realtimeCapturePause != null || realtimeMicDetachedForQuiet) return
          // Output remains suppressed through the cancelled pre-PTT turn. The
          // first accepted resumed frame establishes the next provider turn.
          realtimeOutputSuppressed = false
          _isListening.value = true
        }

        "responseStarted" -> {
          val responseTurnId = obj["turnId"].asJsonStringOrNull()?.takeIf(String::isNotBlank) ?: return
          if (realtimeOutputSuppressed || realtimeOutputTurn?.id == responseTurnId) return
          // Turn IDs are provider-session scoped. If a relay ever reuses one, a fresh response
          // must own its new status instead of inheriting a retired callback owner.
          realtimeOutputOwners.remove(responseTurnId)
          setStatus(TalkStatus(nativeText("Thinking…"), TalkStatusState.Active, awaitingAgent = true, owner = TalkStatusOwner(responseTurnId)))
          realtimeOutputStatusOwner(responseTurnId)
        }

        "audio" -> {
          if (realtimeOutputSuppressed) return
          if (turnId.isNullOrBlank()) return
          realtimeWakeStartedAtMs?.let { startedAtMs ->
            logWakeLatency("first-assistant-audio", startedAtMs)
            realtimeWakeStartedAtMs = null
          }
          val statusOwner = realtimeOutputStatusOwner(turnId)
          finishRealtimeConversationEntry(VoiceConversationRole.User)
          val audioBase64 = obj["audioBase64"].asJsonStringOrNull() ?: return
          val bytes =
            try {
              Base64.decode(audioBase64, Base64.DEFAULT)
            } catch (err: Throwable) {
              Log.w(tag, "realtime audio decode failed: ${err.message ?: err::class.simpleName}")
              return
            }
          // Arm before the asynchronous playout actor can advance its playhead. The actor also
          // reports fresh PCM for direct/unit callers, but must not create a second epoch here.
          if (playbackEnabled && TalkAudioLevel.pcm16Level(bytes, bytes.size) > audiblePcmLevel) {
            markRealtimeAudibleOutputPendingLocked()
          }
          if (playbackEnabled) {
            afterDispatch = owner?.let {
              realtimePlayout.audio(it, bytes, statusOwner, talkEventReceivedAtMs)
            }
          }
        }

        "audioDone" -> {
          val statusOwner = realtimeOutputStatusOwnerForTurn(turnId) ?: return
          // Empty responses settle too; queued PCM still owns its physical drain.
          afterDispatch = owner?.let { realtimePlayout.audioDone(it, statusOwner) }
        }

        "clear" -> {
          val cancelsTurn = obj["talkEvent"].asObjectOrNull()?.get("type").asJsonStringOrNull() == "turn.cancelled"
          val activeTurnId = realtimeOutputTurn?.id
          if (cancelsTurn && !turnId.isNullOrBlank() && activeTurnId != null && turnId != activeTurnId) return
          // Provider clears flush the sink; only turn.cancelled acknowledges cancellation.
          val pending = pendingRealtimeOutputClear.takeIf { cancelsTurn && !turnId.isNullOrBlank() }
          val cleared = stopRealtimePlayback(owner)
          afterDispatch = {
            cleared.invokeOnCompletion { error -> pending?.complete(if (error == null) turnId else null) }
          }
        }

        "mark" -> {
          val markName = obj["markName"].asJsonStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return
          afterDispatch = owner?.let { realtimePlayout.mark(it, markName) }
        }

        "transcript" -> {
          val role = obj["role"].asJsonStringOrNull()
          val isFinal = obj["final"].asBooleanOrNull() == true
          val statusOwner = if (role == "assistant") realtimeOutputStatusOwner(turnId) else currentStatus.owner
          val text = realtimeTranscriptText(obj["text"].asJsonStringOrNull(), isFinal)
          if (text != null) {
            when (role) {
              "user" -> {
                if (realtimeMicDetachedForQuiet) {
                  // These are delayed results for PCM captured before detachment.
                  // Preserve the caption without renewing or reopening the microphone.
                  if (isFinal) {
                    upsertRealtimeConversation(VoiceConversationRole.User, text, true)
                    // The Gateway may register the consult before this final transcript arrives.
                    // Keep that ownership; only a truly new turn after a completed lifecycle is direct.
                    realtimeVoiceIntentHasAgentLifecycle = realtimeVoiceAgentLifecycleActive
                    onFinalVoiceTranscript(text)
                    realtimeLateDetachedUserFinalPending = true
                    ensureConversationIdleCheckLocked()
                  }
                  return@synchronized
                }
                val now = SystemClock.elapsedRealtime()
                val postAssistantFollowUpDeadlineMs = conversationPostAssistantFollowUpDeadlineMs
                val followUpIsStillAdmitted =
                  postAssistantFollowUpDeadlineMs == 0L ||
                    now <= postAssistantFollowUpDeadlineMs ||
                    now <= conversationPostAssistantFollowUpSpeechUntilMs
                if (!followUpIsStillAdmitted) {
                  // The relay can finish transcribing room speech after Android has reached
                  // its privacy boundary. Do not let that late text renew the session.
                  ensureConversationIdleCheckLocked()
                  return@synchronized
                }
                if (postAssistantFollowUpDeadlineMs > 0L && !isFinal) {
                  // A partial received inside the handoff proves that audio reached
                  // transcription even if the local level detector missed quiet speech.
                  // Allow its final to arrive, without renewing the handoff or admitting
                  // an endless stream of later room partials.
                  if (now <= postAssistantFollowUpDeadlineMs) {
                    conversationPostAssistantFollowUpSpeechUntilMs = maxOf(
                      conversationPostAssistantFollowUpSpeechUntilMs,
                      now + JarvisConversationLifecycle.INPUT_SPEECH_TAIL_MS +
                        JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_FINAL_GRACE_MS,
                    )
                  }
                  upsertRealtimeConversation(VoiceConversationRole.User, text, isFinal = false)
                  conversationUserSpeechUntilMs = minOf(
                    now + JarvisConversationLifecycle.INPUT_SPEECH_TAIL_MS,
                    postAssistantFollowUpDeadlineMs + JarvisConversationLifecycle.INPUT_SPEECH_TAIL_MS,
                  )
                  ensureConversationIdleCheckLocked()
                  return@synchronized
                }
                val deliberateFarewell = isFinal && JarvisConversationLifecycle.isExplicitFarewell(text)
                if (deliberateFarewell) discardUnfinishedRealtimeAssistantEntry()
                upsertRealtimeConversation(VoiceConversationRole.User, text, isFinal)
                if (isFinal) {
                  // The Gateway may register the consult before this final transcript arrives.
                  // Keep that ownership; only a truly new turn after a completed lifecycle is direct.
                  realtimeVoiceIntentHasAgentLifecycle = realtimeVoiceAgentLifecycleActive
                  onFinalVoiceTranscript(text)
                }
                if (postAssistantFollowUpDeadlineMs > 0L) {
                  conversationPostAssistantFollowUpDeadlineMs = 0L
                  conversationPostAssistantFollowUpSpeechStartedAtMs = 0L
                  conversationPostAssistantFollowUpSpeechUntilMs = 0L
                }
                conversationInputSpeechGraceDeadlineMs = 0L
                conversationInputSpeechBudgetExhausted = false
                if (isFinal) {
                  conversationPartialTranscriptMaxWordCount = 0
                  conversationUnfinalizedUtteranceDeadlineMs = 0L
                  markConversationActivityLocked()
                } else {
                  val wordCount = JarvisConversationLifecycle.transcriptWordCount(text)
                  if (wordCount > conversationPartialTranscriptMaxWordCount) {
                    if (conversationUnfinalizedUtteranceDeadlineMs == 0L) {
                      conversationUnfinalizedUtteranceDeadlineMs =
                        now + JarvisConversationLifecycle.INPUT_SPEECH_MAX_GRACE_MS
                    }
                    if (now < conversationUnfinalizedUtteranceDeadlineMs) {
                      conversationPartialTranscriptMaxWordCount = wordCount
                      markConversationActivityLocked()
                    }
                  }
                }
                conversationUserSpeechUntilMs =
                  if (isFinal) 0L else SystemClock.elapsedRealtime() + JarvisConversationLifecycle.INPUT_SPEECH_TAIL_MS
                if (isFinal) {
                  if (deliberateFarewell) {
                    explicitFarewellSessionId = currentSessionId
                    explicitFarewellSleepRequested = JarvisConversationLifecycle.isSleepRequest(text)
                  }
                }
                // A missing final transcript must age out; do not leave capture open forever.
                scheduleConversationIdleCheckLocked()
              }

              "assistant" -> {
                finishRealtimeConversationEntry(VoiceConversationRole.User)
                upsertRealtimeConversation(VoiceConversationRole.Assistant, text, isFinal)
                if (isFinal && !realtimeVoiceIntentHasAgentLifecycle) onDirectVoiceAssistantTranscriptCompleted()
                if (isFinal) onFinalVoiceAssistantSegment()
              }
            }
          }
          if (isFinal && role == "assistant") {
            // Final text can precede audio; refresh after queued playback without closing the turn.
            afterDispatch = owner?.let { realtimePlayout.refreshState(it, statusOwner) }
          }
        }

        "toolCall" -> {
          val callId = obj["callId"].asJsonStringOrNull() ?: return
          val name = obj["name"].asJsonStringOrNull() ?: return
          trackRealtimeRelayToolLocked(callId)
          realtimeAgentCoordinator.handleToolCall(
            callId = callId,
            name = name,
            args = obj["args"],
            forced = obj["forced"].asBooleanOrNull() == true,
          )
        }

        "toolResult" -> {
          obj["callId"].asJsonStringOrNull()?.let(::finishRealtimeRelayToolLocked)
        }

        "error" -> {
          val message = obj["message"].asJsonStringOrNull() ?: "realtime talk error"
          setTalkFailure(nativeText("Talk failed: \$message", message))
          Log.w(tag, "realtime error: $message")
        }

        "close" -> {
          val closeReason = obj["reason"].asJsonStringOrNull()?.trim()?.takeIf(String::isNotEmpty)
          val closeStatus =
            currentStatus.takeIf { it.state == TalkStatusState.TalkFailure } ?: realtimeCloseStatus(closeReason)
          Log.d(tag, "realtime close reason=$closeReason")
          stopRealtimeRelay(closeSession = false, preserveStatus = true)
          if (_isEnabled.value) {
            setStatus(closeStatus)
            stopped = disableRealtimeModeLocked()
          }
        }

        else -> {
          if (type != null) Log.d(tag, "ignored realtime event type=$type")
        }
      }
    }
    afterDispatch?.invoke()
    stopped?.invoke()
    explicitFarewellSessionId?.let {
      closeRealtimeConversation(
        it,
        JarvisConversationCloseReason.ExplicitFarewell,
        sleepRequested = explicitFarewellSleepRequested,
      )
    }
  }

  private fun realtimeTranscriptPayload(
    sessionId: String,
    role: String,
    text: String,
  ): String =
    buildJsonObject {
      put("relaySessionId", JsonPrimitive(sessionId))
      put("type", JsonPrimitive("transcript"))
      put("role", JsonPrimitive(role))
      put("text", JsonPrimitive(text))
      put("final", JsonPrimitive(true))
    }.toString()

  private fun createRealtimePlayoutSession(
    sessionId: String,
    lease: GatewaySession.RequestLease,
  ): RealtimePlayout.Session {
    val generation = gatewayGeneration.get()
    val workScope = gatewayWorkScope
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val routePolicy = selectRealtimeAudioRoutePolicy(
      RealtimeAudioRouteContext(
        outputTypes = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet(),
        inputTypes = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }.toSet(),
        preferredInputKey = preferredAudioInputDevice(),
      ),
    )
    Log.i("TalkAudio", "realtime route policy=$routePolicy sdk=${Build.VERSION.SDK_INT}")
    lateinit var owner: RealtimePlayout.Session

    fun isCurrent() = realtimePlayoutSession === owner && realtimeSessionId == sessionId && gatewayGeneration.get() == generation
    owner =
      RealtimePlayout.Session(
        routePolicy = routePolicy,
        onAudiblePresented = {
          synchronized(realtimeCapturePauseLock) {
            if (isCurrent()) beginRealtimePostOutputTailLocked(sessionId)
          }
        },
        onAudibleDrained = {
          synchronized(realtimeCapturePauseLock) {
            if (isCurrent()) {
              realtimePhysicalAudibleDrainPending = false
              armPostAssistantFollowUpLocked()
              scheduleConversationIdleCheckLocked()
              onVoiceSpeechDrained()
            }
          }
        },
        onState = { playing, level, statusOwner, freshPcm, rawPcmLevel ->
          synchronized(realtimeCapturePauseLock) {
            if (isCurrent()) {
              val audible = setRealtimePlaying(playing, rawPcmLevel, statusOwner, freshPcm)
              if (freshPcm && rawPcmLevel != null && rawPcmLevel > audiblePcmLevel) {
                realtimeAwaitingConsultPlayback = false
                realtimeFollowUpAwaitingOrderedIngress = false
                realtimeFollowUpAwaitingCaptureReady = false
                realtimePostDrainFinalizationDeadlineMs = 0L
                realtimeLateDetachedUserFinalPending = false
                // Output arrival is not presentation. Reject capture until the AudioTrack
                // playhead crosses its final audible frame, then retain only post-end PCM.
                markRealtimeAudibleOutputPendingLocked()
                realtimePhysicalAudibleDrainPending = true
                // Relay timing/silent frames do not keep the cloud microphone open.
                // The follow-up window starts only after physical playback drains.
                conversationAssistantAudibleOutputSeen = true
                conversationPostAssistantFollowUpDeadlineMs = 0L
                conversationPostAssistantFollowUpSpeechStartedAtMs = 0L
                conversationPostAssistantFollowUpSpeechUntilMs = 0L
                conversationInputSpeechGraceDeadlineMs = 0L
                conversationInputSpeechBudgetExhausted = false
              } else if (!playing) {
                // A finite AudioTrack can end after presentation without enough rendered zero
                // PCM to produce onAudibleDrained. `playing=false` is then its physical end.
                realtimePhysicalAudibleDrainPending = false
                armPostAssistantFollowUpLocked()
                scheduleConversationIdleCheckLocked()
                onVoiceSpeechDrained()
              }
              if (!playing || freshPcm) {
                _outputAudioLevel.value = if (freshPcm && rawPcmLevel != null && rawPcmLevel > audiblePcmLevel) level ?: 0f else 0f
              }
              // Device completion is physical; it cannot finish a newer user request's status.
              updateRealtimeOutputStatus(audible, statusOwner)
            }
          }
        },
        onMark = { markName ->
          workScope.launch {
            try {
              if (isCurrent() && owner.active) {
                val acknowledge = realtimeMarkAcknowledger
                if (acknowledge != null) {
                  acknowledge(sessionId, markName)
                } else {
                  val params =
                    buildJsonObject {
                      put("sessionId", JsonPrimitive(sessionId))
                      put("markName", JsonPrimitive(markName))
                    }
                  // The lease binds the physical socket; the enqueue check binds its live relay.
                  lease.request("talk.session.acknowledgeMark", params.toString(), 8_000) { enqueue ->
                    synchronized(realtimeCapturePauseLock) {
                      if (!isCurrent() || !owner.active) throw CancellationException("realtime session replaced")
                      enqueue()
                    }
                  }
                }
              }
            } catch (error: Throwable) {
              if (error is CancellationException) throw error
              Log.d(tag, "realtime mark acknowledgement ignored: ${error.message ?: error::class.simpleName}")
            }
          }
        },
        onFailure = { message -> failRealtimeRelay(sessionId, message, owner) },
      )
    return owner
  }

  private fun stopRealtimePlayback(owner: RealtimePlayout.Session? = realtimePlayoutSession): Deferred<Unit> =
    synchronized(realtimeCapturePauseLock) {
      audioRetirement.retire(cleanup = owner?.takeIf { realtimePlayoutDelegate.isInitialized() }?.let { realtimePlayout.clear(it) })
    }

  private fun stopRealtimeRelay(
    closeSession: Boolean = true,
    preserveStatus: Boolean = false,
    preserveVoiceChange: Boolean = false,
    preserveWakeCaptureHold: Boolean = false,
  ) = synchronized(realtimeCapturePauseLock) {
    conversationIdleJob?.cancel()
    conversationIdleJob = null
    conversationLastActivityAtMs = 0L
    conversationUserSpeechUntilMs = 0L
    conversationInputSpeechUntilMs = 0L
    conversationInputSpeechGraceDeadlineMs = 0L
    conversationInputSpeechBudgetExhausted = false
    conversationAssistantAudibleOutputSeen = false
    conversationPostAssistantFollowUpDeadlineMs = 0L
    conversationPostAssistantFollowUpSpeechStartedAtMs = 0L
    conversationPostAssistantFollowUpSpeechUntilMs = 0L
    conversationPartialTranscriptMaxWordCount = 0
    conversationUnfinalizedUtteranceDeadlineMs = 0L
    realtimeMicDetachedForQuiet = false
    realtimeAwaitingConsultPlayback = false
    realtimeVoiceAgentLifecycleActive = false
    realtimeVoiceIntentHasAgentLifecycle = false
    realtimePendingConsultRequestIds.clear()
    realtimePendingConsultOverflow = false
    realtimeOrderedIngressDraining = false
    realtimeFollowUpAwaitingOrderedIngress = false
    realtimeFollowUpAwaitingCaptureReady = false
    realtimePostDrainFinalizationDeadlineMs = 0L
    realtimeLateDetachedUserFinalPending = false
    realtimeOutputPresentationPending = false
    realtimePhysicalAudibleDrainPending = false
    realtimePostOutputTail = null
    if (!preserveWakeCaptureHold) realtimeWakeCaptureHold = null
    realtimeOutputEpoch += 1
    pendingRealtimeWakeSeed = null
    pendingRealtimeWakeStartedAtMs = null
    pendingRealtimeWakeCaptureStartGate = null
    realtimeWakeStartedAtMs = null
    clearRealtimeRelayToolsLocked()
    if (_conversationLifecycle.value.closingReason == null) {
      _conversationLifecycle.value = JarvisConversationLifecycleState(active = false)
    }
    if (!preserveVoiceChange) cancelRealtimeVoiceChange()
    val status = currentStatus
    val sessionId = realtimeSessionId
    val lease = realtimeRequestLease
    val communicationAudio = realtimeCommunicationAudio
    realtimeProviderReady?.cancel()
    realtimeProviderReady = null
    earlyRealtimeReadyAtMs.clear()
    realtimeSessionId = null
    realtimeSessionKey = null
    realtimeRequestLease = null
    realtimeCommunicationAudio = null
    audioInputGeneration.incrementAndGet()
    onAppliedAudioInputChanged(null)
    val cleared =
      realtimePlayoutSession?.let {
        it.active = false
        if (realtimePlayoutDelegate.isInitialized()) realtimePlayout.clear(it, acknowledge = false) else null
      }
    val routeRelease =
      communicationAudio?.let { owner ->
        // Gateway work is replaceable on reconnect. Audio ownership must still be released if
        // that scope is cancelled while an old relay is retiring.
        scope.async<Unit>(Dispatchers.IO) {
          try {
            cleared?.await()
          } finally {
            owner.close()
          }
        }
      }
    audioRetirement.retire(realtimeCaptureJob, realtimeAudioInput, routeRelease ?: cleared)
    realtimeAudioInput = null
    realtimePlayoutSession = null
    realtimeCaptureJob = null
    realtimeCaptureReady = null
    realtimeAppendJob?.cancel()
    realtimeAppendJob = null
    if (!preserveVoiceChange) realtimeCapturePause = null
    realtimeOutputSuppressed = false
    realtimeOutputTurn = null
    realtimeOutputOwners.clear()
    pendingRealtimeOutputClear?.cancel()
    pendingRealtimeOutputClear = null
    realtimeAgentCoordinator.endSession(sessionId)
    clearActiveTools()
    realtimeUserEntryId = null
    realtimeUserEntryAwaitingFinal = false
    realtimeUserEntryAwaitingFinalStartedAtMs = null
    realtimeAssistantEntryId = null
    setRealtimePlaying(false)
    clearRealtimeAudioLevels()
    if (preserveStatus) setStatus(status)
    _isListening.value = false
    _isCaptureAdmitted.value = false
    if (closeSession && !sessionId.isNullOrBlank() && lease != null) {
      gatewayWorkScope.launch { closeRealtimeSession(sessionId, lease) }
    }
  }

  internal fun prepareRealtimeCapturePause(
    captureId: String,
    lease: GatewaySession.RequestLease?,
  ): suspend () -> Unit {
    val pause: RealtimeCapturePause
    val cancellationTurnId: String?
    val retirement: Deferred<Unit>
    val appendJob =
      synchronized(realtimeCapturePauseLock) {
        pause = RealtimeCapturePause(sessionId = realtimeSessionId, pttCaptureId = captureId)
        cancellationTurnId = realtimeOutputTurn?.id
        realtimeCapturePause = pause
        _isCaptureAdmitted.value = false
        realtimeOutputPresentationPending = false
        realtimePhysicalAudibleDrainPending = false
        realtimePostOutputTail = null
        realtimeOutputEpoch += 1
        audioInputGeneration.incrementAndGet()
        onAppliedAudioInputChanged(null)
        realtimeOutputSuppressed = true
        stopRealtimePlayback()
        retirement = audioRetirement.retire(realtimeCaptureJob, realtimeAudioInput)
        realtimeAudioInput = null
        realtimeCaptureJob = null
        realtimeCaptureReady = null
        realtimeAppendJob.also { realtimeAppendJob = null }
      }
    return {
      audioRetirement.await(retirement)
      appendJob?.cancelAndJoin()
      // Stop input first so no frame can create new provider output while the
      // cancellation boundary is being established.
      if (
        !cancelRealtimeOutput(
          reason = "android-push-to-talk",
          sessionId = pause.sessionId,
          turnId = cancellationTurnId,
          lease = lease,
          isCurrent = { realtimeCapturePause === pause },
        )
      ) {
        synchronized(realtimeCapturePauseLock) {
          // Cancellation can finish after Off/On has admitted a replacement relay.
          if (realtimeCapturePause !== pause) return@synchronized
          Log.w(tag, "realtime output cancellation was not confirmed; closing relay")
          stopRealtimeRelay(preserveStatus = true)
          realtimeCapturePause = pause.copy(sessionId = null, restartRelay = true)
          realtimeOutputSuppressed = true
        }
      }
    }
  }

  private fun isRealtimeCapturePaused(): Boolean = synchronized(realtimeCapturePauseLock) { realtimeCapturePause != null }

  internal fun resumeRealtimeCaptureAfterPushToTalk(captureId: String) {
    val generation = startGeneration.get()
    val outcome =
      synchronized(realtimeCapturePauseLock) {
        val current = realtimeCapturePause ?: return@synchronized RealtimeCaptureResume.Skipped
        if (current.pttCaptureId != captureId || activePttCaptureId != null) {
          return@synchronized RealtimeCaptureResume.Skipped
        }
        if (!_isEnabled.value || stopRequested) {
          realtimeCapturePause = null
          return@synchronized RealtimeCaptureResume.Skipped
        }
        // Native Talk has no relay ID. A completed PTT turn may have already
        // restarted it; cancellation and empty turns still need that restart.
        if (!realtimeRelayModelSupported && current.sessionId == null && !listeningMode) {
          realtimeCapturePause = null
          return@synchronized RealtimeCaptureResume.Restart
        }
        if (current.restartRelay && current.sessionId == null) {
          realtimeCapturePause = null
          return@synchronized RealtimeCaptureResume.Restart
        }
        val sessionId = current.sessionId
        if (sessionId == null || realtimeSessionId != sessionId) {
          realtimeCapturePause = null
          return@synchronized RealtimeCaptureResume.Skipped
        }
        if (!isConnected()) return@synchronized RealtimeCaptureResume.Disconnected
        if (realtimeCaptureJob?.isActive == true || realtimeAppendJob?.isActive == true) {
          realtimeCapturePause = null
          return@synchronized RealtimeCaptureResume.Skipped
        }
        realtimeCapturePause = null
        listeningMode = true
        _isListening.value = true
        setStatus(nativeText("Listening"))
        startRealtimeCaptureLocked(sessionId)
        RealtimeCaptureResume.Resumed
      }
    when (outcome) {
      RealtimeCaptureResume.Skipped, RealtimeCaptureResume.Resumed -> {
        return
      }

      RealtimeCaptureResume.Restart -> {
        start()
      }

      RealtimeCaptureResume.Disconnected -> {
        disableRealtimeModeAndNotifyOwner(generation, nativeText("Gateway not connected"))
      }
    }
  }

  private suspend fun closeRealtimeSession(
    sessionId: String,
    lease: GatewaySession.RequestLease,
  ) {
    try {
      val params = buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) }
      lease.request("talk.session.close", params.toString(), timeoutMs = 5_000)
    } catch (err: Throwable) {
      if (err !is CancellationException) {
        Log.d(tag, "realtime close ignored: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private fun upsertRealtimeConversation(
    role: VoiceConversationRole,
    text: String,
    isFinal: Boolean,
  ) {
    var entryId =
      when (role) {
        VoiceConversationRole.User -> realtimeUserEntryId
        VoiceConversationRole.Assistant -> realtimeAssistantEntryId
      }
    if (role == VoiceConversationRole.Assistant) {
      finishRealtimeConversationEntry(VoiceConversationRole.User)
    }
    val shouldStartNewUserEntry =
      role == VoiceConversationRole.User &&
        entryId != null &&
        shouldStartNewRealtimeUserEntry(entryId, text, isFinal)
    if (
      role == VoiceConversationRole.User &&
      (entryId == null || shouldStartNewUserEntry)
    ) {
      finishRealtimeConversationEntry(VoiceConversationRole.Assistant)
    }
    if (shouldStartNewUserEntry) {
      finishRealtimeConversationEntry(VoiceConversationRole.User)
      entryId = null
      realtimeUserEntryAwaitingFinal = false
      realtimeUserEntryAwaitingFinalStartedAtMs = null
    }
    val resolvedEntryId =
      if (entryId == null) {
        appendConversation(role = role, text = text.trimStart(), isStreaming = !isFinal)
      } else {
        updateConversationEntry(id = entryId, text = text, isStreaming = !isFinal)
        entryId
      }
    when (role) {
      VoiceConversationRole.User -> {
        realtimeUserEntryId = if (isFinal) null else resolvedEntryId
        realtimeUserEntryAwaitingFinal = false
        realtimeUserEntryAwaitingFinalStartedAtMs = null
      }

      VoiceConversationRole.Assistant -> {
        realtimeAssistantEntryId = if (isFinal) null else resolvedEntryId
      }
    }
  }

  private fun finishRealtimeConversationEntry(role: VoiceConversationRole) {
    val entryId =
      when (role) {
        VoiceConversationRole.User -> realtimeUserEntryId
        VoiceConversationRole.Assistant -> realtimeAssistantEntryId
      } ?: return
    val current = _conversation.value
    val targetIndex = current.indexOfFirst { it.id == entryId }
    if (targetIndex >= 0 && current[targetIndex].isStreaming) {
      val updated = current.toMutableList()
      updated[targetIndex] = current[targetIndex].copy(isStreaming = false)
      _conversation.value = updated
      if (role == VoiceConversationRole.User) {
        realtimeUserEntryAwaitingFinal = true
        realtimeUserEntryAwaitingFinalStartedAtMs = SystemClock.elapsedRealtime()
      }
    }
    when (role) {
      VoiceConversationRole.User -> Unit
      VoiceConversationRole.Assistant -> realtimeAssistantEntryId = null
    }
  }

  private fun shouldStartNewRealtimeUserEntry(
    entryId: String,
    incoming: String,
    isFinal: Boolean,
  ): Boolean {
    val entry = _conversation.value.firstOrNull { it.id == entryId } ?: return false
    if (entry.isStreaming) return false
    val existing = entry.text
    if (existing.isBlank() || incoming.isBlank()) return false
    if (incoming.firstOrNull()?.isWhitespace() == true) return false
    if (incoming == existing || incoming.startsWith(existing) || existing.endsWith(incoming)) return false
    if (isFinal && realtimeUserEntryAwaitingFinal) {
      val elapsedMs =
        realtimeUserEntryAwaitingFinalStartedAtMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
      if (elapsedMs <= realtimeUserFinalRewriteGraceMs && looksLikeTranscriptReplacement(existing, incoming)) {
        return false
      }
    }
    return true
  }

  private fun appendConversation(
    role: VoiceConversationRole,
    text: String,
    isStreaming: Boolean,
  ): String {
    val id = UUID.randomUUID().toString()
    _conversation.value =
      (_conversation.value + VoiceConversationEntry(id = id, role = role, text = text, isStreaming = isStreaming))
        .takeLast(maxConversationEntries)
    return id
  }

  private fun updateConversationEntry(
    id: String,
    text: String,
    isStreaming: Boolean,
  ) {
    val current = _conversation.value
    val targetIndex =
      when {
        current.isEmpty() -> -1
        current[current.lastIndex].id == id -> current.lastIndex
        else -> current.indexOfFirst { it.id == id }
      }
    if (targetIndex < 0) return
    val entry = current[targetIndex]
    val updatedText = mergeRealtimeTranscriptText(entry.text, text, isFinal = !isStreaming)
    if (entry.text == updatedText && entry.isStreaming == isStreaming) return
    val updated = current.toMutableList()
    updated[targetIndex] = entry.copy(text = updatedText, isStreaming = isStreaming)
    _conversation.value = updated
  }

  private fun realtimeTranscriptText(
    rawText: String?,
    isFinal: Boolean,
  ): String? {
    val text = rawText ?: return null
    return text.takeIf { if (isFinal) it.isNotBlank() else it.isNotEmpty() }
  }

  private fun mergeRealtimeTranscriptText(
    existing: String,
    incoming: String,
    isFinal: Boolean,
  ): String {
    if (existing.isBlank()) return incoming.trimStart()
    if (incoming.isEmpty()) return existing
    // Gateway relay finals are full-turn snapshots. Non-final events remain deltas.
    if (isFinal) return incoming.trimStart()
    if (incoming == existing || existing.endsWith(incoming)) return existing
    if (incoming.startsWith(existing)) return incoming
    if (incoming.firstOrNull()?.isWhitespace() == true) return existing + incoming
    val overlap = findTranscriptTextOverlap(existing, incoming)
    val suffix = if (overlap > 0) incoming.drop(overlap) else incoming
    if (suffix.isEmpty()) return existing
    val separator =
      if (overlap > 0 || !shouldInsertTranscriptSpace(existing, suffix)) {
        ""
      } else {
        " "
      }
    return existing + separator + suffix
  }

  private fun looksLikeTranscriptReplacement(
    existing: String,
    incoming: String,
  ): Boolean {
    val existingWords = transcriptWords(existing)
    val incomingWords = transcriptWords(incoming)
    if (existingWords.isEmpty() || incomingWords.isEmpty()) return false
    if (existingWords[0] != incomingWords[0]) return false
    if (existingWords.size > 1 && incomingWords.size > 1 && existingWords[1] == incomingWords[1]) return true
    val existingText = normalizeTranscriptText(existing)
    val incomingText = normalizeTranscriptText(incoming)
    val commonPrefix = commonPrefixLength(existingText, incomingText)
    val shortest = minOf(existingText.length, incomingText.length)
    return commonPrefix >= 6 && commonPrefix.toDouble() / maxOf(1, shortest).toDouble() >= 0.45
  }

  private fun transcriptWords(value: String): List<String> =
    Regex("""[\p{L}\p{N}]+""")
      .findAll(value.lowercase(Locale.ROOT))
      .map { it.value }
      .toList()

  private fun normalizeTranscriptText(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()

  private fun commonPrefixLength(
    left: String,
    right: String,
  ): Int {
    val max = minOf(left.length, right.length)
    var index = 0
    while (index < max && left[index] == right[index]) {
      index += 1
    }
    return index
  }

  private fun findTranscriptTextOverlap(
    existing: String,
    incoming: String,
  ): Int {
    val base = existing.lowercase(Locale.ROOT)
    val next = incoming.lowercase(Locale.ROOT)
    val max = minOf(base.length, next.length)
    for (length in max downTo 3) {
      if (base.endsWith(next.take(length))) {
        return length
      }
    }
    return 0
  }

  private fun shouldInsertTranscriptSpace(
    existing: String,
    incoming: String,
  ): Boolean {
    val last = existing.lastOrNull() ?: return false
    val first = incoming.firstOrNull() ?: return false
    if (last.isWhitespace() || first.isWhitespace()) return false
    return first.isLetterOrDigit() &&
      (last.isLetterOrDigit() || transcriptSpaceAfterPunctuation.contains(last))
  }

  private val transcriptSpaceAfterPunctuation =
    setOf('.', '!', '?', ',', ':', ';', ')', ']', '}', '"', '\'', '’', '”')

  // API 33 adds segmented callbacks and caller-owned audio. Keep this ordered ladder
  // in one place: removing the restart rung makes older devices drop speech after a pause.
  private fun pushToTalkCandidates(first: PushToTalkRecognitionCandidate?): List<PushToTalkRecognitionCandidate> =
    pushToTalkRecognitionCandidates(
      supportsSegmentedRecognition = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
      first = first,
    )

  private fun startPushToTalkRecognition(
    captureId: String,
    firstCandidate: PushToTalkRecognitionCandidate? = null,
  ) = synchronized(realtimeCapturePauseLock) {
    if (activePttCaptureId != captureId || pttReleaseCompletion != null || stopRequested) return@synchronized
    val recognizerInstance = recognizer ?: error("Speech recognizer unavailable")
    var lastFailure: Throwable? = null
    for (candidate in pushToTalkCandidates(firstCandidate)) {
      try {
        val rung =
          when (candidate) {
            PushToTalkRecognitionCandidate.RawAudioSegmented -> {
              PushToTalkRecognitionRung.RawAudioSegmented(openPushToTalkAudioSource())
            }

            PushToTalkRecognitionCandidate.SilenceSegmented -> {
              PushToTalkRecognitionRung.SilenceSegmented
            }

            PushToTalkRecognitionCandidate.RestartingSingleSession -> {
              PushToTalkRecognitionRung.RestartingSingleSession
            }
          }
        pttRecognitionRung = rung
        recognizerInstance.startListening(recognizerIntent(rung))
        _isListening.value = true
        setStatus(nativeText("Listening (PTT)"))
        return@synchronized
      } catch (err: Throwable) {
        lastFailure = err
        closePushToTalkRung()
        Log.w(tag, "PTT recognizer rung failed captureId=$captureId rung=$candidate: ${err.message}")
      }
    }
    throw lastFailure ?: IllegalStateException("Speech recognizer unavailable")
  }

  private fun recognizerIntent(rung: PushToTalkRecognitionRung = PushToTalkRecognitionRung.RestartingSingleSession): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, resolvedSpeechLocaleTag())
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
      when (rung) {
        is PushToTalkRecognitionRung.RawAudioSegmented -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyRawAudioSegmentedExtras(this, rung.source)
          }
        }

        PushToTalkRecognitionRung.SilenceSegmented,
        PushToTalkRecognitionRung.RestartingSingleSession,
        -> {
          // Cloud recognition tolerates natural speech and pauses better than on-device recognition.
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
          putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800)
          if (rung == PushToTalkRecognitionRung.SilenceSegmented && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applySilenceSegmentedExtras(this)
          }
        }
      }
    }

  // API 33 RecognizerIntent extras live behind @RequiresApi so min-SDK lint stays meaningful;
  // segmented rungs are only ever constructed on TIRAMISU+ (see pushToTalkRecognitionCandidates).
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun applyRawAudioSegmentedExtras(
    intent: Intent,
    source: PushToTalkAudioSource,
  ) {
    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source.readDescriptor)
    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pushToTalkSampleRateHz)
    intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun applySilenceSegmentedExtras(intent: Intent) {
    intent.putExtra(
      RecognizerIntent.EXTRA_SEGMENTED_SESSION,
      RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
    )
  }

  @SuppressLint("MissingPermission")
  private fun openPushToTalkAudioSource(): PushToTalkAudioSource {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    val minBufferSize =
      AudioRecord.getMinBufferSize(
        pushToTalkSampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    check(minBufferSize > 0) { "AudioRecord buffer unavailable" }

    val pipe = ParcelFileDescriptor.createPipe()
    var recorder: AudioRecord? = null
    var writeStream: ParcelFileDescriptor.AutoCloseOutputStream? = null
    try {
      recorder =
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          pushToTalkSampleRateHz,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minBufferSize * 2,
        )
      check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
      recorder.startRecording()
      check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
      val activeRecorder = checkNotNull(recorder)
      val activeWriteStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
      writeStream = activeWriteStream
      val source = PushToTalkAudioSource(pipe[0], activeWriteStream, activeRecorder)
      source.pumpJob =
        gatewayWorkScope.launch(Dispatchers.IO) {
          val buffer = ByteArray(minBufferSize.coerceAtLeast(4_096))
          try {
            while (currentCoroutineContext().isActive) {
              val bytesRead = activeRecorder.read(buffer, 0, buffer.size)
              if (bytesRead <= 0) break
              activeWriteStream.write(buffer, 0, bytesRead)
            }
          } catch (err: IOException) {
            Log.d(tag, "PTT audio pipe closed: ${err.message}")
          } finally {
            source.finishFromPump()
          }
        }
      return source
    } catch (err: Throwable) {
      runCatching { recorder?.stop() }
      runCatching { recorder?.release() }
      runCatching { writeStream?.close() }
      if (writeStream == null) runCatching { pipe[1].close() }
      runCatching { pipe[0].close() }
      throw err
    }
  }

  private fun schedulePushToTalkRestart(
    delayMs: Long,
    advanceRung: Boolean,
  ) {
    val captureId = activePttCaptureId ?: return
    if (pttReleaseCompletion != null) return
    val rung = pttRecognitionRung ?: return
    val firstCandidate =
      when (rung) {
        is PushToTalkRecognitionRung.RawAudioSegmented -> {
          if (advanceRung) {
            PushToTalkRecognitionCandidate.SilenceSegmented
          } else {
            PushToTalkRecognitionCandidate.RawAudioSegmented
          }
        }

        PushToTalkRecognitionRung.SilenceSegmented -> {
          if (advanceRung) {
            PushToTalkRecognitionCandidate.RestartingSingleSession
          } else {
            PushToTalkRecognitionCandidate.SilenceSegmented
          }
        }

        PushToTalkRecognitionRung.RestartingSingleSession -> {
          PushToTalkRecognitionCandidate.RestartingSingleSession
        }
      }
    commitPushToTalkLivePartial()
    closePushToTalkRung()
    restartJob?.cancel()
    restartJob =
      gatewayWorkScope.launch {
        delay(delayMs)
        mainHandler.post {
          synchronized(realtimeCapturePauseLock) {
            if (activePttCaptureId != captureId || pttReleaseCompletion != null || stopRequested) return@post
            try {
              startPushToTalkRecognition(captureId, firstCandidate)
            } catch (err: Throwable) {
              _isListening.value = false
              setTalkFailure(nativeText("Talk failed: \$message", err.message ?: err::class.simpleName.orEmpty()))
            }
          }
        }
      }
  }

  private fun closePushToTalkRung() =
    synchronized(realtimeCapturePauseLock) {
      (pttRecognitionRung as? PushToTalkRecognitionRung.RawAudioSegmented)?.source?.close()
      pttRecognitionRung = null
    }

  private fun commitPushToTalkLivePartial() {
    val partial = pttLivePartial.trim()
    if (partial.isNotEmpty()) {
      pttFinalSegments += partial
    }
    pttLivePartial = ""
  }

  private fun startListeningInternal(markListening: Boolean) {
    val r = recognizer ?: return
    val intent = recognizerIntent()
    if (markListening) {
      setStatus(nativeText("Listening"))
      _isListening.value = true
    }
    r.startListening(intent)
  }

  private fun scheduleRestart(delayMs: Long = 350) {
    if (stopRequested) return
    restartJob?.cancel()
    restartJob =
      gatewayWorkScope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested) return@post
          try {
            recognizer?.cancel()
            val shouldListen = listeningMode
            val shouldInterrupt = _isSpeaking.value && interruptOnSpeech && shouldAllowSpeechInterrupt()
            if (!shouldListen && !shouldInterrupt) return@post
            startListeningInternal(markListening = shouldListen)
          } catch (_: Throwable) {
            // handled by onError
          }
        }
      }
  }

  private fun handleTranscript(
    text: String,
    isFinal: Boolean,
  ) {
    val trimmed = text.trim()
    if (activePttCaptureId != null) {
      if (trimmed.isNotEmpty()) {
        if (isFinal) {
          pttFinalSegments += trimmed
          pttLivePartial = ""
        } else {
          pttLivePartial = trimmed
        }
        lastHeardAtMs = SystemClock.elapsedRealtime()
      }
      return
    }
    if (_isSpeaking.value && interruptOnSpeech) {
      if (shouldInterrupt(trimmed)) {
        cancelActivePlayback()
      }
      return
    }

    if (!_isListening.value) return

    if (trimmed.isNotEmpty()) {
      lastTranscript = trimmed
      lastHeardAtMs = SystemClock.elapsedRealtime()
    }

    if (isFinal) {
      lastTranscript = trimmed
      // Don't finalize immediately — let the silence monitor trigger after
      // silenceWindowMs. This allows the recognizer to fire onResults and
      // still give the user a natural pause before we send.
    }
  }

  private fun startSilenceMonitor(captureId: String? = null) {
    silenceJob?.cancel()
    silenceJob =
      gatewayWorkScope.launch {
        while (activePttCaptureId == captureId && (_isEnabled.value || pttAutoStopEnabled)) {
          delay(200)
          checkSilence(captureId)
        }
      }
  }

  private suspend fun checkSilence(captureId: String?) {
    if (activePttCaptureId != captureId) return
    if (!_isListening.value) return
    val transcript =
      if (activePttCaptureId != null) {
        PushToTalkTranscriptMerger.merge(pttFinalSegments, pttLivePartial)
      } else {
        lastTranscript.trim()
      }
    if (transcript.isEmpty()) return
    val lastHeard = lastHeardAtMs ?: return
    val elapsed = SystemClock.elapsedRealtime() - lastHeard
    if (elapsed < silenceWindowMs) return
    if (captureId != null) {
      if (pttAutoStopEnabled) {
        if (pttReleaseCompletion != null) return
        endPushToTalk(captureId)
      }
      return
    }
    // The listening job owns the turn, so stop or PTT takeover also cancels pending finalization.
    finalizeTranscript(transcript)
  }

  private suspend fun finalizeTranscript(transcript: String) {
    listeningMode = false
    _isListening.value = false
    setStatus(nativeText("Thinking…"), awaitingAgent = true)
    lastTranscript = ""
    lastHeardAtMs = null
    // Do not start synthesis while native recognition is still queued for teardown on Main.
    retireRecognizer()
    audioRetirement.await()

    try {
      ensureConfigLoaded()
      currentCoroutineContext().ensureActive()
      if (!isConnected()) {
        setStatus(nativeText("Gateway not connected"))
        Log.w(tag, "finalize: gateway not connected")
        return
      }
      val startedAt = System.currentTimeMillis().toDouble() / 1000.0
      Log.d(tag, "chat.send start sessionKey=${activeTalkSessionKey()} chars=${transcript.length}")
      val ack = sendChat(transcript)
      val runId = ack.runId ?: throw IllegalStateException("chat.send returned no run id")
      Log.d(tag, "chat.send ok runId=$runId status=${ack.status}")
      if (ack.isTerminalFailure) {
        setStatus(if (ack.normalizedStatus == "error") nativeText("Chat error") else nativeText("Aborted"))
        return
      }
      val ok = if (ack.isTerminalSuccess) true else waitForChatFinal(runId)
      if (!ok) {
        Log.w(tag, "chat final timeout runId=$runId; attempting history fallback")
      }
      // Use text cached from the final event first — avoids chat.history polling
      val assistant =
        consumeRunText(runId)
          ?: waitForAssistantText(
            chatSendAckHistorySinceSeconds(ack, startedAt),
            if (ok) 12_000 else 25_000,
          )
      if (assistant.isNullOrBlank()) {
        setStatus(nativeText("No reply"))
        Log.w(tag, "assistant text timeout runId=$runId")
        return
      }
      Log.d(tag, "assistant text ok chars=${assistant.length}")
      val playbackToken = cancelActivePlayback()
      // Muting owns only this reply's audio. Stopping capture still cancels the
      // parent turn and must never restart listening over a replacement PTT hold.
      supervisorScope {
        val playback = async { playAssistant(assistant, playbackToken) }
        try {
          playback.await()
        } catch (err: CancellationException) {
          currentCoroutineContext().ensureActive()
        }
      }
    } catch (err: Throwable) {
      if (err is CancellationException) {
        Log.d(tag, "finalize speech cancelled")
        return
      }
      setTalkFailure(nativeText("Talk failed: \$message", err.message ?: err::class.simpleName.orEmpty()))
      Log.w(tag, "finalize failed: ${err.message ?: err::class.simpleName}")
    } finally {
      if (currentCoroutineContext().isActive && _isEnabled.value) start()
    }
  }

  private suspend fun awaitPushToTalkRelease(captureId: String) {
    if (activePttCaptureId != captureId) return
    restartJob?.cancel()
    restartJob = null
    val rung = pttRecognitionRung ?: return
    // onResults, onError, and onEndOfSegmentedSession normally arrive well under a second,
    // so typical release latency is unchanged. The five-second bound only caps pathological recognizers;
    // leaving early truncates final words, which is worse than waiting.
    pttReleaseCompletion?.let { existing ->
      awaitPushToTalkReleaseCompletion(existing, pushToTalkReleaseGraceMs)
      return
    }
    if (!_isListening.value || recognizer == null) return

    val completion = CompletableDeferred<Unit>()
    pttReleaseCompletion = completion
    _isListening.value = false
    (rung as? PushToTalkRecognitionRung.RawAudioSegmented)?.source?.requestFinish()
    // EXTRA_AUDIO_SOURCE is optional: a service may ignore the pipe and run its own mic,
    // so closing our AudioRecord alone would leave it listening past release. stopListening
    // forces its endpointer; for pipe-consuming services it is redundant after EOF.
    runCatching { recognizer?.stopListening() }.onFailure { completion.complete(Unit) }
    awaitPushToTalkReleaseCompletion(completion, pushToTalkReleaseGraceMs)
    if (pttReleaseCompletion === completion) {
      pttReleaseCompletion = null
    }
  }

  private suspend fun drainPushToTalkReleaseBeforeBegin() {
    val deadline = SystemClock.elapsedRealtime() + pushToTalkReleaseDrainTimeoutMs
    while (true) {
      val remainingMs = deadline - SystemClock.elapsedRealtime()
      val release = pttReleaseCompletion
      if (release != null && remainingMs > 0) {
        awaitPushToTalkReleaseCompletion(release, remainingMs)
      }
      if (activePttCaptureId == null && pttReleaseCompletion == null) return
      if (SystemClock.elapsedRealtime() >= deadline) return
      yield()
    }
  }

  private suspend fun awaitPushToTalkReleaseCompletion(
    completion: CompletableDeferred<Unit>,
    timeoutMs: Long,
  ) {
    try {
      withTimeoutOrNull(timeoutMs) { completion.await() }
    } catch (err: CancellationException) {
      if (completion.isCancelled && currentCoroutineContext().isActive) return
      throw err
    }
  }

  private fun clearPushToTalkRecognition(captureId: String): ClearedPushToTalkCapture? {
    val (cleared, release) =
      synchronized(realtimeCapturePauseLock) {
        if (activePttCaptureId != captureId) return null
        val transcript = PushToTalkTranscriptMerger.merge(pttFinalSegments, pttLivePartial)
        val completion = pttCompletion
        pttTimeoutJob?.cancel()
        pttTimeoutJob = null
        pttAutoStopEnabled = false
        pttCompletion = null
        val release = pttReleaseCompletion
        pttReleaseCompletion = null
        activePttCaptureId = null
        _isListening.value = false
        listeningMode = false
        retireRecognizer()
        closePushToTalkRung()
        pttFinalSegments.clear()
        pttLivePartial = ""
        lastTranscript = ""
        lastHeardAtMs = null
        ClearedPushToTalkCapture(transcript = transcript, completion = completion) to release
      }
    release?.cancel()
    return cleared
  }

  private fun finishPushToTalk(
    payload: TalkPttStopPayload,
    completion: CompletableDeferred<TalkPttStopPayload>?,
  ): TalkPttStopPayload {
    completion?.complete(payload)
    return payload
  }

  private fun finishClearedPushToTalk(
    captureId: String,
    cleared: ClearedPushToTalkCapture,
    status: String,
    transcript: String? = null,
    statusText: NativeText = if (_isEnabled.value) nativeText("Listening") else nativeText("Ready"),
  ): TalkPttStopPayload {
    setStatus(statusText)
    resumeRealtimeCaptureAfterPushToTalk(captureId)
    return finishPushToTalk(
      TalkPttStopPayload(captureId = captureId, transcript = transcript, status = status),
      cleared.completion,
    )
  }

  private fun clearFinishingPushToTalk(
    captureId: String,
    job: Job,
  ): Boolean =
    synchronized(finishingPttLock) {
      if (finishingPttCaptureId != captureId || finishingPttJob !== job) {
        return@synchronized false
      }
      finishingPttCaptureId = null
      finishingPttJob = null
      true
    }

  private suspend fun sendChat(message: String): ChatSendAck {
    val runId = UUID.randomUUID().toString()
    armPendingRun(runId)
    val params =
      buildJsonObject {
        put("sessionKey", JsonPrimitive(activeTalkSessionKey()))
        put("message", JsonPrimitive(message))
        put("timeoutMs", JsonPrimitive(30_000))
        put("idempotencyKey", JsonPrimitive(runId))
      }
    try {
      val res = requestGateway("chat.send", params.toString())
      val parsed = parseChatSendAck(json, res)
      val actualRunId = parsed.runId ?: runId
      if (actualRunId != runId) {
        pendingRunId = actualRunId
      }
      if (parsed.isTerminal) {
        clearPendingRun(actualRunId)
      }
      return parsed.copy(runId = actualRunId)
    } catch (err: Throwable) {
      clearPendingRun(runId)
      throw err
    }
  }

  internal suspend fun waitForChatFinal(runId: String): Boolean {
    consumeRunCompletion(runId)?.let { return it }
    val deferred =
      if (pendingRunId == runId) {
        pendingFinal ?: armPendingRun(runId)
      } else {
        armPendingRun(runId)
      }

    consumeRunCompletion(runId)?.let { return it }

    val result =
      try {
        withTimeout(chatFinalWaitMs) { deferred.await() }
      } catch (_: TimeoutCancellationException) {
        false
      }

    if (!result && pendingRunId == runId) {
      clearPendingRun(runId)
    }
    return result
  }

  private fun armPendingRun(runId: String): CompletableDeferred<Boolean> {
    pendingFinal?.cancel()
    val deferred = CompletableDeferred<Boolean>()
    pendingRunId = runId
    pendingFinal = deferred
    return deferred
  }

  private fun clearPendingRun(runId: String) {
    if (pendingRunId == runId) {
      pendingFinal = null
      pendingRunId = null
    }
  }

  private fun cacheRunCompletion(
    runId: String,
    isFinal: Boolean,
  ) {
    synchronized(completedRunsLock) {
      completedRunStates[runId] = isFinal
      while (completedRunStates.size > maxCachedRunCompletions) {
        val first = completedRunStates.entries.firstOrNull() ?: break
        completedRunStates.remove(first.key)
      }
    }
  }

  private fun consumeRunCompletion(runId: String): Boolean? {
    synchronized(completedRunsLock) {
      return completedRunStates.remove(runId)
    }
  }

  private fun hasRunCompletion(runId: String): Boolean {
    synchronized(completedRunsLock) {
      return completedRunStates.containsKey(runId)
    }
  }

  private fun consumeRunText(runId: String): String? {
    synchronized(completedRunsLock) {
      return completedRunTexts.remove(runId)
    }
  }

  private suspend fun waitForAssistantText(
    sinceSeconds: Double?,
    timeoutMs: Long,
  ): String? {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      val text = fetchLatestAssistantText(sinceSeconds)
      if (!text.isNullOrBlank()) return text
      delay(300)
    }
    return null
  }

  private suspend fun fetchLatestAssistantText(
    sinceSeconds: Double? = null,
  ): String? {
    val key = activeTalkSessionKey()
    val params = buildJsonObject { put("sessionKey", JsonPrimitive(key)) }
    val res = requestGateway("chat.history", params.toString())
    val root = json.parseToJsonElement(res).asObjectOrNull() ?: return null
    val messages = root["messages"] as? JsonArray ?: return null
    for (item in messages.reversed()) {
      val obj = item.asObjectOrNull() ?: continue
      if (obj["role"].asJsonStringOrNull() != "assistant") continue
      if (sinceSeconds != null) {
        val timestamp = obj["timestamp"].asDoubleOrNull()
        if (timestamp != null && !TalkModeRuntime.isMessageTimestampAfter(timestamp, sinceSeconds)) continue
      }
      val content = obj["content"] as? JsonArray ?: continue
      val text =
        content
          .mapNotNull { entry ->
            entry
              .asObjectOrNull()
              ?.get("text")
              ?.asJsonStringOrNull()
              ?.trim()
          }.filter { it.isNotEmpty() }
      if (text.isNotEmpty()) return text.joinToString("\n")
    }
    return null
  }

  private suspend fun playAssistant(
    text: String,
    playbackToken: Long,
  ) {
    val lease = PlaybackLease(playbackToken, checkNotNull(coroutineContext[Job]))
    var shouldResumeAfterSpeak = false
    var failure: NativeText? = null
    try {
      synchronized(playbackLock) {
        ensurePlaybackActive(playbackToken)
        if (!lease.job.isActive) throw CancellationException("assistant speech cancelled")
        localPlayback = lease
      }
      shouldResumeAfterSpeak = true
      onBeforeSpeak()
      ensurePlaybackActive(playbackToken)
      val parsed = TalkDirectiveParser.parse(text)
      if (parsed.unknownKeys.isNotEmpty()) Log.w(tag, "Unknown talk directive keys: ${parsed.unknownKeys}")
      val directive = parsed.directive
      val cleaned = parsed.stripped.trim()
      if (cleaned.isEmpty()) return
      synchronized(playbackLock) {
        ensurePlaybackActive(playbackToken)
        lastSpokenText = cleaned
        setStatus(nativeText("Generating voice…"), awaitingAgent = true)
      }
      try {
        val started = SystemClock.elapsedRealtime()
        when (val result = talkSpeakClient.synthesize(text = cleaned, directive = directive)) {
          is TalkSpeakResult.Success -> {
            markAudioPlaybackStarting(playbackToken)
            talkAudioPlayer.play(result.audio)
            ensurePlaybackActive(playbackToken)
            Log.d(tag, "talk.speak ok durMs=${SystemClock.elapsedRealtime() - started}")
          }

          is TalkSpeakResult.FallbackToLocal -> {
            Log.d(tag, "talk.speak unavailable; using local TTS: ${result.message}")
            ensurePlaybackActive(playbackToken)
            systemSpeech.speak(
              text = cleaned,
              locale = TalkModeRuntime.validatedLanguage(directive?.language)?.let(Locale::forLanguageTag) ?: Locale.getDefault(),
              speechRate = (TalkModeRuntime.resolveSpeed(directive?.speed, directive?.rateWpm) ?: 1.0).toFloat(),
              beforeSpeak = { markAudioPlaybackStarting(playbackToken) },
            )
            ensurePlaybackActive(playbackToken)
            Log.d(tag, "system tts ok durMs=${SystemClock.elapsedRealtime() - started}")
          }

          is TalkSpeakResult.Failure -> {
            throw IllegalStateException(result.message)
          }
        }
      } catch (err: Throwable) {
        if (isPlaybackCancelled(err, playbackToken)) {
          Log.d(tag, "assistant speech cancelled")
          return
        }
        failure = nativeText("Speak failed: \$message", err.message ?: err::class.simpleName.orEmpty())
        Log.w(tag, "talk playback failed: ${err.message ?: err::class.simpleName}")
      }
    } finally {
      synchronized(playbackLock) {
        // Cancellation does not join: an old caller can finish after its replacement.
        if (localPlayback === lease) {
          localPlayback = null
          publishSpeakingState()
          failure?.let { setStatus(it) }
        }
      }
      try {
        lease.releaseAudioFocus?.invoke()
      } finally {
        if (shouldResumeAfterSpeak) {
          withContext(NonCancellable) {
            onAfterSpeak()
          }
        }
      }
    }
  }

  private fun cancelActivePlayback(): Long {
    val (token, activeJob) =
      synchronized(playbackLock) {
        val token = playbackGeneration.incrementAndGet()
        val job = localPlayback?.job
        localPlayback = null
        publishSpeakingState()
        token to job
      }
    // SystemSpeech's beforeSpeak callback takes playbackLock; never reverse that edge.
    activeJob?.cancel()
    talkAudioPlayer.stop()
    systemSpeech.stop()
    return token
  }

  /**
   * Keeps each relay PCM callback from allocating an identical [NativeText.Resource] and
   * publishing an equivalent [TalkStatus]. It is safe from both playout and expiry paths.
   */
  private fun updateRealtimeOutputStatus(
    audible: Boolean,
    statusOwner: Any?,
  ) {
    val source = if (audible) "Speaking…" else "Listening"
    status.update { current ->
      if (!_isEnabled.value || current.owner !== statusOwner) {
        current
      } else if (
        current.state == TalkStatusState.Active &&
        !current.awaitingAgent &&
        (current.text as? NativeText.Resource)?.let { it.source == source && it.formatArgs.isEmpty() } == true
      ) {
        current
      } else {
        current.copy(text = nativeText(source), state = TalkStatusState.Active, awaitingAgent = false)
      }
    }
  }

  private fun setRealtimePlaying(
    playing: Boolean,
    level: Float? = null,
    statusOwner: Any? = null,
    freshPcm: Boolean = false,
  ): Boolean =
    synchronized(playbackLock) {
      realtimePlaying = playing
      if (!playing) {
        realtimeAudible = false
        realtimeAudibleUntilMs = 0L
        realtimeAudibleStatusOwner = null
        realtimeAudibleExpiryJob?.cancel()
        realtimeAudibleExpiryJob = null
      } else if (freshPcm && level != null && level > audiblePcmLevel) {
        realtimeAudible = true
        realtimeAudibleUntilMs = SystemClock.elapsedRealtime() + audiblePcmHangoverMs
        realtimeAudibleStatusOwner = statusOwner
        // One deadline worker observes every fresh PCM deadline. Restarting a coroutine for
        // every 20 ms relay packet needlessly schedules and cancels work on the audio path.
        if (realtimeAudibleExpiryJob?.isActive != true) {
          realtimeAudibleExpiryJob =
            scope.launch {
              while (true) {
                val waitMs =
                  synchronized(playbackLock) {
                    if (!realtimePlaying || !realtimeAudible) return@launch
                    (realtimeAudibleUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                  }
                if (waitMs > 0L) delay(waitMs)

                var expired = false
                synchronized(playbackLock) {
                  if (!realtimePlaying || !realtimeAudible) return@launch
                  if (SystemClock.elapsedRealtime() >= realtimeAudibleUntilMs) {
                    // A fresh packet may arrive before this coroutine returns below.
                    // Relinquish worker ownership while still holding playbackLock.
                    realtimeAudibleExpiryJob = null
                    realtimeAudible = false
                    realtimeAudibleUntilMs = 0L
                    val expiredStatusOwner = realtimeAudibleStatusOwner
                    realtimeAudibleStatusOwner = null
                    publishSpeakingState()
                    _outputAudioLevel.value = 0f
                    updateRealtimeOutputStatus(audible = false, statusOwner = expiredStatusOwner)
                    expired = true
                  }
                }
                // Release playbackLock before taking the capture owner lock: playout callbacks
                // take these locks in the opposite order. A silent provider stream must still age out.
                if (expired) {
                  synchronized(realtimeCapturePauseLock) { scheduleConversationIdleCheckLocked() }
                  return@launch
                }
              }
            }
        }
      }
      publishSpeakingState()
      realtimeAudible
    }

  private fun clearRealtimeAudioLevels() {
    _inputAudioLevel.value = 0f
    _outputAudioLevel.value = 0f
  }

  // Called under playbackLock; the capture gate reads the same local playback fact.
  // Either source can keep speaking after the other source completes or is cancelled.
  private fun publishSpeakingState() {
    localMediaPlaybackActive = localPlayback?.phase == PlaybackPhase.Playing
    if (localMediaPlaybackActive) _isCaptureAdmitted.value = false
    _isSpeaking.value = realtimeAudible || localMediaPlaybackActive
  }

  private fun markAudioPlaybackStarting(playbackToken: Long) {
    synchronized(playbackLock) {
      ensurePlaybackActive(playbackToken)
      val lease = localPlayback
      if (lease?.token != playbackToken || !lease.job.isActive) throw CancellationException("assistant speech cancelled")
      lease.phase = PlaybackPhase.Playing
      publishSpeakingState()
      setStatus(nativeText("Speaking…"))
      requestAudioFocusForTts(lease)
    }
    ensureInterruptListener()
  }

  fun stopTts() {
    val lease = session.captureRequestLease(gatewayStableId())
    synchronized(realtimeCapturePauseLock) {
      val sessionId = realtimeSessionId
      val output = realtimeOutputTurn
      realtimeOutputSuppressed = true
      realtimeOutputPresentationPending = false
      realtimePhysicalAudibleDrainPending = false
      realtimePostOutputTail = null
      realtimeOutputEpoch += 1
      stopRealtimePlayback()
      setStatus(currentStatus.copy(text = nativeText("Listening"), state = TalkStatusState.Active, awaitingAgent = false))
      if (sessionId != null && output?.id != null) {
        scope.launch {
          cancelRealtimeOutput(
            reason = "android-stop-tts",
            sessionId = sessionId,
            turnId = output.id,
            lease = lease,
            isCurrent = { realtimeSessionId == sessionId && realtimeOutputTurn === output },
          )
        }
      }
    }
    cancelActivePlayback()
  }

  private suspend fun cancelRealtimeOutput(
    reason: String,
    sessionId: String?,
    turnId: String?,
    lease: GatewaySession.RequestLease?,
    isCurrent: () -> Boolean,
  ): Boolean =
    realtimeOutputCancellationMutex.withLock {
      sessionId ?: return@withLock true
      turnId ?: return@withLock false
      lease ?: return@withLock false
      val clear = CompletableDeferred<String?>()
      synchronized(realtimeCapturePauseLock) {
        if (!isCurrent() || !lease.isCurrent()) return@withLock false
        pendingRealtimeOutputClear = clear
        _isCaptureAdmitted.value = false
      }
      try {
        val params =
          buildJsonObject {
            put("sessionId", JsonPrimitive(sessionId))
            put("reason", JsonPrimitive(reason))
            put("turnId", JsonPrimitive(turnId))
          }
        val response =
          lease.request("talk.session.cancelOutput", params.toString(), timeoutMs = 5_000) { enqueue ->
            synchronized(realtimeCapturePauseLock) {
              if (!isCurrent()) throw CancellationException("realtime output owner replaced")
              enqueue()
            }
          }
        val result = requireAcceptedRealtimeOutputCancellation(response, turnId)
        if (result.status == "stale" || result.status == "idle") return@withLock true
        // The response confirms provider cancellation; clear confirms that the
        // old playback boundary reached Android before capture can resume.
        val clearedTurnId = withTimeout(2_000) { clear.await() }
        check(clearedTurnId == turnId) {
          "talk.session.cancelOutput clear turnId did not match"
        }
        true
      } catch (err: TimeoutCancellationException) {
        Log.d(tag, "realtime cancelOutput unconfirmed: ${err.message ?: "timeout"}")
        false
      } catch (err: CancellationException) {
        if (!currentCoroutineContext().isActive) throw err
        Log.d(tag, "realtime cancelOutput interrupted by relay shutdown")
        false
      } catch (err: Throwable) {
        Log.d(tag, "realtime cancelOutput failed: ${err.message ?: err::class.simpleName}")
        false
      } finally {
        synchronized(realtimeCapturePauseLock) {
          if (pendingRealtimeOutputClear === clear) pendingRealtimeOutputClear = null
        }
      }
    }

  internal fun shouldAllowSpeechInterrupt(): Boolean = listeningMode && !isRealtimeCapturePaused()

  private fun requestAudioFocusForTts(lease: PlaybackLease) {
    if (realtimeAudioInput != null) return
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val req =
      AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechPlaybackAttributes())
        .setOnAudioFocusChangeListener { change ->
          if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            lease.job.cancel(CancellationException("audio focus lost"))
          }
        }.build()
    // Android keys focus by listener identity. Old cleanup and callbacks must
    // retain this playback's request rather than touching its replacement.
    lease.releaseAudioFocus = { am.abandonAudioFocusRequest(req) }
    val result = am.requestAudioFocus(req)
    Log.d(tag, "audio focus request result=$result")
  }

  private fun shouldInterrupt(transcript: String): Boolean {
    val trimmed = transcript.trim()
    if (trimmed.length < 3) return false
    val spoken = lastSpokenText?.lowercase()
    if (spoken != null && spoken.contains(trimmed.lowercase())) return false
    return true
  }

  private fun ensurePlaybackActive(playbackToken: Long) {
    if (!playbackEnabled || playbackToken != playbackGeneration.get()) {
      throw CancellationException("assistant speech cancelled")
    }
  }

  private fun isPlaybackCancelled(
    err: Throwable?,
    playbackToken: Long,
  ): Boolean {
    if (err is CancellationException) return true
    return !playbackEnabled || playbackToken != playbackGeneration.get()
  }

  private fun invalidateConfig() {
    // Keep active capture settings, but invalidate before waiting for the loader
    // so neither its stale response nor its waiting consumer can use the old revision.
    configCache.updateAndGet { it.copy(loaded = false) }
  }

  private suspend fun ensureConfigLoaded() =
    configReloadMutex.withLock {
      while (true) {
        currentCoroutineContext().ensureActive()
        val owner = configCache.get()
        if (owner.loaded) return@withLock
        val loaded =
          try {
            val res = requestGateway("talk.config", "{}")
            val root = json.parseToJsonElement(res).asObjectOrNull()
            TalkConfigCache(TalkModeGatewayConfigParser.parse(root?.get("config").asObjectOrNull()), loaded = true)
          } catch (err: Throwable) {
            if (err is CancellationException) throw err
            Log.w(tag, "talk config unavailable errorType=${err.javaClass.simpleName}")
            TalkConfigCache()
          }
        // Only invalidation requires another read; a current failure returns once.
        if (configCache.compareAndSet(owner, loaded)) return@withLock
      }
    }

  private fun resolvedSpeechLocaleTag(): String = speechLocale ?: Locale.getDefault().toLanguageTag()

  private object TalkModeRuntime {
    fun resolveSpeed(
      speed: Double?,
      rateWpm: Int?,
    ): Double? {
      if (rateWpm != null && rateWpm > 0) {
        val resolved = rateWpm.toDouble() / 175.0
        if (resolved <= 0.5 || resolved >= 2.0) return null
        return resolved
      }
      if (speed != null) {
        if (speed <= 0.5 || speed >= 2.0) return null
        return speed
      }
      return null
    }

    fun validatedLanguage(value: String?): String? {
      val normalized = value?.trim()?.lowercase() ?: return null
      if (normalized.length != 2) return null
      if (!normalized.all { it in 'a'..'z' }) return null
      return normalized
    }

    fun isMessageTimestampAfter(
      timestamp: Double,
      sinceSeconds: Double,
    ): Boolean {
      val sinceMs = sinceSeconds * 1000
      return if (timestamp > 10_000_000_000) {
        timestamp >= sinceMs - 500
      } else {
        timestamp >= sinceSeconds - 0.5
      }
    }
  }

  private fun ensureInterruptListener() {
    if (!interruptOnSpeech || !_isEnabled.value || !shouldAllowSpeechInterrupt()) return
    // Starting a recognizer during finalization or a paused PTT turn can kill
    // TTS playback and compete with the realtime recorder for microphone ownership.
    mainHandler.post {
      synchronized(realtimeCapturePauseLock) {
        // Recheck after dispatch so a listener queued before PTT cannot reclaim
        // the microphone while the full PTT turn still owns it.
        if (stopRequested || !shouldAllowSpeechInterrupt()) return@post
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@post
        try {
          if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(recognitionListener(null, it)) }
          }
          recognizer?.cancel()
          startListeningInternal(markListening = false)
        } catch (_: Throwable) {
          // ignore
        }
      }
    }
  }

  private fun recognitionListener(
    captureId: String?,
    owner: SpeechRecognizer,
  ): RecognitionListener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) =
        withCurrentRecognition(owner, captureId) {
          // Only a live listening session may claim the status; a speech-interrupt
          // recognizer readying during playback must not touch Thinking state.
          if (activePttCaptureId != null && _isListening.value) {
            setStatus(nativeText("Listening (PTT)"))
          } else if (_isEnabled.value && _isListening.value) {
            setStatus(nativeText("Listening"))
          }
        }

      override fun onBeginningOfSpeech() {}

      override fun onRmsChanged(rmsdB: Float) {}

      override fun onBufferReceived(buffer: ByteArray?) {}

      // onResults/onError always follow end of speech and own the restart. Restarting here
      // cancels the session before its final hypothesis, and the cancel's ERROR_CLIENT clears
      // listening, so checkSilence never sends the transcript.
      override fun onEndOfSpeech() {}

      override fun onError(error: Int) =
        withCurrentRecognition(owner, captureId) {
          if (stopRequested) return@withCurrentRecognition
          _isListening.value = false
          val pushToTalkActive = activePttCaptureId != null
          if (pushToTalkActive) {
            pttReleaseCompletion?.let {
              it.complete(Unit)
              return@withCurrentRecognition
            }
          }
          if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            setStatus(nativeText("Microphone permission required"))
            return@withCurrentRecognition
          }

          setStatus(
            when (error) {
              SpeechRecognizer.ERROR_AUDIO -> nativeText("Audio error")

              SpeechRecognizer.ERROR_CLIENT -> nativeText("Client error")

              SpeechRecognizer.ERROR_NETWORK -> nativeText("Network error")

              SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> nativeText("Network timeout")

              SpeechRecognizer.ERROR_NO_MATCH,
              SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
              -> if (pushToTalkActive) nativeText("Listening (PTT)") else nativeText("Listening")

              SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> nativeText("Recognizer busy")

              SpeechRecognizer.ERROR_SERVER -> nativeText("Server error")

              else -> nativeText("Speech error (\$error)", error)
            },
          )
          if (pushToTalkActive) {
            schedulePushToTalkRestart(
              delayMs = 600L,
              advanceRung = pttRecognitionRung !is PushToTalkRecognitionRung.RestartingSingleSession,
            )
            return@withCurrentRecognition
          }
          scheduleRestart(delayMs = 600)
        }

      override fun onResults(results: Bundle?) =
        withCurrentRecognition(owner, captureId) {
          val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
          list.firstOrNull()?.let { handleTranscript(it, isFinal = true) }
          if (activePttCaptureId != null) {
            _isListening.value = false
            pttReleaseCompletion?.let {
              it.complete(Unit)
              return@withCurrentRecognition
            }
            schedulePushToTalkRestart(
              delayMs = pushToTalkRestartDelayMs,
              advanceRung = pttRecognitionRung !is PushToTalkRecognitionRung.RestartingSingleSession,
            )
            return@withCurrentRecognition
          }
          scheduleRestart()
        }

      override fun onPartialResults(partialResults: Bundle?) =
        withCurrentRecognition(owner, captureId) {
          val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
          list.firstOrNull()?.let { handleTranscript(it, isFinal = false) }
        }

      override fun onSegmentResults(segmentResults: Bundle) =
        withCurrentRecognition(owner, captureId) {
          if (activePttCaptureId == null) return@withCurrentRecognition
          val list = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
          list.firstOrNull()?.let { handleTranscript(it, isFinal = true) }
        }

      override fun onEndOfSegmentedSession() =
        withCurrentRecognition(owner, captureId) {
          if (activePttCaptureId == null) return@withCurrentRecognition
          _isListening.value = false
          pttReleaseCompletion?.let {
            it.complete(Unit)
            return@withCurrentRecognition
          }
          schedulePushToTalkRestart(
            delayMs = 180L,
            advanceRung = shouldAdvancePushToTalkRungAfterSegmentedSession(pttRecognitionRung?.candidate ?: return@withCurrentRecognition),
          )
        }

      override fun onEvent(
        eventType: Int,
        params: Bundle?,
      ) {}
    }

  // A destroyed recognizer can still deliver queued callbacks, including continuous Talk's null PTT id.
  private inline fun withCurrentRecognition(
    owner: SpeechRecognizer,
    captureId: String?,
    action: () -> Unit,
  ) {
    // SDK recognition callbacks and SystemSpeechSpeaker.beforeSpeak both run on Main; keep that ordering.
    synchronized(realtimeCapturePauseLock) {
      if (recognizer !== owner || captureId != activePttCaptureId) return
      action()
    }
  }
}

internal fun requireAcceptedRealtimeOutputCancellation(
  response: String,
  turnId: String?,
): TalkSessionCancelOutputResult {
  val result = Json.decodeFromString<TalkSessionCancelOutputResult>(response)
  check(result.ok) { "talk.session.cancelOutput was not accepted" }
  when (result.status) {
    null,
    "applied",
    "stale",
    "idle",
    -> Unit

    else -> error("unknown talk.session.cancelOutput status")
  }
  check(turnId == null || result.turnId == null || result.turnId == turnId) {
    "talk.session.cancelOutput turnId did not match"
  }
  return result
}

private fun JsonElement?.asDoubleOrNull(): Double? {
  val primitive = this as? JsonPrimitive ?: return null
  return primitive.content.toDoubleOrNull()
}

private fun JsonElement?.asBooleanOrNull(): Boolean? {
  val primitive = this as? JsonPrimitive ?: return null
  val content = primitive.content.trim().lowercase()
  return when (content) {
    "true", "yes", "1" -> true
    "false", "no", "0" -> false
    else -> null
  }
}

private fun GatewaySession.ErrorShape.isUnsupportedSessionLanguageParam(): Boolean =
  code == "INVALID_REQUEST" &&
    message
      .lowercase(Locale.ROOT)
      .contains("invalid talk.session.create params")
