package org.dmg.balletmaster

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class MilestoneTest {
    enum class StepIds : StepId {
        STEP_A,
        STEP_B
    }

    enum class BatchSteps : StepId {
        FETCH_PAGE,
        PROCESS_PAGE
    }

    data class PageData(val pageNumber: Int, val isLast: Boolean)

    @Test
    fun sequentialPaginationLoop() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        val processedPages = mutableListOf<Int>()

        // Processing method (simulates a Kafka consumer / worker handling one iteration):
        fun processPage(page: Int, suffix: String): Try<PageData> {
            callContextManager.store("batchOp", "101", isReplay = false, attempt = 0, operationSuffix = suffix)

            return balletMaster
                .start()
                .step(BatchSteps.FETCH_PAGE)
                .input(page)
                .map { _, p -> PageData(p, isLast = (p >= 3)) }
                .checkPoint()
                .step(BatchSteps.PROCESS_PAGE)
                .map { _, pageData ->
                    processedPages.add(pageData.pageNumber)
                    pageData
                }
                .checkPoint()
                .execute()
        }

        // Sequential execution loop driven by application logic
        var currentPage = 1
        var currentSuffix = ""

        while (true) {
            val result = processPage(currentPage, currentSuffix)
            var hasNext = false

            result.fold(
                onFailure = { throw it },
                onState = { status ->
                    // Technical status means processing intentionally did not launch (e.g. BLOCKED, SKIP)
                    fail("Expected step to execute, but got technical status: $status")
                },
                onSuccess = { pageData ->
                    // Application code decides whether to continue to the next iteration
                    if (!pageData.isLast) {
                        currentPage = pageData.pageNumber + 1
                        currentSuffix = "page_${pageData.pageNumber + 1}"
                        hasNext = true
                    }
                }
            )

            if (!hasNext) {
                break // Exit loop
            }
        }

        callContextManager.clear()

        // Assertions:
        // 1. All 3 pages were processed sequentially
        assertEquals(listOf(1, 2, 3), processedPages)

        // 2. All 3 loop iterations have isolated keys in Store:
        val page1 = store.get<Try<PageData>>("batchOp:101:0::FETCH_PAGE:output")
        assertTrue(page1.isSuccess)
        assertEquals(Try.success(PageData(1, isLast = false)), page1.getOrThrow())

        val page2 = store.get<Try<PageData>>("batchOp:101:0:page_2:FETCH_PAGE:output")
        assertTrue(page2.isSuccess)
        assertEquals(Try.success(PageData(2, isLast = false)), page2.getOrThrow())

        val page3 = store.get<Try<PageData>>("batchOp:101:0:page_3:FETCH_PAGE:output")
        assertTrue(page3.isSuccess)
        assertEquals(Try.success(PageData(3, isLast = true)), page3.getOrThrow())

        // 3. Idempotency check: re-delivery of page 2 with suffix "page_2"
        // Processing should intentionally NOT launch and return TechStatus.SKIP
        val duplicateAttempt = processPage(2, "page_2")
        assertTrue(duplicateAttempt.isState)
        assertEquals(Try.state<PageData>(TechStatus.SKIP), duplicateAttempt)
        // Side effects were not executed again:
        assertEquals(listOf(1, 2, 3), processedPages)
    }

    @Test
    fun singleWorkflowSpiralLoop() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("spiralOp", "101", isReplay = false, attempt = 0)

        val result = balletMaster
            .start()
            // Iteration 0 (default empty suffix)
            .step(StepIds.STEP_A)
            .input("start")
            .map { ctx, input ->
                assertEquals("", ctx.operationSuffix)
                assertEquals("", ctx.suffix)
                "$input-A0"
            }
            .checkPoint()
            // Milestone updates the suffix to "iter-1" and passes input ("start-A0") through
            .milestone { ctx, input ->
                assertEquals("", ctx.operationSuffix)
                "iter-1"
            }
            // Iteration 1: re-execute STEP_A with the new suffix
            .step(StepIds.STEP_A)
            .map { ctx, input ->
                assertEquals("iter-1", ctx.operationSuffix)
                assertEquals("iter-1", ctx.suffix)
                "$input-A1"
            }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(result.isSuccess)
        // input passed through milestone: "start-A0" -> "start-A0-A1"
        assertEquals(Try.success("start-A0-A1"), result)

        // Verify keys in Store:
        // 1. Iteration 0 keys (suffix "")
        val iter0StepA = store.get<Try<String>>("spiralOp:101:0::STEP_A:output")
        assertTrue(iter0StepA.isSuccess)
        assertEquals(Try.success("start-A0"), iter0StepA.getOrThrow())

        // 2. Iteration 1 keys (suffix "iter-1")
        val iter1StepA = store.get<Try<String>>("spiralOp:101:0:iter-1:STEP_A:output")
        assertTrue(iter1StepA.isSuccess)
        assertEquals(Try.success("start-A0-A1"), iter1StepA.getOrThrow())
    }

    @Test
    fun milestoneChainedDirectlyOnStep() {
        val store = InMemoryStore()
        val notifiedSuffix = AtomicReference<String>()
        val callContextManager = CallContextManager(
            suffixListener = { call -> notifiedSuffix.set(call.operationSuffix.toString()) }
        )
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("chainedOp", "102", isReplay = false, attempt = 0)

        // Milestone can be chained directly on a step without needing a separate stepId
        val result = balletMaster
            .start()
            .step(StepIds.STEP_A)
            .input("work")
            .map { _, input -> "$input-completed" }
            .checkPoint()
            .milestone { _, _ -> "next-phase" }
            .execute()

        assertTrue(result.isSuccess)
        // Business payload is preserved
        assertEquals(Try.success("work-completed"), result)
        // Suffix was updated
        assertEquals("next-phase", notifiedSuffix.get())
        assertEquals("next-phase", callContextManager.get()?.operationSuffix)

        callContextManager.clear()
    }

    @Test
    fun distributedConsumersMilestoneSimulation() {
        val store = InMemoryStore()
        val notifiedSuffix = AtomicReference<String>()
        val callContextManager = CallContextManager(
            suffixListener = { updatedCall ->
                notifiedSuffix.set(updatedCall.operationSuffix.toString())
            }
        )
        val balletMaster = BalletMaster(store, callContextManager)

        // --- Kafka Consumer 1: processes Iteration 0 and triggers milestone ---
        callContextManager.store("distributedOp", "202", isReplay = false, attempt = 0, operationSuffix = "")

        val consumer1Result = balletMaster
            .start()
            .step(StepIds.STEP_A)
            .input("payload-0")
            .map { _, input -> "$input-processed" }
            .checkPoint()
            .milestone { ctx, input ->
                // Compute next suffix based on business logic
                "batch-2"
            }
            .execute()

        assertTrue(consumer1Result.isSuccess)
        // Business payload is returned for Kafka message body
        assertEquals(Try.success("payload-0-processed"), consumer1Result)
        // New suffix is available for Kafka headers via listener and callContextManager
        assertEquals("batch-2", notifiedSuffix.get())
        assertEquals("batch-2", callContextManager.get()?.operationSuffix)

        callContextManager.clear()

        // --- Kafka Consumer 2: receives message for next iteration with header operationSuffix = "batch-2" ---
        callContextManager.store("distributedOp", "202", isReplay = false, attempt = 0, operationSuffix = "batch-2")

        val consumer2Result = balletMaster
            .start()
            .step(StepIds.STEP_A)
            .input("payload-1")
            .map { ctx, input ->
                assertEquals("batch-2", ctx.operationSuffix)
                "$input-batch2"
            }
            .checkPoint()
            .step(StepIds.STEP_B)
            .map { ctx, input ->
                assertEquals("batch-2", ctx.operationSuffix)
                "$input-done"
            }
            .checkPoint()
            .execute()

        callContextManager.clear()

        assertTrue(consumer2Result.isSuccess)
        assertEquals(Try.success("payload-1-batch2-done"), consumer2Result)

        // Verify isolation in store
        val stepA0 = store.get<Try<String>>("distributedOp:202:0::STEP_A:output")
        val stepABatch2 = store.get<Try<String>>("distributedOp:202:0:batch-2:STEP_A:output")
        val stepBBatch2 = store.get<Try<String>>("distributedOp:202:0:batch-2:STEP_B:output")

        assertEquals(Try.success("payload-0-processed"), stepA0.getOrThrow())
        assertEquals(Try.success("payload-1-batch2"), stepABatch2.getOrThrow())
        assertEquals(Try.success("payload-1-batch2-done"), stepBBatch2.getOrThrow())
    }

    @Test
    fun milestoneCalculatesNextSuffixIncrementally() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("counterOp", "404", isReplay = false, attempt = 0, operationSuffix = "iter_1")

        val result = balletMaster
            .start()
            .step(StepIds.STEP_A)
            .input("data")
            .milestone { ctx, _ ->
                val currentNumber = ctx.operationSuffix.toString().removePrefix("iter_").toInt()
                "iter_${currentNumber + 1}"
            }
            .execute()

        assertEquals("iter_2", callContextManager.get()?.operationSuffix)
        assertEquals(Try.success("data"), result)

        callContextManager.clear()
    }
}
