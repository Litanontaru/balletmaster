package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class StepExecutionExceptionTest {

    enum class OrderSteps : StepId {
        VALIDATE_ORDER,
        CHARGE_CARD,
        SEND_CONFIRMATION
    }

    @Test
    fun stepExecutionExceptionStoresStepIdAndCause() {
        val rootCause = IllegalArgumentException("Invalid card number")
        val ex = StepExecutionException(OrderSteps.CHARGE_CARD, rootCause)

        assertEquals(OrderSteps.CHARGE_CARD, ex.stepId)
        assertEquals(rootCause, ex.cause)
        assertTrue(ex.message!!.contains("CHARGE_CARD"))
        assertTrue(ex.message!!.contains("Invalid card number"))

        val exSame = StepExecutionException(OrderSteps.CHARGE_CARD, rootCause)
        assertEquals(ex, exSame)
        assertEquals(ex.hashCode(), exSame.hashCode())
    }

    @Test
    fun workflowWrapsMapExceptionInStepExecutionException() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("orderOp", "100", isReplay = false, attempt = 0)

        val step3Executed = AtomicBoolean(false)
        val originalError = IllegalStateException("Insufficient funds")

        val result = balletMaster
            .start()
            .step(OrderSteps.VALIDATE_ORDER)
            .input("order-payload")
            .map { _, input -> "valid-$input" }
            .checkPoint()
            .step(OrderSteps.CHARGE_CARD)
            .map { _, _ -> throw originalError }
            .checkPoint()
            .step(OrderSteps.SEND_CONFIRMATION)
            .map { _, _ ->
                step3Executed.set(true)
                "confirmed"
            }
            .checkPoint()
            .execute()

        callContextManager.clear()

        // 1. Result must be Failure
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)

        // 2. Step 3 must NOT have executed
        assertFalse(step3Executed.get())

        // 3. Exception must be StepExecutionException with CHARGE_CARD
        var caughtStepId: StepId? = null
        var caughtCause: Throwable? = null

        result.fold(
            onFailure = { ex ->
                if (ex is StepExecutionException) {
                    caughtStepId = ex.stepId
                    caughtCause = ex.cause
                }
            },
            onError = {},
            onState = {},
            onSuccess = {}
        )

        assertEquals(OrderSteps.CHARGE_CARD, caughtStepId)
        assertEquals(originalError, caughtCause)

        // 4. Verify Store checkpoints
        val step1Checkpoint = store.get<Try<String>>("orderOp:100:0::VALIDATE_ORDER:output").getOrThrow()
        assertTrue(step1Checkpoint!!.isSuccess)

        val step2Checkpoint = store.get<Try<*>>("orderOp:100:0::CHARGE_CARD:output").getOrThrow()
        assertTrue(step2Checkpoint!!.isFailure)

        val step3Checkpoint = store.get<Try<*>>("orderOp:100:0::SEND_CONFIRMATION:output").getOrThrow()
        assertNull(step3Checkpoint)
    }

    @Test
    fun workflowWrapsGateExceptionInStepExecutionException() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("orderOp", "101", isReplay = false, attempt = 0)

        val gateError = RuntimeException("Gate evaluation timeout")

        val result = balletMaster
            .start()
            .step(OrderSteps.VALIDATE_ORDER)
            .gate { _, _ -> throw gateError }
            .map { _, _ -> "valid" }
            .execute()

        callContextManager.clear()

        assertTrue(result.isFailure)
        result.fold(
            onFailure = { ex ->
                assertTrue(ex is StepExecutionException)
                val stepEx = ex as StepExecutionException
                assertEquals(OrderSteps.VALIDATE_ORDER, stepEx.stepId)
                assertEquals(gateError, stepEx.cause)
            },
            onError = {},
            onState = {},
            onSuccess = {}
        )
    }

    @Test
    fun workflowDoesNotCatchJvmError() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("orderOp", "102", isReplay = false, attempt = 0)

        try {
            assertThrows(AssertionError::class.java) {
                balletMaster
                    .start()
                    .step(OrderSteps.VALIDATE_ORDER)
                    .map { _, _ -> throw AssertionError("JVM fatal error") }
                    .checkPoint()
                    .execute()
            }
        } finally {
            callContextManager.clear()
        }

        assertNull(store.get<Try<*>>("orderOp:102:0::VALIDATE_ORDER:output").getOrThrow())
    }
}
