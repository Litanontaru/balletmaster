package org.dmg.balletmaster.state

import com.fasterxml.jackson.core.type.TypeReference
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.logging.StateLog
import org.dmg.balletmaster.logging.logStoreError
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
            .logStoreError(config.operationName, "state_config_save", key)
        if (saveConfigResult.isFailure || saveConfigResult.getOrNull() == false) {
            return saveConfigResult
        }

        val currentVersions = getVersions(config.operationName).getOrElse { ex ->
            return Result.failure(ex)
        }
        if (config.version !in currentVersions) {
            val updatedVersions = currentVersions + config.version
            val versionsJson = serializer.mapper.writeValueAsString(updatedVersions)
            val vKey = versionsKey(config.operationName)
            val saveVersionsResult = store.set(vKey, versionsJson)
                .logStoreError(config.operationName, "state_versions_save", vKey)
            if (saveVersionsResult.isFailure || saveVersionsResult.getOrNull() == false) {
                return saveVersionsResult
            }
        }
        StateLog.configSaved(config.operationName, config.version, config.rules.size)
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
            .logStoreError(operationName, "state_config_get", key)
            .map { json ->
                json?.let { serializer.deserialize(it, stepClass, stateClass) }
            }
    }

    override fun getVersions(operationName: String): Result<List<String>> {
        val key = versionsKey(operationName)
        return store.get<String>(key)
            .logStoreError(operationName, "state_versions_get", key)
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
