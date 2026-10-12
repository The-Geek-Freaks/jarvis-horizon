package ai.openclaw.app.voice

import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PlayoutAudioTrack::class])
class RealtimePlayoutTest {
  private val scheduler = TestCoroutineScheduler()
  private val job = SupervisorJob()
  private val acknowledgements = mutableListOf<String>()
  private val failures = mutableListOf<String>()
  private val statusOwner = Any()
  private val playout =
    RealtimePlayout(
      scope = CoroutineScope(job + StandardTestDispatcher(scheduler)),
      dispatcher = StandardTestDispatcher(scheduler),
      sampleRate = 24_000,
    )

  private fun session(id: String) =
    RealtimePlayout.Session(
      onState = { _, _, _, _, _ -> },
      onMark = { mark -> acknowledgements += "$id/$mark" },
      onFailure = { error -> failures += "$id/$error" },
    )

  private val session = session("session")

  /** A 20 ms relay fixture cannot advance hardware head before the 480 ms startup reserve plays. */
  private fun presentedFramesAfterStartupReserve(writes: Int): Int =
    if (PlayoutAudioTrack.track?.playState == AudioTrack.PLAYSTATE_PLAYING) {
      ((writes - 24).coerceAtLeast(0)) * 480
    } else {
      0
    }

  @After
  fun cleanup() {
    job.cancel()
    scheduler.runCurrent()
    PlayoutAudioTrack.reset()
  }

  @Test
  fun ingressOnlyQueuesAndClearCompletesAfterTheActualTrackIsReleased() {
    playout.audio(session, ByteArray(23_040), statusOwner)
    assertEquals(0, PlayoutAudioTrack.writes)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    assertEquals(24_000, track.sampleRate)
    assertEquals(AudioFormat.ENCODING_PCM_16BIT, track.audioFormat)
    assertTrue(playout.isPlaying)
    val cleared = playout.clear(session)
    assertFalse(cleared.isCompleted)
    scheduler.runCurrent()
    assertTrue(cleared.isCompleted)
    assertFalse(cleared.isCancelled)
    assertEquals(AudioTrack.STATE_UNINITIALIZED, track.state)
    assertFalse(playout.isPlaying)
  }

  @Test
  fun retiringARealtimeTrackReportsBoundedNumericSessionDiagnostics() {
    playout.audio(session, ByteArray(4_800), statusOwner)
    scheduler.runCurrent()

    playout.clear(session, acknowledge = false)
    scheduler.runCurrent()

    val diagnostic =
      ShadowLog.getLogsForTag("OpenClawPlayout")
        .lastOrNull { it.msg.startsWith("retire ") }
        ?.msg
        ?: error("Expected one retirement diagnostic")
    assertTrue(diagnostic.contains("route="))
    assertTrue(diagnostic.contains("acceptedBytes=4800"))
    assertTrue(diagnostic.contains("writtenFrames=2400"))
    assertTrue(diagnostic.contains("head="))
    assertTrue(diagnostic.contains("underruns="))
    assertTrue(diagnostic.contains("zeroWrites="))
    assertTrue(diagnostic.contains("partialWrites="))
    assertTrue(diagnostic.contains("talkEventGapMs="))
    assertTrue(diagnostic.contains("talkEventToEnqueueMs="))
    assertTrue(diagnostic.contains("talkEventToActorMs="))
    assertTrue(diagnostic.contains("actorToTrackWriteMs="))
    assertTrue(diagnostic.contains("capacityFrames="))
    assertTrue(diagnostic.contains("initialBufferFrames="))
    assertTrue(diagnostic.contains("steadyBufferFrames="))
  }

  @Test
  @Config(sdk = [28], shadows = [PlayoutAudioTrack::class])
  fun legacyMediaOutputPrimesTheExistingTrackBufferBeforeStarting() {
    PlayoutAudioTrack.presentedFrames = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        routePolicy = RealtimeAudioRoutePolicy.MediaOutput,
      )
    playout.audio(owner, ByteArray(946), statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    assertFalse("A single relay packet must not start legacy A2DP output", playout.isPlaying)

    playout.audio(owner, ByteArray(track.bufferSizeInFrames * 2 - 946), statusOwner)
    scheduler.runCurrent()

    assertTrue("The 480 ms initial track buffer releases playback with a real jitter reserve", playout.isPlaying)
    assertEquals(11_520, track.bufferSizeInFrames)
  }

  @Test
  @Config(sdk = [30], shadows = [PlayoutAudioTrack::class])
  fun legacyCommunicationUsesTheFullBoundedReserveBeforePhysicalPlayback() {
    PlayoutAudioTrack.presentedFrames = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
      )
    playout.audio(owner, ByteArray(946), statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    assertEquals(11_520, track.bufferSizeInFrames)

    playout.audio(owner, ByteArray(track.bufferSizeInFrames * 2 - 946), statusOwner)
    scheduler.runCurrent()

    assertTrue(playout.isPlaying)
    assertEquals(11_520, track.bufferSizeInFrames)
    PlayoutAudioTrack.presentedFrames = 1
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(11_520, track.bufferSizeInFrames)
    val created =
      ShadowLog.getLogsForTag("OpenClawPlayout")
        .lastOrNull { it.msg.startsWith("track created ") }
        ?.msg
        ?: error("Expected track creation diagnostic")
    assertTrue(created.contains("capacityFrames=11520"))
    assertTrue(created.contains("initialBufferFrames=11520"))
  }

  @Test
  @Config(sdk = [28], shadows = [PlayoutAudioTrack::class])
  fun legacyMediaOutputAudioDoneReleasesAShortPrimedReply() {
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        routePolicy = RealtimeAudioRoutePolicy.MediaOutput,
      )
    playout.audio(owner, ByteArray(946), statusOwner)
    scheduler.runCurrent()
    assertFalse(playout.isPlaying)

