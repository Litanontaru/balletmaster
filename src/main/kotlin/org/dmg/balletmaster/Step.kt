package org.dmg.balletmaster

import org.dmg.balletmaster.context.DefaultStep
import org.dmg.balletmaster.context.InternalContext
import org.dmg.balletmaster.context.TechStatus.SKIP
import org.dmg.balletmaster.util.and

class Step<T : Any>(
    private val stepId: StepId,
    private val context: InternalContext,
    private val prev: Step<*>? = null,
    internal val action: (Try<Any>) -> Try<T>,
) {
    private fun Throwable.wrapped() =
        if (this is StepExecutionException) this else StepExecutionException(stepId, this)

    infix fun <R : Any> input(value: R) = Step(stepId, context, prev) { prevResult ->
        action(prevResult) map { context.setInput(value) and { Try.success(value) } }
    }

    infix fun gate(gate: (Context, T) -> Boolean) = Step(stepId, context, prev) { prevResult ->
        action(prevResult) map { input ->
            try {
                if (gate(context, input)) {
                    Try.success(input)
                } else {
                    Try.state(SKIP)
                }
            } catch (e: Throwable) {
                Try.failure(e.wrapped())
            }
        }
    }

    infix fun <R : Any> map(next: (Context, T) -> R) = Step(stepId, context, prev) { prevResult ->
        action(prevResult) map { input ->
            context.idempotencyGate() and {
                if (it) {
                    fun execute() = try {
                        Try.success(next(context, input))
                    } catch (e: Throwable) {
                        Try.failure(e.wrapped())
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
    }

    infix fun milestone(milestone: (Context, T) -> Any) = Step(stepId, context, prev) { prevResult ->
        action(prevResult) also { input ->
            context.setSuffix(milestone(context, input))
        }
    }

    fun checkPoint() = Step(stepId, context, prev) { prevResult ->
        val output = action(prevResult)
        if (output.isState) {
            output
        } else {
            context.setOutput(output).fold({ output }, Try.Companion::failure)
        }
    }

    infix fun step(stepId: StepId): Step<T> = Step(stepId, context, prev = this) { prevResult ->
        @Suppress("UNCHECKED_CAST")
        (prevResult as Try<T>) also { context.setInput(it) }
    }

    fun execute(): Try<T> {
        val prevResult: Try<Any> = if (prev != null) {
            val res = prev.execute()
            if (!res.isSuccess) {
                @Suppress("UNCHECKED_CAST")
                return res as Try<T>
            }
            res
        } else {
            Try.success(Unit)
        }

        context.setStep(this.stepId)
        return if (this.stepId == DefaultStep) {
            action(prevResult)
        } else {
            context.execute { action(prevResult) }
        }
    }
}
