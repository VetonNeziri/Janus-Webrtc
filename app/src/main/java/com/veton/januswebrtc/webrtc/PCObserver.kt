package com.veton.januswebrtc.webrtc

import com.veton.januswebrtc.janus.JanusConnection
import org.webrtc.*
import timber.log.Timber
import java.util.concurrent.ScheduledExecutorService

open class PCObserver(
    private val executor: ScheduledExecutorService,
    private val events: PeerConnectionEvents,
    private val isError: Boolean,
    private val observerInterface: ObserverInterface
) : PeerConnection.Observer {
    private var connection: JanusConnection? = null

    fun setConnection(connection: JanusConnection) {
        this.connection = connection
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
        Timber.tag(PeerConnectionClient.TAG).d("SignalingState: $newState")
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        executor.execute {
            Timber.tag(PeerConnectionClient.TAG).d("IceConnectionState: $newState")
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    events.onIceConnected()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    events.onIceDisconnected()
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    observerInterface.reportError("ICE connection failed.")
                }

                else -> {}
            }
        }
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Timber.tag(PeerConnectionClient.TAG).d("IceConnectionReceiving changed to $receiving")
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
        Timber.tag(PeerConnectionClient.TAG).d("IceGatheringState: $newState")
    }

    override fun onIceCandidate(candidates: IceCandidate?) {
        executor.execute {
            events.onIceCandidate(candidates, connection?.handleId)
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate?>?) {
        executor.execute {
            events.onIceCandidatesRemoved(candidates)
        }
    }

    override fun onAddStream(stream: MediaStream?) {
        executor.execute {
            if (isError && connection?.peerConnection == null) {
                return@execute
            }
            Timber.tag(PeerConnectionClient.TAG).d(" onAddStream ")
            if (stream?.videoTracks?.size == 1) {
                observerInterface.onAddStream(connection!!, stream)
            }
        }
    }

    override fun onRemoveStream(stream: MediaStream?) {
        executor.execute {
            observerInterface.onRemoveStream()
        }
    }

    override fun onDataChannel(dc: DataChannel?) {
        Timber.tag(PeerConnectionClient.TAG).d("New Data channel " + dc?.label())
    }

    override fun onRenegotiationNeeded() {

    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {

    }

}