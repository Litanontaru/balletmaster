package org.dmg.balletmaster

/**
 * Key-value storage abstraction providing atomic operations, conditional updates,
 * and state persistence for workflow steps and leases.
 */
interface Store {
    /**
     * Atomically updates the value associated with [key] if the current value matches the condition.
     *
     * @param key the storage key to update.
     * @param valueChange a pair of `(expectedCondition to newValue)`:
     *   - `first`: the expectation predicate. If [Unit], expects the key to be absent (`null`).
     *     If [Value], invokes [Value.evaluate]. Otherwise, performs equality check (`current == expected`).
     *   - `second`: the new value to store if the predicate matches. If [Unit], removes the key.
     * @return [Result.success] with `true` if condition matched and value was updated;
     *   `false` if condition did not match; or [Result.failure] on storage error.
     */
    fun set(key: String, valueChange: Pair<Any, Any>): Result<Boolean>

    /**
     * Unconditionally sets [value] under [key].
     *
     * @param key the storage key.
     * @param value the value to store. Note that in current implementations, [Unit] acts as a deletion signal.
     * @return [Result.success] with `true`, or [Result.failure] on storage error.
     */
    fun set(key: String, value: Any): Result<Boolean>

    /**
     * Atomically increments an integer counter associated with [key].
     * If the key does not exist, it is initialized to 1.
     *
     * @param key the storage key to increment.
     * @return [Result.success] with `true`, or [Result.failure] on storage error.
     */
    fun inc(key: String): Result<Boolean>

    /**
     * Retrieves the value associated with [key].
     *
     * @param key the storage key to lookup.
     * @return [Result.success] with the value (or `null` if key does not exist),
     *   or [Result.failure] on storage error.
     */
    fun <T : Any> get(key: String): Result<T?>
}