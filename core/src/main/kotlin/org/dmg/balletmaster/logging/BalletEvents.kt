package org.dmg.balletmaster.logging

import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.context.CallContext
import org.dmg.balletmaster.context.InternalContext
import org.dmg.balletmaster.context.TechStatus.BLOCKED
import org.dmg.balletmaster.context.TechStatus.NO_CALL
import org.dmg.balletmaster.context.TechStatus.SKIP

internal inline fun <T> Result<T>.logStoreFailure(
    storeOp: String,
    targetKey: String,
    crossinline contextAttrs: LogAttrs.() -> Unit
): Result<T> = onFailure { ex ->
    BalletLoggers.store.errorEvent("store.error", ex) {
        contextAttrs()
        attr("storeOp", storeOp)
        attr("key", targetKey)
    }
}

internal fun <T> Result<T>.logStoreError(
    operationName: String,
    storeOp: String,
    targetKey: String
): Result<T> = logStoreFailure(storeOp, targetKey) {
    attr(BalletMdc.OPERATION, operationName)
}

internal fun <T> Result<T>.logStoreError(
    call: CallContext?,
    stepId: StepId,
    suffix: Any,
    storeOp: String,
    targetKey: String
): Result<T> = logStoreFailure(storeOp, targetKey) {
    correlation(call, stepId, suffix)
}

internal fun <T> Result<T>.logStoreError(
    operationName: String,
    operationId: String,
    attempt: Int,
    suffix: Any,
    stepId: StepId,
    storeOp: String,
    targetKey: String
): Result<T> = logStoreFailure(storeOp, targetKey) {
    correlation(operationName, operationId, attempt, suffix, stepId)
}

internal object StateLog {
    fun configSaved(operationName: String, version: String, rulesCount: Int) {
        BalletLoggers.state.infoEvent("state.config_saved") {
            attr(BalletMdc.OPERATION, operationName)
            attr("version", version)
            attr("rulesCount", rulesCount)
        }
    }

    fun configNotFound(operationName: String, versionPrefix: String) {
        BalletLoggers.state.warnEvent("state.config_not_found") {
            attr(BalletMdc.OPERATION, operationName)
            attr("versionPrefix", versionPrefix)
        }
    }

    fun resolved(
        operationName: String,
        operationId: String,
        attempt: Int,
        suffix: Any,
        version: String,
        versionPrefix: String,
        state: Any
    ) {
        BalletLoggers.state.debugEvent("state.resolved") {
            correlation(operationName, operationId, attempt, suffix)
            attr("version", version)
            attr("versionPrefix", versionPrefix)
            attr("state", safeStateLabel(state))
        }
    }
}

internal object LockLog {
    fun startTimer(): Long? = BalletLoggers.lock.startWarnTimer()

    fun acquired(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        leaseMs: Long,
        leaseUntil: Long,
        startNanos: Long?,
        attempts: Int
    ) = BalletLoggers.lock.traceEvent("lock.acquired") {
        lockAcquireAttrs(call, stepId, suffix, leaseMs, leaseUntil, startNanos, attempts)
    }

    fun stolenExpired(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        leaseMs: Long,
        leaseUntil: Long,
        startNanos: Long?,
        attempts: Int
    ) = BalletLoggers.lock.warnEvent("lock.stolen_expired") {
        lockAcquireAttrs(call, stepId, suffix, leaseMs, leaseUntil, startNanos, attempts)
    }

    fun contentionWait(call: CallContext?, stepId: StepId, suffix: Any, attemptNo: Int, startNanos: Long?) {
        BalletLoggers.lock.traceEvent("lock.contention_wait") {
            correlation(call, stepId, suffix)
            attr("attemptNo", attemptNo)
            attr("elapsedMs", elapsedMillis(startNanos))
        }
    }

    fun waitInterrupted(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        error: InterruptedException,
        attempts: Int,
        startNanos: Long?
    ) {
        BalletLoggers.lock.warnEvent("lock.wait_interrupted", error) {
            correlation(call, stepId, suffix)
            attr("attempts", attempts)
            attr("waitMs", elapsedMillis(startNanos))
        }
    }

