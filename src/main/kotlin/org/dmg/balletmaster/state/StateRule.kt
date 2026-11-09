package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId

data class StateRule<S : StepId, STATE : Any>(
    val state: STATE,
    val condition: Condition<S>
)
