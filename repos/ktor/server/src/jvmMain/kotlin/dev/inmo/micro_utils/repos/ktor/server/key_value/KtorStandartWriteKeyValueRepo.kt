package dev.inmo.micro_utils.repos.ktor.server.key_value

import dev.inmo.micro_utils.repos.StandardWriteKeyValueRepo
import dev.inmo.micro_utils.repos.ktor.common.key_value.*
import io.ktor.application.*
import io.ktor.routing.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.PairSerializer

fun <K, V> Route.configureWriteStandartKeyValueRepoRoutes (
    originalRepo: StandardWriteKeyValueRepo<K, V>,
    keySerializer: KSerializer<K>,
    valueSerializer: KSerializer<V>,
) {
    includeWebsocketHandling(
        onNewValueRoute,
        originalRepo.onNewValue,
        PairSerializer(keySerializer, valueSerializer)
    )

    includeWebsocketHandling(
        onValueRemovedRoute,
        originalRepo.onValueRemoved,
        keySerializer
    )

    post(setRoute) {
        val (key, value) = call.uniload(
            KeyValuePostObject.serializer(keySerializer, valueSerializer)
        )

        originalRepo.set(key, value)
    }

    post(unsetRoute) {
        val key = call.uniload(
            keySerializer
        )

        originalRepo.unset(key)
    }
}