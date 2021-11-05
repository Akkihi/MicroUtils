package dev.inmo.micro_utils.fsm.common.managers

import dev.inmo.micro_utils.fsm.common.State
import dev.inmo.micro_utils.fsm.common.StatesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Implement this repo if you want to use some custom repo for [DefaultStatesManager]
 */
interface DefaultStatesManagerRepo<T : State> {
    /**
     * Must save [state] as current state of chain with [State.context] of [state]
     */
    suspend fun set(state: T)
    /**
     * Remove exactly [state]. In case if internally [State.context] is busy with different [State], that [State] should
     * NOT be removed
     */
    suspend fun removeState(state: T)
    /**
     * @return Current list of available and saved states
     */
    suspend fun getStates(): List<T>

    /**
     * @return Current state by [context]
     */
    suspend fun getContextState(context: Any): T?

    /**
     * @return Current state by [context]
     */
    suspend fun contains(context: Any): Boolean = getContextState(context) != null
}

/**
 * @param repo This repo will be used as repository for storing states. All operations with this repo will happen BEFORE
 * any event will be sent to [onChainStateUpdated], [onStartChain] or [onEndChain]. By default will be used
 * [InMemoryDefaultStatesManagerRepo] or you may create custom [DefaultStatesManagerRepo] and pass as [repo] parameter
 * @param onContextsConflictResolver Receive old [State], new one and the state currently placed on new [State.context]
 * key. In case when this callback will returns true, the state placed on [State.context] of new will be replaced by
 * new state by using [endChain] with that state
 */
class DefaultStatesManager<T : State>(
    private val repo: DefaultStatesManagerRepo<T> = InMemoryDefaultStatesManagerRepo(),
    private val onContextsConflictResolver: suspend (old: T, new: T, currentNew: T) -> Boolean = { _, _, _ -> true }
) : StatesManager<T> {
    private val _onChainStateUpdated = MutableSharedFlow<Pair<T, T>>(0)
    override val onChainStateUpdated: Flow<Pair<T, T>> = _onChainStateUpdated.asSharedFlow()
    private val _onStartChain = MutableSharedFlow<T>(0)
    override val onStartChain: Flow<T> = _onStartChain.asSharedFlow()
    private val _onEndChain = MutableSharedFlow<T>(0)
    override val onEndChain: Flow<T> = _onEndChain.asSharedFlow()

    private val mapMutex = Mutex()

    override suspend fun update(old: T, new: T) = mapMutex.withLock {
        val stateByOldContext: T? = repo.getContextState(old.context)
        when {
            stateByOldContext != old -> return@withLock
            stateByOldContext == null || old.context == new.context -> {
                repo.set(new)
                _onChainStateUpdated.emit(old to new)
            }
            else -> {
                val stateOnNewOneContext = repo.getContextState(new.context)
                if (stateOnNewOneContext == null || onContextsConflictResolver(old, new, stateOnNewOneContext)) {
                    stateOnNewOneContext ?.let { endChainWithoutLock(it) }
                    repo.removeState(old)
                    repo.set(new)
                    _onChainStateUpdated.emit(old to new)
                }
            }
        }
    }

    override suspend fun startChain(state: T) = mapMutex.withLock {
        if (!repo.contains(state.context)) {
            repo.set(state)
            _onStartChain.emit(state)
        }
    }

    private suspend fun endChainWithoutLock(state: T) {
        if (repo.getContextState(state.context) == state) {
            repo.removeState(state)
            _onEndChain.emit(state)
        }
    }

    override suspend fun endChain(state: T) {
        mapMutex.withLock {
            endChainWithoutLock(state)
        }
    }

    override suspend fun getActiveStates(): List<T> = repo.getStates()

}
