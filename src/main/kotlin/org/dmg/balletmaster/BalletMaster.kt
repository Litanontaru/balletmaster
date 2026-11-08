package org.dmg.balletmaster

class BalletMaster(
    private val clientId: Any,
    private val store: Store,
    private val callContextManager: CallContextManager,
) {
    fun start() =
        Step(
            DefaultStep,
            ContextImpl(
                store,
                clientId,
                callContextManager::get,
                callContextManager::off,
                callContextManager::setSuffix
            )
        ) {
            Try.success(Unit)
        }
}
