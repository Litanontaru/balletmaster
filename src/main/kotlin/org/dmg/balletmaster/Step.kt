package org.dmg.balletmaster

import org.dmg.balletmaster.context.InternalContext
import org.dmg.balletmaster.context.StoreAccessException
import org.dmg.balletmaster.context.TechStatus.SKIP
import org.dmg.balletmaster.util.and

class Step<T : Any> private constructor(
    private val context: InternalContext,
    private val chain: Chain,
) {
    internal constructor(context: InternalContext) : this(context, Chain())

    private data class StepConfig(val stepId: StepId)

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

    private infix fun Exception.on(stepId: StepId) =
        if (this is StoreAccessException) Try.error<Nothing>(cause)
        else Try.failure(if (this is StepExecutionException) this else StepExecutionException(stepId, this))

    infix fun <R : Any> input(value: R): Step<R> = nextSimple { _, _ ->
        context.setInput(value) and { Try.success(value) }
    }

    infix fun gate(gate: (Context, T) -> Boolean): Step<T> = nextSimple { stepId, input ->
        try {
            if (gate(context, input)) {
                Try.success(input)
            } else {
                Try.state(SKIP)
            }
        } catch (e: Exception) {
            e on stepId
        }
    }

    infix fun <R : Any> map(next: (Context, T) -> R): Step<R> = nextSimple { stepId, input ->
        context.idempotencyGate() and {
            if (it) {
                fun execute() = try {
                    Try.success(next(context, input))
                } catch (e: Exception) {
                    e on stepId
                } finally {
                    context.setPlay()
                }

                if (context.isReplay()) context.originalOutput<R>() and { original -> original ?: execute() }
                else execute()
            } else {
                Try.state(SKIP)
            }
        }
    }

    infix fun milestone(milestone: (Context, T) -> Any): Step<T> = nextSimple { stepId, input ->
        try {
            context.setSuffix(milestone(context, input))
            Try.success(input)
        } catch (e: Exception) {
            e on stepId
        }
    }

    fun checkPoint(): Step<T> = nextComplex { _, output ->
        if (output.isState || output.isError) {
            output
        } else {
            context.setOutput(output) and output
        }
    }

    infix fun step(stepId: StepId): Step<T> = next {
        val hasPrev = actions.isNotEmpty()
        addStep(stepId)
        if (hasPrev) {
            simple<T, T> { _, input -> context.setInput(input) and { Try.success(input) } }
        }
    }

    fun execute(): Try<T> {
        var input: Try<Any> = Try.success(Unit)
        for ((config, steps) in chain.actions) {
            context.setStep(config.stepId)
            input = context.execute { steps.fold(input) { acc, action -> action(config.stepId, acc) } }
            if (!input.isSuccess) {
                break
            }
        }
        @Suppress("UNCHECKED_CAST")
        return input as Try<T>
    }
}
