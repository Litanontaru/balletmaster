package org.dmg.balletmaster

import org.dmg.balletmaster.context.InternalContext
import org.dmg.balletmaster.context.StoreAccessException
import org.dmg.balletmaster.context.TechStatus.SKIP
import org.dmg.balletmaster.logging.BalletMdc
import org.dmg.balletmaster.logging.StepLog
import org.dmg.balletmaster.util.and

class Step<T : Any> private constructor(
    private val context: InternalContext,
    private val chain: Chain,
) {
    internal constructor(context: InternalContext) : this(context, Chain())

    infix fun step(stepId: StepId): Step<T> = next {
        val hasPrev = steps.isNotEmpty()
        addStep(stepId)
        if (hasPrev) {
            simple<T, T> { _, input -> context.setInput(input) and { Try.success(input) } }
        }
    }

    /**
     * Sets the lease duration (in milliseconds) for the current step only;
     * subsequent steps use the default lease unless configured explicitly.
     */
    infix fun lease(lease: Long): Step<T> = next {
        require(lease > 0) { "Lease must be positive, got $lease" }
        steps.lastOrNull()?.lease = lease
    }

    infix fun <R : Any> input(value: R): Step<R> = nextSimple { _, _ ->
        context.setInput(value) and { Try.success(value) }
    }

    infix fun gate(gate: (Context, T) -> Boolean): Step<T> = nextSimple { stepId, input ->
        tryStep(stepId, "gate") {
            if (gate(context, input)) {
                Try.success(input)
            } else {
                StepLog.skippedGate(context, stepId)
                Try.state(SKIP)
            }
        }
    }

    infix fun <R : Any> map(next: (Context, T) -> R): Step<R> = nextSimple { stepId, input ->
        context.idempotencyGate() and { allowed ->
            when {
                !allowed -> {
                    StepLog.skippedIdempotent(context, stepId)
                    Try.state(SKIP)
                }
                context.isReplay() -> replayOrExecute(stepId, input, next)
                else -> executeMap(stepId, input, next)
            }
        }
    }

    infix fun milestone(milestone: (Context, T) -> Any): Step<T> = nextSimple { stepId, input ->
        tryStep(stepId, "milestone") {
            val oldSuffix = StepLog.currentSuffix(context)
            val newSuffix = milestone(context, input).also(context::setSuffix)
            StepLog.milestoneUpdated(context, stepId, oldSuffix, newSuffix)
            Try.success(input)
        }
    }

    fun checkPoint(): Step<T> = nextComplex { stepId, output ->
        if (output.isState || output.isError) {
            StepLog.checkpointBypassed(context, stepId, output)
            output
        } else {
            context.setOutput(output)
                .onSuccess { saved -> StepLog.checkpointResult(context, stepId, output, saved) } and output
        }
    }

    fun execute(): Try<T> = BalletMdc.withChainContext(StepLog.currentCall(context)) {
        val startNanos = StepLog.startTimer()
        val totalSteps = chain.steps.size
        StepLog.chainStarted(context, totalSteps) { chain.steps.joinToString(",") { it.stepId.toString() } }

        var result: Try<Any> = Try.success(Unit)
        for (index in chain.steps.indices) {
            val step = chain.steps[index]
            context.setStep(step.stepId)
            step.lease?.let(context::setLease)
            result = context.execute { step.actions.fold(result) { acc, action -> action(step.stepId, acc) } }
            if (!result.isSuccess) {
                StepLog.chainStopped(context, step.stepId, index + 1, totalSteps, result, startNanos)
                break
            }
        }

        if (result.isSuccess) {
            StepLog.chainCompleted(context, totalSteps, startNanos)
        }

        @Suppress("UNCHECKED_CAST")
        result as Try<T>
    }

    private fun <R : Any> next(block: Chain.() -> Unit): Step<R> = Step(context, chain.apply(block))

    private fun <R : Any> nextSimple(action: (StepId, T) -> Try<R>): Step<R> = next { simple(action) }

    private fun nextComplex(action: (StepId, Try<T>) -> Try<T>): Step<T> = next { complex(action) }

    private fun <R : Any> replayOrExecute(stepId: StepId, input: T, next: (Context, T) -> R): Try<R> =
        context.originalOutput<R>() and { original ->
            if (original != null) {
                StepLog.replayCacheHit(context, stepId, original)
                original
            } else {
                StepLog.replayCacheMiss(context, stepId)
                executeMap(stepId, input, next)
            }
        }

    private fun <R : Any> executeMap(stepId: StepId, input: T, next: (Context, T) -> R): Try<R> {
        context.setPlay()
        return tryStep(stepId, "map") { Try.success(next(context, input)) }
    }

    private inline fun <R : Any> tryStep(stepId: StepId, phase: String, block: () -> Try<R>): Try<R> =
        try {
            block()
        } catch (e: Exception) {
            e.toStepResult(stepId, phase)
        }

    private fun Exception.toStepResult(stepId: StepId, phase: String): Try<Nothing> =
        if (this is StoreAccessException) {
            Try.error(cause)
        } else {
            val wrapped = this as? StepExecutionException ?: StepExecutionException(stepId, this)
            StepLog.stepException(context, stepId, phase, this)
            Try.failure(wrapped)
        }

    private class Chain {
        val steps = ArrayList<StepEntry>()

        fun addStep(stepId: StepId) {
            steps += StepEntry(stepId)
        }

        fun <T : Any, R : Any> simple(action: (StepId, T) -> Try<R>) {
            steps.lastOrNull()?.actions?.add { stepId, input ->
                @Suppress("UNCHECKED_CAST")
                input.map { value -> action(stepId, value as T) }
            }
        }

        fun <R : Any> complex(action: (StepId, Try<R>) -> Try<R>) {
            @Suppress("UNCHECKED_CAST")
            steps.lastOrNull()?.actions?.add(action as ((StepId, Try<Any>) -> Try<Any>))
        }
    }

    private class StepEntry(
        val stepId: StepId,
        var lease: Long? = null
    ) {
        val actions = ArrayList<(StepId, Try<Any>) -> Try<Any>>()
    }
}
