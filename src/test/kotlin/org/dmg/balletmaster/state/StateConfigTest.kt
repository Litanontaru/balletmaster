package org.dmg.balletmaster.state

import org.dmg.balletmaster.BalletMaster
import org.dmg.balletmaster.CallContext
import org.dmg.balletmaster.CallContextManager
import org.dmg.balletmaster.Context
import org.dmg.balletmaster.InMemoryStore
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateConfigTest {

    enum class TestSteps : StepId {
        STEP_A,
        STEP_B,
        STEP_C,
        STEP_D
    }

    enum class TestStatus {
        TODO,
        IN_PROGRESS,
        READY_TO_PROCESS,
        PROCESSED,
        UNKNOWN
    }

    data class ComplexState(val code: Int, val description: String)

    @Test
    fun `dsl and evaluator basic matching and order priority`() {
        val config = processStates<TestSteps, TestStatus>("test-op", "1.0") {
            state(TestStatus.PROCESSED) { step(TestSteps.STEP_D) }
            state(TestStatus.READY_TO_PROCESS) { allOf(TestSteps.STEP_A, TestSteps.STEP_B, TestSteps.STEP_C) }
            state(TestStatus.IN_PROGRESS) { anyOf(TestSteps.STEP_A, TestSteps.STEP_B) }
            state(TestStatus.TODO) { empty() }
            defaultState(TestStatus.UNKNOWN)
        }

        // 1. None completed -> TODO
        assertEquals(TestStatus.TODO, config.evaluate { false })

        // 2. Only STEP_A completed -> IN_PROGRESS
        assertEquals(TestStatus.IN_PROGRESS, config.evaluate { it == TestSteps.STEP_A })

        // 3. STEP_A and STEP_B completed -> IN_PROGRESS
        assertEquals(TestStatus.IN_PROGRESS, config.evaluate { it in setOf(TestSteps.STEP_A, TestSteps.STEP_B) })

        // 4. STEP_A, STEP_B, STEP_C completed -> READY_TO_PROCESS
        assertEquals(
            TestStatus.READY_TO_PROCESS,
            config.evaluate { it in setOf(TestSteps.STEP_A, TestSteps.STEP_B, TestSteps.STEP_C) }
        )

        // 5. STEP_D completed (even if others are too) -> PROCESSED (rule priority: first match)
        assertEquals(
            TestStatus.PROCESSED,
            config.evaluate { it in setOf(TestSteps.STEP_A, TestSteps.STEP_B, TestSteps.STEP_C, TestSteps.STEP_D) }
        )

        // 6. Only STEP_C completed -> not empty, but not A or B -> UNKNOWN
        assertEquals(TestStatus.UNKNOWN, config.evaluate { it == TestSteps.STEP_C })
    }

    @Test
    fun `short-circuiting prevents unnecessary predicate checks`() {
        val checkedSteps = mutableListOf<TestSteps>()
        val isDone: (TestSteps) -> Boolean = { step ->
            checkedSteps.add(step)
            step == TestSteps.STEP_A // only A is done
        }

        val config = processStates<TestSteps, TestStatus>("short-circuit-op", "1.0") {
            // allOf(STEP_B, STEP_C) -> B is false, C should NEVER be queried!
            state(TestStatus.READY_TO_PROCESS) { allOf(TestSteps.STEP_B, TestSteps.STEP_C) }
            // anyOf(STEP_A, STEP_D) -> A is true, D should NEVER be queried!
            state(TestStatus.IN_PROGRESS) { anyOf(TestSteps.STEP_A, TestSteps.STEP_D) }
            defaultState(TestStatus.UNKNOWN)
        }

        val result = config.evaluate(isDone)

        assertEquals(TestStatus.IN_PROGRESS, result)
        // Checked order:
        // 1. Rule 1: STEP_B checked -> returned false -> short-circuit (STEP_C not checked)
        // 2. Rule 2: STEP_A checked -> returned true -> short-circuit (STEP_D not checked)
        assertEquals(listOf(TestSteps.STEP_B, TestSteps.STEP_A), checkedSteps)
    }

    @Test
    fun `serialization and deserialization roundtrip with enums and complex state`() {
        val originalConfig = processStates<TestSteps, ComplexState>("complex-op", "2.1") {
            state(ComplexState(200, "Done")) { step(TestSteps.STEP_D) }
            state(ComplexState(100, "Waiting")) { allOf(TestSteps.STEP_A, TestSteps.STEP_B) }
            state(ComplexState(0, "Initial")) { empty() }
            defaultState(ComplexState(-1, "Unknown"))
        }

        val serializer = StateConfigSerializer()
        val json = serializer.serialize(originalConfig)
        assertTrue(json.contains("\"operationName\":\"complex-op\""))
        assertTrue(json.contains("\"version\":\"2.1\""))

        val restoredConfig = serializer.deserialize(json, TestSteps::class.java, ComplexState::class.java)

        assertEquals(originalConfig.operationName, restoredConfig.operationName)
        assertEquals(originalConfig.version, restoredConfig.version)
        assertEquals(originalConfig.defaultState, restoredConfig.defaultState)

        // Evaluate both configs and ensure exact same outputs
        val stateNone = restoredConfig.evaluate { false }
        assertEquals(ComplexState(0, "Initial"), stateNone)

        val stateWaiting = restoredConfig.evaluate { it in setOf(TestSteps.STEP_A, TestSteps.STEP_B) }
        assertEquals(ComplexState(100, "Waiting"), stateWaiting)

        val stateDone = restoredConfig.evaluate { it == TestSteps.STEP_D }
        assertEquals(ComplexState(200, "Done"), stateDone)
    }

    @Test
    fun `balletmaster persistence to store and supervisor reload`() {
        val store = InMemoryStore()
        val clientId = "tenant-1"
        val balletMaster = BalletMaster(clientId, store, CallContextManager())

        // 1. Define states on worker node via facade
        val config = balletMaster.defineStates<TestSteps, TestStatus>("order-flow", "v1.0") {
            state(TestStatus.PROCESSED) { step(TestSteps.STEP_D) }
            state(TestStatus.IN_PROGRESS) { anyOf(TestSteps.STEP_A, TestSteps.STEP_B) }
            state(TestStatus.TODO) { empty() }
            defaultState(TestStatus.UNKNOWN)
        }
        assertNotNull(config)

        // 2. Verify stored in store
        val storedJson = store.get<String>("tenant-1:config:order-flow:v1.0").fold({ it }, { null })
        assertNotNull(storedJson)

        // 3. Supervisor reads directly from StateConfigRepository
        val supervisorRepo = StoreStateConfigRepository(clientId, store)
        val supervisorConfig = supervisorRepo.get<TestSteps, TestStatus>("order-flow", "v1.0").getOrThrow()
        assertNotNull(supervisorConfig)
        assertEquals("order-flow", supervisorConfig!!.operationName)
        assertEquals("v1.0", supervisorConfig.version)

        assertEquals(TestStatus.TODO, supervisorConfig.evaluate { false })
        assertEquals(TestStatus.IN_PROGRESS, supervisorConfig.evaluate { it == TestSteps.STEP_A })
        assertEquals(TestStatus.PROCESSED, supervisorConfig.evaluate { it == TestSteps.STEP_D })
    }

    @Test
    fun `custom state config repository pluggability in balletmaster`() {
        val savedConfigs = mutableListOf<StateConfig<*, *>>()
        val mockRepo = object : StateConfigRepository {
            override fun <S : StepId, STATE : Any> save(config: StateConfig<S, STATE>): Result<Boolean> {
                savedConfigs.add(config)
                return Result.success(true)
            }

            override fun <S : StepId, STATE : Any> get(
                operationName: String,
                version: String,
                stepClass: Class<S>,
                stateClass: Class<STATE>
            ): Result<StateConfig<S, STATE>?> {
                @Suppress("UNCHECKED_CAST")
                val found = savedConfigs.firstOrNull { it.operationName == operationName && it.version == version } as? StateConfig<S, STATE>
                return Result.success(found)
            }
        }

        val balletMaster = BalletMaster(
            clientId = "tenant-custom",
            store = InMemoryStore(),
            callContextManager = CallContextManager(),
            stateConfigRepository = mockRepo
        )

        balletMaster.defineStates<TestSteps, TestStatus>("plug-op", "1.0") {
            state(TestStatus.PROCESSED) { step(TestSteps.STEP_A) }
            defaultState(TestStatus.UNKNOWN)
        }

        assertEquals(1, savedConfigs.size)
        val loaded = mockRepo.get<TestSteps, TestStatus>("plug-op", "1.0").getOrThrow()
        assertNotNull(loaded)
        assertEquals("plug-op", loaded!!.operationName)
    }

    // --- DDD Cross-Domain Test ---

    enum class OrderDomainSteps : StepId {
        ORDER_CREATED,
        ORDER_PAID,
        ORDER_SHIPPED
    }

    enum class OrderDomainState {
        DRAFT,
        AWAITING_PAYMENT,
        AWAITING_SHIPMENT,
        COMPLETED,
        UNKNOWN
    }

    enum class PaymentDomainSteps : StepId {
        HOLD_FUNDS,
        CAPTURE_FUNDS,
        REFUND_FUNDS
    }

    enum class PaymentDomainState {
        NOT_STARTED,
        PENDING_CONFIRMATION,
        SETTLED,
        REFUNDED,
        UNKNOWN
    }

    @Test
    fun `cross-domain supervisor perspectives on shared execution facts`() {
        val store = InMemoryStore()
        val clientId = "store-client"
        val balletMaster = BalletMaster(clientId, store, CallContextManager())

        // Domain 1: Order Supervisor Config
        val orderConfig = balletMaster.defineStates<OrderDomainSteps, OrderDomainState>("ecommerce-process", "1.0") {
            state(OrderDomainState.COMPLETED) { step(OrderDomainSteps.ORDER_SHIPPED) }
            state(OrderDomainState.AWAITING_SHIPMENT) { step(OrderDomainSteps.ORDER_PAID) }
            state(OrderDomainState.AWAITING_PAYMENT) { step(OrderDomainSteps.ORDER_CREATED) }
            state(OrderDomainState.DRAFT) { empty() }
            defaultState(OrderDomainState.UNKNOWN)
        }

        // Domain 2: Payment Supervisor Config
        val paymentConfig = balletMaster.defineStates<PaymentDomainSteps, PaymentDomainState>("ecommerce-process-payment", "1.0") {
            state(PaymentDomainState.REFUNDED) { step(PaymentDomainSteps.REFUND_FUNDS) }
            state(PaymentDomainState.SETTLED) { step(PaymentDomainSteps.CAPTURE_FUNDS) }
            state(PaymentDomainState.PENDING_CONFIRMATION) { step(PaymentDomainSteps.HOLD_FUNDS) }
            state(PaymentDomainState.NOT_STARTED) { empty() }
            defaultState(PaymentDomainState.UNKNOWN)
        }

        // Simulate shared execution facts in Context:
        // ORDER_CREATED and HOLD_FUNDS are done
        val mockContext = object : Context {
            override fun isDone(stepId: StepId): Boolean = when (stepId) {
                OrderDomainSteps.ORDER_CREATED -> true
                PaymentDomainSteps.HOLD_FUNDS -> true
                else -> false
            }
            override val operationSuffix: Any = ""
        }

        // Order supervisor evaluates using its lens:
        val orderState = orderConfig.evaluate(mockContext)
        assertEquals(OrderDomainState.AWAITING_PAYMENT, orderState)

        // Payment supervisor evaluates using its lens:
        val paymentState = paymentConfig.evaluate(mockContext)
        assertEquals(PaymentDomainState.PENDING_CONFIRMATION, paymentState)

        // Now payment is settled (CAPTURE_FUNDS) and order is marked paid (ORDER_PAID)
        val mockContextAfterCapture = object : Context {
            override fun isDone(stepId: StepId): Boolean = when (stepId) {
                OrderDomainSteps.ORDER_CREATED, OrderDomainSteps.ORDER_PAID -> true
                PaymentDomainSteps.HOLD_FUNDS, PaymentDomainSteps.CAPTURE_FUNDS -> true
                else -> false
            }
            override val operationSuffix: Any = ""
        }

        assertEquals(OrderDomainState.AWAITING_SHIPMENT, orderConfig.evaluate(mockContextAfterCapture))
        assertEquals(PaymentDomainState.SETTLED, paymentConfig.evaluate(mockContextAfterCapture))
    }
}
