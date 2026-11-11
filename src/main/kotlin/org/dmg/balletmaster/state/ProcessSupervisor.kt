package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.logging.BalletLoggers
import org.dmg.balletmaster.logging.BalletMdc
import org.dmg.balletmaster.logging.debugEvent
import org.dmg.balletmaster.logging.errorEvent
import org.dmg.balletmaster.logging.safeStateLabel
import org.dmg.balletmaster.logging.warnEvent
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
    ): Result<STATE> {
        return repository.getLatest(operationName, versionPrefix, stepClass, stateClass) + { config ->
            if (config == null) {
                BalletLoggers.state.warnEvent("state.config_not_found") {
                    attr(BalletMdc.OPERATION, operationName)
                    attr("versionPrefix", versionPrefix)
                }
                Result.failure(NoSuchElementException("StateConfig not found for operation='$operationName' with versionPrefix='$versionPrefix'"))
            } else {
                try {
                    val state = config.evaluate { stepId ->
                        val key = "${operationName}:${operationId}:${attempt}:${suffix}:${stepId}:output"
                        store.get<Try<*>>(key)
                            .onFailure { ex ->
                                BalletLoggers.store.errorEvent("store.error", ex) {
                                    attr(BalletMdc.OPERATION, operationName)
                                    attr(BalletMdc.OPERATION_ID, operationId)
                                    attr(BalletMdc.ATTEMPT, attempt)
                                    attr(BalletMdc.SUFFIX, suffix)
                                    attr(BalletMdc.STEP, stepId)
                                    attr("storeOp", "supervisor_step_check")
                                    attr("key", key)
                                }
                            }
                            .fold(
                                onSuccess = { it?.isSuccess == true },
                                onFailure = { throw SupervisorStoreReadException(it) }
                            )
                    }
                    BalletLoggers.state.debugEvent("state.resolved") {
                        attr(BalletMdc.OPERATION, operationName)
                        attr(BalletMdc.OPERATION_ID, operationId)
                        attr(BalletMdc.ATTEMPT, attempt)
                        attr(BalletMdc.SUFFIX, suffix)
                        attr("version", config.version)
                        attr("versionPrefix", versionPrefix)
                        attr("state", safeStateLabel(state))
                    }
                    Result.success(state)
                } catch (e: SupervisorStoreReadException) {
                    Result.failure(e.cause)
                }
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
