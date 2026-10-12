package ai.openclaw.app.voice

import ai.openclaw.app.NodeRuntime
import ai.openclaw.app.NodeRuntimeMode
import ai.openclaw.app.SecurePrefs
import ai.openclaw.app.VoiceCaptureMode
import ai.openclaw.app.closeNodeRuntimeTestFixture
import ai.openclaw.app.gateway.DeviceAuthStore
import ai.openclaw.app.gateway.GatewayClientInfo
import ai.openclaw.app.gateway.GatewayConnectOptions
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.testDeviceIdentityStore
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.notifyNativeLocaleChanged
import ai.openclaw.app.i18n.verbatimText
import android.Manifest
import android.content.ComponentName
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PlayoutAudioTrack::class])
class TalkModeManagerTest {
  private companion object {
    const val REALTIME_AUDIBLE_PCM_BYTES = 24_000 * 2 * 480 / 1_000
    const val REALTIME_AUDIBLE_PCM_FRAMES = 24_000L * 480 / 1_000
  }

  @After
  fun resetPlayoutAudioTrack() {
    PlayoutAudioTrack.reset()
  }

  @Test
  fun phoneRealtimeRetriesWithoutLanguageWhenOlderGatewayRejectsCreateParams() =
    runTest {
      val requestedLanguages = mutableListOf<String?>()

      val payload =
        requestPhoneRealtimeSessionWithLanguageFallback("de") { language ->
          requestedLanguages += language
          if (requestedLanguages.size == 1) {
            throw GatewayRequestRejected(
              GatewaySession.ErrorShape(
                code = "INVALID_REQUEST",
                message = "invalid talk.session.create params at root",
              ),
            )
          }
          """{"relaySessionId":"relay-1"}"""
        }

      assertEquals("""{"relaySessionId":"relay-1"}""", payload)
      assertEquals(listOf("de", null), requestedLanguages)
    }

  @Test
  fun phoneRealtimeDoesNotRetryUnrelatedGatewayErrors() =
    runTest {
      var attempts = 0

      val error =
        runCatching {
          requestPhoneRealtimeSessionWithLanguageFallback("de") {
            attempts += 1
            throw GatewayRequestRejected(
              GatewaySession.ErrorShape(
                code = "INVALID_REQUEST",
                message = "invalid talk.session.appendAudio params",
              ),
            )
          }
        }.exceptionOrNull()

      assertTrue(error is GatewayRequestRejected)
      assertEquals(1, attempts)
    }

  @Test
  fun wakeSeedIsResampledAndAppendedBeforeQueuedStartupPcm() =
    runBlocking {
      val appended = ConcurrentLinkedQueue<ByteArray>()
      val source = VoiceWakeAudio(byteArrayOf(0, 0, 0x10, 0, 0x20, 0, 0x30, 0), sampleRateHz = 16_000)
      withStartedTalk(
        wakeSeed = source,
        interceptRequest = { request, _ ->
          if (request.getValue("method").jsonPrimitive.content == "talk.session.appendAudio") {
            appended += Base64.decode(request.getValue("params").jsonObject.getValue("audioBase64").jsonPrimitive.content, Base64.NO_WRAP)
          }
          false
        },
      ) { proof ->
        // The relay owns seed ordering, while the recorder is allowed to open
        // immediately and buffer bounded startup PCM behind that seed.
        assertTrue(proof.drainNextCaptureTask())
        withTimeout(5_000) {
          while (appended.isEmpty()) withContext(Dispatchers.Default) { delay(10) }
        }

        assertEquals(1, appended.size)
        assertArrayEquals(proof.manager.resampleWakeSeedToRealtimePcm(source), appended.single())
      }
    }

  @Test
  fun wakeSeedAppendsWhileAcknowledgementGateDefersCommunicationInput() =
    runBlocking {
      val appended = ConcurrentLinkedQueue<ByteArray>()
      val gate = CompletableDeferred<Unit>()
      val source = VoiceWakeAudio(byteArrayOf(0, 0, 0x10, 0, 0x20, 0, 0x30, 0), sampleRateHz = 16_000)
      withStartedTalk(
        wakeSeed = source,
        captureStartGate = gate,
        interceptRequest = { request, _ ->
          if (request.getValue("method").jsonPrimitive.content == "talk.session.appendAudio") {
            appended += Base64.decode(request.getValue("params").jsonObject.getValue("audioBase64").jsonPrimitive.content, Base64.NO_WRAP)
          }
          false
        },
      ) { proof ->
        val audio = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))

        // The append coroutine is dispatched before capture. It may send the
        // recognizer-owned seed while the acknowledgement still owns focus.
        assertTrue(proof.drainNextCaptureTask())
        assertTrue(proof.drainNextCaptureTask())
        withTimeout(5_000) {
          while (appended.isEmpty()) withContext(Dispatchers.Default) { delay(10) }
        }

        assertArrayEquals(proof.manager.resampleWakeSeedToRealtimePcm(source), appended.single())
        assertNull(readPrivateField(proof.manager, "realtimeAudioInput"))
        assertNull("Communication capture must not request focus during the acknowledgement", audio.lastAudioFocusRequest)

