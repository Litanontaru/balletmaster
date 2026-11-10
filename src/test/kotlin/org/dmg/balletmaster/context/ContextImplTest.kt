package org.dmg.balletmaster.context

import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.dmg.balletmaster.context.TechStatus.BLOCKED
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ContextImplTest {

    enum class Steps : StepId {
        STEP_A
    }

    @Test
    fun staleOwnerDoesNotUnlockLockAcquiredByNewOwner() {
        val store = InMemoryStore()
        val call = CallContext("lockOp", "1", isReplay = false, attempt = 0)
        val lockKey = "lockOp:1:0::STEP_A:timeout"

        val staleContext = ContextImpl(
            store = store,
            callProvider = { call },
            off = {}
        ).apply {
            setStep(Steps.STEP_A)
            setLease(50L)
        }

        val newContext = ContextImpl(
            store = store,
            callProvider = { call },
            off = {}
        ).apply {
            setStep(Steps.STEP_A)
            setLease(1000L)
        }

        val probingContext = ContextImpl(
            store = store,
            callProvider = { call },
            off = {}
        ).apply {
            setStep(Steps.STEP_A)
            setLease(20L)
        }

        val staleEntered = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        val newAcquired = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)

        val staleResult = AtomicReference<Try<String>>()
        val newResult = AtomicReference<Try<String>>()

        val staleThread = Thread {
            staleResult.set(staleContext.execute {
                staleEntered.countDown()
                assertTrue(releaseStale.await(5, TimeUnit.SECONDS))
                Try.success("stale-done")
            })
        }
        staleThread.start()

        assertTrue(staleEntered.await(5, TimeUnit.SECONDS))
        val staleLease = store.get<Long>(lockKey).getOrThrow()
        assertNotNull(staleLease)

        // Wait until the stale thread's lease has strictly expired
        while (System.currentTimeMillis() <= staleLease!!) {
            Thread.sleep(5)
        }

        val newThread = Thread {
            newResult.set(newContext.execute {
                newAcquired.countDown()
                assertTrue(releaseNew.await(5, TimeUnit.SECONDS))
                Try.success("new-done")
            })
        }
        newThread.start()

        assertTrue(newAcquired.await(5, TimeUnit.SECONDS))
        val newLease = store.get<Long>(lockKey).getOrThrow()
        assertNotNull(newLease)
        assertTrue(newLease!! > staleLease)

        // Let the stale thread finish and attempt unlock(staleLease) while newThread still holds the lock
        releaseStale.countDown()
        staleThread.join(5000)
        assertTrue(staleResult.get().isSuccess)

        // The new owner's lock must remain intact in Store and continue blocking other workers
        assertEquals(newLease, store.get<Long>(lockKey).getOrThrow())
        val probeResult = probingContext.execute { Try.success("should-not-run") }
        assertEquals(Try.state<String>(BLOCKED), probeResult)

        // Only when the new owner finishes should its lock be released
        releaseNew.countDown()
        newThread.join(5000)
        assertTrue(newResult.get().isSuccess)
        assertNull(store.get<Long>(lockKey).getOrThrow())
    }

    @Test
    fun releasesLeaseEvenWhenActionThrowsError() {
        val store = InMemoryStore()
        val call = CallContext("errOp", "1", isReplay = false, attempt = 0)
        val lockKey = "errOp:1:0::STEP_A:timeout"

        val context = ContextImpl(store, { call }, {}).apply {
            setStep(Steps.STEP_A)
            setLease(5_000L)
        }

        val thrown = org.junit.jupiter.api.Assertions.assertThrows(AssertionError::class.java) {
            context.execute<String> {
                throw AssertionError("fatal step error")
            }
        }
        assertEquals("fatal step error", thrown.message)
        assertNull(store.get<Long>(lockKey).getOrThrow())

        val nextContext = ContextImpl(store, { call }, {}).apply {
            setStep(Steps.STEP_A)
            setLease(5_000L)
        }
        assertEquals(Try.success("ok"), nextContext.execute { Try.success("ok") })
    }
}
