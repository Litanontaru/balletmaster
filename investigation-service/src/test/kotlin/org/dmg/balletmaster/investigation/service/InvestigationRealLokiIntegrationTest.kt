package org.dmg.balletmaster.investigation.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import org.dmg.balletmaster.BalletMaster
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.investigation.Confidence
import org.dmg.balletmaster.investigation.FindingKind
import org.dmg.balletmaster.investigation.FindingType
import org.dmg.balletmaster.investigation.InvestigationReport
import org.dmg.balletmaster.investigation.InvestigationStatus
import org.dmg.balletmaster.investigation.OperationInvestigator
import org.dmg.balletmaster.loki.LokiClientConfig
import org.dmg.balletmaster.loki.LokiOperationObservationSource
import org.dmg.balletmaster.loki.LokiQueryClient
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.OperationQuery
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * End-to-end integration test against a real Grafana Loki instance.
 *
 * Exercises the complete real cycle:
 * 1. Executes real BalletMaster workflow emitting structured JSON logs via Logback.
 * 2. Ingests target and distractor events into real Loki via `/loki/api/v1/push`.
 * 3. Builds and executes queries through [LokiOperationObservationSource] on real Loki.
 * 4. Validates that exact `operationId` filtering separates target from distractor events.
 * 5. Runs [OperationInvestigator] and the Spring Boot REST endpoint [InvestigationController].
 * 6. Validates the resulting [InvestigationReport] findings, confidence, and scope isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvestigationRealLokiIntegrationTest {

    enum class Steps : StepId {
        AUTHORIZE_PAYMENT,
        CAPTURE_PAYMENT
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("balletmaster.investigation.provider") { "loki" }
            registry.add("balletmaster.investigation.loki.base-url") {
                RealLokiTestHelper.resolveLokiUri()?.toString() ?: "http://127.0.0.1:3100"
            }
            registry.add("balletmaster.investigation.security.allowed-apps") { "checkout-service" }
            registry.add("balletmaster.investigation.security.allowed-environments") { "prod" }
        }
    }

    @Test
    fun realLokiCycleDiagnosesBusinessStepFailureAndEnforcesExactOperationIdFiltering() {
        val lokiBaseUrl = RealLokiTestHelper.getOrStartLoki()

        val app = "checkout-service"
        val env = "prod"
        val uniqueSuffix = System.currentTimeMillis()
        val targetOpId = "chk-real-$uniqueSuffix"
        val distractorOpId = "chk-real-${uniqueSuffix}00" // Substring collision

        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val sdkLogger = loggerContext.getLogger("org.dmg.balletmaster")
        val previousLevel = sdkLogger.level
        sdkLogger.level = Level.INFO

        val encoder = JsonEncoder().apply {
            context = loggerContext
            start()
        }
        val output = ByteArrayOutputStream()
        val appender = OutputStreamAppender<ILoggingEvent>().apply {
            context = loggerContext
            this.encoder = encoder
            setOutputStream(output)
            start()
        }
        sdkLogger.addAppender(appender)

        val ccm = CallContextManager()
        val bm = BalletMaster(InMemoryStore(), ccm)

        try {
            // 1. Execute target operation failing with business exception
            ccm.store("checkoutFlow", targetOpId, isReplay = false, attempt = 0)
            val result = bm.start()
                .step(Steps.AUTHORIZE_PAYMENT)
                .map { _, _ -> throw IllegalStateException("INSUFFICIENT_FUNDS_BUSINESS_ERROR") }
                .execute()
            assertTrue(result.isFailure)

            // 2. Execute distractor operation on same app/env failing with a different exception
            ccm.store("checkoutFlow", distractorOpId, isReplay = false, attempt = 0)
            val distractorResult = bm.start()
                .step(Steps.CAPTURE_PAYMENT)
                .map { _, _ -> throw IllegalArgumentException("DISTRACTOR_ERROR") }
                .execute()
            assertTrue(distractorResult.isFailure)
        } finally {
            ccm.clear()
            sdkLogger.detachAppender(appender)
            appender.stop()
            sdkLogger.level = previousLevel
        }

        val allLines = output.toString(StandardCharsets.UTF_8).lines().map { it.trim() }.filter { it.isNotBlank() }
        val targetLines = allLines.filter { it.contains(targetOpId) }
        val distractorLines = allLines.filter { it.contains(distractorOpId) }

        assertEquals(2, targetLines.size, "Expected 2 target log lines (step.exception + chain.stopped)")
        assertEquals(2, distractorLines.size, "Expected 2 distractor log lines")

        val now = Instant.now()
        val baseTsNs = (now.toEpochMilli() - 10_000) * 1_000_000L

        // Ingest into real Loki via push API
        val targetEntries = targetLines.mapIndexed { idx, line -> (baseTsNs + (idx + 1) * 10_000_000L) to line }
        val distractorEntries = distractorLines.mapIndexed { idx, line -> (baseTsNs + (idx + 3) * 10_000_000L) to line }
        val streamLabels = mapOf("app" to app, "environment" to env)
        RealLokiTestHelper.pushLogs(lokiBaseUrl, streamLabels, targetEntries + distractorEntries)

        val query = OperationQuery(
            app = app,
            environment = env,
            operationId = targetOpId,
            start = now.minusSeconds(60),
            end = now.plusSeconds(30)
        )

        // 3. Test through direct OperationInvestigator on real Loki
        val client = LokiQueryClient(LokiClientConfig(baseUrl = lokiBaseUrl))
        val source = LokiOperationObservationSource(client)
        val investigator = OperationInvestigator(source)

        // Wait until real Loki indexes and returns the target batch
        var report: InvestigationReport? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val candidate = investigator.investigate(query)
            if (candidate.timelineCount >= 2) {
                report = candidate
                break
            }
            Thread.sleep(250)
        }

        assertNotNull(report, "Timed out waiting for real Loki to return observations")
        assertEquals("loki", report!!.provider)
        assertEquals(targetOpId, report.operationId)
        assertEquals(Completeness.COMPLETE, report.completeness)
        assertEquals(InvestigationStatus.FAILURE_DETECTED, report.summaryStatus)

        // Verify exact operationId filtering: strictly 2 observations for targetOpId, none for distractorOpId!
        assertEquals(2, report.timelineCount)
        val finding = report.findings.first { it.type == FindingType.BUSINESS_STEP_FAILURE }
        assertEquals(FindingKind.CORRELATED_HYPOTHESIS, finding.kind)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertEquals("AUTHORIZE_PAYMENT", finding.step)
        assertEquals(0, finding.attempt)
        assertEquals("", finding.suffix)
        assertEquals(targetOpId, finding.operationId)
        assertTrue(finding.correlationComplete)
        assertFalse(finding.summary.contains("DISTRACTOR_ERROR"))
        assertTrue(finding.summary.contains("IllegalStateException"))

        // 4. Test through Spring Boot REST endpoint with real Loki backend
        mockMvc.get("/api/v1/investigations") {
            param("app", app)
            param("env", env)
            param("operationId", targetOpId)
            param("windowMinutes", "10")
        }.andExpect {
            status { isOk() }
            jsonPath("$.operationId") { value(targetOpId) }
            jsonPath("$.provider") { value("loki") }
            jsonPath("$.summaryStatus") { value("FAILURE_DETECTED") }
            jsonPath("$.completeness") { value("COMPLETE") }
            jsonPath("$.timelineCount") { value(2) }
            jsonPath("$.findings[0].type") { value("BUSINESS_STEP_FAILURE") }
            jsonPath("$.findings[0].kind") { value("CORRELATED_HYPOTHESIS") }
            jsonPath("$.findings[0].step") { value("AUTHORIZE_PAYMENT") }
            jsonPath("$.findings[0].confidence") { value("HIGH") }
            jsonPath("$.findings[0].correlationComplete") { value(true) }
        }
    }
}
