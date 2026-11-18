package org.dmg.balletmaster.investigation.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.dmg.balletmaster.observation.BalletCorrelation
import org.dmg.balletmaster.observation.BalletObservation
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.ObservationBatch
import org.dmg.balletmaster.observation.ObservationSourceException
import org.dmg.balletmaster.observation.OperationObservationSource
import org.dmg.balletmaster.observation.OperationQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "balletmaster.investigation.security.allowed-apps=payments",
        "balletmaster.investigation.security.allowed-environments=prod",
        "balletmaster.investigation.security.allow-all-correlated=false",
        "balletmaster.investigation.security.max-window=6h",
        "balletmaster.investigation.security.max-concurrent-queries=1"
    ]
)
@AutoConfigureMockMvc
@Import(InvestigationControllerTest.StubSourceConfig::class)
class InvestigationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var stubSource: StubObservationSource

    class StubObservationSource : OperationObservationSource {
        override val providerName: String = "stub-provider"
        var lastQuery: OperationQuery? = null
        var behavior: (OperationQuery) -> ObservationBatch = { q ->
            ObservationBatch(
                provider = providerName,
                sourceQuery = "stub:${q.operationId}",
                observations = emptyList(),
                completeness = Completeness.COMPLETE
            )
        }

        override fun fetchByOperation(query: OperationQuery): ObservationBatch {
            lastQuery = query
            return behavior(query)
        }
    }

    @TestConfiguration
    class StubSourceConfig {
        @Bean
        @Primary
        fun stubClock(): Clock = Clock.fixed(Instant.parse("2026-11-17T22:00:00Z"), ZoneOffset.UTC)

        @Bean
        @Primary
        fun stubObservationSource(): StubObservationSource = StubObservationSource()
    }

    @Test
    fun returnsStructuredInvestigationReportJson() {
        val ts = Instant.parse("2026-11-17T21:30:00Z")
        stubSource.behavior = { q ->
            ObservationBatch(
                provider = "stub-provider",
                sourceQuery = "stub:${q.operationId}",
                observations = listOf(
                    BalletObservation(
                        timestampNs = ts.epochSecond * 1_000_000_000L,
                        timestamp = ts,
                        provider = "stub-provider",
                        rawLine = "{}",
                        logger = "org.dmg.balletmaster.step",
                        level = "INFO",
                        event = "step.exception",
                        correlation = BalletCorrelation(
                            operation = "orderFlow",
                            operationId = q.operationId,
                            attempt = 0,
                            step = "CHARGE_CARD"
                        ),
                        attributes = mapOf("phase" to "map", "exception" to "IllegalArgumentException")
                    )
                ),
                completeness = Completeness.COMPLETE
            )
        }

        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-42")
            param("windowMinutes", "30")
            param("operation", "orderFlow")
            param("step", "CHARGE_CARD")
            param("attempt", "0")
        }.andExpect {
            status { isOk() }
            jsonPath("$.operationId") { value("ord-42") }
            jsonPath("$.provider") { value("stub-provider") }
            jsonPath("$.summaryStatus") { value("FAILURE_DETECTED") }
            jsonPath("$.completeness") { value("COMPLETE") }
            jsonPath("$.findings[0].type") { value("BUSINESS_STEP_FAILURE") }
            jsonPath("$.findings[0].severity") { value("HIGH") }
            jsonPath("$.findings[0].step") { value("CHARGE_CARD") }
            jsonPath("$.limitations[0]") { exists() }
        }

        val captured = stubSource.lastQuery!!
        assertEquals(Instant.parse("2026-11-17T21:30:00Z"), captured.start)
        assertEquals(Instant.parse("2026-11-17T22:00:00Z"), captured.end)
        assertEquals("orderFlow", captured.operation)
        assertEquals("CHARGE_CARD", captured.step)
        assertEquals(0, captured.attempt)
    }

    @Test
    fun supportsExplicitIsoTimeRange() {
        stubSource.behavior = { q ->
            ObservationBatch(
                provider = "stub-provider",
                sourceQuery = "stub:${q.operationId}",
                observations = emptyList(),
                completeness = Completeness.COMPLETE
            )
        }

        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-empty")
            param("from", "2026-11-17T20:00:00Z")
            param("to", "2026-11-17T21:00:00Z")
        }.andExpect {
            status { isOk() }
            jsonPath("$.summaryStatus") { value("INCONCLUSIVE_NO_EVENTS") }
            jsonPath("$.windowStart") { value("2026-11-17T20:00:00Z") }
            jsonPath("$.windowEnd") { value("2026-11-17T21:00:00Z") }
        }
    }

    @Test
    fun returnsBadRequestForInvalidParameters() {
        // Missing required operationId
        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_REQUEST") }
        }

        // Only 'from' without 'to'
        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-1")
            param("from", "2026-11-17T20:00:00Z")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_REQUEST") }
        }

        // from > to
        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-1")
            param("from", "2026-11-17T22:00:00Z")
            param("to", "2026-11-17T21:00:00Z")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun returnsBadGatewayOnUpstreamObservationSourceFailureWithoutLeakingInternalDetails() {
        stubSource.behavior = {
            throw ObservationSourceException(
                provider = "stub-provider",
                message = "Connection refused to http://internal-loki:3100 with Bearer SECRET_TOKEN_123",
                statusCode = 503
            )
        }

        val mvcResult = mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-503")
        }.andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("UPSTREAM_OBSERVATION_SOURCE_ERROR") }
            jsonPath("$.provider") { value("stub-provider") }
        }.andReturn()

        val body = mvcResult.response.contentAsString
        assertFalse(body.contains("internal-loki"))
        assertFalse(body.contains("SECRET_TOKEN_123"))
    }

    @Test
    fun deniesDisallowedAppEnvironmentAndAllCorrelatedScopeWithAuditLog() {
        val auditLogger = LoggerFactory.getLogger("org.dmg.balletmaster.investigation.audit") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        auditLogger.addAppender(appender)
        try {
            // Disallowed app
            mockMvc.get("/api/v1/investigations") {
                param("app", "other-service")
                param("env", "prod")
                param("operationId", "ord-1")
                header("X-Forwarded-User", "alice")
                header("X-Request-Id", "req-1")
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.error") { value("ACCESS_DENIED") }
            }

            // Disallowed environment
            mockMvc.get("/api/v1/investigations") {
                param("app", "payments")
                param("env", "staging")
                param("operationId", "ord-1")
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.error") { value("ACCESS_DENIED") }
            }

            // Disallowed ALL_CORRELATED scope
            mockMvc.get("/api/v1/investigations") {
                param("app", "payments")
                param("env", "prod")
                param("operationId", "ord-1")
                param("scope", "ALL_CORRELATED")
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.error") { value("ACCESS_DENIED") }
            }

            assertEquals(3, appender.list.size)
            val firstAttrs = appender.list[0].keyValuePairs.associate { it.key to it.value }
            assertEquals("DENIED", firstAttrs["outcome"])
            assertEquals("alice", firstAttrs["subject"])
            assertEquals("req-1", firstAttrs["requestId"])
            assertEquals("other-service", firstAttrs["app"])
        } finally {
            auditLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun rejectsWindowExceedingServiceMaxWindowAndSanitizesTypeMismatch() {
        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-1")
            param("windowMinutes", "500") // > 6h (360m)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_REQUEST") }
        }

        val mismatchBody = mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-1")
            param("scope", "NOT_A_SCOPE")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("INVALID_REQUEST") }
        }.andReturn().response.contentAsString

        assertFalse(mismatchBody.contains("org.dmg.balletmaster"))
    }

    @Test
    fun enforcesConcurrencyLimitWith429AndReleasesSlotAfterCompletion() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        stubSource.behavior = { q ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            ObservationBatch(
                provider = "stub-provider",
                sourceQuery = "stub:${q.operationId}",
                observations = emptyList(),
                completeness = Completeness.COMPLETE
            )
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstFuture = executor.submit {
                mockMvc.get("/api/v1/investigations") {
                    param("app", "payments")
                    param("env", "prod")
                    param("operationId", "ord-slow")
                }.andExpect {
                    status { isOk() }
                }
            }

            assertTrue(entered.await(5, TimeUnit.SECONDS))

            // Second concurrent request exceeds max-concurrent-queries=1 -> 429
            mockMvc.get("/api/v1/investigations") {
                param("app", "payments")
                param("env", "prod")
                param("operationId", "ord-second")
            }.andExpect {
                status { isTooManyRequests() }
                jsonPath("$.error") { value("TOO_MANY_REQUESTS") }
            }

            release.countDown()
            firstFuture.get(5, TimeUnit.SECONDS)

            // Once first completes, slot is released and subsequent request succeeds
            stubSource.behavior = { q ->
                ObservationBatch("stub-provider", "stub:${q.operationId}", emptyList(), Completeness.COMPLETE)
            }
            mockMvc.get("/api/v1/investigations") {
                param("app", "payments")
                param("env", "prod")
                param("operationId", "ord-after")
            }.andExpect {
                status { isOk() }
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun exposesActuatorHealthEndpoint() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }
}
