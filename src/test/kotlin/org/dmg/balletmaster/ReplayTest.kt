package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContext
import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.context.ContextImpl
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReplayTest {
    enum class StepIds : StepId {
        STEP_A,
        STEP_B,
        STEP_C
    }

    @Test
    fun contextImplDynamicReplayTransition() {
        var call = CallContext("op", "1", isReplay = true, attempt = 1)
        val context = ContextImpl(
            store = InMemoryStore(),
            callProvider = { call },
            off = { call = call.off() }
        )

        // For direct unit testing of ContextImpl, InternalContext is accessible
        assertTrue(context.isReplay())
        context.setPlay()
        assertFalse(context.isReplay())
    }

    @Test
    fun workflowReplayTransitionsToPlayAfterExecution() {
        val replayOffTriggered = AtomicBoolean(false)
        val callContextManager = CallContextManager(
            replayOffListener = { updatedContext ->
                if (!updatedContext.isReplay) {
                    replayOffTriggered.set(true)
                }
            }
        )
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        // Attempt 0: execute STEP_A, checkpointing output "AB"
        callContextManager.store("op", "100", isReplay = false, attempt = 0)
        val stepAExecutions = AtomicInteger(0)

        val resultAttempt0 = balletMaster
            .start()
            .step(StepIds.STEP_A)
            .input("A")
            .map { _, input ->
                stepAExecutions.incrementAndGet()
                input + "B"
            }
            .checkPoint()
            .execute()

        assertTrue(resultAttempt0.isSuccess)
        assertEquals(Try.success("AB"), resultAttempt0)
        assertEquals(1, stepAExecutions.get())

        // Attempt 1: run in Replay mode
        callContextManager.store("op", "100", isReplay = true, attempt = 0)

        val stepBExecutions = AtomicInteger(0)
        val stepCExecutions = AtomicInteger(0)

        assertFalse(replayOffTriggered.get(), "Replay should be active before workflow run")

        val resultAttempt1 = balletMaster
            .start()
            .step(StepIds.STEP_A)
            .input("A")
            .map { _, input ->
                stepAExecutions.incrementAndGet()
                input + "B"
            }
            .checkPoint()
            .step(StepIds.STEP_B)
            .map { _, input ->
                stepBExecutions.incrementAndGet()
                input + "C"
            }
            .checkPoint()
            .step(StepIds.STEP_C)
            .map { _, input ->
                stepCExecutions.incrementAndGet()
                // Inspect through callContextManager from test scope without touching ctx: Context
                assertFalse(callContextManager.get()?.isReplay ?: true)
                input + "D"
            }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(resultAttempt1.isSuccess)
        assertEquals(Try.success("ABCD"), resultAttempt1)

        // STEP_A should not be re-executed (replayed from cache)
        assertEquals(1, stepAExecutions.get(), "STEP_A must be replayed from cache without re-execution")

        // STEP_B and STEP_C executed live
        assertEquals(1, stepBExecutions.get(), "STEP_B must execute live")
        assertEquals(1, stepCExecutions.get(), "STEP_C must execute live")

        // Replay off listener confirmed transition
        assertTrue(replayOffTriggered.get(), "Replay off listener must have been triggered")
    }
}
