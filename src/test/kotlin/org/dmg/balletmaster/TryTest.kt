package org.dmg.balletmaster

import org.dmg.balletmaster.context.TechStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TryTest {
    @Test
    fun stateEquality() {
        val state1 = Try.state<String>(TechStatus.SKIP)
        val state2 = Try.state<String>(TechStatus.SKIP)
        val state3 = Try.state<String>(TechStatus.BLOCKED)

        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())
        assertNotEquals(state1, state3)
        assertTrue(state1.isState)
        assertFalse(state1.isSuccess)
        assertFalse(state1.isFailure)
        assertFalse(state1.isError)
    }

    @Test
    fun successEquality() {
        val success1 = Try.success("test")
        val success2 = Try.success("test")
        val success3 = Try.success("other")

        assertEquals(success1, success2)
        assertEquals(success1.hashCode(), success2.hashCode())
        assertNotEquals(success1, success3)
        assertTrue(success1.isSuccess)
        assertFalse(success1.isState)
        assertFalse(success1.isFailure)
        assertFalse(success1.isError)
    }

    @Test
    fun failureEquality() {
        val exception = RuntimeException("error")
        val failure1 = Try.failure<String>(exception)
        val failure2 = Try.failure<String>(exception)

        assertEquals(failure1, failure2)
        assertEquals(failure1.hashCode(), failure2.hashCode())
        assertNotEquals(failure1, Try.failure<String>(RuntimeException("other")))
        assertTrue(failure1.isFailure)
        assertFalse(failure1.isSuccess)
        assertFalse(failure1.isState)
        assertFalse(failure1.isError)
    }

    @Test
    fun errorEquality() {
        val exception = RuntimeException("infra error")
        val error1 = Try.error<String>(exception)
        val error2 = Try.error<String>(exception)

        assertEquals(error1, error2)
        assertEquals(error1.hashCode(), error2.hashCode())
        assertNotEquals(error1, Try.error<String>(RuntimeException("other")))
        assertNotEquals(error1, Try.failure<String>(exception))
        assertTrue(error1.isError)
        assertFalse(error1.isSuccess)
        assertFalse(error1.isFailure)
        assertFalse(error1.isState)
    }

    @Test
    fun foldAndCombinatorsPreserveError() {
        val exception = IllegalStateException("store down")
        val error = Try.error<String>(exception)

        val folded = error.fold(
            onFailure = { "failure" },
            onError = { "error:${it.message}" },
            onState = { "state" },
            onSuccess = { "success" }
        )
        assertEquals("error:store down", folded)

        var invoked = false
        val afterThen = error.then {
            invoked = true
            Try.success("ok")
        }
        val afterAlso = error also { invoked = true }
        val afterMap = error map {
            invoked = true
            Try.success("ok")
        }
        val afterAnd = error and {
            invoked = true
            Result.success("ok")
        }

        assertFalse(invoked)
        assertEquals(error, afterThen)
        assertEquals(error, afterAlso)
        assertEquals(error, afterMap)
        assertEquals(error, afterAnd)
        assertTrue(afterThen.isError)
        assertTrue(afterAlso.isError)
        assertTrue(afterMap.isError)
        assertTrue(afterAnd.isError)
    }

    @Test
    fun tryAndConvertsResultFailureToError() {
        val storeEx = IllegalStateException("redis unreachable")
        val res = Try.success("ok") and { Result.failure<String>(storeEx) }

        assertTrue(res.isError)
        assertFalse(res.isFailure)
        assertEquals(Try.error<String>(storeEx), res)
    }
}
