package org.dmg.balletmaster

import org.dmg.balletmaster.TechStatus.SKIP

class Step<T : Any>(
    private val stepId: StepId,
    private val context: InternalContext,
    internal val action: () -> Try<T>,
) {
    infix fun <R : Any> input(value: R) = Step(stepId, context) {
        action() map { context.setInput(value) and { Try.success(value) } }
    }

    infix fun gate(gate: (Context, T) -> Boolean) = Step(stepId, context) {
        action() map { input ->
            context.enterGate() and {
                if (gate(context, input)) {
                    Try.success(input)
                } else {
                    Try.state(SKIP)
                }
            }
        }
    }

    infix fun <R : Any> map(next: (Context, T) -> R) = Step(stepId, context) {
        action() map { input ->
            context.idempotencyGate() and {
                if (it) {
                    fun execute() = try {
                        Try.success(next(context, input))
                    } catch (e: Throwable) {
                        Try.failure(e)
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

    fun checkPoint() = Step(stepId, context) {
        val output = action()
        if (output.isState) {
            output
        } else {
            context.setOutput(output).fold({ output }, Try.Companion::failure)
        }
    }

    infix fun step(stepId: StepId): Step<T> {
        val output = execute()
        return Step(stepId, context) { output also { context.setInput(it) } }
    }

    fun execute(): Try<T> {
        context.setStep(this.stepId)
        return if (this.stepId == DefaultStep) {
            action()
        } else {
            context.execute(action)
        }
    }
}
