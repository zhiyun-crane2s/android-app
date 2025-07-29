// app/src/main/java/me/rajtech/crane2s/webrtc/WebRTCManager.kt
package me.rajtech.crane2s.webrtc

import android.content.Context
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val iceServers: List<PeerConnection.IceServer>,
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit
) {
    private lateinit var factory     : PeerConnectionFactory
    private var peerConnection      : PeerConnection? = null

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        val egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(): PeerConnection =
        factory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) = onIceCandidate(candidate)
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) =
                    onConnectionState(newState)
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onDataChannel(dc: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(rtpReceiver: RtpReceiver, streams: Array<MediaStream>) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
                override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
                override fun onTrack(transceiver: RtpTransceiver) {}
            }
        )!!.also { peerConnection = it }

    fun setRemoteOfferAndCreateAnswer(
        remoteSdp: String,
        remoteType: String,
        onAnswer: (answerSdp: String, answerType: String) -> Unit
    ) {
        val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(remoteType), remoteSdp)
        peerConnection?.setRemoteDescription(SimpleObserver(), desc)
        peerConnection?.createAnswer(object : SimpleObserver() {
            override fun onCreateSuccess(answer: SessionDescription) {
                peerConnection?.setLocalDescription(SimpleObserver(), answer)
                onAnswer(answer.description, answer.type.canonicalForm())
            }
        }, MediaConstraints())
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int?, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex ?: 0, candidate))
    }

    fun dispose() {
        peerConnection?.dispose()
        factory.dispose()
    }

    private open class SimpleObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {}
        override fun onSetFailure(err: String?) {}
    }
}