    playout.audioDone(owner, statusOwner)
    scheduler.runCurrent()

    assertTrue(playout.isPlaying)
  }

  @Test
  @Config(sdk = [30], shadows = [PlayoutAudioTrack::class])
  fun legacyCommunicationNeverPadsAnOpenRelayBeforeLaterRealPcm() {
    PlayoutAudioTrack.presentedFrames = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { failures += it },
      )
    playout.audio(owner, ByteArray(946), statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    val initialBufferFrames = track.bufferSizeInFrames
    assertFalse(playout.isPlaying)

    // An open relay must never splice quiet padding in front of a later real packet.
    ShadowSystemClock.advanceBy(Duration.ofMillis(600))
    scheduler.advanceTimeBy(600)
    scheduler.runCurrent()
    assertEquals(listOf(946), PlayoutAudioTrack.writeSizes)
    assertFalse(playout.isPlaying)
    assertTrue(failures.isEmpty())

    playout.audio(owner, ByteArray(initialBufferFrames * 2 - 946).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    scheduler.runCurrent()
    assertEquals(2, PlayoutAudioTrack.writeSizes.size)
    assertTrue(playout.isPlaying)
    assertTrue(failures.isEmpty())
  }

  @Test
  @Config(sdk = [30], shadows = [PlayoutAudioTrack::class])
  fun legacyCommunicationAudioDonePadsAShortReplyToTheStartThreshold() {
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { failures += it },
      )
    val partialReplyFrames = 4_793
    val partialReply = ByteArray(partialReplyFrames * 2).also { it[0] = 0x7f; it[1] = 0x7f }
    playout.audio(owner, partialReply, statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    val initialBufferFrames = track.bufferSizeInFrames
    assertFalse(playout.isPlaying)

    playout.audioDone(owner, statusOwner)
    scheduler.runCurrent()

    assertEquals(listOf(partialReply.size, initialBufferFrames * 2 - partialReply.size), PlayoutAudioTrack.writeSizes)
    assertTrue(playout.isPlaying)
    assertTrue(failures.isEmpty())
  }

  @Test
  @Config(sdk = [30], shadows = [PlayoutAudioTrack::class])
  fun fullLegacyStartBufferThatNeverAdvancesStillFailsAsAPlaybackStall() {
    PlayoutAudioTrack.presentedFrames = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { failures += it },
      )
    playout.audio(owner, ByteArray(946), statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    playout.audio(owner, ByteArray(track.bufferSizeInFrames * 2 - 946), statusOwner)
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)

    ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    scheduler.advanceTimeBy(2_400)
    scheduler.runCurrent()

    assertEquals(listOf("audio playback stalled"), failures)
    assertFalse(playout.isPlaying)
  }

  @Test
  @Config(sdk = [28], shadows = [PlayoutAudioTrack::class])
  fun cancellingLegacyMediaOutputBeforePrimeCannotStartItLater() {
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        routePolicy = RealtimeAudioRoutePolicy.MediaOutput,
      )
    playout.audio(owner, ByteArray(946), statusOwner)
    scheduler.runCurrent()
    assertFalse(playout.isPlaying)

    playout.clear(owner, acknowledge = false)
    scheduler.advanceTimeBy(1_000)
    scheduler.runCurrent()

    assertFalse(playout.isPlaying)
    assertEquals(AudioTrack.STATE_UNINITIALIZED, checkNotNull(PlayoutAudioTrack.track).state)
  }

  @Test
  fun onlyThePostWriteStateReportsFreshPcm() {
    val freshFlags = mutableListOf<Boolean>()
    val freshRawLevels = mutableListOf<Float>()
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, freshPcm, rawPcmLevel ->
          freshFlags += freshPcm
          rawPcmLevel?.let(freshRawLevels::add)
        },
        onMark = {},
        onFailure = { error(it) },
      )

    playout.audio(owner, ByteArray(11_520 * 2).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    scheduler.runCurrent()

    assertFalse(freshFlags.first())
    assertTrue(freshFlags.any { it })
    assertTrue(freshRawLevels.single() > 0.01f)

    playout.audio(owner, ByteArray(960), statusOwner)
    scheduler.runCurrent()

    assertEquals(0f, freshRawLevels.last())
  }

  @Test
  fun audibleReplyDrainsBeforeAContinuousSilentRelayStreamEnds() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    var audiblePresentations = 0
    var audibleDrains = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        onAudiblePresented = { audiblePresentations++ },
        onAudibleDrained = { audibleDrains++ },
      )
    val audible = ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }
    val renderedSilence = ByteArray(24_000 * 2 * 300 / 1_000)

    playout.audio(owner, audible, statusOwner)
    playout.audio(owner, renderedSilence, statusOwner)
    playout.audio(owner, ByteArray(960), statusOwner)
    playout.audio(owner, ByteArray((11_520 - 7_680) * 2), statusOwner)
    scheduler.runCurrent()

    assertEquals(0, audibleDrains)
    PlayoutAudioTrack.presentedFrames = 7_680
    PlayoutAudioTrack.timestampFrames = 7_680
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertEquals(1, audibleDrains)
    assertEquals(1, audiblePresentations)
    assertTrue("The zero-Pcm relay stream remains active", playout.isPlaying)
  }

  @Test
  fun audibleDrainIsPolledBeforeALongSilentWriteCompletes() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    PlayoutAudioTrack.acceptedChunkBytes = 960
    var drainAtWrite: Int? = null
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        onAudibleDrained = { drainAtWrite = PlayoutAudioTrack.writes },
      )
    val audible = ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }
    val longSilence = ByteArray(960 * 100)
    PlayoutAudioTrack.onWrite = { writes ->
      val presented = presentedFramesAfterStartupReserve(writes)
      PlayoutAudioTrack.presentedFrames = presented
      PlayoutAudioTrack.timestampFrames = presented.toLong()
    }

    playout.audio(owner, audible, statusOwner)
    playout.audio(owner, longSilence, statusOwner)
    scheduler.runCurrent()

    assertTrue(checkNotNull(drainAtWrite) < 101)
    assertTrue(playout.isPlaying)
  }

  @Test
  fun audibleDrainIsPolledWhileContinuousSmallSilentCommandsKeepTheMailboxBusy() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    PlayoutAudioTrack.acceptedChunkBytes = 960
    var drainAtWrite: Int? = null
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        onAudibleDrained = { drainAtWrite = PlayoutAudioTrack.writes },
      )
    PlayoutAudioTrack.onWrite = { writes ->
      val presented = presentedFramesAfterStartupReserve(writes)
      PlayoutAudioTrack.presentedFrames = presented
      PlayoutAudioTrack.timestampFrames = presented.toLong()
    }

    playout.audio(owner, ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    repeat(100) { playout.audio(owner, ByteArray(960), statusOwner) }
    scheduler.runCurrent()

    assertTrue(checkNotNull(drainAtWrite) < 101)
    assertTrue(playout.isPlaying)
  }

  @Test
  fun acceptedAudibleStatePrecedesItsPhysicalPresentationAndDrainWithinOneLongCommand() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    PlayoutAudioTrack.acceptedChunkBytes = 960
    val callbacks = mutableListOf<String>()
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, freshPcm, rawPcmLevel ->
          if (freshPcm && rawPcmLevel != null && rawPcmLevel > RealtimePlayout.AUDIBLE_LEVEL) callbacks += "fresh"
        },
        onMark = {},
        onFailure = { error(it) },
        onAudiblePresented = { callbacks += "presented" },
        onAudibleDrained = { callbacks += "drained" },
      )
    PlayoutAudioTrack.onWrite = { writes ->
      val presented = presentedFramesAfterStartupReserve(writes)
      PlayoutAudioTrack.presentedFrames = presented
      PlayoutAudioTrack.timestampFrames = presented.toLong()
    }
    playout.audio(owner, ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    playout.audio(owner, ByteArray(960 * 40), statusOwner)
    scheduler.runCurrent()
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertEquals(listOf("fresh", "presented", "drained"), callbacks)
  }

  @Test
  @Config(sdk = [30], shadows = [PlayoutAudioTrack::class])
  fun legacyPrimePublishesAcceptedAudibleStateBeforeItsPhysicalPresentationAndDrain() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    PlayoutAudioTrack.acceptedChunkBytes = 960
    val callbacks = mutableListOf<String>()
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, freshPcm, rawPcmLevel ->
          if (freshPcm && rawPcmLevel != null && rawPcmLevel > RealtimePlayout.AUDIBLE_LEVEL) callbacks += "fresh"
        },
        onMark = {},
        onFailure = { error(it) },
        onAudiblePresented = { callbacks += "presented" },
        onAudibleDrained = { callbacks += "drained" },
      )
    PlayoutAudioTrack.onWrite = { writes ->
      val presented = presentedFramesAfterStartupReserve(writes)
      PlayoutAudioTrack.presentedFrames = presented
      PlayoutAudioTrack.timestampFrames = presented.toLong()
    }
    // The real 20 ms audible head is followed by enough real relay silence to fill the reserve
    // and then cross the 300 ms drain interval after physical playback begins.
    playout.audio(owner, ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    playout.audio(owner, ByteArray(960 * 40), statusOwner)
    scheduler.runCurrent()
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertEquals(listOf("fresh", "presented", "drained"), callbacks)
  }

  @Test
  @Config(sdk = [30], shadows = [PlayoutAudioTrack::class])
  fun legacyAudioDonePublishesAQueuedAudibleHeadBeforeItsPhysicalCallbacks() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    PlayoutAudioTrack.acceptedChunkBytes = 960
    val callbacks = mutableListOf<String>()
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, freshPcm, rawPcmLevel ->
          if (freshPcm && rawPcmLevel != null && rawPcmLevel > RealtimePlayout.AUDIBLE_LEVEL) callbacks += "fresh"
        },
        onMark = {},
        onFailure = { error(it) },
        onAudiblePresented = { callbacks += "presented" },
        onAudibleDrained = { callbacks += "drained" },
      )
    PlayoutAudioTrack.onWrite = { writes ->
      val presented = presentedFramesAfterStartupReserve(writes)
      PlayoutAudioTrack.presentedFrames = presented
      PlayoutAudioTrack.timestampFrames = presented.toLong()
    }

    playout.audio(owner, ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    playout.audioDone(owner, statusOwner)
    scheduler.runCurrent()
    PlayoutAudioTrack.presentedFrames = 11_520
    PlayoutAudioTrack.timestampFrames = 11_520
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertEquals(listOf("fresh", "presented", "drained"), callbacks)
  }

  @Test
  @Config(sdk = [30], shadows = [PlayoutAudioTrack::class])
  fun legacyOpenRelayWaitsForRealPcmInsteadOfPaddingBeforeItsPhysicalCallbacks() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    PlayoutAudioTrack.acceptedChunkBytes = 960
    val callbacks = mutableListOf<String>()
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, freshPcm, rawPcmLevel ->
          if (freshPcm && rawPcmLevel != null && rawPcmLevel > RealtimePlayout.AUDIBLE_LEVEL) callbacks += "fresh"
        },
        onMark = {},
        onFailure = { error(it) },
        onAudiblePresented = { callbacks += "presented" },
        onAudibleDrained = { callbacks += "drained" },
      )
    PlayoutAudioTrack.onWrite = { writes ->
      val presented = presentedFramesAfterStartupReserve(writes)
      PlayoutAudioTrack.presentedFrames = presented
      PlayoutAudioTrack.timestampFrames = presented.toLong()
    }

    playout.audio(owner, ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    scheduler.runCurrent()
    ShadowSystemClock.advanceBy(Duration.ofMillis(600))
    scheduler.advanceTimeBy(600)
    scheduler.runCurrent()
    assertTrue(callbacks.isEmpty())
    playout.audio(owner, ByteArray(960 * 40), statusOwner)
    scheduler.runCurrent()
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertEquals(listOf("fresh", "presented", "drained"), callbacks)
  }

  @Test
  fun audiblePresentationUsesThePhysicalLastAudibleFrameBeforeItsQuietDrain() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    var presentations = 0
    var drains = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        onAudiblePresented = { presentations++ },
        onAudibleDrained = { drains++ },
      )
    val audible = ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }
    val renderedSilence = ByteArray(24_000 * 2 * 300 / 1_000)

    playout.audio(owner, audible, statusOwner)
    playout.audio(owner, renderedSilence, statusOwner)
    playout.audio(owner, ByteArray((11_520 - 7_680) * 2), statusOwner)
    scheduler.runCurrent()

    // 960 bytes are exactly 480 PCM frames: presentation is earlier than the 300ms drain.
    PlayoutAudioTrack.presentedFrames = 480
    PlayoutAudioTrack.timestampFrames = 480
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(1, presentations)
    assertEquals(0, drains)

    PlayoutAudioTrack.presentedFrames = 7_680
    PlayoutAudioTrack.timestampFrames = 7_680
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(1, presentations)
    assertEquals(1, drains)
  }

  @Test
  fun futureAudibleFramesReplaceAnEarlierPendingAudibleDrain() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    var audiblePresentations = 0
    var audibleDrains = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        onAudiblePresented = { audiblePresentations++ },
        onAudibleDrained = { audibleDrains++ },
      )
    val audible = ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }
    val renderedSilence = ByteArray(24_000 * 2 * 300 / 1_000)

    playout.audio(owner, audible, statusOwner)
    playout.audio(owner, renderedSilence, statusOwner)
    playout.audio(owner, audible, statusOwner)
    playout.audio(owner, renderedSilence, statusOwner)
    playout.audio(owner, ByteArray(960), statusOwner)
    playout.audio(owner, ByteArray((11_520 - 8_160) * 2), statusOwner)
    scheduler.runCurrent()

    PlayoutAudioTrack.presentedFrames = 7_680
    PlayoutAudioTrack.timestampFrames = 7_680
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(0, audibleDrains)

    PlayoutAudioTrack.presentedFrames = 15_360
    PlayoutAudioTrack.timestampFrames = 15_360
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(1, audibleDrains)
    assertEquals(1, audiblePresentations)
  }

  @Test
  fun silentOnlyRelayStreamNeverSignalsAnAudibleDrain() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    var audibleDrains = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        onAudibleDrained = { audibleDrains++ },
      )

    playout.audio(owner, ByteArray(16_320), statusOwner)
    scheduler.runCurrent()
    PlayoutAudioTrack.presentedFrames = 8_160
    PlayoutAudioTrack.timestampFrames = 8_160
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertEquals(0, audibleDrains)
  }

  @Test
  fun futurePresentationTimestampDelaysTheAudibleDrain() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    var audibleDrains = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { error(it) },
        onAudibleDrained = { audibleDrains++ },
      )
    val audible = ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }
    val renderedSilence = ByteArray(24_000 * 2 * 300 / 1_000)

    playout.audio(owner, audible, statusOwner)
    playout.audio(owner, renderedSilence, statusOwner)
    playout.audio(owner, ByteArray(960), statusOwner)
    playout.audio(owner, ByteArray((11_520 - 8_160) * 2), statusOwner)
    scheduler.runCurrent()
    assertEquals(0, audibleDrains)

    PlayoutAudioTrack.presentedFrames = 7_680
    PlayoutAudioTrack.timestampFrames = 7_680
    PlayoutAudioTrack.timestampNanos = System.nanoTime() + 10_000_000_000L
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(0, audibleDrains)
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(1, audibleDrains)
  }

  @Test
  fun relayFramedBacklogIsBoundedByBytesNotEntries() {
    // Gateway relay output: 20 ms PCM frames at 24 kHz, each followed by a mark in the worst case.
    val frame = ByteArray(960)
    val frames = (RealtimePlayout.MAX_QUEUED_BYTES / frame.size).toInt()
    repeat(frames) { index ->
      assertEquals(null, playout.audio(session, frame, statusOwner))
      assertEquals(null, playout.mark(session, "mark-$index"))
    }
    val tail = ByteArray((RealtimePlayout.MAX_QUEUED_BYTES % frame.size).toInt())
    assertEquals(null, playout.audio(session, tail, statusOwner))
    assertEquals(null, playout.mark(session, "tail"))
    // Final transcript and audioDone refresh status without adding PCM.
    repeat(2) { assertEquals(null, playout.refreshState(session, statusOwner)) }
    assertTrue(failures.isEmpty())
    assertTrue(session.active)

    val overflow = checkNotNull(playout.audio(session, frame, statusOwner))
    overflow()
    assertEquals(listOf("session/audio playback queue overflow"), failures)
    assertFalse(session.active)
  }

  @Test
  fun pcmByteBudgetRemainsIndependentOfEntryCapacity() {
    assertEquals(null, playout.audio(session, ByteArray(RealtimePlayout.MAX_QUEUED_BYTES.toInt()), statusOwner))
    val overflow = checkNotNull(playout.audio(session, ByteArray(2), statusOwner))
    overflow()
    assertEquals(listOf("session/audio playback queue overflow"), failures)
    assertFalse(session.active)
  }

  @Test
  fun dequeuedRefreshesDoNotIncreaseMediaAllowance() {
    repeat(2) { assertEquals(null, playout.refreshState(session, statusOwner)) }
    scheduler.runCurrent()
    repeat(RealtimePlayout.MAX_QUEUED_MEDIA) { assertEquals(null, playout.mark(session, "mark-$it")) }
    val overflow = checkNotNull(playout.mark(session, "over-budget"))
    overflow()
    assertEquals(listOf("session/audio playback queue overflow"), failures)
    assertFalse(session.active)
  }

  @Test
  fun refreshOverflowPreservesClearHeadroomForAFullMediaBacklog() {
    playout.audio(session, ByteArray(100), statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    repeat(2) {
      repeat(24) { assertEquals(null, playout.refreshState(session, statusOwner)) }
      scheduler.runCurrent()
    }
    repeat(RealtimePlayout.MAX_QUEUED_MEDIA) { assertEquals(null, playout.mark(session, "mark-$it")) }
    var overflow: (() -> Unit)? = null
    repeat(64) {
      if (overflow == null) overflow = playout.refreshState(session, statusOwner)
    }
    checkNotNull(overflow).invoke()
    val cleared = playout.clear(session, acknowledge = false)
    scheduler.runCurrent()
    assertEquals(listOf("session/audio playback queue overflow"), failures)
    assertTrue(cleared.isCompleted)
    assertFalse(cleared.isCancelled)
    assertEquals(AudioTrack.STATE_UNINITIALIZED, track.state)
    assertFalse(playout.isPlaying)
  }

  @Test
  fun audioDoneUsesReservedControlHeadroomWhenMediaBacklogIsFull() {
    playout.audio(session, ByteArray(100), statusOwner)
    scheduler.runCurrent()
    repeat(RealtimePlayout.MAX_QUEUED_MEDIA) { assertEquals(null, playout.mark(session, "mark-$it")) }

    assertEquals(null, playout.audioDone(session, statusOwner))
    assertTrue(session.active)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun zeroWriteYieldsSoClearCanDiscardTheOldAudio() {
    PlayoutAudioTrack.result = 0
    playout.audio(session, ByteArray(4_800), statusOwner)
    scheduler.runCurrent()
    assertEquals(1, PlayoutAudioTrack.writes)
    val cleared = playout.clear(session)
    scheduler.advanceTimeBy(10)
    scheduler.runCurrent()
    assertTrue(cleared.isCompleted)
    assertEquals(1, PlayoutAudioTrack.writes)
    assertTrue(failures.isEmpty())
    assertFalse(playout.isPlaying)
  }

  @Test
  fun negativeDeviceWriteFailsAndRetiresTheTrack() {
    PlayoutAudioTrack.result = AudioTrack.ERROR_DEAD_OBJECT
    playout.audio(session, ByteArray(4_800), statusOwner)
    scheduler.runCurrent()
    assertEquals(1, failures.size)
    assertTrue(failures.single().contains("device error"))
    assertEquals(AudioTrack.STATE_UNINITIALIZED, checkNotNull(PlayoutAudioTrack.track).state)
    assertFalse(playout.isPlaying)
  }

  @Test
  fun aDeviceThatNeverAcceptsAudioFailsWithinTheBoundedBudget() {
    PlayoutAudioTrack.result = 0
    playout.audio(session, ByteArray(23_040), statusOwner)
    scheduler.advanceTimeBy(2_400)
    scheduler.runCurrent()
    assertEquals(listOf("session/audio playback stalled"), failures)
    assertFalse(playout.isPlaying)
  }

  @Test
  fun marksWaitForPresentedFramesAndClearReleasesPendingMarks() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    playout.audio(session, ByteArray(23_040), statusOwner)
    playout.mark(session, "first")
    scheduler.runCurrent()
    assertTrue(acknowledgements.isEmpty())
    PlayoutAudioTrack.presentedFrames = 11_520
    PlayoutAudioTrack.timestampFrames = 11_520
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(listOf("session/first"), acknowledgements)
    playout.audio(session, ByteArray(23_040), statusOwner)
    playout.mark(session, "second")
    scheduler.runCurrent()
    playout.clear(session)
    scheduler.runCurrent()
    assertEquals(listOf("session/first", "session/second"), acknowledgements)
  }

  @Test
  fun interruptedQueuedMarkWaitsForPhysicalTrackRelease() {
    PlayoutAudioTrack.presentedFrames = 0
    var releasedAtAcknowledgement = false
    val owner =
      RealtimePlayout.Session({ _, _, _, _, _ -> }, {
        releasedAtAcknowledgement = PlayoutAudioTrack.track?.state == AudioTrack.STATE_UNINITIALIZED
      }, { error(it) })
    playout.audio(owner, ByteArray(100), statusOwner)
    scheduler.runCurrent()
    playout.mark(owner, "interrupted")
    playout.clear(owner)
    scheduler.runCurrent()
    assertTrue(releasedAtAcknowledgement)
  }

  @Test
  fun aShortFinalReplyStartsWithoutFillingTheDeviceBuffer() {
    PlayoutAudioTrack.timestampFrames = 50
    playout.audio(session, ByteArray(100), statusOwner)
    playout.mark(session, "short-reply")
    playout.audioDone(session, statusOwner)
    scheduler.runCurrent()
    assertEquals(listOf("session/short-reply"), acknowledgements)
    ShadowSystemClock.advanceBy(Duration.ofMillis(20))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertFalse(playout.isPlaying)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun anOpenRelayThatPhysicallyDrainsPausesWithoutReleasingPlaybackOwnershipThenRefills() {
    PlayoutAudioTrack.presentedFrames = 0
    val states = mutableListOf<Boolean>()
    val owner =
      RealtimePlayout.Session(
        onState = { playing, _, _, _, _ -> states += playing },
        onMark = { acknowledgements += "owner/$it" },
        onFailure = { failures += it },
      )
    playout.audio(owner, ByteArray(11_520 * 2), statusOwner)
    playout.mark(owner, "before-rebuffer")
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)

    PlayoutAudioTrack.presentedFrames = 11_520
    PlayoutAudioTrack.timestampFrames = 11_520
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    ShadowSystemClock.advanceBy(Duration.ofMillis(20))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertFalse("An open relay must pause at a real drain instead of declaring the reply complete", playout.isPlaying)
    assertEquals(listOf("owner/before-rebuffer"), acknowledgements)
    assertFalse("A starvation pause must not release the playback/capture owner", states.contains(false))
    val rebuffer =
      ShadowLog.getLogsForTag("OpenClawPlayout")
        .lastOrNull { it.msg.startsWith("rebuffer paused ") }
        ?.msg
        ?: error("Expected one rebuffer diagnostic")
    // Both clocks advance explicitly: the diagnostic uses Android's monotonic time, while
    // the coroutine scheduler only triggers the physical-drain poll.
    fun loggedAge(name: String): Long =
      Regex("$name=(-?\\d+)").find(rebuffer)?.groupValues?.get(1)?.toLong()
        ?: error("Missing $name in rebuffer diagnostic: $rebuffer")
    assertEquals(20L, loggedAge("sinceLastTrackWriteMs"))
    assertTrue(rebuffer.contains("lastTrackWriteAudible=false"))
    assertEquals(-1L, loggedAge("sinceLastAudibleTrackWriteMs"))
    assertEquals(20L, loggedAge("sinceLastTalkEventMs"))

    playout.audio(owner, ByteArray(11_520 * 2), statusOwner)
    scheduler.runCurrent()

    assertTrue("The next bounded reserve resumes the same playback owner", playout.isPlaying)
    assertFalse(states.contains(false))
    assertTrue(failures.isEmpty())
  }

  @Test
  fun aNewNativeUnderrunRebuffersAnOpenRelayEvenWhenTheDurationFloorStillHasPendingFrames() {
    PlayoutAudioTrack.presentedFrames = 0
    val states = mutableListOf<Boolean>()
    val owner =
      RealtimePlayout.Session(
        onState = { playing, _, _, _, _ -> states += playing },
        onMark = {},
        onFailure = { failures += it },
      )
    playout.audio(owner, ByteArray(11_520 * 2), statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    assertTrue(playout.isPlaying)

    // A fresh 20 ms relay frame arrives while native playback has underflowed. Its physical head
    // remains behind writtenFrames and the future timestamp keeps the conservative duration floor.
    PlayoutAudioTrack.presentedFrames = 11_000
    PlayoutAudioTrack.timestampFrames = 11_000
    PlayoutAudioTrack.timestampNanos = System.nanoTime() + 1_000_000_000L
    playout.audio(owner, ByteArray(960), statusOwner)
    scheduler.runCurrent()
    PlayoutAudioTrack.underrunCount = 1
    assertEquals(1, track.underrunCount)
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertFalse(playout.isPlaying)
    assertEquals(AudioTrack.PLAYSTATE_PAUSED, track.playState)
    assertFalse(states.contains(false))

    // The same counter value is only observed once; after a new reserve it must not immediately
    // retrigger another pause.
    playout.audio(owner, ByteArray((11_520 - (12_000 - 11_000)) * 2), statusOwner)
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun aNewOpenTurnAfterAudioDoneDoesNotInheritTheFormerTurnTerminalDrain() {
    PlayoutAudioTrack.presentedFrames = 0
    val states = mutableListOf<Boolean>()
    val owner =
      RealtimePlayout.Session(
        onState = { playing, _, _, _, _ -> states += playing },
        onMark = {},
        onFailure = { failures += it },
      )

    // Turn A is terminal and may release its state normally.
    playout.audio(owner, ByteArray(960), statusOwner)
    playout.audioDone(owner, statusOwner)
    scheduler.runCurrent()
    PlayoutAudioTrack.presentedFrames = 480
    PlayoutAudioTrack.timestampFrames = 480
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    playout.refreshState(owner, statusOwner)
    scheduler.runCurrent()
    assertTrue(states.contains(false))

    // The first accepted PCM after AudioDone begins turn B in the same Session.
    playout.audio(owner, ByteArray(11_520 * 2), statusOwner)
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)
    val falseStatesBeforeBDrain = states.count { !it }

    PlayoutAudioTrack.presentedFrames = 12_000
    PlayoutAudioTrack.timestampFrames = 12_000
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertFalse(playout.isPlaying)
    assertEquals(falseStatesBeforeBDrain, states.count { !it })
    assertTrue(failures.isEmpty())
  }

  @Test
  fun aTerminalTurnPausesTheTrackSoTheNextFiniteChunkedTurnCanRebuildItsReserve() {
    PlayoutAudioTrack.presentedFrames = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { failures += it },
      )

    playout.audio(owner, ByteArray(960), statusOwner)
    playout.audioDone(owner, statusOwner)
    scheduler.runCurrent()
    val track = checkNotNull(PlayoutAudioTrack.track)
    PlayoutAudioTrack.presentedFrames = 480
    PlayoutAudioTrack.timestampFrames = 480
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    playout.refreshState(owner, statusOwner)
    scheduler.runCurrent()
    assertFalse(playout.isPlaying)
    assertEquals(AudioTrack.PLAYSTATE_PAUSED, track.playState)

    repeat(23) { playout.audio(owner, ByteArray(960), statusOwner) }
    scheduler.runCurrent()
    assertFalse("Twenty-three 20 ms frames must remain queued below the 480 ms reserve", playout.isPlaying)
    assertEquals(AudioTrack.PLAYSTATE_PAUSED, track.playState)

    playout.audio(owner, ByteArray(960), statusOwner)
    playout.audioDone(owner, statusOwner)
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)
    assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun anOpenRelayRenderedQuietTailSignalsAudibleDrainWithoutTerminalAudioDone() {
    PlayoutAudioTrack.presentedFrames = 0
    var audibleDrains = 0
    val owner =
      RealtimePlayout.Session(
        onState = { _, _, _, _, _ -> },
        onMark = {},
        onFailure = { failures += it },
        onAudibleDrained = { audibleDrains++ },
      )
    // The first 20 ms is audible; the following 460 ms is real relay silence. It fills the
    // reserve. Continuous RTC uses that physically rendered quiet tail as its handoff signal.
    playout.audio(owner, ByteArray(960).also { it[0] = 0x7f; it[1] = 0x7f }, statusOwner)
    playout.audio(owner, ByteArray((11_520 - 480) * 2), statusOwner)
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)

    PlayoutAudioTrack.presentedFrames = 11_520
    PlayoutAudioTrack.timestampFrames = 11_520
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()

    assertFalse(playout.isPlaying)
    assertEquals(1, audibleDrains)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun consumedFramesWaitForTheirFuturePresentationTimestamp() {
    PlayoutAudioTrack.presentedFrames = 0
    PlayoutAudioTrack.timestampFrames = 0
    PlayoutAudioTrack.timestampNanos = System.nanoTime() + 10_000_000_000L
    playout.audio(session, ByteArray(4_800), statusOwner)
    playout.mark(session, "reply")
    playout.audioDone(session, statusOwner)
    scheduler.runCurrent()
    assertTrue("Consumed frames are still committed to future presentation", playout.isPlaying)
    assertTrue(acknowledgements.isEmpty())
    PlayoutAudioTrack.timestampFrames = null
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertTrue("Temporary timestamp loss cannot forget known future presentation", acknowledgements.isEmpty())
    PlayoutAudioTrack.presentedFrames = 2_400
    PlayoutAudioTrack.timestampFrames = 2_400L
    scheduler.advanceTimeBy(2_000)
    scheduler.runCurrent()
    assertTrue("Known future presentation is not a stalled device", failures.isEmpty())
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(listOf("session/reply"), acknowledgements)
    assertFalse(playout.isPlaying)
  }

  @Test
  fun unavailableTimestampPreservesTheNominalAudioDurationGate() {
    playout.audio(session, ByteArray(24_000), statusOwner)
    playout.mark(session, "reply")
    scheduler.runCurrent()
    assertTrue(acknowledgements.isEmpty())
    assertTrue("Approximate head progress must not remove the existing duration floor", playout.isPlaying)
    ShadowSystemClock.advanceBy(Duration.ofMillis(499))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertTrue(playout.isPlaying)
    ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(listOf("session/reply"), acknowledgements)
    assertFalse(playout.isPlaying)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun drainedAudioDoesNotFailWhenTheLastTimestampRemainsBehindTheHead() {
    PlayoutAudioTrack.timestampFrames = 0
    playout.audio(session, ByteArray(4_800), statusOwner)
    playout.mark(session, "reply")
    playout.audioDone(session, statusOwner)
    scheduler.runCurrent()
    PlayoutAudioTrack.timestampFrames = 2_160
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    // AudioTimestamp uses the real monotonic clock, not the coroutine scheduler.
    Thread.sleep(20)
    ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    scheduler.advanceTimeBy(2_000)
    scheduler.runCurrent()
    assertTrue("A final server timestamp can remain behind consumed frames", failures.isEmpty())
    assertEquals(listOf("session/reply"), acknowledgements)
    assertFalse(playout.isPlaying)
  }

  @Test
  fun aTimestampFromBeforeAnIdleGapCannotFinishNewAudio() {
    PlayoutAudioTrack.timestampFrames = 0
    playout.audio(session, ByteArray(4_800), statusOwner)
    playout.mark(session, "first")
    playout.audioDone(session, statusOwner)
    scheduler.runCurrent()
    // Turn A has an actual physical completion anchor. Retain it only as a stale internal clock
    // value while turn B is queued; it must not present B's newly accepted frames.
    PlayoutAudioTrack.presentedFrames = 2_400
    PlayoutAudioTrack.timestampFrames = 2_400
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertFalse(playout.isPlaying)
    PlayoutAudioTrack.timestampFrames = null
    ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    playout.audio(session, ByteArray(4_800), statusOwner)
    playout.mark(session, "second")
    playout.audioDone(session, statusOwner)
    scheduler.runCurrent()
    assertTrue("An old clock anchor cannot present newly submitted audio", playout.isPlaying)
    assertEquals(listOf("session/first"), acknowledgements)
    PlayoutAudioTrack.presentedFrames = 4_800
    PlayoutAudioTrack.timestampFrames = 4_800
    PlayoutAudioTrack.timestampNanos = System.nanoTime()
    ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertFalse(playout.isPlaying)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun pacedWritesDoNotCountElapsedPlaybackTwice() {
    PlayoutAudioTrack.acceptedChunkBytes = 4_800
    playout.audio(session, ByteArray(48_000), statusOwner)
    scheduler.runCurrent()
    assertEquals(10, PlayoutAudioTrack.writes)
    assertTrue(playout.isPlaying)
    // The 480 ms reserve begins after the fifth paced packet, so a one-second reply finishes
    // near 1.48 s, without incurring a second full one-second duration floor.
    ShadowSystemClock.advanceBy(Duration.ofMillis(580))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertFalse("A one-second reply must not suppress input for another whole second", playout.isPlaying)
    assertTrue(failures.isEmpty())
  }

  @Test
  fun unavailableTimestampKeepsFramesAfterAnIntermediateFutureAnchorPending() {
    playout.audio(session, ByteArray(2_400), statusOwner)
    playout.mark(session, "middle")
    playout.audio(session, ByteArray(2_400), statusOwner)
    playout.mark(session, "reply")
    playout.audioDone(session, statusOwner)
    scheduler.runCurrent()
    PlayoutAudioTrack.timestampFrames = 1_200
    PlayoutAudioTrack.timestampNanos = System.nanoTime() + 200_000_000L
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertTrue(acknowledgements.isEmpty())
    PlayoutAudioTrack.timestampFrames = null
    // Pass the actual monotonic anchor time, but not the additional 50 ms of queued PCM.
    Thread.sleep(220)
    ShadowSystemClock.advanceBy(Duration.ofMillis(220))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals("Only the intermediate mark has presented", listOf("session/middle"), acknowledgements)
    assertTrue(playout.isPlaying)
    ShadowSystemClock.advanceBy(Duration.ofMillis(50))
    scheduler.advanceTimeBy(20)
    scheduler.runCurrent()
    assertEquals(listOf("session/middle", "session/reply"), acknowledgements)
    assertFalse(playout.isPlaying)
  }

  @Test
  fun terminalClearDiscardsQueuedMarksButAnInterruptionAcknowledgesThem() {
    playout.mark(session, "discarded")
    playout.clear(session, acknowledge = false)
    val replacement = session("replacement")
    playout.mark(replacement, "interrupted")
    playout.clear(replacement)
    scheduler.runCurrent()
    assertEquals(listOf("replacement/interrupted"), acknowledgements)
  }

  @Test
  fun oldClearCannotFlushTheReplacementTrack() {
    val replacement = session("replacement")
    playout.audio(replacement, ByteArray(100), statusOwner)
    playout.audioDone(replacement, statusOwner)
    playout.clear(session, acknowledge = false)
    scheduler.runCurrent()
    assertEquals(AudioTrack.STATE_INITIALIZED, checkNotNull(PlayoutAudioTrack.track).state)
    assertTrue(playout.isPlaying)
  }

  @Test
  fun clearBeforeActorStartsIsCancelledWhenItsScopeIsCancelled() {
    val clear = playout.clear(session)
    job.cancel()
    scheduler.runCurrent()
    assertTrue(clear.isCancelled)
  }

  @Test
  fun cancellationStopsAlreadyQueuedAcknowledgements() {
    val acknowledged = mutableListOf<String>()
    val owner =
      RealtimePlayout.Session({ _, _, _, _, _ -> }, { name ->
        acknowledged += name
        job.cancel()
      }, { error(it) })
    playout.mark(owner, "first")
    playout.mark(owner, "second")
    scheduler.runCurrent()
    assertEquals(listOf("first"), acknowledged)
  }

  @Test
  fun cancellingAfterClearDeliveryStillCompletesItsWaiter() {
    playout.audio(session, ByteArray(100), statusOwner)
    scheduler.runCurrent()
    val clear = playout.clear(session)
    job.cancel()
    scheduler.runCurrent()
    assertTrue(clear.isCancelled)
  }

  @Test
  fun acceptedAudioThatNeverPresentsFailsInsteadOfHangingMarks() {
    PlayoutAudioTrack.presentedFrames = 0
    playout.audio(session, ByteArray(100), statusOwner)
    playout.mark(session, "short-reply")
    playout.audioDone(session, statusOwner)
    scheduler.runCurrent()
    ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
    scheduler.advanceTimeBy(2_400)
    scheduler.runCurrent()
    assertEquals(listOf("session/audio playback stalled"), failures)
    assertTrue(acknowledgements.isEmpty())
    assertFalse(playout.isPlaying)
  }
}

@Implements(AudioTrack::class)
class PlayoutAudioTrack : ShadowAudioTrack() {
  @RealObject private lateinit var realTrack: AudioTrack
  private var startThreshold: Int? = null
  private var effectiveBufferFrames: Int? = null

  companion object {
    var result: Int? = null
    var acceptedChunkBytes: Int? = null
    var presentedFrames: Int? = null
    var timestampFrames: Long? = null
    var timestampNanos: Long? = null
    var underrunCount = 0
    var onWrite: ((Int) -> Unit)? = null
    var track: AudioTrack? = null
    var writes = 0
    val writeSizes = mutableListOf<Int>()

    fun reset() {
      result = null
      acceptedChunkBytes = null
      presentedFrames = null
      timestampFrames = null
      timestampNanos = null
      underrunCount = 0
      onWrite = null
      track = null
      writes = 0
      writeSizes.clear()
    }
  }

  @Implementation(minSdk = 23)
  override fun native_write_byte(
    audioData: ByteArray,
    offsetInBytes: Int,
    sizeInBytes: Int,
    format: Int,
    isBlocking: Boolean,
  ): Int {
    assertFalse("The owner must not block on AudioTrack writes", isBlocking)
    track = realTrack
    writeSizes += sizeInBytes
    if (acceptedChunkBytes != null && writes > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    writes += 1
    onWrite?.invoke(writes)
    return result ?: super.native_write_byte(audioData, offsetInBytes, minOf(sizeInBytes, acceptedChunkBytes ?: sizeInBytes), format, isBlocking)
  }

  @Implementation(minSdk = 31)
  fun native_setStartThresholdInFrames(frames: Int): Int {
    startThreshold = frames
    return frames
  }

  @Implementation
  fun native_set_buffer_size_frames(frames: Int): Int {
    effectiveBufferFrames = frames
    return frames
  }

  @Implementation
  override fun native_get_buffer_size_frames(): Int =
    effectiveBufferFrames ?: super.native_get_buffer_size_frames()

  @Implementation
  fun getTimestamp(timestamp: AudioTimestamp): Boolean {
    timestamp.framePosition = timestampFrames ?: return false
    timestamp.nanoTime = timestampNanos ?: System.nanoTime()
    return true
  }

  @Implementation(minSdk = 24)
  fun native_get_underrun_count(): Int = underrunCount

  @Implementation
  override fun getPlaybackHeadPosition(): Int =
    presentedFrames ?: if (realTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
      0
    } else {
      super.getPlaybackHeadPosition().let {
        if (it >= (startThreshold ?: realTrack.bufferSizeInFrames)) it else 0
      }
    }
}
