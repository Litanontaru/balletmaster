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

    override val operationSuffix: Any get() = call?.operationSuffix ?: ""

    override fun currentCall(): CallContext? = call

    override fun currentSuffix(): Any = stepSuffix

    private fun key(stepId: StepId) = aKey(stepId, call?.attempt, stepSuffix)

    private fun aKey(stepId: StepId, attempt: Int?, suffix: Any) =
        "${call?.operationName}:${call?.operationId}:${attempt}:${suffix}:${stepId}:"

    private val key: String get() = key(stepId)

    private val originalKey: String get() = aKey(stepId, call?.attempt?.takeIf { it > 0 }?.let { it - 1 }, stepSuffix)

    private fun <T> Result<T>.logStoreFailure(
        storeOp: String,
        targetKey: String,
        targetStepId: StepId = stepId
    ): Result<T> = logStoreError(call, targetStepId, stepSuffix, storeOp, targetKey)

    override fun isDone(stepId: StepId): Boolean {
        val targetKey = key(stepId) + "output"
        return store.get<Try<*>>(targetKey)
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

    override fun setInput(value: Any): Result<Boolean> {
        val targetKey = key + "input"
        return store.set(targetKey, value).logStoreFailure("set_input", targetKey)
    }

    override fun idempotencyGate(): Result<Boolean> {
        val targetKey = key + "output"
        return store.set(targetKey, null to null).logStoreFailure("idempotency_gate", targetKey)
    }

    override fun setOutput(value: Try<Any>): Result<Boolean> {
        val targetKey = key + "output"
        return store.set(targetKey, value).logStoreFailure("set_output", targetKey)
    }

    override fun <R : Any> originalOutput(): Result<Try<R>?> {
        val targetKey = originalKey + "output"
        return store.get<Try<R>>(targetKey).logStoreFailure("original_output", targetKey)
    }

    override fun isReplay() = call?.isReplay ?: false

    override fun setPlay() {
        val wasReplay = isReplay()
        off()
        if (wasReplay && !isReplay()) {
            BalletMdc.updateReplay(false)
            StepLog.replaySwitchedToLive(call, stepId, stepSuffix)
        }
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

        val freshAcquire = store.set(lockKey, null to leaseUntil).logStoreFailure("lock_acquire", lockKey)
        if (freshAcquire.isFailure) return freshAcquire.map { 0L }
        if (freshAcquire.getOrDefault(false)) {
            LockLog.acquired(call, stepId, stepSuffix, lease, leaseUntil, startNanos, attempts)
            return Result.success(leaseUntil)
        }

        val stealExpired = store.set(lockKey, lessThen(now) to leaseUntil).logStoreFailure("lock_steal", lockKey)
        if (stealExpired.isFailure) return stealExpired.map { 0L }
        if (stealExpired.getOrDefault(false)) {
            LockLog.stolenExpired(call, stepId, stepSuffix, lease, leaseUntil, startNanos, attempts)
            return Result.success(leaseUntil)
        }

        return null
    }

    private fun unlock(expected: Long): Result<Boolean> {
        val lockKey = key + "timeout"
        return store.set(lockKey, expected to null)
            .logStoreFailure("lock_unlock", lockKey)
            .onSuccess { released -> LockLog.unlockOutcome(call, stepId, stepSuffix, expected, released) }
    }

    override fun <R : Any> execute(action: () -> Try<R>): Try<R> =
        BalletMdc.withStepContext(call, stepId, stepSuffix) {
            if (call == null) {
                StepLog.noCall(stepId)
                return@withStepContext Try.state(NO_CALL)
            }
            tryLock() and { acquired ->
                if (acquired > 0L) executeWithLease(acquired, action) else Try.state(BLOCKED)
            }
        }

    private fun <R : Any> executeWithLease(acquired: Long, action: () -> Try<R>): Try<R> {
        val startNanos = StepLog.startTimer()
        var res: Try<R>
        var unlockResult: Result<Boolean>
        try {
            StepLog.stepStarted(call, stepId, stepSuffix, lease, isReplay())
            res = runProtectedAction(action)
        } finally {
            unlockResult = try {
                unlock(acquired)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val finalRes = if (res.isError) res else unlockResult and res
        StepLog.stepCompleted(call, stepId, stepSuffix, finalRes, startNanos)
        return finalRes
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
}
