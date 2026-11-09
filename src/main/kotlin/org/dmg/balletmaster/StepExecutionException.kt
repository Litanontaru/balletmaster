package org.dmg.balletmaster

class StepExecutionException(
    val stepId: StepId,
    override val cause: Throwable
) : RuntimeException("Step '$stepId' failed with: ${cause.message}", cause) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StepExecutionException) return false
        return stepId == other.stepId && cause == other.cause
    }

    override fun hashCode(): Int = 31 * stepId.hashCode() + cause.hashCode()

    override fun toString(): String = "StepExecutionException(stepId=$stepId, cause=$cause)"
}
