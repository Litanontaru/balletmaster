package org.dmg.balletmaster

import org.dmg.balletmaster.context.InternalContext
import org.dmg.balletmaster.context.TechStatus.SKIP
import org.dmg.balletmaster.util.and

class Step<T : Any> private constructor(
    private val context: InternalContext,
    private val chain: Chain,
) {
    internal constructor(context: InternalContext) : this(context, Chain())

    private class Chain {
        val actions = ArrayList<Pair<StepId, MutableList<(StepId, Try<Any>) -> Try<Any>>>>()

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

        fun step(stepId: StepId) {
            actions.add(stepId to ArrayList())
        }
    }

    private infix fun Throwable.wrapped(stepId: StepId) =
        if (this is StepExecutionException) this else StepExecutionException(stepId, this)

    infix fun <R : Any> input(value: R) = Step<R>(context, chain.apply {
        simple<T, R> { _, _ -> context.setInput(value) and { Try.success(value) } }
    })

    infix fun gate(gate: (Context, T) -> Boolean) = Step<T>(context, chain.apply {
        simple<T, T> { stepId, input ->
            try {
                if (gate(context, input)) {
                    Try.success(input)
                } else {
                    Try.state(SKIP)
                }
            } catch (e: Throwable) {
                Try.failure(e wrapped stepId)
            }
        }
    })

    infix fun <R : Any> map(next: (Context, T) -> R) = Step<R>(context, chain.apply {
        simple<T, R> { stepId, input ->
            context.idempotencyGate() and {
                if (it) {
                    fun execute() = try {
                        Try.success(next(context, input))
                    } catch (e: Throwable) {
                        Try.failure(e wrapped stepId)
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
    })

    infix fun milestone(milestone: (Context, T) -> Any) = Step<T>(context, chain.apply {
        simple<T, T> { stepId, input ->
            try {
                context.setSuffix(milestone(context, input))
                Try.success(input)
            } catch (e: Throwable) {
                Try.failure(e wrapped stepId)
            }
        }
    })

    fun checkPoint() = Step<T>(context, chain.apply {
        complex<T> { _, output ->
            if (output.isState) {
                output
            } else {
                context.setOutput(output).fold({ output }, Try.Companion::failure)
            }
        }
    })

    infix fun step(stepId: StepId) = Step<T>(context, chain.apply {
        val hasPrev = actions.isNotEmpty()
        step(stepId)
        if (hasPrev) {
            simple<T, T> { _, input -> context.setInput(input) and { Try.success(input) } }
        }
    })

    fun execute(): Try<T> {
        var input: Try<Any> = Try.success(Unit)
        for ((stepId, steps) in chain.actions) {
            context.setStep(stepId)
            input = context.execute {
                for (action in steps) {
                    input = action(stepId, input)
                }
                input
            }
            if (!input.isSuccess) {
                break
            }
        }
        @Suppress("UNCHECKED_CAST")
        return input as Try<T>
    }
}
