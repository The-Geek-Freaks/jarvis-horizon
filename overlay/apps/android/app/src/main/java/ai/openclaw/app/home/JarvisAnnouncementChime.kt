package ai.openclaw.app.home

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.delay

/** A short local cue that shares the device's media route and volume with normal playback. */
internal class JarvisAnnouncementChime {
  suspend fun play() {
    val tone = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
    try {
      check(tone.startTone(ToneGenerator.TONE_PROP_BEEP, GOLD_NOTE_MILLIS.toInt())) { "Announcement first tone was not accepted" }
      delay(GOLD_NOTE_MILLIS)
      delay(GAP_MILLIS)
      check(tone.startTone(ToneGenerator.TONE_PROP_BEEP2, CYAN_NOTE_MILLIS.toInt())) { "Announcement second tone was not accepted" }
      delay(CYAN_NOTE_MILLIS)
      delay(TAIL_MILLIS)
      Log.i("JarvisAnnouncement", "chime complete durationMs=385")
    } finally {
      // Cancellation and failure release the generator before a later announcement can start.
      tone.release()
    }
  }

  private companion object {
    const val VOLUME = 52
    const val GOLD_NOTE_MILLIS = 120L
    const val GAP_MILLIS = 55L
    const val CYAN_NOTE_MILLIS = 180L
    const val TAIL_MILLIS = 30L
  }
}
