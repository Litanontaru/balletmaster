package org.dmg.balletmaster

class CallContextManager(
    private val replayOffListener: (CallContext) -> Unit = {},
    private val suffixListener: (CallContext) -> Unit = {}
) {
    private val store = ThreadLocal<CallContext>()

    fun store(
        operationName: Any,
        operationId: Any,
        isReplay: Boolean,
        attempt: Int,
        operationSuffix: Any = ""
    ) {
        store.set(
            CallContext(
                operationName = operationName,
                operationId = operationId,
                operationSuffix = operationSuffix,
                isReplay = isReplay,
                attempt = if (isReplay) attempt + 1 else attempt
            )
        )
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
                val new = it.off()
                store.set(new)
                replayOffListener(new)
            }
        }
    }

    fun setSuffix(suffix: Any) {
        store.get()?.also {
            val new = it.withSuffix(suffix)
            store.set(new)
            suffixListener(new)
        }
    }
}