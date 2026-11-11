package org.dmg.balletmaster

import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.context.TechStatus
import org.dmg.balletmaster.state.StateConfig
import org.dmg.balletmaster.state.StateConfigRepository
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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

    @Test
    fun multiStepSavesPreviousOutputAsNextStepInput() {
        val callContextManager = CallContextManager()
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("operation", "12", false, 0)

        val result = balletMaster
            .start()
            .step(MyStepId.STEP_A)
            .input("A")
            .map { _, value -> "$value->B" }
            .checkPoint()
            .step(MyStepId.STEP_B)
            .map { _, value -> "$value->C" }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isSuccess)
        assertEquals(Try.success("A->B->C"), result)
        assertEquals("A->B", store.get<String>("operation:12:0::STEP_B:input").getOrThrow())
        assertEquals(Try.success("A->B->C"), store.get<Try<String>>("operation:12:0::STEP_B:output").getOrThrow())
    }

    @Test
    fun multiStepShortCircuitsOnGateSkip() {
        val callContextManager = CallContextManager()
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("operation", "13", false, 0)

        val result = balletMaster
            .start()
            .step(MyStepId.STEP_A)
            .input("A")
            .gate { _, _ -> false }
            .map { _, _ -> fail<String>("STEP_A map must not execute when gate returns false") }
            .checkPoint()
            .step(MyStepId.STEP_B)
            .map { _, _ -> fail<String>("STEP_B must not execute after STEP_A SKIP") }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isState)
        assertEquals(Try.state<String>(TechStatus.SKIP), result)
        assertNull(store.get<Try<*>>("operation:13:0::STEP_A:output").getOrThrow())
        assertNull(store.get<Any>("operation:13:0::STEP_B:input").getOrThrow())
        assertNull(store.get<Try<*>>("operation:13:0::STEP_B:output").getOrThrow())
    }

    enum class MyState {
        DONE,
        TODO
    }

    private fun stubRepository(saveResult: Result<Boolean>): StateConfigRepository =
        object : StateConfigRepository {
            override fun <S : StepId, STATE : Any> save(config: StateConfig<S, STATE>): Result<Boolean> =
                saveResult

            override fun <S : StepId, STATE : Any> get(
                operationName: String,
                version: String,
                stepClass: Class<S>,
                stateClass: Class<STATE>
            ): Result<StateConfig<S, STATE>?> = Result.success(null)

            override fun getVersions(operationName: String): Result<List<String>> =
                Result.success(emptyList())
        }

    @Test
    fun executeWithoutCallContextReturnsNoCall() {
        val callContextManager = CallContextManager()
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, callContextManager)

        val result = balletMaster
            .start()
            .step(MyStepId.STEP_A)
            .input("A")
            .map { _, _ -> fail<String>("Step must not execute without CallContext") }
            .checkPoint()
            .step(MyStepId.STEP_B)
            .map { _, _ -> fail<String>("Second step must not execute without CallContext") }
            .checkPoint()
            .execute()

        assertTrue(result.isState)
        assertEquals(Try.state<String>(TechStatus.NO_CALL), result)
    }

    @Test
    fun defineStatesReturnsConfigWhenRepositorySaveSucceeds() {
        val balletMaster = BalletMaster(
            store = InMemoryStore(),
            callContextManager = CallContextManager(),
            stateConfigRepository = stubRepository(Result.success(true))
        )

        val config = balletMaster.defineStates<MyStepId, MyState>("order-flow", "1.0.0") {
            state(MyState.DONE) { step(MyStepId.STEP_A) }
            defaultState(MyState.TODO)
        }

        assertEquals("order-flow", config.operationName)
        assertEquals("1.0.0", config.version)
        assertEquals(MyState.TODO, config.defaultState)
    }

    @Test
    fun defineStatesThrowsOriginalExceptionWhenRepositorySaveFails() {
        val storageFailure = RuntimeException("store unavailable")
        val balletMaster = BalletMaster(
            store = InMemoryStore(),
            callContextManager = CallContextManager(),
            stateConfigRepository = stubRepository(Result.failure(storageFailure))
        )

        val thrown = assertThrows(RuntimeException::class.java) {
            balletMaster.defineStates<MyStepId, MyState>("order-flow", "1.0.0") {
                state(MyState.DONE) { step(MyStepId.STEP_A) }
                defaultState(MyState.TODO)
            }
        }

        assertSame(storageFailure, thrown)
    }

    @Test
    fun defineStatesThrowsIllegalStateWhenRepositorySaveReturnsFalse() {
        val balletMaster = BalletMaster(
            store = InMemoryStore(),
            callContextManager = CallContextManager(),
            stateConfigRepository = stubRepository(Result.success(false))
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            balletMaster.defineStates<MyStepId, MyState>("order-flow", "1.0.0") {
                state(MyState.DONE) { step(MyStepId.STEP_A) }
                defaultState(MyState.TODO)
            }
        }

        assertTrue(thrown.message?.contains("order-flow") == true)
        assertTrue(thrown.message?.contains("1.0.0") == true)
    }
}
