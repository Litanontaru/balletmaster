package org.dmg.balletmaster.state

import com.fasterxml.jackson.core.type.TypeReference
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.logging.StateLog
import org.dmg.balletmaster.logging.logStoreError
import org.dmg.balletmaster.store.Store
import org.dmg.balletmaster.util.plus

private val VERSIONS_TYPE = object : TypeReference<List<String>>() {}

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
            versions
                .filter { it.startsWith(versionPrefix) }
                .maxWithOrNull(VersionComparator)
                ?.let { get(operationName, it, stepClass, stateClass) }
                ?: Result.success(null)
        }
}

class StoreStateConfigRepository(
    private val store: Store,
    private val serializer: StateConfigSerializer = StateConfigSerializer()
) : StateConfigRepository {

    override fun <S : StepId, STATE : Any> save(config: StateConfig<S, STATE>): Result<Boolean> =
        (saveEntry(
            key = configKey(config.operationName, config.version),
            json = serializer.serialize(config),
            operationName = config.operationName,
            storeOp = "state_config_save"
        ) + { saved ->
            if (saved) registerVersion(config) else Result.success(false)
        }).onSuccess { saved ->
            if (saved) StateLog.configSaved(config.operationName, config.version, config.rules.size)
        }

    private fun registerVersion(config: StateConfig<*, *>): Result<Boolean> =
        getVersions(config.operationName) + { versions ->
            if (config.version in versions) {
                Result.success(true)
            } else {
                saveEntry(
                    key = versionsKey(config.operationName),
                    json = serializer.mapper.writeValueAsString(versions + config.version),
                    operationName = config.operationName,
                    storeOp = "state_versions_save"
                )
            }
        }

    private fun saveEntry(key: String, json: String, operationName: String, storeOp: String): Result<Boolean> =
        store.set(key, json).logStoreError(operationName, storeOp, key)

    override fun <S : StepId, STATE : Any> get(
        operationName: String,
        version: String,
        stepClass: Class<S>,
        stateClass: Class<STATE>
    ): Result<StateConfig<S, STATE>?> =
        configKey(operationName, version).let { key ->
            store.get<String>(key)
                .logStoreError(operationName, "state_config_get", key)
                .map { json -> json?.let { serializer.deserialize(it, stepClass, stateClass) } }
        }

    override fun getVersions(operationName: String): Result<List<String>> =
        versionsKey(operationName).let { key ->
            store.get<String>(key)
                .logStoreError(operationName, "state_versions_get", key)
                .map { json -> json?.let { serializer.mapper.readValue(it, VERSIONS_TYPE) } ?: emptyList() }
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
