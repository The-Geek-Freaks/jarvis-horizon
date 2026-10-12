package ai.openclaw.app.home

import org.webrtc.AudioTrack
import org.webrtc.PeerConnectionFactory

/**
 * A camera view never captures this device's microphone.  Talkback is a
 * separate, explicit session so a normal live view cannot silently become a
 * microphone publisher during SDP negotiation.
 */
internal enum class RingWebRtcMode {
  ViewOnly,
  Intercom,
}

internal fun RingWebRtcMode.offersMicrophone(): Boolean = this == RingWebRtcMode.Intercom

/**
 * NodeRuntime owns admission to the microphone.  The WebRTC session receives
 * this lease only after the voice conversation has released capture.  It must
 * release the lease on every close and on every failed track construction.
 */
internal interface RingIntercomMicrophoneLease {
  fun createTrack(factory: PeerConnectionFactory): AudioTrack?
  /** Stops the Android capture source while the peer factory is still alive. */
  fun disposeCapture()
  fun release()
}
