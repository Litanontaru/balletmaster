package org.dmg.balletmaster.store

import org.dmg.balletmaster.BalletMaster
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.context.CallContextManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class StoreTest {
    enum class StepIds : StepId {
        NOTIFY_STEP
    }

    @Test
    fun nullAcceptance() {
        // null matches null (absence of value)
        assertTrue(null accept null)
        assertFalse(null accept "present")
        assertFalse(null accept Unit)

        // Unit now matches Unit as a normal value, not null!
        assertTrue(Unit accept Unit)
        assertFalse(Unit accept null)
        assertFalse(Unit accept "something")
    }

    @Test
    fun storeSavesAndRetrievesUnit() {
        val store = InMemoryStore()
        val key = "side-effect-result"

        // Setting Unit must store it, not delete the key
        val setResult = store.set(key, Unit)
        assertTrue(setResult.isSuccess && setResult.getOrThrow())

        // Getting Unit returns Unit, not null
        val getResult = store.get<Unit>(key)
        assertTrue(getResult.isSuccess)
        assertEquals(Unit, getResult.getOrThrow())
    }

    @Test
    fun storeDeletesKeyWhenPassingNull() {
        val store = InMemoryStore()
        val key = "temp-key"

        store.set(key, "hello")
        assertEquals("hello", store.get<String>(key).getOrThrow())

        // Passing null unconditionally deletes the key
        val deleteResult = store.set(key, null)
        assertTrue(deleteResult.isSuccess && deleteResult.getOrThrow())

        assertNull(store.get<String>(key).getOrThrow())
    }

    @Test
    fun conditionalCasWithNull() {
        val store = InMemoryStore()
        val key = "cas-key"

        // null to "first": insert if absent -> should succeed
        val insertResult = store.set(key, null to "first")
        assertTrue(insertResult.isSuccess && insertResult.getOrThrow())
        assertEquals("first", store.get<String>(key).getOrThrow())

        // null to "second": insert if absent -> should fail because key exists
        val failInsertResult = store.set(key, null to "second")
        assertTrue(failInsertResult.isSuccess)
        assertFalse(failInsertResult.getOrThrow())
        assertEquals("first", store.get<String>(key).getOrThrow())

        // "first" to null: delete if matches -> should succeed
        val deleteResult = store.set(key, "first" to null)
        assertTrue(deleteResult.isSuccess && deleteResult.getOrThrow())
        assertNull(store.get<String>(key).getOrThrow())
    }

    @Test
    fun workflowStepReturningUnitCheckpointsAndReplaysWithoutCrash() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager {}
        val balletMaster = BalletMaster(store, callContextManager)
        val stepExecutionCount = AtomicInteger(0)

        // Attempt 0: execute a side-effect step returning Unit
        callContextManager.store("op", "200", isReplay = false, attempt = 0)

        val resultAttempt0 = balletMaster
            .start()
            .step(StepIds.NOTIFY_STEP)
            .input("event-payload")
            .map { _, _ ->
                stepExecutionCount.incrementAndGet()
                // returns Unit (e.g. side-effect like sending a message)
                Unit
            }
            .checkPoint()
            .execute()

        assertTrue(resultAttempt0.isSuccess)
        assertEquals(Try.success(Unit), resultAttempt0)
        assertEquals(1, stepExecutionCount.get())

        // Verify Unit output is actually stored under the checkpoint key as Try
        val storedOutput = store.get<Try<Unit>>("op:200:0::NOTIFY_STEP:output")
        assertTrue(storedOutput.isSuccess)
        assertEquals(Try.success(Unit), storedOutput.getOrThrow())

        // Attempt 1: Replay the workflow.
        // It should read the cached Unit output without throwing NullPointerException!
        callContextManager.store("op", "200", isReplay = true, attempt = 0)

        val resultAttempt1 = balletMaster
            .start()
            .step(StepIds.NOTIFY_STEP)
            .input("event-payload")
            .map { _, _ ->
                stepExecutionCount.incrementAndGet()
                Unit
            }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(resultAttempt1.isSuccess)
        assertEquals(Try.success(Unit), resultAttempt1)

        // Step was replayed from store, so lambda was NOT executed again
        assertEquals(1, stepExecutionCount.get())
    }
}
