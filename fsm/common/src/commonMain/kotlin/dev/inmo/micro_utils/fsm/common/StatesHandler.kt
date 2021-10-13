package dev.inmo.micro_utils.fsm.common

/**
 * Default realization of states handler
 */
fun interface StatesHandler<I : State> {
    /**
     * Main handling of [state]. In case when this [state] leads to another [State] and [handleState] returns not null
     * [State] it is assumed that chain is not completed.
     */
    suspend fun StatesMachine.handleState(state: I): State?
}
