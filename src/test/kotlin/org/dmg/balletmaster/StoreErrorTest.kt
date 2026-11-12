package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.store.InMemoryStore
import org.dmg.balletmaster.store.Store
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class StoreErrorTest {

    enum class Steps : StepId {
        STEP_A,
        STEP_B
    }

    private class FailingStore(
        private val delegate: Store = InMemoryStore(),
        private val failSetPair: (String, Pair<Any?, Any?>) -> Throwable? = { _, _ -> null },
        private val failSetValue: (String, Any?) -> Throwable? = { _, _ -> null },
        private val failGet: (String) -> Throwable? = { null },
    ) : Store {
        override fun set(key: String, valueChange: Pair<Any?, Any?>): Result<Boolean> =
            failSetPair(key, valueChange)?.let(Result.Companion::failure) ?: delegate.set(key, valueChange)

        override fun set(key: String, value: Any?): Result<Boolean> =
            failSetValue(key, value)?.let(Result.Companion::failure) ?: delegate.set(key, value)

        override fun inc(key: String): Result<Boolean> =
            delegate.inc(key)

        override fun <T : Any> get(key: String): Result<T?> =
            failGet(key)?.let(Result.Companion::failure) ?: delegate.get(key)
    }

    @Test
    fun storeFailureOnLockAcquireReturnsErrorImmediately() {
        val storeError = RuntimeException("redis connection refused")
        val store = FailingStore(
            failSetPair = { key, _ -> if (key.endsWith(":timeout")) storeError else null }
        )
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("op", "1", isReplay = false, attempt = 0)

        val result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .input("A")
            .map { _, _ -> fail<String>("STEP_A must not execute when lock acquisition fails") }
            .checkPoint()
            .step(Steps.STEP_B)
            .map { _, _ -> fail<String>("STEP_B must not execute after STEP_A Error") }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isError)
        assertFalse(result.isFailure)
        assertFalse(result.isState)
        assertEquals(Try.error<String>(storeError), result)
    }

    @Test
    fun storeFailureOnSetInputReturnsErrorAndSkipsCheckpointAndNextStep() {
        val storeError = RuntimeException("input write failed")
        val backingStore = InMemoryStore()
        val store = FailingStore(
            delegate = backingStore,
            failSetValue = { key, _ -> if (key.endsWith(":input")) storeError else null }
        )
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("op", "2", isReplay = false, attempt = 0)

        val result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .input("A")
            .map { _, _ -> fail<String>("map must not execute after setInput Error") }
            .checkPoint()
            .step(Steps.STEP_B)
            .map { _, _ -> fail<String>("STEP_B must not execute after STEP_A Error") }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isError)
        assertEquals(Try.error<String>(storeError), result)
        assertNull(backingStore.get<Try<*>>("op:2:0::STEP_A:output").getOrThrow())
        assertNull(backingStore.get<Try<*>>("op:2:0::STEP_B:output").getOrThrow())
    }

    @Test
    fun storeFailureOnIdempotencyGateReturnsError() {
        val storeError = RuntimeException("idempotency gate failed")
        val store = FailingStore(
            failSetPair = { key, _ -> if (key.endsWith(":output")) storeError else null }
        )
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("op", "3", isReplay = false, attempt = 0)

        val result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .input("A")
            .map { _, _ -> fail<String>("map must not execute when idempotencyGate fails") }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isError)
        assertEquals(Try.error<String>(storeError), result)
    }

    @Test
    fun storeFailureOnCheckPointReturnsError() {
        val storeError = RuntimeException("checkpoint write failed")
        val store = FailingStore(
            failSetValue = { key, value -> if (key.endsWith(":output") && value != null) storeError else null }
        )
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("op", "4", isReplay = false, attempt = 0)

        val result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .input("A")
            .map { _, v -> "$v-done" }
            .checkPoint()
            .step(Steps.STEP_B)
            .map { _, _ -> fail<String>("STEP_B must not execute after checkPoint Error") }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isError)
        assertFalse(result.isFailure)
        assertEquals(Try.error<String>(storeError), result)
    }

    @Test
    fun storeFailureOnOriginalOutputDuringReplayReturnsErrorAndKeepsReplay() {
        val storeError = RuntimeException("replay read failed")
        val store = FailingStore(
            failGet = { key -> if (key == "op:5:0::STEP_A:output") storeError else null }
        )
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("op", "5", isReplay = true, attempt = 0)

        val result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .input("A")
            .map { _, _ -> fail<String>("live lambda must not run when replay lookup fails") }
            .checkPoint()
            .execute()

        assertTrue(result.isError)
        assertEquals(Try.error<String>(storeError), result)
        assertTrue(callContextManager.get()?.isReplay == true)

        callContextManager.clear()
    }

    @Test
    fun storeFailureOnTimeoutUnlockReturnsError() {
        val storeError = RuntimeException("unlock failed")
        val store = FailingStore(
            failSetPair = { key, change ->
                if (key.endsWith(":timeout") && change.second == null) storeError else null
            }
        )
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("op", "6", isReplay = false, attempt = 0)

        val result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .input("A")
            .map { _, v -> "$v-done" }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isError)
        assertEquals(Try.error<String>(storeError), result)
    }

    @Test
    fun storeFailureOnIsDoneInsideGateReturnsErrorInsteadOfSkip() {
        val storeError = RuntimeException("isDone read failed")
        val store = FailingStore(
            failGet = { key -> if (key.endsWith("STEP_A:output")) storeError else null }
        )
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("op", "7", isReplay = false, attempt = 0)

        val result = balletMaster
            .start()
            .step(Steps.STEP_B)
            .gate { ctx, _ -> ctx.isDone(Steps.STEP_A) }
            .map { _, _ -> fail<String>("STEP_B must not execute when isDone read fails") }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isError)
        assertFalse(result.isState)
        assertFalse(result.isFailure)
        assertEquals(Try.error<String>(storeError), result)
    }
}
