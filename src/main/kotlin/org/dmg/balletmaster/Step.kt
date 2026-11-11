package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContext
import org.dmg.balletmaster.context.InternalContext
import org.dmg.balletmaster.context.StoreAccessException
import org.dmg.balletmaster.context.TechStatus.SKIP
import org.dmg.balletmaster.logging.BalletLoggers
import org.dmg.balletmaster.logging.LoggingContext
import org.dmg.balletmaster.logging.debugEvent
import org.dmg.balletmaster.logging.elapsedMillis
import org.dmg.balletmaster.logging.outcomeLabel
import org.dmg.balletmaster.logging.startDebugTimer
import org.dmg.balletmaster.logging.stateStatusOrNull
import org.dmg.balletmaster.logging.traceEvent
import org.dmg.balletmaster.logging.warnEvent
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

    private fun logCall(): CallContext? = (context as? LoggingContext)?.currentCall()

    private fun logSuffix(): Any = (context as? LoggingContext)?.currentSuffix() ?: context.operationSuffix

    private fun Exception.toStepResult(stepId: StepId, phase: String): Try<Nothing> =
        if (this is StoreAccessException) {
            Try.error(cause)
        } else {
            val wrapped = if (this is StepExecutionException) this else StepExecutionException(stepId, this)
            BalletLoggers.step.debugEvent("step.exception", wrapped) {
                correlation(logCall(), stepId, logSuffix())
                attr("phase", phase)
                attr("exception", this@toStepResult.javaClass.simpleName)
            }
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
                BalletLoggers.step.debugEvent("step.skipped_gate") {
                    correlation(logCall(), stepId, logSuffix())
                    attr("status", SKIP)
                }
                Try.state(SKIP)
            }
        } catch (e: Exception) {
            e.toStepResult(stepId, "gate")
        }
    }

    infix fun <R : Any> map(next: (Context, T) -> R): Step<R> = nextSimple { stepId, input ->
        context.idempotencyGate() and {
            if (it) {
                fun execute(): Try<R> {
                    context.setPlay()
                    return try {
                        Try.success(next(context, input))
                    } catch (e: Exception) {
                        e.toStepResult(stepId, "map")
                    }
                }

                if (context.isReplay()) {
                    context.originalOutput<R>() and { original ->
                        if (original != null) {
                            BalletLoggers.replay.debugEvent("replay.cache_hit") {
                                correlation(logCall(), stepId, logSuffix())
                                attr("outcome", original.outcomeLabel())
                            }
                            original
                        } else {
                            BalletLoggers.replay.debugEvent("replay.cache_miss") {
                                correlation(logCall(), stepId, logSuffix())
                            }
                            execute()
                        }
                    }
                } else {
                    execute()
                }
            } else {
                BalletLoggers.step.debugEvent("step.skipped_idempotent") {
                    correlation(logCall(), stepId, logSuffix())
                    attr("status", SKIP)
                }
                Try.state(SKIP)
            }
        }
    }

    infix fun milestone(milestone: (Context, T) -> Any): Step<T> = nextSimple { stepId, input ->
        try {
            val oldSuffix = logSuffix()
            val newSuffix = milestone(context, input)
            context.setSuffix(newSuffix)
            BalletLoggers.step.debugEvent("step.milestone_updated") {
                correlation(logCall(), stepId, oldSuffix)
                attr("newSuffix", newSuffix)
            }
            Try.success(input)
        } catch (e: Exception) {
            e.toStepResult(stepId, "milestone")
        }
    }

    fun checkPoint(): Step<T> = nextComplex { stepId, output ->
        if (output.isState || output.isError) {
            BalletLoggers.step.traceEvent("step.checkpoint_bypassed") {
                correlation(logCall(), stepId, logSuffix())
                attr("outcome", output.outcomeLabel())
                attr("status", output.stateStatusOrNull())
            }
            output
        } else {
            context.setOutput(output).onSuccess { saved ->
                if (saved) {
                    BalletLoggers.step.debugEvent("step.checkpoint_saved") {
                        correlation(logCall(), stepId, logSuffix())
                        attr("outcome", output.outcomeLabel())
                    }
                } else {
                    BalletLoggers.step.warnEvent("step.checkpoint_not_saved") {
                        correlation(logCall(), stepId, logSuffix())
                        attr("outcome", output.outcomeLabel())
                    }
                }
            } and output
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

    fun execute(): Try<T> {
        val startNanos = BalletLoggers.step.startDebugTimer()
        val totalSteps = chain.actions.size

        if (totalSteps > 0) {
            BalletLoggers.step.debugEvent("chain.started") {
                correlation(logCall())
                attr("stepsCount", totalSteps)
                attr("steps", chain.actions.joinToString(",") { it.first.stepId.toString() })
            }
        }

        var input: Try<Any> = Try.success(Unit)
        var executedSteps = 0
        for ((config, steps) in chain.actions) {
            executedSteps++
            context.setStep(config.stepId)
            config.lease?.let(context::setLease)
            input = context.execute { steps.fold(input) { acc, action -> action(config.stepId, acc) } }
            if (!input.isSuccess) {
                BalletLoggers.step.debugEvent("chain.stopped") {
                    correlation(logCall(), config.stepId, logSuffix())
                    attr("executedSteps", executedSteps)
                    attr("totalSteps", totalSteps)
                    attr("outcome", input.outcomeLabel())
                    attr("status", input.stateStatusOrNull())
                    attr("durationMs", elapsedMillis(startNanos))
                }
                break
            }
        }

        if (input.isSuccess && totalSteps > 0) {
            BalletLoggers.step.debugEvent("chain.completed") {
                correlation(logCall())
                attr("stepsCount", totalSteps)
                attr("durationMs", elapsedMillis(startNanos))
            }
        }

        @Suppress("UNCHECKED_CAST")
        return input as Try<T>
    }
}
