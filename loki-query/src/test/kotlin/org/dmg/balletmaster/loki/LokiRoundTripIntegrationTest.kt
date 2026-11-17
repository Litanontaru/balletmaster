package org.dmg.balletmaster.loki

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.dmg.balletmaster.BalletMaster
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LokiRoundTripIntegrationTest {

    enum class Steps : StepId {
        CHARGE_CARD,
        SEND_RECEIPT
    }

    private lateinit var server: HttpServer
    private var port: Int = 0
    private var handler: (HttpExchange) -> Unit = {}

    private lateinit var loggerContext: LoggerContext
    private lateinit var sdkLogger: Logger
    private lateinit var logOutput: ByteArrayOutputStream
    private lateinit var appender: OutputStreamAppender<ILoggingEvent>
    private var previousLevel: Level? = null

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/loki/api/v1/query_range") { exchange ->
            try {
                handler(exchange)
            } catch (e: Exception) {
                val err = e.message?.toByteArray() ?: byteArrayOf()
                exchange.sendResponseHeaders(500, err.size.toLong())
                exchange.responseBody.write(err)
                exchange.close()
            }
        }
        server.start()

        loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        sdkLogger = loggerContext.getLogger("org.dmg.balletmaster")
        previousLevel = sdkLogger.level
        sdkLogger.level = Level.INFO

        val encoder = JsonEncoder().apply {
            context = loggerContext
            start()
        }

        logOutput = ByteArrayOutputStream()
        appender = OutputStreamAppender<ILoggingEvent>().apply {
            context = loggerContext
            this.encoder = encoder
            setOutputStream(logOutput)
            start()
        }
        sdkLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        sdkLogger.detachAppender(appender)
        appender.stop()
        sdkLogger.level = previousLevel
        server.stop(0)
    }

    private fun respondJson(exchange: HttpExchange, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun buildLokiStreamPayload(lines: List<String>, baseTsNs: Long = 1731429500000000000L): String {
        val mapper = LokiLogParser.mapper
        val values = lines.mapIndexed { idx, line ->
            listOf((baseTsNs + idx * 1000000L).toString(), line)
        }
        val streamObj = mapOf(
            "stream" to mapOf("app" to "order-service", "environment" to "prod"),
            "values" to values
        )
        val payload = mapOf(
            "status" to "success",
            "data" to mapOf(
                "resultType" to "streams",
                "result" to listOf(streamObj)
            )
        )
        return mapper.writeValueAsString(payload)
    }

    @Test
    fun roundTripScenario1FailureChainAndStepExceptionOnInfoLevel() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        val sensitiveError = "CARD_LIMIT_EXCEEDED_SENSITIVE"
        callContextManager.store("orderFlow", "ord-100", isReplay = false, attempt = 0)
        try {
            val result = balletMaster.start()
                .step(Steps.CHARGE_CARD)
                .map { _, _ -> throw IllegalArgumentException(sensitiveError) }
                .step(Steps.SEND_RECEIPT)
                .map { _, _ -> "receipt-ok" }
                .execute()

            assertTrue(result.isFailure)
        } finally {
            callContextManager.clear()
        }

        appender.stop()
        val rawLogs = logOutput.toString(StandardCharsets.UTF_8)
        val lines = rawLogs.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Must contain step.exception and chain.stopped on INFO, and must NOT contain sensitive message in system logs
        assertEquals(2, lines.size)
        assertFalse(rawLogs.contains(sensitiveError))

        handler = { ex ->
            respondJson(ex, buildLokiStreamPayload(lines))
        }

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("order-service", "prod", "ord-100")
        val now = Instant.now()
        val queryResult = client.queryRange(query, now.minusSeconds(60), now)

        assertEquals(Completeness.COMPLETE, queryResult.completeness)
        assertEquals(2, queryResult.observations.size)

        // 1. step.exception
        val exObs = queryResult.observations[0]
        assertEquals("step.exception", exObs.event)
        assertEquals("INFO", exObs.level)
        assertEquals("CHARGE_CARD", exObs.correlation?.step)
        assertEquals("ord-100", exObs.correlation?.operationId)
        assertEquals("IllegalArgumentException", exObs.attributes["exception"])
        assertEquals("map", exObs.attributes["phase"])

        // 2. chain.stopped
        val stopObs = queryResult.observations[1]
        assertEquals("chain.stopped", stopObs.event)
        assertEquals("INFO", stopObs.level)
        assertEquals("FAILURE", stopObs.attributes["outcome"])
        assertEquals("1", stopObs.attributes["executedSteps"]?.toString())
        assertEquals("2", stopObs.attributes["totalSteps"]?.toString())
        assertNotNull(stopObs.attributes["durationMs"])
    }

    @Test
    fun roundTripScenario2LockBlockedAndContentionOnInfoLevel() {
        val store = InMemoryStore()
        val callContextManager = CallContextManager()
        val balletMaster = BalletMaster(store, callContextManager)

        val stepAEntered = CountDownLatch(1)
        val releaseWorker1 = CountDownLatch(1)
        val worker2Entered = CountDownLatch(1)
        val releaseWorker2 = CountDownLatch(1)

        val worker1 = Thread {
            try {
                callContextManager.store("lockFlow", "lock-55", isReplay = false, attempt = 0)
                balletMaster.start()
                    .step(Steps.CHARGE_CARD)
                    .lease(40L)
                    .map { _, _ ->
                        stepAEntered.countDown()
                        assertTrue(releaseWorker1.await(5, TimeUnit.SECONDS))
                        "worker1-done"
                    }
                    .checkPoint()
                    .execute()
            } finally {
                callContextManager.clear()
            }
        }
        worker1.start()
        assertTrue(stepAEntered.await(5, TimeUnit.SECONDS))

        // Wait until worker1's 40ms lease expires
        Thread.sleep(70)

        // Worker 2 takes over the expired lock -> triggers lock.stolen_expired (WARN)
        val worker2 = Thread {
            try {
                callContextManager.store("lockFlow", "lock-55", isReplay = false, attempt = 0)
                balletMaster.start()
                    .step(Steps.CHARGE_CARD)
                    .lease(1000L)
                    .map { _, _ ->
                        worker2Entered.countDown()
                        assertTrue(releaseWorker2.await(5, TimeUnit.SECONDS))
                        "worker2-done"
                    }
                    .checkPoint()
                    .execute()
            } finally {
                callContextManager.clear()
            }
        }
        worker2.start()
        assertTrue(worker2Entered.await(5, TimeUnit.SECONDS))

        // Release worker1 -> worker1 attempts to unlock its stale expired lease -> triggers lock.stale_unlock_skipped (WARN)
        releaseWorker1.countDown()
        worker1.join(5000)

        // Worker 3 attempts to acquire occupied lock with 20ms lease -> triggers lock.blocked (INFO) and chain.stopped (INFO, outcome=STATE, status=BLOCKED)
        try {
            callContextManager.store("lockFlow", "lock-55", isReplay = false, attempt = 0)
            val blockedResult = balletMaster.start()
                .step(Steps.CHARGE_CARD)
                .lease(20L)
                .map { _, _ -> "worker3-should-block" }
                .execute()
            assertTrue(blockedResult.isState)
        } finally {
            callContextManager.clear()
            releaseWorker2.countDown()
            worker2.join(5000)
        }

        appender.stop()
        val rawLogs = logOutput.toString(StandardCharsets.UTF_8)
        val lines = rawLogs.lines().map { it.trim() }.filter { it.isNotBlank() }

        handler = { ex ->
            respondJson(ex, buildLokiStreamPayload(lines))
        }

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("order-service", "prod", "lock-55")
        val now = Instant.now()
        val queryResult = client.queryRange(query, now.minusSeconds(60), now)

        assertEquals(Completeness.COMPLETE, queryResult.completeness)
        assertTrue(queryResult.observations.size >= 4)

        val blockedObs = queryResult.observations.first { it.event == "lock.blocked" }
        assertEquals("INFO", blockedObs.level)
        assertEquals("BLOCKED", blockedObs.attributes["status"])
        assertEquals("CHARGE_CARD", blockedObs.correlation?.step)

        val chainStopObs = queryResult.observations.first { it.event == "chain.stopped" }
        assertEquals("INFO", chainStopObs.level)
        assertEquals("STATE", chainStopObs.attributes["outcome"])
        assertEquals("BLOCKED", chainStopObs.attributes["status"])

        val stolenObs = queryResult.observations.first { it.event == "lock.stolen_expired" }
        assertEquals("WARN", stolenObs.level)
        assertEquals("CHARGE_CARD", stolenObs.correlation?.step)

        val staleUnlockObs = queryResult.observations.first { it.event == "lock.stale_unlock_skipped" }
        assertEquals("WARN", staleUnlockObs.level)
    }
}
