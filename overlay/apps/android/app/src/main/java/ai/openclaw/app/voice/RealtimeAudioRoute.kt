package ai.openclaw.app.voice

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * A realtime session keeps its selected route policy for its whole lifetime.
 *
 * Android 9–11 cannot simultaneously preserve A2DP media output and acquire a
 * communication/SCO route.  Selecting media before capture starts avoids the
 * mid-answer switch from a paired speaker back to the handset.
 */
internal enum class RealtimeAudioRoutePolicy {
  Communication,
  MediaOutput,
}

internal data class RealtimeAudioRouteContext(
  val sdkInt: Int = Build.VERSION.SDK_INT,
  val outputTypes: Set<Int>,
  val inputTypes: Set<Int>,
  val preferredInputKey: String?,
)

internal fun snapshotRealtimeAudioRouteContext(
  audioManager: AudioManager,
  preferredInputKey: String?,
): RealtimeAudioRouteContext =
  RealtimeAudioRouteContext(
    outputTypes = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(mutableSetOf()) { it.type },
    inputTypes = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapTo(mutableSetOf()) { it.type },
    preferredInputKey = preferredInputKey,
  )

internal fun selectRealtimeAudioRoutePolicy(context: RealtimeAudioRouteContext): RealtimeAudioRoutePolicy {
  val preferredType = context.preferredInputKey?.let(::audioInputDeviceOptionFromKey)?.type
  val hasA2dpOnlyOutput =
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in context.outputTypes &&
      AudioDeviceInfo.TYPE_BLUETOOTH_SCO !in context.outputTypes
  val hasScoInput = AudioDeviceInfo.TYPE_BLUETOOTH_SCO in context.inputTypes
  val explicitlyRequestedBluetoothMic = preferredType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
  return if (
    context.sdkInt in Build.VERSION_CODES.P..Build.VERSION_CODES.R &&
    hasA2dpOnlyOutput &&
    !hasScoInput &&
    !explicitlyRequestedBluetoothMic
  ) {
    RealtimeAudioRoutePolicy.MediaOutput
  } else {
    RealtimeAudioRoutePolicy.Communication
  }
}
