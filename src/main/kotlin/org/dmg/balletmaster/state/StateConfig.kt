package org.dmg.balletmaster.state

import org.dmg.balletmaster.Context
import org.dmg.balletmaster.StepId

data class StateConfig<S : StepId, STATE : Any>(
    val operationName: String,
    val version: String,
    val rules: List<StateRule<S, STATE>>,
    val defaultState: STATE
) {
    val allReferencedSteps: Set<S> by lazy {
        rules.flatMap { it.condition.referencedSteps }.toSet()
    }

    fun evaluate(isDone: (S) -> Boolean): STATE {
        for (rule in rules) {
            if (rule.condition.evaluate(isDone)) {
                return rule.state
            }
        }
        return defaultState
    }

    fun evaluate(context: Context): STATE = evaluate { step -> context.isDone(step) }
}
