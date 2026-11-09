package org.dmg.balletmaster.state

import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Store

interface StateConfigRepository {
    fun <S : StepId, STATE : Any> save(config: StateConfig<S, STATE>): Result<Boolean>

    fun <S : StepId, STATE : Any> get(
        operationName: String,
        version: String,
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): Result<StateConfig<S, STATE>?>
}

class StoreStateConfigRepository(
    private val clientId: Any,
    private val store: Store,
    private val serializer: StateConfigSerializer = StateConfigSerializer()
) : StateConfigRepository {

    override fun <S : StepId, STATE : Any> save(config: StateConfig<S, STATE>): Result<Boolean> {
        val json = serializer.serialize(config)
        return store.set(configKey(config.operationName, config.version), json)
    }

    override fun <S : StepId, STATE : Any> get(
        operationName: String,
        version: String,
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): Result<StateConfig<S, STATE>?> {
        val key = configKey(operationName, version)
        return store.get<String>(key).map { json ->
            json?.let { serializer.deserialize(it, stepClass, stateClass) }
        }
    }

    private fun configKey(operationName: String, version: String) =
        "${clientId}:config:${operationName}:${version}"
}

inline fun <reified S : StepId, reified STATE : Any> StateConfigRepository.get(
    operationName: String,
    version: String
): Result<StateConfig<S, STATE>?> = get(operationName, version, S::class.java, STATE::class.java)