    fun blocked(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        leaseMs: Long,
        startNanos: Long?,
        attempts: Int,
        interrupted: Boolean
    ) {
        BalletLoggers.lock.debugEvent("lock.blocked") {
            correlation(call, stepId, suffix)
            attr("status", BLOCKED)
            attr("leaseMs", leaseMs)
            attr("waitMs", elapsedMillis(startNanos))
            attr("attempts", attempts)
            attr("interrupted", interrupted)
        }
    }

    fun unlockOutcome(call: CallContext?, stepId: StepId, suffix: Any, expectedLeaseUntil: Long, released: Boolean) {
        if (released) {
            BalletLoggers.lock.traceEvent("lock.released") {
                correlation(call, stepId, suffix)
                attr("leaseUntil", expectedLeaseUntil)
            }
        } else {
            BalletLoggers.lock.warnEvent("lock.stale_unlock_skipped") {
                correlation(call, stepId, suffix)
                attr("expectedLeaseUntil", expectedLeaseUntil)
            }
        }
    }

    private fun LogAttrs.lockAcquireAttrs(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        leaseMs: Long,
        leaseUntil: Long,
        startNanos: Long?,
        attempts: Int
    ) {
        correlation(call, stepId, suffix)
        attr("leaseMs", leaseMs)
        attr("leaseUntil", leaseUntil)
        attr("waitMs", elapsedMillis(startNanos))
        attr("attempts", attempts)
    }
}

internal object StepLog {
    fun startTimer(): Long? = BalletLoggers.step.startDebugTimer()

    fun currentCall(context: InternalContext): CallContext? =
        (context as? LoggingContext)?.currentCall()

    private fun InternalContext.currentSuffixOrDefault(): Any =
        (this as? LoggingContext)?.currentSuffix() ?: operationSuffix

    fun noCall(stepId: StepId) {
        BalletLoggers.step.warnEvent("step.no_call") {
            attr(BalletMdc.STEP, stepId)
            attr("status", NO_CALL)
        }
    }

    fun stepStarted(call: CallContext?, stepId: StepId, suffix: Any, leaseMs: Long, isReplay: Boolean) {
        BalletLoggers.step.debugEvent("step.started") {
            correlation(call, stepId, suffix)
            attr("leaseMs", leaseMs)
            attr(BalletMdc.REPLAY, isReplay)
        }
    }

    fun stepCompleted(call: CallContext?, stepId: StepId, suffix: Any, result: Try<*>, startNanos: Long?) {
        BalletLoggers.step.debugEvent("step.completed") {
            correlation(call, stepId, suffix)
            attr("outcome", result.outcomeLabel())
            attr("status", result.stateStatusOrNull())
            attr("durationMs", elapsedMillis(startNanos))
        }
    }

    fun stepException(
        context: InternalContext,
        stepId: StepId,
        phase: String,
        error: Exception
    ) = BalletLoggers.step.debugEvent("step.exception") {
        stepExceptionAttrs(currentCall(context), stepId, context.currentSuffixOrDefault(), phase, error)
    }

    fun unexpectedException(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        phase: String,
        error: Exception
    ) = BalletLoggers.step.warnEvent("step.exception") {
        stepExceptionAttrs(call, stepId, suffix, phase, error)
    }

    fun skippedGate(context: InternalContext, stepId: StepId) =
        logSkipped(context, stepId, "step.skipped_gate")

    fun skippedIdempotent(context: InternalContext, stepId: StepId) =
        logSkipped(context, stepId, "step.skipped_idempotent")

    fun replayCacheHit(context: InternalContext, stepId: StepId, original: Try<*>) {
        BalletLoggers.replay.debugEvent("replay.cache_hit") {
            correlation(context, stepId)
            attr("outcome", original.outcomeLabel())
        }
    }

