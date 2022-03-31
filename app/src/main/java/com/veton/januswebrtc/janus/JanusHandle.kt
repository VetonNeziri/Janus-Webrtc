package com.veton.januswebrtc.janus

import org.json.JSONObject
import java.math.BigInteger

data class JanusHandle(
    var handleId: BigInteger? = null,
    var feedId: BigInteger? = null,
    var display: String? = null,
    var onJoined: OnJoined? = null,
    var onRemoteJsep: OnRemoteJsep? = null,
    var onLeaving: OnJoined? = null
)

interface OnJoined {
    fun onJoined(jh: JanusHandle)
}

interface OnRemoteJsep {
    fun onRemoteJsep(jh: JanusHandle, jsep: JSONObject)
}
