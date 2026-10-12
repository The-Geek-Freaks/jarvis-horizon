package ai.openclaw.app.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Kotlin port of OpenClawKit's `TalkAudioLevel`: the shared 0..1 UI level scale
 * (dB full scale over a 50 dB window) used by every talk waveform surface, so
 * Android levels read identically to iOS/macOS. Change the Swift original in
 * `TalkPlaybackLevelMeters.swift` first; every constant mirrors it.
 */
internal object TalkAudioLevel {
  /**
   * Reusable PCM facts for one AudioRecord frame. Callers keep an instance for
   * their capture loop so level, RMS and peak do not require separate scans.
   */
  internal class Pcm16Metrics {
    var rms: Double = 0.0
      private set
    var level: Float = 0f
      private set
    var peak: Int = 0
      private set

    internal fun update(
      rms: Double,
      level: Float,
      peak: Int,
    ) {
      this.rms = rms
      this.level = level
      this.peak = peak
    }
  }

  fun normalized(rms: Double): Float = normalizedDecibels(20.0 * log10(max(rms, 1e-7)))

  fun normalizedDecibels(decibels: Double): Float = ((decibels + 50.0) / 50.0).coerceIn(0.0, 1.0).toFloat()

  /** Normalized level of little-endian PCM16 audio; 0 for empty frames. */
  fun pcm16Level(
    frame: ByteArray,
    length: Int,
  ): Float = normalized(pcm16Rms(frame, length))

  /** RMS of little-endian PCM16 audio in 0..1; 0 for empty or odd-length frames. */
  fun pcm16Rms(
    frame: ByteArray,
    length: Int,
  ): Double {
    var sum = 0.0
    var count = 0
    var index = 0
    val limit = length - (length % 2)
    while (index < limit) {
      val sample =
        ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8))
          .toShort()
          .toInt()
      val value = sample / Short.MAX_VALUE.toDouble()
      sum += value * value
      count += 1
      index += 2
    }
    if (count == 0) return 0.0
    return sqrt(sum / count)
  }

  /**
   * Calculates the same PCM16 RMS/level curve as [pcm16Rms] and [pcm16Level]
   * while also retaining the diagnostic peak in that single pass.
   */
  fun pcm16MetricsInto(
    frame: ByteArray,
    offset: Int,
    length: Int,
    destination: Pcm16Metrics,
  ) {
    var sum = 0.0
    var count = 0
    var peak = 0
    var index = offset
    val limit = offset + length - (length % 2)
    while (index < limit) {
      val sample =
        ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8))
          .toShort()
          .toInt()
      val value = sample / Short.MAX_VALUE.toDouble()
      sum += value * value
      peak = max(peak, kotlin.math.abs(sample))
      count += 1
      index += 2
    }
    val rms = if (count == 0) 0.0 else sqrt(sum / count)
    destination.update(rms = rms, level = normalized(rms), peak = peak)
  }

  /** iOS-parity level smoothing (new = old*0.8 + raw*0.2) for waveform meters. */
  fun smoothed(
    previous: Float,
    raw: Float,
  ): Float = previous * 0.8f + raw * 0.2f
}
