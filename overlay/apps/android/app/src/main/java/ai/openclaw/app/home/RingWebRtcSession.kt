package ai.openclaw.app.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.DataChannel
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.RtpTransceiver

/** Owns a single HA WebRTC peer and releases its decoder, EGL renderer, and signaling socket together. */
internal class RingWebRtcSession(
  private val context: Context,
  private val signal: (String) -> Unit,
  private val onError: () -> Unit,
  private val mode: RingWebRtcMode = RingWebRtcMode.ViewOnly,
  private val intercomMicrophone: RingIntercomMicrophoneLease? = null,
) {
  private var factory: PeerConnectionFactory? = null
  private var peer: PeerConnection? = null
  private var renderer: VideoSink? = null
  private var videoTrack: VideoTrack? = null
  private var audioTrack: AudioTrack? = null
  private var localIntercomTrack: AudioTrack? = null
  private var audioEnabled = false
  private val audioLock = Any()
  private var dataChannel: DataChannel? = null
  private val candidateLock = Any()
  private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
  private val pendingLocalCandidates = mutableListOf<IceCandidate>()
  private var sessionId: String? = null
  private var remoteDescriptionAccepted = false
  var onConnected: (() -> Unit)? = null
  var onLocalCandidate: ((String, IceCandidate) -> Unit)? = null
  var onRemoteDescriptionAccepted: (() -> Unit)? = null

  private val closeStarted = AtomicBoolean(false)
  private val closed: Boolean get() = closeStarted.get()
  private val callbackHandler = Handler(Looper.getMainLooper())

  fun attach(renderer: VideoSink, iceServers: List<PeerConnection.IceServer>, dataChannelName: String?) {
    if (closed) return
    this.renderer = renderer
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    factory = PeerConnectionFactory.builder()
      .setVideoEncoderFactory(DefaultVideoEncoderFactory(null, true, true))
      .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
      .createPeerConnectionFactory()
    peer = factory?.createPeerConnection(PeerConnection.RTCConfiguration(iceServers), observer())
    if (peer == null) {
      fail()
      return
    }
    peer?.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
    if (mode.offersMicrophone()) {
      // The lease was admitted by NodeRuntime before this explicit talkback
      // request. Creating this track is the only point at which the camera
      // session may touch microphone capture.
      val activeFactory = factory ?: run {
        fail()
        return
      }
      val localTrack = intercomMicrophone?.createTrack(activeFactory)
      if (localTrack == null) {
        fail()
        return
      }
      localIntercomTrack = localTrack
      peer?.addTransceiver(localTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
    } else {
      peer?.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
    }
    dataChannel = dataChannelName?.takeIf(String::isNotBlank)?.let { name -> peer?.createDataChannel(name, DataChannel.Init()) }?.also { channel ->
      channel.registerObserver(object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = Unit
        override fun onMessage(buffer: DataChannel.Buffer) = Unit
      })
    }
    peer?.createOffer(object : SdpObserver {
      override fun onCreateSuccess(sdp: SessionDescription) {
        if (closed) return
        peer?.setLocalDescription(object : EmptySdpObserver() {
          override fun onSetSuccess() {
            if (!closed) signal(sdp.description)
          }
          override fun onSetFailure(error: String) = fail()
        }, sdp)
      }
      override fun onCreateFailure(error: String) = fail()
      override fun onSetSuccess() = Unit
      override fun onSetFailure(error: String) = fail()
    }, MediaConstraints())
  }

  fun acceptAnswer(sdp: String) {
    if (closed) return
    peer?.setRemoteDescription(object : EmptySdpObserver() {
      override fun onSetSuccess() {
        if (closed) return
        val candidates = synchronized(candidateLock) {
          remoteDescriptionAccepted = true
          pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        candidates.forEach { peer?.addIceCandidate(it) }
        onRemoteDescriptionAccepted?.invoke()
      }
      override fun onSetFailure(error: String) = fail()
    }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
  }

  fun addCandidate(candidate: IceCandidate) {
    if (closed) return
    val queued = synchronized(candidateLock) {
      if (!remoteDescriptionAccepted) {
        pendingRemoteCandidates += candidate
        true
      } else {
        false
      }
    }
    if (!queued) peer?.addIceCandidate(candidate)
  }

  fun setSession(id: String) {
    if (closed) return
    val candidates = synchronized(candidateLock) {
      if (sessionId != null) return
      sessionId = id
      pendingLocalCandidates.toList().also { pendingLocalCandidates.clear() }
    }
    candidates.forEach { onLocalCandidate?.invoke(id, it) }
  }

  /** Enables receiver audio without changing Android's selected media route or audio focus. */
  fun setAudioEnabled(enabled: Boolean) {
    synchronized(audioLock) {
      audioEnabled = enabled
      audioTrack?.setEnabled(enabled)
    }
  }

  fun close() {
    if (!closeStarted.compareAndSet(false, true)) return
    disposeResources()
  }

  private fun disposeResources() {
    // Keep the microphone owner until the peer, track and AudioSource have all
    // retired. Re-arming wake before that boundary can make it compete with a
    // still-active WebRTC ADM capture on slow Android 11 hardware.
    try {
      runCatching { renderer?.let { videoTrack?.removeSink(it) } }
      videoTrack = null
      runCatching {
        synchronized(audioLock) {
          audioTrack?.setEnabled(false)
          audioTrack = null
          audioEnabled = false
        }
      }
      runCatching { localIntercomTrack?.setEnabled(false) }
      runCatching { localIntercomTrack?.dispose() }
      localIntercomTrack = null
      runCatching { dataChannel?.unregisterObserver() }
      runCatching { dataChannel?.dispose() }
      dataChannel = null
      runCatching { peer?.dispose() }
      peer = null
      // The source must be disposed before its factory, but wake remains muted
      // until the factory itself has released its native ADM resources.
      runCatching { intercomMicrophone?.disposeCapture() }
      runCatching { factory?.dispose() }
      factory = null
      renderer = null
      synchronized(candidateLock) {
        pendingRemoteCandidates.clear()
        pendingLocalCandidates.clear()
        sessionId = null
        remoteDescriptionAccepted = false
      }
    } finally {
      intercomMicrophone?.release()
    }
  }

  private fun fail() {
    if (!closeStarted.compareAndSet(false, true)) return
    // Native observer/SDP callbacks must unwind before PeerConnection.dispose.
    // Fence callbacks immediately; keep microphone ownership until teardown.
    callbackHandler.post {
      disposeResources()
      onError()
    }
  }

  private fun observer() = object : PeerConnection.Observer {
    override fun onIceCandidate(candidate: IceCandidate) {
      if (closed) return
      val id = synchronized(candidateLock) {
        sessionId ?: run {
          pendingLocalCandidates.add(candidate)
          null
        }
      }
      id?.let { onLocalCandidate?.invoke(it, candidate) }
    }
    override fun onAddStream(stream: org.webrtc.MediaStream) {
      bindAudioTrack(stream.audioTracks.firstOrNull())
      bindVideoTrack(stream.videoTracks.firstOrNull())
    }
    override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
      val track = transceiver.receiver.track()
      bindAudioTrack(track as? AudioTrack)
      bindVideoTrack(track as? VideoTrack)
    }
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
      if (closed) return
      if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) onConnected?.invoke()
      if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) fail()
    }
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
    override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<org.webrtc.MediaStream>) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
    override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
      if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) fail()
    }
    override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) = Unit
  }

  private fun bindVideoTrack(track: VideoTrack?) {
    if (closed || track == null || videoTrack === track) return
    renderer?.let { videoTrack?.removeSink(it) }
    videoTrack = track
    renderer?.let(track::addSink)
  }

  /**
   * Ring/HA sends the camera microphone as a receive-only WebRTC track.  It was
   * previously disabled on arrival, so even cameras with an advertised audio
   * m-line were silently rendered.  Keeping the track enabled lets WebRTC use
   * Android's current media route (including Bluetooth) without forcing speaker
   * mode or claiming audio focus from Jarvis' conversation path.
   */
  private fun bindAudioTrack(track: AudioTrack?) {
    if (closed || track == null) return
    synchronized(audioLock) {
      if (closed || audioTrack === track) return
      audioTrack?.setEnabled(false)
      audioTrack = track
      track.setEnabled(audioEnabled)
    }
  }
}

private open class EmptySdpObserver : SdpObserver {
  override fun onCreateSuccess(sdp: SessionDescription) = Unit
  override fun onSetSuccess() = Unit
  override fun onCreateFailure(error: String) = Unit
  override fun onSetFailure(error: String) = Unit
}
