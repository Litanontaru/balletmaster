package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.context.ContextImpl
import org.dmg.balletmaster.state.StateConfig
import org.dmg.balletmaster.state.StateConfigBuilder
import org.dmg.balletmaster.state.StateConfigRepository
import org.dmg.balletmaster.state.StoreStateConfigRepository
import org.dmg.balletmaster.state.processStates
import org.dmg.balletmaster.store.Store

class BalletMaster(
    private val store: Store,
    private val callContextManager: CallContextManager,
    private val stateConfigRepository: StateConfigRepository = StoreStateConfigRepository(store)
) {
    fun start() =
        Step<Unit>(
            ContextImpl(
                store,
                callContextManager::get,
                callContextManager::off,
                callContextManager::setSuffix
            )
        )

    fun <S : StepId, STATE : Any> defineStates(
        operationName: String,
        version: String,
        init: StateConfigBuilder<S, STATE>.() -> Unit
    ): StateConfig<S, STATE> =
        processStates(operationName, version, init).also {
            check(stateConfigRepository.save(it).getOrThrow()) {
                "Failed to save StateConfig for operation='$operationName', version='$version'"
            }
        }
}
