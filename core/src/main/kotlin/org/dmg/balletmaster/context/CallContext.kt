package org.dmg.balletmaster.context

data class CallContext(
    val operationName: Any,
    val operationId: Any,
    val operationSuffix: Any = "",

    val isReplay: Boolean = false,
    val attempt: Int = 0
) {
    fun replay() = CallContext(operationName, operationId, operationSuffix, true, attempt + 1)
    fun off() = CallContext(operationName, operationId, operationSuffix, false, attempt)
    fun withSuffix(operationSuffix: Any) = CallContext(operationName, operationId, operationSuffix, isReplay, attempt)
}