        // Cancelling the acknowledgement is terminal for this handoff. The
        // suspended capture task must retire without constructing AudioRecord.
        gate.cancel()
        proof.drainCancelledCapture()
        assertNull(readPrivateField(proof.manager, "realtimeAudioInput"))
        assertNull("A cancelled acknowledgement must not acquire communication focus", audio.lastAudioFocusRequest)
      }
    }

  @Test
  fun wakeSeedIngressStopsAtThreeSecondsThenResumesInOrderAfterAnAcknowledgement() =
    runBlocking {
      val gate = CompletableDeferred<Unit>()
      val sentFrames = ConcurrentLinkedQueue<ByteArray>()
      val heldRequests = ConcurrentLinkedQueue<Pair<String, WebSocket>>()
      val frameBytes = 4_800
      val source =
        VoiceWakeAudio(
          pcm16 = ByteArray(frameBytes * 40) { index -> (index / frameBytes).toByte() },
          sampleRateHz = 24_000,
        )
      withStartedTalk(
        wakeSeed = source,
        captureStartGate = gate,
        interceptRequest = { request, socket ->
          if (request.getValue("method").jsonPrimitive.content != "talk.session.appendAudio") return@withStartedTalk false
          sentFrames +=
            Base64.decode(
              request.getValue("params").jsonObject.getValue("audioBase64").jsonPrimitive.content,
              Base64.NO_WRAP,
            )
          heldRequests += request.getValue("id").jsonPrimitive.content to socket
          true
        },
      ) { proof ->
        // Append owns the recognizer's 4s seed. Capture remains behind the
        // acknowledgement gate, so this exercises only the real relay ingress.
        assertTrue(proof.drainNextCaptureTask())
        assertTrue(proof.drainNextCaptureTask())
        withTimeout(5_000) {
          while (sentFrames.size < 30) {
            // Robolectric's elapsedRealtime is static unless the fixture advances it.
            // The production pacer deliberately uses that monotonic source clock.
            ShadowSystemClock.advanceBy(Duration.ofMillis(10))
            proof.drainNextCaptureTask()
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        assertEquals(30, sentFrames.size)
        sentFrames.forEachIndexed { index, frame ->
          assertArrayEquals(source.pcm16.copyOfRange(index * frameBytes, (index + 1) * frameBytes), frame)
        }

        // The next ordered seed frame awaits capacity; it neither drops nor
        // waits for the normal 8s per-request timeout.
        withContext(Dispatchers.Default) { delay(150) }
        assertEquals(30, sentFrames.size)

        val (ackId, socket) = checkNotNull(heldRequests.poll())
        socket.send("""{"type":"res","id":"$ackId","ok":true,"payload":{}}""")
        withTimeout(5_000) {
          while (sentFrames.size < 31) {
            proof.drainNextCaptureTask()
            ShadowSystemClock.advanceBy(Duration.ofMillis(10))
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        assertArrayEquals(source.pcm16.copyOfRange(30 * frameBytes, 31 * frameBytes), sentFrames.elementAt(30))

        // Retiring this relay cancels a seed still waiting for capacity. Later
        // acknowledgements from the old socket cannot enqueue a successor.
        proof.manager.stopAllCapture()
        proof.drainCancelledCapture()
        while (true) {
          val held = heldRequests.poll() ?: break
          val (id, oldSocket) = held
          oldSocket.send("""{"type":"res","id":"$id","ok":true,"payload":{}}""")
        }
        withContext(Dispatchers.Default) { delay(150) }
        proof.drainCancelledCapture()
        assertEquals(31, sentFrames.size)
      }
    }

  @Test
  fun startupPcmQueueDrainsEveryAcceptedFrameBeforeLiveCapture() {
    val queue = RealtimeStartupPcmQueue<ByteArray>(maxFrames = 2)
    val first = byteArrayOf(1)
    val second = byteArrayOf(2)

    assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(first))
    assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(second))

    queue.beginDrain()

    assertArrayEquals(first, checkNotNull(queue.takeForDrain()))
    assertArrayEquals(second, checkNotNull(queue.takeForDrain()))
    assertNull(queue.takeForDrain())
    assertEquals(RealtimeStartupPcmOffer.Live, queue.offer(byteArrayOf(3)))
  }

  @Test
  fun startupPcmQueueRejectsOverflowWithoutDroppingEarlierFrames() {
    val queue = RealtimeStartupPcmQueue<ByteArray>(maxFrames = 1)
    val retained = byteArrayOf(1)

    assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(retained))
    assertEquals(RealtimeStartupPcmOffer.Overflow, queue.offer(byteArrayOf(2)))

    queue.beginDrain()
    assertArrayEquals(retained, checkNotNull(queue.takeForDrain()))
    assertNull(queue.takeForDrain())
  }

  @Test
  fun startupPcmQueueRetainsTheFullProviderReadyAndWakeSeedWindowInOrder() {
    // A provider may take 15s to become ready while a retained 12s wake seed is
    // paced first. That complete 27s source must remain ahead of live capture.
    val queue = RealtimeStartupPcmQueue<Int>(maxFrames = 300)
    repeat(270) { frame ->
      assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(frame))
    }

    queue.beginDrain()
    repeat(270) { frame -> assertEquals(frame, queue.takeForDrain()) }
    assertNull(queue.takeForDrain())
  }

  @Test
  fun startupPcmQueueDrainsRecorderOwnedFramesAddedWhileTheSeedIsPacing() {
    val queue = RealtimeStartupPcmQueue<Int>(maxFrames = 4)
    assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(1))
    queue.beginDrain()
    assertEquals(1, queue.takeForDrain())

    // The recorder can continue while seed PCM is paced. Its frames remain ordered
    // until quiet detach closes the producer; they are never reclassified as fresh live PCM.
    assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(2))
    assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(3))
    assertEquals(2, queue.takeForDrain())
    assertEquals(3, queue.takeForDrain())
    assertNull(queue.takeForDrain())
  }

  @Test
  fun cancelledStartupPcmQueueCannotReleaseFramesIntoANewRelay() {
    val queue = RealtimeStartupPcmQueue<ByteArray>(maxFrames = 1)
    assertEquals(RealtimeStartupPcmOffer.Buffered, queue.offer(byteArrayOf(1)))

    queue.cancel()

    assertEquals(RealtimeStartupPcmOffer.Cancelled, queue.offer(byteArrayOf(2)))
    queue.beginDrain()
    assertNull(queue.takeForDrain())
  }

  @Test
  fun obsoleteOrderedEpochFramesArePrunedBeforeTheyAdvanceThePacer() =
    runTest {
      val manager = createManager()
      val relay = "relay-pacer-prune"
      installRealtimeSession(manager, relay)
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      setPrivateField(manager, "realtimeOutputEpoch", 4L)
      val pacer = RealtimeOrderedAudioPacer(CompletableDeferred(Unit), nowMs = { currentTime })

      // Matching ordered frames retain the normal 500 ms lookahead pacing.
      repeat(6) {
        assertFalse(manager.isRealtimeAudioFrameIrreversiblySupersededForTest(relay, generation, 4L))
        pacer.awaitAdmission(100L)
      }
      val clockAfterValidFrames = currentTime
      assertEquals(100L, clockAfterValidFrames)

      // Presentation can resolve while an ordered frame is pacing, so it remains an enqueue-
      // time admission fence rather than an irreversible early discard.
      setPrivateField(manager, "realtimeOutputPresentationPending", true)
      assertFalse(manager.isRealtimeAudioFrameIrreversiblySupersededForTest(relay, generation, 4L))
      setPrivateField(manager, "realtimeOutputPresentationPending", false)

      // A physical reply advanced the output epoch. These queued frames cannot become current
      // again, so the append path must discard them before asking the pacer for admission.
      setPrivateField(manager, "realtimeOutputEpoch", 5L)
      repeat(90) {
        assertTrue(manager.isRealtimeAudioFrameIrreversiblySupersededForTest(relay, generation, 4L))
      }
      assertEquals(clockAfterValidFrames, currentTime)

      // The current epoch remains ordered and keeps the same bounded pacing behavior.
      assertFalse(manager.isRealtimeAudioFrameIrreversiblySupersededForTest(relay, generation, 5L))
      pacer.awaitAdmission(100L)
      assertEquals(clockAfterValidFrames + 100L, currentTime)
      manager.stopAllCapture()
    }

  @Test
  fun stoppingTalkClearsAnUnconsumedWakeSeed() {
    val manager = createManager()
    manager.setNextRealtimeWakeSeed(VoiceWakeAudio(byteArrayOf(1, 0), sampleRateHz = 16_000))

    manager.stopAllCapture()

    assertNull(readPrivateField(manager, "pendingRealtimeWakeSeed"))
  }

  @Test
  fun wakeSeedResamplingPreservesSignedPcmInterpolation() {
    val manager = createManager()

    val output =
      manager.resampleWakeSeedToRealtimePcm(
        VoiceWakeAudio(byteArrayOf(0xff.toByte(), 0xff.toByte(), 1, 0), sampleRateHz = 16_000),
      )

    assertArrayEquals(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0, 0, 1, 0), output)
  }

  @Test
  fun stopTtsWithoutOutputIdentityCancelsPlaybackWithoutReplacingCancellationWaiter() =
    runTest {
      withStartedLocalPlayback { manager, playbackJob, player ->
        val pendingClear = CompletableDeferred<String?>()
        installRealtimeSession(manager, "relay-1")
        manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"audio","talkEvent":{"turnId":" "},"audioBase64":""}""")
        setPrivateField(manager, "pendingRealtimeOutputClear", pendingClear)
        val stops = player.stopCalls

        manager.stopTts()
        runCurrent()

        assertTrue(playbackJob.isCancelled)
        assertEquals(stops + 1, player.stopCalls)
        assertTrue(readPrivateField(manager, "pendingRealtimeOutputClear") === pendingClear)
      }
    }

  @Test
  fun retiredStopTtsCannotReplaceAnotherCancellationWaiter() =
    runTest {
      val manager = createManager(scope = backgroundScope)
      val priorClear = CompletableDeferred<String?>()
      installRealtimeSession(manager, "relay-a")
      manager.realtimeEvent("""{"relaySessionId":"relay-a","type":"audio","talkEvent":{"turnId":"turn-a"},"audioBase64":""}""")
      setPrivateField(manager, "pendingRealtimeOutputClear", priorClear)

      manager.stopTts()
      installRealtimeSession(manager, "relay-b")
      manager.realtimeEvent("""{"relaySessionId":"relay-b","type":"audio","talkEvent":{"turnId":"turn-b"},"audioBase64":""}""")
      runCurrent()

      assertTrue(readPrivateField(manager, "pendingRealtimeOutputClear") === priorClear)
    }

  @Test
  fun disablingPlaybackCancelsTrackedJobOnce() =
    runTest {
      withStartedLocalPlayback { manager, playbackJob, player ->
        val stops = player.stopCalls
        manager.setPlaybackEnabled(false)
        manager.setPlaybackEnabled(false)
        runCurrent()

        assertTrue(playbackJob.isCancelled)
        assertEquals(stops + 1, player.stopCalls)
      }
    }

  @Test
  fun beginPushToTalkRejectsNewCaptureWhenNewCaptureIsDisallowed() =
    runTest {
      val manager = createManager()

      val error =
        runCatching { manager.beginPushToTalk(allowNewCapture = false) }
          .exceptionOrNull()

      assertEquals("NODE_BACKGROUND_UNAVAILABLE: command requires foreground", error?.message)
    }

  @Test
  fun beginPushToTalkReturnsActiveCaptureWhenNewCaptureIsDisallowed() =
    runTest {
      val manager = createManager()
      setPrivateField(manager, "activePttCaptureId", "capture-1")

      val payload = manager.beginPushToTalk(allowNewCapture = false)

      assertEquals("capture-1", payload.captureId)
    }

  @Test
  fun beginPushToTalkRejectsInvalidatedCaptureBeforeStarting() =
    runTest {
      installSpeechRecognitionService()
      val manager = createManager()
      withMain {
        val error =
          runCatching {
            manager.beginPushToTalk(
              allowNewCapture = true,
              canStartCapture = { false },
            )
          }.exceptionOrNull()

        assertEquals("NODE_BACKGROUND_UNAVAILABLE: command requires foreground", error?.message)
        assertNull(readPrivateField(manager, "activePttCaptureId"))
        assertFalse(manager.isListening.value)
      }
    }

  @Test
  fun stopAllCaptureClearsPttWhenContinuousModeIsDisabled() {
    val manager = createManager()
    val finishingJob = Job()
    setPrivateField(manager, "activePttCaptureId", "capture-1")
    setPrivateField(manager, "finishingPttCaptureId", "capture-finishing")
    setPrivateField(manager, "finishingPttJob", finishingJob)
    setMutableStateFlow(manager, "_isListening", true)

    manager.stopAllCapture()

    assertNull(readPrivateField(manager, "activePttCaptureId"))
    assertEquals("capture-finishing", manager.finishingPushToTalkCaptureId)
    assertTrue(finishingJob.isCancelled)
    assertFalse(manager.isEnabled.value)
    assertFalse(manager.isListening.value)
    assertEquals("Off", manager.statusText.value)
  }

  @Test
  fun staleCancellationDoesNotStopNewerPushToTalkCapture() =
    runTest {
      val manager = createManager()
      val completion = CompletableDeferred<TalkPttStopPayload>()
      setPrivateField(manager, "activePttCaptureId", "capture-new")
      setPrivateField(manager, "pttCompletion", completion)
      withMain {
        val payload = manager.cancelPushToTalk("capture-old")

        assertEquals("idle", payload.status)
        assertEquals("capture-new", readPrivateField(manager, "activePttCaptureId"))
        assertFalse(completion.isCompleted)
      }
    }

  @Test
  fun oneShotRetryDoesNotReplaceActivePushToTalkCapture() =
    runTest {
      val manager = createManager()
      val completion = CompletableDeferred<TalkPttStopPayload>()
      setPrivateField(manager, "activePttCaptureId", "capture-active")
      setPrivateField(manager, "pttCompletion", completion)

      val start = manager.beginPushToTalkOnce()
      val payload = manager.awaitPushToTalkOnce(start)

      assertEquals("busy", payload.status)
      assertEquals("capture-active", payload.captureId)
      assertEquals("capture-active", readPrivateField(manager, "activePttCaptureId"))
      assertFalse(completion.isCompleted)
    }

  @Test
  fun cancelledOneShotWaitCleansItsCapture() =
    runTest {
      val manager = createManager()
      val completion = CompletableDeferred<TalkPttStopPayload>()
      setPrivateField(manager, "activePttCaptureId", "capture-1")
      setPrivateField(manager, "pttCompletion", completion)
      setMutableStateFlow(manager, "_isListening", true)
      val start = TalkPttOnceStart.Started(captureId = "capture-1", completion = completion)
      withMain {
        val wait = launch { manager.awaitPushToTalkOnce(start) }
        advanceUntilIdle()
        wait.cancel()
        runCurrent()
        wait.join()

        assertNull(readPrivateField(manager, "activePttCaptureId"))
        assertNull(readPrivateField(manager, "pttCompletion"))
        assertFalse(manager.isListening.value)
        assertTrue(completion.isCompleted)
      }
    }

  @Test
  fun staleStopDoesNotSubmitNewerPushToTalkCapture() =
    runTest {
      val manager = createManager()
      val completion = CompletableDeferred<TalkPttStopPayload>()
      setPrivateField(manager, "activePttCaptureId", "capture-new")
      setPrivateField(manager, "pttCompletion", completion)
      setPrivateField(manager, "lastTranscript", "new partial transcript")
      withMain {
        val payload = manager.endPushToTalk("capture-old")

        assertEquals("idle", payload.status)
        assertEquals("capture-new", readPrivateField(manager, "activePttCaptureId"))
        assertEquals("new partial transcript", readPrivateField(manager, "lastTranscript"))
        assertFalse(completion.isCompleted)
      }
    }

  @Test
  fun segmentDuringPushToTalkReleaseWaitsForEndOfSegmentedSession() {
    val manager = createManager()
    val releaseCompletion = CompletableDeferred<Unit>()
    setPrivateField(manager, "activePttCaptureId", "capture-1")
    setPrivateField(manager, "pttReleaseCompletion", releaseCompletion)
    val listener = recognitionListener(manager, "capture-1")
    val segment =
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("first segment"))
      }

    listener.onSegmentResults(segment)

    assertFalse(releaseCompletion.isCompleted)
    assertEquals(listOf("first segment"), readPrivateField(manager, "pttFinalSegments"))

    listener.onEndOfSegmentedSession()

    assertTrue(releaseCompletion.isCompleted)
  }

  @Test
  fun releaseKeepsWaitingPastOldGraceForLateTerminalSegment() =
    runTest {
      val manager = createManager(isConnected = { false })
      val releaseCompletion = CompletableDeferred<Unit>()
      setPrivateField(manager, "activePttCaptureId", "capture-1")
      setPrivateField(manager, "pttReleaseCompletion", releaseCompletion)
      setPrivateField(manager, "pttRecognitionRung", silenceSegmentedRung())
      @Suppress("UNCHECKED_CAST")
      (readPrivateField(manager, "pttFinalSegments") as MutableList<String>) += "early segment"
      val listener = recognitionListener(manager, "capture-1")
      withMain {
        val ending = async { manager.endPushToTalk("capture-1") }
        runCurrent()

        advanceTimeBy(1_200)
        listener.onSegmentResults(
          Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("late segment"))
          },
        )
        assertFalse(ending.isCompleted)

        listener.onEndOfSegmentedSession()
        advanceUntilIdle()

        assertEquals("early segment. late segment", ending.await().transcript)
      }
    }

  @Test
  fun cancelledEndPushToTalkClearsPendingReleaseBeforeNextBegin() =
    runTest {
      installSpeechRecognitionService()
      val manager = createManager()
      setPrivateField(manager, "activePttCaptureId", "capture-a")
      setPrivateField(manager, "pttReleaseCompletion", CompletableDeferred<Unit>())
      setPrivateField(manager, "pttRecognitionRung", silenceSegmentedRung())
      @Suppress("UNCHECKED_CAST")
      (readPrivateField(manager, "pttFinalSegments") as MutableList<String>) += "capture a"
      withMain(cleanup = manager::stopAllCapture) {
        val ending = async { manager.endPushToTalk("capture-a") }
        runCurrent()
        ending.cancel()
        runCurrent()
        ending.join()

        assertTrue(ending.isCancelled)
        assertNull(readPrivateField(manager, "activePttCaptureId"))
        assertNull(readPrivateField(manager, "pttReleaseCompletion"))
        assertEquals(emptyList<String>(), readPrivateField(manager, "pttFinalSegments"))

        val started = manager.beginPushToTalk(allowNewCapture = true)

        assertEquals(started.captureId, readPrivateField(manager, "activePttCaptureId"))
        assertEquals(emptyList<String>(), readPrivateField(manager, "pttFinalSegments"))
      }
    }

  @Test
  fun replacementBeginDrainsPendingReleaseBeforeStartingNewCapture() =
    runTest {
      installSpeechRecognitionService()
      var connectionChecks = 0
      val manager =
        createManager(
          isConnected = {
            connectionChecks += 1
            connectionChecks != 2
          },
        )
      val releaseCompletion = CompletableDeferred<Unit>()
      setPrivateField(manager, "activePttCaptureId", "capture-a")
      setPrivateField(manager, "pttReleaseCompletion", releaseCompletion)
      setPrivateField(manager, "pttRecognitionRung", silenceSegmentedRung())
      @Suppress("UNCHECKED_CAST")
      (readPrivateField(manager, "pttFinalSegments") as MutableList<String>) += "first segment"
      withMain(cleanup = manager::stopAllCapture) {
        val ending = async { manager.endPushToTalk("capture-a") }
        runCurrent()
        val starting = async { manager.beginPushToTalk(allowNewCapture = true) }
        runCurrent()

        releaseCompletion.complete(Unit)
        advanceUntilIdle()

        val ended = ending.await()
        val started = starting.await()
        assertEquals("offline", ended.status)
        assertEquals("first segment", ended.transcript)
        assertEquals(started.captureId, readPrivateField(manager, "activePttCaptureId"))
        assertEquals(emptyList<String>(), readPrivateField(manager, "pttFinalSegments"))
      }
    }

  @Test
  fun duplicateFinalForPendingTalkRunDoesNotStartAllResponseTts() {
    val manager = createManager()
    val final = CompletableDeferred<Boolean>()

    manager.ttsOnAllResponses = true
    setPrivateField(manager, "pendingRunId", "run-talk")
    setPrivateField(manager, "pendingFinal", final)

    manager.handleGatewayEvent("chat", chatFinalPayload(runId = "run-talk", text = "spoken once"))
    assertTrue(final.isCompleted)
    assertEquals(0L, playbackGeneration(manager).get())

    manager.handleGatewayEvent("chat", chatFinalPayload(runId = "run-talk", text = "spoken once"))

    assertEquals(0L, playbackGeneration(manager).get())
  }

  @Test
  fun nonPendingFinalStillUsesAllResponseTts() {
    val manager = createManager()

    manager.ttsOnAllResponses = true
    manager.handleGatewayEvent("chat", chatFinalPayload(runId = "run-other", text = "speak this"))

    assertEquals(1L, playbackGeneration(manager).get())
  }

  @Test
  fun realtimeRelayOwnsSpeechForGatewayRunConsultFinals() {
    val manager = createManager()

    manager.ttsOnAllResponses = true
    installRealtimeSession(manager, "relay-1")
    manager.handleGatewayEvent("chat", chatFinalPayload(runId = "talk-realtime-relay-consult:relay-1:call-1", text = "It is 12:38 PM."))
    manager.handleGatewayEvent("chat", chatFinalPayload(runId = "run-typed", text = "typed reply"))
    assertEquals(0L, playbackGeneration(manager).get())

    installRealtimeSession(manager, null)
    manager.handleGatewayEvent("chat", chatFinalPayload(runId = "run-after", text = "speak this"))
    assertEquals(1L, playbackGeneration(manager).get())
  }

  @Test
  fun nonPendingUserFinalDoesNotUseAllResponseTts() {
    val manager = createManager()

    manager.ttsOnAllResponses = true
    manager.handleGatewayEvent("chat", chatFinalPayload(runId = "run-user", text = "do not speak", role = "user"))

    assertEquals(0L, playbackGeneration(manager).get())
  }

  @Test
  fun realtimeCloseErrorDisablesTalkButKeepsFailureStatus() {
    var stoppedByRelay = false
    val manager = createManager(onStoppedByRelay = { stoppedByRelay = true })

    installRealtimeSession(manager, "relay-1")
    setMutableStateFlow(manager, "_isEnabled", true)
    assertNull(manager.failureNotice.value)

    manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"close","reason":"error"}""")

    assertFalse(manager.isEnabled.value)
    assertTrue(stoppedByRelay)
    assertEquals(
      "Talk failed: Realtime provider closed unexpectedly.",
      manager.statusText.value,
    )
    // Chat renders this after Talk ends; the status line alone is not shown there.
    assertEquals(manager.statusText.value, manager.failureNotice.value?.text)
  }

  @Test
  fun acknowledgingAnOlderFailureDoesNotHideAnIdenticalNewFailure() {
    val manager = createManager()
    val failure = verbatimText("Realtime provider authentication failed")
    manager.stopAllCapture(failure = failure)
    val first = checkNotNull(manager.failureNotice.value)
    manager.stopAllCapture(failure = failure)
    val second = checkNotNull(manager.failureNotice.value)

    manager.acknowledgeFailure(first)
    assertEquals(second, manager.failureNotice.value)
    manager.acknowledgeFailure(second)
    assertNull(manager.failureNotice.value)
  }

  @Test
  fun aDeferredTerminalNotificationCannotStopAReplacementTalkStart() {
    var claim: (() -> Boolean)? = null
    val manager = createManager(onStoppedByRelay = { claim = it })
    installRealtimeSession(manager, "relay-1")
    setMutableStateFlow(manager, "_isEnabled", true)
    manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"close","reason":"completed"}""")
    assertTrue(checkNotNull(claim).invoke())
    // NodeRuntime invalidates capture before its asynchronous voice-wake suppression work.
    manager.stopAllCapture()
    assertFalse(checkNotNull(claim).invoke())
  }

  @Test
  fun realtimeClosePreservesTypedFailureWithoutEnglishPrefix() {
    val manager = createManager()

    installRealtimeSession(manager, "relay-1")
    setMutableStateFlow(manager, "_isEnabled", true)
    setTalkFailure(manager, verbatimText("Échec de Talk : session refusée."))

    manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"close","reason":"error"}""")

    assertEquals("Échec de Talk : session refusée.", manager.statusText.value)
  }

  @Test
  fun explicitFarewellClosesTheRelayAndRequestsSleepWithoutTreatingACommandAsFarewell() {
    val manager = createManager()
    installRealtimeSession(manager, "relay-1")
    setMutableStateFlow(manager, "_isEnabled", true)
    manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"transcript","role":"assistant","text":"Ich prüfe","final":false}""")

    manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"transcript","role":"user","text":"Danke Jarvis, das war's, bis später","final":true}""")

    assertFalse(manager.isEnabled.value)
    assertNull(readPrivateField(manager, "realtimeSessionId"))
    assertEquals(JarvisConversationCloseReason.ExplicitFarewell, manager.conversationLifecycle.value.closingReason)
    assertFalse(manager.conversationLifecycle.value.sleepRequested)
    assertTrue(manager.conversation.value.none { it.role == VoiceConversationRole.Assistant && it.text == "Ich prüfe" })

    installRealtimeSession(manager, "relay-2")
    setMutableStateFlow(manager, "_isEnabled", true)
    manager.realtimeEvent("""{"relaySessionId":"relay-2","type":"transcript","role":"user","text":"Danke, schalte das Licht aus","final":true}""")

    assertTrue(manager.isEnabled.value)
    assertEquals("relay-2", readPrivateField(manager, "realtimeSessionId"))
    manager.stopAllCapture()
  }

  @Test
  fun sleepFarewellPublishesTheSleepRequest() {
    val manager = createManager()
    installRealtimeSession(manager, "relay-1")
    setMutableStateFlow(manager, "_isEnabled", true)

    manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"transcript","role":"user","text":"Jarvis, geh schlafen","final":true}""")

    assertTrue(manager.conversationLifecycle.value.sleepRequested)
    assertEquals(JarvisConversationCloseReason.ExplicitFarewell, manager.conversationLifecycle.value.closingReason)
  }

  @Test
  fun realtimeRelayClosesAfterSevenSecondsOfQuietWithVirtualClocks() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-quiet")
      setMutableStateFlow(manager, "_isEnabled", true)

      manager.realtimeEvent("""{"relaySessionId":"relay-quiet","type":"transcript","role":"user","text":"Wie spät ist es","final":true}""")
      runCurrent() // Register the manager's gateway-scope idle delay before advancing virtual time.
      ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
      advanceTimeBy(7_000)
      runCurrent()

      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun stalledRealtimeResponseDetachesMicrophoneAtSevenSecondsButKeepsRelay() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-stalled")
      setMutableStateFlow(manager, "_isEnabled", true)

      manager.realtimeEvent("""{"relaySessionId":"relay-stalled","type":"transcript","role":"user","text":"Wie spät ist es","final":true}""")
      manager.realtimeEvent("""{"relaySessionId":"relay-stalled","type":"responseStarted","turnId":"stalled-turn"}""")
      runCurrent()
      ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
      advanceTimeBy(7_000)
      runCurrent()
      assertEquals("relay-stalled", readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isListening.value)
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)

      // No terminal, tool expiry, or later event arrives. The original watchdog must
      // still retain and then close the relay at its transport bound exactly once.
      ShadowSystemClock.advanceBy(Duration.ofSeconds(83))
      advanceTimeBy(83_000)
      runCurrent()
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Stalled, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun unacknowledgedAppendTimeoutDetachesActualCaptureButRetainsRelayForLateAudibleDrain() =
    runBlocking {
      val frames = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
      val append = CompletableDeferred<Pair<String, WebSocket>>()
      org.robolectric.shadows.ShadowAudioRecord.setSourceProvider {
        object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
          override fun readInByteArray(bytes: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
            val frame = checkNotNull(frames.poll(10, TimeUnit.SECONDS)) { "Timed-out append capture did not retire" }
            frame.copyInto(bytes, destinationOffset = offset)
            return frame.size
          }
        }
      }
      try {
        withStartedTalk(interceptRequest = { request, socket ->
          if (request.getValue("method").jsonPrimitive.content != "talk.session.appendAudio" || append.isCompleted) return@withStartedTalk false
          append.complete(request.getValue("id").jsonPrimitive.content to socket)
          true // Deliberately no response: exercise GatewaySession's real 8s timeout callback.
        }) { proof ->
          var capture: Thread? = null
          try {
            assertTrue(proof.drainNextCaptureTask()) // append worker
            capture = Thread { proof.drainNextCaptureTask() }.also { it.start() }
            withTimeout(5_000) {
              while (readPrivateField(proof.manager, "realtimeAudioInput") == null) {
                withContext(Dispatchers.Default) { delay(10) }
              }
            }
            assertNotNull(readPrivateField(proof.manager, "realtimeCaptureJob"))
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Prüfe die Antwort","final":true}""")
            frames.put(ByteArray(4_800) { if (it % 2 == 0) 0x20 else 0x01 })
            val (id, socket) =
              withTimeout(5_000) {
                while (!append.isCompleted) {
                  // The live frame wakes the append coroutine through the same
                  // serialized capture dispatcher as production ingress.
                  proof.drainNextCaptureTask()
                  withContext(Dispatchers.Default) { delay(10) }
                }
                append.await()
              }

            withTimeout(10_000) {
              while (readPrivateField(proof.manager, "realtimeAudioInput") != null) {
                withContext(Dispatchers.Default) { delay(25) }
              }
            }
            assertEquals("playback-relay", readPrivateField(proof.manager, "realtimeSessionId"))
            assertNull(readPrivateField(proof.manager, "realtimeCaptureJob"))
            assertTrue(readPrivateField(proof.manager, "realtimeMicDetachedForQuiet") as Boolean)
            assertTrue(readPrivateField(proof.manager, "realtimeAwaitingConsultPlayback") as Boolean)

            // The timed-out socket may answer late, but it cannot revive capture.
            socket.send("""{"type":"res","id":"$id","ok":true,"payload":{}}""")
            withContext(Dispatchers.Default) { delay(100) }
            assertNull(readPrivateField(proof.manager, "realtimeAudioInput"))

            // A later nonzero playout callback clears the pending-reply state;
            // its physical drain re-enters the ordinary follow-up path.
            val owner = readPrivateField(proof.manager, "realtimePlayoutSession") as RealtimePlayout.Session
            owner.onState(true, 0.4f, null, true, 0.4f)
            assertFalse(readPrivateField(proof.manager, "realtimeAwaitingConsultPlayback") as Boolean)
            owner.onState(false, 0f, null, false, 0f)
            owner.onAudibleDrained()
            assertFalse(readPrivateField(proof.manager, "realtimeMicDetachedForQuiet") as Boolean)
            assertTrue(proof.manager.isListening.value)

            proof.manager.stopAllCapture()
            assertNull(readPrivateField(proof.manager, "realtimeSessionId"))
          } finally {
            proof.manager.stopAllCapture()
            frames.offer(byteArrayOf())
            capture?.join(5_000)
            assertFalse("Timed-out append capture worker must terminate", capture?.isAlive == true)
            proof.drainCancelledCapture()
          }
        }
      } finally {
        org.robolectric.shadows.ShadowAudioRecord.clearSource()
      }
    }

  @Test
  fun retiredAppendTimeoutCannotReviveAnExplicitlyStoppedRelay() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-stale-append-timeout"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()

      manager.handleRealtimeAudioAppendFailureForTest(
        relay,
        generation,
        GatewaySession.ErrorShape("UNAVAILABLE", "request timeout"),
      )
      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertTrue(readPrivateField(manager, "realtimeAwaitingConsultPlayback") as Boolean)

      // Quiet detach closes the recorder but deliberately retains frames it already read.
      // Terminal stop still rejects a later callback through the cleared relay identity.
      assertEquals(generation, (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get())
      manager.stopAllCapture()
      manager.handleRealtimeAudioAppendFailureForTest(
        relay,
        generation,
        GatewaySession.ErrorShape("UNAVAILABLE", "request timeout"),
      )
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isEnabled.value)
      assertFalse(readPrivateField(manager, "realtimeAwaitingConsultPlayback") as Boolean)
    }

  @Test
  fun quietDetachClosesTheRecorderButRetainsAlreadyOwnedOrderedIngressUntilTerminalStop() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-owned-ingress")
      setMutableStateFlow(manager, "_isEnabled", true)
      val append = Job()
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      setPrivateField(manager, "realtimeAppendJob", append)

      assertTrue(manager.detachRealtimeCaptureForQuietForTest())
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertSame(append, readPrivateField(manager, "realtimeAppendJob"))
      assertFalse(append.isCancelled)
      assertEquals(generation, (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get())

      manager.stopAllCapture()
      assertTrue(append.isCancelled)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
    }

  @Test
  fun focusLossAfterQuietDetachStillStopsTheCurrentRelay() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-detached-focus"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      val audioManager =
        RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
      val lease =
        RealtimeCommunicationAudio.open(
          audioManager = audioManager,
          isCurrent = { readPrivateField(manager, "realtimeSessionId") == relay && manager.isEnabled.value },
          onFocusLost = { manager.failRealtimeRelayForTest(relay, "audio focus lost") },
        )
      setPrivateField(manager, "realtimeCommunicationAudio", lease)
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()

      manager.handleRealtimeAudioAppendFailureForTest(
        relay,
        generation,
        GatewaySession.ErrorShape("UNAVAILABLE", "request timeout"),
      )
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      shadowOf(audioManager).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
      shadowOf(Looper.getMainLooper()).idle()
      runCurrent()

      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isEnabled.value)
      lease.close()
    }

  @Test
  @Config(sdk = [30])
  @Suppress("DEPRECATION") // The API 30 relay lease owns speaker state until durable cleanup.
  fun gatewayScopeReplacementStillReleasesTheRelayCommunicationLease() =
    runBlocking {
      val manager = createManager()
      val relay = "relay-gateway-replacement"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      val audioManager =
        RuntimeEnvironment.getApplication().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
      audioManager.isSpeakerphoneOn = false
      val lease = RealtimeCommunicationAudio.open(audioManager)
      setPrivateField(manager, "realtimeCommunicationAudio", lease)
      assertTrue(lease.eligible)
      assertTrue(audioManager.isSpeakerphoneOn)

      manager.onGatewayScopeChanging()

      withTimeout(2_000) {
        while (lease.eligible) delay(10)
      }
      assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
      assertFalse(audioManager.isSpeakerphoneOn)
      lease.close()
    }

  @Test
  fun nonTimeoutAppendFailureStillFailsAndClosesTheRelay() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-fatal-append"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()

      manager.handleRealtimeAudioAppendFailureForTest(
        relay,
        generation,
        GatewaySession.ErrorShape("INTERNAL", "gateway rejected audio"),
      )

      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isEnabled.value)
      assertFalse(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
    }

  @Test
  fun pendingConsultAdmissionRetainsOnlyActiveRelayUntilTheExistingStallBound() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-pending-admission"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Prüfe das","final":true}""")
      val baseline = readPrivateField(manager, "conversationLastActivityAtMs") as Long

      manager.realtimeEvent(pendingConsultEvent("other-relay", "foreign", "start"))
      manager.realtimeEvent(pendingConsultEvent(relay, "request-1", "start"))
      manager.realtimeEvent(pendingConsultEvent(relay, "request-1", "start"))
      @Suppress("UNCHECKED_CAST")
      val pending = readPrivateField(manager, "realtimePendingConsultRequestIds") as LinkedHashSet<String>
      assertEquals(setOf("request-1"), pending)
      assertEquals("Pending admission must not renew the 90-second relay clock", baseline, readPrivateField(manager, "conversationLastActivityAtMs"))

      advanceConversationClock(this, JarvisConversationLifecycle.QUIET_TIMEOUT_MS)
      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)

      manager.realtimeEvent(pendingConsultEvent(relay, "unknown", "end"))
      assertEquals(setOf("request-1"), pending)
      advanceConversationClock(this, JarvisConversationLifecycle.STALLED_TIMEOUT_MS - JarvisConversationLifecycle.QUIET_TIMEOUT_MS)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Stalled, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun pendingConsultAdmissionEndYieldsToAcceptedLifecycleAndClearsOnRelayStop() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-pending-to-lifecycle"
      installRealtimeSession(manager, relay)
      setPrivateField(manager, "realtimeSessionKey", "main")
      setMutableStateFlow(manager, "_isEnabled", true)
      val coordinator = readPrivateField(manager, "realtimeAgentCoordinator") as RealtimeAgentCoordinator
      coordinator.beginSession(RealtimeAgentSession(relay, "main"))
      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Prüfe das","final":true}""")
      manager.realtimeEvent(pendingConsultEvent(relay, "request-1", "start"))
      val runId = "talk-realtime-relay-consult:${System.currentTimeMillis()}:admission"
      manager.handleGatewayEvent(
        "agent",
        """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"start"}}""",
      )
      manager.realtimeEvent(pendingConsultEvent(relay, "request-1", "end"))
      @Suppress("UNCHECKED_CAST")
      val pending = readPrivateField(manager, "realtimePendingConsultRequestIds") as LinkedHashSet<String>
      assertTrue(pending.isEmpty())

      advanceConversationClock(this, JarvisConversationLifecycle.QUIET_TIMEOUT_MS)
      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertTrue(coordinator.hasPendingWork(relay))

      manager.stopAllCapture()
      assertTrue(pending.isEmpty())
    }

  @Test
  fun longGatewayConsultWithoutAudibleReplyDetachesMicrophoneWithoutClosingRelay() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-consult")
      setPrivateField(manager, "realtimeSessionKey", "main")
      setMutableStateFlow(manager, "_isEnabled", true)
      val coordinator = readPrivateField(manager, "realtimeAgentCoordinator") as RealtimeAgentCoordinator
      coordinator.beginSession(RealtimeAgentSession("relay-consult", "main"))
      val runId = "talk-realtime-relay-consult:${System.currentTimeMillis()}:c35a4299-a535-433a-b2e6-fc8e0628c884"

      manager.realtimeEvent("""{"relaySessionId":"relay-consult","type":"transcript","role":"user","text":"Prüfe das","final":true}""")
      manager.handleGatewayEvent(
        "agent",
        """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"start"}}""",
      )
      // A short provider filler may finish and leave the visual state at Listening.
      manager.realtimeEvent("""{"relaySessionId":"relay-consult","type":"ready"}""")
      runCurrent()

      advanceConversationClock(this, JarvisConversationLifecycle.QUIET_TIMEOUT_MS)
      assertEquals("relay-consult", readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isListening.value)
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)

      advanceConversationClock(this, JarvisConversationLifecycle.STALLED_TIMEOUT_MS - JarvisConversationLifecycle.QUIET_TIMEOUT_MS)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
    }

  @Test
  fun completedConsultWithOnlySilentProviderAudioRetainsReplyButStopsMicrophone() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-awaiting-consult-voice"
      installRealtimeSession(manager, relay)
      setPrivateField(manager, "realtimeSessionKey", "main")
      setMutableStateFlow(manager, "_isEnabled", true)
      val coordinator = readPrivateField(manager, "realtimeAgentCoordinator") as RealtimeAgentCoordinator
      coordinator.beginSession(RealtimeAgentSession(relay, "main"))
      val runId = "talk-realtime-relay-consult:${System.currentTimeMillis()}:delivery"
      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Funktionierst du jetzt","final":false}""")
      for (phase in listOf("start", "end")) {
        manager.handleGatewayEvent("agent", """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"$phase"}}""")
      }
      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"ready"}""")
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session
      owner.onState(false, 0f, null, true, 0f)
      runCurrent()
      advanceConversationClock(this, JarvisConversationLifecycle.QUIET_TIMEOUT_MS)
      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertFalse(manager.isListening.value)

      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Funktionierst du jetzt","final":true}""")
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      owner.onState(true, 0.4f, null, true, 0.4f)
      assertFalse(readPrivateField(manager, "realtimeAwaitingConsultPlayback") as Boolean)
      owner.onState(false, 0f, null, false, 0f)
      owner.onAudiblePresented()
      owner.onAudibleDrained()
      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
    }

  @Test
  fun lifecycleStartBeforeFinalUserTranscriptKeepsProgressFromTakingDirectPresentationPath() = runTest {
    var finalUser = 0
    var completed = 0
    var directCompleted = 0
    val presentation = ai.openclaw.app.home.HorizonPresentationRepository.createForTests()
    val coordinator = ai.openclaw.app.home.HorizonVoicePresentationCoordinator(presentation)
    val manager = createManager(
      scope = backgroundScope,
      onFinalVoiceTranscript = { finalUser += 1; coordinator.onFinalUserTranscript(it) },
      onVoiceAgentCompleted = { completed += 1; coordinator.onAgentCompleted() },
      onDirectVoiceAssistantTranscriptCompleted = { directCompleted += 1; coordinator.onDirectAssistantTranscriptCompleted() },
      onVoiceSpeechDrained = coordinator::onAssistantSpeechDrained,
    )
    val relay = "relay-weather-order"
    installRealtimeSession(manager, relay)
    setPrivateField(manager, "realtimeSessionKey", "main")
    setMutableStateFlow(manager, "_isEnabled", true)
    val agent = readPrivateField(manager, "realtimeAgentCoordinator") as RealtimeAgentCoordinator
    agent.beginSession(RealtimeAgentSession(relay, "main"))
    val runId = "talk-realtime-relay-consult:${System.currentTimeMillis()}:weather"

    manager.handleGatewayEvent("agent", """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"start"}}""")
    manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Wie wird das Wetter heute hier bei uns","final":true}""")
    manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"assistant","text":"Ich prüfe das.","final":true}""")
    assertEquals(1, finalUser)
    assertEquals(0, directCompleted)

    val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session
    owner.onAudibleDrained() // progress speech before the agent result
    assertNull(presentation.presentation.value.tile)
    manager.handleGatewayEvent("agent", """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"end"}}""")
    assertEquals(1, completed)
    assertNull(presentation.presentation.value.tile)
    owner.onAudibleDrained() // actual final answer drain
    assertEquals(ai.openclaw.app.home.HorizonTileType.Weather, presentation.presentation.value.tile?.type)
  }

  @Test
  fun missingFinalTranscriptStillAgesOutAtTheQuietDeadline() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-partial")
      setMutableStateFlow(manager, "_isEnabled", true)

      manager.realtimeEvent("""{"relaySessionId":"relay-partial","type":"transcript","role":"user","text":"Ich wollte noch","final":false}""")
      runCurrent()
      ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
      advanceTimeBy(7_000)
      runCurrent()

      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun microphoneNoiseNearQuietDeadlineCannotRenewFinalTranscriptLease() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-ingress")
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.realtimeEvent("""{"relaySessionId":"relay-ingress","type":"transcript","role":"user","text":"Vorherige Frage","final":true}""")
      runCurrent()

      advanceConversationClock(this, 6_000)
      manager.recordRealtimeInputSpeechForTest("relay-ingress")
      runCurrent()
      advanceConversationClock(this, 1_000)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun newPartialWordsRenewOneUtteranceButRepeatedPartialDoesNot() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-partial-words")
      setMutableStateFlow(manager, "_isEnabled", true)

      manager.realtimeEvent("""{"relaySessionId":"relay-partial-words","type":"transcript","role":"user","text":"Schalte","final":false}""")
      runCurrent()
      advanceConversationClock(this, 5_000)
      manager.realtimeEvent("""{"relaySessionId":"relay-partial-words","type":"transcript","role":"user","text":"Schalte das Licht","final":false}""")
      runCurrent()
      advanceConversationClock(this, 6_000)
      assertEquals("relay-partial-words", readPrivateField(manager, "realtimeSessionId"))

      manager.realtimeEvent("""{"relaySessionId":"relay-partial-words","type":"transcript","role":"user","text":"Schalte das Licht","final":false}""")
      advanceConversationClock(this, 1_000)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
    }

  @Test
  fun continualNewPartialWordsCannotOutliveBoundedUnfinalizedUtterance() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-partial-cap")
      setMutableStateFlow(manager, "_isEnabled", true)

      manager.realtimeEvent("""{"relaySessionId":"relay-partial-cap","type":"transcript","role":"user","text":"Bitte","final":false}""")
      runCurrent()
      advanceConversationClock(this, 10_000)
      manager.realtimeEvent("""{"relaySessionId":"relay-partial-cap","type":"transcript","role":"user","text":"Bitte schalte","final":false}""")
      advanceConversationClock(this, 10_000)
      manager.realtimeEvent("""{"relaySessionId":"relay-partial-cap","type":"transcript","role":"user","text":"Bitte schalte das Licht","final":false}""")
      advanceConversationClock(this, 10_000)

      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun partialUtteranceDeadlineDetachesMicrophoneButRetainsItsPendingConsult() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-partial-consult"
      installRealtimeSession(manager, relay)
      setPrivateField(manager, "realtimeSessionKey", "main")
      setMutableStateFlow(manager, "_isEnabled", true)
      val coordinator = readPrivateField(manager, "realtimeAgentCoordinator") as RealtimeAgentCoordinator
      coordinator.beginSession(RealtimeAgentSession(relay, "main"))
      val runId = "talk-realtime-relay-consult:${System.currentTimeMillis()}:partial-consult"

      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Bitte","final":false}""")
      manager.handleGatewayEvent(
        "agent",
        """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"start"}}""",
      )
      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"ready"}""")
      runCurrent()
      advanceConversationClock(this, 10_000)
      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Bitte prüfe","final":false}""")
      advanceConversationClock(this, 10_000)
      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Bitte prüfe das","final":false}""")
      advanceConversationClock(this, 10_000)

      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isListening.value)
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertTrue(coordinator.hasPendingWork(relay))
      // A missing answer still releases the transport at its original bounded deadline.
      advanceConversationClock(this, JarvisConversationLifecycle.STALLED_TIMEOUT_MS - 10_000)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Stalled, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun audibleReplyDefersExpiredPartialCapThenClosesAfterPhysicalFollowUpWindow() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-partial-audible")
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.realtimeEvent("""{"relaySessionId":"relay-partial-audible","type":"transcript","role":"user","text":"Bitte","final":false}""")
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session
      runCurrent()

      // Drive the same fresh-PCM callback a real playout session uses. One isolated
      // callback only has a short audible hangover; this models continuous output.
      repeat((JarvisConversationLifecycle.INPUT_SPEECH_MAX_GRACE_MS / 100L).toInt()) {
        advanceConversationClock(this, 100L)
        owner.onState(true, 0.4f, null, true, 0.4f)
      }
      assertEquals("relay-partial-audible", readPrivateField(manager, "realtimeSessionId"))

      owner.onAudiblePresented()
      owner.onAudibleDrained()
      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
    }

  @Test
  fun physicalAssistantDrainClosesAfterTwentySecondFollowUpWindow() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-follow-up")
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)

      manager.armPostAssistantFollowUpForTest()
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()

      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS - 1L)

      assertEquals("relay-follow-up", readPrivateField(manager, "realtimeSessionId"))

      advanceConversationClock(this, 1L)

      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun progressDrainCannotArmFollowUpWhileConsultReplyIsStillPending() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-progress-before-final"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "realtimeMicDetachedForQuiet", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      setPrivateField(manager, "realtimeAwaitingConsultPlayback", true)
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session

      // A spoken progress sentence can drain before the consult's actual answer.
      owner.onAudibleDrained()
      assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)

      // Genuine fresh answer PCM settles the pending-consult marker. Its physical
      // drain may then arm the ordinary, bounded follow-up handoff.
      owner.onState(true, 0.4f, null, true, 0.4f)
      owner.onAudiblePresented()
      owner.onAudibleDrained()
      assertTrue((readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) > SystemClock.elapsedRealtime())
      assertFalse(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      manager.stopAllCapture()
    }

  @Test
  fun consultEndClearsProgressWindowAndQueuedAnswerPresentationRetainsTheRelay() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-progress-then-queued-final"
      installRealtimeSession(manager, relay)
      setPrivateField(manager, "realtimeSessionKey", "main")
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      manager.armPostAssistantFollowUpForTest()
      assertTrue((readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) > SystemClock.elapsedRealtime())

      val coordinator = readPrivateField(manager, "realtimeAgentCoordinator") as RealtimeAgentCoordinator
      coordinator.beginSession(RealtimeAgentSession(relay, "main"))
      val runId = "talk-realtime-relay-consult:${System.currentTimeMillis()}:queued-final"
      manager.handleGatewayEvent(
        "agent",
        """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"start"}}""",
      )
      manager.handleGatewayEvent(
        "agent",
        """{"runId":"$runId","sessionKey":"main","stream":"tool","data":{"phase":"start","toolCallId":"missing-terminal","name":"exec"}}""",
      )
      assertEquals(JarvisToolPhase.Running, manager.activeToolExecutions.value.single().phase)
      manager.handleGatewayEvent(
        "agent",
        """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"end"}}""",
      )
      assertEquals(JarvisToolPhase.Unknown, manager.activeToolExecutions.value.single().phase)
      assertTrue(readPrivateField(manager, "realtimeAwaitingConsultPlayback") as Boolean)
      assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))

      // The Gateway's final chat retires the quarantined consult. Ending the relay itself
      // would cancel its owner and is not a provider completion.
      manager.handleGatewayEvent(
        "chat",
        """{"runId":"$runId","sessionKey":"main","state":"final","message":{"role":"assistant","content":"fertig"}}""",
      )
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session
      // The playout actor accepts the audible range first. It reports fresh PCM before
      // the physical playhead reaches that range, so Talk owns the pending-presentation
      // watchdog gap without inventing a second presentation callback. Its status owner is
      // the same one that the actual `talk.event audio` path assigns to this final output.
      owner.onState(
        true,
        0.4f,
        realtimeOutputStatusOwnerForTest(manager, "queued-final"),
        true,
        0.4f,
      )
      assertFalse(readPrivateField(manager, "realtimeAwaitingConsultPlayback") as Boolean)
      assertTrue(readPrivateField(manager, "realtimeOutputPresentationPending") as Boolean)
      setPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs", SystemClock.elapsedRealtime() - 1L)
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()
      advanceConversationClock(this, 1L)
      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))

      // The same physical output then presents and drains once. A completed relay has
      // no further consult work, so this one real drain opens the normal follow-up window.
      owner.onAudiblePresented()
      owner.onAudibleDrained()
      assertTrue((readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) > SystemClock.elapsedRealtime())
      manager.stopAllCapture()
    }

  @Test
  fun presentedAudibleReplyRetainsRelayUntilPhysicalDrainThenArmsFollowUp() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-presented-awaiting-drain"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session

      // Presentation clears the capture suppression so the post-output tail can be collected,
      // but it is still before AudioTrack's physical drain callback.
      owner.onState(true, 0.4f, null, true, 0.4f)
      owner.onAudiblePresented()
      assertTrue(readPrivateField(manager, "realtimePhysicalAudibleDrainPending") as Boolean)
      // The short local level hangover may expire while AudioTrack still holds queued PCM.
      setPrivateField(manager, "realtimeAudible", false)
      ShadowSystemClock.advanceBy(Duration.ofMillis(JarvisConversationLifecycle.QUIET_TIMEOUT_MS + 1L))
      setPrivateField(
        manager,
        "conversationLastActivityAtMs",
        SystemClock.elapsedRealtime() - JarvisConversationLifecycle.QUIET_TIMEOUT_MS,
      )
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()
      advanceConversationClock(this, 1L)

      // The quiet policy may retire the microphone, never the reply that AudioTrack has yet to drain.
      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertTrue(readPrivateField(manager, "realtimePhysicalAudibleDrainPending") as Boolean)

      owner.onAudibleDrained()
      assertFalse(readPrivateField(manager, "realtimePhysicalAudibleDrainPending") as Boolean)
      assertTrue((readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) > SystemClock.elapsedRealtime())
      manager.stopAllCapture()
    }

  @Test
  fun finitePhysicalPlayoutEndClearsDrainPendingAndArmsFollowUpWithoutZeroTail() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-finite-end")
      setMutableStateFlow(manager, "_isEnabled", true)
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session

      owner.onState(true, 0.4f, null, true, 0.4f)
      owner.onAudiblePresented()
      assertTrue(readPrivateField(manager, "realtimePhysicalAudibleDrainPending") as Boolean)

      // RealtimePlayout emits playing=false only once AudioTrack's presented head reached
      // its finite written end. No rendered 300ms zero tail exists for this response.
      owner.onState(false, null, null, false, null)
      assertFalse(readPrivateField(manager, "realtimePhysicalAudibleDrainPending") as Boolean)
      assertTrue((readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) > SystemClock.elapsedRealtime())
      manager.stopAllCapture()
    }

  @Test
  fun physicalFollowUpDeadlineWinsWhileOrderedIngressStillDrains() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-ordered-follow-up"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "realtimeMicDetachedForQuiet", true)
      setPrivateField(manager, "realtimeOrderedIngressDraining", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)

      manager.armPostAssistantFollowUpForTest()
      val physicalDeadline = readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long
      assertTrue(physicalDeadline > SystemClock.elapsedRealtime())
      assertTrue(readPrivateField(manager, "realtimeFollowUpAwaitingOrderedIngress") as Boolean)

      // The old recorder-owned source remains ordered, but it cannot postpone the physical
      // reply's privacy boundary. Capture detaches at that original deadline.
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()
      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS)
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertEquals(physicalDeadline, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))

      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      manager.finishRealtimeOrderedIngressForTest(relay, generation)
      val finalizationDeadline = readPrivateField(manager, "realtimePostDrainFinalizationDeadlineMs") as Long
      assertTrue(finalizationDeadline > SystemClock.elapsedRealtime())

      // Quiet old PCM has no pending result, so its finite drain closes after the short
      // provider-completion grace rather than holding the disabled microphone relay for 90s.
      advanceConversationClock(
        this,
        RealtimeOrderedAudioPacer.MAX_LOOKAHEAD_MS +
          JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_FINAL_GRACE_MS,
      )
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      manager.stopAllCapture()
    }

  @Test
  fun orderedIngressFinishedBeforePhysicalDeadlineKeepsOnlyTheRemainingWindow() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-ordered-remaining-window"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "realtimeMicDetachedForQuiet", true)
      setPrivateField(manager, "realtimeOrderedIngressDraining", true)
      setPrivateField(manager, "realtimeFollowUpAwaitingOrderedIngress", true)
      val deadline = SystemClock.elapsedRealtime() + 4_000L
      setPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs", deadline)

      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      manager.finishRealtimeOrderedIngressForTest(relay, generation)

      assertEquals(deadline, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))
      assertFalse(readPrivateField(manager, "realtimeFollowUpAwaitingOrderedIngress") as Boolean)
      manager.stopAllCapture()
    }

  @Test
  fun detachedFinalTranscriptKeepsPostDrainRelayForTheExistingStalledBound() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-ordered-late-final"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "realtimeMicDetachedForQuiet", true)
      setPrivateField(manager, "realtimeOrderedIngressDraining", true)
      setPrivateField(manager, "realtimeFollowUpAwaitingOrderedIngress", true)
      setPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs", SystemClock.elapsedRealtime() - 1L)
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      manager.finishRealtimeOrderedIngressForTest(relay, generation)

      manager.realtimeEvent("""{"relaySessionId":"$relay","type":"transcript","role":"user","text":"Bitte fortsetzen","final":true}""")
      assertTrue(readPrivateField(manager, "realtimeLateDetachedUserFinalPending") as Boolean)
      advanceConversationClock(
        this,
        RealtimeOrderedAudioPacer.MAX_LOOKAHEAD_MS +
          JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_FINAL_GRACE_MS,
      )

      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      manager.stopAllCapture()
    }

  @Test
  fun pendingConsultDuringPostDrainGraceUsesTheExistingStalledBound() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-ordered-consult"
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "realtimeMicDetachedForQuiet", true)
      setPrivateField(manager, "realtimeOrderedIngressDraining", true)
      setPrivateField(manager, "realtimeFollowUpAwaitingOrderedIngress", true)
      setPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs", SystemClock.elapsedRealtime() - 1L)
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      manager.finishRealtimeOrderedIngressForTest(relay, generation)
      // This marker is set by a real consult that has completed its lifecycle but whose
      // final audible response has not yet reached Android.
      setPrivateField(manager, "realtimeAwaitingConsultPlayback", true)

      advanceConversationClock(
        this,
        RealtimeOrderedAudioPacer.MAX_LOOKAHEAD_MS +
          JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_FINAL_GRACE_MS,
      )

      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertEquals(0L, readPrivateField(manager, "realtimePostDrainFinalizationDeadlineMs"))
      manager.stopAllCapture()
    }

  @Test
  fun freshAudibleReplyClearsAnOldPostDrainFinalization() {
    val manager = createManager()
    installRealtimeSession(manager, "relay-fresh-post-drain")
    setMutableStateFlow(manager, "_isEnabled", true)
    setPrivateField(manager, "realtimePostDrainFinalizationDeadlineMs", SystemClock.elapsedRealtime() + 2_500L)
    setPrivateField(manager, "realtimeLateDetachedUserFinalPending", true)
    val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session

    owner.onState(true, 0.4f, null, true, 0.4f)

    assertEquals(0L, readPrivateField(manager, "realtimePostDrainFinalizationDeadlineMs"))
    assertFalse(readPrivateField(manager, "realtimeLateDetachedUserFinalPending") as Boolean)
    manager.stopAllCapture()
  }

  @Test
  fun deferredOrderedIngressKeepsTheReplacementCaptureAlivePastTheOldQuietDeadline() =
    runTest {
      val manager = createManager(scope = this)
      val relay = "relay-ordered-idle-gap"
      val ready = CompletableDeferred<Unit>()
      installRealtimeSession(manager, relay)
      setMutableStateFlow(manager, "_isEnabled", true)
      advanceConversationClock(this, JarvisConversationLifecycle.QUIET_TIMEOUT_MS + 1L)
      setPrivateField(manager, "conversationLastActivityAtMs", SystemClock.elapsedRealtime() - JarvisConversationLifecycle.QUIET_TIMEOUT_MS - 1L)
      // This is the exact state after ordered ingress finished and replacement capture started,
      // before AudioRecord completed its readiness callback.
      setPrivateField(manager, "realtimeFollowUpAwaitingOrderedIngress", false)
      setPrivateField(manager, "realtimeFollowUpAwaitingCaptureReady", true)
      setPrivateField(manager, "realtimeMicDetachedForQuiet", false)
      setPrivateField(manager, "realtimeCaptureReady", ready)

      manager.scheduleConversationIdleCheckForTest()
      runCurrent()

      assertEquals(relay, readPrivateField(manager, "realtimeSessionId"))
      assertFalse(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)
      assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))

      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      ready.complete(Unit)
      manager.armPostAssistantFollowUpAfterCaptureReadyForTest(relay, generation, ready)
      assertTrue((readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) > SystemClock.elapsedRealtime())
      manager.stopAllCapture()
    }

  @Test
  fun staleCaptureReadyCannotReopenADeferredOrderedIngressFollowUp() {
    val manager = createManager()
    val ready = CompletableDeferred<Unit>().also { it.complete(Unit) }
    installRealtimeSession(manager, "relay-replacement")
    setMutableStateFlow(manager, "_isEnabled", true)
    setPrivateField(manager, "realtimeFollowUpAwaitingCaptureReady", true)
    setPrivateField(manager, "realtimeCaptureReady", ready)
    val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()

    manager.armPostAssistantFollowUpAfterCaptureReadyForTest("relay-retired", generation, ready)

    assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))
    assertTrue(readPrivateField(manager, "realtimeFollowUpAwaitingCaptureReady") as Boolean)
    manager.stopAllCapture()
  }

  @Test
  fun cancelledCaptureReadyCannotArmADeferredOrderedIngressFollowUp() {
    val manager = createManager()
    val relay = "relay-cancelled-ready"
    val ready = CompletableDeferred<Unit>().also { it.cancel() }
    installRealtimeSession(manager, relay)
    setMutableStateFlow(manager, "_isEnabled", true)
    setPrivateField(manager, "realtimeFollowUpAwaitingCaptureReady", true)
    setPrivateField(manager, "realtimeCaptureReady", ready)
    val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()

    manager.armPostAssistantFollowUpAfterCaptureReadyForTest(relay, generation, ready)

    assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))
    assertTrue(readPrivateField(manager, "realtimeFollowUpAwaitingCaptureReady") as Boolean)
    manager.stopAllCapture()
  }

  @Test
  fun undrainedAudibleReplyStillUsesTheExistingNinetySecondRelayBound() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-undrained-bound")
      setMutableStateFlow(manager, "_isEnabled", true)
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session
      owner.onState(true, 0.4f, null, true, 0.4f)
      owner.onAudiblePresented()
      setPrivateField(manager, "realtimeAudible", false)
      ShadowSystemClock.advanceBy(Duration.ofMillis(JarvisConversationLifecycle.QUIET_TIMEOUT_MS + 1L))
      setPrivateField(
        manager,
        "conversationLastActivityAtMs",
        SystemClock.elapsedRealtime() - JarvisConversationLifecycle.QUIET_TIMEOUT_MS,
      )
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()
      advanceConversationClock(this, 1L)
      assertEquals("relay-undrained-bound", readPrivateField(manager, "realtimeSessionId"))

      advanceConversationClock(
        this,
        JarvisConversationLifecycle.STALLED_TIMEOUT_MS - JarvisConversationLifecycle.QUIET_TIMEOUT_MS,
      )
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Stalled, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun physicalAssistantDrainDetachesMicrophoneAtFollowUpDeadlineWhileConsultFinishes() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-follow-up-consult")
      setPrivateField(manager, "realtimeSessionKey", "main")
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      manager.armPostAssistantFollowUpForTest()
      val coordinator = readPrivateField(manager, "realtimeAgentCoordinator") as RealtimeAgentCoordinator
      coordinator.beginSession(RealtimeAgentSession("relay-follow-up-consult", "main"))
      val runId = "talk-realtime-relay-consult:${System.currentTimeMillis()}:c35a4299-a535-433a-b2e6-fc8e0628c884"
      manager.handleGatewayEvent(
        "agent",
        """{"runId":"$runId","sessionKey":"main","stream":"lifecycle","ts":${System.currentTimeMillis()},"data":{"phase":"start"}}""",
      )
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()

      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS)

      assertEquals("relay-follow-up-consult", readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isListening.value)
      assertTrue(readPrivateField(manager, "realtimeMicDetachedForQuiet") as Boolean)

      advanceConversationClock(
        this,
        JarvisConversationLifecycle.STALLED_TIMEOUT_MS - JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS,
      )
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Stalled, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun newAudibleOutputCancelsTheUncommittedPostOutputTail() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-tail-replaced")
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session

      owner.onState(true, 0.4f, null, true, 0.4f)
      owner.onAudiblePresented()
      assertNotNull(readPrivateField(manager, "realtimePostOutputTail"))

      // A later provider chunk replaces the output epoch before any retained PCM can commit.
      owner.onState(true, 0.4f, null, true, 0.4f)
      assertNull(readPrivateField(manager, "realtimePostOutputTail"))
      assertTrue(readPrivateField(manager, "realtimeOutputPresentationPending") as Boolean)
      // This fixture leaves an audible stream active; its deadline worker belongs to that stream.
      owner.onState(false, null, null, false, null)
    }

  @Test
  fun wakeCaptureHoldRejectsAcknowledgementFramesAndAdmitsOnlyFramesBeginningAfterRelease() =
    runTest {
      val manager = createManager(scope = this)
      val sessionId = "relay-wake-hold"
      installRealtimeSession(manager, sessionId)
      setMutableStateFlow(manager, "_isListening", true)
      val inputGeneration = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      val token = manager.armRealtimeWakeCaptureHold()
      val ackEndedAtMs = SystemClock.elapsedRealtime() + 100L

      assertNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, ackEndedAtMs - 1L, byteArrayOf(1, 1)))
      assertFalse(manager.isCaptureAdmitted.value)
      assertTrue(manager.releaseRealtimeWakeCaptureHold(token, ackEndedAtMs))
      // A read that began during the ACK may return after it; its start boundary still rejects it.
      assertNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, ackEndedAtMs - 1L, byteArrayOf(2, 2)))
      assertEquals(
        "Live",
        checkNotNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, ackEndedAtMs, byteArrayOf(3, 3))).javaClass.simpleName,
      )
      assertTrue(manager.isCaptureAdmitted.value)
    }

  @Test
  fun wakeCaptureHoldIsGenerationOwnedAndCancellationCannotLeaveCaptureMuted() =
    runTest {
      val manager = createManager(scope = this)
      val sessionId = "relay-wake-hold-owner"
      installRealtimeSession(manager, sessionId)
      val inputGeneration = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      val staleToken = manager.armRealtimeWakeCaptureHold()
      val ownerToken = manager.armRealtimeWakeCaptureHold()
      val now = SystemClock.elapsedRealtime()

      assertFalse(manager.releaseRealtimeWakeCaptureHold(staleToken, now))
      assertNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, now, byteArrayOf(1, 1)))
      manager.cancelRealtimeWakeCaptureHold(ownerToken)
      assertEquals(
        "Live",
        checkNotNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, now + 1L, byteArrayOf(2, 2))).javaClass.simpleName,
      )
    }

  @Test
  fun quietDetachedRecorderCannotClassifyALateBlockingRead() =
    runTest {
      val manager = createManager(scope = this)
      val sessionId = "relay-quiet-late-read"
      installRealtimeSession(manager, sessionId)
      val generation = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      setPrivateField(manager, "realtimeMicDetachedForQuiet", true)

      assertNull(classifyRealtimeCapturedFrame(manager, sessionId, generation, SystemClock.elapsedRealtime(), byteArrayOf(1, 1)))
    }

  @Test
  fun postOutputTailRejectsStraddlingPcmThenFlushesOnlyPostEndFramesBeforeLiveInput() =
    runTest {
      val manager = createManager(scope = this)
      val sessionId = "relay-tail-order"
      installRealtimeSession(manager, sessionId)
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session
      val inputGeneration = (readPrivateField(manager, "audioInputGeneration") as AtomicLong).get()
      owner.onState(true, 0.4f, null, true, 0.4f)
      owner.onAudiblePresented()
      val tail = checkNotNull(readPrivateField(manager, "realtimePostOutputTail"))
      val beganAt = readPrivateField(tail, "beganAtMs") as Long
      val deadline = SystemClock.elapsedRealtime() + 5_000L
      setPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs", deadline)
      val straddling = byteArrayOf(1, 1)
      val first = byteArrayOf(2, 2)
      val second = byteArrayOf(3, 3)
      val final = byteArrayOf(4, 4)

      assertNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, beganAt - 1L, straddling))
      assertEquals("RetainedTailFrame", checkNotNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, beganAt, first)).javaClass.simpleName)
      assertEquals("RetainedTailFrame", checkNotNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, beganAt + 100L, second)).javaClass.simpleName)
      // Loud tail PCM must not locally extend the existing follow-up deadline.
      assertEquals(deadline, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))

      ShadowSystemClock.advanceBy(Duration.ofMillis(900))
      val packet = checkNotNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, beganAt + 900L, final))
      assertEquals("PostOutputTail", packet.javaClass.simpleName)
      @Suppress("UNCHECKED_CAST")
      val frames = readPrivateField(packet, "frames") as List<ByteArray>
      assertEquals(3, frames.size)
      assertArrayEquals(first, frames[0])
      assertArrayEquals(second, frames[1])
      assertArrayEquals(final, frames[2])
      assertEquals("Live", checkNotNull(classifyRealtimeCapturedFrame(manager, sessionId, inputGeneration, beganAt + 1_000L, byteArrayOf(5, 5))).javaClass.simpleName)
    }

  @Test
  fun longFollowUpStartedInsideWindowMayFinishButLateRoomTranscriptCannotRenewIt() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-follow-up-speech")
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      manager.armPostAssistantFollowUpForTest()
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()

      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS - 1_000L)
      manager.realtimeEvent("""{"relaySessionId":"relay-follow-up-speech","type":"transcript","role":"user","text":"Und","final":false}""")
      assertTrue((readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) > 0L)
      manager.recordRealtimeInputSpeechForTest("relay-follow-up-speech")
      repeat(7) {
        advanceConversationClock(this, 1_000L)
        manager.recordRealtimeInputSpeechForTest("relay-follow-up-speech")
      }
      advanceConversationClock(this, 1_000L)
      manager.realtimeEvent("""{"relaySessionId":"relay-follow-up-speech","type":"transcript","role":"user","text":"Und morgen?","final":true}""")

      assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))
      assertEquals("Und morgen?", manager.conversation.value.last().text)

      setPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs", SystemClock.elapsedRealtime() - 1L)
      setPrivateField(manager, "conversationPostAssistantFollowUpSpeechStartedAtMs", 0L)
      manager.recordRealtimeInputSpeechForTest("relay-follow-up-speech")
      assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpSpeechStartedAtMs"))
      manager.realtimeEvent("""{"relaySessionId":"relay-follow-up-speech","type":"transcript","role":"user","text":"Raumgespräch","final":true}""")

      assertEquals("Und morgen?", manager.conversation.value.last().text)
    }

  @Test
  fun physicallyDrainedSpeechStartsFollowUpEvenWhenProviderKeepsStreamingSilence() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-continuous-silence")
      setMutableStateFlow(manager, "_isEnabled", true)
      val owner = readPrivateField(manager, "realtimePlayoutSession") as RealtimePlayout.Session
      owner.onState(true, 0.4f, null, true, 0.4f)
      advanceConversationClock(this, 300L)
      owner.onAudiblePresented()
      owner.onAudibleDrained()
      val deadline = readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs") as Long
      assertTrue(deadline > SystemClock.elapsedRealtime())

      // Fresh zero-valued frames during the visual hangover cannot clear the
      // physical handoff and wait forever for the provider's silent track to end.
      owner.onState(true, 0f, null, true, 0f)
      assertEquals(deadline, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))
      repeat((JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS / 100L).toInt()) {
        advanceConversationClock(this, 100L)
        owner.onState(true, 0f, null, true, 0f)
      }
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun quietFollowUpPartialInsideHandoffAllowsItsDelayedFinalWithoutLocalLevelDetection() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-quiet-follow-up")
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      manager.armPostAssistantFollowUpForTest()
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()

      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS - 200L)
      manager.realtimeEvent("""{"relaySessionId":"relay-quiet-follow-up","type":"transcript","role":"user","text":"Und morgen","final":false}""")
      advanceConversationClock(this, 200L)
      assertEquals("relay-quiet-follow-up", readPrivateField(manager, "realtimeSessionId"))
      advanceConversationClock(this, 1_500L)
      manager.realtimeEvent("""{"relaySessionId":"relay-quiet-follow-up","type":"transcript","role":"user","text":"Und morgen um zehn?","final":true}""")

      assertEquals("Und morgen um zehn?", manager.conversation.value.last().text)
      assertEquals(0L, readPrivateField(manager, "conversationPostAssistantFollowUpDeadlineMs"))
      assertEquals("relay-quiet-follow-up", readPrivateField(manager, "realtimeSessionId"))
    }

  @Test
  fun partialTranscriptGraceCannotBeRenewedByRoomPartialsAfterHandoff() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-partial-grace")
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      manager.armPostAssistantFollowUpForTest()
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()

      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS - 200L)
      manager.realtimeEvent("""{"relaySessionId":"relay-partial-grace","type":"transcript","role":"user","text":"Eine Frage","final":false}""")
      val expires = readPrivateField(manager, "conversationPostAssistantFollowUpSpeechUntilMs")
      repeat(3) {
        advanceConversationClock(this, 1_000L)
        manager.realtimeEvent("""{"relaySessionId":"relay-partial-grace","type":"transcript","role":"user","text":"Raumgespräch geht weiter","final":false}""")
        assertEquals(expires, readPrivateField(manager, "conversationPostAssistantFollowUpSpeechUntilMs"))
      }
      advanceConversationClock(this, 300L)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun continuousFollowUpNoiseCannotOutliveItsFifteenSecondUtteranceCap() =
    runTest {
      val manager = createManager(scope = this)
      installRealtimeSession(manager, "relay-follow-up-cap")
      setMutableStateFlow(manager, "_isEnabled", true)
      setPrivateField(manager, "conversationAssistantAudibleOutputSeen", true)
      manager.armPostAssistantFollowUpForTest()
      manager.scheduleConversationIdleCheckForTest()
      runCurrent()

      advanceConversationClock(this, JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MS - 1_000L)
      manager.recordRealtimeInputSpeechForTest("relay-follow-up-cap")
      val startedAtMs = readPrivateField(manager, "conversationPostAssistantFollowUpSpeechStartedAtMs") as Long
      // The utterance began inside the handoff. Crossing the general seven-second
      // idle boundary must not cut off its finite completion grace.
      advanceConversationClock(this, 1_000L)
      manager.recordRealtimeInputSpeechForTest("relay-follow-up-cap")
      assertEquals("relay-follow-up-cap", readPrivateField(manager, "realtimeSessionId"))
      repeat(13) {
        advanceConversationClock(this, 1_000L)
        manager.recordRealtimeInputSpeechForTest("relay-follow-up-cap")
        assertEquals("relay-follow-up-cap", readPrivateField(manager, "realtimeSessionId"))
      }

      assertTrue(
        (readPrivateField(manager, "conversationPostAssistantFollowUpSpeechUntilMs") as Long) <=
          startedAtMs + JarvisConversationLifecycle.POST_ASSISTANT_FOLLOW_UP_MAX_UTTERANCE_MS,
      )
      // At the absolute cap, continuing room noise cannot postpone closure.
      advanceConversationClock(this, 1_000L)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertEquals(JarvisConversationCloseReason.Idle, manager.conversationLifecycle.value.closingReason)
    }

  @Test
  fun localizedOffStatusDoesNotBecomeRealtimeStartFailure() =
    runTest {
      val manager = createManager(scope = this)
      val turn =
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching {
            manager.runE2eRealtimeTurn(
              userText = "ignored",
              assistantText = "ignored",
              timeoutMs = 250L,
            )
          }.exceptionOrNull()
        }

      manager.stopAllCapture()
      NativeStringResources.install(RuntimeEnvironment.getApplication())
      try {
        NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("fr"))
        notifyNativeLocaleChanged()
        assertEquals("Désactivé", manager.statusText.value)
        advanceUntilIdle()
        assertTrue(turn.await() is TimeoutCancellationException)
      } finally {
        NativeStringResources.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        notifyNativeLocaleChanged()
      }
    }

  @Test
  fun realtimePlaybackMarkAcknowledgesAfterQueuedAudioBarrier() =
    runTest {
      val acknowledgements = mutableListOf<Pair<String, String>>()
      val manager =
        createManager(
          scope = backgroundScope,
          realtimePlaybackDispatcher = StandardTestDispatcher(testScheduler),
          realtimeMarkAcknowledger = { sessionId, markName ->
            acknowledgements += sessionId to markName
          },
        )
      installRealtimeSession(manager, "relay-1")

      manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"mark","markName":"audio-1"}""")
      runCurrent()

      assertEquals(listOf("relay-1" to "audio-1"), acknowledgements)
    }

  @Test
  fun realtimeTranscriptsPopulateVoiceConversation() {
    val manager = createRealtimeManager()

    manager.transcript("user", "hello")
    manager.transcript("user", "hello world", final = true)
    manager.transcript("assistant", "hi")
    manager.transcript("assistant", "hi there", final = true)

    assertEquals(
      listOf(
        VoiceConversationEntry(
          id = manager.conversation.value[0].id,
          role = VoiceConversationRole.User,
          text = "hello world",
        ),
        VoiceConversationEntry(
          id = manager.conversation.value[1].id,
          role = VoiceConversationRole.Assistant,
          text = "hi there",
        ),
      ),
      manager.conversation.value,
    )
  }

  @Test
  fun responseStartMarksAwaitingAgentUntilStatusMovesOn() {
    val manager = createRealtimeManager()

    assertFalse(manager.awaitingAgent.value)
    manager.transcript("user", "hello", final = true)
    assertFalse("A transcript does not prove response work has started", manager.awaitingAgent.value)
    manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"responseStarted","turnId":"response-turn"}""")
    assertTrue(manager.awaitingAgent.value)
    // Any later status transition clears the typed flag; forgetting it at a
    // new setStatus site fails safe instead of showing a stale Thinking wave.
    manager.transcript("assistant", "hi there", final = true)
    manager.stopAllCapture()
    assertFalse(manager.awaitingAgent.value)
  }

  @Test
  fun realtimeTranscriptDeltasAccumulateVoiceConversation() {
    val manager = createRealtimeManager()

    manager.transcript("assistant", "The")
    manager.transcript("assistant", " answer")

    val entry = manager.conversation.value.single()
    assertEquals("The answer", entry.text)
    assertTrue(entry.isStreaming)
  }

  @Test
  fun realtimeTranscriptFragmentsInsertWordSpacing() {
    val manager = createRealtimeManager()

    manager.transcript("user", "Turn off")
    manager.transcript("user", "the lights")

    val entry = manager.conversation.value.single()
    assertEquals("Turn off the lights", entry.text)
    assertTrue(entry.isStreaming)
  }

  @Test
  fun realtimeTranscriptFragmentsInsertSpacingAfterPunctuation() {
    val manager = createRealtimeManager()

    manager.transcript("assistant", "Ready.")
    manager.transcript("assistant", "What next?")

    val entry = manager.conversation.value.single()
    assertEquals("Ready. What next?", entry.text)
    assertTrue(entry.isStreaming)
  }

  @Test
  fun realtimeFinalTranscriptCanCompleteDeltaText() {
    val manager = createRealtimeManager()

    manager.transcript("assistant", "The")
    manager.transcript("assistant", " answer")
    manager.transcript("assistant", "The answer", final = true)

    val entry = manager.conversation.value.single()
    assertEquals("The answer", entry.text)
    assertFalse(entry.isStreaming)
  }

  @Test
  fun realtimeFinalTranscriptSnapshotReplacesDivergentPartialWithoutDuplicatingItsTail() {
    val manager = createRealtimeManager()
    val finalQuestion = "Wie viel ist 2 plus 3? Antworte nur mit der Zahl."

    manager.transcript("user", "Antworte nur mit der Zahl.")
    manager.transcript("user", finalQuestion, final = true)

    val entry = manager.conversation.value.single()
    assertEquals(finalQuestion, entry.text)
    assertFalse(entry.isStreaming)
  }

  @Test
  fun realtimeAssistantOutputSeparatesNextUserBubble() {
    val manager = createRealtimeManager()

    manager.transcript("user", "First request")
    manager.transcript("assistant", "Checking")
    manager.transcript("user", "Second request")

    val entries = manager.conversation.value
    assertEquals(3, entries.size)
    assertEquals(VoiceConversationRole.User, entries[0].role)
    assertEquals("First request", entries[0].text)
    assertFalse(entries[0].isStreaming)
    assertEquals(VoiceConversationRole.Assistant, entries[1].role)
    assertEquals("Checking", entries[1].text)
    assertFalse(entries[1].isStreaming)
    assertEquals(VoiceConversationRole.User, entries[2].role)
    assertEquals("Second request", entries[2].text)
    assertTrue(entries[2].isStreaming)
  }

  @Test
  fun realtimeUserTranscriptRewriteStaysInSameBubble() {
    val manager = createRealtimeManager()

    manager.transcript("user", "Can you tack")
    manager.transcript("user", "Can you check?", final = true)

    val entry = manager.conversation.value.single()
    assertEquals(VoiceConversationRole.User, entry.role)
    assertEquals("Can you check?", entry.text)
    assertFalse(entry.isStreaming)
  }

  @Test
  fun realtimeLateFinalUserTranscriptRewritesBubbleAfterAssistantStarts() {
    val manager = createRealtimeManager()

    manager.transcript("user", "Can you tack")
    manager.transcript("assistant", "Checking")
    manager.transcript("user", "Can you check?", final = true)

    val entries = manager.conversation.value
    assertEquals(2, entries.size)
    assertEquals(VoiceConversationRole.User, entries[0].role)
    assertEquals("Can you check?", entries[0].text)
    assertFalse(entries[0].isStreaming)
    assertEquals(VoiceConversationRole.Assistant, entries[1].role)
    assertEquals("Checking", entries[1].text)
  }

  @Test
  fun realtimeFinalNextUserAfterAssistantStartsCreatesNewBubble() {
    val manager = createRealtimeManager()

    manager.transcript("user", "First request")
    manager.transcript("assistant", "Checking")
    manager.transcript("user", "Second request", final = true)

    val entries = manager.conversation.value
    assertEquals(3, entries.size)
    assertEquals(VoiceConversationRole.User, entries[0].role)
    assertEquals("First request", entries[0].text)
    assertEquals(VoiceConversationRole.Assistant, entries[1].role)
    assertEquals("Checking", entries[1].text)
    assertEquals(VoiceConversationRole.User, entries[2].role)
    assertEquals("Second request", entries[2].text)
    assertFalse(entries[2].isStreaming)
  }

  @Test
  fun realtimeAlternatingTurnsStayInSeparateBubbles() {
    val manager = createRealtimeManager()

    manager.transcript("user", "Hey, what time is it?", final = true)
    manager.transcript("assistant", "Let me look into that for you. It's currently 7:55 PM UTC.", final = true)
    manager.transcript("user", "How's it going?", final = true)
    manager.transcript("assistant", "Great! Ready for the next task. What can I do for you?", final = true)
    manager.transcript("user", "Turn on the basement lights", final = true)
    manager.transcript("assistant", "Got it, let me check on that.", final = true)

    val entries = manager.conversation.value
    assertEquals(6, entries.size)
    assertEquals(VoiceConversationRole.User, entries[0].role)
    assertEquals("Hey, what time is it?", entries[0].text)
    assertEquals(VoiceConversationRole.Assistant, entries[1].role)
    assertEquals("Let me look into that for you. It's currently 7:55 PM UTC.", entries[1].text)
    assertEquals(VoiceConversationRole.User, entries[2].role)
    assertEquals("How's it going?", entries[2].text)
    assertEquals(VoiceConversationRole.Assistant, entries[3].role)
    assertEquals("Great! Ready for the next task. What can I do for you?", entries[3].text)
    assertEquals(VoiceConversationRole.User, entries[4].role)
    assertEquals("Turn on the basement lights", entries[4].text)
    assertEquals(VoiceConversationRole.Assistant, entries[5].role)
    assertEquals("Got it, let me check on that.", entries[5].text)
    assertTrue(entries.none { it.isStreaming })
  }

  @Test
  fun e2eRealtimeTurnUsesRelayTranscriptPath() =
    runTest {
      val manager = createManager(scope = backgroundScope)

      installRealtimeSession(manager, "relay-1")
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.runE2eRealtimeTurn(
        userText = "voice e2e user",
        assistantText = "voice e2e assistant",
        timeoutMs = 1_000L,
      )

      val entries = manager.conversation.value
      assertEquals(2, entries.size)
      assertEquals(VoiceConversationRole.User, entries[0].role)
      assertEquals("voice e2e user", entries[0].text)
      assertEquals(VoiceConversationRole.Assistant, entries[1].role)
      assertEquals("voice e2e assistant", entries[1].text)
      assertTrue(entries.none { it.isStreaming })
    }

  @Test
  fun realtimeStartWithoutGatewayTurnsTalkOff() =
    runTest {
      val stoppedByRelay = AtomicBoolean(false)
      val manager =
        createManager(
          scope = this,
          isConnected = { false },
          onStoppedByRelay = { stoppedByRelay.set(true) },
        )

      manager.setEnabled(true)
      advanceUntilIdle()

      assertFalse(manager.isEnabled.value)
      assertFalse(manager.isListening.value)
      assertEquals("Gateway not connected", manager.statusText.value)
      assertTrue(stoppedByRelay.get())
    }

  @Test
  fun talkConfigChangedRefreshesNextUseWithoutStoppingCapture() =
    runBlocking {
      installSpeechRecognitionService()
      val config = AtomicReference(nativeTalkConfig("de-DE"))
      withStartedTalk(responseForRequest = { request, _ ->
        config.get().takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
      }) { proof ->
        val recognizer = currentRecognizer()
        config.set(nativeTalkConfig("en-US"))
        proof.manager.handleGatewayEvent("config.changed", "{}")

        assertTrue(proof.manager.isListening.value)
        assertFalse(recognizer.isDestroyed)
        val language = proof.scope.async { proof.manager.resolveRealtimeLanguageHint("fr") }
        awaitTalkWork(proof) { language.isCompleted }

        assertEquals("en", language.await())
        assertTrue(currentRecognizer() === recognizer)
      }
    }

  @Test
  fun removedSpeechInterruptionSettingDoesNotKeepInterruptingPlayback() =
    runBlocking {
      installSpeechRecognitionService()
      val config = AtomicReference(nativeTalkConfig("de-DE", interrupt = true))
      withStartedTalk(responseForRequest = { request, _ ->
        config.get().takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
      }) { proof ->
        config.set(nativeTalkConfig("de-DE"))
        val refresh = proof.scope.async { proof.manager.refreshConfig() }
        awaitTalkWork(proof) { refresh.isCompleted }
        refresh.await()
        val playback = proof.scope.async { proof.manager.speakAssistantReply("Synthetic spoken response") }
        awaitTalkWork(proof) { proof.synthesizer.requested.isCompleted }
        completeRemoteSynthesis(proof.synthesizer)
        proof.scheduler.runCurrent()
        assertTrue(proof.manager.isSpeaking.value)

        currentRecognizer().triggerOnPartialResults(recognitionResults("Different user words"))
        proof.scheduler.runCurrent()

        assertTrue("Removing interruptOnSpeech must restore the Android default", proof.manager.isSpeaking.value)
        proof.player.finished.complete(Unit)
        proof.scheduler.runCurrent()
        playback.await()
      }
    }

  @Test
  fun invalidatedConfigResponseCannotPopulateNextUseCache() = verifyConfigResponseOwnership(loadNewerBeforeRelease = false)

  @Test
  fun explicitRefreshRetiresOlderConfigBeforeWaitingForItsResponse() = verifyConfigResponseOwnership(loadNewerBeforeRelease = true)

  @Test
  fun configConsumerWaitsForTheNewerRefreshBeforeUsingItsSettings() =
    runBlocking {
      installSpeechRecognitionService()
      val holdReads = AtomicBoolean(false)
      val held = ConcurrentLinkedQueue<Pair<String, WebSocket>>()
      withStartedTalk(
        responseForRequest = { request, _ ->
          nativeTalkConfig("de-DE").takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
        },
        interceptRequest = { request, socket ->
          val hold = request.getValue("method").jsonPrimitive.content == "talk.config" && holdReads.get()
          if (hold) held.add(request.getValue("id").jsonPrimitive.content to socket)
          hold
        },
      ) { proof ->
        holdReads.set(true)
        proof.manager.handleGatewayEvent("config.changed", "{}")
        val language = proof.scope.async { proof.manager.resolveRealtimeLanguageHint("fr") }
        awaitTalkWork(proof) { held.isNotEmpty() }
        val (oldId, socket) = held.remove()
        val refresh = proof.scope.async { proof.manager.refreshConfig() }
        proof.scheduler.runCurrent()
        socket.send("""{"type":"res","id":"$oldId","ok":true,"payload":${nativeTalkConfig("de-DE")}}""")
        awaitTalkWork(proof) { held.isNotEmpty() }
        assertEquals(1, held.size)
        // The same-socket health reply is ordered after the old config response;
        // drain its continuation before checking that the consumer still waits.
        val barrier = proof.scope.async { proof.session.request("health", "{}") }
        awaitTalkWork(proof) { barrier.isCompleted }
        barrier.await()
        proof.scheduler.runCurrent()
        assertFalse("A superseded config read must not release its consumer with old settings", language.isCompleted)

        val (newId, newSocket) = held.remove()
        newSocket.send("""{"type":"res","id":"$newId","ok":true,"payload":${nativeTalkConfig("en-US")}}""")
        awaitTalkWork(proof) { language.isCompleted && refresh.isCompleted }
        assertEquals("en", language.await())
        refresh.await()
      }
    }

  @Test
  fun failedCurrentConfigReadReturnsOnceAndCanRetryOnNextUse() =
    runBlocking {
      installSpeechRecognitionService()
      val failReads = AtomicBoolean(false)
      val failures = AtomicLong()
      withStartedTalk(
        responseForRequest = { request, _ ->
          nativeTalkConfig("de-DE").takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
        },
        interceptRequest = { request, socket ->
          val fail = request.getValue("method").jsonPrimitive.content == "talk.config" && failReads.get()
          if (fail) {
            failures.incrementAndGet()
            val id = request.getValue("id").jsonPrimitive.content
            socket.send("""{"type":"res","id":"$id","ok":false,"error":{"code":"UNAVAILABLE","message":"Synthetic config unavailable"}}""")
          }
          fail
        },
      ) { proof ->
        failReads.set(true)
        proof.manager.handleGatewayEvent("config.changed", "{}")
        repeat(2) { index ->
          val language = proof.scope.async { proof.manager.resolveRealtimeLanguageHint("fr") }
          awaitTalkWork(proof) { language.isCompleted }
          assertTrue("A current config failure must not retry inside the same use", language.isCompleted)
          assertEquals("fr", language.await())
          assertEquals(index + 1L, failures.get())
        }
      }
    }

  private fun verifyConfigResponseOwnership(loadNewerBeforeRelease: Boolean) =
    runBlocking {
      installSpeechRecognitionService()
      val config = AtomicReference(nativeTalkConfig("de-DE"))
      val holdNext = AtomicBoolean(false)
      val held = ConcurrentLinkedQueue<Pair<String, WebSocket>>()
      withStartedTalk(
        responseForRequest = { request, _ ->
          config.get().takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
        },
        interceptRequest = { request, socket ->
          val hold = request.getValue("method").jsonPrimitive.content == "talk.config" && holdNext.compareAndSet(true, false)
          if (hold) held.add(request.getValue("id").jsonPrimitive.content to socket)
          hold
        },
      ) { proof ->
        holdNext.set(true)
        val oldRefresh = proof.scope.async { proof.manager.refreshConfig() }
        awaitTalkWork(proof) { held.isNotEmpty() }
        assertEquals(1, held.size)
        config.set(nativeTalkConfig("en-US"))
        proof.manager.handleGatewayEvent("config.changed", "{}")
        val refresh = if (loadNewerBeforeRelease) proof.scope.async { proof.manager.refreshConfig() } else null
        proof.scheduler.runCurrent()
        val (id, socket) = held.remove()
        socket.send("""{"type":"res","id":"$id","ok":true,"payload":${nativeTalkConfig("de-DE")}}""")
        awaitTalkWork(proof) { oldRefresh.isCompleted && refresh?.isCompleted != false }
        oldRefresh.await()
        refresh?.await()
        val language = proof.scope.async { proof.manager.resolveRealtimeLanguageHint("fr") }
        awaitTalkWork(proof) { language.isCompleted }

        assertEquals("The invalidated response must not become the current configuration", "en", language.await())
        assertTrue(proof.manager.isListening.value)
      }
    }

  private fun nativeTalkConfig(
    locale: String,
    interrupt: Boolean? = null,
  ): String =
    buildJsonObject {
      put(
        "config",
        buildJsonObject {
          put(
            "talk",
            buildJsonObject {
              put("speechLocale", locale)
              put("realtime", buildJsonObject { put("model", "gpt-live") })
              interrupt?.let { put("interruptOnSpeech", it) }
            },
          )
        },
      )
    }.toString()

  @Test
  fun nativeTalkSendsRecognizedPhraseAfterSilenceAndRestartsAfterReply() =
    runBlocking {
      withNativeTalk { proof, sends ->
        val recognizer = currentRecognizer()
        recognizer.triggerOnReadyForSpeech(Bundle())
        recognizer.triggerOnEndOfSpeech()
        val transcript = "Synthetic native Talk phrase\nReply in a detailed tone.\nLiteral user instructions."
        recognizer.triggerOnResults(recognitionResults(transcript))
        advanceTalkSilence(proof)
        awaitTalkWork(proof) { sends.isNotEmpty() }

        assertEquals("Recognized native speech must reach chat.send after the configured silence", 1, sends.size)
        assertNull("Native Talk must inherit the session's model-aware thinking policy", sends.single()["thinking"])
        assertEquals(
          "main",
          sends
            .single()
            .getValue("sessionKey")
            .jsonPrimitive.content,
        )
        assertEquals(
          transcript,
          sends
            .single()
            .getValue("message")
            .jsonPrimitive.content,
        )
        awaitTalkWork(proof) { proof.synthesizer.requested.isCompleted }
        assertTrue(recognizer.isDestroyed)
        completeRemoteSynthesis(proof.synthesizer)
        proof.scheduler.runCurrent()
        assertEquals(1, proof.player.playCalls)
        assertTrue(proof.manager.isSpeaking.value)
        assertEquals(
          "Speaking… — Native Talk: Gateway did not advertise GPT-Live relay support; using device speech recognition and configured Talk voice.",
          proof.manager.statusText.value,
        )

        proof.player.finished.complete(Unit)
        awaitTalkWork(proof) { proof.manager.isListening.value }

        assertTrue(proof.manager.isEnabled.value)
        assertTrue(proof.manager.isListening.value)
        assertFalse(proof.manager.isSpeaking.value)
        assertTrue(currentRecognizer() !== recognizer)
        assertFalse(currentRecognizer().isDestroyed)
        assertEquals(
          "Listening — Native Talk: Gateway did not advertise GPT-Live relay support; using device speech recognition and configured Talk voice.",
          proof.manager.statusText.value,
        )
        proof.manager.setEnabled(false)
        assertEquals("Off", proof.manager.statusText.value)
        assertNull(proof.manager.failureNotice.value)
      }
    }

  @Test
  fun publishedGatewayRelayContractsStartTheAdvertisedTransport() =
    runBlocking {
      installSpeechRecognitionService()
      for (fixture in realtimeRelayContractCases()) {
        val id = fixture.getValue("id").jsonPrimitive.content
        val realtime = fixture.getValue("realtime").jsonPrimitive.boolean
        val requests = ConcurrentLinkedQueue<JsonObject>()
        val payload = buildJsonObject { put("config", fixture.getValue("config")) }.toString()
        withStartedTalk(responseForRequest = { request, _ ->
          requests.add(request)
          payload.takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
        }) { proof ->
          val creates = requests.filter { it.getValue("method").jsonPrimitive.content == "talk.session.create" }
          assertEquals("$id must use the Gateway-advertised transport", if (realtime) 1 else 0, creates.size)
          if (realtime) {
            val params = creates.single().getValue("params").jsonObject
            assertEquals("gateway-relay", params.getValue("transport").jsonPrimitive.content)
            assertFalse("The Gateway retains model selection", params.containsKey("model"))
            assertEquals("Listening", proof.manager.statusText.value)
            val track = startRealtimeAudio(proof)
            finishRealtimeAudio(proof, track, clear = true)
          } else {
            assertTrue(
              "$id explains native speech startup",
              proof.manager.statusText.value
                .contains("Native Talk:"),
            )
          }
          assertNull(proof.manager.failureNotice.value)
          assertFalse(requests.any { it.getValue("method").jsonPrimitive.content in setOf("chat.send", "talk.speak") })
          assertFalse("Realtime audio must not synthesize a native TTS reply", proof.synthesizer.requested.isCompleted)
        }
      }
    }

  private fun realtimeRelayContractCases(): List<JsonObject> {
    val fixture =
      generateSequence(java.io.File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .map { java.io.File(it, "test/fixtures/talk-realtime-relay-contract.json") }
        .first { it.isFile }
    return Json
      .parseToJsonElement(fixture.readText())
      .jsonObject
      .getValue("cases")
      .jsonArray
      .map { it.jsonObject }
  }

  @Test
  fun activeNativeRouteSurvivesConfigRefreshUntilRelayRestart() =
    runBlocking {
      installSpeechRecognitionService()
      val config = AtomicReference(nativeTalkConfig("en-US"))
      withStartedTalk(responseForRequest = { request, _ ->
        config.get().takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
      }) { proof ->
        val nativeStatus = proof.manager.statusText.value
        assertTrue(nativeStatus.contains("Gateway did not advertise GPT-Live relay support"))
        config.set("""{"config":{"talk":{"realtime":{"model":"gpt-realtime-2.1"}}}}""")
        val refresh = proof.scope.async { proof.manager.refreshConfig() }
        awaitTalkWork(proof) { refresh.isCompleted }
        refresh.await()
        currentRecognizer().triggerOnReadyForSpeech(Bundle())
        assertEquals("Refreshing settings must not relabel the running native loop", nativeStatus, proof.manager.statusText.value)

        proof.manager.setEnabled(false)
        assertEquals("Off", proof.manager.statusText.value)
        proof.manager.setEnabled(true)
        awaitTalkWork(proof) { proof.manager.isListening.value }
        assertEquals("The replacement relay must not inherit a native fallback reason", "Listening", proof.manager.statusText.value)
        assertNull(proof.manager.failureNotice.value)
      }
    }

  @Test
  fun nativeTalkKeepsRecognitionOpenFromEndOfSpeechUntilResults() =
    runBlocking {
      withNativeTalk { proof, sends ->
        val recognizer = currentRecognizer()
        val session = recognizer.lastRecognizerIntent
        recognizer.triggerOnReadyForSpeech(Bundle())
        // Google's recognizer delivers the final hypothesis as a partial at end of speech and
        // reports onResults only when the session closes, after the Talk silence window.
        recognizer.triggerOnPartialResults(recognitionResults("Synthetic end-of-speech phrase"))
        recognizer.triggerOnEndOfSpeech()
        advanceTalkSilence(proof)
        awaitTalkWork(proof) { sends.isNotEmpty() }

        assertSame(
          "End of speech must not cancel and restart the session before its results arrive",
          session,
          recognizer.lastRecognizerIntent,
        )
        assertTrue(
          sends
            .single()
            .getValue("message")
            .jsonPrimitive.content
            .endsWith("Synthetic end-of-speech phrase"),
        )
      }
    }

  @Test
  fun nativeStopThenStartKeepsReplacementAndRejectsRetiredResults() =
    runBlocking {
      withNativeTalk { proof, sends ->
        val retired = currentRecognizer()
        withContext(Dispatchers.Default) { proof.manager.setEnabled(false) }
        proof.manager.setEnabled(true)
        val statusBeforeRetiredCallback = proof.manager.statusText.value
        retired.triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(statusBeforeRetiredCallback, proof.manager.statusText.value)
        // Replacement capture waits for physical retirement of the old recognizer.
        awaitTalkWork(proof) { proof.manager.isListening.value }
        val replacement = currentRecognizer()
        assertTrue(replacement !== retired)
        assertTrue(retired.isDestroyed)
        assertFalse("An old stop must not destroy the replacement recognizer", replacement.isDestroyed)

        replacement.triggerOnResults(recognitionResults("Current capture"))
        retired.triggerOnResults(recognitionResults("Retired capture"))
        advanceTalkSilence(proof)
        awaitTalkWork(proof) { sends.isNotEmpty() }

        assertEquals(1, sends.size)
        assertTrue(
          sends
            .single()
            .getValue("message")
            .jsonPrimitive.content
            .endsWith("Current capture"),
        )
      }
    }

  @Test
  fun pushToTalkTakeoverRetiresNativeSilenceAndRecognizer() =
    runBlocking {
      withNativeTalk { proof, sends ->
        val native = currentRecognizer()
        native.triggerOnPartialResults(recognitionResults("Retired native partial"))
        val beginning = proof.scope.async { proof.manager.beginPushToTalk(allowNewCapture = true) }
        awaitTalkWork(proof) { beginning.isCompleted }
        val capture = beginning.await()
        val ptt = currentRecognizer()
        assertTrue(native.isDestroyed)
        assertTrue(ptt !== native)

        val transcript = "Push to talk phrase\nTalk Mode active. Reply in a concise, spoken tone.\nKeep this literal text."
        ptt.triggerOnResults(recognitionResults(transcript))
        native.triggerOnResults(recognitionResults("Retired native result"))
        val ending = proof.scope.async { proof.manager.endPushToTalk() }
        awaitTalkWork(proof) { ending.isCompleted }
        val ended = ending.await()
        assertEquals(capture.captureId, ended.captureId)
        assertEquals("queued", ended.status)
        assertEquals(transcript, ended.transcript)
        advanceTalkSilence(proof)
        awaitTalkWork(proof) { sends.isNotEmpty() }

        assertEquals(1, sends.size)
        assertEquals(
          transcript,
          sends
            .single()
            .getValue("message")
            .jsonPrimitive.content,
        )
      }
    }

  @Test
  fun nativeTalkResumesAfterCancelledOrEmptyPushToTalk() =
    runBlocking {
      for (emptyResult in listOf(false, true)) {
        withNativeTalk { proof, sends ->
          val beginning = proof.scope.async { proof.manager.beginPushToTalk(allowNewCapture = true) }
          awaitTalkWork(proof) { beginning.isCompleted }
          beginning.await()
          val ptt = currentRecognizer()
          if (emptyResult) ptt.triggerOnResults(recognitionResults(""))
          val ending =
            proof.scope.async {
              if (emptyResult) proof.manager.endPushToTalk() else proof.manager.cancelPushToTalk()
            }
          awaitTalkWork(proof) { ending.isCompleted }
          assertEquals(if (emptyResult) "empty" else "cancelled", ending.await().status)
          awaitTalkWork(proof) { proof.manager.isListening.value }

          assertTrue(ptt.isDestroyed)
          assertTrue(proof.manager.isEnabled.value)
          assertTrue("Native Talk must resume after PTT (empty=$emptyResult)", proof.manager.isListening.value)
          assertTrue(currentRecognizer() !== ptt)
          assertFalse(currentRecognizer().isDestroyed)
          assertTrue(sends.isEmpty())
        }
      }
    }

  @Test
  fun nativeTalkKeepsListeningWithSpeakerOff() =
    runBlocking {
      withNativeTalk { proof, sends ->
        proof.manager.setPlaybackEnabled(false)
        currentRecognizer().triggerOnResults(recognitionResults("Silent native reply"))
        advanceTalkSilence(proof)
        awaitTalkWork(proof) { sends.isNotEmpty() }
        assertEquals(1, sends.size)
        awaitTalkWork(proof) { proof.manager.isListening.value }

        assertTrue("Speaker-off must not stop native capture after its reply", proof.manager.isListening.value)
        assertTrue(proof.manager.isEnabled.value)
        assertFalse(proof.synthesizer.requested.isCompleted)
        assertEquals(0, proof.player.playCalls)
      }
    }

  @Test
  fun mutingNativeReplyDoesNotStopCapture() =
    runBlocking {
      for (duringPreparation in listOf(true, false)) {
        withNativeTalk { proof, sends ->
          currentRecognizer().triggerOnResults(recognitionResults("Mute this native reply"))
          advanceTalkSilence(proof)
          awaitTalkWork(proof) { proof.synthesizer.requested.isCompleted }
          assertEquals(1, sends.size)
          assertTrue(proof.synthesizer.requested.isCompleted)
          if (!duringPreparation) {
            completeRemoteSynthesis(proof.synthesizer)
            proof.scheduler.runCurrent()
            assertEquals(1, proof.player.playCalls)
          }

          proof.manager.setPlaybackEnabled(false)
          proof.scheduler.runCurrent()
          awaitTalkWork(proof) { proof.manager.isListening.value }

          assertTrue("Muting playback must not retire native capture (preparing=$duringPreparation)", proof.manager.isListening.value)
          assertTrue(proof.manager.isEnabled.value)
          assertFalse(proof.manager.isSpeaking.value)
          assertEquals(0, proof.callbackDepth())
        }
      }
    }

  @Test
  fun stoppingNativeCaptureWhileMutingDoesNotRestartIt() =
    runBlocking {
      withNativeTalk { proof, sends ->
        currentRecognizer().triggerOnResults(recognitionResults("Stop this native turn"))
        advanceTalkSilence(proof)
        awaitTalkWork(proof) { proof.synthesizer.requested.isCompleted }
        assertEquals(1, sends.size)
        assertTrue(proof.synthesizer.requested.isCompleted)

        proof.manager.setPlaybackEnabled(false)
        proof.manager.setEnabled(false)
        proof.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(proof.manager.isEnabled.value)
        assertFalse(proof.manager.isListening.value)
        assertFalse(proof.manager.isSpeaking.value)
        assertTrue(currentRecognizer().isDestroyed)
        assertEquals(0, proof.callbackDepth())
      }
    }

  @Test
  fun pushToTalkDuringNativeReplyKeepsTheNewCapture() =
    runBlocking {
      withNativeTalk { proof, sends ->
        currentRecognizer().triggerOnResults(recognitionResults("Interrupt this native reply"))
        advanceTalkSilence(proof)
        awaitTalkWork(proof) { proof.synthesizer.requested.isCompleted }
        assertEquals(1, sends.size)
        assertTrue(proof.synthesizer.requested.isCompleted)
        completeRemoteSynthesis(proof.synthesizer)
        proof.scheduler.runCurrent()
        assertTrue(proof.manager.isSpeaking.value)

        val beginning = proof.scope.async { proof.manager.beginPushToTalk(allowNewCapture = true) }
        awaitTalkWork(proof) { beginning.isCompleted }
        val capture = beginning.await()
        val ptt = currentRecognizer()
        assertTrue(proof.manager.isListening.value)
        assertEquals(capture.captureId, proof.manager.activePushToTalkCaptureId)
        ptt.triggerOnResults(recognitionResults("Current push to talk phrase"))
        assertFalse("The PTT recognizer must still own its delivered result", proof.manager.isListening.value)
        val ending = proof.scope.async { proof.manager.endPushToTalk() }
        awaitTalkWork(proof) { ending.isCompleted }

        assertEquals("Current push to talk phrase", ending.await().transcript)
      }
    }

  private suspend fun withNativeTalk(block: suspend (RealtimePlaybackProof, ConcurrentLinkedQueue<JsonObject>) -> Unit) {
    installSpeechRecognitionService()
    val sends = ConcurrentLinkedQueue<JsonObject>()
    val relayCreates = ConcurrentLinkedQueue<JsonObject>()
    withStartedTalk(
      responseForRequest = { request, _ ->
        when (request.getValue("method").jsonPrimitive.content) {
          "talk.config" -> {
            """{"config":{"talk":{"realtime":{"model":"gpt-live"},"silenceTimeoutMs":800}}}"""
          }

          "talk.session.create" -> {
            relayCreates.add(request)
            null
          }

          "chat.send" -> {
            sends.add(request.getValue("params").jsonObject)
            """{"runId":"native-talk-turn","status":"ok"}"""
          }

          "chat.history" -> {
            """{"messages":[{"role":"assistant","content":[{"type":"text","text":"Synthetic native reply"}]}]}"""
          }

          else -> {
            null
          }
        }
      },
    ) { proof ->
      assertEquals(
        "Listening — Native Talk: Gateway did not advertise GPT-Live relay support; using device speech recognition and configured Talk voice.",
        proof.manager.statusText.value,
      )
      assertTrue(relayCreates.isEmpty())
      block(proof, sends)
    }
  }

  private fun currentRecognizer(): ShadowSpeechRecognizer {
    shadowOf(Looper.getMainLooper()).idle()
    return shadowOf(checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
  }

  private fun recognitionResults(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

  private fun advanceTalkSilence(proof: RealtimePlaybackProof) {
    repeat(12) {
      ShadowSystemClock.advanceBy(Duration.ofMillis(100))
      proof.scheduler.advanceTimeBy(100)
      shadowOf(Looper.getMainLooper()).idle()
      proof.scheduler.runCurrent()
    }
  }

  private suspend fun awaitTalkWork(
    proof: RealtimePlaybackProof,
    ready: () -> Boolean,
  ) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!ready() && System.nanoTime() < deadline) {
      shadowOf(Looper.getMainLooper()).idle()
      proof.scheduler.runCurrent()
      withContext(Dispatchers.Default) { delay(10) }
    }
    check(ready()) { "Talk work did not complete while driving its test dispatcher and Android looper" }
  }

  @Test
  fun textReadyDoesNotEnterSpeakingUntilAudioPlaybackStarts() =
    runTest {
      val talkSpeakClient = FakeTalkSpeechSynthesizer()
      val talkAudioPlayer = FakeTalkAudioPlayer()
      val manager = createManager(talkSpeakClient = talkSpeakClient, talkAudioPlayer = talkAudioPlayer)

      val job = launch { manager.speakAssistantReply("hello") }
      talkSpeakClient.requested.await()

      assertEquals("Generating voice…", manager.statusText.value)
      assertFalse(manager.isSpeaking.value)

      completeRemoteSynthesis(talkSpeakClient)
      talkAudioPlayer.started.await()

      assertEquals("Speaking…", manager.statusText.value)
      assertTrue(manager.isSpeaking.value)

      talkAudioPlayer.finished.complete(Unit)
      job.join()
    }

  @Test
  fun localPlaybackReleasesItsAudioFocusForEveryTerminalOutcome() =
    runTest {
      for (outcome in listOf("completed", "failed", "focus-lost", "cancelled")) {
        withStartedLocalPlayback { manager, playback, player ->
          val audio = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
          val focus = checkNotNull(audio.lastAudioFocusRequest.audioFocusRequest)
          when (outcome) {
            "completed" -> player.finished.complete(Unit)
            "failed" -> player.finished.completeExceptionally(IllegalStateException("speaker failed"))
            "focus-lost" -> audio.lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            "cancelled" -> manager.stopTts()
          }
          runCurrent()
          assertTrue("Playback must finish after $outcome", playback.isCompleted)
          assertFalse(manager.isSpeaking.value)
          assertSame("Focus must return after $outcome", focus, audio.lastAbandonedAudioFocusRequest)
        }
      }
    }

  @Test
  fun cancelledPlaybackCompletionKeepsReplacementSpeaking() =
    runTest {
      val audio = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
      val synthesizer = FakeTalkSpeechSynthesizer()
      completeRemoteSynthesis(synthesizer)
      val player = FakeTalkAudioPlayer()
      val managerJob = SupervisorJob()
      var callbackDepth = 0
      val manager =
        createManager(
          talkSpeakClient = synthesizer,
          talkAudioPlayer = player,
          scope = CoroutineScope(coroutineContext + managerJob),
          onBeforeSpeak = { callbackDepth += 1 },
          onAfterSpeak = { callbackDepth -= 1 },
        )
      withMain {
        val first = launch { manager.speakAssistantReply("First reply") }
        var replacement: Job? = null
        try {
          runCurrent()
          assertEquals(1, player.playCalls)
          assertEquals(1, callbackDepth)
          assertTrue(manager.isSpeaking.value)
          val firstFocus = checkNotNull(audio.lastAudioFocusRequest)

          // Cancellation queues the old caller's cleanup; the replacement may
          // start before that cleanup runs on another dispatcher.
          val next = launch(start = CoroutineStart.UNDISPATCHED) { manager.speakAssistantReply("Replacement reply") }
          replacement = next
          assertEquals(2, player.playCalls)
          assertEquals(2, callbackDepth)
          assertTrue(first.isCancelled)
          assertFalse(first.isCompleted)
          assertTrue(manager.isSpeaking.value)
          val replacementFocus = checkNotNull(audio.lastAudioFocusRequest.audioFocusRequest)
          firstFocus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

          runCurrent()
          assertTrue(first.isCompleted)
          assertTrue(next.isActive)
          assertFalse(player.finished.isCompleted)
          assertEquals(1, callbackDepth)
          assertTrue("The replacement is still playing after the cancelled caller finishes", manager.isSpeaking.value)

          player.finished.complete(Unit)
          next.join()
          assertFalse(manager.isSpeaking.value)
          assertEquals(0, callbackDepth)
          assertSame(replacementFocus, audio.lastAbandonedAudioFocusRequest)
        } finally {
          manager.stopAllCapture()
          first.cancelAndJoin()
          replacement?.cancelAndJoin()
          managerJob.cancelAndJoin()
          runCurrent()
          shadowOf(Looper.getMainLooper()).idle()
          runCurrent()
        }
      }
    }

  @Test
  fun localPreparationKeepsRealtimePlaybackSpeaking() =
    runBlocking {
      withStartedTalk { proof ->
        val track = startRealtimeAudio(proof)
        val local = proof.scope.launch { proof.manager.speakAssistantReply("Local reply") }
        proof.scheduler.runCurrent()

        assertTrue(proof.synthesizer.requested.isCompleted)
        assertFalse(proof.synthesizer.result.isCompleted)
        assertTrue(local.isActive)
        assertEquals(1, proof.callbackDepth())
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertTrue("Preparing a local reply must not hide realtime playback", proof.manager.isSpeaking.value)
      }
    }

  @Test
  fun localCompletionKeepsRealtimePlaybackSpeaking() =
    runBlocking {
      withStartedTalk { proof ->
        completeRemoteSynthesis(proof.synthesizer)
        val local = proof.scope.launch { proof.manager.speakAssistantReply("Local reply") }
        proof.scheduler.runCurrent()
        assertEquals(1, proof.player.playCalls)
        val track = startRealtimeAudio(proof)

        proof.player.finished.complete(Unit)
        proof.scheduler.runCurrent()

        assertTrue(local.isCompleted)
        assertEquals(0, proof.callbackDepth())
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
        assertTrue("Completing a local reply must not hide realtime playback", proof.manager.isSpeaking.value)
      }
    }

  @Test
  fun realtimeClearKeepsLocalPlaybackSpeaking() = assertRealtimeEndKeepsLocalPlaybackSpeaking(clear = true)

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun realtimeIdleKeepsLocalPlaybackSpeaking() = assertRealtimeEndKeepsLocalPlaybackSpeaking(clear = false)

  private fun assertRealtimeEndKeepsLocalPlaybackSpeaking(clear: Boolean) =
    runBlocking {
      withStartedTalk { proof ->
        val track = startRealtimeAudio(proof)
        completeRemoteSynthesis(proof.synthesizer)
        val local = proof.scope.launch { proof.manager.speakAssistantReply("Local reply") }
        proof.scheduler.runCurrent()
        assertEquals(1, proof.player.playCalls)
        assertTrue(proof.manager.isSpeaking.value)
        val localStops = proof.player.stopCalls

        finishRealtimeAudio(proof, track, clear)

        assertTrue(local.isActive)
        assertFalse(proof.player.finished.isCompleted)
        assertEquals(localStops, proof.player.stopCalls)
        assertEquals(1, proof.callbackDepth())
        assertTrue("Ending realtime output must not hide the local reply", proof.manager.isSpeaking.value)
        proof.player.finished.complete(Unit)
        proof.scheduler.runCurrent()
        assertTrue(local.isCompleted)
        assertFalse(proof.manager.isSpeaking.value)
      }
    }

  private fun finishRealtimeAudio(
    proof: RealtimePlaybackProof,
    track: AudioTrack,
    clear: Boolean,
  ) {
    if (clear) {
      proof.manager.handleGatewayEvent(
        "talk.event",
        """{"relaySessionId":"playback-relay","type":"clear","talkEvent":{"turnId":"realtime-turn"}}""",
      )
      proof.scheduler.runCurrent()
      assertEquals(AudioTrack.STATE_UNINITIALIZED, track.state)
    } else {
      proof.manager.realtimeEvent(audioDoneEvent("realtime-turn"))
      proof.scheduler.runCurrent()
      val frames = proof.writes.sumOf { it.second.size } / 2
      assertEquals(frames, track.playbackHeadPosition)
      ShadowSystemClock.advanceBy(Duration.ofMillis(frames * 1_000L / 24_000 + 20))
      proof.scheduler.advanceTimeBy(20)
    }
    proof.scheduler.runCurrent()
  }

  private fun realtimeAudiblePcm(): ByteArray =
    ByteArray(REALTIME_AUDIBLE_PCM_BYTES) { if (it % 2 == 0) 0x20 else 0x01 }

  private fun audioDoneEvent(turnId: String): String =
    """{"relaySessionId":"playback-relay","type":"audioDone","talkEvent":{"type":"turn.ended","turnId":"$turnId","final":true}}"""

  private fun startRealtimeAudio(proof: RealtimePlaybackProof): AudioTrack {
    val pcm = realtimeAudiblePcm()
    val priorWrites = proof.writes.size
    proof.manager.handleGatewayEvent(
      "talk.event",
      """{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"realtime-turn"},"audioBase64":"${Base64.encodeToString(pcm, Base64.NO_WRAP)}"}""",
    )
    proof.scheduler.runCurrent()
    assertEquals(priorWrites + 1, proof.writes.size)
    val (track, written, format) = proof.writes.last()
    assertArrayEquals(pcm, written)
    assertEquals(24_000, format.sampleRate)
    assertEquals(AudioFormat.ENCODING_PCM_16BIT, format.encoding)
    assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.playState)
    assertTrue(proof.manager.isSpeaking.value)
    return track
  }

  @Test
  @Config(shadows = [DeadRealtimeAudioTrack::class])
  fun realtimeDeviceFailureStopsTheRelayWithVisibleFailure() =
    runBlocking {
      withStartedTalk { proof ->
        val pcm = ByteArray(100)
        proof.manager.handleGatewayEvent(
          "talk.event",
          """{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"failed-turn"},"audioBase64":"${Base64.encodeToString(pcm, Base64.NO_WRAP)}"}""",
        )
        proof.scheduler.runCurrent()
        assertFalse("A failed output device must not leave Talk silently enabled", proof.manager.isEnabled.value)
        assertTrue(
          proof.manager.statusText.value
            .contains("audio playback device error"),
        )
        val notice = proof.manager.failureNotice.value
        assertEquals(proof.manager.statusText.value, notice?.text)
        assertFalse(proof.manager.isSpeaking.value)
      }
    }

  @Test
  fun retiredPttRestartCannotReopenRecognition() {
    val manager = createManager()
    val recognizer = SpeechRecognizer.createSpeechRecognizer(RuntimeEnvironment.getApplication())
    setPrivateField(manager, "recognizer", recognizer)
    setPrivateField(manager, "activePttCaptureId", "replacement-capture")
    val start =
      manager.javaClass
        .getDeclaredMethod(
          "startPushToTalkRecognition",
          String::class.java,
          PushToTalkRecognitionCandidate::class.java,
        ).also { it.isAccessible = true }
    try {
      start.invoke(manager, "retired-capture", PushToTalkRecognitionCandidate.SilenceSegmented)
      assertNull("A stale restart must not reacquire native recognition", shadowOf(recognizer).lastRecognizerIntent)
      assertFalse(manager.isListening.value)
    } finally {
      manager.stopAllCapture()
    }
  }

  @Test
  fun rawPttCloseWaitsForInFlightDeviceCleanup() =
    runBlocking {
      val releaseEntered = CountDownLatch(1)
      val finishRelease = CountDownLatch(1)
      val recorder =
        object : AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          16_000,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          16_000,
        ) {
          override fun release() {
            releaseEntered.countDown()
            check(finishRelease.await(10, TimeUnit.SECONDS))
            super.release()
          }
        }
      val pipe = ParcelFileDescriptor.createPipe()
      val type = Class.forName("ai.openclaw.app.voice.PushToTalkAudioSource")
      val source =
        type
          .getDeclaredConstructor(
            ParcelFileDescriptor::class.java,
            ParcelFileDescriptor.AutoCloseOutputStream::class.java,
            AudioRecord::class.java,
          ).also { it.isAccessible = true }
          .newInstance(pipe[0], ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]), recorder)
      val finish = type.getDeclaredMethod("finishFromPump").also { it.isAccessible = true }
      val close = type.getDeclaredMethod("close").also { it.isAccessible = true }
      val finishing = async(Dispatchers.Default) { finish.invoke(source) }
      val closeStarted = CompletableDeferred<Unit>()
      var closing: kotlinx.coroutines.Deferred<*>? = null
      try {
        assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
        closing =
          async(Dispatchers.Default) {
            closeStarted.complete(Unit)
            close.invoke(source)
          }
        closeStarted.await()
        assertNull(
          "close must wait for the cleanup that owns the recorder",
          withTimeoutOrNull(250) {
            closing.await()
            true
          },
        )
      } finally {
        finishRelease.countDown()
        finishing.await()
        closing?.await() ?: close.invoke(source)
      }
      assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
    }

  @Test
  fun destroyedContinuousRecognizerCannotFailItsReplacement() =
    runTest {
      val manager = createManager(scope = this)
      val app = RuntimeEnvironment.getApplication()
      val retired = SpeechRecognizer.createSpeechRecognizer(app)
      setPrivateField(manager, "recognizer", retired)
      val retiredListener = recognitionListener(manager, null)
      manager.stopAllCapture()
      val replacement = SpeechRecognizer.createSpeechRecognizer(app)
      setPrivateField(manager, "recognizer", replacement)
      setPrivateField(manager, "stopRequested", false)
      setMutableStateFlow(manager, "_isEnabled", true)
      setMutableStateFlow(manager, "_isListening", true)
      val status = manager.statusText.value
      try {
        retiredListener.onError(SpeechRecognizer.ERROR_AUDIO)
        assertTrue("A late native callback must not stop the replacement listener", manager.isListening.value)
        assertEquals(status, manager.statusText.value)
      } finally {
        manager.stopAllCapture()
      }
    }

  @Test
  fun manualMicWaitsForRealtimeDeviceAndCaptureRetirement() =
    runBlocking {
      withNodeOwningRealtimePlayback { runtime, proof ->
        val track = startRealtimeAudio(proof)
        runtime.setMicEnabled(true)
        assertFalse("Manual mic cannot overlap the old Talk device", runtime.micEnabled.value)
        proof.scheduler.runCurrent()
        assertEquals(AudioTrack.STATE_UNINITIALIZED, track.state)
        assertFalse("Capture cancellation must also finish before the handoff", runtime.micEnabled.value)
        proof.drainCancelledCapture()
        withTimeout(5_000) {
          while (!runtime.micEnabled.value) {
            proof.scheduler.runCurrent()
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        assertEquals(VoiceCaptureMode.ManualMic, runtime.voiceCaptureMode.value)
      }
    }

  @Test
  fun sameTalkModeReassertionStopsManualMicCapture() =
    runBlocking {
      withNodeOwningRealtimePlayback { runtime, proof ->
        val mic = (readPrivateField(runtime, "micCapture\$delegate") as Lazy<*>).value as MicCaptureManager
        mic.setMicEnabled(true)
        assertTrue(runtime.micEnabled.value)
        runtime.setTalkModeEnabled(true)
        assertFalse(runtime.micEnabled.value)
        proof.drainCancelledCapture()
        withTimeout(5_000) {
          while (!proof.manager.isEnabled.value) {
            proof.scheduler.runCurrent()
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        assertEquals(VoiceCaptureMode.TalkMode, runtime.voiceCaptureMode.value)
        assertTrue(proof.manager.isEnabled.value)
      }
    }

  @Test
  fun repeatedStopKeepsOtherMicOwnersBlockedUntilRealtimeCleanupFinishes() =
    runBlocking {
      withNodeOwningRealtimePlayback { runtime, proof ->
        val track = startRealtimeAudio(proof)
        runtime.setTalkModeEnabled(false)
        runtime.setTalkModeEnabled(false)
        assertFalse(runtime.tryAcquireVoiceNoteMic())
        assertFalse(runtime.tryAcquireDictationMic())
        assertFalse(runtime.setCameraAudioCaptureActive(true))
        proof.scheduler.runCurrent()
        assertEquals(AudioTrack.STATE_UNINITIALIZED, track.state)
        assertFalse(runtime.tryAcquireVoiceNoteMic())
        proof.drainCancelledCapture()
        withTimeout(5_000) {
          while (!runtime.tryAcquireVoiceNoteMic()) {
            proof.scheduler.runCurrent()
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        runtime.releaseVoiceNoteMic()
        assertTrue(runtime.tryAcquireDictationMic())
        runtime.releaseDictationMic()
        assertTrue(runtime.setCameraAudioCaptureActive(true))
        runtime.setCameraAudioCaptureActive(false)
      }
    }

  private suspend fun withNodeOwningRealtimePlayback(block: suspend (NodeRuntime, RealtimePlaybackProof) -> Unit) {
    val app = RuntimeEnvironment.getApplication()
    val prefs = SecurePrefs(app, app.getSharedPreferences("node-playback-${System.nanoTime()}", 0))
    val runtime = NodeRuntime(app, prefs, mode = NodeRuntimeMode.ScreenshotFixture)
    try {
      ((readPrivateField(runtime, "micCapture\$delegate") as Lazy<*>).value as MicCaptureManager).onGatewayConnectionChanged(false)
      withStartedTalk { proof ->
        setPrivateField(runtime, "talkMode\$delegate", lazyOf(proof.manager))
        setMutableStateFlow(runtime, "_voiceCaptureMode", VoiceCaptureMode.TalkMode)
        setMutableStateFlow(runtime, "externalAudioCaptureActive", true)
        block(runtime, proof)
      }
    } finally {
      closeNodeRuntimeTestFixture(runtime)
    }
  }

  @Test
  fun relayFailureRetainsTheCaptureOwnerFromAdmissionDuringNewPttPreparation() =
    runBlocking {
      var captureEpoch = 1
      var stoppedReplacement = false
      withStartedTalk(captureRelayStopNotification = {
        val admittedEpoch = captureEpoch

        fun(isCurrent: () -> Boolean) {
          if (admittedEpoch == captureEpoch && isCurrent()) stoppedReplacement = true
        }
      }) { proof ->
        // PTT preparation claims the outer owner before it enters the Talk manager.
        captureEpoch++
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"close","reason":"error"}""")
        assertFalse(proof.manager.isEnabled.value)
        assertFalse(stoppedReplacement)
      }
    }

  @Test
  fun freshRealtimePcmExtendsOneAudibleExpiryWorkerWithoutRepublishingSpeakingStatus() =
    runBlocking {
      withStartedTalk { proof ->
        val playoutOwner = readPrivateField(proof.manager, "realtimePlayoutSession") as RealtimePlayout.Session
        val status = readPrivateField(proof.manager, "status") as MutableStateFlow<*>
        val statusOwner = readPrivateField(status.value!!, "owner")

        playoutOwner.onState(true, 0.4f, statusOwner, true, 0.4f)
        proof.scheduler.runCurrent()
        val firstSpeakingStatus = status.value
        val expiryWorker = readPrivateField(proof.manager, "realtimeAudibleExpiryJob") as Job
        assertTrue(expiryWorker.isActive)
        assertEquals("Speaking…", proof.manager.statusText.value)

        ShadowSystemClock.advanceBy(Duration.ofMillis(160))
        proof.scheduler.advanceTimeBy(160)
        proof.scheduler.runCurrent()
        playoutOwner.onState(true, 0.4f, statusOwner, true, 0.4f)
        proof.scheduler.runCurrent()

        assertSame("A fresh PCM deadline reuses the active expiry worker", expiryWorker, readPrivateField(proof.manager, "realtimeAudibleExpiryJob"))
        assertSame("Repeated fresh PCM retains the already-published speaking status", firstSpeakingStatus, status.value)

        ShadowSystemClock.advanceBy(Duration.ofMillis(160))
        proof.scheduler.advanceTimeBy(160)
        proof.scheduler.runCurrent()
        assertTrue("The second PCM extends the audible hangover", proof.manager.isSpeaking.value)

        ShadowSystemClock.advanceBy(Duration.ofMillis(159))
        proof.scheduler.advanceTimeBy(159)
        proof.scheduler.runCurrent()
        assertTrue(proof.manager.isSpeaking.value)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        proof.scheduler.advanceTimeBy(1)
        proof.scheduler.runCurrent()
        assertFalse(proof.manager.isSpeaking.value)
        assertEquals("Listening", proof.manager.statusText.value)
      }
    }

  @Test
  fun stoppedRealtimePlaybackCancelsItsAudibleExpiryBeforeItsOriginalDeadline() =
    runBlocking {
      withStartedTalk { proof ->
        val playoutOwner = readPrivateField(proof.manager, "realtimePlayoutSession") as RealtimePlayout.Session
        playoutOwner.onState(true, 0.4f, null, true, 0.4f)
        proof.scheduler.runCurrent()
        val expiryWorker = readPrivateField(proof.manager, "realtimeAudibleExpiryJob") as Job
        assertTrue(expiryWorker.isActive)

        playoutOwner.onState(false, null, null, false, null)
        proof.scheduler.runCurrent()
        assertTrue("Stopping output must cancel its pending expiry", expiryWorker.isCancelled)
        assertNull(readPrivateField(proof.manager, "realtimeAudibleExpiryJob"))

        ShadowSystemClock.advanceBy(Duration.ofMillis(320))
        proof.scheduler.advanceTimeBy(320)
        proof.scheduler.runCurrent()
        assertFalse("A cancelled old expiry cannot revive or alter later playback", proof.manager.isSpeaking.value)
      }
    }

  @Test
  fun finalAssistantTextRestoresIdleWithoutConsumingLaterAudio() =
    runBlocking {
      for ((playbackEnabled, responseStarted) in listOf(false, true).flatMap { playback -> listOf(playback to false, playback to true) }) {
        withStartedTalk { proof ->
          proof.manager.setPlaybackEnabled(playbackEnabled)
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Question","final":true}""")
          if (responseStarted) proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"realtime-turn"}""")
          assertEquals(responseStarted, proof.manager.awaitingAgent.value)
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"Answer","final":true,"talkEvent":{"turnId":"realtime-turn"}}""")
          proof.scheduler.runCurrent()
          assertEquals(
            "Answer",
            proof.manager.conversation.value
              .last()
              .text,
          )
          assertFalse("Final text with no pending playback must leave Thinking", proof.manager.awaitingAgent.value)
          assertEquals("Listening", proof.manager.statusText.value)
          if (playbackEnabled) {
            // Google can finish output transcription before a later audio part arrives.
            startRealtimeAudio(proof)
            assertEquals("Speaking…", proof.manager.statusText.value)
          }
        }
      }
    }

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun finalAssistantTextWaitsForQueuedAudioPresentation() =
    runBlocking {
      PlayoutAudioTrack.reset()
      PlayoutAudioTrack.presentedFrames = 0
      PlayoutAudioTrack.timestampFrames = 0
      val acknowledged = CompletableDeferred<Unit>()
      try {
        withStartedTalk(responseForRequest = { request, _ ->
          if (request.getValue("method").jsonPrimitive.content == "talk.session.acknowledgeMark") acknowledged.complete(Unit)
          null
        }) { proof ->
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Question","final":true}""")
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"realtime-turn"}""")
          val pcm = realtimeAudiblePcm()
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"realtime-turn"},"audioBase64":"${Base64.encodeToString(pcm, Base64.NO_WRAP)}"}""")
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"mark","markName":"last-chunk"}""")
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"Answer","final":true,"talkEvent":{"turnId":"realtime-turn"}}""")
          assertTrue("Queued audio must be observed before changing response status", proof.manager.awaitingAgent.value)
          proof.scheduler.runCurrent()
          assertTrue(proof.manager.isSpeaking.value)
          assertEquals("Speaking…", proof.manager.statusText.value)
          assertFalse("Final text cannot acknowledge unpresented audio", acknowledged.isCompleted)
          proof.manager.realtimeEvent(audioDoneEvent("realtime-turn"))
          PlayoutAudioTrack.presentedFrames = pcm.size / 2
          PlayoutAudioTrack.timestampFrames = pcm.size / 2L
          proof.scheduler.advanceTimeBy(20)
          proof.scheduler.runCurrent()
          withTimeout(5_000) { acknowledged.await() }
          assertFalse(proof.manager.isSpeaking.value)
          assertFalse(proof.manager.awaitingAgent.value)
          assertEquals("Listening", proof.manager.statusText.value)
        }
      } finally {
        PlayoutAudioTrack.reset()
      }
    }

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun audibleHeadAndSilentTailClearPresentationGateAndArmFollowUpAfterAQuietContinuation() =
    runBlocking {
      PlayoutAudioTrack.reset()
      PlayoutAudioTrack.presentedFrames = 0
      PlayoutAudioTrack.timestampFrames = 0
      PlayoutAudioTrack.acceptedChunkBytes = 960
      try {
        withStartedTalk { proof ->
          // The test owns the queued capture dispatcher. Let the no-seed ordered ingress
          // transition to live before exercising the physical playout boundary below.
          assertTrue(proof.drainNextCaptureTask())
          // A single gateway command can contain one audible 20 ms frame followed by a
          // long zero tail. The physical head stays still while the reserve primes, then a
          // subsequent quiet relay frame observes the rendered tail and settles Talk's gate.
          PlayoutAudioTrack.onWrite = { writes ->
            val track = PlayoutAudioTrack.track
            if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) {
              val presented = (writes - 1) * 480
              PlayoutAudioTrack.presentedFrames = presented
              PlayoutAudioTrack.timestampFrames = presented.toLong()
            }
          }
          val audibleThenQuietTail = ByteArray(960 * 24).also { it[0] = 0x7f; it[1] = 0x7f }
          proof.manager.realtimeEvent(
            """{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"audible-tail"},"audioBase64":"${Base64.encodeToString(audibleThenQuietTail, Base64.NO_WRAP)}"}""",
          )

          proof.scheduler.runCurrent()
          proof.manager.realtimeEvent(
            """{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"audible-tail"},"audioBase64":"${Base64.encodeToString(ByteArray(960), Base64.NO_WRAP)}"}""",
          )
          proof.scheduler.runCurrent()
          proof.scheduler.advanceTimeBy(20)
          proof.scheduler.runCurrent()
          assertFalse(readPrivateField(proof.manager, "realtimeOutputPresentationPending") as Boolean)
          assertTrue(
            (readPrivateField(proof.manager, "conversationPostAssistantFollowUpDeadlineMs") as Long) >
              SystemClock.elapsedRealtime(),
          )
        }
      } finally {
        PlayoutAudioTrack.reset()
      }
    }

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun providerClearStopsPreviousAudioAfterNextResponseStarts() =
    runBlocking {
      PlayoutAudioTrack.reset()
      try {
        withStartedTalk { proof ->
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"First question","final":true,"talkEvent":{"turnId":"realtime-turn"}}""")
          val track = startRealtimeAudio(proof)
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"First answer","final":true,"talkEvent":{"turnId":"realtime-turn"}}""")
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Second question","final":true,"talkEvent":{"turnId":"next-turn"}}""")
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"next-turn"}""")
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"Second answer","final":false,"talkEvent":{"turnId":"next-turn"}}""")
          proof.scheduler.runCurrent()
          assertTrue(proof.manager.isSpeaking.value)
          assertTrue(proof.manager.awaitingAgent.value)

          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"clear","talkEvent":{"type":"output.audio.done","turnId":"realtime-turn","final":true}}""")
          proof.scheduler.runCurrent()

          assertEquals("A newer transcript cannot prevent clearing queued playback", AudioTrack.STATE_UNINITIALIZED, track.state)
          assertFalse(proof.manager.isSpeaking.value)
          assertTrue("Clearing old audio cannot finish the next response", proof.manager.awaitingAgent.value)
          assertEquals("Thinking…", proof.manager.statusText.value)
          assertEquals(
            listOf("First question", "First answer", "Second question", "Second answer"),
            proof.manager.conversation.value
              .map { it.text },
          )
        }
      } finally {
        PlayoutAudioTrack.reset()
      }
    }

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun queuedFinalTextCannotFinishNewerUserThinking() = assertOldOutputPreservesNewerUserThinking(withAudio = false)

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun presentedOldAudioCannotFinishNewerUserThinking() = assertOldOutputPreservesNewerUserThinking(withAudio = true)

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun continuingOldOutputCannotFinishNewerUserThinking() {
    for (lateEvent in listOf("audio", "final-text")) {
      for (playback in listOf("playing", "drained", "cleared")) {
        assertOldOutputPreservesNewerUserThinking(withAudio = true, lateEvent = lateEvent, drainedBeforeInput = playback == "drained", clearBeforeInput = playback == "cleared")
      }
    }
  }

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun responseLifecycleOwnsStatusAcrossContinuationAndLateInput() =
    runBlocking {
      for (drainedBeforeInput in listOf(false, true)) {
        PlayoutAudioTrack.reset()
        try {
          withStartedTalk { proof ->
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"First question","final":true}""")
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"realtime-turn"}""")
            startRealtimeAudio(proof)
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audioDone","talkEvent":{"type":"turn.ended","turnId":"realtime-turn","final":true}}""")
            PlayoutAudioTrack.timestampFrames = REALTIME_AUDIBLE_PCM_FRAMES
            proof.scheduler.advanceTimeBy(20)
            proof.scheduler.runCurrent()
            assertEquals("Listening", proof.manager.statusText.value)

            val continuationStart = """{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"continuation-turn"}"""
            proof.manager.realtimeEvent(continuationStart)
            assertTrue("A continuation is admitted work without a new user transcript", proof.manager.awaitingAgent.value)
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","audioBase64":"${Base64.encodeToString(realtimeAudiblePcm(), Base64.NO_WRAP)}","talkEvent":{"turnId":"continuation-turn"}}""")
            proof.manager.realtimeEvent(audioDoneEvent("continuation-turn"))
            proof.scheduler.runCurrent()
            assertTrue(proof.manager.isSpeaking.value)
            proof.manager.realtimeEvent(continuationStart)
            assertEquals("A duplicate response start cannot reset playback", "Speaking…", proof.manager.statusText.value)
            assertFalse(proof.manager.awaitingAgent.value)
            if (drainedBeforeInput) {
              PlayoutAudioTrack.timestampFrames = REALTIME_AUDIBLE_PCM_FRAMES * 2
              proof.scheduler.advanceTimeBy(20)
              proof.scheduler.runCurrent()
              assertFalse(proof.manager.isSpeaking.value)
            }
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Next question","final":true,"talkEvent":{"turnId":"continuation-turn"}}""")
            assertEquals(
              "Next question",
              proof.manager.conversation.value
                .last()
                .text,
            )
            assertFalse("Late input does not identify what the continuation answered", proof.manager.awaitingAgent.value)
            assertEquals(if (drainedBeforeInput) "Listening" else "Speaking…", proof.manager.statusText.value)

            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"next-turn"}""")
            assertTrue(proof.manager.awaitingAgent.value)
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audioDone","talkEvent":{"type":"turn.ended","turnId":"continuation-turn","final":true}}""")
            proof.scheduler.runCurrent()
            assertEquals(!drainedBeforeInput, proof.manager.isSpeaking.value)
            assertTrue("A stale terminal cannot finish newer work", proof.manager.awaitingAgent.value)
            assertEquals("Thinking…", proof.manager.statusText.value)
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audioDone","talkEvent":{"type":"turn.ended","turnId":"next-turn","final":true}}""")
            proof.scheduler.runCurrent()
            assertEquals(!drainedBeforeInput, proof.manager.isSpeaking.value)
            assertFalse("An empty matching terminal must finish its response while earlier audio remains buffered", proof.manager.awaitingAgent.value)
            assertEquals(if (drainedBeforeInput) "Listening" else "Speaking…", proof.manager.statusText.value)
            PlayoutAudioTrack.timestampFrames = REALTIME_AUDIBLE_PCM_FRAMES * 2
            proof.scheduler.advanceTimeBy(20)
            proof.scheduler.runCurrent()
            assertFalse(proof.manager.isSpeaking.value)
            assertEquals("Listening", proof.manager.statusText.value)
          }
        } finally {
          PlayoutAudioTrack.reset()
        }
      }
    }

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun consultWorkCannotBeFinishedByEarlierPlayback() =
    runBlocking {
      PlayoutAudioTrack.reset()
      try {
        withStartedTalk(responseForRequest = { request, _ ->
          if (request.getValue("method").jsonPrimitive.content == "talk.client.toolCall") """{"runId":"working-run"}""" else null
        }) { proof ->
          startRealtimeAudio(proof)
          proof.manager.realtimeEvent(audioDoneEvent("realtime-turn"))
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"toolCall","callId":"next-consult","name":"openclaw_agent_consult","args":{"question":"Next question"}}""")
          withTimeout(5_000) {
            while (!proof.manager.awaitingAgent.value) {
              proof.scheduler.runCurrent()
              withContext(Dispatchers.Default) { delay(10) }
            }
          }
          PlayoutAudioTrack.timestampFrames = REALTIME_AUDIBLE_PCM_FRAMES
          proof.scheduler.advanceTimeBy(20)
          proof.scheduler.runCurrent()
          assertFalse(proof.manager.isSpeaking.value)
          assertTrue("Earlier playback cannot finish an admitted consult", proof.manager.awaitingAgent.value)
          assertEquals("Thinking…", proof.manager.statusText.value)
          startRealtimeAudio(proof)
          assertFalse("Newly admitted audio can advance the current work phase", proof.manager.awaitingAgent.value)
          assertEquals("Speaking…", proof.manager.statusText.value)
        }
      } finally {
        PlayoutAudioTrack.reset()
      }
    }

  @Test
  fun providerDirectAgentToolsUseTheAcceptedRunNotTheVoiceSessionKey() =
    runBlocking {
      withStartedTalk(responseForRequest = { request, _ ->
        if (request.getValue("method").jsonPrimitive.content == "talk.client.toolCall") {
          // 2026.9.5 may omit agentSessionKey although the later event carries
          // the canonical delegated target instead of the voice session key.
          """{"runId":"provider-direct-run"}"""
        } else {
          null
        }
      }) { proof ->
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"toolCall","callId":"consult","name":"openclaw_agent_consult","args":{}}""")
        withTimeout(5_000) {
          while (!proof.manager.awaitingAgent.value) {
            proof.scheduler.runCurrent()
            withContext(Dispatchers.Default) { delay(10) }
          }
        }

        proof.manager.handleGatewayEvent(
          "agent",
          """{"runId":"provider-direct-run","sessionKey":"agent:jarvis-voice:node-1b382a0d8c95","stream":"tool","data":{"phase":"start","toolCallId":"bash-1","name":"bash","args":{"command":"private"}}}""",
        )
        assertEquals(listOf("Shell"), proof.manager.activeToolExecutions.value.map { it.name })
        assertEquals(JarvisToolPhase.Running, proof.manager.activeToolExecutions.value.single().phase)
        val bashStartedAt = proof.manager.activeToolExecutions.value.single().startedAtMs
        assertNotNull(bashStartedAt)

        proof.manager.handleGatewayEvent(
          "agent",
          """{"runId":"foreign-run","sessionKey":"agent:jarvis-voice:node-1b382a0d8c95","stream":"tool","data":{"phase":"start","toolCallId":"foreign-1","name":"memory_recall"}}""",
        )
        assertEquals(listOf("Shell"), proof.manager.activeToolExecutions.value.map { it.name })

        proof.manager.handleGatewayEvent(
          "agent",
          """{"runId":"provider-direct-run","sessionKey":"agent:jarvis-voice:node-1b382a0d8c95","stream":"tool","data":{"phase":"result","toolCallId":"bash-1"}}""",
        )
        assertEquals(JarvisToolPhase.Completed, proof.manager.activeToolExecutions.value.single().phase)
        proof.manager.handleGatewayEvent(
          "agent",
          """{"runId":"provider-direct-run","sessionKey":"agent:jarvis-voice:node-1b382a0d8c95","stream":"tool","data":{"phase":"end","toolCallId":"bash-1"}}""",
        )
        assertEquals("A trailing end must preserve the actual result", JarvisToolPhase.Completed, proof.manager.activeToolExecutions.value.single().phase)
        assertEquals(bashStartedAt, proof.manager.activeToolExecutions.value.single().startedAtMs)
        proof.scheduler.advanceTimeBy(8_999)
        proof.scheduler.runCurrent()
        assertTrue(proof.manager.activeToolExecutions.value.isNotEmpty())
        proof.scheduler.advanceTimeBy(1)
        proof.scheduler.runCurrent()
        assertTrue(proof.manager.activeToolExecutions.value.isEmpty())

        // GPT-Live gateway control starts this run itself. No Android
        // openclaw_agent_consult event/ACK exists; the active node voice key
        // is the ownership boundary.
        val sessionEventTimestamp = System.currentTimeMillis()
        proof.manager.handleGatewayEvent(
          "session.tool",
          """{"runId":"gateway-owned-run","sessionKey":"main","stream":"tool","seq":1,"ts":$sessionEventTimestamp,"data":{"phase":"start","toolCallId":"memory-1","name":"memory_recall"}}""",
        )
        assertEquals(listOf("Gedächtnis"), proof.manager.activeToolExecutions.value.map { it.name })
        proof.manager.handleGatewayEvent(
          "session.tool",
          """{"runId":"gateway-owned-run","sessionKey":"main","stream":"tool","seq":2,"ts":${sessionEventTimestamp + 1},"data":{"phase":"end","toolCallId":"memory-1"}}""",
        )
        assertEquals(JarvisToolPhase.Unknown, proof.manager.activeToolExecutions.value.single().phase)

        proof.manager.handleGatewayEvent(
          "session.tool",
          """{"runId":"gateway-owned-run","sessionKey":"main","stream":"tool","seq":3,"ts":${sessionEventTimestamp + 2},"data":{"phase":"start","toolCallId":"memory-2","name":"memory_recall"}}""",
        )
        proof.manager.handleGatewayEvent(
          "session.tool",
          """{"runId":"gateway-owned-run","sessionKey":"main","stream":"tool","seq":4,"ts":${sessionEventTimestamp + 3},"data":{"phase":"result","toolCallId":"memory-2","isError":true}}""",
        )
        assertEquals(
          JarvisToolPhase.Failed,
          proof.manager.activeToolExecutions.value.single { it.toolCallId == "memory-2" }.phase,
        )
      }
    }

  @Test
  @Config(shadows = [PlayoutAudioTrack::class])
  fun lateFinalUserTranscriptKeepsTheAnsweredUtteranceStatus() =
    runBlocking {
      val cases =
        listOf("consult-audio", "stopped-audio", "cleared-audio", "active-audio", "drained-audio", "local-replay")
          .flatMap { mode -> listOf(Triple(mode, true, false), Triple(mode, false, false)) } +
          listOf(Triple("active-audio", false, true), Triple("drained-audio", false, true))
      for ((mode, earlyPartial, completedPreviousTurn) in cases) {
        PlayoutAudioTrack.reset()
        try {
          withStartedTalk(responseForRequest = { request, _ ->
            if (request.getValue("method").jsonPrimitive.content == "talk.client.toolCall") """{"runId":"consult-run"}""" else null
          }) { proof ->
            val encodedAudio = Base64.encodeToString(realtimeAudiblePcm(), Base64.NO_WRAP)
            if (completedPreviousTurn) {
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Previous question","final":true,"talkEvent":{"turnId":"previous-turn"}}""")
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","audioBase64":"$encodedAudio","talkEvent":{"turnId":"previous-turn"}}""")
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"Previous answer","final":true,"talkEvent":{"turnId":"previous-turn"}}""")
              proof.manager.realtimeEvent(audioDoneEvent("previous-turn"))
              proof.scheduler.runCurrent()
              assertTrue(proof.manager.isSpeaking.value)
              PlayoutAudioTrack.timestampFrames = REALTIME_AUDIBLE_PCM_FRAMES
              proof.scheduler.advanceTimeBy(20)
              proof.scheduler.runCurrent()
              assertFalse(proof.manager.isSpeaking.value)
              assertEquals("Listening", proof.manager.statusText.value)
            }
            val completedFrames =
              if (completedPreviousTurn) REALTIME_AUDIBLE_PCM_FRAMES * 2 else REALTIME_AUDIBLE_PCM_FRAMES
            if (earlyPartial) {
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Can you tack","final":false,"talkEvent":{"turnId":"active-turn"}}""")
            }
            val partialUserId =
              proof.manager.conversation.value
                .lastOrNull { it.role == VoiceConversationRole.User }
                ?.id
            if (mode == "consult-audio") {
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"toolCall","callId":"consult-call","name":"openclaw_agent_consult","args":{"question":"Synthetic question"}}""")
              withTimeout(5_000) {
                while (!proof.manager.awaitingAgent.value) {
                  proof.scheduler.runCurrent()
                  withContext(Dispatchers.Default) { delay(10) }
                }
              }
            }
            val audio = """{"relaySessionId":"playback-relay","type":"audio","audioBase64":"$encodedAudio","talkEvent":{"turnId":"active-turn"}}"""
            if (mode == "local-replay") {
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"Checking","final":true}""")
            } else {
              proof.manager.realtimeEvent(audio)
              proof.manager.realtimeEvent(audioDoneEvent("active-turn"))
            }
            proof.scheduler.runCurrent()
            assertEquals(mode != "local-replay", proof.manager.isSpeaking.value)
            if (mode == "stopped-audio") {
              proof.manager.stopTts()
              proof.scheduler.runCurrent()
              assertFalse(proof.manager.isSpeaking.value)
            }
            if (mode == "cleared-audio") {
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"clear","talkEvent":{"turnId":"active-turn"}}""")
              proof.scheduler.runCurrent()
              assertFalse(proof.manager.isSpeaking.value)
            }
            if (mode == "drained-audio") {
              PlayoutAudioTrack.timestampFrames = completedFrames
              proof.scheduler.advanceTimeBy(20)
              proof.scheduler.runCurrent()
              assertFalse(proof.manager.isSpeaking.value)
            }
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Can you check?","final":true,"talkEvent":{"turnId":"active-turn"}}""")
            val userEntry =
              proof.manager.conversation.value
                .last { it.role == VoiceConversationRole.User }
            if (earlyPartial) assertEquals(partialUserId, userEntry.id)
            val userId = userEntry.id
            assertEquals("Can you check?", userEntry.text)
            val awaitingAfterUserTranscript = proof.manager.awaitingAgent.value
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"Checking","final":true,"talkEvent":{"turnId":"active-turn"}}""")
            PlayoutAudioTrack.timestampFrames = completedFrames
            proof.scheduler.advanceTimeBy(20)
            proof.scheduler.runCurrent()
            assertFalse(proof.manager.isSpeaking.value)
            assertEquals(
              "Finalizing an answered utterance cannot restart Thinking or outlive playback ($mode, earlyPartial=$earlyPartial, completedPreviousTurn=$completedPreviousTurn)",
              false to "Listening",
              awaitingAfterUserTranscript to proof.manager.statusText.value,
            )
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Second question","final":false,"talkEvent":{"turnId":"active-turn"}}""")
            if (mode != "local-replay") {
              proof.manager.realtimeEvent(audio)
              proof.scheduler.runCurrent()
              assertEquals("A partial transcript does not replace the physical playback phase", if (mode == "stopped-audio") "Listening" else "Speaking…", proof.manager.statusText.value)
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audioDone","talkEvent":{"turnId":"active-turn"}}""")
            }
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Second question","final":true,"talkEvent":{"turnId":"active-turn"}}""")
            assertFalse("Even a distinct transcript does not prove new work was admitted", proof.manager.awaitingAgent.value)
            assertTrue(
              userId !=
                proof.manager.conversation.value
                  .last()
                  .id,
            )
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"inputAudio","byteLength":960}""")
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"next-turn"}""")
            assertTrue("An admitted response owns the waiting status", proof.manager.awaitingAgent.value)
            PlayoutAudioTrack.timestampFrames = completedFrames + REALTIME_AUDIBLE_PCM_FRAMES
            proof.scheduler.advanceTimeBy(20)
            proof.scheduler.runCurrent()
            assertFalse(proof.manager.isSpeaking.value)
            assertTrue("Earlier audio cannot finish newly admitted work", proof.manager.awaitingAgent.value)
            assertEquals("Thinking…", proof.manager.statusText.value)
          }
        } finally {
          PlayoutAudioTrack.reset()
        }
      }
    }

  private fun assertOldOutputPreservesNewerUserThinking(
    withAudio: Boolean,
    lateEvent: String? = null,
    drainedBeforeInput: Boolean = false,
    clearBeforeInput: Boolean = false,
  ) = runBlocking {
    PlayoutAudioTrack.reset()
    try {
      withStartedTalk { proof ->
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"First question","final":true,"talkEvent":{"turnId":"active-turn"}}""")
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"active-turn"}""")
        if (withAudio) {
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","audioBase64":"${Base64.encodeToString(realtimeAudiblePcm(), Base64.NO_WRAP)}","talkEvent":{"turnId":"active-turn"}}""")
          proof.manager.realtimeEvent(audioDoneEvent("active-turn"))
          proof.scheduler.runCurrent()
          assertTrue(proof.manager.isSpeaking.value)
        }
        val finalText = """{"relaySessionId":"playback-relay","type":"transcript","role":"assistant","text":"First answer","final":true,"talkEvent":{"turnId":"active-turn"}}"""
        if (lateEvent == null) proof.manager.realtimeEvent(finalText)
        if (drainedBeforeInput) {
          PlayoutAudioTrack.timestampFrames = REALTIME_AUDIBLE_PCM_FRAMES
          proof.scheduler.advanceTimeBy(20)
          proof.scheduler.runCurrent()
          assertFalse(proof.manager.isSpeaking.value)
        }
        if (clearBeforeInput) {
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"clear","talkEvent":{"turnId":"active-turn"}}""")
          proof.scheduler.runCurrent()
        }
        // Old output can be queued before the next response starts, even after its input transcript.
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Second question","final":true,"talkEvent":{"turnId":"active-turn"}}""")
        when (lateEvent) {
          "audio" -> {
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","audioBase64":"${Base64.encodeToString(realtimeAudiblePcm(), Base64.NO_WRAP)}","talkEvent":{"turnId":"active-turn"}}""")
            proof.manager.realtimeEvent(audioDoneEvent("active-turn"))
          }
          "final-text" -> proof.manager.realtimeEvent(finalText)
        }
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"responseStarted","turnId":"next-turn"}""")
        proof.scheduler.runCurrent()
        assertTrue("Queued old output cannot claim a newly admitted response", proof.manager.awaitingAgent.value)
        if (withAudio) {
          PlayoutAudioTrack.timestampFrames =
            if (lateEvent == "audio") REALTIME_AUDIBLE_PCM_FRAMES * 2 else REALTIME_AUDIBLE_PCM_FRAMES
        }
        proof.scheduler.advanceTimeBy(20)
        proof.scheduler.runCurrent()
        assertFalse(proof.manager.isSpeaking.value)
        assertTrue("Old output completion cannot finish a newer admitted response", proof.manager.awaitingAgent.value)
        assertEquals("Thinking…", proof.manager.statusText.value)
        if (lateEvent != null) {
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","audioBase64":"${Base64.encodeToString(realtimeAudiblePcm(), Base64.NO_WRAP)}","talkEvent":{"turnId":"next-turn"}}""")
          proof.scheduler.runCurrent()
          assertTrue("A new output turn must claim the current request", proof.manager.isSpeaking.value)
          assertFalse(proof.manager.awaitingAgent.value)
        }
      }
    } finally {
      PlayoutAudioTrack.reset()
    }
  }

  @Test
  fun createdRelayRejectedAtAdmissionIsClosed() =
    runBlocking {
      val creates =
        java.util.concurrent.atomic
          .AtomicInteger()
      val pending = CompletableDeferred<Pair<String, WebSocket>>()
      val closed = CompletableDeferred<Unit>()
      withStartedTalk(interceptRequest = { request, socket ->
        val method = request.getValue("method").jsonPrimitive.content
        if (method == "talk.session.close" && request["params"]
            ?.jsonObject
            ?.get("sessionId")
            ?.jsonPrimitive
            ?.content == "unadmitted-relay"
        ) {
          closed.complete(Unit)
        }
        if (method == "talk.session.create" && creates.incrementAndGet() == 2) {
          pending.complete(request.getValue("id").jsonPrimitive.content to socket)
          true
        } else {
          false
        }
      }) { proof ->
        proof.manager.stopAllCapture()
        proof.drainCancelledCapture()
        proof.manager.setEnabled(true)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!pending.isCompleted) {
          proof.scheduler.runCurrent()
          check(System.nanoTime() < deadline) { "Replacement relay create was not requested" }
          withContext(Dispatchers.Default) { delay(10) }
        }
        val (requestId, socket) = pending.await()
        val pumping =
          java.util.concurrent.atomic
            .AtomicBoolean(true)
        var worker: Thread? = null
        try {
          synchronized(readPrivateField(proof.manager, "realtimeCapturePauseLock")!!) {
            socket.send("""{"type":"res","id":"$requestId","ok":true,"payload":{"relaySessionId":"unadmitted-relay"}}""")
            worker =
              Thread {
                while (pumping.get()) {
                  proof.scheduler.runCurrent()
                  Thread.yield()
                }
              }.also { it.start() }
            val lockDeadline = System.nanoTime() + 5_000_000_000L
            while (worker.state != Thread.State.BLOCKED) {
              check(System.nanoTime() < lockDeadline) { "Create response did not reach locked admission" }
              Thread.yield()
            }
            proof.manager.stopAllCapture()
          }
          assertNotNull("A remotely created but unadmitted relay must be closed", withTimeoutOrNull(5_000) { closed.await() })
          assertFalse(proof.manager.isEnabled.value)
        } finally {
          pumping.set(false)
          worker?.join(5_000)
          assertFalse("Scheduler worker must terminate", worker?.isAlive == true)
        }
      }
    }

  @Test
  fun stoppedPushToTalkAdmissionCannotPauseLaterTalk() =
    runBlocking {
      installSpeechRecognitionService()
      withStartedTalk { proof ->
        var stopped = false
        val stopBeforePause =
          object : ThreadContextElement<Unit>, AbstractCoroutineContextElement(object : CoroutineContext.Key<ThreadContextElement<Unit>> {}) {
            override fun updateThreadContext(context: CoroutineContext) {
              if (!stopped && proof.manager.activePushToTalkCaptureId != null) {
                stopped = true
                proof.manager.stopAllCapture()
              }
            }

            override fun restoreThreadContext(
              context: CoroutineContext,
              oldState: Unit,
            ) = Unit
          }
        val obsolete = proof.scope.async(stopBeforePause) { runCatching { proof.manager.beginPushToTalk(allowNewCapture = true) } }

        suspend fun awaitState(condition: () -> Boolean) {
          val deadline = System.nanoTime() + 5_000_000_000L
          while (!condition()) {
            proof.scheduler.runCurrent()
            check(System.nanoTime() < deadline) { "Timed out waiting for PTT admission boundary" }
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        awaitState {
          if (stopped) proof.drainCancelledCapture()
          obsolete.isCompleted
        }
        assertTrue("Stop must occur after PTT admission and before its awaited completion", stopped)
        assertTrue(obsolete.await().isFailure)
        proof.manager.setEnabled(true)
        awaitState { readPrivateField(proof.manager, "realtimeSessionId") != null }
        assertTrue("A retired PTT admission cannot leave later Talk paused", proof.manager.isListening.value)
      }
    }

  @Test
  fun retiredPushToTalkCannotEnqueueCancellationAfterPhysicalCleanup() =
    runBlocking {
      installSpeechRecognitionService()
      val cancellations =
        java.util.concurrent.atomic
          .AtomicInteger()
      withStartedTalk(responseForRequest = { request, _ ->
        if (request.getValue("method").jsonPrimitive.content == "talk.session.cancelOutput") {
          cancellations.incrementAndGet()
          """{"ok":true,"status":"idle","turnId":"old-turn"}"""
        } else {
          null
        }
      }) { proof ->
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"old-turn"},"audioBase64":"AAA="}""")
        val obsolete = proof.scope.async { runCatching { proof.manager.beginPushToTalk(allowNewCapture = true) } }
        val admissionDeadline = System.nanoTime() + 5_000_000_000L
        while (proof.manager.activePushToTalkCaptureId == null) {
          proof.scheduler.runCurrent()
          check(System.nanoTime() < admissionDeadline) { "Timed out admitting push-to-talk" }
          withContext(Dispatchers.Default) { delay(10) }
        }
        assertNotNull(proof.manager.activePushToTalkCaptureId)
        assertTrue("PTT must still await its retired physical input", proof.manager.audioRetirement.pending)
        assertEquals(0, cancellations.get())

        proof.manager.stopAllCapture()
        proof.drainCancelledCapture()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!obsolete.isCompleted) {
          proof.scheduler.runCurrent()
          proof.drainCancelledCapture()
          check(System.nanoTime() < deadline) { "Timed out retiring push-to-talk" }
          withContext(Dispatchers.Default) { delay(10) }
        }
        assertTrue(obsolete.await().isFailure)
        assertEquals("Retired PTT must not send cancellation after physical cleanup", 0, cancellations.get())
      }
    }

  @Test
  fun retiredPushToTalkCancellationCannotStopReplacementRelay() =
    runBlocking {
      installSpeechRecognitionService()
      for (applied in listOf(false, true)) {
        val pending = CompletableDeferred<Pair<String, WebSocket>>()
        withStartedTalk(interceptRequest = { request, socket ->
          if (request.getValue("method").jsonPrimitive.content == "talk.session.cancelOutput") {
            pending.complete(request.getValue("id").jsonPrimitive.content to socket)
            true
          } else {
            false
          }
        }) { proof ->
          suspend fun awaitState(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition()) {
              proof.scheduler.runCurrent()
              check(System.nanoTime() < deadline) { "Timed out waiting for push-to-talk transition" }
              withContext(Dispatchers.Default) { delay(10) }
            }
          }
          proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"old-turn"},"audioBase64":"AAA="}""")
          val oldStart = proof.scope.async { runCatching { proof.manager.beginPushToTalk(allowNewCapture = true) } }
          proof.scheduler.runCurrent()
          proof.drainCancelledCapture()
          awaitState {
            proof.drainCancelledCapture()
            pending.isCompleted
          }
          proof.manager.stopAllCapture()
          proof.drainCancelledCapture()
          proof.manager.setEnabled(true)
          awaitState { proof.manager.isListening.value }
          val (requestId, socket) = pending.await()
          socket.send(
            if (applied) {
              """{"type":"res","id":"$requestId","ok":true,"payload":{"ok":true,"status":"applied","turnId":"old-turn"}}"""
            } else {
              """{"type":"res","id":"$requestId","ok":false,"error":{"code":"UNAVAILABLE","message":"old cancellation failed"}}"""
            },
          )
          awaitState { oldStart.isCompleted }
          assertTrue("The retired PTT request must reject capture", oldStart.await().isFailure)
          assertTrue("Old cancellation must leave the replacement relay enabled", proof.manager.isEnabled.value)
          assertTrue("Old cancellation must leave the replacement relay listening", proof.manager.isListening.value)
        }
      }
    }

  @Test
  fun providerClearCannotCompletePushToTalkCancellation() =
    runBlocking {
      installSpeechRecognitionService()
      val pending = CompletableDeferred<Pair<String, WebSocket>>()
      val providerClearDrained = mapOf("unkeyed" to CompletableDeferred<Unit>(), "keyed" to CompletableDeferred<Unit>())
      withStartedTalk(interceptRequest = { request, socket ->
        when (request.getValue("method").jsonPrimitive.content) {
          "talk.session.cancelOutput" -> {
            pending.complete(request.getValue("id").jsonPrimitive.content to socket)
            true
          }

          "talk.session.acknowledgeMark" -> {
            request["params"]
              ?.jsonObject
              ?.get("markName")
              ?.jsonPrimitive
              ?.content
              ?.let { providerClearDrained[it]?.complete(Unit) }
            false
          }

          else -> {
            false
          }
        }
      }) { proof ->
        suspend fun awaitState(condition: () -> Boolean) {
          val deadline = System.nanoTime() + 5_000_000_000L
          while (!condition()) {
            proof.scheduler.runCurrent()
            proof.drainCancelledCapture()
            check(System.nanoTime() < deadline) { "Timed out waiting for push-to-talk cancellation boundary" }
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"old-turn"},"audioBase64":"AAA="}""")
        val starting = proof.scope.async { runCatching { proof.manager.beginPushToTalk(allowNewCapture = true) } }
        awaitState { pending.isCompleted }
        val clear = readPrivateField(proof.manager, "pendingRealtimeOutputClear") as CompletableDeferred<*>
        val (requestId, socket) = pending.await()
        var replied = false
        try {
          val completedByProviderClear = mutableMapOf<String, Boolean>()
          for ((kind, drained) in providerClearDrained) {
            val talkEvent = if (kind == "keyed") """, "talkEvent":{"type":"output.audio.done","turnId":"old-turn","final":true}""" else ""
            socket.send("""{"type":"event","event":"talk.event","payload":{"relaySessionId":"playback-relay","type":"clear","reason":"barge-in"$talkEvent}}""")
            // A mark after clear observes the real playback queue retiring before its acknowledgement.
            socket.send("""{"type":"event","event":"talk.event","payload":{"relaySessionId":"playback-relay","type":"mark","markName":"$kind"}}""")
            awaitState { drained.isCompleted && !proof.manager.audioRetirement.pending }
            completedByProviderClear[kind] = clear.isCompleted
            assertFalse("PTT must still await the cancellation RPC", starting.isCompleted)
          }

          socket.send("""{"type":"event","event":"talk.event","payload":{"relaySessionId":"playback-relay","type":"clear","talkEvent":{"type":"turn.cancelled","turnId":"old-turn"}}}""")
          socket.send("""{"type":"res","id":"$requestId","ok":true,"payload":{"ok":true,"status":"applied","turnId":"old-turn"}}""")
          replied = true
          awaitState { starting.isCompleted }

          assertEquals(
            "Provider playback clears cannot acknowledge explicit push-to-talk cancellation",
            mapOf("providerClearCompletedCancellation" to mapOf("unkeyed" to false, "keyed" to false), "relaySessionId" to "playback-relay", "pushToTalkStarted" to true),
            mapOf(
              "providerClearCompletedCancellation" to completedByProviderClear,
              "relaySessionId" to readPrivateField(proof.manager, "realtimeSessionId"),
              "pushToTalkStarted" to starting.await().isSuccess,
            ),
          )
        } finally {
          if (!replied) socket.send("""{"type":"res","id":"$requestId","ok":true,"payload":{"ok":true,"status":"idle"}}""")
        }
      }
    }

  @Test
  fun delayedStartFailureCannotStopTheReplacementRelay() =
    runBlocking {
      val creates =
        java.util.concurrent.atomic
          .AtomicInteger()
      val pending = CompletableDeferred<Pair<String, WebSocket>>()
      withStartedTalk(interceptRequest = { request, socket ->
        if (request.getValue("method").jsonPrimitive.content == "talk.session.create" && creates.incrementAndGet() == 2) {
          pending.complete(request.getValue("id").jsonPrimitive.content to socket)
          true
        } else {
          false
        }
      }) { proof ->
        suspend fun awaitState(condition: () -> Boolean) {
          val deadline = System.nanoTime() + 5_000_000_000L
          while (!condition()) {
            proof.scheduler.runCurrent()
            check(System.nanoTime() < deadline) { "Timed out waiting for relay transition" }
            withContext(Dispatchers.Default) { delay(10) }
          }
        }
        proof.manager.stopAllCapture()
        proof.drainCancelledCapture()
        proof.manager.setEnabled(true)
        val work = readPrivateField(proof.manager, "gatewayWorkJob") as Job
        awaitState { pending.isCompleted && work.children.count { it.isActive } == 1 }
        val oldStart = work.children.single { it.isActive }
        proof.manager.stopAllCapture()
        proof.drainCancelledCapture()
        proof.manager.setEnabled(true)
        awaitState { proof.manager.isListening.value }
        val (requestId, socket) = pending.await()
        socket.send("""{"type":"res","id":"$requestId","ok":false,"error":{"code":"UNAVAILABLE","message":"delayed start failure"}}""")
        awaitState { oldStart.isCompleted }
        assertTrue("An obsolete start error must not disable its replacement", proof.manager.isEnabled.value)
        assertTrue(proof.manager.isListening.value)
      }
    }

  @Test
  fun rejectedSessionStartLeavesAFailureNoticeUntilTalkStartsAgain() =
    runBlocking {
      val subscription = realtimeRelayContractCases().single { it.getValue("id").jsonPrimitive.content == "subscription" }
      val config = buildJsonObject { put("config", subscription.getValue("config")) }.toString()
      val creates =
        java.util.concurrent.atomic
          .AtomicInteger()
      withStartedTalk(responseForRequest = { request, _ ->
        config.takeIf { request.getValue("method").jsonPrimitive.content == "talk.config" }
      }, interceptRequest = { request, socket ->
        if (request.getValue("method").jsonPrimitive.content == "talk.session.create" && creates.incrementAndGet() == 2) {
          val id = request.getValue("id").jsonPrimitive.content
          socket.send("""{"type":"res","id":"$id","ok":false,"error":{"code":"UNAVAILABLE","message":"provider unavailable"}}""")
          true
        } else {
          false
        }
      }) { proof ->
        proof.manager.stopAllCapture()
        proof.drainCancelledCapture()
        assertNull(proof.manager.failureNotice.value)
        proof.manager.setEnabled(true)
        awaitTalkWork(proof) { !proof.manager.isEnabled.value }
        assertFalse(proof.manager.isListening.value)
        // Chat shows this notice once Talk ends; without it a rejected start leaves no trace there.
        val notice = proof.manager.failureNotice.value
        assertEquals("Start failed: UNAVAILABLE: provider unavailable", notice?.text)

        proof.manager.setEnabled(true)
        awaitTalkWork(proof) { proof.manager.isListening.value }
        assertNull(proof.manager.failureNotice.value)
      }
    }

  @Test
  fun relayConsultReturnsCanonicalOwnedResultOverGatewayConnection() =
    runBlocking {
      for ((voiceKey, agentKey) in listOf("main" to "agent:voice:main", "global" to "global")) {
        for (early in listOf(false, true)) {
          val socket = CompletableDeferred<WebSocket>()
          val result = CompletableDeferred<JsonObject>()
          val final = """{"type":"event","event":"chat","payload":{"sessionKey":"$agentKey","runId":"owned-run","state":"final","message":{"role":"assistant","content":"Owned reply"}}}"""
          withStartedTalk(
            sessionKey = voiceKey,
            responseForRequest = { request, webSocket ->
              socket.complete(webSocket)
              val params = request["params"]?.jsonObject
              when (request.getValue("method").jsonPrimitive.content) {
                "talk.client.toolCall" -> {
                  assertEquals(voiceKey, params?.getValue("sessionKey")?.jsonPrimitive?.content)
                  if (early) webSocket.send(final)
                  """{"runId":"owned-run","agentId":"voice","agentSessionKey":"$agentKey"}"""
                }

                "talk.session.submitToolResult" -> {
                  result.complete(checkNotNull(params))
                  "{}"
                }

                else -> {
                  null
                }
              }
            },
          ) { proof ->
            proof.manager.ttsOnAllResponses = true
            socket.await().send("""{"type":"event","event":"chat","payload":{"sessionKey":"other-session","runId":"private-run","state":"final","message":{"role":"assistant","content":"Private reply"}}}""")
            socket.await().send("""{"type":"event","event":"talk.event","payload":{"relaySessionId":"playback-relay","type":"toolCall","callId":"owned-call","name":"openclaw_agent_consult","args":{"question":"Synthetic question"}}}""")
            val deadline = System.nanoTime() + 5_000_000_000L
            var finalSent = early
            while (!result.isCompleted) {
              proof.scheduler.runCurrent()
              if (!finalSent && proof.manager.awaitingAgent.value) {
                socket.await().send(final)
                finalSent = true
              }
              check(System.nanoTime() < deadline) { "Android did not return the owned consult result (key=$voiceKey, early=$early)" }
              withContext(Dispatchers.Default) { delay(10) }
            }
            val submitted = result.await()
            assertEquals("playback-relay", submitted.getValue("sessionId").jsonPrimitive.content)
            assertEquals("owned-call", submitted.getValue("callId").jsonPrimitive.content)
            assertEquals(
              "Owned reply",
              submitted
                .getValue("result")
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content,
            )
            assertFalse(proof.synthesizer.requested.isCompleted)
          }
        }
      }
    }

  @Test
  fun realtimeStartupNegotiatesVoiceSelectionFromTheConnectedGatewayHello() =
    runBlocking {
      val voiceMethods = listOf("talk.voice.get", "talk.voice.set", "talk.voice.complete")
      for ((methods, expectedCapability) in listOf(
        null to false,
        emptyList<String>() to false,
        voiceMethods.dropLast(1) to false,
        voiceMethods to true,
      )) {
        val creates = ConcurrentLinkedQueue<JsonObject>()
        val hello =
          buildJsonObject {
            put("snapshot", buildJsonObject { put("sessionDefaults", buildJsonObject { put("mainSessionKey", "main") }) })
            methods?.let { put("features", buildJsonObject { put("methods", JsonArray(it.map(::JsonPrimitive))) }) }
          }.toString()
        withStartedTalk(
          responseForRequest = { request, _ ->
            hello.takeIf { request["method"]?.jsonPrimitive?.content == "connect" }
          },
          interceptRequest = { request, socket ->
            if (request["method"]?.jsonPrimitive?.content == "talk.session.create") {
              val params = request.getValue("params").jsonObject
              creates += params
              val allowed = setOf("sessionKey", "mode", "transport", "brain", "language") + if (expectedCapability) setOf("capabilities") else emptySet()
              if (params.keys.any { it !in allowed }) {
                val id = request.getValue("id").jsonPrimitive.content
                socket.send("""{"type":"res","id":"$id","ok":false,"error":{"code":"INVALID_REQUEST","message":"invalid talk.session.create params: unexpected capabilities"}}""")
                return@withStartedTalk true
              }
            }
            false
          },
        ) { proof ->
          assertTrue(proof.manager.isListening.value)
          val create = creates.single()
          assertEquals(expectedCapability, create.containsKey("capabilities"))
          if (expectedCapability) assertEquals(JsonArray(listOf(JsonPrimitive("voice-selection"))), create["capabilities"])
          assertEquals("gateway-relay", create.getValue("transport").jsonPrimitive.content)
          assertEquals("main", create.getValue("sessionKey").jsonPrimitive.content)
        }
      }
    }

  @Test
  fun voiceChangeHandoffUsesItsOriginalLeaseAndWaitsForLocalReadiness() =
    runBlocking {
      for ((boundary, outcome) in listOf(
        "complete" to "ptt-resumed",
        "complete" to "ready",
        "complete" to "stop",
        "complete" to "cancelled",
        "complete" to "unconfirmed",
        "close" to "stop",
        "close" to "cancelled",
        "close" to "error",
        "close" to "retirement",
        "create" to "stop",
        "create" to "cancelled",
        "create" to "gateway",
      )) {
        val requests = ConcurrentLinkedQueue<JsonObject>()
        val held = CompletableDeferred<Pair<String, WebSocket>>()
        val frames = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        org.robolectric.shadows.ShadowAudioRecord.setSourceProvider {
          object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(
              bytes: ByteArray,
              offset: Int,
              size: Int,
              blocking: Boolean,
            ): Int {
              val frame = checkNotNull(frames.poll(10, TimeUnit.SECONDS)) { "Voice change capture did not stop" }
              frame.copyInto(bytes, destinationOffset = offset)
              return frame.size
            }
          }
        }
        try {
          withStartedTalk(
            responseForRequest = { request, _ ->
              if (request["method"]?.jsonPrimitive?.content == "talk.session.create" && request["params"]?.jsonObject?.containsKey("voiceChangeId") == true) {
                """{"relaySessionId":"replacement-relay"}"""
              } else if (request["method"]?.jsonPrimitive?.content == "talk.session.cancelOutput") {
                """{"ok":true,"status":"idle","turnId":"finished-turn"}"""
              } else {
                null
              }
            },
            interceptRequest = { request, socket ->
              requests += request
              val method = request.getValue("method").jsonPrimitive.content
              val params = request["params"]?.jsonObject
              val atBoundary =
                when (boundary) {
                  "close" -> method == "talk.session.close" && params?.get("sessionId")?.jsonPrimitive?.content == "playback-relay"
                  "create" -> method == "talk.session.create" && params?.containsKey("voiceChangeId") == true
                  else -> method == "talk.voice.complete" && params?.get("outcome")?.jsonPrimitive?.content == "ready"
                }
              if (atBoundary && !held.isCompleted) {
                held.complete(request.getValue("id").jsonPrimitive.content to socket)
                true
              } else {
                false
              }
            },
          ) { proof ->
            var capture: Thread? = null

            suspend fun awaitState(
              progress: () -> Unit = { proof.scheduler.runCurrent() },
              condition: () -> Boolean,
            ) {
              val deadline = System.nanoTime() + 5_000_000_000L
              while (!condition()) {
                progress()
                check(System.nanoTime() < deadline) { "Voice change did not reach $boundary/$outcome: ${proof.manager.statusText.value}" }
                withContext(Dispatchers.Default) { delay(10) }
              }
            }

            fun changes() = requests.filter { it["method"]?.jsonPrimitive?.content == "talk.session.create" }

            fun completions() = requests.filter { it["method"]?.jsonPrimitive?.content == "talk.voice.complete" }
            val change = """{"changeId":"change-1","voiceSessionId":"playback-relay","sessionKey":"main","voice":"cedar","phase":"requested"}"""
            try {
              assertTrue(
                changes()
                  .single()
                  .getValue("params")
                  .jsonObject
                  .getValue("capabilities")
                  .toString()
                  .contains("voice-selection"),
              )
              proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"transcript","role":"user","text":"Keep the agenda.","final":true}""")
              proof.manager.handleGatewayEvent("talk.voice.change", change.replace("playback-relay", "foreign-relay"))
              proof.manager.handleGatewayEvent("talk.voice.change", change.replace("main", "other-chat"))
              proof.scheduler.runCurrent()
              assertEquals(1, changes().size)
              assertFalse(requests.any { it["method"]?.jsonPrimitive?.content == "talk.session.close" })
              if (outcome == "retirement") {
                proof.manager.audioRetirement.retire(cleanup = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("Device cleanup failed")) })
              }
              proof.manager.handleGatewayEvent("talk.voice.change", change)
              proof.manager.handleGatewayEvent("talk.voice.change", change)
              proof.manager.setMainSessionKey("another-chat")
              proof.scheduler.runCurrent()
              proof.drainCancelledCapture()
              if (boundary == "complete") {
                awaitState { readPrivateField(proof.manager, "realtimeSessionId") == "replacement-relay" }
                assertTrue("Local media is not ready while its capture is queued", completions().isEmpty())
                if (outcome == "ptt-resumed") {
                  proof.manager.realtimeEvent("""{"relaySessionId":"replacement-relay","type":"responseStarted","turnId":"finished-turn"}""")
                  val finishPause = proof.manager.prepareRealtimeCapturePause("voice-change-ptt", proof.session.captureRequestLease())
                  proof.drainCancelledCapture()
                  val paused = async(start = CoroutineStart.UNDISPATCHED) { finishPause() }
                  awaitState(progress = proof.drainCancelledCapture) { paused.isCompleted }
                  paused.await()
                  proof.manager.resumeRealtimeCaptureAfterPushToTalk("voice-change-ptt")
                  // Recorder A has settled, but recorder B is still queued behind this continuation.
                  proof.scheduler.runCurrent()
                  assertTrue("PTT resume must not tear down the replacement", proof.manager.isEnabled.value)
                  assertTrue("The new recorder must be ready before confirming", completions().isEmpty())
                }
                capture = Thread { proof.drainCancelledCapture() }.also { it.start() }
              }
              awaitState { held.isCompleted }
              assertEquals(if (boundary == "close") 1 else 2, changes().size)
              if (boundary != "close") {
                val replacement = changes().last().getValue("params").jsonObject
                assertEquals("change-1", replacement.getValue("voiceChangeId").jsonPrimitive.content)
                assertEquals("main", replacement.getValue("sessionKey").jsonPrimitive.content)
                assertEquals("cedar", replacement.getValue("voice").jsonPrimitive.content)
              }
              assertFalse(requests.any { it["method"]?.jsonPrimitive?.content == "chat.abort" })
              assertEquals(if (outcome == "ptt-resumed") 1 else 0, requests.count { it["method"]?.jsonPrimitive?.content == "talk.session.cancelOutput" })
              when (outcome) {
                "stop" -> proof.manager.stopAllCapture()
                "gateway" -> proof.manager.onGatewayScopeChanging()
                "cancelled" -> proof.manager.handleGatewayEvent("talk.voice.change", change.replace("requested", "cancelled"))
              }
              val (id, socket) = held.await()
              if (outcome == "error") {
                socket.send("""{"type":"res","id":"$id","ok":false,"error":{"code":"UNAVAILABLE","message":"close failed"}}""")
              } else {
                val payload =
                  when {
                    boundary == "create" -> """{"relaySessionId":"replacement-relay"}"""
                    outcome == "unconfirmed" -> "{}"
                    else -> """{"ok":true}"""
                  }
                socket.send("""{"type":"res","id":"$id","ok":true,"payload":$payload}""")
              }
              if (outcome == "ready" || outcome == "ptt-resumed") {
                awaitState { proof.manager.statusText.value == "Listening" && readPrivateField(proof.manager, "realtimeVoiceChange") == null }
                assertTrue(proof.manager.isEnabled.value)
                assertEquals("replacement-relay", readPrivateField(proof.manager, "realtimeSessionId"))
                assertEquals(
                  "replacement-relay",
                  completions()
                    .single()
                    .getValue("params")
                    .jsonObject
                    .getValue("voiceSessionId")
                    .jsonPrimitive.content,
                )
                assertEquals(
                  "Keep the agenda.",
                  proof.manager.conversation.value
                    .single()
                    .text,
                )
              } else {
                awaitState { if (outcome == "gateway") readPrivateField(proof.manager, "realtimeSessionId") == null else !proof.manager.isEnabled.value }
                assertNull(readPrivateField(proof.manager, "realtimeSessionId"))
                if (outcome != "gateway") assertFalse(proof.manager.isEnabled.value)
                if (boundary == "close") assertEquals(1, changes().size)
                if (boundary != "complete") {
                  assertFalse(
                    completions().any {
                      it["params"]
                        ?.jsonObject
                        ?.get("outcome")
                        ?.jsonPrimitive
                        ?.content == "ready"
                    },
                  )
                }
                if (boundary == "create" && outcome != "gateway") {
                  awaitState {
                    requests.any {
                      it["method"]?.jsonPrimitive?.content == "talk.session.close" && it["params"]
                        ?.jsonObject
                        ?.get("sessionId")
                        ?.jsonPrimitive
                        ?.content == "replacement-relay"
                    }
                  }
                }
              }
            } finally {
              proof.manager.stopAllCapture()
              frames.offer(byteArrayOf())
              capture?.join(5_000)
              assertFalse("Voice change capture worker must terminate", capture?.isAlive == true)
              proof.drainCancelledCapture()
            }
          }
        } finally {
          org.robolectric.shadows.ShadowAudioRecord
            .clearSource()
        }
      }
    }

  private suspend fun withStartedTalk(
    sessionKey: String = "main",
    wakeSeed: VoiceWakeAudio? = null,
    captureStartGate: Deferred<Unit>? = null,
    captureRelayStopNotification: () -> ((() -> Boolean) -> Unit) = { {} },
    responseForRequest: (JsonObject, WebSocket) -> String? = { _, _ -> null },
    interceptRequest: (JsonObject, WebSocket) -> Boolean = { _, _ -> false },
    block: suspend (RealtimePlaybackProof) -> Unit,
  ) {
    val app = RuntimeEnvironment.getApplication()
    shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    val sessionJob = SupervisorJob()
    val managerJob = SupervisorJob()
    val scheduler = TestCoroutineScheduler()
    val managerScope = CoroutineScope(StandardTestDispatcher(scheduler) + managerJob)
    val captureTasks = ConcurrentLinkedQueue<Runnable>()
    val captureDispatcher =
      object : CoroutineDispatcher() {
        override fun dispatch(
          context: CoroutineContext,
          block: Runnable,
        ) {
          captureTasks.add(block)
        }
      }
    val connected = CompletableDeferred<Unit>()
    lateinit var manager: TalkModeManager
    val session =
      GatewaySession(
        scope = CoroutineScope(sessionJob + Dispatchers.Default),
        identityStore = testDeviceIdentityStore(app),
        deviceAuthStore = DeviceAuthStore(SecurePrefs(app, app.getSharedPreferences("talk-playback-${System.nanoTime()}", 0))),
        onConnected = { connected.complete(Unit) },
        onDisconnected = {},
        onEvent = { event, payload -> manager.handleGatewayEvent(event, payload) },
      )
    val synthesizer = FakeTalkSpeechSynthesizer()
    val player = FakeTalkAudioPlayer()
    var callbackDepth = 0
    manager =
      TalkModeManager(
        context = app,
        scope = managerScope,
        session = session,
        isConnected = { connected.isCompleted },
        talkSpeakClient = synthesizer,
        talkAudioPlayer = player,
        onBeforeSpeak = { callbackDepth += 1 },
        onAfterSpeak = { callbackDepth -= 1 },
        realtimeCaptureDispatcher = captureDispatcher,
        realtimePlaybackDispatcher = StandardTestDispatcher(scheduler),
        captureRelayStopNotification = captureRelayStopNotification,
      )
    val writes = mutableListOf<Triple<AudioTrack, ByteArray, AudioFormat>>()
    val listener = ShadowAudioTrack.OnAudioDataWrittenListener { track, bytes, format -> writes += Triple(track, bytes, format) }
    val server = MockWebServer()
    Dispatchers.setMain(StandardTestDispatcher(scheduler))
    try {
      try {
        server.enqueue(
          MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
              override fun onOpen(
                webSocket: WebSocket,
                response: Response,
              ) {
                webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"talk-playback-test","ts":1700000000123}}""")
              }

              override fun onMessage(
                webSocket: WebSocket,
                text: String,
              ) {
                val request = Json.parseToJsonElement(text).jsonObject
                if (request["type"]?.jsonPrimitive?.content != "req") return
                if (interceptRequest(request, webSocket)) return
                val id = request.getValue("id").jsonPrimitive.content
                val payload =
                  responseForRequest(request, webSocket) ?: when (request.getValue("method").jsonPrimitive.content) {
                    "connect" -> """{"features":{"methods":["talk.voice.get","talk.voice.set","talk.voice.complete"]},"snapshot":{"sessionDefaults":{"mainSessionKey":"main"}}}"""
                    "talk.config" -> """{"config":{}}"""
                    "talk.session.create" -> """{"relaySessionId":"playback-relay"}"""
                    else -> "{}"
                  }
                webSocket.send("""{"type":"res","id":"$id","ok":true,"payload":$payload}""")
                if (request.getValue("method").jsonPrimitive.content == "talk.session.create") {
                  val relaySessionId =
                    runCatching { Json.parseToJsonElement(payload).jsonObject["relaySessionId"]?.jsonPrimitive?.content }
                      .getOrNull()
                  if (!relaySessionId.isNullOrBlank()) {
                    // A production provider may win the race against create-commit. This keeps
                    // the harness on the same event path and exercises the early-ready handoff.
                    webSocket.send("""{"type":"event","event":"talk.event","payload":{"relaySessionId":"$relaySessionId","type":"ready"}}""")
                  }
                }
              }
            },
          ),
        )
        server.start()
        session.connect(
          endpoint =
            GatewayEndpoint(
              stableId = "manual|127.0.0.1|${server.port}",
              name = "Playback test",
              host = "127.0.0.1",
              port = server.port,
              tlsEnabled = false,
            ),
          token = "test-token",
          bootstrapToken = null,
          password = null,
          options =
            GatewayConnectOptions(
              role = "operator",
              scopes = listOf("operator.admin"),
              caps = emptyList(),
              commands = emptyList(),
              permissions = emptyMap(),
              client = GatewayClientInfo("openclaw-android", "Android playback test", "1.0.0-test", "android", "ui", "playback-test", "android", "test"),
            ),
        )
        withContext(Dispatchers.Default) { withTimeout(5_000) { connected.await() } }
        manager.setMainSessionKey(sessionKey)
        manager.setNextRealtimeWakeSeed(wakeSeed, captureStartGate = captureStartGate)
        manager.setEnabled(true)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!manager.isListening.value) {
          scheduler.runCurrent()
          check(System.nanoTime() < deadline) { "Real gateway session did not start realtime Talk: ${manager.statusText.value}" }
          withContext(Dispatchers.Default) { delay(10) }
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        block(
          RealtimePlaybackProof(
            manager = manager,
            scope = managerScope,
            scheduler = scheduler,
            synthesizer = synthesizer,
            player = player,
            writes = writes,
            session = session,
            drainNextCaptureTask = { captureTasks.poll()?.let { it.run(); true } ?: false },
            drainCancelledCapture = { while (true) (captureTasks.poll() ?: break).run() },
            callbackDepth = { callbackDepth },
          ),
        )
      } finally {
        manager.stopAllCapture()
        managerJob.cancel()
        scheduler.runCurrent()
        while (true) (captureTasks.poll() ?: break).run()
        scheduler.runCurrent()
        withTimeout(5_000) { managerJob.join() }
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.runCurrent()
        ShadowAudioTrack.removeAudioDataListener(listener)
        withContext(Dispatchers.Default) {
          session.disconnectAndJoin()
          sessionJob.cancelAndJoin()
          server.shutdown()
        }
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  private fun completeRemoteSynthesis(synthesizer: FakeTalkSpeechSynthesizer) {
    synthesizer.result.complete(
      TalkSpeakResult.Success(
        TalkSpeakAudio(byteArrayOf(1, 2, 3), "mp3_44100_128", "audio/mpeg", ".mp3"),
      ),
    )
  }

  private suspend fun TestScope.withStartedLocalPlayback(block: suspend (TalkModeManager, Job, FakeTalkAudioPlayer) -> Unit) {
    val synthesizer = FakeTalkSpeechSynthesizer()
    completeRemoteSynthesis(synthesizer)
    val player = FakeTalkAudioPlayer()
    val managerJob = SupervisorJob()
    val manager =
      createManager(
        talkSpeakClient = synthesizer,
        talkAudioPlayer = player,
        scope = CoroutineScope(coroutineContext + managerJob),
      )
    withMain {
      val playback = launch { manager.speakAssistantReply("Local reply") }
      try {
        runCurrent()
        assertEquals(1, player.playCalls)
        assertTrue(playback.isActive)
        assertTrue(manager.isSpeaking.value)
        block(manager, playback, player)
      } finally {
        manager.stopAllCapture()
        playback.cancelAndJoin()
        managerJob.cancelAndJoin()
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
      }
    }
  }

  @Test
  fun localFallbackKeepsWholeReplyAndBalancesCallbacksOnCompletionOrStop() =
    runTest {
      for (stopAfterFirst in listOf(false, true)) {
        val callbacks = mutableListOf<String>()
        val synthesizer = FakeTalkSpeechSynthesizer()
        synthesizer.result.complete(TalkSpeakResult.FallbackToLocal("Gateway TTS unavailable"))
        val manager =
          createManager(
            talkSpeakClient = synthesizer,
            scope = this,
            onBeforeSpeak = { callbacks += "before" },
            onAfterSpeak = { callbacks += "after" },
          )
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMAN)
        withMain(cleanup = { manager.stopAllCapture() }) {
          val reply = "Ein Wort. ".repeat(500).trim()
          val speech = launch { manager.speakAssistantReply("{\"language\":\"de\",\"speed\":1.25}\n$reply") }
          runCurrent()
          val shadow = shadowOf(checkNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
          shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
          runCurrent()

          assertEquals(listOf("before"), callbacks)
          assertTrue(manager.isSpeaking.value)
          assertEquals(Locale.GERMAN, shadow.currentLanguage)
          assertEquals(1, shadow.spokenTextList.size)
          if (stopAfterFirst) manager.stopTts()
          while (!speech.isCompleted) {
            val submitted = shadow.spokenTextList.size
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals(submitted + if (speech.isCompleted) 0 else 1, shadow.spokenTextList.size)
          }

          assertEquals(listOf("before", "after"), callbacks)
          assertFalse(manager.isSpeaking.value)
          val submittedText = shadow.spokenTextList.joinToString("")
          if (stopAfterFirst) {
            assertEquals(1, shadow.spokenTextList.size)
            assertTrue(reply.startsWith(submittedText) && submittedText.length < reply.length)
          } else {
            assertEquals(reply, submittedText)
          }
          assertTrue(shadow.spokenTextList.all { it.length <= TextToSpeech.getMaxSpeechInputLength() })
        }
        shadowOf(Looper.getMainLooper()).idle()
      }
    }

  @Test
  fun stopTtsDiscardsQueuedAudioWithoutStoppingLaterCapture() =
    runBlocking {
      val frames = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
      val reads =
        java.util.concurrent.atomic
          .AtomicInteger()
      val firstFrameQueued = CountDownLatch(1)
      val resumedFrameQueued = CountDownLatch(1)
      val appends = ConcurrentLinkedQueue<String>()
      val cancelled = CompletableDeferred<Pair<String, WebSocket>>()
      val resumed = CompletableDeferred<Unit>()
      org.robolectric.shadows.ShadowAudioRecord.setSourceProvider {
        object : org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
          override fun readInByteArray(
            bytes: ByteArray,
            offset: Int,
            size: Int,
            blocking: Boolean,
          ): Int {
            when (reads.incrementAndGet()) {
              2 -> firstFrameQueued.countDown()
              3 -> resumedFrameQueued.countDown()
            }
            val frame = checkNotNull(frames.poll(5, TimeUnit.SECONDS)) { "Capture fixture did not receive its next frame" }
            frame.copyInto(bytes, destinationOffset = offset)
            return frame.size
          }
        }
      }
      try {
        withStartedTalk(interceptRequest = { request, socket ->
          when (request.getValue("method").jsonPrimitive.content) {
            "talk.session.cancelOutput" -> {
              cancelled.complete(request.getValue("id").jsonPrimitive.content to socket)
              true
            }

            "talk.session.appendAudio" -> {
              appends +=
                request
                  .getValue("params")
                  .jsonObject
                  .getValue("audioBase64")
                  .jsonPrimitive.content
              resumed.complete(Unit)
              false
            }

            else -> {
              false
            }
          }
        }) { proof ->
          val gateway = readPrivateField(proof.manager, "session") as GatewaySession
          val transport = readPrivateField(gateway, "writeLock") as kotlinx.coroutines.sync.Mutex
          val lockOwner = Any()
          transport.lock(lockOwner)
          var held = true
          var capture: Thread? = null
          try {
            frames.put(byteArrayOf(1, 2))
            capture = Thread { proof.drainCancelledCapture() }.also { it.start() }
            assertTrue("Capture must queue the first frame", firstFrameQueued.await(5, TimeUnit.SECONDS))
            proof.drainCancelledCapture()
            proof.manager.realtimeEvent("""{"relaySessionId":"playback-relay","type":"audio","talkEvent":{"turnId":"old-turn"},"audioBase64":"AAA="}""")
            proof.manager.stopTts()
            proof.scheduler.runCurrent()
            assertNotNull(readPrivateField(proof.manager, "pendingRealtimeOutputClear"))

            transport.unlock(lockOwner)
            held = false
            val cancellationDeadline = System.nanoTime() + 5_000_000_000L
            while (!cancelled.isCompleted) {
              proof.drainCancelledCapture()
              proof.scheduler.runCurrent()
              check(System.nanoTime() < cancellationDeadline) { "Cancellation did not reach the peer" }
              withContext(Dispatchers.Default) { delay(10) }
            }
            val (id, socket) = withTimeout(5_000) { cancelled.await() }
            assertTrue("The peer must not receive a pre-cancellation frame", appends.isEmpty())
            socket.send("""{"type":"res","id":"$id","ok":true,"payload":{"ok":true,"status":"idle","turnId":"old-turn"}}""")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (readPrivateField(proof.manager, "pendingRealtimeOutputClear") != null) {
              proof.scheduler.runCurrent()
              check(System.nanoTime() < deadline) { "Cancellation did not settle" }
              withContext(Dispatchers.Default) { delay(10) }
            }

            frames.put(byteArrayOf(3, 4))
            assertTrue("Capture must queue a new frame after cancellation", resumedFrameQueued.await(5, TimeUnit.SECONDS))
            proof.drainCancelledCapture()
            withTimeout(5_000) { resumed.await() }
            assertEquals(listOf(Base64.encodeToString(byteArrayOf(3, 4), Base64.NO_WRAP)), appends.toList())
            assertTrue(proof.manager.isEnabled.value)
          } finally {
            if (held) transport.unlock(lockOwner)
            proof.manager.stopAllCapture()
            frames.offer(byteArrayOf())
            capture?.join(5_000)
            assertFalse("Capture worker must terminate", capture?.isAlive == true)
            proof.drainCancelledCapture()
          }
        }
      } finally {
        org.robolectric.shadows.ShadowAudioRecord
          .clearSource()
      }
    }

  @Test
  fun realtimeAudioFramesStreamUntilPlaybackOrCancellationStarts() {
    val manager = createManager()

    assertFalse(shouldAppendRealtimeCapturedFrame(manager, 0))
    assertTrue(shouldAppendRealtimeCapturedFrame(manager, 16))
    assertTrue(shouldAppendRealtimeCapturedFrame(manager, 4_800))

    setPrivateField(manager, "localMediaPlaybackActive", true)
    assertFalse(shouldAppendRealtimeCapturedFrame(manager, 4_800))
    setPrivateField(manager, "localMediaPlaybackActive", false)

    assertTrue(shouldAppendRealtimeCapturedFrame(manager, 4_800))
    setPrivateField(manager, "pendingRealtimeOutputClear", CompletableDeferred<String?>())
    assertFalse(shouldAppendRealtimeCapturedFrame(manager, 4_800))
    setPrivateField(manager, "pendingRealtimeOutputClear", null)
    assertTrue(shouldAppendRealtimeCapturedFrame(manager, 4_800))
  }

  @Test
  fun pushToTalkPauseWaitsForRealtimeCaptureJobs() =
    runTest {
      val manager = createManager()
      val captureJob = Job()
      val appendJob = Job()
      setPrivateField(manager, "realtimeCaptureJob", captureJob)
      setPrivateField(manager, "realtimeAppendJob", appendJob)
      setMutableStateFlow(manager, "_isEnabled", true)

      manager.prepareRealtimeCapturePause("capture-1", lease = null)()

      assertTrue(captureJob.isCancelled)
      assertTrue(appendJob.isCancelled)
      assertNull(readPrivateField(manager, "realtimeCaptureJob"))
      assertNull(readPrivateField(manager, "realtimeAppendJob"))
      assertTrue(readPrivateField(manager, "realtimeCapturePause") != null)
    }

  @Test
  fun pushToTalkWithoutOutputIdentityClosesRealtimeRelayWithoutWaitingForClear() =
    runTest {
      var stoppedByRelay = false
      val manager =
        createManager(
          scope = this,
          onStoppedByRelay = { stoppedByRelay = true },
        )
      installRealtimeSession(manager, "relay-1")
      setMutableStateFlow(manager, "_isEnabled", true)

      manager.prepareRealtimeCapturePause("capture-1", lease = null)()

      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertNull(readPrivateField(manager, "pendingRealtimeOutputClear"))
      val pause = readPrivateField(manager, "realtimeCapturePause")!!
      assertEquals("capture-1", readPrivateField(pause, "pttCaptureId"))
      assertTrue(readPrivateField(pause, "restartRelay") as Boolean)
      assertTrue(manager.isEnabled.value)
      assertFalse(stoppedByRelay)
    }

  @Test
  fun outputCancellationResultPreservesLegacyAndRecognizedRaceOutcomes() {
    val accepted =
      listOf(
        """{"ok":true}""" to null,
        """{"ok":true,"status":"applied"}""" to "applied",
        """{"ok":true,"turnId":"turn-1"}""" to null,
        """{"ok":true,"status":"applied","turnId":"turn-1"}""" to "applied",
        """{"ok":true,"status":"stale"}""" to "stale",
        """{"ok":true,"status":"idle"}""" to "idle",
      )

    accepted.forEach { (response, status) ->
      assertEquals(status, requireAcceptedRealtimeOutputCancellation(response, "turn-1").status)
    }
    assertEquals(
      "turn-from-server",
      requireAcceptedRealtimeOutputCancellation(
        """{"ok":true,"status":"applied","turnId":"turn-from-server"}""",
        null,
      ).turnId,
    )
  }

  @Test
  fun malformedOutputCancellationResultFailsClosed() {
    listOf(
      """{"ok":true,"status":"applied","turnId":"turn-2"}""",
      """{"status":"stale"}""",
      """{"ok":false}""",
      """{"ok":true,"status":"unknown"}""",
      """{"ok":true,"extra":1}""",
    ).forEach { response ->
      assertTrue(runCatching { requireAcceptedRealtimeOutputCancellation(response, "turn-1") }.isFailure)
    }
  }

  @Test
  fun stalePushToTalkCompletionCannotResumeNewerPause() =
    runTest {
      val manager = createManager()
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.prepareRealtimeCapturePause("capture-new", lease = null)()
      setPrivateField(manager, "activePttCaptureId", "capture-new")

      manager.resumeRealtimeCaptureAfterPushToTalk("capture-old")

      assertTrue(readPrivateField(manager, "realtimeCapturePause") != null)
      assertEquals("capture-new", readPrivateField(manager, "activePttCaptureId"))
    }

  @Test
  fun pushToTalkPauseOutlivesRecognitionWhileRelayConnects() =
    runTest {
      val manager = createManager()

      manager.prepareRealtimeCapturePause("capture-1", lease = null)()
      setPrivateField(manager, "activePttCaptureId", null)

      val pause = readPrivateField(manager, "realtimeCapturePause")
      assertTrue(pause != null)
      assertNull(readPrivateField(pause!!, "sessionId"))
      assertEquals("capture-1", readPrivateField(pause, "pttCaptureId"))

      manager.resumeRealtimeCaptureAfterPushToTalk("capture-1")

      assertNull(readPrivateField(manager, "realtimeCapturePause"))
    }

  @Test
  fun resumingRealtimeCaptureRestoresListeningState() =
    runTest {
      val manager =
        createManager(
          scope = this,
          realtimeCaptureDispatcher = StandardTestDispatcher(testScheduler),
        )
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.prepareRealtimeCapturePause("capture-1", lease = null)()
      val pause = readPrivateField(manager, "realtimeCapturePause")!!
      setPrivateField(pause, "sessionId", "relay-1")
      installRealtimeSession(manager, "relay-1")
      setMutableStateFlow(manager, "_isListening", false)
      manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"transcript","role":"user","text":"Question","final":true}""")

      manager.resumeRealtimeCaptureAfterPushToTalk("capture-1")

      assertTrue(manager.isListening.value)
      assertEquals("Listening", manager.statusText.value)
      assertTrue(readPrivateField(manager, "realtimeOutputSuppressed") as Boolean)

      manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"transcript","role":"user","text":"stale","final":true}""")

      assertTrue(readPrivateField(manager, "realtimeOutputSuppressed") as Boolean)

      manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"inputAudio","byteLength":4800}""")

      assertFalse(readPrivateField(manager, "realtimeOutputSuppressed") as Boolean)
      manager.stopAllCapture()
    }

  @Test
  fun replacementRelayPublishedDuringPushToTalkResumesCapture() =
    runTest {
      val manager =
        createManager(
          scope = this,
          realtimeCaptureDispatcher = StandardTestDispatcher(testScheduler),
        )
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.prepareRealtimeCapturePause("capture-1", lease = null)()
      val pause = readPrivateField(manager, "realtimeCapturePause")!!
      setPrivateField(pause, "sessionId", "relay-replacement")
      setPrivateField(pause, "restartRelay", true)
      installRealtimeSession(manager, "relay-replacement")

      manager.resumeRealtimeCaptureAfterPushToTalk("capture-1")

      assertNull(readPrivateField(manager, "realtimeCapturePause"))
      assertTrue(manager.isListening.value)
      assertTrue((readPrivateField(manager, "realtimeCaptureJob") as Job).isActive)
      assertTrue((readPrivateField(manager, "realtimeAppendJob") as Job).isActive)
      manager.stopAllCapture()
    }

  @Test
  fun stoppedTalkModeDoesNotRestartRelayAfterPushToTalk() =
    runTest {
      val manager = createManager(scope = this)
      manager.prepareRealtimeCapturePause("capture-1", lease = null)()
      val pause = readPrivateField(manager, "realtimeCapturePause")!!
      setPrivateField(pause, "restartRelay", true)
      setPrivateField(manager, "stopRequested", true)

      manager.resumeRealtimeCaptureAfterPushToTalk("capture-1")

      assertNull(readPrivateField(manager, "realtimeCapturePause"))
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertFalse(manager.isEnabled.value)
      assertEquals("Off", manager.statusText.value)
    }

  @Test
  fun pausedPushToTalkTurnSuppressesSpeechInterruptListener() =
    runTest {
      val manager = createManager(scope = this)
      setPrivateField(manager, "listeningMode", true)
      assertTrue(manager.shouldAllowSpeechInterrupt())

      manager.prepareRealtimeCapturePause("capture-1", lease = null)()

      assertFalse(manager.shouldAllowSpeechInterrupt())
      manager.resumeRealtimeCaptureAfterPushToTalk("capture-1")
      assertTrue(manager.shouldAllowSpeechInterrupt())
    }

  @Test
  fun finishingPushToTalkTurnRejectsReplacementCapture() =
    runTest {
      val manager = createManager(scope = this)
      setPrivateField(manager, "finishingPttCaptureId", "capture-1")

      val error =
        runCatching { manager.beginPushToTalk(allowNewCapture = true) }
          .exceptionOrNull()
      val oneShot = manager.beginPushToTalkOnce()

      assertEquals("PTT_BUSY: previous push-to-talk turn is still finishing", error?.message)
      assertTrue(oneShot is TalkPttOnceStart.Busy)
      assertEquals("capture-1", (oneShot as TalkPttOnceStart.Busy).payload.captureId)
    }

  @Test
  fun cancelledQueuedFinalizerResumesOnlyItsRealtimeCaptureOnMain() =
    runTest {
      val finalizerDispatcher = StandardTestDispatcher()
      val manager =
        createManager(
          scope = CoroutineScope(SupervisorJob() + finalizerDispatcher),
        )
      withMain(dispatcher = Dispatchers.Unconfined, cleanup = manager::stopAllCapture) {
        setMutableStateFlow(manager, "_isEnabled", true)
        manager.prepareRealtimeCapturePause("capture-1", lease = null)()
        setPrivateField(manager, "activePttCaptureId", "capture-1")
        @Suppress("UNCHECKED_CAST")
        (readPrivateField(manager, "pttFinalSegments") as MutableList<String>) += "finish this capture"

        val payload = manager.endPushToTalk("capture-1")
        val finalizer = readPrivateField(manager, "finishingPttJob") as Job

        assertEquals("queued", payload.status)
        assertEquals("capture-1", manager.finishingPushToTalkCaptureId)
        assertTrue(readPrivateField(manager, "realtimeCapturePause") != null)

        finalizer.cancel()
        finalizerDispatcher.scheduler.runCurrent()

        assertTrue(finalizer.isCancelled)
        assertNull(manager.finishingPushToTalkCaptureId)
        assertNull(readPrivateField(manager, "realtimeCapturePause"))
        assertNull(readPrivateField(manager, "activePttCaptureId"))
      }
    }

  @Test
  fun relayClosePreservesFinishingPushToTalkOwnership() =
    runTest {
      val manager = createManager(scope = this)
      manager.prepareRealtimeCapturePause("capture-1", lease = null)()
      installRealtimeSession(manager, "relay-1")
      setPrivateField(manager, "finishingPttCaptureId", "capture-1")

      manager.realtimeEvent("""{"relaySessionId":"relay-1","type":"close","reason":"completed"}""")

      assertNull(readPrivateField(manager, "realtimeCapturePause"))
      assertEquals("capture-1", manager.finishingPushToTalkCaptureId)
    }

  @Test
  fun disconnectedRelayDoesNotResumeAfterPushToTalk() =
    runTest {
      var stoppedByRelay = false
      val manager =
        createManager(
          scope = this,
          isConnected = { false },
          onStoppedByRelay = { stoppedByRelay = true },
        )
      setMutableStateFlow(manager, "_isEnabled", true)
      manager.prepareRealtimeCapturePause("capture-1", lease = null)()
      val pause = readPrivateField(manager, "realtimeCapturePause")!!
      setPrivateField(pause, "sessionId", "relay-1")
      installRealtimeSession(manager, "relay-1")
      setMutableStateFlow(manager, "_isListening", false)

      manager.resumeRealtimeCaptureAfterPushToTalk("capture-1")

      assertFalse(manager.isListening.value)
      assertFalse(manager.isEnabled.value)
      assertTrue(stoppedByRelay)
      assertEquals("Gateway not connected", manager.statusText.value)
      assertNull(readPrivateField(manager, "realtimeSessionId"))
      assertNull(readPrivateField(manager, "realtimeCaptureJob"))
      assertNull(readPrivateField(manager, "realtimeAppendJob"))
    }

  @Test
  fun chatFinalWaitUsesGatewayEventTimeout() =
    runTest {
      val manager = createManager(scope = this)

      setPrivateField(manager, "pendingRunId", "run-missing-final")
      setPrivateField(manager, "pendingFinal", CompletableDeferred<Boolean>())

      assertFalse(manager.waitForChatFinal("run-missing-final"))
      assertEquals(45_000, currentTime)
    }

  private fun createManager(
    talkSpeakClient: TalkSpeechSynthesizing = TalkSpeakClient(),
    talkAudioPlayer: TalkAudioPlaying? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    isConnected: () -> Boolean = { true },
    onBeforeSpeak: suspend () -> Unit = {},
    onAfterSpeak: suspend () -> Unit = {},
    onStoppedByRelay: (isCurrent: () -> Boolean) -> Unit = {},
    realtimeCaptureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    realtimePlaybackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    realtimeMarkAcknowledger: (suspend (String, String) -> Unit)? = null,
    onFinalVoiceTranscript: (String) -> Unit = {},
    onVoiceAgentCompleted: () -> Unit = {},
    onDirectVoiceAssistantTranscriptCompleted: () -> Unit = {},
    onVoiceSpeechDrained: () -> Unit = {},
  ): TalkModeManager {
    val app = RuntimeEnvironment.getApplication()
    val session =
      GatewaySession(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        identityStore = testDeviceIdentityStore(app),
        deviceAuthStore = DeviceAuthStore(SecurePrefs(app, app.getSharedPreferences("talk-mode-test-${System.nanoTime()}", 0))),
        onConnected = {},
        onDisconnected = {},
        onEvent = { _, _ -> },
      )
    return TalkModeManager(
      context = app,
      scope = scope,
      session = session,
      isConnected = isConnected,
      onBeforeSpeak = onBeforeSpeak,
      onAfterSpeak = onAfterSpeak,
      captureRelayStopNotification = { onStoppedByRelay },
      talkSpeakClient = talkSpeakClient,
      talkAudioPlayer = talkAudioPlayer ?: TalkAudioPlayer(app),
      realtimeCaptureDispatcher = realtimeCaptureDispatcher,
      realtimePlaybackDispatcher = realtimePlaybackDispatcher,
      realtimeMarkAcknowledger = realtimeMarkAcknowledger,
      onFinalVoiceTranscript = onFinalVoiceTranscript,
      onVoiceAgentCompleted = onVoiceAgentCompleted,
      onDirectVoiceAssistantTranscriptCompleted = onDirectVoiceAssistantTranscriptCompleted,
      onVoiceSpeechDrained = onVoiceSpeechDrained,
    ).also { setPrivateField(it, "relayStopNotification", onStoppedByRelay) }
  }

  private fun createRealtimeManager(): TalkModeManager = createManager().also { installRealtimeSession(it, "relay-1") }

  private suspend fun TestScope.withMain(
    dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    cleanup: () -> Unit = {},
    block: suspend () -> Unit,
  ) {
    Dispatchers.setMain(dispatcher)
    try {
      block()
    } finally {
      cleanup()
      Dispatchers.resetMain()
    }
  }

  private fun TalkModeManager.transcript(
    role: String,
    text: String,
    final: Boolean = false,
  ) = handleGatewayEvent("talk.event", realtimeTranscriptPayload(role, text, final))


  private fun realtimeOutputStatusOwnerForTest(
    manager: TalkModeManager,
    turnId: String,
  ): Any {
    val method = TalkModeManager::class.java.getDeclaredMethod("realtimeOutputStatusOwner", String::class.java)
    method.isAccessible = true
    return checkNotNull(method.invoke(manager, turnId))
  }

  private fun TalkModeManager.handleRealtimeAudioAppendFailureForTest(
    sessionId: String,
    inputGeneration: Long,
    error: GatewaySession.ErrorShape,
  ) {
    val method =
      TalkModeManager::class.java.getDeclaredMethod(
        "handleRealtimeAudioAppendFailure",
        String::class.java,
        Long::class.javaPrimitiveType,
        GatewaySession.ErrorShape::class.java,
      )
    method.isAccessible = true
    method.invoke(this, sessionId, inputGeneration, error)
  }

  private fun TalkModeManager.detachRealtimeCaptureForQuietForTest(): Boolean {
    val method = TalkModeManager::class.java.getDeclaredMethod("detachRealtimeCaptureForQuietLocked")
    method.isAccessible = true
    return method.invoke(this) as Boolean
  }

  private fun TalkModeManager.failRealtimeRelayForTest(
    sessionId: String,
    message: String,
  ) {
    val method =
      TalkModeManager::class.java.getDeclaredMethod(
        "failRealtimeRelay",
        String::class.java,
        String::class.java,
        RealtimePlayout.Session::class.java,
        Long::class.javaObjectType,
      )
    method.isAccessible = true
    method.invoke(this, sessionId, message, null, null)
  }
  private fun TalkModeManager.recordRealtimeInputSpeechForTest(sessionId: String) {
    val method = TalkModeManager::class.java.getDeclaredMethod("recordRealtimeInputSpeech", String::class.java)
    method.isAccessible = true
    method.invoke(this, sessionId)
  }

  private fun TalkModeManager.armPostAssistantFollowUpForTest() {
    val method = TalkModeManager::class.java.getDeclaredMethod("armPostAssistantFollowUpLocked")
    method.isAccessible = true
    method.invoke(this)
  }

  private fun TalkModeManager.finishRealtimeOrderedIngressForTest(
    sessionId: String,
    inputGeneration: Long,
  ) {
    val method =
      TalkModeManager::class.java.getDeclaredMethod(
        "finishRealtimeOrderedIngress",
        String::class.java,
        Long::class.javaPrimitiveType,
      )
    method.isAccessible = true
    method.invoke(this, sessionId, inputGeneration)
  }

  private fun TalkModeManager.armPostAssistantFollowUpAfterCaptureReadyForTest(
    sessionId: String,
    inputGeneration: Long,
    ready: Deferred<Unit>,
  ) {
    val method =
      TalkModeManager::class.java.getDeclaredMethod(
        "armPostAssistantFollowUpAfterCaptureReadyLocked",
        String::class.java,
        Long::class.javaPrimitiveType,
        Deferred::class.java,
      )
    method.isAccessible = true
    method.invoke(this, sessionId, inputGeneration, ready)
  }

  private fun TalkModeManager.isRealtimeAudioFrameIrreversiblySupersededForTest(
    sessionId: String,
    inputGeneration: Long,
    expectedOutputEpoch: Long?,
  ): Boolean {
    val method =
      TalkModeManager::class.java.getDeclaredMethod(
        "isRealtimeAudioFrameIrreversiblySupersededLocked",
        String::class.java,
        Long::class.javaPrimitiveType,
        Long::class.javaObjectType,
      )
    method.isAccessible = true
    return method.invoke(this, sessionId, inputGeneration, expectedOutputEpoch) as Boolean
  }

  private fun TalkModeManager.scheduleConversationIdleCheckForTest() {
    val method = TalkModeManager::class.java.getDeclaredMethod("scheduleConversationIdleCheckLocked")
    method.isAccessible = true
    method.invoke(this)
  }

  private fun advanceConversationClock(scope: TestScope, milliseconds: Long) {
    ShadowSystemClock.advanceBy(Duration.ofMillis(milliseconds))
    scope.testScheduler.advanceTimeBy(milliseconds)
    scope.testScheduler.runCurrent()
  }

  private fun TalkModeManager.realtimeEvent(payload: String) = handleGatewayEvent("talk.event", payload)

  private fun installSpeechRecognitionService() {
    val app = RuntimeEnvironment.getApplication()
    shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    val speechService = ComponentName(app, "TestSpeechRecognitionService")
    shadowOf(app.packageManager).apply {
      addServiceIfNotPresent(speechService)
      addIntentFilterForService(speechService, IntentFilter(RecognitionService.SERVICE_INTERFACE))
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun playbackGeneration(manager: TalkModeManager) = readPrivateField(manager, "playbackGeneration") as AtomicLong

  private fun installRealtimeSession(
    manager: TalkModeManager,
    id: String?,
  ) {
    setPrivateField(manager, "realtimeSessionId", id)
    val owner =
      if (id == null) {
        null
      } else {
        val lease =
          GatewaySession.RequestLease("synthetic-gateway") { _, _, _, enqueue ->
            enqueue {}
            "{}"
          }
        val method = TalkModeManager::class.java.getDeclaredMethod("createRealtimePlayoutSession", String::class.java, GatewaySession.RequestLease::class.java)
        method.isAccessible = true
        method.invoke(manager, id, lease)
      }
    setPrivateField(manager, "realtimePlayoutSession", owner)
  }

  private fun setPrivateField(
    target: Any,
    name: String,
    value: Any?,
  ) {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    field.set(target, value)
  }

  private fun pendingConsultEvent(relaySessionId: String, requestId: String, phase: String): String =
    """{"relaySessionId":"$relaySessionId","type":"consult.pending","requestId":"$requestId","phase":"$phase"}"""

  private fun readPrivateField(
    target: Any,
    name: String,
  ): Any? {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    return field.get(target)
  }

  private fun setTalkFailure(
    manager: TalkModeManager,
    text: NativeText,
  ) {
    val method = manager.javaClass.getDeclaredMethod("setTalkFailure", NativeText::class.java)
    method.isAccessible = true
    method.invoke(manager, text)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> setMutableStateFlow(
    target: Any,
    name: String,
    value: T,
  ) {
    (readPrivateField(target, name) as MutableStateFlow<T>).value = value
  }

  private fun shouldAppendRealtimeCapturedFrame(
    manager: TalkModeManager,
    length: Int,
  ): Boolean {
    val method =
      manager.javaClass.getDeclaredMethod(
        "shouldAppendRealtimeCapturedFrame",
        Int::class.javaPrimitiveType,
      )
    method.isAccessible = true
    return method.invoke(manager, length) as Boolean
  }

  private fun classifyRealtimeCapturedFrame(
    manager: TalkModeManager,
    sessionId: String,
    inputGeneration: Long,
    frameStartedAtMs: Long,
    frame: ByteArray,
  ): Any? {
    val method =
      manager.javaClass.getDeclaredMethod(
        "classifyRealtimeCapturedFrame",
        String::class.java,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        ByteArray::class.java,
        Int::class.javaPrimitiveType,
      )
    method.isAccessible = true
    return method.invoke(manager, sessionId, inputGeneration, frameStartedAtMs, frame, frame.size)
  }

  private fun recognitionListener(
    manager: TalkModeManager,
    captureId: String?,
  ): RecognitionListener {
    val owner =
      (readPrivateField(manager, "recognizer") as? SpeechRecognizer)
        ?: SpeechRecognizer.createSpeechRecognizer(RuntimeEnvironment.getApplication()).also { setPrivateField(manager, "recognizer", it) }
    val method = manager.javaClass.getDeclaredMethod("recognitionListener", String::class.java, SpeechRecognizer::class.java)
    method.isAccessible = true
    return method.invoke(manager, captureId, owner) as RecognitionListener
  }

  private fun silenceSegmentedRung(): Any {
    val clazz = Class.forName("ai.openclaw.app.voice.PushToTalkRecognitionRung\$SilenceSegmented")
    return requireNotNull(clazz.getField("INSTANCE").get(null))
  }

  private fun chatFinalPayload(
    runId: String,
    text: String,
    role: String = "assistant",
  ): String = """{"runId":"$runId","sessionKey":"main","state":"final","message":{"role":"$role","content":[{"type":"text","text":"$text"}]}}"""

  private fun realtimeTranscriptPayload(
    role: String,
    text: String,
    final: Boolean = false,
  ): String = """{"relaySessionId":"relay-1","type":"transcript","role":"$role","text":"$text","final":$final}"""
}

private data class RealtimePlaybackProof(
  val manager: TalkModeManager,
  val scope: CoroutineScope,
  val scheduler: TestCoroutineScheduler,
  val synthesizer: FakeTalkSpeechSynthesizer,
  val player: FakeTalkAudioPlayer,
  val writes: List<Triple<AudioTrack, ByteArray, AudioFormat>>,
  val session: GatewaySession,
  val drainNextCaptureTask: () -> Boolean,
  val drainCancelledCapture: () -> Unit,
  val callbackDepth: () -> Int,
)

private class FakeTalkSpeechSynthesizer : TalkSpeechSynthesizing {
  val requested = CompletableDeferred<Unit>()
  val result = CompletableDeferred<TalkSpeakResult>()

  override suspend fun synthesize(
    text: String,
    directive: TalkDirective?,
  ): TalkSpeakResult {
    requested.complete(Unit)
    return result.await()
  }
}

private class FakeTalkAudioPlayer : TalkAudioPlaying {
  val started = CompletableDeferred<Unit>()
  val finished = CompletableDeferred<Unit>()
  var stopped = false
  var playCalls = 0
    private set
  var stopCalls = 0
    private set

  override suspend fun play(audio: TalkSpeakAudio) {
    playCalls += 1
    started.complete(Unit)
    finished.await()
  }

  override fun stop() {
    stopCalls += 1
    stopped = true
  }
}

@org.robolectric.annotation.Implements(AudioTrack::class)
class DeadRealtimeAudioTrack : ShadowAudioTrack() {
  @org.robolectric.annotation.Implementation(minSdk = 23)
  override fun native_write_byte(
    audioData: ByteArray,
    offsetInBytes: Int,
    sizeInBytes: Int,
    format: Int,
    isBlocking: Boolean,
  ): Int = AudioTrack.ERROR_DEAD_OBJECT
}
