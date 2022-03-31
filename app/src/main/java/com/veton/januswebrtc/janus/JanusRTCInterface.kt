package com.veton.januswebrtc.janus

import org.json.JSONObject
import java.math.BigInteger

interface JanusRTCInterface {
    fun onPublisherJoined(handleId: BigInteger)
    fun onPublisherRemoteJsep(handleId: BigInteger, jsep: JSONObject)
    fun subscriberHandleRemoteJsep(handleId: BigInteger, jsep: JSONObject)
    fun onLeaving(handleId: BigInteger)
}