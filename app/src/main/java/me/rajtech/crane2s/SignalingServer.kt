// app/src/main/java/me/rajtech/crane2s/signal/SignalingServer.kt
package me.rajtech.crane2s.signal

import android.content.Context
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach


class SignalingServer(
    private val androidContext: Context,
    private val port: Int,
    private val onOffer: (
        remoteSdp: String,
        remoteType: String,
        respond: (answerSdp: String, answerType: String) -> Unit
    ) -> Unit,
    private val onCandidate: (
        sdpMid: String?,
        sdpMLineIndex: Int?,
        candidate: String
    ) -> Unit
) {
    private val gson = Gson()

    private val server = embeddedServer(CIO, port = port) {
        // Only WebSockets plugin is needed
        install(WebSockets)

        routing {
            // Serve the HTML viewer
            get("/") {
                val html = androidContext.assets
                    .open("viewer.html")
                    .bufferedReader()
                    .use { it.readText() }
                call.respondText(html, ContentType.Text.Html)
            }

            // Signaling WebSocket endpoint
            webSocket("/ws") {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val msg = gson.fromJson(frame.readText(), Map::class.java)
                        when (msg["type"]) {
                            "offer" -> {
                                @Suppress("UNCHECKED_CAST")
                                val sdpMap     = msg["sdp"] as Map<String, String>
                                val remoteType = sdpMap["type"]!!
                                val remoteSdp  = sdpMap["sdp"]!!
                                onOffer(remoteSdp, remoteType) { answerSdp, answerType ->
                                    val resp = mapOf(
                                        "type" to "answer",
                                        "sdp"  to mapOf("type" to answerType, "sdp" to answerSdp)
                                    )
                                    // Non-suspending push into the outgoing channel
                                    outgoing.trySend(Frame.Text(gson.toJson(resp)))
                                }
                            }
                            "candidate" -> {
                                val candidate   = msg["candidate"] as String
                                val sdpMid      = msg["id"] as? String
                                val labelRaw    = msg["label"] as? Double
                                val sdpMLineIdx = labelRaw?.toInt()
                                onCandidate(sdpMid, sdpMLineIdx, candidate)
                            }
                        }
                    }
                }
            }
        }
    }

    fun start() = server.start(wait = false)
    fun stop()  = server.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_000)
}
