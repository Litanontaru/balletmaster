package org.dmg.balletmaster

import org.dmg.balletmaster.StepStatus.DONE
import org.dmg.balletmaster.TechStatus.BLOCKED
import org.dmg.balletmaster.TechStatus.NO_CALL
import java.lang.System.currentTimeMillis

class ContextImpl(
    private val store: Store,
    private val clientId: Any,
    private val call: CallContext?,
    private val off: () -> Unit
) : InternalContext {
    private val timeout = 1000L

    private var stepId: StepId = DefaultStep

    private fun key(stepId: StepId) = aKey(stepId, call?.attempt)

    private fun aKey(stepId: StepId, attempt: Int?) = "${clientId}:${call?.operationName}:${attempt}:${call?.operationId}:${stepId}:"

    private val key: String get() = key(stepId)

    private val originalKey: String get() = aKey(stepId, call?.attempt?.takeIf { it >0 }?.let { it -1 })

    override fun isDone(stepId: StepId) = store.get<StepStatus>(key(stepId) + "status").fold({ it == DONE }, { false })

    override fun setStep(stepId: StepId) {
        this.stepId = stepId
    }

    override fun setInput(value: Any): Result<Boolean> = store.set(key + "input", value)

    override fun enterGate() = store.inc(key + "gate")

    override fun idempotencyGate(): Result<Boolean> = store.set(key + "status", Unit to Unit)

    override fun setOutput(value: Any, status: StepStatus) = store.set(key + "output", value) + { store.set(key + "status", status) }

    override fun <R> originalOutput() = store.get<R & Any>(originalKey + "output")

    override fun originalStatus(): Result<StepStatus?> = store.get(originalKey + "status")

    override fun isReplay() = call?.isReplay ?: false

    override fun setPlay() {
        off()
    }

    override fun <R : Any> execute(action: () -> Try<R>): Try<R> =
        if (call == null) {
            Try.state(NO_CALL)
        } else {
            store.set(key + "timeout", Unit to currentTimeMillis() + timeout) + {
                if (it) Result.success(it)
                else store.set(key + "timeout", lessThen(currentTimeMillis()) to (currentTimeMillis() + timeout))
            } and {
                if (it) {
                    try {
                        val res = action()
                        store.set(key + "timeout", Unit) and res
                    } catch (e: Throwable) {
                        store.set(key + "timeout", Unit) and Try.failure(e)
                    }
                } else {
                    Try.state(BLOCKED)
                }
            }
        }
}