    fun replayCacheMiss(context: InternalContext, stepId: StepId) {
        BalletLoggers.replay.debugEvent("replay.cache_miss") {
            correlation(context, stepId)
        }
    }

    fun replaySwitchedToLive(call: CallContext?, stepId: StepId, suffix: Any) {
        BalletLoggers.replay.infoEvent("replay.switched_to_live") {
            correlation(call, stepId, suffix)
        }
    }

    fun milestoneUpdated(context: InternalContext, stepId: StepId, oldSuffix: Any, newSuffix: Any) {
        BalletLoggers.step.debugEvent("step.milestone_updated") {
            correlation(currentCall(context), stepId, oldSuffix)
            attr("newSuffix", newSuffix)
        }
    }

    fun currentSuffix(context: InternalContext): Any = context.currentSuffixOrDefault()

    fun checkpointBypassed(context: InternalContext, stepId: StepId, output: Try<*>) {
        BalletLoggers.step.traceEvent("step.checkpoint_bypassed") {
            correlation(context, stepId)
            attr("outcome", output.outcomeLabel())
            attr("status", output.stateStatusOrNull())
        }
    }

    fun checkpointResult(context: InternalContext, stepId: StepId, output: Try<*>, saved: Boolean) {
        val call = currentCall(context)
        val suffix = context.currentSuffixOrDefault()
        if (saved) {
            BalletLoggers.step.debugEvent("step.checkpoint_saved") { checkpointAttrs(call, stepId, suffix, output) }
        } else {
            BalletLoggers.step.warnEvent("step.checkpoint_not_saved") { checkpointAttrs(call, stepId, suffix, output) }
        }
    }

    inline fun chainStarted(
        context: InternalContext,
        totalSteps: Int,
        stepsSummary: () -> String
    ) {
        if (totalSteps <= 0) return
        BalletLoggers.step.debugEvent("chain.started") {
            correlation(currentCall(context))
            attr("stepsCount", totalSteps)
            attr("steps", stepsSummary())
        }
    }

    fun chainStopped(
        context: InternalContext,
        stepId: StepId,
        executedSteps: Int,
        totalSteps: Int,
        result: Try<*>,
        startNanos: Long?
    ) {
        val call = currentCall(context)
        val suffix = context.currentSuffixOrDefault()
        BalletMdc.applyContext(call, stepId, suffix)
        BalletLoggers.step.debugEvent("chain.stopped") {
            correlation(call, stepId, suffix)
            attr("executedSteps", executedSteps)
            attr("totalSteps", totalSteps)
            attr("outcome", result.outcomeLabel())
            attr("status", result.stateStatusOrNull())
            attr("durationMs", elapsedMillis(startNanos))
        }
    }

    fun chainCompleted(context: InternalContext, totalSteps: Int, startNanos: Long?) {
        if (totalSteps <= 0) return
        val call = currentCall(context)
        BalletMdc.applyContext(call, null, call?.operationSuffix ?: "")
        BalletLoggers.step.debugEvent("chain.completed") {
            correlation(call)
            attr("stepsCount", totalSteps)
            attr("durationMs", elapsedMillis(startNanos))
        }
    }

    private fun logSkipped(context: InternalContext, stepId: StepId, event: String) {
        BalletLoggers.step.debugEvent(event) {
            correlation(context, stepId)
            attr("status", SKIP)
        }
    }

    private fun LogAttrs.correlation(context: InternalContext, stepId: StepId) =
        correlation(currentCall(context), stepId, context.currentSuffixOrDefault())

    private fun LogAttrs.stepExceptionAttrs(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        phase: String,
        error: Exception
    ) {
        correlation(call, stepId, suffix)
        attr("phase", phase)
        attr("exception", safeExceptionName(error))
    }

    private fun LogAttrs.checkpointAttrs(
        call: CallContext?,
        stepId: StepId,
        suffix: Any,
        output: Try<*>
    ) {
        correlation(call, stepId, suffix)
        attr("outcome", output.outcomeLabel())
    }
}
