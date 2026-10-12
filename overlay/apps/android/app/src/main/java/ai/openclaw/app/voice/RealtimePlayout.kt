package ai.openclaw.app.voice

import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/** The only owner of the realtime AudioTrack. Gateway callbacks enqueue; they never touch it. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RealtimePlayout(
  scope: CoroutineScope,
  dispatcher: CoroutineDispatcher,
  private val sampleRate: Int,
) {
  class Session(
    // `freshPcm` is true only after a complete PCM frame has been accepted. State refreshes and
    // pre-write notifications must not be able to extend UI audibility for a silent stream.
    val onState: (Boolean, Float?, Any?, Boolean, Float?) -> Unit,
    val onMark: (String) -> Unit,
    val onFailure: (String) -> Unit,
    val routePolicy: RealtimeAudioRoutePolicy = RealtimeAudioRoutePolicy.Communication,
    /** Fires at the AudioTrack playhead after the final audible PCM frame, before its quiet drain. */
    val onAudiblePresented: () -> Unit = {},
    val onAudibleDrained: () -> Unit = {},
  ) {
    @Volatile var active = true
      internal set

    @Volatile internal var epoch = 0L
  }

  private sealed interface Command {
    data class Audio(
      val session: Session,
      val epoch: Long,
      val bytes: ByteArray,
      val statusOwner: Any?,
      // Captured at TalkMode's gateway-event callback. It is intentionally not labelled as a
      // network timestamp: the WebSocket decoder and parent event fan-out precede this boundary.
      val talkEventReceivedAtMs: Long,
      val ingressAtMs: Long,
    ) : Command

    data class Mark(
      val session: Session,
      val epoch: Long,
      val name: String,
    ) : Command

    data class RefreshState(
      val session: Session,
      val statusOwner: Any?,
    ) : Command

    data class AudioDone(
      val session: Session,
      val statusOwner: Any?,
    ) : Command

    data class Clear(
      val session: Session,
      val acknowledge: Boolean,
      val completion: CompletableDeferred<Unit>,
    ) : Command
  }

  private data class Mark(
    val name: String,
    val frame: Long,
  )

  // Epoch changes and admission share one lock: new audio can never overtake its clear.
  private val mailboxLock = Any()
  private var queuedBytes = 0L
  private var queuedMedia = 0
  private var queuedRefreshes = 0

  // Refreshes have their own budget; the final 32 slots remain reserved for Clear.
  private val commands =
    Channel<Command>(MAX_QUEUED_MEDIA + MAX_QUEUED_REFRESHES + 32, onUndeliveredElement = {
      if (it is Command.Clear) it.completion.cancel()
    })
  private var track: AudioTrack? = null
  private var owner: Session? = null
  private var statusOwner: Any? = null
  private var writtenFrames = 0L
  private val timestamp = AudioTimestamp()
  private var approximateEndMs = 0L
  private var timestampOriginNs = 0L
  private var lastHeadPosition = 0L
  // A continuously ready relay mailbox must not starve physical presentation checks.
  private var lastPhysicalPollMs = 0L
  private var stalledMs = 0L
  private val marks = mutableListOf<Mark>()
  private var level = 0f
  private var lastAudibleAcceptedFrame: Long? = null
  private var audiblePresentationDelivered = false
  private var audibleDrainDelivered = false
  // Legacy output can accept audible PCM before its full-buffer prime starts playback.
  private var pendingAcceptedAudibleLevel: Float? = null
  private var pendingAcceptedAudibleEpoch = -1L
  private var pendingAcceptedAudibleStatusOwner: Any? = null
  // Bounded device diagnostics: counters only, never PCM content.
  private var diagnosticAcceptedBytes = 0L
  private var diagnosticFirstWriteMs = 0L
  private var diagnosticPeakMax = 0
  private var diagnosticRmsMax = 0
  private var diagnosticFirstAudiblePcmLogged = false
  private var diagnosticMilestoneIndex = 0
  private var diagnosticOutputRouteLogged = false
  private var diagnosticOutputRouteType: Int? = null
  private var diagnosticUnderrunCountAtCreation = 0
  private var diagnosticLastUnderrunCount = 0
  private var diagnosticZeroWrites = 0
  private var diagnosticPartialWrites = 0
  private var diagnosticLastIngressMs = -1L
  private var diagnosticMaxIngressGapMs = 0L
  private var diagnosticLastTalkEventReceivedMs = -1L
  private var diagnosticMaxTalkEventGapMs = 0L
  private var diagnosticMaxTalkEventToEnqueueMs = 0L
  private var diagnosticMaxTalkEventToActorMs = 0L
  private var diagnosticMaxActorWaitMs = 0L
  private var diagnosticMaxActorToTrackWriteMs = 0L
  // Rebuffer may occur while no command is being processed. Keep only bounded timing/level-class
  // facts from the last accepted write so its cause can be classified without retaining PCM.
  private var diagnosticLastTrackWriteAtMs = -1L
  private var diagnosticLastAudibleTrackWriteAtMs = -1L
  private var diagnosticLastTrackWriteAudible = false
  private var diagnosticStartQueuedFrames = -1L
  private var diagnosticMinQueuedFrames = Long.MAX_VALUE
  private var diagnosticInitialBufferFrames = 0
  private var diagnosticSteadyBufferFrames = 0
  private var trackBufferCapacityFrames = 0
  private var diagnosticRebufferCount = 0
  private var primeTargetFrames = 0L
  // The relay remains open until its terminal AudioDone. A physical drain before then is a
  // network starvation, not a completed reply: keep the conversation's playback ownership while
  // the track is paused and refilled.
  private var inputFinished = false

  @Volatile var isPlaying = false
    private set

  internal companion object {
    private const val TAG = "OpenClawPlayout"
    private val DIAGNOSTIC_MILESTONES_MS = longArrayOf(1_000L, 5_000L, 10_000L)
    // Bytes bound the unplayed backlog. The gateway relay splits output into 20 ms frames and
    // adds a mark per provider chunk, so the entry cap must admit the whole byte budget as
    // 20 ms frames plus one mark each; a smaller entry cap ended long replies at ~77 s.
    const val MAX_QUEUED_BYTES = 12L * 1024 * 1024
    /** Shared threshold for assistant audio that should affect a voice conversation. */
    const val AUDIBLE_LEVEL = 0.01f
    private const val AUDIBLE_DRAIN_SILENCE_MS = 300L
    // Echo's live relay has seen a 267 ms ingress gap and real underflows during a 5.4 s reply.
    // Reserve 480 ms before starting; capacity alone cannot help if playback begins on a 20 ms
    // relay packet and thereafter consumes at the relay's one-times rate.
    private const val INITIAL_BUFFER_MS = 480
    private const val STEADY_BUFFER_MS = 480
    // Legacy AudioTrack instances retain a full-buffer start threshold. Only a terminal
    // AudioDone may pad the incomplete tail; an open relay always waits for real PCM.
    private const val PRIME_POLL_MS = 20L
    private const val MAX_QUEUED_REFRESHES = 32
    private const val RELAY_FRAME_BYTES = 24_000 * 2 * 20 / 1000
    const val MAX_QUEUED_MEDIA = ((MAX_QUEUED_BYTES + RELAY_FRAME_BYTES - 1) / RELAY_FRAME_BYTES * 2).toInt()
  }

  init {
    scope
      .launch(dispatcher) {
        try {
          while (true) {
            coroutineContext.ensureActive()
            val command =
              select<Command?> {
                commands.onReceive { it }
                if (isPlaying) onTimeout(PRIME_POLL_MS) { null }
              }
            if (command == null) {
              if (isPlaying) poll(periodic = true)
              continue
            }
            if (command !is Command.Clear) {
              synchronized(mailboxLock) {
                if (command is Command.Audio) queuedBytes -= command.bytes.size
                if (command is Command.RefreshState || command is Command.AudioDone) queuedRefreshes-- else queuedMedia--
              }
            }
            when (command) {
              is Command.Audio -> {
                if (!command.session.active || command.epoch != command.session.epoch) continue
                try {
                  write(command)
                } catch (error: CancellationException) {
                  coroutineContext.ensureActive()
                  fail(command.session, "audio playback stalled")
                } catch (error: RuntimeException) {
                  fail(command.session, error.message ?: "audio playback failed")
                }
              }

              is Command.Mark -> {
                if (!command.session.active) continue
                if (owner === command.session) {
                  marks += Mark(command.name, writtenFrames)
                  // An interrupted mark acknowledges only after the queued clear has flushed audio.
                  if (command.epoch == command.session.epoch) poll()
                } else {
                  command.session.onMark(command.name)
                }
              }

              is Command.RefreshState -> {
                if (command.session.active) {
                  if (owner === command.session) {
                    statusOwner = command.statusOwner
                    poll()
                    if (isPlaying) command.session.onState(true, null, command.statusOwner, false, null)
                  } else {
                    command.session.onState(false, null, command.statusOwner, false, null)
                  }
                }
              }

              is Command.AudioDone -> {
                if (command.session.active && owner === command.session) {
                  statusOwner = command.statusOwner
                  inputFinished = true
                  try {
                    padLegacyStartBuffer(track, command.session)
                  } catch (error: CancellationException) {
                    coroutineContext.ensureActive()
                    fail(command.session, "audio playback stalled")
                    continue
                  } catch (error: RuntimeException) {
                    fail(command.session, error.message ?: "audio playback failed")
                    continue
                  }
                  startPlaybackIfReady(track, command.session, force = true)
                  publishPendingAcceptedAudibleState(command.session)
                  poll()
                  if (isPlaying) command.session.onState(true, null, command.statusOwner, false, null)
                }
              }

              is Command.Clear -> {
                try {
                  if (owner === command.session) retire(command.acknowledge)
                  command.completion.complete(Unit)
                } catch (error: RuntimeException) {
                  command.completion.completeExceptionally(error)
                }
              }
            }
          }
        } finally {
          retire(acknowledge = false)
        }
      }.invokeOnCompletion {
        // Also runs when cancellation wins before the actor body starts.
        val pending = mutableListOf<CompletableDeferred<Unit>>()
        synchronized(mailboxLock) {
          commands.close()
          while (true) {
            val command = commands.tryReceive().getOrNull() ?: break
            if (command is Command.Clear) pending += command.completion
          }
        }
        pending.forEach { it.cancel() }
      }
  }

  fun audio(
    session: Session,
    bytes: ByteArray,
    statusOwner: Any,
    talkEventReceivedAtMs: Long = SystemClock.elapsedRealtime(),
  ): (() -> Unit)? {
    val ingressAtMs = SystemClock.elapsedRealtime()
    return if (bytes.isNotEmpty()) {
      offer(session, bytes.size) {
        Command.Audio(
          session = session,
          epoch = session.epoch,
          bytes = bytes,
          statusOwner = statusOwner,
          talkEventReceivedAtMs = talkEventReceivedAtMs,
          ingressAtMs = ingressAtMs,
        )
      }
    } else {
      null
    }
  }

  fun mark(
    session: Session,
    name: String,
  ) = offer(session, 0) { Command.Mark(session, session.epoch, name) }

  fun refreshState(
    session: Session,
    statusOwner: Any,
  ) = offer(session, 0) { Command.RefreshState(session, statusOwner) }

  /** Releases a short legacy A2DP reply that cannot fill the normal prime target. */
  fun audioDone(
    session: Session,
    statusOwner: Any,
  ) = offer(session, 0) { Command.AudioDone(session, statusOwner) }

  private fun offer(
    session: Session,
    bytes: Int,
    command: () -> Command,
  ): (() -> Unit)? {
    val overflow =
      synchronized(mailboxLock) {
        if (!session.active) return null
        val next = command()
        val nonMedia = next is Command.RefreshState || next is Command.AudioDone
        val full = if (nonMedia) queuedRefreshes >= MAX_QUEUED_REFRESHES else queuedMedia >= MAX_QUEUED_MEDIA
        if (full || queuedBytes + bytes > MAX_QUEUED_BYTES || !commands.trySend(next).isSuccess) {
          true
        } else {
          if (nonMedia) queuedRefreshes++ else queuedMedia++
          queuedBytes += bytes
          false
        }
      }
    if (overflow) {
      clear(session, acknowledge = false)
      // Admission may hold the caller's lifecycle lock; outward failure notification may not.
      return { session.onFailure("audio playback queue overflow") }
    }
    return null
  }

  fun clear(
    session: Session,
    acknowledge: Boolean = true,
  ): CompletableDeferred<Unit> =
    synchronized(mailboxLock) {
      session.epoch++
      if (!acknowledge) session.active = false
      CompletableDeferred<Unit>().also { completion ->
        if (!commands.trySend(Command.Clear(session, acknowledge, completion)).isSuccess) completion.cancel()
      }
    }

  private suspend fun write(command: Command.Audio) {
    val actorStartedAtMs = SystemClock.elapsedRealtime()
    if (owner !== command.session) {
      retire(acknowledge = false)
      owner = command.session
    }
    statusOwner = command.statusOwner
    diagnosticMaxActorWaitMs = maxOf(diagnosticMaxActorWaitMs, actorStartedAtMs - command.ingressAtMs)
    diagnosticMaxTalkEventToEnqueueMs = maxOf(
      diagnosticMaxTalkEventToEnqueueMs,
      (command.ingressAtMs - command.talkEventReceivedAtMs).coerceAtLeast(0L),
    )
    diagnosticMaxTalkEventToActorMs = maxOf(
      diagnosticMaxTalkEventToActorMs,
      (actorStartedAtMs - command.talkEventReceivedAtMs).coerceAtLeast(0L),
    )
    if (diagnosticLastTalkEventReceivedMs >= 0L) {
      diagnosticMaxTalkEventGapMs = maxOf(
        diagnosticMaxTalkEventGapMs,
        (command.talkEventReceivedAtMs - diagnosticLastTalkEventReceivedMs).coerceAtLeast(0L),
      )
    }
    diagnosticLastTalkEventReceivedMs = command.talkEventReceivedAtMs
    if (diagnosticLastIngressMs >= 0L) {
      diagnosticMaxIngressGapMs = maxOf(
        diagnosticMaxIngressGapMs,
        (command.ingressAtMs - diagnosticLastIngressMs).coerceAtLeast(0L),
      )
    }
    diagnosticLastIngressMs = command.ingressAtMs
    val bytes = command.bytes
    // Commands are FIFO. A non-empty Audio command after this session's AudioDone belongs to its
    // next response turn, so it must not inherit the former turn's terminal drain state.
    if (inputFinished && bytes.isNotEmpty()) {
      inputFinished = false
      lastAudibleAcceptedFrame = null
      audiblePresentationDelivered = false
      audibleDrainDelivered = false
      pendingAcceptedAudibleLevel = null
      pendingAcceptedAudibleEpoch = -1L
      pendingAcceptedAudibleStatusOwner = null
      primeTargetFrames = 0L
      Log.i(TAG, "new output turn after terminal audio")
    }
    val output =
      track ?: AudioTrack
        .Builder()
        .setAudioAttributes(RealtimeCommunicationAudio.playbackAttributes(command.session.routePolicy))
        .setAudioFormat(
          AudioFormat
            .Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        ).setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
          maxOf(
            AudioTrack.getMinBufferSize(
              sampleRate,
              AudioFormat.CHANNEL_OUT_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
            ),
            sampleRate * 2 * STEADY_BUFFER_MS / 1000,
          ),
        ).build()
        .also {
          track = it
          diagnosticUnderrunCountAtCreation = it.underrunCount
          diagnosticLastUnderrunCount = diagnosticUnderrunCountAtCreation
          trackBufferCapacityFrames = it.bufferSizeInFrames
          val requestedInitialFrames = (sampleRate * INITIAL_BUFFER_MS / 1_000).coerceAtMost(trackBufferCapacityFrames)
          diagnosticInitialBufferFrames = it.setBufferSizeInFrames(requestedInitialFrames)
          diagnosticSteadyBufferFrames = diagnosticInitialBufferFrames
          Log.i(
            TAG,
            "track created rate=$sampleRate capacityFrames=$trackBufferCapacityFrames " +
              "initialBufferFrames=$diagnosticInitialBufferFrames " +
              "usage=${RealtimeCommunicationAudio.playbackAttributes(command.session.routePolicy).usage} " +
              "route=${command.session.routePolicy}",
          )
          // Streaming defaults to a full buffer; a short final reply might never start.
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.setStartThresholdInFrames(1)
        }
    // A timestamp from before an empty buffer cannot predict newly submitted audio after an idle gap.
    if (!requiresLegacyStartPrime() && output.playbackHeadPosition.toLong().and(0xffff_ffffL) >= writtenFrames) {
      timestampOriginNs = System.nanoTime()
    }
    logOutputRoute(output)
    val bufferMs = output.bufferSizeInFrames.toLong() * 1000 / sampleRate
    var freshAudibleStateDelivered = false
    // A partial non-blocking write retries the same immutable command PCM. Keep its diagnostic
    // peak/RMS scan lazy (no scan if every write is zero) but never repeat it per accepted slice.
    var commandStatistics: Pair<Int, Int>? = null
    withTimeout((bufferMs * 4).coerceAtLeast(400) + bytes.size.toLong() * 1000 / (sampleRate * 2)) {
      var offset = 0
      while (offset < bytes.size && command.session.active && command.epoch == command.session.epoch) {
        val accepted = output.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
        check(accepted >= 0) { "audio playback device error $accepted" }
        if (accepted == 0) {
          diagnosticZeroWrites++
          delay((bufferMs / 16).coerceIn(2, 10))
        } else {
          val acceptedStart = offset
          val trackWriteAtMs = SystemClock.elapsedRealtime()
          diagnosticMaxActorToTrackWriteMs = maxOf(
            diagnosticMaxActorToTrackWriteMs,
            trackWriteAtMs - actorStartedAtMs,
          )
          if (accepted < bytes.size - offset) diagnosticPartialWrites++
          val (peak, rms) = commandStatistics ?: pcm16Statistics(bytes, bytes.size).also { commandStatistics = it }
          if (diagnosticAcceptedBytes == 0L) {
            diagnosticFirstWriteMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "first chunk bytes=${bytes.size} peak=$peak rms=$rms accepted=$accepted")
          }
          offset += accepted
          diagnosticAcceptedBytes += accepted
          diagnosticPeakMax = maxOf(diagnosticPeakMax, peak)
          diagnosticRmsMax = maxOf(diagnosticRmsMax, rms)
          if (!diagnosticFirstAudiblePcmLogged && peak > 128) {
            diagnosticFirstAudiblePcmLogged = true
            Log.i(TAG, "first audible PCM peak=$peak rms=$rms accepted=$accepted")
          }
          writtenFrames += accepted / 2
          if (primeTargetFrames == 0L) {
            primeTargetFrames = output.bufferSizeInFrames.toLong()
          }
          val acceptedRawPcmLevel = pcm16Level(bytes, acceptedStart, accepted)
          diagnosticLastTrackWriteAtMs = trackWriteAtMs
          diagnosticLastTrackWriteAudible = acceptedRawPcmLevel > AUDIBLE_LEVEL
          if (acceptedRawPcmLevel > AUDIBLE_LEVEL) {
            diagnosticLastAudibleTrackWriteAtMs = trackWriteAtMs
            lastAudibleAcceptedFrame = writtenFrames
            audiblePresentationDelivered = false
            audibleDrainDelivered = false
            if (!freshAudibleStateDelivered) {
              pendingAcceptedAudibleLevel = acceptedRawPcmLevel
              pendingAcceptedAudibleEpoch = command.epoch
              pendingAcceptedAudibleStatusOwner = command.statusOwner
            }
          }
          logPlayoutAggregate(output.playbackHeadPosition.toLong().and(0xffff_ffffL))
          // Account at acceptance: paced writes have already played part of a large chunk.
          approximateEndMs = maxOf(SystemClock.elapsedRealtime(), approximateEndMs) + accepted.toLong() * 1000 / (sampleRate * 2)
          startPlaybackIfReady(output, command.session)
          // Publish accepted audible PCM after any legacy prime release but before this write's
          // physical presentation/drain poll.
          freshAudibleStateDelivered = publishPendingAcceptedAudibleState(command.session) || freshAudibleStateDelivered
          pollDuringWriteIfDue()
          yield()
        }
      }
    }
    if (!command.session.active || command.epoch != command.session.epoch) return
    if (!freshAudibleStateDelivered) {
      val rawPcmLevel = TalkAudioLevel.pcm16Level(bytes, bytes.size)
      level = TalkAudioLevel.smoothed(level, rawPcmLevel)
      if (isPlaying) command.session.onState(true, level, statusOwner, true, rawPcmLevel)
    }
  }

  /** Android 11 and older retain the stream's full-buffer start threshold for every route. */
  private fun requiresLegacyStartPrime(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

  private fun startPlaybackIfReady(
    output: AudioTrack?,
    session: Session,
    force: Boolean = false,
  ) {
    if (output == null || isPlaying || !session.active || owner !== session) return
    val headPosition = output.playbackHeadPosition.toLong().and(0xffff_ffffL)
    val queuedFrames = (writtenFrames - headPosition).coerceAtLeast(0L)
    if (!force && queuedFrames < primeTargetFrames) return
    timestampOriginNs = System.nanoTime()
    // Before prime release, all accepted frames are still resident. Rebase the duration estimate
    // so its completion gate cannot consume the pre-roll interval as if it had already played.
    approximateEndMs = SystemClock.elapsedRealtime() + queuedFrames * 1_000L / sampleRate
    primeTargetFrames = 0L
    diagnosticStartQueuedFrames = queuedFrames
    diagnosticMinQueuedFrames = queuedFrames
    isPlaying = true
    commandStatePlaying(session)
    if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
  }

  private fun publishPendingAcceptedAudibleState(session: Session): Boolean {
    val rawPcmLevel = pendingAcceptedAudibleLevel ?: return false
    if (owner !== session || !session.active || pendingAcceptedAudibleEpoch != session.epoch) {
      pendingAcceptedAudibleLevel = null
      pendingAcceptedAudibleEpoch = -1L
      pendingAcceptedAudibleStatusOwner = null
      return false
    }
    if (!isPlaying) return false
    level = TalkAudioLevel.smoothed(level, rawPcmLevel)
    session.onState(true, level, pendingAcceptedAudibleStatusOwner, true, rawPcmLevel)
    pendingAcceptedAudibleLevel = null
    pendingAcceptedAudibleEpoch = -1L
    pendingAcceptedAudibleStatusOwner = null
    return true
  }

  private fun commandStatePlaying(session: Session) {
    session.onState(true, null, statusOwner, false, null)
  }

  /**
   * `AudioTrack.play()` cannot release a short stream on Android 11 and older until its buffer
   * fills. At the terminal boundary, append only the missing quiet frames so that the submitted
   * PCM remains intact and the normal physical drain/mark ordering still applies.
   */
  private suspend fun padLegacyStartBuffer(
    output: AudioTrack?,
    session: Session,
  ) {
    if (output == null || !requiresLegacyStartPrime() || isPlaying || !session.active || owner !== session) return
    val headPosition = output.playbackHeadPosition.toLong().and(0xffff_ffffL)
    val missingFrames = (output.bufferSizeInFrames.toLong() - (writtenFrames - headPosition)).coerceAtLeast(0L)
    if (missingFrames == 0L) return
    val padding = ByteArray((missingFrames * 2).toInt())
    val bufferMs = output.bufferSizeInFrames.toLong() * 1000 / sampleRate
    withTimeout((bufferMs * 4).coerceAtLeast(400) + padding.size.toLong() * 1000 / (sampleRate * 2)) {
      var offset = 0
      while (offset < padding.size && session.active && owner === session) {
        val accepted = output.write(padding, offset, padding.size - offset, AudioTrack.WRITE_NON_BLOCKING)
        check(accepted >= 0) { "audio playback device error $accepted" }
        if (accepted == 0) {
          diagnosticZeroWrites++
          delay((bufferMs / 16).coerceIn(2, 10))
        } else {
          if (accepted < padding.size - offset) diagnosticPartialWrites++
          offset += accepted
          diagnosticAcceptedBytes += accepted
          writtenFrames += accepted / 2
          logPlayoutAggregate(output.playbackHeadPosition.toLong().and(0xffff_ffffL))
          approximateEndMs = maxOf(SystemClock.elapsedRealtime(), approximateEndMs) + accepted.toLong() * 1000 / (sampleRate * 2)
          yield()
        }
      }
    }
  }

  private fun poll(
    periodic: Boolean = false,
    deferIdleState: Boolean = false,
  ) {
    lastPhysicalPollMs = SystemClock.elapsedRealtime()
    val session = owner ?: return
    val output = track ?: return
    val headPosition = output.playbackHeadPosition.toLong().and(0xffff_ffffL)
    val underrunCount = output.underrunCount
    val observedNewUnderrun = underrunCount > diagnosticLastUnderrunCount
    diagnosticLastUnderrunCount = underrunCount
    if (isPlaying) diagnosticMinQueuedFrames = minOf(diagnosticMinQueuedFrames, (writtenFrames - headPosition).coerceAtLeast(0L))
    logPlayoutAggregate(headPosition)
    // Android timestamps are clock anchors, not completion counters; a drained server anchor can stop advancing.
    // False queries leave the object unchanged, so retain a known future commitment across temporary loss.
    val available = output.getTimestamp(timestamp)
    val nowNs = System.nanoTime()
    val hasTimestamp = (available || timestamp.nanoTime > nowNs) && timestamp.nanoTime >= timestampOriginNs
    val futureNs = if (hasTimestamp) (timestamp.nanoTime - nowNs).coerceAtLeast(0) else 0
    if (futureNs > 0) {
      val remainingNs = futureNs + (writtenFrames - timestamp.framePosition).coerceAtLeast(0) * 1_000_000_000 / sampleRate
      approximateEndMs = maxOf(approximateEndMs, SystemClock.elapsedRealtime() + (remainingNs + 999_999) / 1_000_000)
    }
    val remainingMs = (approximateEndMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    val presented =
      if (hasTimestamp) {
        timestamp.framePosition + Math.floorDiv((nowNs - timestamp.nanoTime) * sampleRate, 1_000_000_000)
      } else {
        // Android recommends the approximate head when timestamps are unavailable; retain the PCM-duration floor.
        writtenFrames - (remainingMs * sampleRate + 999) / 1000
      }.coerceIn(0, minOf(headPosition, writtenFrames))
    val waitingForPresentation = if (hasTimestamp) futureNs > 0 else remainingMs > 0
    val ready = marks.filter { it.frame <= presented }
    marks.removeAll(ready.toSet())
    if (session.active) ready.forEach { session.onMark(it.name) }
    deliverAudibleDrain(session, presented)
    // Android's native underrun counter is a physical starvation signal. It can advance while
    // timestamp/PCM-duration estimates still conservatively report pending presentation; pause
    // only the open relay after due marks and quiet-tail callbacks have been delivered.
    if (isPlaying && !inputFinished && observedNewUnderrun) {
      pauseForRebuffer(output, headPosition)
      return
    }
    stalledMs = if (headPosition > lastHeadPosition || waitingForPresentation) 0 else stalledMs + if (periodic) 20 else 0
    lastHeadPosition = headPosition
    if (presented >= writtenFrames && !waitingForPresentation) {
      stalledMs = 0
      if (isPlaying && !deferIdleState) {
        if (!inputFinished) {
          pauseForRebuffer(output, headPosition)
        } else {
          Log.i(TAG, "drained head=$headPosition writtenFrames=$writtenFrames acceptedBytes=$diagnosticAcceptedBytes")
          if (output.playState == AudioTrack.PLAYSTATE_PLAYING) output.pause()
          isPlaying = false
          session.onState(false, null, statusOwner, false, null)
        }
      }
    } else if (stalledMs >= (output.bufferSizeInFrames.toLong() * 4_000 / sampleRate).coerceAtLeast(400)) {
      fail(session, "audio playback stalled")
    }
  }

  private fun pauseForRebuffer(output: AudioTrack, headPosition: Long) {
    val now = SystemClock.elapsedRealtime()
    val sinceLastTrackWriteMs = elapsedSince(now, diagnosticLastTrackWriteAtMs)
    val sinceLastAudibleTrackWriteMs = elapsedSince(now, diagnosticLastAudibleTrackWriteAtMs)
    val sinceLastTalkEventMs = elapsedSince(now, diagnosticLastTalkEventReceivedMs)
    if (output.playState == AudioTrack.PLAYSTATE_PLAYING) output.pause()
    isPlaying = false
    primeTargetFrames = output.bufferSizeInFrames.toLong()
    diagnosticRebufferCount++
    Log.i(
      TAG,
      "rebuffer paused head=$headPosition writtenFrames=$writtenFrames targetFrames=$primeTargetFrames " +
        "sinceLastTrackWriteMs=$sinceLastTrackWriteMs " +
        "sinceLastAudibleTrackWriteMs=$sinceLastAudibleTrackWriteMs " +
        "lastTrackWriteAudible=$diagnosticLastTrackWriteAudible " +
        "sinceLastTalkEventMs=$sinceLastTalkEventMs",
    )
  }

  /** `-1` means the current playout owner has not accepted that class of data. */
  private fun elapsedSince(
    nowMs: Long,
    previousMs: Long,
  ): Long = if (previousMs < 0L) -1L else (nowMs - previousMs).coerceAtLeast(0L)

  private fun pollDuringWriteIfDue() {
    if (!isPlaying) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastPhysicalPollMs < PRIME_POLL_MS) return
    // Keep the current command's fresh-PCM state ordered before any idle transition.
    poll(periodic = true, deferIdleState = true)
  }

  private fun deliverAudibleDrain(
    session: Session,
    presented: Long,
  ) {
    val audibleFrame = lastAudibleAcceptedFrame ?: return
    if (!audiblePresentationDelivered && presented >= audibleFrame && session.active) {
      audiblePresentationDelivered = true
      Log.i(TAG, "audible presented frame=$audibleFrame presented=$presented")
      session.onAudiblePresented()
    }
    val silenceFrames = sampleRate.toLong() * AUDIBLE_DRAIN_SILENCE_MS / 1_000L
    val drainFrame = audibleFrame + silenceFrames
    // The relay can keep an AudioTrack alive indefinitely with zero PCM. Require those zero
    // frames to have been accepted and rendered before opening the conversational follow-up.
    if (!audibleDrainDelivered && writtenFrames >= drainFrame && presented >= drainFrame && session.active) {
      audibleDrainDelivered = true
      Log.i(TAG, "audible drained frame=$audibleFrame presented=$presented")
      session.onAudibleDrained()
    }
  }

  private fun fail(
    session: Session,
    message: String,
  ) {
    val headPosition = track?.playbackHeadPosition?.toLong()?.and(0xffff_ffffL) ?: 0L
    Log.w(TAG, "failure=$message head=$headPosition writtenFrames=$writtenFrames acceptedBytes=$diagnosticAcceptedBytes")
    clear(session, acknowledge = false)
    try {
      if (owner === session) retire(acknowledge = false)
    } finally {
      session.onFailure(message)
    }
  }

  private fun logOutputRoute(output: AudioTrack) {
    val routedType = output.routedDevice?.type
    if (!diagnosticOutputRouteLogged || routedType != diagnosticOutputRouteType) {
      diagnosticOutputRouteLogged = true
      diagnosticOutputRouteType = routedType
      Log.i(TAG, "output route=${routedType ?: "pending"}")
    }
  }

  private fun pcm16Level(
    bytes: ByteArray,
    offset: Int,
    length: Int,
  ): Float {
    var sum = 0.0
    var samples = 0
    var index = offset
    val limit = offset + length - (length % 2)
    while (index + 1 < limit) {
      val sample =
        ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8))
          .toShort()
          .toInt()
      val normalized = sample / Short.MAX_VALUE.toDouble()
      sum += normalized * normalized
      samples++
      index += 2
    }
    return if (samples == 0) 0f else TalkAudioLevel.normalized(kotlin.math.sqrt(sum / samples))
  }

  private fun retire(acknowledge: Boolean) {
    val session = owner
    try {
      track?.let { output ->
        val headPosition = output.playbackHeadPosition.toLong().and(0xffff_ffffL)
        val underruns = (output.underrunCount - diagnosticUnderrunCountAtCreation).coerceAtLeast(0)
        Log.i(
          TAG,
          "retire route=${diagnosticOutputRouteType ?: output.routedDevice?.type ?: "pending"} " +
            "head=$headPosition writtenFrames=$writtenFrames acceptedBytes=$diagnosticAcceptedBytes " +
            "peakMax=$diagnosticPeakMax rmsMax=$diagnosticRmsMax " +
            "underruns=$underruns zeroWrites=$diagnosticZeroWrites partialWrites=$diagnosticPartialWrites " +
            // `talkEvent*` begins at TalkMode's gateway-event callback. It deliberately excludes
            // the WebSocket decoder and is therefore an app callback boundary, not a network RTT.
            "talkEventGapMs=$diagnosticMaxTalkEventGapMs " +
            "talkEventToEnqueueMs=$diagnosticMaxTalkEventToEnqueueMs " +
            "talkEventToActorMs=$diagnosticMaxTalkEventToActorMs " +
            "ingressGapMs=$diagnosticMaxIngressGapMs actorWaitMs=$diagnosticMaxActorWaitMs " +
            "actorToTrackWriteMs=$diagnosticMaxActorToTrackWriteMs " +
            "capacityFrames=$trackBufferCapacityFrames initialBufferFrames=$diagnosticInitialBufferFrames " +
            "steadyBufferFrames=$diagnosticSteadyBufferFrames rebufferCount=$diagnosticRebufferCount " +
            "startQueuedFrames=$diagnosticStartQueuedFrames " +
            "minQueuedFrames=${diagnosticMinQueuedFrames.takeIf { it != Long.MAX_VALUE } ?: 0L}",
        )
        runCatching { output.pause() }
        runCatching { output.flush() }
        runCatching { output.stop() }
        output.release()
      }
    } finally {
      track = null
      owner = null
      writtenFrames = 0
      approximateEndMs = 0
      timestamp.nanoTime = 0
      lastHeadPosition = 0
      lastPhysicalPollMs = 0L
      stalledMs = 0
      isPlaying = false
      level = 0f
      lastAudibleAcceptedFrame = null
      audiblePresentationDelivered = false
      audibleDrainDelivered = false
      pendingAcceptedAudibleLevel = null
      pendingAcceptedAudibleEpoch = -1L
      pendingAcceptedAudibleStatusOwner = null
      diagnosticAcceptedBytes = 0L
      diagnosticFirstWriteMs = 0L
      diagnosticPeakMax = 0
      diagnosticRmsMax = 0
      diagnosticFirstAudiblePcmLogged = false
      diagnosticMilestoneIndex = 0
      diagnosticOutputRouteLogged = false
      diagnosticOutputRouteType = null
      diagnosticUnderrunCountAtCreation = 0
      diagnosticLastUnderrunCount = 0
      diagnosticZeroWrites = 0
      diagnosticPartialWrites = 0
      diagnosticLastIngressMs = -1L
      diagnosticMaxIngressGapMs = 0L
      diagnosticLastTalkEventReceivedMs = -1L
      diagnosticMaxTalkEventGapMs = 0L
      diagnosticMaxTalkEventToEnqueueMs = 0L
      diagnosticMaxTalkEventToActorMs = 0L
      diagnosticMaxActorWaitMs = 0L
      diagnosticMaxActorToTrackWriteMs = 0L
      diagnosticLastTrackWriteAtMs = -1L
      diagnosticLastAudibleTrackWriteAtMs = -1L
      diagnosticLastTrackWriteAudible = false
      diagnosticStartQueuedFrames = -1L
      diagnosticMinQueuedFrames = Long.MAX_VALUE
      diagnosticInitialBufferFrames = 0
      diagnosticSteadyBufferFrames = 0
      trackBufferCapacityFrames = 0
      diagnosticRebufferCount = 0
      primeTargetFrames = 0L
      inputFinished = false
      session?.onState(false, null, statusOwner, false, null)
      statusOwner = null
      val discarded = marks.toList()
      marks.clear()
      if (acknowledge && session?.active == true) discarded.forEach { session.onMark(it.name) }
    }
  }

  private fun pcm16Statistics(bytes: ByteArray, length: Int): Pair<Int, Int> {
    var peak = 0
    var sumSquares = 0.0
    var count = 0
    var index = 0
    while (index + 1 < length) {
      val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
      val amplitude = sample.toShort().toInt()
      peak = maxOf(peak, kotlin.math.abs(amplitude))
      sumSquares += amplitude.toDouble() * amplitude
      count++
      index += 2
    }
    return peak to if (count == 0) 0 else kotlin.math.sqrt(sumSquares / count).toInt()
  }

  private fun logPlayoutAggregate(headPosition: Long) {
    if (diagnosticFirstWriteMs == 0L || diagnosticMilestoneIndex >= DIAGNOSTIC_MILESTONES_MS.size) return
    val elapsed = SystemClock.elapsedRealtime() - diagnosticFirstWriteMs
    while (
      diagnosticMilestoneIndex < DIAGNOSTIC_MILESTONES_MS.size &&
      elapsed >= DIAGNOSTIC_MILESTONES_MS[diagnosticMilestoneIndex]
    ) {
      val milestone = DIAGNOSTIC_MILESTONES_MS[diagnosticMilestoneIndex++]
      Log.i(
        TAG,
        "aggregate after${milestone}ms peakMax=$diagnosticPeakMax rmsMax=$diagnosticRmsMax head=$headPosition writtenFrames=$writtenFrames acceptedBytes=$diagnosticAcceptedBytes",
      )
    }
  }

}
