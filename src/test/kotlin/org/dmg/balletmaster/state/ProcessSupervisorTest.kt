package org.dmg.balletmaster.state

import org.dmg.balletmaster.BalletMaster
import org.dmg.balletmaster.CallContextManager
import org.dmg.balletmaster.InMemoryStore
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.Try
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessSupervisorTest {

    enum class OrderSteps : StepId {
        CREATE_ORDER,
        PAY_ORDER,
        PACK_ORDER,
        SHIP_ORDER
    }

    enum class OrderStatus {
        TODO,
        IN_PROGRESS,
        COMPLETED,
        UNKNOWN
    }

    @Test
    fun `version comparator handles semantic, numeric and alphanumeric versioning`() {
        // Numeric tokens comparison
        assertTrue(VersionComparator.compare("1.0", "1.1") < 0)
        assertTrue(VersionComparator.compare("1.1", "1.2") < 0)
        assertTrue(VersionComparator.compare("1.2", "1.10") < 0) // 1.10 is later than 1.2
        assertTrue(VersionComparator.compare("1.9.0", "1.10.0") < 0)
        assertTrue(VersionComparator.compare("2.0.0", "1.10.0") > 0)

        // Equal versions
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"))

        // Alphanumeric
        assertTrue(VersionComparator.compare("v1.0", "v2.0") < 0)
        assertTrue(VersionComparator.compare("2026.01", "2026.02") < 0)
    }

    @Test
    fun `state config repository resolves latest version by prefix or overall`() {
        val store = InMemoryStore()
        val repo = StoreStateConfigRepository(store)

        fun defineDummyConfig(version: String) {
            val config = processStates<OrderSteps, OrderStatus>("order-flow", version) {
                state(OrderStatus.COMPLETED) { step(OrderSteps.SHIP_ORDER) }
                defaultState(OrderStatus.UNKNOWN)
            }
            repo.save(config)
        }

        defineDummyConfig("1.0.0")
        defineDummyConfig("1.1.0")
        defineDummyConfig("1.2.0")
        defineDummyConfig("2.0.0")
        defineDummyConfig("2.1.0")

        // 1. All registered versions
        val versions = repo.getVersions("order-flow").getOrThrow()
        assertEquals(listOf("1.0.0", "1.1.0", "1.2.0", "2.0.0", "2.1.0"), versions)

        // 2. Latest for prefix "1." -> 1.2.0
        val v1Latest = repo.getLatest<OrderSteps, OrderStatus>("order-flow", "1.").getOrThrow()
        assertNotNull(v1Latest)
        assertEquals("1.2.0", v1Latest!!.version)

        // 3. Latest for prefix "1.1" -> 1.1.0
        val v11Latest = repo.getLatest<OrderSteps, OrderStatus>("order-flow", "1.1").getOrThrow()
        assertNotNull(v11Latest)
        assertEquals("1.1.0", v11Latest!!.version)

        // 4. Latest overall (empty prefix "") -> 2.1.0
        val overallLatest = repo.getLatest<OrderSteps, OrderStatus>("order-flow", "").getOrThrow()
        assertNotNull(overallLatest)
        assertEquals("2.1.0", overallLatest!!.version)

        // 5. Non-existent prefix -> null
        val notFound = repo.getLatest<OrderSteps, OrderStatus>("order-flow", "3.").getOrThrow()
        assertNull(notFound)
    }

    @Test
    fun `process supervisor resolves state using prefix and overall latest schemas`() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)
        val supervisor = ProcessSupervisor(store)

        // Schema v1.0: only has CREATE and PAY. Once paid, it is COMPLETED.
        balletMaster.defineStates<OrderSteps, OrderStatus>("ecommerce-order", "1.0.0") {
            state(OrderStatus.COMPLETED) { allOf(OrderSteps.CREATE_ORDER, OrderSteps.PAY_ORDER) }
            state(OrderStatus.IN_PROGRESS) { step(OrderSteps.CREATE_ORDER) }
            state(OrderStatus.TODO) { empty() }
            defaultState(OrderStatus.UNKNOWN)
        }

        // Schema v2.0: introduced PACK_ORDER. COMPLETED now requires PACK_ORDER too!
        balletMaster.defineStates<OrderSteps, OrderStatus>("ecommerce-order", "2.0.0") {
            state(OrderStatus.COMPLETED) { allOf(OrderSteps.CREATE_ORDER, OrderSteps.PAY_ORDER, OrderSteps.PACK_ORDER) }
            state(OrderStatus.IN_PROGRESS) { anyOf(OrderSteps.CREATE_ORDER, OrderSteps.PAY_ORDER) }
            state(OrderStatus.TODO) { empty() }
            defaultState(OrderStatus.UNKNOWN)
        }

        // --- Execution: run workflow for process ord-777 ---
        // Steps CREATE_ORDER and PAY_ORDER are executed.
        callContextManager.store("ecommerce-order", "ord-777", isReplay = false, attempt = 0)

        val executionResult = balletMaster
            .start()
            .step(OrderSteps.CREATE_ORDER)
            .map { _, _ -> "created" }
            .checkPoint()
            .step(OrderSteps.PAY_ORDER)
            .map { _, _ -> "paid" }
            .checkPoint()
            .execute()

        assertTrue(executionResult.isSuccess)
        callContextManager.clear()

        // --- Supervision / State Resolution ---

        // 1. Under v1.x lens (versionPrefix = "1."):
        // CREATE_ORDER and PAY_ORDER are done -> according to v1.0, the order is COMPLETED!
        val stateUnderV1 = supervisor.resolveState<OrderSteps, OrderStatus>(
            operationName = "ecommerce-order",
            operationId = "ord-777",
            versionPrefix = "1."
        ).getOrThrow()

        assertEquals(OrderStatus.COMPLETED, stateUnderV1)

        // 2. Under latest overall lens (versionPrefix = ""):
        // Picks latest schema v2.0.0. Under v2.0.0, PACK_ORDER is still missing -> order is IN_PROGRESS!
        val stateUnderLatest = supervisor.resolveState<OrderSteps, OrderStatus>(
            operationName = "ecommerce-order",
            operationId = "ord-777",
            versionPrefix = ""
        ).getOrThrow()

        assertEquals(OrderStatus.IN_PROGRESS, stateUnderLatest)

        // 3. Querying with non-matching version prefix returns failure
        val failureResult = supervisor.resolveState<OrderSteps, OrderStatus>(
            operationName = "ecommerce-order",
            operationId = "ord-777",
            versionPrefix = "99."
        )

        assertFalse(failureResult.isSuccess)
        assertTrue(failureResult.isFailure)
    }
}
