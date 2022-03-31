package com.veton.januswebrtc.webrtc

import android.content.Context
import android.os.Environment
import com.veton.januswebrtc.janus.JanusConnection
import com.veton.januswebrtc.webrtc.BaseFunctions.createJavaAudioDevice
import org.webrtc.*
import org.webrtc.PeerConnection.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.voiceengine.WebRtcAudioManager
import org.webrtc.voiceengine.WebRtcAudioUtils
import timber.log.Timber
import java.io.File
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class PeerConnectionClient(
    private val context: Context,
    private val rootEglBase: EglBase,
    private val peerConnectionParameters: PeerConnectionParameters
) : Errors {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var factory: PeerConnectionFactory? = null
    private var options: PeerConnectionFactory.Options? = null
    private var events: PeerConnectionEvents? = null

    private var isError = false

    private val peerConnectionMap = ConcurrentHashMap<BigInteger, JanusConnection>()

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioConstraints: MediaConstraints? = null
    private var enableAudio = false

    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localRender: VideoSink? = null
    private var renderVideo = true
    private var preferredVideoCodec: String = VIDEO_CODEC_VP8
    private var videoWidth = peerConnectionParameters.videoWidth
    private var videoHeight = peerConnectionParameters.videoHeight
    private var videoFps = peerConnectionParameters.videoFps
    private var videoCapturerStopped = false
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // enableVideo is set to true if video should be rendered and sent.
    private var localVideoSender: RtpSender? = null

    private var statsTimer: Timer? = null

    private var pcConstraints: MediaConstraints = MediaConstraints().apply {
        optional.add(MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"))
    }
    private var sdpMediaConstraints: MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }
    private var mediaStream: MediaStream? = null

    init {
        executor.execute {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setFieldTrials(VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )
        }
    }

    fun createPeerConnectionFactory(events: PeerConnectionEvents) {
        // Reset states
        this.events = events
        factory = null
        videoCapturerStopped = false
        isError = false
        mediaStream = null
        videoCapturer = null
        renderVideo = true
        localVideoTrack = null
        remoteVideoTrack = null
        enableAudio = true
        localAudioTrack = null
        statsTimer = Timer()
        executor.execute {
            createPeerConnectionFactoryInternal()
        }
    }

    private fun createPeerConnectionFactoryInternal() {
        if (peerConnectionParameters.tracing) {
            PeerConnectionFactory.startInternalTracingCapture(
                Environment.getExternalStorageDirectory().absolutePath + File.separator
                        + "webrtc-trace.txt"
            )
        }
        Timber.i("Create peer connection factory. Use video: true")
        isError = false

        if (peerConnectionParameters.videoCodec != null) {
            if (peerConnectionParameters.videoCodec == VIDEO_CODEC_VP9) {
                preferredVideoCodec = VIDEO_CODEC_VP9
            } else if (peerConnectionParameters.videoCodec == VIDEO_CODEC_H264) {
                preferredVideoCodec = VIDEO_CODEC_H264
            }
        }
        Timber.i("Preferred video codec: $preferredVideoCodec")

        // Enable/disable OpenSL ES playback.
        if (!peerConnectionParameters.useOpenSLES) {
            Timber.d("Disable OpenSL ES audio even if device supports it")
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true /* enable */)
        } else {
            Timber.d("Allow OpenSL ES audio if device supports it")
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(false)
        }

        if (peerConnectionParameters.disableBuiltInAEC) {
            Timber.d("Disable built-in AEC even if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true)
        } else {
            Timber.d("Enable built-in AEC if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false)
        }

        if (peerConnectionParameters.disableBuiltInAGC) {
            Timber.d("Disable built-in AGC even if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true)
        } else {
            Timber.d("Enable built-in AGC if device supports it")
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false)
        }

        if (peerConnectionParameters.disableBuiltInNS) {
            Timber.d("Disable built-in NS even if device supports it")
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true)
        } else {
            Timber.d("Enable built-in NS if device supports it")
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false)
        }

        val adm: AudioDeviceModule? = createJavaAudioDevice(context, peerConnectionParameters, this)
        if (options != null) {
            Timber.d("Factory networkIgnoreMask option: %s" + options?.networkIgnoreMask)
        }
        val enableH264HighProfile =
            VIDEO_CODEC_H264_HIGH == peerConnectionParameters.videoCodec
        val encoderFactory: VideoEncoderFactory
        val decoderFactory: VideoDecoderFactory

        if (peerConnectionParameters.videoCodecHwAcceleration) {
            encoderFactory = DefaultVideoEncoderFactory(
                rootEglBase.eglBaseContext, true /* enableIntelVp8Encoder */, enableH264HighProfile
            )
            decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
        } else {
            encoderFactory = SoftwareVideoEncoderFactory()
            decoderFactory = SoftwareVideoDecoderFactory()
        }

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Timber.d("Peer connection factory created.")
    }

    fun getJanusConnectionByHandleId(handleId: BigInteger?) = peerConnectionMap[handleId]

    fun createPeerConnectionInternal(
        localRender: VideoSink,
        videoCapturer: VideoCapturer,
        handleId: BigInteger
    ) {
        this.localRender = localRender
        this.videoCapturer = videoCapturer

        executor.execute {
            try {
                createMediaConstraintsInternal()

                if (factory == null || isError) {
                    Timber.e("Peer connection factory is not created")
                    return@execute
                }

                val peerConnection = createPeerConnection(handleId, true)
                mediaStream = factory?.createLocalMediaStream("ARDAMS")
                mediaStream?.addTrack(createVideoTrack(videoCapturer))

                mediaStream?.addTrack(createAudioTrack())
                peerConnection?.addStream(mediaStream)
                findVideoSender(handleId)
            } catch (e: Exception) {
                reportError("Failed to create peer connection: ${e.message}")
                Timber.e(e.message)
            }
        }
    }

    private fun createPeerConnection(handleId: BigInteger, type: Boolean): PeerConnection? {
        val iceServer = IceServer("turn:numb.viagenie.ca:3478", "username", "password")
        val iceServer2 = IceServer("stun:stun.l.google.com:19302")
        val iceServers = mutableListOf(iceServer, iceServer2)

        val rtcConfig = RTCConfiguration(iceServers).apply {
            iceTransportsType = IceTransportsType.ALL
        }

        val janusConnection = JanusConnection().apply {
            this.handleId = handleId
            this.type = type
        }

        val pcObserver = PCObserver(janusConnection)
        val sdpObserver = SDPObserver(janusConnection)

        val peerConnection = factory?.createPeerConnection(rtcConfig, pcConstraints, pcObserver)

        janusConnection.sdpObserver = sdpObserver
        janusConnection.peerConnection = peerConnection

        peerConnectionMap[handleId] = janusConnection
        return peerConnection
    }

    private fun createMediaConstraintsInternal() {
        // If video resolution is not specified, default to HD.
        if (videoWidth == 0 || videoHeight == 0) {
            videoWidth = HD_VIDEO_WIDTH
            videoHeight = HD_VIDEO_HEIGHT
        }

        // If fps is not specified, default to 30.
        if (videoFps == 0) {
            videoFps = VIDEO_FPS
        }
        Timber.d("Capturing format: " + videoWidth + "x" + videoHeight + "@" + videoFps)

        // Create audio constraints.
        audioConstraints = MediaConstraints()
        // added for audio performance measurements
        if (peerConnectionParameters.noAudioProcessing) {
            Timber.d("Disabling audio processing")
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(
                    AUDIO_ECHO_CANCELLATION_CONSTRAINT,
                    "false"
                )
            )
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(
                    AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT,
                    "false"
                )
            )
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(
                    AUDIO_HIGH_PASS_FILTER_CONSTRAINT,
                    "false"
                )
            )
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(
                    AUDIO_NOISE_SUPPRESSION_CONSTRAINT,
                    "false"
                )
            )
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        audioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack?.setEnabled(enableAudio)
        return localAudioTrack
    }

    private fun createVideoTrack(capturer: VideoCapturer): VideoTrack? {
        videoSource = factory?.createVideoSource(capturer.isScreencast)
        surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        capturer.startCapture(videoWidth, videoHeight, videoFps)
        localVideoTrack = factory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        localVideoTrack?.setEnabled(renderVideo)
        localVideoTrack?.addSink(localRender)
        return localVideoTrack
    }

    private fun switchCameraInternal() {
        if (videoCapturer is CameraVideoCapturer) {
            Timber.d("Switch camera")
            val cameraVideoCapturer = videoCapturer as CameraVideoCapturer
            cameraVideoCapturer.switchCamera(null)
        } else {
            Timber.d("Will not switch camera, video caputurer is not a camera")
        }
    }

    fun switchCamera() {
        executor.execute { switchCameraInternal() }
    }

    fun changeCaptureFormat(width: Int, height: Int, frameRate: Int) {
        executor.execute { changeCaptureFormatInternal(width, height, frameRate) }
    }

    private fun changeCaptureFormatInternal(width: Int, height: Int, frameRate: Int) {
        if (isError || videoCapturer == null) {
            Timber.e("Failed to change capture format. Video: true. Error : $isError")
            return
        }
        Timber.d("changeCaptureFormat: " + width + "x" + height + "@" + frameRate)
        videoSource?.adaptOutputFormat(width, height, frameRate)
    }

    private fun findVideoSender(handleId: BigInteger) {
        val peerConnection: PeerConnection? = peerConnectionMap[handleId]?.peerConnection
        if (peerConnection == null) {
            Timber.e("Peer connection is null")
            return
        }
        for (sender in peerConnection.senders) {
            if (sender.track() != null) {
                val trackType = sender.track()!!.kind()
                if (trackType == VIDEO_TRACK_TYPE) {
                    Timber.d("Found video sender.")
                    localVideoSender = sender
                }
            }
        }
    }

    private fun getStats(handleId: BigInteger) {
        val peerConnection: PeerConnection? = peerConnectionMap[handleId]?.peerConnection
        val success = peerConnection?.getStats({ reports: Array<StatsReport?>? ->
            events?.onPeerConnectionStatsReady(
                reports
            )
        }, null)
        if (success == false) {
            Timber.e("getStats() returns false!")
        }
    }

    fun enableStatsEvents(enable: Boolean, periodMs: Int, handleId: BigInteger) {
        if (enable) {
            try {
                statsTimer?.schedule(object : TimerTask() {
                    override fun run() {
                        executor.execute { getStats(handleId) }
                    }
                }, 0, periodMs.toLong())
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.e(e, "Can not schedule statistics timer")
            }
        } else {
            statsTimer?.cancel()
        }
    }

    fun setAudioEnabled(enable: Boolean) {
        executor.execute {
            enableAudio = enable
            localAudioTrack?.setEnabled(enableAudio)
        }
    }

    fun setVideoEnabled(enable: Boolean) {
        executor.execute {
            renderVideo = enable
            localVideoTrack?.setEnabled(renderVideo)
            remoteVideoTrack?.setEnabled(renderVideo)
        }
    }

    fun startVideoSource() {
        executor.execute {
            if (videoCapturer != null && videoCapturerStopped) {
                Timber.d("Restart video source.")
                videoCapturer?.startCapture(videoWidth, videoHeight, videoFps)
                videoCapturerStopped = false
            }
        }
    }

    fun stopVideoSource() {
        executor.execute {
            if (videoCapturer != null && !videoCapturerStopped) {
                Timber.d("Stop video source.")
                try {
                    videoCapturer?.stopCapture()
                } catch (e: InterruptedException) {
                    Timber.e(e.message)
                }
                videoCapturerStopped = true
            }
        }
    }


    fun createOffer(handleId: BigInteger?) {
        executor.execute {
            val connection: JanusConnection? = peerConnectionMap[handleId]
            val peerConnection = connection?.peerConnection
            if (peerConnection != null && !isError) {
                Timber.d("PC Create OFFER")
                peerConnection.createOffer(connection.sdpObserver, sdpMediaConstraints)
            }
        }
    }

    fun setRemoteDescription(handleId: BigInteger?, sdp: SessionDescription) {
        Timber.i("setRemoteDescription (SessionDescription): $sdp")
        executor.execute {
            val peerConnection = peerConnectionMap[handleId]?.peerConnection
            val sdpObserver = peerConnectionMap[handleId]?.sdpObserver
            if (peerConnection == null || isError) {
                return@execute
            }
            peerConnection.setRemoteDescription(sdpObserver, sdp)
        }
    }

    fun subscriberHandleRemoteJsep(handleId: BigInteger?, sdp: SessionDescription) {
        Timber.i("subscriberHandleRemoteJsep (SessionDescription): $sdp")
        executor.execute {
            val peerConnection = createPeerConnection(handleId!!, false)
            val sdpObserver = peerConnectionMap[handleId]?.sdpObserver
            if (peerConnection == null || isError) {
                return@execute
            }
            val connection = peerConnectionMap[handleId]
            peerConnection.setRemoteDescription(sdpObserver, sdp)
            Timber.d("PC create ANSWER")
            peerConnection.createAnswer(connection?.sdpObserver, sdpMediaConstraints)
        }
    }


    override fun reportError(msg: String?) {
        Timber.e("Peer connection error: $msg")
        executor.execute {
            if (!isError) {
                events?.onPeerConnectionError(msg)
                isError = true
            }
        }
    }

    fun isHDVideo() = videoWidth * videoHeight >= HD_VIDEO_WIDTH * HD_VIDEO_HEIGHT

    fun clearAllConnections() = peerConnectionMap.clear()

    fun close() = executor.execute { closeInternal() }

    private fun closeInternal() {
        Timber.d("Closing peer connection.")
        statsTimer?.cancel()
        for ((_, value) in peerConnectionMap.entries) {
            if (value.peerConnection != null) {
                val peerConnection = value.peerConnection
                peerConnection?.dispose()
                Timber.i("closeInternal: ")
            }
        }
        Timber.d("Closing audio source.")
        audioSource?.dispose()
        audioSource = null
        Timber.d("Stopping capture.")
        if (videoCapturer != null) {
            try {
                videoCapturer?.stopCapture()
            } catch (e: InterruptedException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
            videoCapturerStopped = true
            videoCapturer?.dispose()
            videoCapturer = null
        }
        Timber.d("Closing video source.")
        videoSource?.dispose()
        videoSource = null
        Timber.d("Closing peer connection factory.")

        factory?.dispose()
        factory = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        options = null
        localRender = null

        Timber.d("Closing peer connection done.")
        events?.onPeerConnectionClosed()
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }


    inner class PCObserver(
        private val connection: JanusConnection
    ) : PeerConnection.Observer {
        override fun onSignalingChange(newState: SignalingState?) {
            Timber.d("SignalingState: $newState")
        }

        override fun onIceConnectionChange(newState: IceConnectionState?) {
            executor.execute {
                Timber.d("IceConnectionState: $newState")
                when (newState) {
                    IceConnectionState.CONNECTED -> {
                        events?.onIceConnected()
                    }
                    IceConnectionState.DISCONNECTED -> {
                        events?.onIceDisconnected()
                    }
                    IceConnectionState.FAILED -> {
                        reportError("ICE connection failed.")
                    }

                    else -> {}
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Timber.d("IceConnectionReceiving changed to $receiving")
        }

        override fun onIceGatheringChange(newState: IceGatheringState?) {
            Timber.d("IceGatheringState: $newState")
        }

        override fun onIceCandidate(candidates: IceCandidate?) {
            executor.execute {
                events?.onIceCandidate(candidates, connection.handleId)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate?>?) {
            executor.execute {
                events?.onIceCandidatesRemoved(candidates)
            }
        }

        override fun onAddStream(stream: MediaStream?) {
            executor.execute {
                if (isError && connection.peerConnection == null) {
                    return@execute
                }
                Timber.d(" onAddStream ")
                if (stream?.videoTracks?.size == 1) {
                    remoteVideoTrack = stream.videoTracks?.get(0)
                    remoteVideoTrack?.setEnabled(true)
                    connection.videoTrack = remoteVideoTrack
                    events?.onRemoteRender(connection)
                }
            }
        }

        override fun onRemoveStream(stream: MediaStream?) {
            executor.execute { remoteVideoTrack = null }
        }

        override fun onDataChannel(dc: DataChannel?) {
            Timber.d("New Data channel " + dc?.label())
        }

        override fun onRenegotiationNeeded() {

        }

        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {

        }

    }

    inner class SDPObserver(connection: JanusConnection) : SdpObserver {
        private var peerConnection = connection.peerConnection
        private var sdpObserver = connection.sdpObserver
        private var handleId: BigInteger? = connection.handleId
        private var type: Boolean = connection.type

        private var localSdp: SessionDescription? = null

        override fun onCreateSuccess(origSdp: SessionDescription?) {
            Timber.e("SDP on create success$origSdp")
            val sdp = SessionDescription(origSdp?.type, origSdp?.description)
            localSdp = sdp
            executor.execute {
                if (peerConnection != null && !isError) {
                    Timber.d("Set local SDP from " + sdp.type)
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
                        Timber.d("Local SDP set successfully")
                        events?.onLocalDescription(localSdp, handleId)
                    } else {
                        Timber.d("Remote SDP set successfully")
                    }
                } else {
                    if (peerConnection?.localDescription != null) {
                        Timber.d("answer Local SDP set successfully")
                        events?.onRemoteDescription(localSdp, handleId)
                    } else {
                        Timber.d("answer Remote SDP set successfully")
                    }
                }
            }
        }

        override fun onCreateFailure(error: String?) {
            reportError("createSDP error: $error")
        }

        override fun onSetFailure(error: String?) {
            reportError("setSDP error: $error")
        }

    }

}