package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Store
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.plus

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
    ): Result<STATE> {
        return repository.getLatest(operationName, versionPrefix, stepClass, stateClass) + { config ->
            if (config == null) {
                Result.failure(NoSuchElementException("StateConfig not found for operation='$operationName' with versionPrefix='$versionPrefix'"))
            } else {
                val state = config.evaluate { stepId ->
                    val key = "${operationName}:${operationId}:${attempt}:${suffix}:${stepId}:output"
                    store.get<Try<*>>(key).fold({ it?.isSuccess == true }, { false })
                }
                Result.success(state)
            }
        }
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
