package ai.openclaw.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val WAKE_ACKNOWLEDGEMENT_TIMEOUT_MS = 2_000L
internal const val WAKE_ACKNOWLEDGEMENT_TAIL_MS = 300L
private const val WAKE_ACKNOWLEDGEMENT_LOG_TAG = "JarvisWakeAck"

internal val jarvisWakeAcknowledgementAssets =
  listOf(
    "jarvis/wake/bin_da.wav",
    "jarvis/wake/ja_boss.wav",
    "jarvis/wake/was_gibts.wav",
  )

internal fun jarvisWakeAcknowledgementAssetAt(index: Int): String =
  jarvisWakeAcknowledgementAssets[Math.floorMod(index, jarvisWakeAcknowledgementAssets.size)]

/** Preparation and playback share the acknowledgement's single bounded wait. */
internal suspend fun awaitJarvisWakeAcknowledgement(
  prepared: Deferred<Unit>,
  completed: Deferred<Unit>,
  timeoutMs: Long = WAKE_ACKNOWLEDGEMENT_TIMEOUT_MS,
): Boolean =
  withTimeoutOrNull(timeoutMs) {
    prepared.await()
    completed.await()
    true
  } ?: false

/**
 * Local, synthesized neutral chime acknowledgement for the fixed Jarvis wake phrase. It is
 * intentionally independent of providers and is fully retired before capture
 * moves on to the actual request.
 */
internal class JarvisWakeAcknowledgement(
  private val context: Context,
  private val tailMs: Long = WAKE_ACKNOWLEDGEMENT_TAIL_MS,
) {
  private val playbackMutex = Mutex()
  private var nextAssetIndex = 0

  suspend fun playIfEnabled(playbackEnabled: Boolean): Boolean {
    if (!playbackEnabled) return false
    return playbackMutex.withLock {
      val asset = jarvisWakeAcknowledgementAssetAt(nextAssetIndex)
      nextAssetIndex = (nextAssetIndex + 1) % jarvisWakeAcknowledgementAssets.size
      playAsset(asset)
    }
  }

  private suspend fun playAsset(asset: String): Boolean {
    val completed = CompletableDeferred<Unit>()
    val prepared = CompletableDeferred<Unit>()
    val disposed = AtomicBoolean(false)
    var player: MediaPlayer? = null
    val started = AtomicBoolean(false)
    var outcome = "error"
    val completedNormally =
      try {
        withContext(Dispatchers.Main.immediate) {
          context.assets.openFd(asset).use { descriptor ->
            MediaPlayer().also { player = it }.apply {
              setAudioAttributes(speechPlaybackAttributes())
              setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
              setOnCompletionListener { completed.complete(Unit) }
              setOnErrorListener { _, _, _ ->
                val error = IllegalStateException("wake acknowledgement playback failed")
                prepared.completeExceptionally(error)
                completed.completeExceptionally(error)
                true
              }
              setOnPreparedListener { active ->
                if (disposed.get()) return@setOnPreparedListener
                try {
                  active.start()
                  started.set(true)
                  prepared.complete(Unit)
                  Log.i(WAKE_ACKNOWLEDGEMENT_LOG_TAG, "start asset=${asset.substringAfterLast('/')}")
                } catch (error: Throwable) {
                  prepared.completeExceptionally(error)
                  completed.completeExceptionally(error)
                }
              }
              prepareAsync()
            }
          }
        }
        awaitJarvisWakeAcknowledgement(prepared, completed)
          .also { completed -> outcome = if (completed) "complete" else "timeout" }
      } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        false
      } finally {
        disposed.set(true)
        withContext(NonCancellable + Dispatchers.Main.immediate) {
          player?.let { active ->
            active.setOnPreparedListener(null)
            active.setOnCompletionListener(null)
            active.setOnErrorListener(null)
            runCatching { active.stop() }
            active.release()
          }
        }
      }
    // A timeout or decoder error can still leave a short audible tail. Keep
    // capture out of that tail whenever start() actually accepted the clip.
    if (started.get() && tailMs > 0) kotlinx.coroutines.delay(tailMs)
    if (started.get()) Log.i(WAKE_ACKNOWLEDGEMENT_LOG_TAG, "$outcome asset=${asset.substringAfterLast('/')} tailMs=$tailMs")
    return completedNormally
  }
}
