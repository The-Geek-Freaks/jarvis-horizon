package ai.openclaw.app.ui

import ai.openclaw.app.ui.horizon.JarvisHorizonLayout
import ai.openclaw.app.home.DoorCameraPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

class HorizonLayoutTest {
  @Test
  fun `only the small CRONOS panel keeps the fixed physical stage`() {
    assertEquals(JarvisHorizonLayout.EchoFixed, jarvisHorizonLayoutFor("cronos", 960))
    assertEquals(JarvisHorizonLayout.Adaptive, jarvisHorizonLayoutFor("cronos", 1_440))
    assertEquals(JarvisHorizonLayout.Adaptive, jarvisHorizonLayoutFor("h930", 1_440))
  }


  @Test
  fun `adaptive camera aligns with portrait and compact landscape stage contracts`() {
    assertEquals(HorizonCameraStage(14f, 360f, 332f, 290f), adaptiveHorizonCameraStage(360f, 720f))
    assertEquals(HorizonCameraStage(14f, 360f, 384f, 340f), adaptiveHorizonCameraStage(412f, 915f))
    assertEquals(HorizonCameraStage(270f, 52f, 516f, 240f), adaptiveHorizonCameraStage(800f, 360f))
  }

  @Test
  fun `Horizon camera stays voice first even in fullscreen`() {
    assertEquals(true, showsNativeRingControls(null))
    assertEquals(false, showsNativeRingControls(JarvisHorizonLayout.EchoFixed))
    assertEquals(false, showsNativeRingControls(JarvisHorizonLayout.Adaptive))
  }

  @Test
  fun `fullscreen camera voice indicator reports capture before wake readiness`() {
    assertEquals(
      CameraVoiceIndicatorTone.Listening,
      cameraVoiceIndicatorState(
        talkCaptureAdmitted = true,
        wakeListening = true,
        wakeEnabled = true,
        wakeStatus = "Listening",
        talkSpeaking = false,
      ).tone,
    )
    assertEquals(
      "JARVIS BEREIT",
      cameraVoiceIndicatorState(
        talkCaptureAdmitted = false,
        wakeListening = true,
        wakeEnabled = true,
        wakeStatus = "Listening",
        talkSpeaking = false,
      ).title,
    )
    assertEquals(
      CameraVoiceIndicatorTone.Listening,
      cameraVoiceIndicatorState(
        talkCaptureAdmitted = true,
        wakeListening = false,
        wakeEnabled = true,
        wakeStatus = "Listening",
        talkSpeaking = true,
      ).tone,
    )
  }

  @Test
  fun `fullscreen camera voice indicator distinguishes disabled paused and starting wake`() {
    assertEquals(
      CameraVoiceIndicatorTone.Disabled,
      cameraVoiceIndicatorState(false, false, false, "Paused", talkSpeaking = false).tone,
    )
    assertEquals(
      "MIKRO PAUSIERT",
      cameraVoiceIndicatorState(false, false, true, "Paused", talkSpeaking = false).title,
    )
    assertEquals(
      CameraVoiceIndicatorTone.Starting,
      cameraVoiceIndicatorState(false, false, true, "Starting…", talkSpeaking = false).tone,
    )
    assertEquals(
      "Jarvis spricht",
      cameraVoiceIndicatorState(false, false, true, "Paused", talkSpeaking = true).detail,
    )
    assertEquals(
      CameraVoiceIndicatorTone.Paused,
      cameraVoiceIndicatorState(false, false, true, "Listening", talkSpeaking = true).tone,
    )
  }

  @Test
  fun `adaptive PiP camera becomes fullscreen only while the device is landscape`() {
    assertEquals(
      DoorCameraPresentation.Fullscreen,
      effectiveDoorCameraPresentation(DoorCameraPresentation.PiP, JarvisHorizonLayout.Adaptive, isLandscape = true),
    )
    assertEquals(
      DoorCameraPresentation.PiP,
      effectiveDoorCameraPresentation(DoorCameraPresentation.PiP, JarvisHorizonLayout.Adaptive, isLandscape = false),
    )
    assertEquals(
      DoorCameraPresentation.PiP,
      effectiveDoorCameraPresentation(DoorCameraPresentation.PiP, JarvisHorizonLayout.EchoFixed, isLandscape = true),
    )
    assertEquals(
      DoorCameraPresentation.Fullscreen,
      effectiveDoorCameraPresentation(DoorCameraPresentation.Fullscreen, JarvisHorizonLayout.Adaptive, isLandscape = false),
    )
    assertEquals(
      DoorCameraPresentation.PiP,
      effectiveDoorCameraPresentation(
        DoorCameraPresentation.PiP,
        JarvisHorizonLayout.Adaptive,
        isLandscape = true,
        allowAutomaticLandscapeFullscreen = false,
      ),
    )
  }

  @Test
  fun `camera texture fit letterboxes without crop or stretch`() {
    assertEquals(
      CameraTextureFit(scaleX = 1f, scaleY = 0.3515625f),
      cameraTextureFit(800, 1_280, 1_920, 1_080),
    )
    assertEquals(
      CameraTextureFit(scaleX = 1f, scaleY = 0.9f),
      cameraTextureFit(1_280, 800, 1_920, 1_080),
    )
    assertEquals(CameraTextureFit(1f, 1f), cameraTextureFit(0, 800, 1_920, 1_080))
  }
}

