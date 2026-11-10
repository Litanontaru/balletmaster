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

internal class StoreAccessException(override val cause: Throwable) : RuntimeException(cause)

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

    override fun isDone(stepId: StepId) =
        store.get<Try<*>>(key(stepId) + "output").fold(
            onSuccess = { it?.isSuccess == true },
            onFailure = { throw StoreAccessException(it) }
        )

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

    private fun tryLock(): Result<Boolean> {
        val deadline = currentTimeMillis() + timeout

        while (currentTimeMillis() <= deadline) {
            val now = currentTimeMillis()
            val leaseUntil = now + timeout

            val lockResult = store.set(key + "timeout", null to leaseUntil) + {
                if (it) Result.success(it)
                else store.set(key + "timeout", lessThen(now) to leaseUntil)
            }

            if (lockResult.isFailure || lockResult.getOrNull() == true) {
                return lockResult
            }

            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return Result.success(false)
    }

    private fun unlock(): Result<Boolean> = store.set(key + "timeout", null)

    override fun <R : Any> execute(action: () -> Try<R>): Try<R> = if (call == null) {
        Try.state(NO_CALL)
    } else {
        tryLock() and { acquired ->
            if (acquired) {
                val res = try {
                    action()
                } catch (e: StoreAccessException) {
                    Try.error(e.cause)
                } catch (e: Throwable) {
                    val wrapped = if (e is StepExecutionException) e else StepExecutionException(stepId, e)
                    Try.failure(wrapped)
                }
                val unlockResult = unlock()
                if (res.isError) res else unlockResult and res
            } else {
                Try.state(BLOCKED)
            }
        }
    }
}
