package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId

class StateRuleBuilder<S : StepId> {
    fun step(step: S): Condition<S> = StepDone(step)

    fun allOf(vararg steps: S): Condition<S> = AllOf(steps.map(::StepDone))
    fun allOf(vararg conditions: Condition<S>): Condition<S> = AllOf(conditions.toList())

    fun anyOf(vararg steps: S): Condition<S> = AnyOf(steps.map(::StepDone))
    fun anyOf(vararg conditions: Condition<S>): Condition<S> = AnyOf(conditions.toList())

    fun noneOf(vararg steps: S): Condition<S> = NoneOf(steps.map(::StepDone))
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
        rules += StateRule(state, StateRuleBuilder<S>().conditionInit())
    }

    fun defaultState(state: STATE) {
        this.defaultState = state
    }

    fun build(): StateConfig<S, STATE> = StateConfig(
        operationName = operationName,
        version = version,
        rules = resolveEmptyConditions(rules),
        defaultState = checkNotNull(defaultState) { "defaultState must be specified for StateConfig" }
    )
}

fun <S : StepId, STATE : Any> processStates(
    operationName: String,
    version: String,
    init: StateConfigBuilder<S, STATE>.() -> Unit
): StateConfig<S, STATE> = StateConfigBuilder<S, STATE>(operationName, version).apply(init).build()

internal fun <S : StepId, STATE : Any> resolveEmptyConditions(
    rules: List<StateRule<S, STATE>>
): List<StateRule<S, STATE>> =
    if (rules.none { it.condition is IsEmpty }) rules
    else rules.flatMap { it.condition.referencedSteps }.toSet().let { allSteps ->
        rules.map { if (it.condition is IsEmpty) it.copy(condition = IsEmpty(allSteps)) else it }
    }
