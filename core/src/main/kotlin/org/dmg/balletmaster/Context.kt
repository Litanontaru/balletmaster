package org.dmg.balletmaster

interface Context {
    fun isDone(stepId: StepId): Boolean
    val operationSuffix: Any
    val suffix: Any get() = operationSuffix
}
