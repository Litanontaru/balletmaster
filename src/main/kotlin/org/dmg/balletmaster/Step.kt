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

    private data class StepConfig(
        val stepId: StepId,
        var lease: Long? = null
    )

    private class Chain {
        val actions = ArrayList<Pair<StepConfig, MutableList<(StepId, Try<Any>) -> Try<Any>>>>()

        fun <T : Any, R : Any> simple(action: (StepId, T) -> Try<R>) {
            actions.lastOrNull()?.second?.add { stepId, inputTry ->
                @Suppress("UNCHECKED_CAST")
                inputTry.map { input -> action(stepId, input as T) }
            }
        }

        fun <R : Any> complex(action: (StepId, Try<R>) -> Try<R>) {
            @Suppress("UNCHECKED_CAST")
            actions.lastOrNull()?.second?.add(action as ((StepId, Try<Any>) -> Try<Any>))
        }

        fun addStep(stepId: StepId) {
            actions.add(StepConfig(stepId) to ArrayList())
        }
    }

    private fun <R : Any> next(block: Chain.() -> Unit): Step<R> = Step(context, chain.apply(block))

    private fun <R : Any> nextSimple(action: (StepId, T) -> Try<R>): Step<R> = next { simple(action) }

    private fun nextComplex(action: (StepId, Try<T>) -> Try<T>): Step<T> = next { complex(action) }

    private fun Exception.toStepResult(stepId: StepId, phase: String): Try<Nothing> =
        if (this is StoreAccessException) {
            Try.error(cause)
        } else {
            val wrapped = this as? StepExecutionException ?: StepExecutionException(stepId, this)
            StepLog.stepException(context, stepId, phase, this)
            Try.failure(wrapped)
        }

    infix fun <R : Any> input(value: R): Step<R> = nextSimple { _, _ ->
        context.setInput(value) and { Try.success(value) }
    }

    infix fun gate(gate: (Context, T) -> Boolean): Step<T> = nextSimple { stepId, input ->
        try {
            if (gate(context, input)) {
                Try.success(input)
            } else {
                StepLog.skippedGate(context, stepId)
                Try.state(SKIP)
            }
        } catch (e: Exception) {
            e.toStepResult(stepId, "gate")
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
        return try {
            Try.success(next(context, input))
        } catch (e: Exception) {
            e.toStepResult(stepId, "map")
        }
    }

    infix fun milestone(milestone: (Context, T) -> Any): Step<T> = nextSimple { stepId, input ->
        try {
            val oldSuffix = StepLog.currentSuffix(context)
            val newSuffix = milestone(context, input)
            context.setSuffix(newSuffix)
            StepLog.milestoneUpdated(context, stepId, oldSuffix, newSuffix)
            Try.success(input)
        } catch (e: Exception) {
            e.toStepResult(stepId, "milestone")
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

    infix fun step(stepId: StepId): Step<T> = next {
        val hasPrev = actions.isNotEmpty()
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
        actions.lastOrNull()?.first?.lease = lease
    }

    fun execute(): Try<T> = BalletMdc.withChainContext(StepLog.currentCall(context)) {
        val startNanos = StepLog.startTimer()
        val totalSteps = chain.actions.size
        StepLog.chainStarted(context, totalSteps) { chain.actions.joinToString(",") { it.first.stepId.toString() } }

        var input: Try<Any> = Try.success(Unit)
        for (index in chain.actions.indices) {
            val (config, steps) = chain.actions[index]
            context.setStep(config.stepId)
            config.lease?.let(context::setLease)
            input = context.execute { steps.fold(input) { acc, action -> action(config.stepId, acc) } }
            if (!input.isSuccess) {
                StepLog.chainStopped(context, config.stepId, index + 1, totalSteps, input, startNanos)
                break
            }
        }

        if (input.isSuccess) {
            StepLog.chainCompleted(context, totalSteps, startNanos)
        }

        @Suppress("UNCHECKED_CAST")
        input as Try<T>
    }
}
