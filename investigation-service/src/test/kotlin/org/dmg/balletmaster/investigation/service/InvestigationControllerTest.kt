package org.dmg.balletmaster.investigation.service

import org.dmg.balletmaster.observation.BalletCorrelation
import org.dmg.balletmaster.observation.BalletObservation
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.ObservationBatch
import org.dmg.balletmaster.observation.ObservationSourceException
import org.dmg.balletmaster.observation.OperationObservationSource
import org.dmg.balletmaster.observation.OperationQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@SpringBootTest
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
    fun returnsBadGatewayOnUpstreamObservationSourceFailure() {
        stubSource.behavior = {
            throw ObservationSourceException(
                provider = "stub-provider",
                message = "Upstream Loki unavailable (HTTP 503)",
                statusCode = 503
            )
        }

        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-503")
        }.andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("UPSTREAM_OBSERVATION_SOURCE_ERROR") }
            jsonPath("$.provider") { value("stub-provider") }
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
