package ai.openclaw.app.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Public-C-API wake-word bridge. The model is copied once from APK assets into
 * a versioned cache directory because the Sherpa C API accepts file paths,
 * while the vendor Kotlin API's AssetManager constructor does not expose the
 * absolute KWS segment clock needed for an inline spoken command.
 */
internal class SherpaWakeKeywordSpotter private constructor(
  private var nativePtr: Long,
) {
  @Synchronized
  fun createStream(): SherpaWakeKeywordStream {
    val spotter = requireOpen()
    val stream = nativeCreateStream(spotter)
    check(stream != 0L) { "Sherpa wake stream creation failed" }
    return SherpaWakeKeywordStream(this, stream)
  }

  /** Returns the absolute 16-kHz sample where the first detected token starts. */
  @Synchronized
  fun acceptAudio(
    stream: SherpaWakeKeywordStream,
    samples: FloatArray,
    sampleCount: Int = samples.size,
  ): Long? {
    require(stream.owner === this) { "Wake stream belongs to a different spotter" }
    require(sampleCount in 0..samples.size) { "Wake sample count is outside its buffer" }
    val result = nativeAcceptWaveform(stream.requireOpen(), samples, sampleCount, SAMPLE_RATE_HZ)
    return result.takeIf { it >= 0L }
  }

  /** Replaces the native stream so its C-API frame clock begins at zero again. */
  @Synchronized
  fun reset(stream: SherpaWakeKeywordStream) {
    require(stream.owner === this) { "Wake stream belongs to a different spotter" }
    nativeResetStream(stream.requireOpen())
  }

  @Synchronized
  fun release() {
    val spotter = nativePtr
    nativePtr = 0L
    if (spotter != 0L) nativeDestroy(spotter)
  }

  private fun requireOpen(): Long = nativePtr.takeIf { it != 0L } ?: error("Sherpa wake spotter is released")

  @Synchronized
  internal fun releaseStream(stream: SherpaWakeKeywordStream) {
    val ptr = stream.takeNativePtr()
    if (ptr != 0L) nativeDestroyStream(ptr)
  }

  companion object {
    private const val SAMPLE_RATE_HZ = 16_000

    init {
      System.loadLibrary("jarvis-wake-jni")
    }

    fun create(context: Context): SherpaWakeKeywordSpotter {
      val spotter = SherpaWakeKeywordSpotter(0L)
      val directory = SherpaWakeModelFiles.prepare(context.applicationContext)
      val ptr = spotter.nativeCreate(directory.absolutePath)
      check(ptr != 0L) { "Sherpa wake spotter creation failed" }
      spotter.nativePtr = ptr
      return spotter
    }
  }

  private external fun nativeCreate(directory: String): Long
  private external fun nativeDestroy(spotter: Long)
  private external fun nativeCreateStream(spotter: Long): Long
  private external fun nativeDestroyStream(stream: Long)
  private external fun nativeAcceptWaveform(stream: Long, samples: FloatArray, sampleCount: Int, sampleRateHz: Int): Long
  private external fun nativeResetStream(stream: Long)
}

internal class SherpaWakeKeywordStream internal constructor(
  internal val owner: SherpaWakeKeywordSpotter,
  private var nativePtr: Long,
) {
  internal fun requireOpen(): Long = nativePtr.takeIf { it != 0L } ?: error("Sherpa wake stream is released")
  internal fun takeNativePtr(): Long = nativePtr.also { nativePtr = 0L }
  fun release() = owner.releaseStream(this)
}

private object SherpaWakeModelFiles {
  private const val VERSION = "sherpa-kws-zh-en-3m-2025-12-20-int8-jarvis-phone1"
  private const val ASSET_DIRECTORY = "jarvis/wake-model"
  private const val READY_FILE = ".ready"
  private val files = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")
  private val lock = Any()

  fun prepare(context: Context): File = synchronized(lock) {
    val parent = File(context.cacheDir, "jarvis-wake-model")
    val destination = File(parent, VERSION)
    if (File(destination, READY_FILE).readTextOrNull() == VERSION && files.all { File(destination, it).isFile }) {
      return@synchronized destination
    }
    check(parent.isDirectory || parent.mkdirs()) { "Could not create Sherpa wake cache" }
    val staging = File(parent, ".$VERSION-${System.nanoTime()}.tmp")
    check(staging.mkdir()) { "Could not create Sherpa wake staging cache" }
    try {
      files.forEach { name ->
        context.assets.open("$ASSET_DIRECTORY/$name").use { input ->
          val target = File(staging, name)
          FileOutputStream(target).use { output ->
            input.copyTo(output)
            output.fd.sync()
          }
          check(target.length() > 0L) { "Empty Sherpa wake asset: $name" }
        }
      }
      File(staging, READY_FILE).writeText(VERSION, Charsets.UTF_8)
      if (destination.exists()) check(destination.deleteRecursively()) { "Could not replace incomplete Sherpa wake cache" }
      try {
        Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: Exception) {
        check(staging.renameTo(destination)) { "Could not publish Sherpa wake cache" }
      }
      destination
    } catch (failure: Throwable) {
      staging.deleteRecursively()
      throw failure
    }
  }

  private fun File.readTextOrNull(): String? = if (isFile) runCatching { readText(Charsets.UTF_8) }.getOrNull() else null
}
