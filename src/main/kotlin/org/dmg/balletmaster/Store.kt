package org.dmg.balletmaster

interface Store {
    fun set(key: String, valueChange: Pair<Any, Any>): Result<Boolean>
    fun set(key: String, value: Any): Result<Boolean>
    fun inc(key: String): Result<Boolean>
    fun <T : Any> get(key: String): Result<T?>
}