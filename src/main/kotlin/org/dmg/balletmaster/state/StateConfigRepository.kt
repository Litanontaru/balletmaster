package org.dmg.balletmaster.state

import com.fasterxml.jackson.core.type.TypeReference
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.logging.BalletLoggers
import org.dmg.balletmaster.logging.BalletMdc
import org.dmg.balletmaster.logging.errorEvent
import org.dmg.balletmaster.logging.infoEvent
import org.dmg.balletmaster.store.Store
import org.dmg.balletmaster.util.plus

interface StateConfigRepository {
    fun <S : StepId, STATE : Any> save(config: StateConfig<S, STATE>): Result<Boolean>

    fun <S : StepId, STATE : Any> get(
        operationName: String,
        version: String,
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): Result<StateConfig<S, STATE>?>

    fun getVersions(operationName: String): Result<List<String>>

    fun <S : StepId, STATE : Any> getLatest(
        operationName: String,
        versionPrefix: String = "",
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): Result<StateConfig<S, STATE>?> =
        getVersions(operationName) + { versions ->
            val matchingVersion = versions
                .filter { it.startsWith(versionPrefix) }
                .maxWithOrNull(VersionComparator)

            if (matchingVersion == null) {
                Result.success(null)
            } else {
                get(operationName, matchingVersion, stepClass, stateClass)
            }
        }
}

class StoreStateConfigRepository(
    private val store: Store,
    private val serializer: StateConfigSerializer = StateConfigSerializer()
) : StateConfigRepository {

    override fun <S : StepId, STATE : Any> save(config: StateConfig<S, STATE>): Result<Boolean> {
        val json = serializer.serialize(config)
        val key = configKey(config.operationName, config.version)
        val saveConfigResult = store.set(key, json)
            .onFailure { ex ->
                BalletLoggers.store.errorEvent("store.error", ex) {
                    attr(BalletMdc.OPERATION, config.operationName)
                    attr("storeOp", "state_config_save")
                    attr("key", key)
                }
            }
        if (saveConfigResult.isFailure || saveConfigResult.getOrNull() == false) {
            return saveConfigResult
        }

        val currentVersions = getVersions(config.operationName).fold({ it }, { emptyList() })
        if (config.version !in currentVersions) {
            val updatedVersions = currentVersions + config.version
            val versionsJson = serializer.mapper.writeValueAsString(updatedVersions)
            val vKey = versionsKey(config.operationName)
            store.set(vKey, versionsJson)
                .onFailure { ex ->
                    BalletLoggers.store.errorEvent("store.error", ex) {
                        attr(BalletMdc.OPERATION, config.operationName)
                        attr("storeOp", "state_versions_save")
                        attr("key", vKey)
                    }
                }
        }
        BalletLoggers.state.infoEvent("state.config_saved") {
            attr(BalletMdc.OPERATION, config.operationName)
            attr("version", config.version)
            attr("rulesCount", config.rules.size)
        }
        return Result.success(true)
    }

    override fun <S : StepId, STATE : Any> get(
        operationName: String,
        version: String,
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): Result<StateConfig<S, STATE>?> {
        val key = configKey(operationName, version)
        return store.get<String>(key)
            .onFailure { ex ->
                BalletLoggers.store.errorEvent("store.error", ex) {
                    attr(BalletMdc.OPERATION, operationName)
                    attr("storeOp", "state_config_get")
                    attr("key", key)
                }
            }
            .map { json ->
                json?.let { serializer.deserialize(it, stepClass, stateClass) }
            }
    }

    override fun getVersions(operationName: String): Result<List<String>> {
        val key = versionsKey(operationName)
        return store.get<String>(key)
            .onFailure { ex ->
                BalletLoggers.store.errorEvent("store.error", ex) {
                    attr(BalletMdc.OPERATION, operationName)
                    attr("storeOp", "state_versions_get")
                    attr("key", key)
                }
            }
            .map { json ->
                if (json == null) emptyList()
                else serializer.mapper.readValue(json, object : TypeReference<List<String>>() {})
            }
    }

    private fun configKey(operationName: String, version: String) =
        "config:${operationName}:${version}"

    private fun versionsKey(operationName: String) =
        "config:${operationName}:versions"
}

inline fun <reified S : StepId, reified STATE : Any> StateConfigRepository.get(
    operationName: String,
    version: String
): Result<StateConfig<S, STATE>?> = get(operationName, version, S::class.java, STATE::class.java)

inline fun <reified S : StepId, reified STATE : Any> StateConfigRepository.getLatest(
    operationName: String,
    versionPrefix: String = ""
): Result<StateConfig<S, STATE>?> = getLatest(operationName, versionPrefix, S::class.java, STATE::class.java)
