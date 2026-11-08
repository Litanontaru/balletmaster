package org.dmg.balletmaster

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GateTest {

    enum class ForkJoinSteps : StepId {
        STEP_A,
        STEP_B,
        JOIN_STEP
    }

    enum class RaceSteps : StepId {
        TARGET_STEP
    }

    /**
     * Classic Choreography Fork-Join Barrier Test:
     * Two concurrent worker threads process parallel branches (STEP_A and STEP_B).
     * Both workers try to trigger JOIN_STEP upon branch completion.
     * JOIN_STEP is gated on: ctx.isDone(STEP_A) && ctx.isDone(STEP_B).
     *
     * Expected:
     * - The worker that arrives first while the other branch is still running receives TechStatus.SKIP.
     * - The worker that arrives second (when both branches are done) passes the gate and executes JOIN_STEP.
     * - JOIN_STEP business logic executes EXACTLY ONCE.
     * - Both workers incremented the gate counter (gate == 2).
     */
    @Test
    fun concurrentForkJoinChoreography() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster("client", store, callContextManager)

        val joinExecutionCount = AtomicInteger(0)
        val workerAResult = AtomicReference<Try<String>>()
        val workerBResult = AtomicReference<Try<String>>()

        val startLatch = CountDownLatch(1)

        // Worker A thread (faster branch)
        val threadA = Thread {
            try {
                startLatch.await()
                callContextManager.store("forkJoinOp", "100", isReplay = false, attempt = 0)

                // 1. Execute STEP_A (fast: 20ms)
                val stepAResult = balletMaster
                    .start()
                    .step(ForkJoinSteps.STEP_A)
                    .input("dataA")
                    .map { _, input ->
                        Thread.sleep(20)
                        "$input-done"
                    }
                    .checkPoint()
                    .execute()

                assertTrue(stepAResult.isSuccess)

                // 2. Worker A arrives at JOIN_STEP first (while Worker B is still running STEP_B)
                val joinAttempt = balletMaster
                    .start()
                    .step(ForkJoinSteps.JOIN_STEP)
                    .input("fromWorkerA")
                    .gate { ctx, _ -> ctx.isDone(ForkJoinSteps.STEP_A) && ctx.isDone(ForkJoinSteps.STEP_B) }
                    .map { _, _ ->
                        joinExecutionCount.incrementAndGet()
                        "joined-payload"
                    }
                    .checkPoint()
                    .execute()

                workerAResult.set(joinAttempt)
            } finally {
                callContextManager.clear()
            }
        }

        // Worker B thread (slower branch)
        val threadB = Thread {
            try {
                startLatch.await()
                callContextManager.store("forkJoinOp", "100", isReplay = false, attempt = 0)

                // 1. Execute STEP_B (slower: 80ms)
                val stepBResult = balletMaster
                    .start()
                    .step(ForkJoinSteps.STEP_B)
                    .input("dataB")
                    .map { _, input ->
                        Thread.sleep(80)
                        "$input-done"
                    }
                    .checkPoint()
                    .execute()

                assertTrue(stepBResult.isSuccess)

                // 2. Worker B arrives at JOIN_STEP second (both STEP_A and STEP_B are now done!)
                val joinAttempt = balletMaster
                    .start()
                    .step(ForkJoinSteps.JOIN_STEP)
                    .input("fromWorkerB")
                    .gate { ctx, _ -> ctx.isDone(ForkJoinSteps.STEP_A) && ctx.isDone(ForkJoinSteps.STEP_B) }
                    .map { _, _ ->
                        joinExecutionCount.incrementAndGet()
                        "joined-payload"
                    }
                    .checkPoint()
                    .execute()

                workerBResult.set(joinAttempt)
            } finally {
                callContextManager.clear()
            }
        }

        threadA.start()
        threadB.start()

        // Release both threads simultaneously
        startLatch.countDown()

        threadA.join(5000)
        threadB.join(5000)

        assertFalse(threadA.isAlive, "Worker A must finish within 5s")
        assertFalse(threadB.isAlive, "Worker B must finish within 5s")

        // Assertions:
        // 1. Worker A arrived early when STEP_B was not yet finished -> gate skipped execution
        val resA = workerAResult.get()
        assertNotNull(resA)
        assertTrue(resA.isState, "Worker A must receive a TechStatus because gate condition was false")
        assertEquals(Try.state<String>(TechStatus.SKIP), resA)

        // 2. Worker B arrived when both branches were done -> gate passed and executed join
        val resB = workerBResult.get()
        assertNotNull(resB)
        assertTrue(resB.isSuccess, "Worker B must succeed because both steps were done")
        assertEquals(Try.success("joined-payload"), resB)

        // 3. JOIN_STEP was executed EXACTLY ONCE
        assertEquals(1, joinExecutionCount.get(), "Join step must execute exactly once")

        // 4. Both workers passed through gate counter (store.inc was called twice)
        val gateCounter = store.get<Int>("client:forkJoinOp:100:0::JOIN_STEP:gate")
        assertTrue(gateCounter.isSuccess)
        assertEquals(2, gateCounter.getOrThrow(), "Gate counter in store must record both activations")

        // 5. Output in store is properly saved
        val storedJoinOutput = store.get<Try<String>>("client:forkJoinOp:100:0::JOIN_STEP:output")
        assertTrue(storedJoinOutput.isSuccess)
        assertEquals(Try.success("joined-payload"), storedJoinOutput.getOrThrow())
    }

    /**
     * High Concurrency Race Test:
     * 20 parallel threads simultaneously hit the same step with a gate that evaluates to true.
     *
     * Expected:
     * - All 20 threads enter the gate -> gate counter in store is exactly 20.
     * - Lease lock and idempotency gate ensure business map logic executes EXACTLY ONCE.
     * - Exactly 1 thread receives Success.
     * - The other 19 threads receive technical status (BLOCKED or SKIP), meaning processing intentionally did not launch.
     * - Zero exceptions, zero duplicate executions.
     */
    @Test
    fun highConcurrencyMassiveRaceOnGate() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster("client", store, callContextManager)

        val threadCount = 20
        val startLatch = CountDownLatch(1)
        val executions = AtomicInteger(0)
        val results = ConcurrentLinkedQueue<Try<String>>()

        val threads = (0 until threadCount).map {
            Thread {
                try {
                    // Wait for all threads to be created and ready to race
                    startLatch.await()

                    callContextManager.store("raceOp", "200", isReplay = false, attempt = 0)

                    val result = balletMaster
                        .start()
                        .step(RaceSteps.TARGET_STEP)
                        .input("race-input")
                        .gate { _, _ -> true } // Gate condition passes for all
                        .map { _, input ->
                            executions.incrementAndGet()
                            Thread.sleep(10) // Small critical section
                            "$input-winner"
                        }
                        .checkPoint()
                        .execute()

                    results.add(result)
                } finally {
                    callContextManager.clear()
                }
            }
        }

        threads.forEach { it.start() }

        // Release all 20 threads at the exact same moment
        startLatch.countDown()

        threads.forEach { it.join(5000) }
        threads.forEach { assertFalse(it.isAlive, "Thread must finish within 5s") }

        assertEquals(threadCount, results.size)

        // Assertions:
        // 1. Business logic was executed EXACTLY ONCE despite 20 concurrent threads
        assertEquals(1, executions.get(), "Critical section must execute exactly once")

        // 2. Exactly 1 thread got Success
        val successCount = results.count { it.isSuccess }
        assertEquals(1, successCount, "Exactly one thread must succeed")

        // 3. Exactly 19 threads got technical states (BLOCKED or SKIP)
        val stateCount = results.count { it.isState }
        assertEquals(threadCount - 1, stateCount, "All other threads must receive technical status")

        // 4. All 20 threads atomically incremented the gate counter in Store
        val gateCount = store.get<Int>("client:raceOp:200:0::TARGET_STEP:gate")
        assertTrue(gateCount.isSuccess)
        assertEquals(threadCount, gateCount.getOrThrow(), "Store must record 20 gate entries")

        // 5. Checkpoint is properly persisted in Store
        val storedOutput = store.get<Try<String>>("client:raceOp:200:0::TARGET_STEP:output")
        assertTrue(storedOutput.isSuccess)
        assertEquals(Try.success("race-input-winner"), storedOutput.getOrThrow())
    }

    /**
     * Staggered Execution Test:
     * Thread B attempts to run STEP_B gated on STEP_A.
     * STEP_A is not done yet -> Thread B gets SKIP.
     * Thread A completes STEP_A.
     * Thread B retries (simulating message redelivery) -> now passes gate and completes STEP_B.
     */
    @Test
    fun gateBlocksUntilPrerequisiteCompleted() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster("client", store, callContextManager)

        val stepBExecutions = AtomicInteger(0)

        // --- Phase 1: Consumer B receives message before STEP_A has run ---
        callContextManager.store("staggeredOp", "300", isReplay = false, attempt = 0)

        val attempt1 = balletMaster
            .start()
            .step(ForkJoinSteps.STEP_B)
            .gate { ctx, _ -> ctx.isDone(ForkJoinSteps.STEP_A) }
            .map { _, _ ->
                stepBExecutions.incrementAndGet()
                "b-done"
            }
            .checkPoint()
            .execute()

        // STEP_A is not done, so gate returns false -> TechStatus.SKIP
        assertTrue(attempt1.isState)
        assertEquals(Try.state<String>(TechStatus.SKIP), attempt1)
        assertEquals(0, stepBExecutions.get())

        // --- Phase 2: Consumer A runs STEP_A and checkpoints ---
        val stepAResult = balletMaster
            .start()
            .step(ForkJoinSteps.STEP_A)
            .map { _, _ -> "a-done" }
            .checkPoint()
            .execute()

        assertTrue(stepAResult.isSuccess)

        // --- Phase 3: Consumer B redelivers message ---
        val attempt2 = balletMaster
            .start()
            .step(ForkJoinSteps.STEP_B)
            .gate { ctx, _ -> ctx.isDone(ForkJoinSteps.STEP_A) }
            .map { _, _ ->
                stepBExecutions.incrementAndGet()
                "b-done"
            }
            .checkPoint()
            .execute()

        assertTrue(attempt2.isSuccess)
        assertEquals(Try.success("b-done"), attempt2)
        assertEquals(1, stepBExecutions.get())

        // Gate counter recorded 2 entries (phase 1 and phase 3)
        val gateCounter = store.get<Int>("client:staggeredOp:300:0::STEP_B:gate")
        assertEquals(2, gateCounter.getOrThrow())

        callContextManager.clear()
    }
}
