// AM (DISCORD) -->

/*
 *   Copyright (c) 2023 Kizzy. All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

// Library from https://github.com/dead8309/KizzyRPC (Thank you)
package eu.kanade.tachiyomi.data.connections.discord

import eu.kanade.tachiyomi.data.connections.discord.Identify.Companion.toIdentifyPayload
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

sealed interface DiscordWebSocket : CoroutineScope {
    suspend fun connect()
    suspend fun sendActivity(presence: Presence)
    fun isWebSocketConnected(): Boolean
    fun close()
}

open class DiscordWebSocketImpl(
    private val token: String,
    private val logger: Logger = NoOpLogger,
) : DiscordWebSocket {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private var connected = false
    private var client: HttpClient = HttpClient {
        install(WebSockets)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Default

    override suspend fun connect() {
        launch {
            try {
                logger.i("Gateway", "Connect called")
                val url = resumeGatewayUrl ?: gatewayUrl
                websocket = client.webSocketSession(url)

                // start receiving messages
                websocket!!.incoming.receiveAsFlow()
                    .collect {
                        when (it) {
                            is Frame.Text -> {
                                val jsonString = it.readText()
                                onMessage(json.decodeFromString(jsonString))
                            }
                            else -> {}
                        }
                    }
                handleClose()
            } catch (e: Exception) {
                logger.e("Gateway", e.message ?: "")
                close()
            }
        }
    }

    private suspend fun handleClose() {
        heartbeatJob?.cancel()
        connected = false
        val close = websocket?.closeReason?.await()
        logger.w(
            "Gateway",
            "Closed with code: ${close?.code}, " +
                "reason: ${close?.message}, " +
                "can_reconnect: ${close?.code?.toInt() == 4000}",
        )
        if (close?.code?.toInt() == 4000) {
            delay(200.milliseconds)
            connect()
        } else {
            close()
        }
    }

    private suspend fun onMessage(payload: Payload) {
        logger.d("Gateway", "Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            OpCode.DISPATCH -> payload.handleDispatch()
            OpCode.HEARTBEAT -> sendHeartBeat()
            OpCode.RECONNECT -> reconnectWebSocket()
            OpCode.INVALID_SESSION -> handleInvalidSession()
            OpCode.HELLO -> payload.handleHello()
            else -> {}
        }
    }

    open fun Payload.handleDispatch() {
        when (this.t.toString()) {
            "READY" -> {
                val ready = json.decodeFromJsonElement<Ready>(this.d!!)
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl + "/?v=10&encoding=json"
                logger.i("Gateway", "resume_gateway_url updated to $resumeGatewayUrl")
                logger.i("Gateway", "session_id updated to $sessionId")
                connected = true
                return
            }
            "RESUMED" -> {
                logger.i("Gateway", "Session Resumed")
            }
            else -> {}
        }
    }

    private suspend inline fun handleInvalidSession() {
        logger.i("Gateway", "Handling Invalid Session")
        logger.d("Gateway", "Sending Identify after 150ms")
        delay(150)
        sendIdentify()
    }

    private suspend inline fun Payload.handleHello() {
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        heartbeatInterval = json.decodeFromJsonElement<Heartbeat>(this.d!!).heartbeatInterval
        logger.i("Gateway", "Setting heartbeatInterval= $heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
    }

    private suspend fun sendHeartBeat() {
        logger.i("Gateway", "Sending ${OpCode.HEARTBEAT} with seq: $sequence")
        send(
            op = OpCode.HEARTBEAT,
            d = if (sequence == 0) "null" else sequence.toString(),
        )
    }

    private suspend inline fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect",
            ),
        )
    }

    private suspend fun sendIdentify() {
        logger.i("Gateway", "Sending ${OpCode.IDENTIFY}")
        send(
            op = OpCode.IDENTIFY,
            d = token.toIdentifyPayload(),
        )
    }

    private suspend fun sendResume() {
        logger.i("Gateway", "Sending ${OpCode.RESUME}")
        send(
            op = OpCode.RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token,
            ),
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = launch {
            while (isActive) {
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    private fun isSocketConnectedToAccount(): Boolean {
        return connected && websocket?.isActive == true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isWebSocketConnected(): Boolean {
        return websocket?.incoming != null && websocket?.outgoing?.isClosedForSend == false
    }

    private suspend inline fun <reified T> send(op: OpCode, d: T?) {
        if (websocket?.isActive == true) {
            val payload = json.encodeToString(
                Payload(
                    op = op,
                    d = json.encodeToJsonElement(d),
                ),
            )
            websocket?.send(Frame.Text(payload))
        }
    }

    override fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        this.cancel()
        resumeGatewayUrl = null
        sessionId = null
        connected = false
        runBlocking {
            websocket?.close()
            logger.e("Gateway", "Connection to gateway closed")
        }
    }

    override suspend fun sendActivity(presence: Presence) {
        // TODO : Figure out a better way to wait for socket to be connected to account
        while (!isSocketConnectedToAccount()) {
            delay(10.milliseconds)
        }
        logger.i("Gateway", "Sending ${OpCode.PRESENCE_UPDATE}")
        send(
            op = OpCode.PRESENCE_UPDATE,
            d = presence,
        )
    }
}
// <-- AM (DISCORD)
