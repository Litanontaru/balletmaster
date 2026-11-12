package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.logging.StateLog
import org.dmg.balletmaster.logging.logStoreError
import org.dmg.balletmaster.store.Store
import org.dmg.balletmaster.util.plus

private class SupervisorStoreReadException(override val cause: Throwable) : RuntimeException(cause)

/**
 * Supervisor component responsible for inspecting, observing, and resolving
 * the current business state of a process instance based on stored execution facts
 * and versioned StateConfig rules.
 */
class ProcessSupervisor(
    private val store: Store,
    private val repository: StateConfigRepository = StoreStateConfigRepository(store)
) {
    /**
     * Resolves the current state of a process instance.
     *
     * @param operationName logical operation name (e.g., "order-flow")
     * @param operationId unique process instance identifier (e.g., "ord-123")
     * @param versionPrefix version mask / prefix (e.g., "1." for latest 1.x, or "" for latest overall)
     * @param stepClass class of the StepId type
     * @param stateClass class of the STATE type
     * @param attempt attempt number (default 0)
     * @param suffix execution suffix / milestone scope (default empty string)
     * @return [Result.success] with the resolved [STATE], or [Result.failure] if config not found or storage error occurs.
     */
    fun <S : StepId, STATE : Any> resolveState(
        operationName: String,
        operationId: String,
        versionPrefix: String = "",
        stepClass: Class<S>,
        stateClass: Class<STATE>,
        attempt: Int = 0,
        suffix: Any = ""
    ): Result<STATE> =
        repository.getLatest(operationName, versionPrefix, stepClass, stateClass) + { config ->
            config?.let { resolveWithConfig(operationName, operationId, versionPrefix, attempt, suffix, it) }
                ?: run {
                    StateLog.configNotFound(operationName, versionPrefix)
                    Result.failure(NoSuchElementException("StateConfig not found for operation='$operationName' with versionPrefix='$versionPrefix'"))
                }
        }

    private fun <S : StepId, STATE : Any> resolveWithConfig(
        operationName: String,
        operationId: String,
        versionPrefix: String,
        attempt: Int,
        suffix: Any,
        config: StateConfig<S, STATE>
    ): Result<STATE> = try {
        val state = config.evaluate { isStepCompleted(operationName, operationId, attempt, suffix, it) }
        Result.success(state).also {
            StateLog.resolved(operationName, operationId, attempt, suffix, config.version, versionPrefix, state)
        }
    } catch (e: SupervisorStoreReadException) {
        Result.failure(e.cause)
    }

    private fun isStepCompleted(
        operationName: String,
        operationId: String,
        attempt: Int,
        suffix: Any,
        stepId: StepId
    ): Boolean = "$operationName:$operationId:$attempt:$suffix:$stepId:output".let { key ->
        store.get<Try<*>>(key)
            .logStoreError(operationName, operationId, attempt, suffix, stepId, "supervisor_step_check", key)
            .getOrElse { throw SupervisorStoreReadException(it) }
            ?.isSuccess == true
    }
}

inline fun <reified S : StepId, reified STATE : Any> ProcessSupervisor.resolveState(
    operationName: String,
    operationId: String,
    versionPrefix: String = "",
    attempt: Int = 0,
    suffix: Any = ""
): Result<STATE> = resolveState(
    operationName = operationName,
    operationId = operationId,
    versionPrefix = versionPrefix,
    stepClass = S::class.java,
    stateClass = STATE::class.java,
    attempt = attempt,
    suffix = suffix
)
