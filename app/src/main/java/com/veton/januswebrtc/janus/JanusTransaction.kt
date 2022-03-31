package com.veton.januswebrtc.janus

import org.json.JSONObject

class JanusTransaction(
    var tid: String?,
    var success: TransactionCallbackSuccess?,
    var error: TransactionCallbackError?,
)

interface TransactionCallbackSuccess {
    fun success(jo: JSONObject)
}

interface TransactionCallbackError {
    fun error(jo: JSONObject)
}