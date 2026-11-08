package org.dmg.balletmaster

class CallContextManager(
    private val replayOffListener: (CallContext) -> Unit
) {
    private val store = ThreadLocal<CallContext>()

    fun store(operationName: Any, operationId: Any, isReplay: Boolean, attempt: Int) {
        store.set(CallContext(operationName, operationId, isReplay, if (isReplay) attempt + 1 else attempt))
    }

    fun clear() {
        store.remove()
    }

    fun get(): CallContext? {
        return store.get()
    }

    fun off() {
        store.get()?.also {
            if (it.isReplay) {
                store.set(it.off())
                replayOffListener(it)
            }
        }
    }
}