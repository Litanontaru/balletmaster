package org.dmg.balletmaster.investigation

import org.dmg.balletmaster.observation.BalletCorrelation
import org.dmg.balletmaster.observation.BalletObservation
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.ObservationBatch
import org.dmg.balletmaster.observation.OperationObservationSource
import org.dmg.balletmaster.observation.OperationQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ObservationAnalyzerTest {

    private val baseStart = Instant.parse("2026-11-17T20:00:00Z")
    private val baseEnd = Instant.parse("2026-11-17T21:00:00Z")

    private fun query(opId: String = "ord-1") = OperationQuery(
        app = "payments",
        environment = "prod",
        operationId = opId,
        start = baseStart,
        end = baseEnd
    )

    private fun obs(
        tsOffsetSec: Long,
        event: String?,
        level: String? = "INFO",
        logger: String? = "org.dmg.balletmaster.step",
        step: String? = "CHARGE_CARD",
        attributes: Map<String, Any?> = emptyMap(),
        parseError: String? = null
    ) = BalletObservation(
        timestampNs = (baseStart.epochSecond + tsOffsetSec) * 1_000_000_000L,
        timestamp = baseStart.plusSeconds(tsOffsetSec),
        provider = "fake-provider",
        rawLine = "{}",
        logger = logger,
        level = level,
        event = event,
        correlation = BalletCorrelation(
            operation = "orderFlow",
            operationId = "ord-1",
            attempt = 0,
            suffix = "",
            step = step,
            replay = false
        ),
        attributes = attributes,
        parseError = parseError
    )

    private fun batch(
        observations: List<BalletObservation>,
        completeness: Completeness = Completeness.COMPLETE,
        truncationReason: String? = null
    ) = ObservationBatch(
        provider = "fake-provider",
        sourceQuery = "fake-query",
        observations = observations,
        completeness = completeness,
        truncationReason = truncationReason
    )

    @Test
    fun emptyBatchProducesInconclusiveReportWithInfoSilenceLimitation() {
        val report = ObservationAnalyzer.analyze(query(), batch(emptyList()))

        assertEquals(InvestigationStatus.INCONCLUSIVE_NO_EVENTS, report.summaryStatus)
        assertEquals(1, report.findings.size)
        assertEquals(FindingType.NO_EVENTS_INCONCLUSIVE, report.findings.single().type)
        assertEquals(Confidence.LOW, report.findings.single().confidence)
        assertTrue(report.limitations.any { it.contains("INFO") })
        assertTrue(report.limitations.any { it.contains("Store") })
    }

    @Test
    fun diagnosesBusinessStepFailure() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "step.exception", "INFO", attributes = mapOf("phase" to "map", "exception" to "IllegalArgumentException")),
                    obs(2, "chain.stopped", "INFO", attributes = mapOf("outcome" to "FAILURE", "executedSteps" to 1, "totalSteps" to 3, "durationMs" to 15))
                )
            )
        )

        assertEquals(InvestigationStatus.FAILURE_DETECTED, report.summaryStatus)
        val finding = report.findings.single()
        assertEquals(FindingType.BUSINESS_STEP_FAILURE, finding.type)
        assertEquals(FindingSeverity.HIGH, finding.severity)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertEquals("CHARGE_CARD", finding.step)
        assertEquals(2, finding.evidence.size)
        assertTrue(finding.summary.contains("IllegalArgumentException"))
    }

    @Test
    fun diagnosesStoreInfrastructureError() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "store.error", "ERROR", logger = "org.dmg.balletmaster.store", attributes = mapOf("storeOp" to "set_output", "key" to "orderFlow:ord-1:0::CHARGE_CARD:output")),
                    obs(2, "chain.stopped", "INFO", attributes = mapOf("outcome" to "ERROR"))
                )
            )
        )

        assertEquals(InvestigationStatus.INFRASTRUCTURE_ERROR, report.summaryStatus)
        val finding = report.findings.single()
        assertEquals(FindingType.STORE_INFRASTRUCTURE_ERROR, finding.type)
        assertEquals(FindingSeverity.CRITICAL, finding.severity)
        assertTrue(finding.summary.contains("set_output"))
    }

    @Test
    fun diagnosesLeaseExpiryRaceWithHighConfidenceWhenBothEventsPresent() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", attributes = mapOf("leaseMs" to 500)),
                    obs(2, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", attributes = mapOf("expectedLeaseUntil" to 123456L))
                )
            )
        )

        assertEquals(InvestigationStatus.LEASE_RACE_DETECTED, report.summaryStatus)
        val finding = report.findings.single()
        assertEquals(FindingType.LEASE_EXPIRY_RACE, finding.type)
        assertEquals(FindingSeverity.HIGH, finding.severity)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertEquals(2, finding.evidence.size)
    }

    @Test
    fun diagnosesLeaseExpiryRaceWithMediumConfidenceWhenOnlyOneEventPresent() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock")
                )
            )
        )

        assertEquals(InvestigationStatus.LEASE_RACE_DETECTED, report.summaryStatus)
        assertEquals(Confidence.MEDIUM, report.findings.single().confidence)
    }

    @Test
    fun diagnosesMixedAnomaliesAndOrdersBySeverity() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "replay.switched_to_live", "INFO", logger = "org.dmg.balletmaster.replay"),
                    obs(2, "lock.blocked", "INFO", logger = "org.dmg.balletmaster.lock", attributes = mapOf("status" to "BLOCKED", "waitMs" to 25)),
                    obs(3, "store.error", "ERROR", logger = "org.dmg.balletmaster.store", attributes = mapOf("storeOp" to "lock_acquire")),
                    obs(4, null, null, null, parseError = "Invalid JSON")
                ),
                completeness = Completeness.TRUNCATED,
                truncationReason = "MAX_PAGES (limit: 20)"
            )
        )

        assertEquals(InvestigationStatus.MIXED_ANOMALIES, report.summaryStatus)
        assertEquals(FindingType.STORE_INFRASTRUCTURE_ERROR, report.findings.first().type)
        assertEquals(FindingSeverity.CRITICAL, report.findings.first().severity)
        assertTrue(report.findings.any { it.type == FindingType.LOCK_CONTENTION_BLOCKED })
        assertTrue(report.findings.any { it.type == FindingType.REPLAY_SWITCHED_TO_LIVE })
        assertTrue(report.findings.any { it.type == FindingType.UNPARSED_OBSERVATIONS })
        assertTrue(report.limitations.any { it.contains("truncated") })
        assertTrue(report.limitations.any { it.contains("failed JSON parsing") })
    }

    @Test
    fun investigatorDelegatesToProviderNeutralSource() {
        val fakeSource = object : OperationObservationSource {
            override val providerName: String = "cloudwatch-mock"
            override fun fetchByOperation(query: OperationQuery): ObservationBatch =
                batch(
                    listOf(
                        obs(1, "replay.switched_to_live", "INFO", logger = "org.dmg.balletmaster.replay")
                    )
                ).copy(provider = providerName)
        }

        val investigator = OperationInvestigator(fakeSource)
        val report = investigator.investigate(query("ord-99"))

        assertEquals("cloudwatch-mock", report.provider)
        assertEquals("ord-99", report.operationId)
        assertEquals(InvestigationStatus.OBSERVATIONS_ONLY, report.summaryStatus)
        assertEquals(FindingType.REPLAY_SWITCHED_TO_LIVE, report.findings.single().type)
    }
}
