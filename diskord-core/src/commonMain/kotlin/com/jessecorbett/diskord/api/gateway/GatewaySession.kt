package com.jessecorbett.diskord.api.gateway

import com.jessecorbett.diskord.api.common.UserStatus
import com.jessecorbett.diskord.api.exceptions.DiscordCompatibilityException
import com.jessecorbett.diskord.api.gateway.commands.Identify
import com.jessecorbett.diskord.api.gateway.commands.IdentifyProperties
import com.jessecorbett.diskord.api.gateway.commands.IdentifyShard
import com.jessecorbett.diskord.api.gateway.commands.Resume
import com.jessecorbett.diskord.api.gateway.commands.UpdateStatus
import com.jessecorbett.diskord.api.gateway.events.DiscordEvent
import com.jessecorbett.diskord.api.gateway.events.Hello
import com.jessecorbett.diskord.api.gateway.events.Ready
import com.jessecorbett.diskord.api.gateway.model.GatewayIntents
import com.jessecorbett.diskord.api.gateway.model.GatewayMessage
import com.jessecorbett.diskord.api.gateway.model.OpCode
import com.jessecorbett.diskord.api.gateway.model.UserStatusActivity
import com.jessecorbett.diskord.api.global.GatewayBotUrl
import com.jessecorbett.diskord.util.defaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import mu.KotlinLogging

public class GatewaySession(
    private val token: String,
    gatewayBotUrl: GatewayBotUrl,
    private val intents: GatewayIntents,
    private val shardCount: Int = 0,
    private val shardNumber: Int = 0,
    private val eventDispatcher: EventDispatcher<Unit>
) {
    private val logger = KotlinLogging.logger {}

    private val heartbeatScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val socketManager = SocketManager(gatewayBotUrl.url, ::receiveGatewayMessage)
    private var sessionId: String? = null
    private var sequenceNumber: Int? = null

    public val running: Boolean by socketManager::running
    public val alive: Boolean by socketManager::alive

    public suspend fun startSession(sessionId: String? = null, sequenceNumber: Int? = null) {
        this.sessionId = sessionId
        this.sequenceNumber = sequenceNumber
        socketManager.open()
    }

    public suspend fun closeSession() {
        socketManager.close()
    }

    public suspend fun restartConnection() {
        socketManager.restartConnection()
    }

    public suspend fun send(gatewayMessage: GatewayMessage) {
        logger.info { "Sending OpCode " + gatewayMessage.opCode }
        socketManager.send(gatewayMessage)
    }

    /**
     * Sets the user status in Discord.
     *
     * @param status The user status to set to.
     * @param isAfk If the user is AFK.
     * @param idleTime How long the user has been idle, in milliseconds.
     * @param activity The activity, if any, that the user is performing.
     */
    public suspend fun setStatus(
        status: UserStatus,
        isAfk: Boolean = false,
        idleTime: Int? = null,
        activity: UserStatusActivity? = null
    ) {
        val json = defaultJson.encodeToJsonElement(UpdateStatus(idleTime, listOfNotNull(activity), status, isAfk))
        socketManager.send(GatewayMessage(
            OpCode.STATUS_UPDATE,
            json
        ))
    }

    private suspend fun receiveGatewayMessage(gatewayMessage: GatewayMessage) = coroutineScope {
        logger.debug { "Received OpCode ${gatewayMessage.opCode}" }
        when (gatewayMessage.opCode) {
            OpCode.DISPATCH -> {
                sequenceNumber = gatewayMessage.sequenceNumber
                doGatewayEventPipeline(gatewayMessage)
            }
            OpCode.HEARTBEAT -> {
                send(GatewayMessage(OpCode.HEARTBEAT_ACK, null, null, null))
            }
            OpCode.RECONNECT -> {
                logger.info { "Server requested a reconnect" }
                restartConnection()
            }
            OpCode.INVALID_SESSION -> {
                logger.warn { "The session was invalid, falling back to new session behavior" }
                sessionId = null
                sequenceNumber = null
                restartConnection()
            }
            OpCode.HELLO -> {
                initializeSession(defaultJson.decodeFromJsonElement(Hello.serializer(), gatewayMessage.dataPayload!!))
            }
            OpCode.HEARTBEAT_ACK -> {
                // TODO: We should handle errors to do with a lack of heartbeat ack, possibly restart.
                // Additional note, I've not observed Discord actually sending heartbeats
            }
            else -> throw DiscordCompatibilityException("Reached unreachable OpCode: ${gatewayMessage.opCode}")
        }
    }


    private var heartbeatJob: Job? = null
    private suspend fun initializeSession(hello: Hello) = coroutineScope {
        if (sessionId != null && sequenceNumber != null) { // RESUME
            send(
                GatewayMessage(
                    OpCode.RESUME,
                    defaultJson.encodeToJsonElement(Resume(token, sessionId!!, sequenceNumber!!)),
                    null,
                    null
                )
            )
        } else if (shardCount != 0) { // IDENTIFY (sharded)
            val identify = IdentifyShard(
                token,
                listOf(shardNumber, shardCount),
                intents = intents,
                properties = IdentifyProperties.Default
            )
            send(GatewayMessage(OpCode.IDENTIFY, defaultJson.encodeToJsonElement(identify), null, null))
        } else { // IDENTIFY (unsharded)
            val identify = Identify(
                token,
                intents = intents,
                properties = IdentifyProperties.Default
            )
            send(GatewayMessage(OpCode.IDENTIFY, defaultJson.encodeToJsonElement(identify), null, null))
        }

        heartbeatJob?.cancel()
        heartbeatJob = heartbeatScope.launch {
            while (this.isActive) {
                delay(hello.heartbeatInterval)
                if (sequenceNumber != null) {
                    send(GatewayMessage(OpCode.HEARTBEAT, JsonPrimitive(sequenceNumber), null, null))
                }
            }
        }
    }

    /**
     * The event pipeline
     */
    private suspend fun doGatewayEventPipeline(gatewayMessage: GatewayMessage) {
        // Begin event pipeline within internal space by processing event
        val discordEvent = DiscordEvent.values().find { it.name == gatewayMessage.event }
            ?: return // Ignore unknown events, since we receive non-bot events because I guess it's hard for discord to not send bots non-bot events
        gatewayMessage.dataPayload
            ?: throw DiscordCompatibilityException("Encountered DiscordEvent ${gatewayMessage.event} without event data")

        // Once event has been processed into usable data, begin pipeline operations
        logger.debug { "Received Dispatch $discordEvent" }

        if (discordEvent == DiscordEvent.READY) {
            sessionId = defaultJson.decodeFromJsonElement<Ready>(gatewayMessage.dataPayload).sessionId
        }

        // Begin userspace event dispatching
        eventDispatcher.handleEvent(discordEvent, gatewayMessage.dataPayload)
    }
}
