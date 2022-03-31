package com.veton.januswebrtc.janus

import com.veton.januswebrtc.webrtc.PeerConnectionClient
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.math.BigInteger

class JanusConnection {
    var handleId: BigInteger? = null
    var peerConnection: PeerConnection? = null
    var sdpObserver: PeerConnectionClient.SDPObserver? = null
    var videoTrack: VideoTrack? = null
    var surfaceVideoRender: SurfaceViewRenderer? = null
    var videoRenderer: VideoSink? = null
    var type = false
    var whichRender = 0
}