package org.dmg.balletmaster.store

import org.dmg.balletmaster.context.StepStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ValueTest {
    @Test
    fun lessThenEvaluate() {
        val threshold = 1500L
        val predicate = lessThen(threshold)

        // Expired timestamp (in the past): another < original -> should be true
        assertTrue(predicate.evaluate(1000L))

        // Active timestamp (in the future): another > original -> should be false
        assertFalse(predicate.evaluate(2000L))

        // Exact boundary: another == original -> should be false
        assertFalse(predicate.evaluate(1500L))

        // Incompatible type -> should be false
        assertFalse(predicate.evaluate("not a long"))
    }

    @Test
    fun lessThenAccept() {
        val predicate = lessThen(100)

        assertTrue(predicate accept 50)
        assertFalse(predicate accept 150)
        assertFalse(predicate accept 100)
        assertFalse(predicate accept null)
    }

    @Test
    fun storeTimeoutLeaseStealing() {
        val store = InMemoryStore()
        val key = "lock:timeout"
        val now = 10_000L

        // Initial state: no lock present (null), acquired by worker 1
        val acquireResult = store.set(key, null to (now + 1000L))
        assertTrue(acquireResult.isSuccess && acquireResult.getOrThrow())

        // Worker 2 attempts to steal active lease (lease is 11_000L, current time is 10_500L) -> must fail
        val currentActiveTime = 10_500L
        val stealActiveResult = store.set(key, lessThen(currentActiveTime) to (currentActiveTime + 1000L))
        assertTrue(stealActiveResult.isSuccess)
        assertFalse(stealActiveResult.getOrThrow())

        // Time passes, lease expires (current time is 12_000L, lease was 11_000L) -> stealing must succeed
        val expiredTime = 12_000L
        val stealExpiredResult = store.set(key, lessThen(expiredTime) to (expiredTime + 1000L))
        assertTrue(stealExpiredResult.isSuccess)
        assertTrue(stealExpiredResult.getOrThrow())

        // Value in store should now be updated to worker 2's lease
        val updatedLease = store.get<Long>(key).getOrThrow()
        assertEquals(expiredTime + 1000L, updatedLease)
    }

    @Test
    fun notEvaluateWithValue() {
        val notDone = !StepStatus.DONE

        // Matches values that are not StepStatus.DONE
        assertTrue(notDone.evaluate(StepStatus.EXCEPTION))
        assertTrue(notDone.evaluate("SOME_OTHER_STATUS"))

        // Does not match StepStatus.DONE
        assertFalse(notDone.evaluate(StepStatus.DONE))
    }

    @Test
    fun notEvaluateWithRawAny() {
        val notString = Not("ACTIVE")

        assertTrue(notString.evaluate("INACTIVE"))
        assertFalse(notString.evaluate("ACTIVE"))
    }

    @Test
    fun notWithLessThen() {
        // Not less than 100 means >= 100
        val notLessThan100 = !lessThen(100)

        assertTrue(notLessThan100.evaluate(150))
        assertTrue(notLessThan100.evaluate(100))
        assertFalse(notLessThan100.evaluate(50))
    }

    @Test
    fun notEqualsAndHashCode() {
        val notDone1 = !StepStatus.DONE
        val notDone2 = Not(StepStatus.DONE)
        val notException = !StepStatus.EXCEPTION

        assertEquals(notDone1, notDone2)
        assertEquals(notDone1.hashCode(), notDone2.hashCode())
        assertNotEquals(notDone1, notException)
        assertEquals("!DONE", notDone1.toString())
    }

    @Test
    fun notInStoreAccept() {
        val notDone = !StepStatus.DONE

        assertTrue(notDone accept StepStatus.EXCEPTION)
        assertFalse(notDone accept StepStatus.DONE)
        assertFalse(notDone accept null)
    }
}
