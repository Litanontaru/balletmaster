package org.dmg.balletmaster

interface Context {
    fun isDone(stepId: StepId): Boolean
}

interface InternalContext: Context {
    fun setStep(stepId: StepId)
    fun setInput(value: Any): Result<Boolean>
    fun enterGate(): Result<Boolean>
    fun idempotencyGate(): Result<Boolean>
    fun setOutput(value: Any, status: StepStatus): Result<Boolean>

    //REPLAY

    fun <R> originalOutput(): Result<R?>
    fun originalStatus(): Result<StepStatus?>
    fun isReplay(): Boolean
    fun setPlay()

    //MAIN
    fun <R : Any> execute(action: () -> Try<R>): Try<R>
}