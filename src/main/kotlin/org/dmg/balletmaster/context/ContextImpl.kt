package org.dmg.balletmaster.context

import org.dmg.balletmaster.StepExecutionException
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.context.TechStatus.BLOCKED
import org.dmg.balletmaster.context.TechStatus.NO_CALL
import org.dmg.balletmaster.logging.BalletLoggers
import org.dmg.balletmaster.logging.BalletMdc
import org.dmg.balletmaster.logging.LoggingContext
import org.dmg.balletmaster.logging.debugEvent
import org.dmg.balletmaster.logging.elapsedMillis
import org.dmg.balletmaster.logging.errorEvent
import org.dmg.balletmaster.logging.infoEvent
import org.dmg.balletmaster.logging.outcomeLabel
import org.dmg.balletmaster.logging.stateStatusOrNull
import org.dmg.balletmaster.logging.traceEvent
import org.dmg.balletmaster.logging.warnEvent
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
    ): Result<T> = onFailure { ex ->
        BalletLoggers.store.errorEvent("store.error", ex) {
            correlation(call, targetStepId, stepSuffix)
            attr("storeOp", storeOp)
            attr("key", targetKey)
        }
    }

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
        BalletMdc.updateSuffix(suffix)
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
        val nowReplay = isReplay()
        if (wasReplay && !nowReplay) {
            BalletMdc.updateReplay(false)
            BalletLoggers.replay.infoEvent("replay.switched_to_live") {
                correlation(call, stepId, stepSuffix)
            }
        }
    }

    private fun tryLock(): Result<Long> {
        val lockKey = key + "timeout"
        val startNanos = System.nanoTime()
        val deadline = currentTimeMillis() + lease
        var attempts = 0
        var interrupted = false

        while (currentTimeMillis() <= deadline) {
            attempts++
            val now = currentTimeMillis()
            val leaseUntil = now + lease

            val freshAcquire = store.set(lockKey, null to leaseUntil).logStoreFailure("lock_acquire", lockKey)
            if (freshAcquire.isFailure) {
                return freshAcquire.map { 0L }
            }
            if (freshAcquire.getOrDefault(false)) {
                BalletLoggers.lock.traceEvent("lock.acquired") {
                    correlation(call, stepId, stepSuffix)
                    attr("leaseMs", lease)
                    attr("leaseUntil", leaseUntil)
                    attr("waitMs", elapsedMillis(startNanos))
                    attr("attempts", attempts)
                }
                return Result.success(leaseUntil)
            }

            val stealExpired = store.set(lockKey, lessThen(now) to leaseUntil).logStoreFailure("lock_steal", lockKey)
            if (stealExpired.isFailure) {
                return stealExpired.map { 0L }
            }
            if (stealExpired.getOrDefault(false)) {
                BalletLoggers.lock.warnEvent("lock.stolen_expired") {
                    correlation(call, stepId, stepSuffix)
                    attr("leaseMs", lease)
                    attr("leaseUntil", leaseUntil)
                    attr("waitMs", elapsedMillis(startNanos))
                    attr("attempts", attempts)
                }
                return Result.success(leaseUntil)
            }

            BalletLoggers.lock.traceEvent("lock.contention_wait") {
                correlation(call, stepId, stepSuffix)
                attr("attemptNo", attempts)
                attr("elapsedMs", elapsedMillis(startNanos))
            }

            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
                BalletLoggers.lock.warnEvent("lock.wait_interrupted", e) {
                    correlation(call, stepId, stepSuffix)
                    attr("attempts", attempts)
                    attr("waitMs", elapsedMillis(startNanos))
                }
                break
            }
        }

        BalletLoggers.lock.warnEvent("lock.blocked") {
            correlation(call, stepId, stepSuffix)
            attr("status", BLOCKED)
            attr("leaseMs", lease)
            attr("waitMs", elapsedMillis(startNanos))
            attr("attempts", attempts)
            attr("interrupted", interrupted)
        }
        return Result.success(0L)
    }

    private fun unlock(expected: Long): Result<Boolean> {
        val lockKey = key + "timeout"
        return store.set(lockKey, expected to null)
            .logStoreFailure("lock_unlock", lockKey)
            .onSuccess { released ->
                if (released) {
                    BalletLoggers.lock.traceEvent("lock.released") {
                        correlation(call, stepId, stepSuffix)
                        attr("leaseUntil", expected)
                    }
                } else {
                    BalletLoggers.lock.warnEvent("lock.stale_unlock_skipped") {
                        correlation(call, stepId, stepSuffix)
                        attr("expectedLeaseUntil", expected)
                    }
                }
            }
    }

    override fun <R : Any> execute(action: () -> Try<R>): Try<R> =
        BalletMdc.withStepContext(call, stepId, stepSuffix) {
            if (call == null) {
                BalletLoggers.step.warnEvent("step.no_call") {
                    attr("step", stepId)
                    attr("status", NO_CALL)
                }
                Try.state(NO_CALL)
            } else {
                tryLock() and { acquired ->
                    if (acquired > 0L) {
                        val startNanos = System.nanoTime()
                        var res: Try<R>
                        var unlockResult: Result<Boolean>
                        try {
                            BalletLoggers.step.debugEvent("step.started") {
                                correlation(call, stepId, stepSuffix)
                                attr("leaseMs", lease)
                                attr("replay", isReplay())
                            }
                            res = try {
                                action()
                            } catch (e: StoreAccessException) {
                                Try.error(e.cause)
                            } catch (e: Exception) {
                                val wrapped = if (e is StepExecutionException) e else StepExecutionException(stepId, e)
                                BalletLoggers.step.warnEvent("step.exception", wrapped) {
                                    correlation(call, stepId, stepSuffix)
                                    attr("phase", "execute")
                                    attr("exception", e.javaClass.simpleName)
                                }
                                Try.failure(wrapped)
                            }
                        } finally {
                            unlockResult = try {
                                unlock(acquired)
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                        val finalRes = if (res.isError) res else unlockResult and res
                        BalletLoggers.step.debugEvent("step.completed") {
                            correlation(call, stepId, stepSuffix)
                            attr("outcome", finalRes.outcomeLabel())
                            attr("status", finalRes.stateStatusOrNull())
                            attr("durationMs", elapsedMillis(startNanos))
                        }
                        finalRes
                    } else {
                        Try.state(BLOCKED)
                    }
                }
            }
        }
}
