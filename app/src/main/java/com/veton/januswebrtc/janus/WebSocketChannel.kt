package com.veton.januswebrtc.janus

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import timber.log.Timber
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


class WebSocketChannel(
    private val delegate: JanusRTCInterface,
    private val errorListener: JanusErrorMessages
) {
    private var mWebSocket: WebSocket? = null
    private val transactions: ConcurrentHashMap<String, JanusTransaction> = ConcurrentHashMap<String, JanusTransaction>()
    private val handles: ConcurrentHashMap<BigInteger, JanusHandle> = ConcurrentHashMap<BigInteger, JanusHandle>()
    private val feeds: ConcurrentHashMap<BigInteger, JanusHandle> = ConcurrentHashMap<BigInteger, JanusHandle>()
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var mSessionId: BigInteger? = null

    private var videoRoom = 1234

    fun initConnection(url: String, room: Int) {
        videoRoom = room
        val httpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                builder.addHeader("Sec-WebSocket-Protocol", "janus-protocol")
                chain.proceed(builder.build())
            }.connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()

        mWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                createSession(room)
                Timber.tag(TAG).i("OnWebsocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                this@WebSocketChannel.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {

            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.tag(TAG).i("OnWebsocket closing")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                transactions.clear()
                handles.clear()
                feeds.clear()

                Timber.tag(TAG).i("OnWebsocket closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.tag(TAG).e("onFailure $t")
                errorListener.websocketFailure(t.message)
            }
        })
    }

    private fun onMessage(message: String) {
        if (!message.contains("ack")) {
            Timber.tag(TAG).i("onMessage $message")
        }
        try {
            val jo = JSONObject(message)
            val janus = jo.optString("janus")
            if (janus == "success") {
                val transaction = jo.optString("transaction")
                val jt = transactions[transaction]
                if (jt?.success != null) {
                    jt.success?.success(jo)
                }

                transactions.remove(transaction)
            } else if (janus == "error") {
                val transaction = jo.optString("transaction")
                val jt = transactions[transaction]
                if (jt?.error != null) {
                    jt.error?.error(jo)
                    errorListener.janusTransactionFailure(jo.toString())
                }
                transactions.remove(transaction)
            } else if (janus == "ack") {
                Timber.tag(TAG).i("ack")
            } else {
                val handle: JanusHandle? = handles[BigInteger(jo.optString("sender"))]
                if (handle == null) {
                    Timber.tag(TAG).i("missing handle")
                } else if (janus == "event") {
                    val pluginData = jo.optJSONObject("plugindata") ?: return
                    val plugin = pluginData.optJSONObject("data") ?: return

                    if (!plugin.optString("error").isNullOrEmpty()) {
                        // Todo: Not sure its always shows no such room
                        errorListener.noSuchRoom(plugin.optString("error"))
                    }

                    if (plugin.optString("videoroom") == "joined") {
                        handle.onJoined?.onJoined(handle)
                    }

                    val publishers = plugin.optJSONArray("publishers")
                    if (publishers != null && publishers.length() > 0) {
                        var i = 0
                        val size = publishers.length()
                        while (i <= size - 1) {
                            val publisher = publishers.optJSONObject(i)
                            val feed = BigInteger(publisher.optString("id"))
                            val display = publisher.optString("display")
                            subscriberCreateHandle(feed, display)
                            i++
                        }
                    }
                    val leaving = plugin.optString("leaving")
                    if (!TextUtils.isEmpty(leaving)) {
                        val jhandle: JanusHandle? = feeds[BigInteger(leaving)]
                        jhandle?.onLeaving?.onJoined(jhandle)
                    }
                    val jsep = jo.optJSONObject("jsep")
                    if (jsep != null) {
                        handle.onRemoteJsep?.onRemoteJsep(handle, jsep)
                    }
                } else if (janus == "detached") {
                    handle.onLeaving?.onJoined(handle)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
    }

    private fun createSession(room: Int) {
        val transaction = randomString()
        val jt = JanusTransaction(
            tid = transaction,
            success = object : TransactionCallbackSuccess {
                override fun success(jo: JSONObject) {
                    val data = jo.optJSONObject("data")
                    if (data != null) {
                        mSessionId = BigInteger(data.optString("id"))
                        mHandler.post(fireKeepAlive)
                        publisherCreateHandle(room)
                    } else {
                        errorListener.janusTransactionFailure("createSession -> data is null")
                    }
                }
            },
            error = object : TransactionCallbackError {
                override fun error(jo: JSONObject) {
                    errorListener.janusTransactionFailure("createSession -> $jo")
                }
            })

        transactions[transaction] = jt
        val msg = JSONObject()
        try {
            msg.putOpt("janus", "create")
            msg.putOpt("transaction", transaction)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(msg.toString())
        Timber.tag(TAG).i(msg.toString())
    }

    private fun publisherCreateHandle(room: Int) {
        val transaction = randomString()
        val jt = JanusTransaction(
            tid = transaction,
            success = object : TransactionCallbackSuccess {
                override fun success(jo: JSONObject) {
                    val janusHandle = JanusHandle()
                    val data = jo.optJSONObject("data")
                    if (data != null) {
                        janusHandle.handleId = BigInteger(data.optString("id"))
                    } else {
                        Timber.tag(TAG).e("publisherCreateHandle -> data is null.")
                    }
                    janusHandle.onJoined = object : OnJoined {
                        override fun onJoined(jh: JanusHandle) {
                            jh.handleId?.let { delegate.onPublisherJoined(it) }
                        }
                    }
                    janusHandle.onRemoteJsep = object : OnRemoteJsep {
                        override fun onRemoteJsep(jh: JanusHandle, jsep: JSONObject) {
                            jh.handleId?.let { delegate.onPublisherRemoteJsep(it, jsep) }
                        }
                    }
                    handles[janusHandle.handleId!!] = janusHandle
                    publisherJoinRoom(janusHandle, room)
                }
            },
            error = object : TransactionCallbackError {
                override fun error(jo: JSONObject) {
                    errorListener.janusTransactionFailure("publisherCreateHandle -> $jo")
                }
            })
        transactions[transaction] = jt
        val msg = JSONObject()
        try {
            msg.putOpt("janus", "attach")
            msg.putOpt("plugin", "janus.plugin.videoroom")
            msg.putOpt("transaction", transaction)
            msg.putOpt("session_id", mSessionId)
            msg.put("opaque_id", "videoroomtest-nV9jOV3mkYMe")
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(msg.toString())
        Timber.tag(TAG).i(msg.toString())
    }

    private fun publisherJoinRoom(handle: JanusHandle, room: Int) {
        val msg = JSONObject()
        val body = JSONObject()
        try {
            body.putOpt("request", "join")
            body.putOpt("room", room)
            body.putOpt("ptype", "publisher")
            body.putOpt("display", "Veton")

            msg.putOpt("janus", "message")
            msg.putOpt("body", body)
            msg.putOpt("transaction", randomString())
            msg.putOpt("session_id", mSessionId)
            msg.putOpt("handle_id", handle.handleId)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(msg.toString())
        Timber.tag(TAG).i(msg.toString())
    }

    fun publisherCreateOffer(handleId: BigInteger?, sdp: SessionDescription?) {
        val publish = JSONObject()
        val jsep = JSONObject()
        val message = JSONObject()
        try {
            publish.putOpt("request", "configure")
            publish.putOpt("audio", true)
            publish.putOpt("video", true)
            jsep.putOpt("type", sdp?.type)
            jsep.putOpt("sdp", sdp?.description)
            message.putOpt("janus", "message")
            message.putOpt("body", publish)
            message.putOpt("jsep", jsep)
            message.putOpt("transaction", randomString())
            message.putOpt("session_id", mSessionId)
            message.putOpt("handle_id", handleId)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        mWebSocket?.send(message.toString())
        Timber.tag(TAG).i(message.toString())
    }

    fun subscriberCreateAnswer(handleId: BigInteger?, sdp: SessionDescription?) {
        val body = JSONObject()
        val jsep = JSONObject()
        val message = JSONObject()
        try {
            body.putOpt("request", "start")
            body.putOpt("room", videoRoom)
            jsep.putOpt("type", sdp?.type)
            jsep.putOpt("sdp", sdp?.description)
            message.putOpt("janus", "message")
            message.putOpt("body", body)
            message.putOpt("jsep", jsep)
            message.putOpt("transaction", randomString())
            message.putOpt("session_id", mSessionId)
            message.putOpt("handle_id", handleId)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(message.toString())
        Timber.tag(TAG).i(message.toString())
    }

    fun trickleCandidate(handleId: BigInteger?, iceCandidate: IceCandidate) {
        val candidate = JSONObject()
        val message = JSONObject()
        try {
            candidate.putOpt("candidate", iceCandidate.sdp)
            candidate.putOpt("sdpMid", iceCandidate.sdpMid)
            candidate.putOpt("sdpMLineIndex", iceCandidate.sdpMLineIndex)
            message.putOpt("janus", "trickle")
            message.putOpt("candidate", candidate)
            message.putOpt("transaction", randomString())
            message.putOpt("session_id", mSessionId)
            message.putOpt("handle_id", handleId)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(message.toString())
        Timber.tag(TAG).i(message.toString())
    }

    fun trickleCandidateComplete(handleId: BigInteger?) {
        val candidate = JSONObject()
        val message = JSONObject()
        try {
            candidate.putOpt("completed", true)
            message.putOpt("janus", "trickle")
            message.putOpt("candidate", candidate)
            message.putOpt("transaction", randomString())
            message.putOpt("session_id", mSessionId)
            message.putOpt("handle_id", handleId)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
    }

    private fun subscriberCreateHandle(feed: BigInteger, display: String) {
        val transaction = randomString()
        val jt = JanusTransaction(
            tid = transaction,
            success = object : TransactionCallbackSuccess {
                override fun success(jo: JSONObject) {
                    val janusHandle = JanusHandle()
                    val data = jo.optJSONObject("data")
                    if (data != null) {
                        janusHandle.handleId = BigInteger(data.optString("id"))
                    } else {
                        Timber.tag(TAG).e("publisherCreateHandle -> data is null.")
                    }
                    janusHandle.feedId = feed
                    janusHandle.display = display
                    janusHandle.onRemoteJsep = object : OnRemoteJsep {
                        override fun onRemoteJsep(jh: JanusHandle, jsep: JSONObject) {
                            jh.handleId?.let { delegate.subscriberHandleRemoteJsep(it, jsep) }
                        }
                    }
                    janusHandle.onLeaving = object : OnJoined {
                        override fun onJoined(jh: JanusHandle) {
                            subscriberOnLeaving(jh)
                        }
                    }
                    handles[janusHandle.handleId!!] = janusHandle
                    feeds[janusHandle.feedId!!] = janusHandle
                    subscriberJoinRoom(janusHandle)
                }
            },
            error = object : TransactionCallbackError {
                override fun error(jo: JSONObject) {
                    errorListener.janusTransactionFailure("subscriberCreateHandle -> $jo")
                }
            })
        transactions[transaction] = jt
        val msg = JSONObject()
        try {
            msg.putOpt("janus", "attach")
            msg.putOpt("plugin", "janus.plugin.videoroom")
            msg.putOpt("transaction", transaction)
            msg.putOpt("session_id", mSessionId)
            msg.put("opaque_id", "videoroomtest-nV9jOV3mkYMe")
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(msg.toString())
        Timber.tag(TAG).i(msg.toString())
    }

    private fun subscriberJoinRoom(handle: JanusHandle) {
        val msg = JSONObject()
        val body = JSONObject()
        try {
            body.putOpt("request", "join")
            body.putOpt("room", videoRoom)
            body.putOpt("ptype", "subscriber")
            body.putOpt("feed", handle.feedId)
            msg.putOpt("janus", "message")
            msg.putOpt("body", body)
            msg.putOpt("transaction", randomString())
            msg.putOpt("session_id", mSessionId)
            msg.putOpt("handle_id", handle.handleId)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(msg.toString())
        Timber.tag(TAG).i(msg.toString())
    }

    private fun subscriberOnLeaving(handle: JanusHandle) {
        val transaction = randomString()
        val jt = JanusTransaction(
            tid = transaction,
            success = object : TransactionCallbackSuccess {
                override fun success(jo: JSONObject) {
                    delegate.onLeaving(handle.handleId!!)
                    handles.remove(handle.handleId)
                    feeds.remove(handle.feedId)
                }
            },
            error = object : TransactionCallbackError {
                override fun error(jo: JSONObject) {
                    errorListener.janusTransactionFailure("subscriberOnLeaving -> $jo")
                }
            })
        transactions[transaction] = jt
        val jo = JSONObject()
        try {
            jo.putOpt("janus", "detach")
            jo.putOpt("transaction", transaction)
            jo.putOpt("session_id", mSessionId)
            jo.putOpt("handle_id", handle.handleId)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(jo.toString())
        Timber.tag(TAG).i(jo.toString())

    }

    private fun keepAlive() {
        val transaction = randomString()
        val msg = JSONObject()
        try {
            msg.putOpt("janus", "keepalive")
            msg.putOpt("session_id", mSessionId)
            msg.putOpt("transaction", transaction)
        } catch (e: JSONException) {
            e.printStackTrace()
            errorListener.jsonException(e.message)
        }
        mWebSocket?.send(msg.toString())
    }

    private val fireKeepAlive: Runnable = object : Runnable {
        override fun run() {
            keepAlive()
            mHandler.postDelayed(this, 30000)
        }
    }

    private fun randomString(): String {
        val str = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val rnd = Random()
        val sb = StringBuilder(12)
        for (i in 0 until 12) {
            sb.append(str[rnd.nextInt(str.length)])
        }
        return sb.toString()
    }

    fun close() {
        mWebSocket?.close(1000, null)
    }

    companion object {
        private const val TAG = "WsChannel"
    }
}