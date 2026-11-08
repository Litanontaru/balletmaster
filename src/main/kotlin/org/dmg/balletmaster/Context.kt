package org.dmg.balletmaster

interface Context {
    fun isDone(stepId: StepId): Boolean
    val operationSuffix: Any
    val suffix: Any get() = operationSuffix
}

interface InternalContext: Context {
    fun setStep(stepId: StepId)
    fun setInput(value: Any): Result<Boolean>
    fun enterGate(): Result<Boolean>
    fun idempotencyGate(): Result<Boolean>
    fun setOutput(value: Try<Any>): Result<Boolean>
    fun setSuffix(suffix: Any)

    //REPLAY

    fun <R : Any> originalOutput(): Result<Try<R>?>
    fun isReplay(): Boolean
    fun setPlay()

    //MAIN
    fun <R : Any> execute(action: () -> Try<R>): Try<R>
}
