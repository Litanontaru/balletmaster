package org.dmg.balletmaster.investigation

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.dmg.balletmaster.BalletMaster
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.loki.LokiClientConfig
import org.dmg.balletmaster.loki.LokiLogParser
import org.dmg.balletmaster.loki.LokiOperationObservationSource
import org.dmg.balletmaster.loki.LokiQueryClient
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.OperationQuery
import org.dmg.balletmaster.store.InMemoryStore
import org.dmg.balletmaster.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

/**
 * Full pipeline verification:
 * core (BalletMaster on INFO) -> Logback JsonEncoder -> mock Loki -> LokiOperationObservationSource -> OperationInvestigator.
 */
class InvestigationEndToEndTest {

    enum class Steps : StepId {
        RESERVE_FUNDS,
        CAPTURE_PAYMENT
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
        server.createContext("/loki/api/v1/query_range") { ex -> handler(ex) }
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

    private fun serveLines(lines: List<String>) {
        val baseTsNs = 1731880000000000000L
        val payload = mapOf(
            "status" to "success",
            "data" to mapOf(
                "resultType" to "streams",
                "result" to listOf(
                    mapOf(
                        "stream" to mapOf("app" to "payments", "environment" to "prod"),
                        "values" to lines.mapIndexed { idx, line ->
                            listOf((baseTsNs + idx * 1_000_000L).toString(), line)
                        }
                    )
                )
            )
        )
        val json = LokiLogParser.mapper.writeValueAsString(payload)
        handler = { ex ->
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun createInvestigator(): OperationInvestigator {
        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        return OperationInvestigator(LokiOperationObservationSource(client))
    }

    @Test
    fun endToEndDiagnosesStoreErrorFromRealCoreLogs() {
        val delegate = InMemoryStore()
        val failingStore = object : Store by delegate {
            override fun set(key: String, value: Any?): Result<Boolean> =
                if (key.endsWith(":output")) Result.failure(RuntimeException("redis connection reset"))
                else delegate.set(key, value)
        }
        val ccm = CallContextManager()
        val bm = BalletMaster(failingStore, ccm)

        ccm.store("payFlow", "pay-err-1", isReplay = false, attempt = 0)
        try {
            val res = bm.start()
                .step(Steps.RESERVE_FUNDS)
                .map { _, _ -> "reserved" }
                .checkPoint()
                .execute()
            assertTrue(res.isError)
        } finally {
            ccm.clear()
        }

        appender.stop()
        val lines = logOutput.toString(StandardCharsets.UTF_8).lines().map { it.trim() }.filter { it.isNotBlank() }
        serveLines(lines)

        val now = Instant.now()
        val report = createInvestigator().investigate(
            OperationQuery(
                app = "payments",
                environment = "prod",
                operationId = "pay-err-1",
                start = now.minusSeconds(60),
                end = now
            )
        )

        assertEquals("loki", report.provider)
        assertEquals(Completeness.COMPLETE, report.completeness)
        assertEquals(InvestigationStatus.INFRASTRUCTURE_ERROR, report.summaryStatus)
        assertEquals(FindingType.STORE_INFRASTRUCTURE_ERROR, report.findings.single().type)
        assertEquals(FindingSeverity.CRITICAL, report.findings.single().severity)
        assertTrue(report.findings.single().summary.contains("set_output"))
    }

    @Test
    fun endToEndDiagnosesLeaseRaceAndContentionFromRealCoreLogs() {
        val store = InMemoryStore()
        val ccm = CallContextManager()
        val bm = BalletMaster(store, ccm)

        val w1Entered = CountDownLatch(1)
        val w1Release = CountDownLatch(1)
        val w2Entered = CountDownLatch(1)
        val w2Release = CountDownLatch(1)

        val w1 = Thread {
            try {
                ccm.store("payFlow", "pay-race-1", isReplay = false, attempt = 0)
                bm.start().step(Steps.CAPTURE_PAYMENT).lease(40L).map { _, _ ->
                    w1Entered.countDown()
                    assertTrue(w1Release.await(5, TimeUnit.SECONDS))
                    "w1"
                }.checkPoint().execute()
            } finally {
                ccm.clear()
            }
        }
        w1.start()
        assertTrue(w1Entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(70)

        val w2 = Thread {
            try {
                ccm.store("payFlow", "pay-race-1", isReplay = false, attempt = 0)
                bm.start().step(Steps.CAPTURE_PAYMENT).lease(1000L).map { _, _ ->
                    w2Entered.countDown()
                    assertTrue(w2Release.await(5, TimeUnit.SECONDS))
                    "w2"
                }.checkPoint().execute()
            } finally {
                ccm.clear()
            }
        }
        w2.start()
        assertTrue(w2Entered.await(5, TimeUnit.SECONDS))

        w1Release.countDown()
        w1.join(5000)

        try {
            ccm.store("payFlow", "pay-race-1", isReplay = false, attempt = 0)
            val blocked = bm.start().step(Steps.CAPTURE_PAYMENT).lease(20L).map { _, _ -> "w3" }.execute()
            assertTrue(blocked.isState)
        } finally {
            ccm.clear()
            w2Release.countDown()
            w2.join(5000)
        }

        appender.stop()
        val lines = logOutput.toString(StandardCharsets.UTF_8).lines().map { it.trim() }.filter { it.isNotBlank() }
        serveLines(lines)

        val now = Instant.now()
        val report = createInvestigator().investigate(
            OperationQuery(
                app = "payments",
                environment = "prod",
                operationId = "pay-race-1",
                start = now.minusSeconds(60),
                end = now
            )
        )

        assertEquals(InvestigationStatus.MIXED_ANOMALIES, report.summaryStatus)
        val leaseFinding = report.findings.first { it.type == FindingType.LEASE_EXPIRY_RACE }
        assertEquals(Confidence.HIGH, leaseFinding.confidence)
        assertEquals("CAPTURE_PAYMENT", leaseFinding.step)
        assertTrue(report.findings.any { it.type == FindingType.LOCK_CONTENTION_BLOCKED })
    }
}
