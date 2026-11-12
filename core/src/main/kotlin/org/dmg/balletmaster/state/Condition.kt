package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId

sealed interface Condition<S : StepId> {
    fun evaluate(isDone: (S) -> Boolean): Boolean
    val referencedSteps: Set<S>
}

data class StepDone<S : StepId>(val step: S) : Condition<S> {
    override fun evaluate(isDone: (S) -> Boolean): Boolean = isDone(step)
    override val referencedSteps: Set<S> get() = setOf(step)
}

data class AllOf<S : StepId>(val conditions: List<Condition<S>>) : Condition<S> {
    override fun evaluate(isDone: (S) -> Boolean): Boolean = conditions.all { it.evaluate(isDone) }
    override val referencedSteps: Set<S> get() = conditions.referencedSteps()
}

data class AnyOf<S : StepId>(val conditions: List<Condition<S>>) : Condition<S> {
    override fun evaluate(isDone: (S) -> Boolean): Boolean = conditions.any { it.evaluate(isDone) }
    override val referencedSteps: Set<S> get() = conditions.referencedSteps()
}

data class NoneOf<S : StepId>(val conditions: List<Condition<S>>) : Condition<S> {
    override fun evaluate(isDone: (S) -> Boolean): Boolean = conditions.none { it.evaluate(isDone) }
    override val referencedSteps: Set<S> get() = conditions.referencedSteps()
}

data class IsEmpty<S : StepId>(val expectedSteps: Set<S> = emptySet()) : Condition<S> {
    override fun evaluate(isDone: (S) -> Boolean): Boolean = expectedSteps.none(isDone)
    override val referencedSteps: Set<S> get() = expectedSteps
}

private fun <S : StepId> List<Condition<S>>.referencedSteps(): Set<S> =
    flatMapTo(mutableSetOf()) { it.referencedSteps }
