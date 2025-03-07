package dev.inmo.micro_utils.repos.exposed.onetomany

import dev.inmo.micro_utils.repos.KeyValuesRepo
import dev.inmo.micro_utils.repos.exposed.ColumnAllocator
import kotlinx.coroutines.flow.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

typealias ExposedOneToManyKeyValueRepo1<Key, Value> = ExposedKeyValuesRepo<Key, Value>
open class ExposedKeyValuesRepo<Key, Value>(
    database: Database,
    keyColumnAllocator: ColumnAllocator<Key>,
    valueColumnAllocator: ColumnAllocator<Value>,
    tableName: String? = null
) : KeyValuesRepo<Key, Value>, ExposedReadKeyValuesRepo<Key, Value>(
    database,
    keyColumnAllocator,
    valueColumnAllocator,
    tableName
) {
    protected val _onNewValue: MutableSharedFlow<Pair<Key, Value>> = MutableSharedFlow()
    override val onNewValue: Flow<Pair<Key, Value>>
        get() = _onNewValue
    protected val _onValueRemoved: MutableSharedFlow<Pair<Key, Value>> = MutableSharedFlow()
    override val onValueRemoved: Flow<Pair<Key, Value>>
        get() = _onValueRemoved
    protected val _onDataCleared: MutableSharedFlow<Key> = MutableSharedFlow()
    override val onDataCleared: Flow<Key>
        get() = _onDataCleared

    override suspend fun add(toAdd: Map<Key, List<Value>>) {
        transaction(database) {
            toAdd.keys.flatMap { k ->
                toAdd[k] ?.mapNotNull { v ->
                    if (select { keyColumn.eq(k).and(valueColumn.eq(v)) }.limit(1).count() > 0) {
                        return@mapNotNull null
                    }
                    val insertResult = insert {
                        it[keyColumn] = k
                        it[valueColumn] = v
                    }
                    if (insertResult.insertedCount > 0) {
                        k to v
                    } else {
                        null
                    }
                } ?: emptyList()
            }
        }.forEach { _onNewValue.emit(it) }
    }

    override suspend fun remove(toRemove: Map<Key, List<Value>>) {
        transaction(database) {
            toRemove.keys.flatMap { k ->
                toRemove[k] ?.mapNotNull { v ->
                    if (deleteWhere { keyColumn.eq(k).and(valueColumn.eq(v)) } > 0 ) {
                        k to v
                    } else {
                        null
                    }
                } ?: emptyList()
            }
        }.forEach {
            _onValueRemoved.emit(it)
        }
    }

    override suspend fun removeWithValue(v: Value) {
        transaction(database) {
            val keys = select { selectByValue(v) }.map { it.asKey }
            deleteWhere { SqlExpressionBuilder.selectByValue(v) }
            keys
        }.forEach {
            _onValueRemoved.emit(it to v)
        }
    }

    override suspend fun clear(k: Key) {
        transaction(database) {
            deleteWhere { keyColumn.eq(k) }
        }.also { _onDataCleared.emit(k) }
    }

    override suspend fun clearWithValue(v: Value) {
        transaction(database) {
            val toClear = select { selectByValue(v) }
                .asSequence()
                .map { it.asKey to it.asObject }
                .groupBy { it.first }
                .mapValues { it.value.map { it.second } }
            deleteWhere { keyColumn.inList(toClear.keys) }
            toClear
        }.forEach {
            it.value.forEach { v ->
                _onValueRemoved.emit(it.key to v)
            }
            _onDataCleared.emit(it.key)
        }
    }
}
