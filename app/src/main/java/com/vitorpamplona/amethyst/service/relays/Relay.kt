package com.vitorpamplona.amethyst.service.relays

import com.google.gson.JsonElement
import java.util.Collections
import nostr.postr.JsonFilter
import nostr.postr.events.Event
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class Relay(
    val url: String,
    var read: Boolean = true,
    var write: Boolean = true
) {
    private val httpClient = OkHttpClient()
    private val listeners = Collections.synchronizedSet(HashSet<Listener>())
    private var socket: WebSocket? = null

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun isConnected(): Boolean {
        return socket != null
    }

    fun unregister(listener: Listener) = listeners.remove(listener)

    fun requestAndWatch(reconnectTs: Long? = null) {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Sends everything.
                Client.subscriptions.forEach {
                    sendFilter(requestId = it.key, reconnectTs = reconnectTs)
                }
                listeners.forEach { it.onRelayStateChange(this@Relay, Type.CONNECT) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = Event.gson.fromJson(text, JsonElement::class.java).asJsonArray
                    val type = msg[0].asString
                    val channel = msg[1].asString
                    when (type) {
                        "EVENT" -> {
                            val event = Event.fromJson(msg[2], Client.lenient)
                            listeners.forEach { it.onEvent(this@Relay, channel, event) }
                        }
                        "EOSE" -> listeners.forEach {
                            it.onRelayStateChange(this@Relay, Type.EOSE)
                        }
                        "NOTICE" -> listeners.forEach {
                            // "channel" being the second string in the string array ...
                            it.onError(this@Relay, channel, Error("Relay sent notice: $channel"))
                        }
                        "OK" -> listeners.forEach {
                            // "channel" being the second string in the string array ...
                            // Event was saved correctly?
                        }
                        else -> listeners.forEach {
                            it.onError(
                                this@Relay,
                                channel,
                                Error("Unknown type $type on channel $channel. Msg was $text")
                            )
                        }
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                    text.chunked(2000) { chunked ->
                        listeners.forEach { it.onError(this@Relay, "", Error("Problem with $chunked")) }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listeners.forEach { it.onRelayStateChange(this@Relay, Type.DISCONNECTING) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                listeners.forEach { it.onRelayStateChange(this@Relay, Type.DISCONNECT) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
                listeners.forEach {
                    it.onError(this@Relay, "", Error("WebSocket Failure. Response: ${response}. Exception: ${t.message}", t))
                }
            }
        }
        socket = httpClient.newWebSocket(request, listener)
    }

    fun disconnect() {
        //httpClient.dispatcher.executorService.shutdown()
        socket?.close(1000, "Normal close")
    }

    fun sendFilter(requestId: String, reconnectTs: Long? = null) {
        if (socket == null) {
            requestAndWatch(reconnectTs)
        } else {
            val filters = if (reconnectTs != null) {
                Client.subscriptions[requestId]?.let {
                    it.map { filter ->
                        JsonFilter(filter.ids, filter.authors, filter.kinds, filter.tags, since = reconnectTs)
                    }
                } ?: error("No filter(s) found.")
            } else {
                Client.subscriptions[requestId] ?: error("No filter(s) found.")
            }
            val request = """["REQ","$requestId",${filters.joinToString(",") { it.toJson() }}]"""
            //println("FILTERSSENT " + """["REQ","$requestId",${filters.joinToString(",") { it.toJson() }}]""")
            socket!!.send(request)
        }
    }

    fun sendFilterOnlyIfDisconnected(requestId: String, reconnectTs: Long? = null) {
        if (socket == null) {
            requestAndWatch(reconnectTs)
        }
    }

    fun send(signedEvent: Event) {
        if (write)
            socket?.send("""["EVENT",${signedEvent.toJson()}]""")
    }

    fun close(subscriptionId: String){
        socket?.send("""["CLOSE","$subscriptionId"]""")
    }

    enum class Type {
        // Websocket connected
        CONNECT,
        // Websocket disconnecting
        DISCONNECTING,
        // Websocket disconnected
        DISCONNECT,
        // End Of Stored Events
        EOSE
    }

    interface Listener {
        /**
         * A new message was received
         */
        fun onEvent(relay: Relay, subscriptionId: String, event: Event)

        fun onError(relay: Relay, subscriptionId: String, error: Error)

        /**
         * Connected to or disconnected from a relay
         *
         * @param type is 0 for disconnect and 1 for connect
         */
        fun onRelayStateChange(relay: Relay, type: Type)
    }
}