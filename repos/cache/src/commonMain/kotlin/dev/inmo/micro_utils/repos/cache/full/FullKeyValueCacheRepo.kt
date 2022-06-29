package dev.inmo.micro_utils.repos.cache.full

import dev.inmo.micro_utils.common.*
import dev.inmo.micro_utils.pagination.Pagination
import dev.inmo.micro_utils.pagination.PaginationResult
import dev.inmo.micro_utils.repos.*
import dev.inmo.micro_utils.repos.cache.cache.UnlimitedKVCache
import dev.inmo.micro_utils.repos.pagination.getAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

open class FullReadKeyValueCacheRepo<Key,Value>(
    protected open val parentRepo: ReadKeyValueRepo<Key, Value>,
    protected open val kvCache: UnlimitedKVCache<Key, Value>,
) : ReadKeyValueRepo<Key, Value> {
    protected inline fun <T> doOrTakeAndActualize(
        action: UnlimitedKVCache<Key, Value>.() -> Optional<T>,
        actionElse: ReadKeyValueRepo<Key, Value>.() -> T,
        actualize: UnlimitedKVCache<Key, Value>.(T) -> Unit
    ): T {
        kvCache.action().onPresented {
            return it
        }.onAbsent {
            return parentRepo.actionElse().also {
                kvCache.actualize(it)
            }
        }
        error("The result should be returned above")
    }
    protected suspend fun actualizeAll() {
        kvCache.clear()
        kvCache.set(parentRepo.getAll { keys(it) }.toMap())
    }

    override suspend fun get(k: Key): Value? = doOrTakeAndActualize(
        { get(k) ?.optional ?: Optional.absent() },
        { get(k) },
        { set(k, it ?: return@doOrTakeAndActualize) }
    )

    override suspend fun values(pagination: Pagination, reversed: Boolean): PaginationResult<Value> = doOrTakeAndActualize(
        { values(pagination, reversed).takeIf { it.results.isNotEmpty() }.optionalOrAbsentIfNull },
        { values(pagination, reversed) },
        { if (it.results.isNotEmpty()) actualizeAll() }
    )

    override suspend fun count(): Long = doOrTakeAndActualize(
        { count().takeIf { it != 0L }.optionalOrAbsentIfNull },
        { count() },
        { if (it != 0L) actualizeAll() }
    )

    override suspend fun contains(key: Key): Boolean = doOrTakeAndActualize(
        { contains(key).takeIf { it }.optionalOrAbsentIfNull },
        { contains(key) },
        { if (it) parentRepo.get(key) ?.also { kvCache.set(key, it) } }
    )

    override suspend fun keys(pagination: Pagination, reversed: Boolean): PaginationResult<Key> = doOrTakeAndActualize(
        { keys(pagination, reversed).takeIf { it.results.isNotEmpty() }.optionalOrAbsentIfNull },
        { keys(pagination, reversed) },
        { if (it.results.isNotEmpty()) actualizeAll() }
    )

    override suspend fun keys(v: Value, pagination: Pagination, reversed: Boolean): PaginationResult<Key> = doOrTakeAndActualize(
        { keys(v, pagination, reversed).takeIf { it.results.isNotEmpty() }.optionalOrAbsentIfNull },
        { parentRepo.keys(v, pagination, reversed) },
        { if (it.results.isNotEmpty()) actualizeAll() }
    )
}

open class FullWriteKeyValueCacheRepo<Key,Value>(
    protected open val parentRepo: WriteKeyValueRepo<Key, Value>,
    protected open val kvCache: UnlimitedKVCache<Key, Value>,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : WriteKeyValueRepo<Key, Value> by parentRepo {
    protected val onNewJob = parentRepo.onNewValue.onEach { kvCache.set(it.first, it.second) }.launchIn(scope)
    protected val onRemoveJob = parentRepo.onValueRemoved.onEach { kvCache.unset(it) }.launchIn(scope)
}

open class FullKeyValueCacheRepo<Key,Value>(
    parentRepo: KeyValueRepo<Key, Value>,
    kvCache: UnlimitedKVCache<Key, Value>,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : FullWriteKeyValueCacheRepo<Key,Value>(parentRepo, kvCache, scope),
    KeyValueRepo<Key,Value>,
    ReadKeyValueRepo<Key, Value> by FullReadKeyValueCacheRepo(parentRepo, kvCache) {
    override suspend fun unsetWithValues(toUnset: List<Value>) = parentRepo.unsetWithValues(toUnset)
}
