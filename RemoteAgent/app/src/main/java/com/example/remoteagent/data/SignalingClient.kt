package com.example.remoteagent.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// Call only after the owner has accepted the matching request.
class SignalingClient(
    private val endpoint: String,
    private val token: String,
    private val sessionId: String,
    private val onSignal: (JSONObject) -> Unit,
    private val onEnded: () -> Unit
) {
    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    fun connect() {
        require(endpoint.startsWith("wss://")) { "Signaling requires a TLS-protected wss:// URL" }
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("sessionId", sessionId).put("type", "join").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                if (message.optString("sessionId") != sessionId) return
                if (message.optString("type") == "session_ended") {
                    onEnded()
                    close()
                } else {
                    onSignal(message)
                }
            }
        })
    }

    fun send(type: String, payload: JSONObject): Boolean {
        require(type in setOf("offer", "answer", "ice")) { "Unsupported signaling message" }
        return socket?.send(
            JSONObject().put("sessionId", sessionId).put("type", type).put("payload", payload).toString()
        ) ?: false
    }

    fun close() {
        socket?.close(1000, "Session closed")
        socket = null
        client.dispatcher.executorService.shutdown()
    }
}
