package org.dmg.balletmaster.store

import java.util.concurrent.ConcurrentHashMap

infix fun Any?.accept(another: Any?) = when (this) {
    null -> another == null
    is Value -> another?.let { this.evaluate(it) } ?: false
    else -> another == this
}

class InMemoryStore : Store {
    private val map = ConcurrentHashMap<String, Any?>()

    override fun set(key: String, valueChange: Pair<Any?, Any?>): Result<Boolean> {
        var result = false
        map.compute(key) { _, current ->
            result = valueChange.first accept current
            if (result) {
                valueChange.second
            } else {
                current
            }
        }
        return Result.success(result)
    }

    override fun set(key: String, value: Any?): Result<Boolean> {
        if (value == null) map.remove(key) else map[key] = value
        return Result.success(true)
    }

    override fun inc(key: String): Result<Boolean> {
        map.merge(key, 1) { a, b -> (a as Int) + (b as Int) }
        return Result.success(true)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: String): Result<T?> = Result.success(map[key] as T?)
}
