package com.veton.januswebrtc.webrtc

import com.veton.januswebrtc.janus.JanusConnection
import org.webrtc.MediaStream

interface ObserverInterface {
    fun reportError(msg: String?)
    fun onAddStream(connection: JanusConnection, stream: MediaStream?)
    fun onRemoveStream()
}