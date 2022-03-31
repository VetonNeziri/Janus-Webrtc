package com.veton.januswebrtc.janus

interface JanusErrorMessages {
    fun websocketFailure(message: String?)
    fun janusTransactionFailure(message: String?)
    fun jsonException(message: String?)
    fun noSuchRoom(message: String?)
}