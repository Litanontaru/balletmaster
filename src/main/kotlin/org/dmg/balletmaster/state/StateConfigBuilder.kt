package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId

class StateRuleBuilder<S : StepId> {
    fun step(step: S): Condition<S> = StepDone(step)

    fun allOf(vararg steps: S): Condition<S> = AllOf(steps.map { StepDone(it) })
    fun allOf(vararg conditions: Condition<S>): Condition<S> = AllOf(conditions.toList())

    fun anyOf(vararg steps: S): Condition<S> = AnyOf(steps.map { StepDone(it) })
    fun anyOf(vararg conditions: Condition<S>): Condition<S> = AnyOf(conditions.toList())

    fun noneOf(vararg steps: S): Condition<S> = NoneOf(steps.map { StepDone(it) })
    fun noneOf(vararg conditions: Condition<S>): Condition<S> = NoneOf(conditions.toList())

    fun empty(): Condition<S> = IsEmpty()
}

class StateConfigBuilder<S : StepId, STATE : Any>(
    private val operationName: String,
    private val version: String
) {
    private val rules = mutableListOf<StateRule<S, STATE>>()
    private var defaultState: STATE? = null

    fun state(state: STATE, conditionInit: StateRuleBuilder<S>.() -> Condition<S>) {
        val builder = StateRuleBuilder<S>()
        val condition = builder.conditionInit()
        rules.add(StateRule(state, condition))
    }

    fun defaultState(state: STATE) {
        this.defaultState = state
    }

    fun build(): StateConfig<S, STATE> {
        val default = checkNotNull(defaultState) { "defaultState must be specified for StateConfig" }
        val allSteps = rules.flatMap { it.condition.referencedSteps }.toSet()
        val resolvedRules = rules.map { rule ->
            val resolvedCondition = if (rule.condition is IsEmpty) IsEmpty(allSteps) else rule.condition
            StateRule(rule.state, resolvedCondition)
        }
        return StateConfig(operationName, version, resolvedRules, default)
    }
}

fun <S : StepId, STATE : Any> processStates(
    operationName: String,
    version: String,
    init: StateConfigBuilder<S, STATE>.() -> Unit
): StateConfig<S, STATE> {
    val builder = StateConfigBuilder<S, STATE>(operationName, version)
    builder.init()
    return builder.build()
}
