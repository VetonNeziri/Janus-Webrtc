package com.veton.januswebrtc

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.veton.januswebrtc.databinding.ActivityVideoBinding
import com.veton.januswebrtc.janus.JanusConnection
import com.veton.januswebrtc.janus.JanusErrorMessages
import com.veton.januswebrtc.janus.JanusRTCInterface
import com.veton.januswebrtc.janus.WebSocketChannel
import com.veton.januswebrtc.webrtc.*
import com.veton.januswebrtc.webrtc.BaseFunctions.createVideoCapturer
import org.json.JSONObject
import org.webrtc.*
import timber.log.Timber
import java.math.BigInteger

class VideoActivity : AppCompatActivity(), PeerConnectionEvents, JanusRTCInterface,
    JanusErrorMessages {
    private lateinit var binding: ActivityVideoBinding

    private val url = "ws://85.10.222.248:8188"

//    "ws://85.10.222.248:8188"
//    wss://devjanus.vitalcheckups.com
    private val roomId = 1234
    private var startWithSpeakerPhone = true

    private val mWebSocketChannel: WebSocketChannel = WebSocketChannel(this, this)

    private var peerConnectionClient: PeerConnectionClient? = null
    private var audioManager: AppRTCAudioManager? = null

    private lateinit var rootEglBase: EglBase
    var videoCapturer: VideoCapturer? = null

    private val peerConnectionParameters: PeerConnectionParameters =
        PeerConnectionParameters(
            tracing = false,
            videoWidth = VIDEO_WIDTH,
            videoHeight = VIDEO_HEIGHT,
            videoFps = VIDEO_FPS,
            videoCodec = VIDEO_CODEC_VP8,
            videoCodecHwAcceleration = true,
            audioStartBitrate = 0,
            audioCodec = AUDIO_CODEC_OPUS,
            noAudioProcessing = false,
            useOpenSLES = false,
            disableBuiltInAEC = false,
            disableBuiltInAGC = false,
            disableBuiltInNS = false
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rootEglBase = EglBase.create()
        peerConnectionClient = PeerConnectionClient(this, rootEglBase, peerConnectionParameters)

        mWebSocketChannel.apply {
            initConnection(url, room = roomId)
        }

        createLocalRender()
        createRemoteRender()

        peerConnectionClient?.createPeerConnectionFactory(this)
        audioManager?.setSpeakerphoneOn(startWithSpeakerPhone)
        audioManager?.setMicrophoneMute(micMuted)

        setupClickHandlers()
    }

    override fun onResume() {
        super.onResume()
        peerConnectionClient?.startVideoSource()
    }

    override fun onPause() {
        super.onPause()
        peerConnectionClient?.stopVideoSource()
    }

    private fun offerPeerConnection(handleId: BigInteger) {
        videoCapturer = createVideoCapturer(this)
        if (videoCapturer == null){
            toastError("Video capturer is null")
            return
        }
        peerConnectionClient?.createPeerConnectionInternal(
            binding.localRender,
            videoCapturer!!,
            handleId)
        peerConnectionClient?.createOffer(handleId)
    }

    private fun createLocalRender() {
        binding.localRender.apply {
            init(rootEglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(true)
            setMirror(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    }

    private fun createRemoteRender() {
        binding.remote1.apply {
            init(rootEglBase.eglBaseContext, null)
        }
        binding.remote2.apply {
            init(rootEglBase.eglBaseContext, null)
        }
        binding.remote3.apply {
            init(rootEglBase.eglBaseContext, null)
        }
        binding.remote4.apply {
            init(rootEglBase.eglBaseContext, null)
        }
    }

    private fun setupClickHandlers() {
        binding.btnEndCall.forceHasOverlappingRendering(true)
        binding.btnEndCall.setOnClickListener {
            endCall(getString(R.string.you_ended_call))
        }

        binding.imgSwitchCamera.setOnClickListener {
            switchCamera()
        }

        binding.imgMic.setOnClickListener {
            muteUnMuteMic()
        }

        binding.imgSwitchAudio.setOnClickListener {
            switchAudio()
        }
    }

    private fun switchCamera() {
        peerConnectionClient?.switchCamera()
    }

    var micMuted = false
    private fun muteUnMuteMic() {
        micMuted = !micMuted
        audioManager?.setMicrophoneMute(micMuted)
        if (micMuted) {
            binding.imgMic.setImageResource(R.drawable.ic_mic_off)
        } else {
            binding.imgMic.setImageResource(R.drawable.ic_mic)
        }
    }

    private fun switchAudio() {
        startWithSpeakerPhone = !startWithSpeakerPhone
        if (startWithSpeakerPhone) {
            binding.imgSwitchAudio.setImageResource(R.drawable.ic_speaker)
        } else {
            binding.imgSwitchAudio.setImageResource(R.drawable.ic_launcher_foreground)
        }
        audioManager?.setSpeakerphoneOn(startWithSpeakerPhone)
        audioManager?.selectAudioDevice(if (startWithSpeakerPhone) AppRTCAudioManager.AudioDevice.SPEAKER_PHONE else AppRTCAudioManager.AudioDevice.EARPIECE)
    }

    private fun endCall(string: String) {
        cleanUpAndFinish()
    }

    @Synchronized
    private fun cleanUpAndFinish() {
        runOnUiThread {
            try {
                peerConnectionClient?.close()
                binding.localRender.release()
                binding.remote1.release()
                binding.remote2.release()
                binding.remote3.release()
                binding.remote4.release()
                mWebSocketChannel.close()
                peerConnectionClient?.clearAllConnections()

                rootEglBase.release()

                audioManager?.stop()
                audioManager = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
            finish()
        }
    }

    //********** EVENTS **********************
    override fun onLocalDescription(sdp: SessionDescription?, handleId: BigInteger?) {
        mWebSocketChannel.publisherCreateOffer(handleId, sdp)
        Timber.i("onLocalDescription: $sdp")
    }

    override fun onRemoteDescription(sdp: SessionDescription?, handleId: BigInteger?) {
        mWebSocketChannel.subscriberCreateAnswer(handleId, sdp)
        Timber.i("onRemoteDescription: $sdp")
    }

    override fun onIceCandidate(candidate: IceCandidate?, handleId: BigInteger?) {
        Timber.i("onIceCandidate: ")
        if (candidate != null) {
            mWebSocketChannel.trickleCandidate(handleId, candidate)
        } else {
            mWebSocketChannel.trickleCandidateComplete(handleId)
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate?>?) {

    }

    override fun onIceConnected() {
    }

    override fun onIceDisconnected() {

    }

    override fun onPeerConnectionClosed() {
        Timber.i("Peer connection is closed.")
    }

    override fun onPeerConnectionStatsReady(reports: Array<StatsReport?>?) {

    }

    override fun onPeerConnectionError(description: String?) {
        runOnUiThread {
            toastError(description ?: "onPeerConnectionError")
        }
    }

    var remotesCount = 0

    override fun onRemoteRender(connection: JanusConnection?) {
        runOnUiThread {
            binding.remoteRenders.visibility = View.VISIBLE
            binding.localRender.layoutParams.apply {
                width = 160F convertDpToPixels this@VideoActivity
                height = 240F convertDpToPixels this@VideoActivity
            }
            remotesCount++
            handleJoiningRemotes(connection)
        }
    }
    //***************************************************************


    private fun handleJoiningRemotes(connection: JanusConnection?) {
        when (remotesCount) {
            1 -> {
                connection?.apply {
                    surfaceVideoRender = binding.remote1
                    whichRender = 1
                    connection.videoRenderer = surfaceVideoRender
                    videoTrack?.addSink(surfaceVideoRender)
                }

                binding.second.visibility = View.GONE

                binding.remote1.visibility = View.VISIBLE
                binding.remote2.visibility = View.GONE
                binding.remote3.visibility = View.GONE
                binding.remote4.visibility = View.GONE
            }
            2 -> {
                binding.second.visibility = View.VISIBLE

                binding.remote1.visibility = View.VISIBLE
                binding.remote2.visibility = View.GONE
                binding.remote3.visibility = View.VISIBLE
                binding.remote4.visibility = View.GONE

                connection?.apply {
                    surfaceVideoRender = binding.remote3
                    whichRender = 3
                    connection.videoRenderer = surfaceVideoRender
                    videoTrack?.addSink(surfaceVideoRender)
                }
            }
            3 -> {
                binding.second.visibility = View.VISIBLE

                binding.remote1.visibility = View.VISIBLE
                binding.remote2.visibility = View.VISIBLE
                binding.remote3.visibility = View.VISIBLE
                binding.remote4.visibility = View.GONE

                connection?.apply {
                    surfaceVideoRender = binding.remote2
                    whichRender = 2
                    connection.videoRenderer = surfaceVideoRender
                    videoTrack?.addSink(surfaceVideoRender)
                }
            }
            4 -> {
                binding.second.visibility = View.VISIBLE

                binding.remote1.visibility = View.VISIBLE
                binding.remote2.visibility = View.VISIBLE
                binding.remote3.visibility = View.VISIBLE
                binding.remote4.visibility = View.VISIBLE

                connection?.apply {
                    surfaceVideoRender = binding.remote4
                    whichRender = 4
                    connection.videoRenderer = surfaceVideoRender
                    videoTrack?.addSink(surfaceVideoRender)
                }
            }
        }
    }


    private fun handleLeavingRemotes() {
        when (remotesCount) {
            0 -> {
                binding.remoteRenders.visibility = View.GONE
                binding.localRender.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            1 -> {
                binding.second.visibility = View.GONE

                binding.remote1.visibility = View.VISIBLE
                binding.remote2.visibility = View.GONE
                binding.remote3.visibility = View.GONE
                binding.remote4.visibility = View.GONE
            }
            2 -> {
                binding.second.visibility = View.VISIBLE

                binding.remote1.visibility = View.VISIBLE
                binding.remote2.visibility = View.GONE
                binding.remote3.visibility = View.VISIBLE
                binding.remote4.visibility = View.GONE
            }
            3 -> {
                binding.second.visibility = View.VISIBLE

                binding.remote1.visibility = View.VISIBLE
                binding.remote2.visibility = View.VISIBLE
                binding.remote3.visibility = View.VISIBLE
                binding.remote4.visibility = View.GONE
            }
        }
    }

    //******************* WEBSOCKET CALLBACKS **********************
    override fun onPublisherJoined(handleId: BigInteger) {
        offerPeerConnection(handleId)
        Timber.i("onPublisherJoined. Handle id: $handleId ")
    }

    override fun onPublisherRemoteJsep(handleId: BigInteger, jsep: JSONObject) {
        val type = SessionDescription.Type.fromCanonicalForm(jsep.optString("type"))
        val sdp = jsep.optString("sdp")
        val sessionDescription = SessionDescription(type, sdp)
        peerConnectionClient?.setRemoteDescription(handleId, sessionDescription)

        Timber.i("onPublisherRemoteJsep. sdp: $sessionDescription")
    }

    override fun subscriberHandleRemoteJsep(handleId: BigInteger, jsep: JSONObject) {
        val type = SessionDescription.Type.fromCanonicalForm(jsep.optString("type"))
        val sdp = jsep.optString("sdp")
        val sessionDescription = SessionDescription(type, sdp)
        peerConnectionClient?.subscriberHandleRemoteJsep(handleId, sessionDescription)

        Timber.i("subscriberHandleRemoteJsep. sdp: $sessionDescription")
    }

    override fun onLeaving(handleId: BigInteger) {
        runOnUiThread {
            val connection = peerConnectionClient?.getJanusConnectionByHandleId(handleId)
            if (connection?.surfaceVideoRender == null) {
                cleanUpAndFinish()
                return@runOnUiThread
            }
            connection.surfaceVideoRender?.visibility = View.GONE
            connection.videoTrack?.removeSink(connection.videoRenderer)
            remotesCount--

            handleLeavingRemotes()

            toastMsg("Some1 left with handle id: $handleId")
        }
    }
    //****************************************************************

    //*********** WEB SOCKET ERROR MESSAGES **************************
    override fun websocketFailure(message: String?) {
        showError(message)
    }

    override fun janusTransactionFailure(message: String?) {
        showError(message)
    }

    override fun jsonException(message: String?) {
        showError(message)
    }

    override fun noSuchRoom(message: String?) {
        showError(message)
        runOnUiThread {
            cleanUpAndFinish()
        }
    }
    //****************************************************************

    private fun showError(msg: String?) = runOnUiThread {
        toastError(msg)
    }
}