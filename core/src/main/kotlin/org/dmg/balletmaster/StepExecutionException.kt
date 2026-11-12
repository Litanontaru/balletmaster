package org.dmg.balletmaster

class StepExecutionException(
    val stepId: StepId,
    override val cause: Throwable
) : RuntimeException("Step '$stepId' failed with: ${cause.message}", cause) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is StepExecutionException && stepId == other.stepId && cause == other.cause)

    override fun hashCode(): Int = 31 * stepId.hashCode() + cause.hashCode()

    override fun toString(): String = "StepExecutionException(stepId=$stepId, cause=$cause)"
}
