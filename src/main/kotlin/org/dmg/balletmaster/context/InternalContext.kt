package org.dmg.balletmaster.context

import org.dmg.balletmaster.Context
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try

interface InternalContext : Context {
    fun setStep(stepId: StepId)
    fun setLease(lease: Long)
    fun setInput(value: Any): Result<Boolean>
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
