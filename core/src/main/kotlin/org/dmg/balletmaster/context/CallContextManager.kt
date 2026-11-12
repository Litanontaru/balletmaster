package org.dmg.balletmaster.context

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

    fun get(): CallContext? = store.get()

    fun off() {
        store.get()?.takeIf { it.isReplay }?.off()?.also { update(it, replayOffListener) }
    }

    fun setSuffix(suffix: Any) {
        store.get()?.withSuffix(suffix)?.also { update(it, suffixListener) }
    }

    private inline fun update(next: CallContext, listener: (CallContext) -> Unit) {
        store.set(next)
        listener(next)
    }
}
