package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.context.TechStatus
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LeaseTest {

    enum class Steps : StepId {
        STEP_A,
        STEP_B
    }

    @Test
    fun stepLeaseAppliesPerStepWithoutLeakingToNextStep() {
        val callContextManager = CallContextManager()
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("leaseOp", "1", false, 0)

        var stepARemainingLease = 0L
        var stepBRemainingLease = 0L

        val result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .lease(100L)
            .map { _, _ ->
                val leaseUntil = store.get<Long>("leaseOp:1:0::STEP_A:timeout").getOrThrow()!!
                stepARemainingLease = leaseUntil - System.currentTimeMillis()
                "A-done"
            }
            .checkPoint()
            .step(Steps.STEP_B)
            .map { _, input ->
                val leaseUntil = store.get<Long>("leaseOp:1:0::STEP_B:timeout").getOrThrow()!!
                stepBRemainingLease = leaseUntil - System.currentTimeMillis()
                "$input->B-done"
            }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isSuccess)
        assertEquals(Try.success("A-done->B-done"), result)
        assertTrue(stepARemainingLease in 1..100, "Expected STEP_A lease ~100ms, got $stepARemainingLease")
        assertTrue(stepBRemainingLease > 500, "Expected STEP_B default lease ~1000ms, got $stepBRemainingLease")
    }

    @Test
    fun shortLeaseOnStepATimeoutDoesNotLeakIntoStepBContention() {
        val callContextManager = CallContextManager()
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        val stepBEntered = CountDownLatch(1)
        val releaseStepB = CountDownLatch(1)
        val stepBExecutions = AtomicInteger(0)
        val worker1Result = AtomicReference<Try<String>>()
        val worker2Result = AtomicReference<Try<String>>()

        val worker1 = Thread {
            try {
                callContextManager.store("leaseOp", "2", isReplay = false, attempt = 0)
                val result = balletMaster
                    .start()
                    .step(Steps.STEP_A)
                    .lease(40L)
                    .map { _, _ -> "A-done" }
                    .checkPoint()
                    .step(Steps.STEP_B)
                    .map { _, input ->
                        stepBExecutions.incrementAndGet()
                        stepBEntered.countDown()
                        assertTrue(releaseStepB.await(5, TimeUnit.SECONDS))
                        "$input->B-done"
                    }
                    .checkPoint()
                    .execute()
                worker1Result.set(result)
            } finally {
                callContextManager.clear()
            }
        }
        worker1.start()

        assertTrue(stepBEntered.await(5, TimeUnit.SECONDS))

        // Wait longer than STEP_A's 40ms lease, while well within STEP_B's default 1000ms lease
        Thread.sleep(70)

        val worker2 = Thread {
            try {
                callContextManager.store("leaseOp", "2", isReplay = false, attempt = 0)
                val result = balletMaster
                    .start()
                    .step(Steps.STEP_B)
                    .lease(40L)
                    .map { _, _ ->
                        stepBExecutions.incrementAndGet()
                        "B-stolen"
                    }
                    .checkPoint()
                    .execute()
                worker2Result.set(result)
            } finally {
                callContextManager.clear()
            }
        }
        worker2.start()
        worker2.join(5000)

        // STEP_B must still have its 1000ms default lease, so worker2 is blocked rather than stealing at 70ms
        assertEquals(Try.state<String>(TechStatus.BLOCKED), worker2Result.get())
        assertEquals(1, stepBExecutions.get())

        releaseStepB.countDown()
        worker1.join(5000)

        assertEquals(Try.success("A-done->B-done"), worker1Result.get())
    }

    @Test
    fun shortLeaseAllowsTakeoverWhenThatStepStalls() {
        val callContextManager = CallContextManager()
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        val stepAEntered = CountDownLatch(1)
        val releaseWorker1 = CountDownLatch(1)
        val executions = AtomicInteger(0)
        val worker1Result = AtomicReference<Try<String>>()

        val worker1 = Thread {
            try {
                callContextManager.store("leaseOp", "3", isReplay = false, attempt = 0)
                val result = balletMaster
                    .start()
                    .step(Steps.STEP_A)
                    .lease(40L)
                    .map { _, _ ->
                        executions.incrementAndGet()
                        stepAEntered.countDown()
                        assertTrue(releaseWorker1.await(5, TimeUnit.SECONDS))
                        "from-worker1"
                    }
                    .checkPoint()
                    .execute()
                worker1Result.set(result)
            } finally {
                callContextManager.clear()
            }
        }
        worker1.start()

        assertTrue(stepAEntered.await(5, TimeUnit.SECONDS))
        Thread.sleep(60)

        callContextManager.store("leaseOp", "3", isReplay = false, attempt = 0)
        val worker2Result = balletMaster
            .start()
            .step(Steps.STEP_A)
            .lease(500L)
            .map { _, _ ->
                executions.incrementAndGet()
                "from-worker2"
            }
            .execute()
        callContextManager.clear()

        assertEquals(Try.success("from-worker2"), worker2Result)
        assertEquals(2, executions.get())

        releaseWorker1.countDown()
        worker1.join(5000)
        assertTrue(worker1Result.get().isSuccess)
    }

    @Test
    fun nonPositiveLeaseIsRejected() {
        val balletMaster = BalletMaster(InMemoryStore(), CallContextManager())

        assertThrows(IllegalArgumentException::class.java) {
            balletMaster.start().step(Steps.STEP_A).lease(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            balletMaster.start().step(Steps.STEP_A).lease(-10L)
        }
    }
}
