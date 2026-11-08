package org.dmg.balletmaster

data class CallContext(
    val operationName: Any,
    val operationId: Any,

    val isReplay: Boolean,
    val attempt: Int
) {
    fun replay() = CallContext(operationName, operationId, true, attempt + 1)
    fun off() = CallContext(operationName, operationId, false, attempt)
}