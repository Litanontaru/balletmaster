package org.dmg.balletmaster

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.dmg.balletmaster.context.CallContext
import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.context.ContextImpl
import org.dmg.balletmaster.logging.BalletLoggers
import org.dmg.balletmaster.logging.BalletMdc
import org.dmg.balletmaster.logging.debugEvent
import org.dmg.balletmaster.logging.elapsedMillis
import org.dmg.balletmaster.logging.startDebugTimer
import org.dmg.balletmaster.logging.startWarnTimer
import org.dmg.balletmaster.state.ProcessSupervisor
import org.dmg.balletmaster.state.StoreStateConfigRepository
import org.dmg.balletmaster.state.processStates
import org.dmg.balletmaster.state.resolveState
import org.dmg.balletmaster.store.InMemoryStore
import org.dmg.balletmaster.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoggingTest {

    enum class Steps : StepId {
        STEP_A,
        STEP_B
    }

    enum class States {
        DONE,
        TODO
    }

    data class CustomProcessState(val status: String, val customerSecret: String)

    private class SnapshottingListAppender : AppenderBase<ILoggingEvent>() {
        private val items = mutableListOf<ILoggingEvent>()

        val list: List<ILoggingEvent>
            get() = synchronized(items) { items.toList() }

        override fun append(eventObject: ILoggingEvent) {
            eventObject.prepareForDeferredProcessing()
            synchronized(items) {
                items.add(eventObject)
            }
        }
    }

    private lateinit var sdkLogger: Logger
    private lateinit var appLogger: Logger
    private var previousSdkLevel: Level? = null
    private var previousAppLevel: Level? = null
    private var previousSdkAdditive: Boolean = true
    private var previousAppAdditive: Boolean = true
    private lateinit var appender: SnapshottingListAppender

    @BeforeEach
    fun setUp() {
        appender = SnapshottingListAppender().apply { start() }

        sdkLogger = LoggerFactory.getLogger(BalletLoggers.ROOT) as Logger
        previousSdkLevel = sdkLogger.level
        previousSdkAdditive = sdkLogger.isAdditive
        sdkLogger.level = Level.TRACE
        sdkLogger.isAdditive = false
        sdkLogger.addAppender(appender)

        appLogger = LoggerFactory.getLogger("com.example.App") as Logger
        previousAppLevel = appLogger.level
        previousAppAdditive = appLogger.isAdditive
        appLogger.level = Level.INFO
        appLogger.isAdditive = false
        appLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        sdkLogger.detachAppender(appender)
        appLogger.detachAppender(appender)
        sdkLogger.level = previousSdkLevel
        appLogger.level = previousAppLevel
        sdkLogger.isAdditive = previousSdkAdditive
        appLogger.isAdditive = previousAppAdditive
        appender.stop()
        MDC.clear()
    }

    private fun events(eventName: String? = null, level: Level? = null): List<ILoggingEvent> =
        appender.list.filter { event ->
            (eventName == null || event.message == eventName) &&
                (level == null || event.level == level)
        }

    private fun ILoggingEvent.attrs(): Map<String, Any?> =
        keyValuePairs.orEmpty().associate { it.key to it.value }

    @Test
    fun successfulChainLogsLifecycleWithoutUserPayloads() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        val secretInput = "SECRET_CARD_4111"
        val secretOutput = "SECRET_TOKEN_999"

        callContextManager.store("orderFlow", "ord-1", isReplay = false, attempt = 0)
        try {
            val result = balletMaster
                .start()
                .step(Steps.STEP_A)
                .input(secretInput)
                .map { _, _ -> secretOutput }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, _ -> "B-ok" }
                .checkPoint()
                .execute()

            assertTrue(result.isSuccess)
        } finally {
            callContextManager.clear()
        }

        val startedChain = events("chain.started", Level.DEBUG)
        assertEquals(1, startedChain.size)
        assertEquals(2, startedChain[0].attrs()["stepsCount"])
        assertEquals("STEP_A,STEP_B", startedChain[0].attrs()["steps"])

        val startedSteps = events("step.started", Level.DEBUG)
        assertEquals(2, startedSteps.size)
        assertTrue(startedSteps.any {
            val attrs = it.attrs()
            attrs[BalletMdc.OPERATION] == "orderFlow" &&
                attrs[BalletMdc.OPERATION_ID] == "ord-1" &&
                attrs[BalletMdc.STEP] == "STEP_A"
        })
        assertTrue(startedSteps.any { it.attrs()[BalletMdc.STEP] == "STEP_B" })

        val checkpoints = events("step.checkpoint_saved", Level.DEBUG)
        assertEquals(2, checkpoints.size)

        val completedSteps = events("step.completed", Level.DEBUG)
        assertEquals(2, completedSteps.size)
        assertTrue(completedSteps.all { it.attrs()["outcome"] == "SUCCESS" })

        val completedChain = events("chain.completed", Level.DEBUG)
        assertEquals(1, completedChain.size)

        // Sensitive payloads must never leak into system logs (neither message, keyValues, nor MDC)
        assertTrue(appender.list.none { event ->
            event.formattedMessage.contains(secretInput) ||
                event.attrs().values.any { it?.toString()?.contains(secretInput) == true } ||
                event.mdcPropertyMap.values.any { it.contains(secretInput) }
        })
        assertTrue(appender.list.none { event ->
            event.formattedMessage.contains(secretOutput) ||
                event.attrs().values.any { it?.toString()?.contains(secretOutput) == true } ||
                event.mdcPropertyMap.values.any { it.contains(secretOutput) }
        })
    }

    @Test
    fun mdcPropagatesCorrelationIntoUserCodeAndRestoresPreviousValues() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        MDC.put("traceId", "trace-xyz")
        MDC.put(BalletMdc.STEP, "outer-step")

        callContextManager.store("mdcOp", "42", isReplay = false, attempt = 1, operationSuffix = "init")
        try {
            balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ ->
                    appLogger.info("user-business-log")
                    "ok"
                }
                .milestone { _, _ -> "phase-2" }
                .execute()
        } finally {
            callContextManager.clear()
        }

        val appEvent = appender.list.first { it.loggerName == "com.example.App" }
        assertEquals("trace-xyz", appEvent.mdcPropertyMap["traceId"])
        assertEquals("mdcOp", appEvent.mdcPropertyMap[BalletMdc.OPERATION])
        assertEquals("42", appEvent.mdcPropertyMap[BalletMdc.OPERATION_ID])
        assertEquals("1", appEvent.mdcPropertyMap[BalletMdc.ATTEMPT])
        assertEquals("init", appEvent.mdcPropertyMap[BalletMdc.SUFFIX])
        assertEquals("STEP_A", appEvent.mdcPropertyMap[BalletMdc.STEP])
        assertEquals("false", appEvent.mdcPropertyMap[BalletMdc.REPLAY])

        val milestoneEvents = events("step.milestone_updated", Level.DEBUG)
        assertEquals(1, milestoneEvents.size)
        assertEquals("phase-2", milestoneEvents[0].attrs()["newSuffix"])

        // Pre-existing MDC entries must be restored and transient ballet.* keys cleaned up
        assertEquals("trace-xyz", MDC.get("traceId"))
        assertEquals("outer-step", MDC.get(BalletMdc.STEP))
        assertNull(MDC.get(BalletMdc.OPERATION))
        assertNull(MDC.get(BalletMdc.OPERATION_ID))
    }

    @Test
    fun gateSkipIdempotentSkipAndNoCallAreLogged() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        // 1. NO_CALL
        val noCall = balletMaster.start().step(Steps.STEP_A).map { _, _ -> "x" }.execute()
        assertTrue(noCall.isState)
        assertEquals(1, events("step.no_call", Level.WARN).size)

        // 2. Gate SKIP + chain.stopped
        callContextManager.store("skipOp", "10", isReplay = false, attempt = 0)
        try {
            balletMaster.start()
                .step(Steps.STEP_A)
                .gate { _, _ -> false }
                .map { _, _ -> "never" }
                .checkPoint()
                .execute()

            val gateSkip = events("step.skipped_gate", Level.DEBUG)
            assertEquals(1, gateSkip.size)

            // 3. Complete STEP_B once, then re-deliver to trigger idempotency SKIP
            balletMaster.start().step(Steps.STEP_B).map { _, _ -> "done" }.checkPoint().execute()
            balletMaster.start().step(Steps.STEP_B).map { _, _ -> "dup" }.checkPoint().execute()

            val idempotentSkip = events("step.skipped_idempotent", Level.DEBUG)
            assertEquals(1, idempotentSkip.size)
            assertEquals("STEP_B", idempotentSkip[0].attrs()[BalletMdc.STEP])

            val stopped = events("chain.stopped", Level.DEBUG)
            assertTrue(stopped.any {
                val attrs = it.attrs()
                attrs["outcome"] == "STATE" && attrs["status"] == "SKIP"
            })
        } finally {
            callContextManager.clear()
        }
    }

    @Test
    fun replayLogsCacheHitMissAndSwitchToLive() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("repOp", "77", isReplay = false, attempt = 0)
        balletMaster.start().step(Steps.STEP_A).map { _, _ -> "A" }.checkPoint().execute()
        callContextManager.clear()

        callContextManager.store("repOp", "77", isReplay = true, attempt = 0)
        try {
            balletMaster.start()
                .step(Steps.STEP_A)
                .map { _, _ -> "A-unused" }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, input -> "$input-B" }
                .checkPoint()
                .execute()
        } finally {
            callContextManager.clear()
        }

        val hits = events("replay.cache_hit", Level.DEBUG)
        assertEquals(1, hits.size)
        assertEquals(BalletLoggers.REPLAY, hits[0].loggerName)
        assertEquals("STEP_A", hits[0].attrs()[BalletMdc.STEP])

        val misses = events("replay.cache_miss", Level.DEBUG)
        assertEquals(1, misses.size)
        assertEquals("STEP_B", misses[0].attrs()[BalletMdc.STEP])

        val switched = events("replay.switched_to_live", Level.INFO)
        assertEquals(1, switched.size)
        assertEquals(BalletLoggers.REPLAY, switched[0].loggerName)
        assertEquals("STEP_B", switched[0].attrs()[BalletMdc.STEP])
    }

    @Test
    fun stepExceptionLogsClassWithoutSensitiveMessageAndStopsChain() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)
        var returnedFailure: Try<String>

        callContextManager.store("failOp", "88", isReplay = false, attempt = 0)
        try {
            val res = balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ -> throw IllegalArgumentException("bad-order secret=CARD_4111_SENSITIVE") }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, _ -> "never" }
                .execute()
            returnedFailure = res
            assertTrue(res.isFailure)

            val fallbackCtx = ContextImpl(store, { callContextManager.get() }, {}).apply {
                setStep(Steps.STEP_B)
            }
            val fallbackRes = fallbackCtx.execute<String> { throw IllegalStateException("secret=TOKEN_999") }
            assertTrue(fallbackRes.isFailure)
        } finally {
            callContextManager.clear()
        }

        val returnedCause = returnedFailure.fold({ it }, { null }, { null }, { null }) as? StepExecutionException
        assertNotNull(returnedCause)
        assertTrue(returnedCause?.cause?.message?.contains("CARD_4111_SENSITIVE") == true)

        val exEvents = events("step.exception", Level.DEBUG)
        assertEquals(1, exEvents.size)
        assertEquals("map", exEvents[0].attrs()["phase"])
        assertEquals("IllegalArgumentException", exEvents[0].attrs()["exception"])
        assertEquals("STEP_A", exEvents[0].attrs()[BalletMdc.STEP])
        assertNull(exEvents[0].throwableProxy)

        val fallbackEvents = events("step.exception", Level.WARN)
        assertEquals(1, fallbackEvents.size)
        assertEquals("execute", fallbackEvents[0].attrs()["phase"])
        assertEquals("IllegalStateException", fallbackEvents[0].attrs()["exception"])
        assertEquals("STEP_B", fallbackEvents[0].attrs()[BalletMdc.STEP])
        assertNull(fallbackEvents[0].throwableProxy)

        assertTrue(appender.list.none { event ->
            event.formattedMessage.contains("CARD_4111_SENSITIVE") ||
                event.formattedMessage.contains("TOKEN_999") ||
                event.attrs().values.any {
                    val str = it?.toString() ?: ""
                    str.contains("CARD_4111_SENSITIVE") || str.contains("TOKEN_999")
                }
        })

        val stopped = events("chain.stopped", Level.DEBUG)
        assertEquals(1, stopped.size)
        assertEquals("FAILURE", stopped[0].attrs()["outcome"])
    }

    @Test
    fun storeErrorLogsErrorOnStoreCategory() {
        val storeErr = RuntimeException("redis timeout")
        val failingStore = object : Store {
            private val delegate = InMemoryStore()
            override fun set(key: String, valueChange: Pair<Any?, Any?>) = delegate.set(key, valueChange)
            override fun set(key: String, value: Any?): Result<Boolean> =
                if (key.endsWith(":input")) Result.failure(storeErr) else delegate.set(key, value)
            override fun inc(key: String) = delegate.inc(key)
            override fun <T : Any> get(key: String) = delegate.get<T>(key)
        }
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(failingStore, callContextManager)

        callContextManager.store("errOp", "99", isReplay = false, attempt = 0)
        try {
            val res = balletMaster.start().step(Steps.STEP_A).input("data").map { _, _ -> "ok" }.execute()
            assertTrue(res.isError)
        } finally {
            callContextManager.clear()
        }

        val storeErrors = events("store.error", Level.ERROR)
        assertEquals(1, storeErrors.size)
        assertEquals(BalletLoggers.STORE, storeErrors[0].loggerName)
        assertEquals("set_input", storeErrors[0].attrs()["storeOp"])
        assertEquals("errOp", storeErrors[0].attrs()[BalletMdc.OPERATION])
        assertEquals("99", storeErrors[0].attrs()[BalletMdc.OPERATION_ID])
        assertNotNull(storeErrors[0].throwableProxy)

        val stopped = events("chain.stopped", Level.DEBUG)
        assertEquals(1, stopped.size)
        assertEquals("ERROR", stopped[0].attrs()["outcome"])
    }

    @Test
    fun lockContentionBlockedStolenAndStaleUnlockAreLogged() {
        val store = InMemoryStore()
        val call = CallContext("lockLogOp", "1", isReplay = false, attempt = 0)

        val staleCtx = ContextImpl(store, { call }, {}).apply {
            setStep(Steps.STEP_A)
            setLease(50L)
        }
        val newCtx = ContextImpl(store, { call }, {}).apply {
            setStep(Steps.STEP_A)
            setLease(1000L)
        }
        val probeCtx = ContextImpl(store, { call }, {}).apply {
            setStep(Steps.STEP_A)
            setLease(20L)
        }

        val staleEntered = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)

        val staleThread = Thread {
            staleCtx.execute {
                staleEntered.countDown()
                assertTrue(releaseStale.await(5, TimeUnit.SECONDS))
                Try.success("stale")
            }
        }
        staleThread.start()
        assertTrue(staleEntered.await(5, TimeUnit.SECONDS))

        val staleLease = store.get<Long>("lockLogOp:1:0::STEP_A:timeout").getOrThrow()!!
        while (System.currentTimeMillis() <= staleLease) {
            Thread.sleep(5)
        }

        val newThread = Thread {
            newCtx.execute {
                newEntered.countDown()
                assertTrue(releaseNew.await(5, TimeUnit.SECONDS))
                Try.success("new")
            }
        }
        newThread.start()
        assertTrue(newEntered.await(5, TimeUnit.SECONDS))

        releaseStale.countDown()
        staleThread.join(5000)

        probeCtx.execute { Try.success("blocked") }

        releaseNew.countDown()
        newThread.join(5000)

        assertEquals(1, events("lock.acquired", Level.TRACE).size)
        assertEquals(1, events("lock.stolen_expired", Level.WARN).size)
        assertEquals(1, events("lock.stale_unlock_skipped", Level.WARN).size)
        assertEquals(1, events("lock.blocked", Level.DEBUG).size)
        assertEquals(1, events("lock.released", Level.TRACE).size)
        assertTrue(events("lock.stolen_expired").all { it.loggerName == BalletLoggers.LOCK })
        assertFalse(events("lock.contention_wait", Level.TRACE).isEmpty())
    }

    @Test
    fun stateConfigAndSupervisorEventsAreLogged() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)
        val supervisor = ProcessSupervisor(store)

        balletMaster.defineStates<Steps, States>("supOp", "1.0.0") {
            state(States.DONE) { step(Steps.STEP_A) }
            defaultState(States.TODO)
        }

        val resolved = supervisor.resolveState<Steps, States>("supOp", "10").getOrThrow()
        assertEquals(States.TODO, resolved)

        val missing = supervisor.resolveState<Steps, States>("supOp", "10", versionPrefix = "9.")
        assertTrue(missing.isFailure)

        val saved = events("state.config_saved", Level.INFO)
        assertEquals(1, saved.size)
        assertEquals(BalletLoggers.STATE, saved[0].loggerName)
        assertEquals("supOp", saved[0].attrs()[BalletMdc.OPERATION])
        assertEquals("1.0.0", saved[0].attrs()["version"])

        val resolvedEvents = events("state.resolved", Level.DEBUG)
        assertEquals(1, resolvedEvents.size)
        assertEquals("TODO", resolvedEvents[0].attrs()["state"])
        assertNull(resolvedEvents[0].mdcPropertyMap[BalletMdc.OPERATION])
        assertEquals("supOp", resolvedEvents[0].attrs()[BalletMdc.OPERATION])
        assertEquals("10", resolvedEvents[0].attrs()[BalletMdc.OPERATION_ID])

        val notFound = events("state.config_not_found", Level.WARN)
        assertEquals(1, notFound.size)
        assertEquals("9.", notFound[0].attrs()["versionPrefix"])
    }

    @Test
    fun attributeFormattingEscapesTabsAndControlCharacters() {
        BalletLoggers.step.debugEvent("test.event") {
            attr("tab", "a\tb")
            attr("ansi", "\u001B[31mred")
            attr("del", "x\u007Fy")
            attr("sep", "l1\u2028l2\u2029l3")
            attr("crlf", "p\r\nq")
            attr("empty", "")
            attr("plain", "ok_123")
            attr("count", 42)
            attr("flag", true)
        }

        val attrs = events("test.event", Level.DEBUG).single().attrs()
        for (value in attrs.values.filterIsInstance<String>()) {
            assertFalse(value.contains('\t'))
            assertFalse(value.contains('\n'))
            assertFalse(value.contains('\r'))
            assertFalse(value.contains('\u001B'))
            assertFalse(value.contains('\u007F'))
            assertFalse(value.contains('\u2028'))
            assertFalse(value.contains('\u2029'))
        }

        assertEquals("a\\tb", attrs["tab"])
        assertEquals("\\u001b[31mred", attrs["ansi"])
        assertEquals("x\\u007fy", attrs["del"])
        assertEquals("l1\\u2028l2\\u2029l3", attrs["sep"])
        assertEquals("p\\r\\nq", attrs["crlf"])
        assertEquals("", attrs["empty"])
        assertEquals("ok_123", attrs["plain"])
        assertEquals(42, attrs["count"])
        assertEquals(true, attrs["flag"])
    }

    @Test
    fun failingAppenderDoesNotBreakExecutionOrLeakLease() {
        val throwingAppender = object : AppenderBase<ILoggingEvent>() {
            override fun doAppend(eventObject: ILoggingEvent) {
                throw RuntimeException("Simulated appender outage")
            }

            override fun append(eventObject: ILoggingEvent) = Unit
        }.apply { start() }

        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        sdkLogger.addAppender(throwingAppender)
        callContextManager.store("loggingFailOp", "op-fail-1", isReplay = false, attempt = 0, operationSuffix = "init")
        try {
            val chainResult = balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ -> "ok" }
                .checkPoint()
                .execute()
            assertEquals(Try.success("ok"), chainResult)

            val ctx = ContextImpl(store, { callContextManager.get() }, {}).apply {
                setStep(Steps.STEP_B)
                setLease(5_000L)
            }
            val directResult = ctx.execute { Try.success("step-b-ok") }
            assertEquals(Try.success("step-b-ok"), directResult)

            sdkLogger.detachAppender(throwingAppender)

            // Lease acquired under failing appender must have been released cleanly
            val nextCtx = ContextImpl(store, { callContextManager.get() }, {}).apply {
                setStep(Steps.STEP_B)
                setLease(5_000L)
            }
            val reacquireResult = nextCtx.execute { Try.success("reacquired") }
            assertEquals(Try.success("reacquired"), reacquireResult)
        } finally {
            sdkLogger.detachAppender(throwingAppender)
            throwingAppender.stop()
            callContextManager.clear()
        }
    }

    @Test
    fun failingToStringInMdcAndLogAttributesDoesNotBreakExecution() {
        val brokenSuffix = object {
            override fun toString(): String = throw IllegalStateException("suffix toString boom")
        }

        BalletLoggers.step.debugEvent("test.safe") {
            attr("bad", brokenSuffix)
            attr("good", "ok")
        }
        val safeAttrs = events("test.safe", Level.DEBUG).single().attrs()
        assertEquals("<toString_error:IllegalStateException>", safeAttrs["bad"])
        assertEquals("ok", safeAttrs["good"])

        MDC.put("traceId", "trace-safe")
        val call = CallContext("mdcFailOp", "id-1", isReplay = false, attempt = 0)
        val result = BalletMdc.withStepContext(call, Steps.STEP_A, brokenSuffix) {
            assertEquals("mdcFailOp", MDC.get(BalletMdc.OPERATION))
            assertEquals("STEP_A", MDC.get(BalletMdc.STEP))
            assertEquals("<toString_error:IllegalStateException>", MDC.get(BalletMdc.SUFFIX))
            "done"
        }

        assertEquals("done", result)
        assertEquals("trace-safe", MDC.get("traceId"))
        assertNull(MDC.get(BalletMdc.OPERATION))
        assertNull(MDC.get(BalletMdc.STEP))
    }

    @Test
    fun checkpointDoesNotLogSavedWhenStoreSetReturnsFalse() {
        val delegate = InMemoryStore()
        val store = object : Store by delegate {
            override fun set(key: String, value: Any?): Result<Boolean> =
                if (key.endsWith(":output")) Result.success(false) else delegate.set(key, value)
        }
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("ckptOp", "1", isReplay = false, attempt = 0)
        try {
            val result = balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ -> "ok" }
                .checkPoint()
                .execute()

            assertEquals(Try.success("ok"), result)
        } finally {
            callContextManager.clear()
        }

        assertTrue(events("step.checkpoint_saved").isEmpty())
        val notSaved = events("step.checkpoint_not_saved", Level.WARN)
        assertEquals(1, notSaved.size)
        assertEquals("STEP_A", notSaved[0].attrs()[BalletMdc.STEP])
    }

    @Test
    fun logTimersSkipNanoTimeWhenLoggingLevelDisabled() {
        sdkLogger.level = Level.INFO
        assertNull(BalletLoggers.step.startDebugTimer())
        assertNotNull(BalletLoggers.lock.startWarnTimer())
        assertNull(elapsedMillis(null))

        sdkLogger.level = Level.ERROR
        assertNull(BalletLoggers.lock.startWarnTimer())

        sdkLogger.level = Level.DEBUG
        val start = BalletLoggers.step.startDebugTimer()
        assertNotNull(start)
        assertNotNull(elapsedMillis(start))
    }

    @Test
    fun structuredEventsCarryUnifiedKeyValuesIndependentlyOfMdcAndRenderViaKvp() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("kvOp", "ord-kv-7", isReplay = false, attempt = 2, operationSuffix = "v1")
        try {
            val result = balletMaster
                .start()
                .step(Steps.STEP_A)
                .lease(2500L)
                .map { _, _ -> "ok" }
                .execute()
            assertEquals(Try.success("ok"), result)
        } finally {
            callContextManager.clear()
        }

        val started = events("step.started", Level.DEBUG).single()
        assertEquals("step.started", started.formattedMessage)

        val startedAttrs = started.attrs()
        assertEquals("kvOp", startedAttrs[BalletMdc.OPERATION])
        assertEquals("ord-kv-7", startedAttrs[BalletMdc.OPERATION_ID])
        assertEquals(2, startedAttrs[BalletMdc.ATTEMPT])
        assertEquals("v1", startedAttrs[BalletMdc.SUFFIX])
        assertEquals("STEP_A", startedAttrs[BalletMdc.STEP])
        assertEquals(false, startedAttrs[BalletMdc.REPLAY])
        assertEquals(2500L, startedAttrs["leaseMs"])
        assertEquals("2", started.mdcPropertyMap[BalletMdc.ATTEMPT])

        val completed = events("step.completed", Level.DEBUG).single()
        assertTrue(completed.attrs()["durationMs"] is Long)

        // Chain events carry operation-level MDC (without step) alongside structured KeyValuePairs
        val chainStarted = events("chain.started", Level.DEBUG).single()
        assertEquals("kvOp", chainStarted.mdcPropertyMap[BalletMdc.OPERATION])
        assertEquals("ord-kv-7", chainStarted.mdcPropertyMap[BalletMdc.OPERATION_ID])
        assertEquals("2", chainStarted.mdcPropertyMap[BalletMdc.ATTEMPT])
        assertEquals("v1", chainStarted.mdcPropertyMap[BalletMdc.SUFFIX])
        assertNull(chainStarted.mdcPropertyMap[BalletMdc.STEP])
        assertEquals("kvOp", chainStarted.attrs()[BalletMdc.OPERATION])
        assertEquals("ord-kv-7", chainStarted.attrs()[BalletMdc.OPERATION_ID])
        assertEquals(2, chainStarted.attrs()[BalletMdc.ATTEMPT])
        assertEquals("v1", chainStarted.attrs()[BalletMdc.SUFFIX])

        // Verify Logback %kvp pattern converter renders structured pairs alongside %msg
        val layout = PatternLayout().apply {
            context = sdkLogger.loggerContext
            pattern = "%-5level %msg %kvp"
            start()
        }
        try {
            val rendered = layout.doLayout(started)
            assertTrue(rendered.contains("step.started"))
            assertTrue(rendered.contains("${BalletMdc.OPERATION}=\"kvOp\""))
            assertTrue(rendered.contains("${BalletMdc.ATTEMPT}=\"2\""))
            assertTrue(rendered.contains("leaseMs=\"2500\""))
        } finally {
            layout.stop()
        }
    }

    @Test
    fun mdcSuffixRemainsConsistentWithStepScopeAfterMilestoneUntilNextStep() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("mileMdcOp", "m-1", isReplay = false, attempt = 0, operationSuffix = "init")
        try {
            val result = balletMaster
                .start()
                .step(Steps.STEP_A)
                .milestone { _, _ -> "phase-2" }
                .map { _, _ ->
                    appLogger.info("after-milestone-same-step")
                    "from-A"
                }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, input ->
                    appLogger.info("next-step")
                    "$input-B"
                }
                .execute()

            assertEquals(Try.success("from-A-B"), result)
        } finally {
            callContextManager.clear()
        }

        val milestoneEvent = events("step.milestone_updated", Level.DEBUG).single()
        assertEquals("init", milestoneEvent.attrs()[BalletMdc.SUFFIX])
        assertEquals("init", milestoneEvent.mdcPropertyMap[BalletMdc.SUFFIX])
        assertEquals("phase-2", milestoneEvent.attrs()["newSuffix"])

        val sameStepUserLog = appender.list.first { it.formattedMessage == "after-milestone-same-step" }
        assertEquals("init", sameStepUserLog.mdcPropertyMap[BalletMdc.SUFFIX])
        assertEquals("STEP_A", sameStepUserLog.mdcPropertyMap[BalletMdc.STEP])

        val checkpointEvent = events("step.checkpoint_saved", Level.DEBUG).single()
        assertEquals("init", checkpointEvent.attrs()[BalletMdc.SUFFIX])
        assertEquals("init", checkpointEvent.mdcPropertyMap[BalletMdc.SUFFIX])

        val completedA = events("step.completed", Level.DEBUG).first { it.attrs()[BalletMdc.STEP] == "STEP_A" }
        assertEquals("init", completedA.attrs()[BalletMdc.SUFFIX])
        assertEquals("init", completedA.mdcPropertyMap[BalletMdc.SUFFIX])

        val nextStepUserLog = appender.list.first { it.formattedMessage == "next-step" }
        assertEquals("phase-2", nextStepUserLog.mdcPropertyMap[BalletMdc.SUFFIX])
        assertEquals("STEP_B", nextStepUserLog.mdcPropertyMap[BalletMdc.STEP])

        val completedB = events("step.completed", Level.DEBUG).first { it.attrs()[BalletMdc.STEP] == "STEP_B" }
        assertEquals("phase-2", completedB.attrs()[BalletMdc.SUFFIX])
        assertEquals("phase-2", completedB.mdcPropertyMap[BalletMdc.SUFFIX])
    }

    @Test
    fun replaySwitchUpdatesMdcAndLogsBeforeFirstLiveStepBodyAndException() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("revRepOp", "r-1", isReplay = false, attempt = 0)
        balletMaster.start().step(Steps.STEP_A).map { _, _ -> "cached-A" }.checkPoint().execute()
        callContextManager.clear()

        callContextManager.store("revRepOp", "r-1", isReplay = true, attempt = 0)
        try {
            val res = balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ -> "unused" }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, _ ->
                    appLogger.info("first-live-body")
                    throw IllegalStateException("live-step-failure")
                }
                .execute()
            assertTrue(res.isFailure)
        } finally {
            callContextManager.clear()
        }

        val all = appender.list
        val cacheHitIdx = all.indexOfFirst { it.message == "replay.cache_hit" }
        val cacheMissIdx = all.indexOfFirst { it.message == "replay.cache_miss" }
        val switchedIdx = all.indexOfFirst { it.message == "replay.switched_to_live" }
        val userBodyIdx = all.indexOfFirst { it.formattedMessage == "first-live-body" }
        val exIdx = all.indexOfFirst { it.message == "step.exception" && it.attrs()["phase"] == "map" }

        assertTrue(cacheHitIdx in all.indices)
        assertTrue(cacheMissIdx > cacheHitIdx)
        assertTrue(switchedIdx > cacheMissIdx)
        assertTrue(userBodyIdx > switchedIdx)
        assertTrue(exIdx > userBodyIdx)

        assertEquals("true", all[cacheHitIdx].mdcPropertyMap[BalletMdc.REPLAY])
        assertEquals("true", all[cacheMissIdx].mdcPropertyMap[BalletMdc.REPLAY])
        assertEquals("false", all[switchedIdx].mdcPropertyMap[BalletMdc.REPLAY])
        assertEquals("false", all[userBodyIdx].mdcPropertyMap[BalletMdc.REPLAY])
        assertEquals("false", all[exIdx].mdcPropertyMap[BalletMdc.REPLAY])
    }

    @Test
    fun supervisorResolveStateReturnsFailureAndSkipsResolvedLogWhenStoreReadFails() {
        val delegate = InMemoryStore()
        val storeErr = RuntimeException("store read unavailable")
        val failingStore = object : Store by delegate {
            override fun <T : Any> get(key: String): Result<T?> =
                if (key.endsWith(":STEP_A:output")) Result.failure(storeErr) else delegate.get(key)
        }
        val balletMaster = BalletMaster(failingStore, CallContextManager())
        val supervisor = ProcessSupervisor(failingStore)

        balletMaster.defineStates<Steps, States>("supFailOp", "1.0.0") {
            state(States.DONE) { step(Steps.STEP_A) }
            defaultState(States.TODO)
        }

        val resolved = supervisor.resolveState<Steps, States>("supFailOp", "op-err-1")
        assertTrue(resolved.isFailure)
        assertEquals(storeErr, resolved.exceptionOrNull())
        assertTrue(events("state.resolved").isEmpty())

        val storeErrors = events("store.error", Level.ERROR)
        assertEquals(1, storeErrors.size)
        val attrs = storeErrors[0].attrs()
        assertEquals("supervisor_step_check", attrs["storeOp"])
        assertEquals("supFailOp", attrs[BalletMdc.OPERATION])
        assertEquals("op-err-1", attrs[BalletMdc.OPERATION_ID])
        assertEquals("STEP_A", attrs[BalletMdc.STEP])
        assertEquals("supFailOp:op-err-1:0::STEP_A:output", attrs["key"])
    }

    @Test
    fun stateConfigSaveReturnsFailureWithoutOverwritingVersionsOrLoggingSavedWhenGetVersionsFails() {
        val delegate = InMemoryStore()
        var failVersionsGet = false
        val readErr = RuntimeException("versions read failed")
        val store = object : Store by delegate {
            override fun <T : Any> get(key: String): Result<T?> =
                if (failVersionsGet && key == "config:cfgGetFail:versions") Result.failure(readErr)
                else delegate.get(key)
        }
        val repo = StoreStateConfigRepository(store)

        val v1 = processStates<Steps, States>("cfgGetFail", "1.0.0") {
            state(States.DONE) { step(Steps.STEP_A) }
            defaultState(States.TODO)
        }
        assertEquals(Result.success(true), repo.save(v1))

        val v2 = processStates<Steps, States>("cfgGetFail", "2.0.0") {
            state(States.DONE) { step(Steps.STEP_B) }
            defaultState(States.TODO)
        }
        failVersionsGet = true
        val v2Save = repo.save(v2)
        failVersionsGet = false

        assertTrue(v2Save.isFailure)
        assertEquals(readErr, v2Save.exceptionOrNull())
        assertEquals(listOf("1.0.0"), repo.getVersions("cfgGetFail").getOrThrow())

        val savedEvents = events("state.config_saved", Level.INFO)
        assertEquals(1, savedEvents.size)
        assertEquals("1.0.0", savedEvents[0].attrs()["version"])

        val storeErrors = events("store.error", Level.ERROR)
        assertEquals(1, storeErrors.size)
        assertEquals("state_versions_get", storeErrors[0].attrs()["storeOp"])
    }

    @Test
    fun stateConfigSaveReturnsFailureAndDoesNotLogSavedWhenSaveVersionsFails() {
        val delegate = InMemoryStore()
        val writeErr = RuntimeException("versions write failed")
        val store = object : Store by delegate {
            override fun set(key: String, value: Any?): Result<Boolean> =
                if (key == "config:cfgSaveFail:versions") Result.failure(writeErr)
                else delegate.set(key, value)
        }
        val repo = StoreStateConfigRepository(store)

        val config = processStates<Steps, States>("cfgSaveFail", "1.0.0") {
            state(States.DONE) { step(Steps.STEP_A) }
            defaultState(States.TODO)
        }
        val res = repo.save(config)

        assertTrue(res.isFailure)
        assertEquals(writeErr, res.exceptionOrNull())
        assertTrue(events("state.config_saved", Level.INFO).isEmpty())

        val storeErrors = events("store.error", Level.ERROR)
        assertEquals(1, storeErrors.size)
        assertEquals("state_versions_save", storeErrors[0].attrs()["storeOp"])
    }

    @Test
    fun supervisorResolveStateDoesNotCallToStringOrLeakFieldsForNonEnumState() {
        val store = InMemoryStore()
        val balletMaster = BalletMaster(store, CallContextManager())
        val supervisor = ProcessSupervisor(store)

        val secretState = CustomProcessState(status = "PENDING", customerSecret = "SECRET_PAN_4111")
        balletMaster.defineStates<Steps, CustomProcessState>("customStateOp", "1.0.0") {
            state(CustomProcessState("DONE", "OK")) { step(Steps.STEP_A) }
            defaultState(secretState)
        }

        val resolved = supervisor.resolveState<Steps, CustomProcessState>("customStateOp", "op-secret-1")
        assertEquals(Result.success(secretState), resolved)

        val resolvedEvent = events("state.resolved", Level.DEBUG).single()
        assertEquals("CustomProcessState", resolvedEvent.attrs()["state"])
        assertTrue(appender.list.none { event ->
            event.formattedMessage.contains("SECRET_PAN_4111") ||
                event.attrs().values.any { it?.toString()?.contains("SECRET_PAN_4111") == true }
        })
    }

    @Test
    fun chainEventsCarryConsistentMdcAndRestoreOuterMdcAfterExecution() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        MDC.put("traceId", "outer-trace")
        MDC.put(BalletMdc.STEP, "outer-step")

        callContextManager.store("chainMdcOp", "ch-1", isReplay = false, attempt = 0, operationSuffix = "init")
        try {
            val completedResult = balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ -> "a" }
                .milestone { _, _ -> "phase-2" }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, input -> "$input-b" }
                .execute()
            assertEquals(Try.success("a-b"), completedResult)
        } finally {
            callContextManager.clear()
        }

        val chainStarted = events("chain.started", Level.DEBUG).single { it.attrs()[BalletMdc.OPERATION] == "chainMdcOp" }
        assertEquals("outer-trace", chainStarted.mdcPropertyMap["traceId"])
        assertEquals("chainMdcOp", chainStarted.mdcPropertyMap[BalletMdc.OPERATION])
        assertEquals("ch-1", chainStarted.mdcPropertyMap[BalletMdc.OPERATION_ID])
        assertEquals("0", chainStarted.mdcPropertyMap[BalletMdc.ATTEMPT])
        assertEquals("init", chainStarted.mdcPropertyMap[BalletMdc.SUFFIX])
        assertEquals("false", chainStarted.mdcPropertyMap[BalletMdc.REPLAY])
        assertNull(chainStarted.mdcPropertyMap[BalletMdc.STEP])

        val chainCompleted = events("chain.completed", Level.DEBUG).single()
        assertEquals("outer-trace", chainCompleted.mdcPropertyMap["traceId"])
        assertEquals("chainMdcOp", chainCompleted.mdcPropertyMap[BalletMdc.OPERATION])
        assertEquals("ch-1", chainCompleted.mdcPropertyMap[BalletMdc.OPERATION_ID])
        assertEquals("phase-2", chainCompleted.mdcPropertyMap[BalletMdc.SUFFIX])
        assertEquals("phase-2", chainCompleted.attrs()[BalletMdc.SUFFIX])
        assertEquals("false", chainCompleted.mdcPropertyMap[BalletMdc.REPLAY])
        assertNull(chainCompleted.mdcPropertyMap[BalletMdc.STEP])

        assertEquals("outer-trace", MDC.get("traceId"))
        assertEquals("outer-step", MDC.get(BalletMdc.STEP))
        assertNull(MDC.get(BalletMdc.OPERATION))

        callContextManager.store("chainStopOp", "ch-2", isReplay = false, attempt = 0, operationSuffix = "init")
        balletMaster.start().step(Steps.STEP_A).map { _, _ -> "cached" }.checkPoint().execute()
        callContextManager.clear()

        callContextManager.store("chainStopOp", "ch-2", isReplay = true, attempt = 0, operationSuffix = "init")
        try {
            val stoppedResult = balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ -> "unused" }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, _ -> throw IllegalStateException("stop-here") }
                .execute()
            assertTrue(stoppedResult.isFailure)
        } finally {
            callContextManager.clear()
        }

        val chainStopped = events("chain.stopped", Level.DEBUG).single()
        assertEquals("chainStopOp", chainStopped.mdcPropertyMap[BalletMdc.OPERATION])
        assertEquals("ch-2", chainStopped.mdcPropertyMap[BalletMdc.OPERATION_ID])
        assertEquals("STEP_B", chainStopped.mdcPropertyMap[BalletMdc.STEP])
        assertEquals("STEP_B", chainStopped.attrs()[BalletMdc.STEP])
        assertEquals("init", chainStopped.mdcPropertyMap[BalletMdc.SUFFIX])
        assertEquals("false", chainStopped.mdcPropertyMap[BalletMdc.REPLAY])

        assertEquals("outer-trace", MDC.get("traceId"))
        assertEquals("outer-step", MDC.get(BalletMdc.STEP))
        assertNull(MDC.get(BalletMdc.OPERATION))
    }

    private fun <T> withFaultyMdcAdapter(
        wrap: (delegate: MDCAdapter) -> MDCAdapter,
        block: () -> T
    ): T {
        val field = MDC::class.java.getDeclaredField("MDC_ADAPTER").apply { isAccessible = true }
        val original = field.get(null) as MDCAdapter
        field.set(null, wrap(original))
        return try {
            block()
        } finally {
            field.set(null, original)
        }
    }

    @Test
    fun mdcDoesNotModifyContextWhenSnapshotPartiallyFailsOnRead() {
        MDC.put("traceId", "t-read")
        MDC.put(BalletMdc.OPERATION, "outer-op")
        MDC.put(BalletMdc.STEP, "outer-step")

        var writesAttempted = 0
        val call = CallContext("inner-op", "inner-1", isReplay = false, attempt = 2)

        val result = withFaultyMdcAdapter({ delegate ->
            object : MDCAdapter by delegate {
                override fun get(key: String?): String? {
                    if (key == BalletMdc.STEP) throw RuntimeException("mdc read fail")
                    return delegate.get(key)
                }

                override fun put(key: String?, value: String?) {
                    writesAttempted++
                    delegate.put(key, value)
                }

                override fun remove(key: String?) {
                    writesAttempted++
                    delegate.remove(key)
                }
            }
        }) {
            BalletMdc.withStepContext(call, Steps.STEP_A, "inner-suffix") {
                assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
                assertNull(MDC.get(BalletMdc.OPERATION_ID))
                "ok"
            }
        }

        assertEquals("ok", result)
        assertEquals(0, writesAttempted)
        assertEquals("t-read", MDC.get("traceId"))
        assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
        assertEquals("outer-step", MDC.get(BalletMdc.STEP))
        assertNull(MDC.get(BalletMdc.OPERATION_ID))
    }

    @Test
    fun mdcRollsBackPartialWritesWhenApplyContextPartiallyFails() {
        MDC.put("traceId", "t-write")
        MDC.put(BalletMdc.OPERATION, "outer-op")
        MDC.put(BalletMdc.STEP, "outer-step")

        val call = CallContext("inner-op", "inner-1", isReplay = false, attempt = 2)
        val result = withFaultyMdcAdapter({ delegate ->
            object : MDCAdapter by delegate {
                override fun put(key: String?, value: String?) {
                    if (key == BalletMdc.OPERATION_ID && value == "inner-1") {
                        throw RuntimeException("mdc write fail")
                    }
                    delegate.put(key, value)
                }
            }
        }) {
            BalletMdc.withStepContext(call, Steps.STEP_A, "inner-suffix") {
                assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
                assertEquals("outer-step", MDC.get(BalletMdc.STEP))
                assertNull(MDC.get(BalletMdc.OPERATION_ID))
                "done"
            }
        }

        assertEquals("done", result)
        assertEquals("t-write", MDC.get("traceId"))
        assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
        assertEquals("outer-step", MDC.get(BalletMdc.STEP))
        assertNull(MDC.get(BalletMdc.OPERATION_ID))
        assertNull(MDC.get(BalletMdc.ATTEMPT))
        assertNull(MDC.get(BalletMdc.SUFFIX))
        assertNull(MDC.get(BalletMdc.REPLAY))
    }

    @Test
    fun mdcRestoresRemainingKeysWhenRestorePartiallyFailsAndPreservesUserExceptions() {
        MDC.put("traceId", "t-restore")
        MDC.put(BalletMdc.OPERATION, "outer-op")

        val call = CallContext("inner-op", "inner-1", isReplay = true, attempt = 3)
        val userBoom = IllegalStateException("user business exception")

        val thrown = withFaultyMdcAdapter({ delegate ->
            object : MDCAdapter by delegate {
                override fun remove(key: String?) {
                    if (key == BalletMdc.OPERATION_ID) {
                        throw RuntimeException("mdc remove fail")
                    }
                    delegate.remove(key)
                }
            }
        }) {
            assertThrows(IllegalStateException::class.java) {
                BalletMdc.withStepContext(call, Steps.STEP_A, "s1") {
                    assertEquals("inner-op", MDC.get(BalletMdc.OPERATION))
                    assertEquals("inner-1", MDC.get(BalletMdc.OPERATION_ID))
                    assertEquals("STEP_A", MDC.get(BalletMdc.STEP))
                    throw userBoom
                }
            }
        }

        assertSame(userBoom, thrown)
        assertEquals("t-restore", MDC.get("traceId"))
        assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
        // Non-failing keys after OPERATION_ID must still be cleaned up
        assertNull(MDC.get(BalletMdc.ATTEMPT))
        assertNull(MDC.get(BalletMdc.SUFFIX))
        assertNull(MDC.get(BalletMdc.STEP))
        assertNull(MDC.get(BalletMdc.REPLAY))
        // Boundary: OPERATION_ID itself could not be removed because provider threw on remove
        assertEquals("inner-1", MDC.get(BalletMdc.OPERATION_ID))
    }

    @Test
    fun mdcNestedChainAndStepContextsRestoreProperly() {
        MDC.put("traceId", "t-nested")
        MDC.put(BalletMdc.STEP, "host-step")

        val outerCall = CallContext("outer-op", "o-1", isReplay = false, attempt = 0, operationSuffix = "s0")
        val innerCall = CallContext("inner-op", "i-2", isReplay = true, attempt = 1, operationSuffix = "s1")

        BalletMdc.withChainContext(outerCall) {
            assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
            assertNull(MDC.get(BalletMdc.STEP))

            BalletMdc.withStepContext(outerCall, Steps.STEP_A, "s0") {
                assertEquals("STEP_A", MDC.get(BalletMdc.STEP))

                BalletMdc.withStepContext(innerCall, Steps.STEP_B, "s1") {
                    assertEquals("inner-op", MDC.get(BalletMdc.OPERATION))
                    assertEquals("i-2", MDC.get(BalletMdc.OPERATION_ID))
                    assertEquals("STEP_B", MDC.get(BalletMdc.STEP))
                    assertEquals("true", MDC.get(BalletMdc.REPLAY))
                }

                assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
                assertEquals("STEP_A", MDC.get(BalletMdc.STEP))
                assertEquals("false", MDC.get(BalletMdc.REPLAY))
            }

            assertEquals("outer-op", MDC.get(BalletMdc.OPERATION))
            assertNull(MDC.get(BalletMdc.STEP))
        }

        assertEquals("t-nested", MDC.get("traceId"))
        assertEquals("host-step", MDC.get(BalletMdc.STEP))
        assertNull(MDC.get(BalletMdc.OPERATION))
    }
}
