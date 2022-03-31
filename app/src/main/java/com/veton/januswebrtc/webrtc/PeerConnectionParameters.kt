package com.veton.januswebrtc.webrtc

data class PeerConnectionParameters(
    val tracing: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Int = 0,
    val videoCodec: String? = null,
    val videoCodecHwAcceleration: Boolean = false,
    val audioStartBitrate: Int = 0,
    val audioCodec: String? = null,
    val noAudioProcessing: Boolean = false,
    val useOpenSLES: Boolean = false,
    val disableBuiltInAEC: Boolean = false,
    val disableBuiltInAGC: Boolean = false,
    val disableBuiltInNS: Boolean = false
)