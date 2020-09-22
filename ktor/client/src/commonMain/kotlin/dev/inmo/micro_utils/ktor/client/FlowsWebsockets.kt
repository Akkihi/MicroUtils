package dev.inmo.micro_utils.ktor.client

import dev.inmo.micro_utils.ktor.common.asCorrectWebSocketUrl
import dev.inmo.micro_utils.ktor.common.standardKtorSerialFormat
import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.ws
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.DeserializationStrategy

/**
 * @param checkReconnection This lambda will be called when it is required to reconnect to websocket to establish
 * connection. Must return true in case if must be reconnected. By default always reconnecting
 */
inline fun <T> HttpClient.createStandardWebsocketFlow(
    url: String,
    crossinline checkReconnection: (Throwable?) -> Boolean = { true },
    crossinline conversation: suspend (ByteArray) -> T
): Flow<T> {
    val correctedUrl = url.asCorrectWebSocketUrl

    return channelFlow {
        val producerScope = this@channelFlow
        do {
            val reconnect = try {
                safely(
                    {
                        throw it
                    }
                ) {
                    ws(
                        correctedUrl
                    ) {
                        while (true) {
                            when (val received = incoming.receive()) {
                                is Frame.Binary -> producerScope.send(
                                    conversation(received.readBytes())
                                )
                                else -> {
                                    producerScope.close()
                                    return@ws
                                }
                            }
                        }
                    }
                }
                checkReconnection(null)
            } catch (e: Throwable) {
                checkReconnection(e).also {
                    if (!it) {
                        producerScope.close(e)
                    }
                }
            }
        } while (reconnect)
        if (!producerScope.isClosedForSend) {
            safely(
                { /* do nothing */ }
            ) {
                producerScope.close()
            }
        }
    }
}

/**
 * @param checkReconnection This lambda will be called when it is required to reconnect to websocket to establish
 * connection. Must return true in case if must be reconnected. By default always reconnecting
 */
inline fun <T> HttpClient.createStandardWebsocketFlow(
    url: String,
    crossinline checkReconnection: (Throwable?) -> Boolean = { true },
    deserializer: DeserializationStrategy<T>
) = createStandardWebsocketFlow(
    url,
    checkReconnection
) {
    standardKtorSerialFormat.decodeFromByteArray(deserializer, it)
}

