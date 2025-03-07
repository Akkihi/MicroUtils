package dev.inmo.micro_utils.repos

import dev.inmo.micro_utils.pagination.*
import dev.inmo.micro_utils.pagination.utils.paginate
import dev.inmo.micro_utils.pagination.utils.reverse
import kotlinx.coroutines.flow.*

class MapReadKeyValuesRepo<Key, Value>(
    private val map: Map<Key, List<Value>> = emptyMap()
) : ReadKeyValuesRepo<Key, Value> {
    override suspend fun get(k: Key, pagination: Pagination, reversed: Boolean): PaginationResult<Value> {
        val list = map[k] ?: return emptyPaginationResult()

        return list.paginate(
            if (reversed) {
                pagination.reverse(list.size)
            } else {
                pagination
            }
        )
    }

    override suspend fun keys(pagination: Pagination, reversed: Boolean): PaginationResult<Key> {
        val keys = map.keys
        val actualPagination = if (reversed) pagination.reverse(keys.size) else pagination
        return keys.paginate(actualPagination).let {
            if (reversed) {
                it.copy(results = it.results.reversed())
            } else {
                it
            }
        }
    }

    override suspend fun keys(v: Value, pagination: Pagination, reversed: Boolean): PaginationResult<Key> {
        val keys = map.keys.filter { map[it] ?.contains(v) == true }
        val actualPagination = if (reversed) pagination.reverse(keys.size) else pagination
        return keys.paginate(actualPagination).let {
            if (reversed) {
                it.copy(results = it.results.reversed())
            } else {
                it
            }
        }
    }

    override suspend fun contains(k: Key): Boolean = map.containsKey(k)

    override suspend fun contains(k: Key, v: Value): Boolean = map[k] ?.contains(v) == true

    override suspend fun count(k: Key): Long = map[k] ?.size ?.toLong() ?: 0L

    override suspend fun count(): Long = map.size.toLong()
}

class MapWriteKeyValuesRepo<Key, Value>(
    private val map: MutableMap<Key, MutableList<Value>> = mutableMapOf()
) : WriteKeyValuesRepo<Key, Value> {
    private val _onNewValue: MutableSharedFlow<Pair<Key, Value>> = MutableSharedFlow()
    override val onNewValue: Flow<Pair<Key, Value>> = _onNewValue.asSharedFlow()
    private val _onValueRemoved: MutableSharedFlow<Pair<Key, Value>> = MutableSharedFlow()
    override val onValueRemoved: Flow<Pair<Key, Value>> = _onValueRemoved.asSharedFlow()
    private val _onDataCleared: MutableSharedFlow<Key> = MutableSharedFlow()
    override val onDataCleared: Flow<Key> = _onDataCleared.asSharedFlow()

    override suspend fun add(toAdd: Map<Key, List<Value>>) {
        toAdd.keys.forEach { k ->
            if (map.getOrPut(k) { mutableListOf() }.addAll(toAdd[k] ?: return@forEach)) {
                toAdd[k] ?.forEach { v ->
                    _onNewValue.emit(k to v)
                }
            }
        }
    }

    override suspend fun remove(toRemove: Map<Key, List<Value>>) {
        toRemove.keys.forEach { k ->
            if (map[k] ?.removeAll(toRemove[k] ?: return@forEach) == true) {
                toRemove[k] ?.forEach { v ->
                    _onValueRemoved.emit(k to v)
                }
            }
            if (map[k] ?.isEmpty() == true) {
                map.remove(k)
                _onDataCleared.emit(k)
            }
        }
    }

    override suspend fun removeWithValue(v: Value) {
        map.forEach { (k, values) ->
            if (values.remove(v)) {
                _onValueRemoved.emit(k to v)
            }
        }
    }

    override suspend fun clear(k: Key) {
        map.remove(k) ?.also { _onDataCleared.emit(k) }
    }

    override suspend fun clearWithValue(v: Value) {
        map.filter { (_, values) ->
            values.contains(v)
        }.forEach {
            map.remove(it.key) ?.onEach { v ->
                _onValueRemoved.emit(it.key to v)
            } ?.also { _ ->
                _onDataCleared.emit(it.key)
            }
        }
    }
}

@Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
class MapKeyValuesRepo<Key, Value>(
    private val map: MutableMap<Key, MutableList<Value>> = mutableMapOf()
) : KeyValuesRepo<Key, Value>,
    ReadKeyValuesRepo<Key, Value> by MapReadKeyValuesRepo(map),
    WriteKeyValuesRepo<Key, Value> by MapWriteKeyValuesRepo(map)

fun <K, V> MutableMap<K, List<V>>.asKeyValuesRepo(): KeyValuesRepo<K, V> = MapKeyValuesRepo(
    map { (k, v) -> k to v.toMutableList() }.toMap().toMutableMap()
)
