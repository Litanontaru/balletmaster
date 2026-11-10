package org.dmg.balletmaster

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.dmg.balletmaster.context.CallContext
import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.context.ContextImpl
import org.dmg.balletmaster.logging.BalletLoggers
import org.dmg.balletmaster.logging.BalletMdc
import org.dmg.balletmaster.logging.buildEventMessage
import org.dmg.balletmaster.state.ProcessSupervisor
import org.dmg.balletmaster.state.resolveState
import org.dmg.balletmaster.store.InMemoryStore
import org.dmg.balletmaster.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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

    private fun events(prefix: String? = null, level: Level? = null): List<ILoggingEvent> =
        appender.list.filter { event ->
            (prefix == null || event.formattedMessage.startsWith("[$prefix]")) &&
                (level == null || event.level == level)
        }

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
        assertTrue(startedChain[0].formattedMessage.contains("stepsCount=2"))
        assertTrue(startedChain[0].formattedMessage.contains("steps=STEP_A,STEP_B"))

        val startedSteps = events("step.started", Level.DEBUG)
        assertEquals(2, startedSteps.size)
        assertTrue(startedSteps.any { it.formattedMessage.contains("op=orderFlow") && it.formattedMessage.contains("id=ord-1") && it.formattedMessage.contains("step=STEP_A") })
        assertTrue(startedSteps.any { it.formattedMessage.contains("step=STEP_B") })

        val checkpoints = events("step.checkpoint_saved", Level.DEBUG)
        assertEquals(2, checkpoints.size)

        val completedSteps = events("step.completed", Level.DEBUG)
        assertEquals(2, completedSteps.size)
        assertTrue(completedSteps.all { it.formattedMessage.contains("outcome=SUCCESS") })

        val completedChain = events("chain.completed", Level.DEBUG)
        assertEquals(1, completedChain.size)

        // Sensitive payloads must never leak into system logs
        assertTrue(appender.list.none { it.formattedMessage.contains(secretInput) })
        assertTrue(appender.list.none { it.formattedMessage.contains(secretOutput) })
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
        assertTrue(milestoneEvents[0].formattedMessage.contains("newSuffix=phase-2"))

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
            assertTrue(idempotentSkip[0].formattedMessage.contains("step=STEP_B"))

            val stopped = events("chain.stopped", Level.DEBUG)
            assertTrue(stopped.any { it.formattedMessage.contains("outcome=STATE") && it.formattedMessage.contains("status=SKIP") })
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
        assertTrue(hits[0].formattedMessage.contains("step=STEP_A"))

        val misses = events("replay.cache_miss", Level.DEBUG)
        assertEquals(1, misses.size)
        assertTrue(misses[0].formattedMessage.contains("step=STEP_B"))

        val switched = events("replay.switched_to_live", Level.INFO)
        assertEquals(1, switched.size)
        assertEquals(BalletLoggers.REPLAY, switched[0].loggerName)
        assertTrue(switched[0].formattedMessage.contains("step=STEP_B"))
    }

    @Test
    fun stepExceptionLogsWarnWithCauseAndStopsChain() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        callContextManager.store("failOp", "88", isReplay = false, attempt = 0)
        try {
            val res = balletMaster
                .start()
                .step(Steps.STEP_A)
                .map { _, _ -> throw IllegalArgumentException("bad-order") }
                .checkPoint()
                .step(Steps.STEP_B)
                .map { _, _ -> "never" }
                .execute()
            assertTrue(res.isFailure)
        } finally {
            callContextManager.clear()
        }

        val exEvents = events("step.exception", Level.WARN)
        assertEquals(1, exEvents.size)
        assertTrue(exEvents[0].formattedMessage.contains("phase=map"))
        assertTrue(exEvents[0].formattedMessage.contains("step=STEP_A"))
        assertNotNull(exEvents[0].throwableProxy)
        assertEquals(StepExecutionException::class.java.name, exEvents[0].throwableProxy.className)

        val stopped = events("chain.stopped", Level.DEBUG)
        assertEquals(1, stopped.size)
        assertTrue(stopped[0].formattedMessage.contains("outcome=FAILURE"))
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
        assertTrue(storeErrors[0].formattedMessage.contains("storeOp=set_input"))
        assertTrue(storeErrors[0].formattedMessage.contains("op=errOp"))
        assertTrue(storeErrors[0].formattedMessage.contains("id=99"))
        assertNotNull(storeErrors[0].throwableProxy)

        val stopped = events("chain.stopped", Level.DEBUG)
        assertEquals(1, stopped.size)
        assertTrue(stopped[0].formattedMessage.contains("outcome=ERROR"))
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
        assertEquals(1, events("lock.blocked", Level.WARN).size)
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
        assertTrue(saved[0].formattedMessage.contains("op=supOp"))
        assertTrue(saved[0].formattedMessage.contains("version=1.0.0"))

        val resolvedEvents = events("state.resolved", Level.DEBUG)
        assertEquals(1, resolvedEvents.size)
        assertTrue(resolvedEvents[0].formattedMessage.contains("state=TODO"))

        val notFound = events("state.config_not_found", Level.WARN)
        assertEquals(1, notFound.size)
        assertTrue(notFound[0].formattedMessage.contains("versionPrefix=9."))
    }

    @Test
    fun attributeFormattingEscapesTabsAndControlCharacters() {
        val msg = buildEventMessage("test.event") {
            attr("tab", "a\tb")
            attr("ansi", "\u001B[31mred")
            attr("del", "x\u007Fy")
            attr("sep", "l1\u2028l2\u2029l3")
            attr("crlf", "p\r\nq")
            attr("empty", "")
            attr("plain", "ok_123")
        }

        assertFalse(msg.contains('\t'))
        assertFalse(msg.contains('\n'))
        assertFalse(msg.contains('\r'))
        assertFalse(msg.contains('\u001B'))
        assertFalse(msg.contains('\u007F'))
        assertFalse(msg.contains('\u2028'))
        assertFalse(msg.contains('\u2029'))

        assertTrue(msg.contains("tab=\"a\\tb\""))
        assertTrue(msg.contains("ansi=\"\\u001b[31mred\""))
        assertTrue(msg.contains("del=\"x\\u007fy\""))
        assertTrue(msg.contains("sep=\"l1\\u2028l2\\u2029l3\""))
        assertTrue(msg.contains("crlf=\"p\\r\\nq\""))
        assertTrue(msg.contains("empty=\"\""))
        assertTrue(msg.contains("plain=ok_123"))
    }
}
