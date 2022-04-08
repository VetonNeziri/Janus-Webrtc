package com.veton.januswebrtc.webrtc

import com.veton.januswebrtc.janus.JanusConnection
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import java.math.BigInteger
import java.util.concurrent.ScheduledExecutorService

open class SDPObserver(
    private val executor: ScheduledExecutorService,
    private val events: PeerConnectionEvents,
    private val isError: Boolean,
    private val observerInterface: ObserverInterface
) : SdpObserver {
    private var peerConnection: PeerConnection? = null
    private var sdpObserver: SDPObserver? = null
    private var handleId: BigInteger? = null
    private var type: Boolean = true

    fun setConnection(connection: JanusConnection) {
        peerConnection = connection.peerConnection
        sdpObserver = connection.sdpObserver
        handleId = connection.handleId
        type = connection.type
    }

    private var localSdp: SessionDescription? = null

    override fun onCreateSuccess(origSdp: SessionDescription?) {
        Timber.tag(PeerConnectionClient.TAG).e("SDP on create success$origSdp")
        val sdp = SessionDescription(origSdp?.type, origSdp?.description)
        localSdp = sdp
        executor.execute {
            if (peerConnection != null && !isError) {
                Timber.tag(PeerConnectionClient.TAG).d("Set local SDP from %s", sdp.type)
                peerConnection?.setLocalDescription(sdpObserver, sdp)
            }
        }
    }

    override fun onSetSuccess() {
        executor.execute {
            if (peerConnection == null || isError) {
                return@execute
            }
            if (type) {
                if (peerConnection?.remoteDescription == null) {
                    Timber.tag(PeerConnectionClient.TAG).d("Local SDP set successfully")
                    events.onLocalDescription(localSdp, handleId)
                } else {
                    Timber.tag(PeerConnectionClient.TAG).d("Remote SDP set successfully")
                }
            } else {
                if (peerConnection?.localDescription != null) {
                    Timber.tag(PeerConnectionClient.TAG).d("answer Local SDP set successfully")
                    events.onRemoteDescription(localSdp, handleId)
                } else {
                    Timber.tag(PeerConnectionClient.TAG).d("answer Remote SDP set successfully")
                }
            }
        }
    }

    override fun onCreateFailure(error: String?) {
        observerInterface.reportError("createSDP error: $error")
    }

    override fun onSetFailure(error: String?) {
        observerInterface.reportError("setSDP error: $error")
    }
}