package org.dmg.balletmaster.context

import org.dmg.balletmaster.StepExecutionException
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.context.TechStatus.BLOCKED
import org.dmg.balletmaster.context.TechStatus.NO_CALL
import org.dmg.balletmaster.store.Store
import org.dmg.balletmaster.store.lessThen
import org.dmg.balletmaster.util.and
import org.dmg.balletmaster.util.plus
import java.lang.System.currentTimeMillis

class ContextImpl(
    private val store: Store,
    private val callProvider: () -> CallContext?,
    private val off: () -> Unit,
    private val onSetSuffix: (Any) -> Unit = {}
) : InternalContext {
    private val call: CallContext? get() = callProvider()

    private val timeout = 1000L

    private var stepId: StepId = DefaultStep
    private var stepSuffix: Any = ""

    override val operationSuffix: Any get() = call?.operationSuffix ?: ""

    private fun key(stepId: StepId) = aKey(stepId, call?.attempt, stepSuffix)

    private fun aKey(stepId: StepId, attempt: Int?, suffix: Any) =
        "${call?.operationName}:${call?.operationId}:${attempt}:${suffix}:${stepId}:"

    private val key: String get() = key(stepId)

    private val originalKey: String get() = aKey(stepId, call?.attempt?.takeIf { it > 0 }?.let { it - 1 }, stepSuffix)

    override fun isDone(stepId: StepId) = store.get<Try<*>>(key(stepId) + "output").fold({ it?.isSuccess == true }, { false })

    override fun setStep(stepId: StepId) {
        this.stepId = stepId
        this.stepSuffix = operationSuffix
    }

    override fun setSuffix(suffix: Any) {
        onSetSuffix(suffix)
    }

    override fun setInput(value: Any): Result<Boolean> = store.set(key + "input", value)

    override fun idempotencyGate(): Result<Boolean> = store.set(key + "output", null to null)

    override fun setOutput(value: Try<Any>) = store.set(key + "output", value)

    override fun <R : Any> originalOutput(): Result<Try<R>?> = store.get(originalKey + "output")

    override fun isReplay() = call?.isReplay ?: false

    override fun setPlay() {
        off()
    }

    override fun <R : Any> execute(action: () -> Try<R>): Try<R> =
        if (call == null) {
            Try.state(NO_CALL)
        } else {
            val deadline = currentTimeMillis() + timeout
            var acquired = false

            while (currentTimeMillis() <= deadline) {
                val now = currentTimeMillis()
                val leaseUntil = now + timeout

                val lockResult = store.set(key + "timeout", null to leaseUntil) + {
                    if (it) Result.success(it)
                    else store.set(key + "timeout", lessThen(now) to leaseUntil)
                }

                if (lockResult.fold({ it }, { false })) {
                    acquired = true
                    break
                }

                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            if (acquired) {
                try {
                    val res = action()
                    store.set(key + "timeout", null) and res
                } catch (e: Throwable) {
                    val wrapped = if (e is StepExecutionException) e else StepExecutionException(stepId, e)
                    store.set(key + "timeout", null) and Try.failure(wrapped)
                }
            } else {
                Try.state(BLOCKED)
            }
        }
}
