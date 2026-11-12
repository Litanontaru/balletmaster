package org.dmg.balletmaster.context

import org.dmg.balletmaster.StepExecutionException
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.context.TechStatus.BLOCKED
import org.dmg.balletmaster.context.TechStatus.NO_CALL
import org.dmg.balletmaster.logging.BalletMdc
import org.dmg.balletmaster.logging.LockLog
import org.dmg.balletmaster.logging.LoggingContext
import org.dmg.balletmaster.logging.StepLog
import org.dmg.balletmaster.logging.logStoreError
import org.dmg.balletmaster.store.Store
import org.dmg.balletmaster.store.lessThen
import org.dmg.balletmaster.util.and
import java.lang.System.currentTimeMillis

internal class StoreAccessException(override val cause: Throwable) : RuntimeException(cause)

private const val DEFAULT_LEASE = 1000L

class ContextImpl(
    private val store: Store,
    private val callProvider: () -> CallContext?,
    private val off: () -> Unit,
    private val onSetSuffix: (Any) -> Unit = {}
) : InternalContext, LoggingContext {
    private val call: CallContext? get() = callProvider()

    private var stepId: StepId = DefaultStep
    private var stepSuffix: Any = ""
    private var lease = DEFAULT_LEASE

    private val key: String get() = stepKey(stepId)

    private val previousAttemptKey: String
        get() = stepKey(stepId, call?.attempt?.takeIf { it > 0 }?.dec())

    override val operationSuffix: Any get() = call?.operationSuffix ?: ""

    override fun currentCall(): CallContext? = call

    override fun currentSuffix(): Any = stepSuffix

    override fun isDone(stepId: StepId): Boolean =
        (stepKey(stepId) + "output").let { targetKey ->
            store.get<Try<*>>(targetKey)
                .logStoreFailure("is_done", targetKey, stepId)
                .fold(
                    onSuccess = { it?.isSuccess == true },
                    onFailure = { throw StoreAccessException(it) }
                )
        }

    override fun setStep(stepId: StepId) {
        this.stepId = stepId
        this.stepSuffix = operationSuffix
        this.lease = DEFAULT_LEASE
    }

    override fun setLease(lease: Long) {
        require(lease > 0) { "Lease must be positive, got $lease" }
        this.lease = lease
    }

    override fun setSuffix(suffix: Any) {
        onSetSuffix(suffix)
    }

    override fun setInput(value: Any): Result<Boolean> =
        (key + "input").let { store.set(it, value).logStoreFailure("set_input", it) }

    override fun idempotencyGate(): Result<Boolean> =
        (key + "output").let { store.set(it, null to null).logStoreFailure("idempotency_gate", it) }

    override fun setOutput(value: Try<Any>): Result<Boolean> =
        (key + "output").let { store.set(it, value).logStoreFailure("set_output", it) }

    override fun <R : Any> originalOutput(): Result<Try<R>?> =
        (previousAttemptKey + "output").let { store.get<Try<R>>(it).logStoreFailure("original_output", it) }

    override fun isReplay() = call?.isReplay ?: false

    override fun setPlay() {
        val wasReplay = isReplay()
        off()
        if (wasReplay && !isReplay()) {
            BalletMdc.updateReplay(false)
            StepLog.replaySwitchedToLive(call, stepId, stepSuffix)
        }
    }

    override fun <R : Any> execute(action: () -> Try<R>): Try<R> =
        BalletMdc.withStepContext(call, stepId, stepSuffix) {
            if (call == null) Try.state<R>(NO_CALL).also { StepLog.noCall(stepId) }
            else tryLock() and { acquired ->
                if (acquired > 0L) executeWithLease(acquired, action) else Try.state(BLOCKED)
            }
        }

    private fun <R : Any> executeWithLease(acquired: Long, action: () -> Try<R>): Try<R> {
        val startNanos = StepLog.startTimer()
        var unlockResult: Result<Boolean>
        val stepResult = try {
            StepLog.stepStarted(call, stepId, stepSuffix, lease, isReplay())
            runProtectedAction(action)
        } finally {
            unlockResult = safeUnlock(acquired)
        }
        return (if (stepResult.isError) stepResult else unlockResult and stepResult).also {
            StepLog.stepCompleted(call, stepId, stepSuffix, it, startNanos)
        }
    }

    private fun <R : Any> runProtectedAction(action: () -> Try<R>): Try<R> = try {
        action()
    } catch (e: StoreAccessException) {
        Try.error(e.cause)
    } catch (e: Exception) {
        val wrapped = e as? StepExecutionException ?: StepExecutionException(stepId, e)
        StepLog.unexpectedException(call, stepId, stepSuffix, "execute", e)
        Try.failure(wrapped)
    }

    private fun safeUnlock(acquired: Long): Result<Boolean> = try {
        unlock(acquired)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun tryLock(): Result<Long> {
        val lockKey = key + "timeout"
        val startNanos = LockLog.startTimer()
        val deadline = currentTimeMillis() + lease
        var attempts = 0
        var interrupted = false

        while (currentTimeMillis() <= deadline) {
            attempts++
            tryAcquireOnce(lockKey, startNanos, attempts)?.let { return it }
            LockLog.contentionWait(call, stepId, stepSuffix, attempts, startNanos)

            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
                LockLog.waitInterrupted(call, stepId, stepSuffix, e, attempts, startNanos)
                break
            }
        }

        LockLog.blocked(call, stepId, stepSuffix, lease, startNanos, attempts, interrupted)
        return Result.success(0L)
    }

    private fun tryAcquireOnce(lockKey: String, startNanos: Long?, attempts: Int): Result<Long>? {
        val now = currentTimeMillis()
        val leaseUntil = now + lease
        return tryLockStep(lockKey, null, leaseUntil, "lock_acquire") {
            LockLog.acquired(call, stepId, stepSuffix, lease, leaseUntil, startNanos, attempts)
        } ?: tryLockStep(lockKey, lessThen(now), leaseUntil, "lock_steal") {
            LockLog.stolenExpired(call, stepId, stepSuffix, lease, leaseUntil, startNanos, attempts)
        }
    }

    private inline fun tryLockStep(
        lockKey: String,
        expected: Any?,
        leaseUntil: Long,
        storeOp: String,
        onAcquired: () -> Unit
    ): Result<Long>? =
        store.set(lockKey, expected to leaseUntil)
            .logStoreFailure(storeOp, lockKey)
            .fold(
                onSuccess = {
                    if (it) {
                        onAcquired()
                        Result.success(leaseUntil)
                    } else {
                        null
                    }
                },
                onFailure = Result.Companion::failure
            )

    private fun unlock(expected: Long): Result<Boolean> =
        (key + "timeout").let { lockKey ->
            store.set(lockKey, expected to null)
                .logStoreFailure("lock_unlock", lockKey)
                .onSuccess { released -> LockLog.unlockOutcome(call, stepId, stepSuffix, expected, released) }
        }

    private fun stepKey(stepId: StepId, attempt: Int? = call?.attempt) =
        "${call?.operationName}:${call?.operationId}:$attempt:$stepSuffix:$stepId:"

    private fun <T> Result<T>.logStoreFailure(
        storeOp: String,
        targetKey: String,
        targetStepId: StepId = stepId
    ): Result<T> = logStoreError(call, targetStepId, stepSuffix, storeOp, targetKey)
}
