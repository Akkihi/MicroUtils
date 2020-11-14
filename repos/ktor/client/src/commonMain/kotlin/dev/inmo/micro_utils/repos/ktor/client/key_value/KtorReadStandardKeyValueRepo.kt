package dev.inmo.micro_utils.repos.ktor.client.key_value

import dev.inmo.micro_utils.ktor.client.encodeUrlQueryValue
import dev.inmo.micro_utils.ktor.client.uniget
import dev.inmo.micro_utils.ktor.common.buildStandardUrl
import dev.inmo.micro_utils.pagination.*
import dev.inmo.micro_utils.repos.ReadStandardKeyValueRepo
import dev.inmo.micro_utils.repos.ktor.common.key_value.*
import dev.inmo.micro_utils.repos.ktor.common.valueParameterName
import io.ktor.client.HttpClient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

class KtorReadStandardKeyValueRepo<Key, Value> (
    private var baseUrl: String,
    private var client: HttpClient = HttpClient(),
    private var keySerializer: KSerializer<Key>,
    private var valueSerializer: KSerializer<Value>,
    private var valueNullableSerializer: KSerializer<Value?>,
) : ReadStandardKeyValueRepo<Key, Value> {
    override suspend fun get(k: Key): Value? = client.uniget(
        buildStandardUrl(
            baseUrl,
            getRoute,
            mapOf(
                keyParameterName to keySerializer.encodeUrlQueryValue(k)
            )
        ),
        valueNullableSerializer
    )

    override suspend fun values(pagination: Pagination, reversed: Boolean): PaginationResult<Value> = client.uniget(
        buildStandardUrl(
            baseUrl,
            valuesRoute,
            mapOf(
                reversedParameterName to Boolean.serializer().encodeUrlQueryValue(reversed)
            ) + pagination.asUrlQueryParts
        ),
        PaginationResult.serializer(valueSerializer)
    )

    override suspend fun keys(pagination: Pagination, reversed: Boolean): PaginationResult<Key> = client.uniget(
        buildStandardUrl(
            baseUrl,
            keysRoute,
            mapOf(
                reversedParameterName to Boolean.serializer().encodeUrlQueryValue(reversed)
            ) + pagination.asUrlQueryParts
        ),
        PaginationResult.serializer(keySerializer)
    )

    override suspend fun keys(v: Value, pagination: Pagination, reversed: Boolean): PaginationResult<Key> = client.uniget(
        buildStandardUrl(
            baseUrl,
            keysRoute,
            mapOf(
                valueParameterName to valueSerializer.encodeUrlQueryValue(v),
                reversedParameterName to Boolean.serializer().encodeUrlQueryValue(reversed)
            ) + pagination.asUrlQueryParts
        ),
        PaginationResult.serializer(keySerializer)
    )

    override suspend fun contains(key: Key): Boolean = client.uniget(
        buildStandardUrl(
            baseUrl,
            containsRoute,
            mapOf(
                keyParameterName to keySerializer.encodeUrlQueryValue(key)
            ),
        ),
        Boolean.serializer(),
    )

    override suspend fun count(): Long = client.uniget(
        buildStandardUrl(
            baseUrl,
            countRoute,
        ),
        Long.serializer()
    )
}
