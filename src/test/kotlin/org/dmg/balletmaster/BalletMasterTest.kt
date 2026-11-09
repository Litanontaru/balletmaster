package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BalletMasterTest {
    enum class MyStepId : StepId {
        STEP_A,
        STEP_B
    }

    @Test
    fun executeOne() {
        val callContextManager = CallContextManager({ println("replay off") })
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("operation", "11", false, 0)

        val result = balletMaster
            .start()
            .step(MyStepId.STEP_A)
            .input("A")
            .map { _, _ -> "B" }
            .checkPoint()
            .step(MyStepId.STEP_B)
            .map { _, _ -> "C" }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isSuccess)
        assertEquals(result, Try.success("C"))
    }
}
