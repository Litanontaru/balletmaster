package org.dmg.balletmaster.investigation

import org.dmg.balletmaster.observation.BalletCorrelation
import org.dmg.balletmaster.observation.BalletObservation
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.ObservationBatch
import org.dmg.balletmaster.observation.OperationObservationSource
import org.dmg.balletmaster.observation.OperationQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        operation: String? = "orderFlow",
        operationId: String? = "ord-1",
        attempt: Int? = 0,
        suffix: String? = "",
        step: String? = "CHARGE_CARD",
        includeCorrelation: Boolean = true,
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
        correlation = if (includeCorrelation) {
            BalletCorrelation(
                operation = operation,
                operationId = operationId,
                attempt = attempt,
                suffix = suffix,
                step = step,
                replay = false
            )
        } else {
            null
        },
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
        val finding = report.findings.single()
        assertEquals(FindingType.NO_EVENTS_INCONCLUSIVE, finding.type)
        assertEquals(FindingKind.INCONCLUSIVE, finding.kind)
        assertEquals(Confidence.LOW, finding.confidence)
        assertFalse(finding.correlationComplete)
        assertTrue(report.limitations.any { it.contains("INFO") })
        assertTrue(report.limitations.any { it.contains("Store") })
    }

    @Test
    fun diagnosesBusinessStepFailureAsCorrelatedHypothesisWhenBothEventsShareScope() {
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
        assertEquals(FindingKind.CORRELATED_HYPOTHESIS, finding.kind)
        assertEquals(FindingSeverity.HIGH, finding.severity)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertTrue(finding.correlationComplete)
        assertEquals("CHARGE_CARD", finding.step)
        assertEquals(0, finding.attempt)
        assertEquals("", finding.suffix)
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
        assertEquals(FindingKind.CORRELATED_HYPOTHESIS, finding.kind)
        assertEquals(FindingSeverity.CRITICAL, finding.severity)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertTrue(finding.correlationComplete)
        assertTrue(finding.summary.contains("set_output"))
    }

    @Test
    fun diagnosesLeaseExpiryRaceAsMediumConfidenceHypothesisWhenBothEventsShareFullCorrelation() {
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
        assertEquals(FindingKind.CORRELATED_HYPOTHESIS, finding.kind)
        assertEquals(FindingSeverity.HIGH, finding.severity)
        // Capped at MEDIUM because Core has no executionId to prove single-episode causality.
        assertEquals(Confidence.MEDIUM, finding.confidence)
        assertTrue(finding.correlationComplete)
        assertEquals("orderFlow", finding.operation)
        assertEquals("ord-1", finding.operationId)
        assertEquals(0, finding.attempt)
        assertEquals("", finding.suffix)
        assertEquals("CHARGE_CARD", finding.step)
        assertEquals(2, finding.evidence.size)
        assertTrue(finding.details.contains("executionId"))
    }

    @Test
    fun diagnosesSingleLeaseEventAsLowConfidenceObservedSignal() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock")
                )
            )
        )

        assertEquals(InvestigationStatus.LEASE_RACE_DETECTED, report.summaryStatus)
        val finding = report.findings.single()
        assertEquals(FindingType.LEASE_EXPIRY_RACE, finding.type)
        assertEquals(FindingKind.OBSERVED_SIGNAL, finding.kind)
        assertEquals(Confidence.LOW, finding.confidence)
        assertTrue(finding.correlationComplete)
        assertEquals(1, finding.evidence.size)
    }

    @Test
    fun doesNotCorrelateLeaseEventsAcrossDifferentStepsOfSameOperation() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_A"),
                    obs(2, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_B")
                )
            )
        )

        assertEquals(InvestigationStatus.LEASE_RACE_DETECTED, report.summaryStatus)
        assertEquals(2, report.findings.size)
        assertEquals(listOf("STEP_A", "STEP_B"), report.findings.map { it.step })
        for (finding in report.findings) {
            assertEquals(FindingType.LEASE_EXPIRY_RACE, finding.type)
            assertEquals(FindingKind.OBSERVED_SIGNAL, finding.kind)
            assertEquals(Confidence.LOW, finding.confidence)
            assertEquals(1, finding.evidence.size)
        }
    }

    @Test
    fun doesNotCorrelateLeaseEventsAcrossDifferentAttempts() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", attempt = 0),
                    obs(2, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", attempt = 1)
                )
            )
        )

        assertEquals(2, report.findings.size)
        assertEquals(listOf(0, 1), report.findings.map { it.attempt })
        assertTrue(report.findings.all { it.kind == FindingKind.OBSERVED_SIGNAL && it.confidence == Confidence.LOW })
    }

    @Test
    fun doesNotCorrelateLeaseEventsAcrossDifferentSuffixes() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", suffix = ""),
                    obs(2, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", suffix = "milestone-2")
                )
            )
        )

        assertEquals(2, report.findings.size)
        assertEquals(listOf("", "milestone-2"), report.findings.map { it.suffix })
        assertTrue(report.findings.all { it.kind == FindingKind.OBSERVED_SIGNAL && it.confidence == Confidence.LOW })
    }

    @Test
    fun isolatesEventsWithDifferentOperationOrOperationIdAndWarnsOnMismatch() {
        val report = ObservationAnalyzer.analyze(
            query("ord-1"),
            batch(
                listOf(
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", operation = "flowA", operationId = "ord-1"),
                    obs(2, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", operation = "flowB", operationId = "ord-1"),
                    obs(3, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", operation = "flowA", operationId = "ord-OTHER")
                )
            )
        )

        assertEquals(3, report.findings.size)
        assertTrue(report.findings.all { it.kind == FindingKind.OBSERVED_SIGNAL && it.confidence == Confidence.LOW })
        assertTrue(report.warnings.any { it.contains("ord-OTHER") })
    }

    @Test
    fun separatesMultipleIndependentContentionEpisodesAndMarksRepeatedSameKeyEpisodes() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    // Episode 1 & 2 on same (orderFlow, ord-1, 0, "", STEP_A) — cannot be separated without executionId
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_A", attributes = mapOf("leaseUntil" to 2000L)),
                    obs(2, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_A", attributes = mapOf("expectedLeaseUntil" to 1000L)),
                    obs(30, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_A", attributes = mapOf("leaseUntil" to 32000L)),
                    obs(31, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_A", attributes = mapOf("expectedLeaseUntil" to 31000L)),
                    // Independent episode on STEP_B
                    obs(40, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_B"),
                    obs(41, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", step = "STEP_B"),
                    // Independent contention blocked episodes on attempt=0 vs attempt=1
                    obs(50, "lock.blocked", "INFO", logger = "org.dmg.balletmaster.lock", step = "STEP_B", attempt = 0, attributes = mapOf("status" to "BLOCKED")),
                    obs(60, "lock.blocked", "INFO", logger = "org.dmg.balletmaster.lock", step = "STEP_B", attempt = 1, attributes = mapOf("status" to "BLOCKED"))
                )
            )
        )

        val leaseFindings = report.findings.filter { it.type == FindingType.LEASE_EXPIRY_RACE }
        assertEquals(2, leaseFindings.size)
        val stepAFinding = leaseFindings.first { it.step == "STEP_A" }
        assertEquals(FindingKind.CORRELATED_HYPOTHESIS, stepAFinding.kind)
        assertEquals(Confidence.MEDIUM, stepAFinding.confidence)
        assertEquals(4, stepAFinding.evidence.size)
        assertTrue(stepAFinding.details.contains("separate concurrent contention episodes"))

        val stepBFinding = leaseFindings.first { it.step == "STEP_B" }
        assertEquals(FindingKind.CORRELATED_HYPOTHESIS, stepBFinding.kind)
        assertEquals(Confidence.MEDIUM, stepBFinding.confidence)
        assertEquals(2, stepBFinding.evidence.size)

        val blockedFindings = report.findings.filter { it.type == FindingType.LOCK_CONTENTION_BLOCKED }
        assertEquals(2, blockedFindings.size)
        assertEquals(listOf(0, 1), blockedFindings.map { it.attempt })
        assertTrue(blockedFindings.all { it.kind == FindingKind.OBSERVED_SIGNAL && it.confidence == Confidence.HIGH })
    }

    @Test
    fun doesNotCorrelateIncompleteCorrelationDataAndLowersConfidence() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    // Both missing step — must NOT be merged or elevated!
                    obs(1, "lock.stolen_expired", "WARN", logger = "org.dmg.balletmaster.lock", step = null),
                    obs(2, "lock.stale_unlock_skipped", "WARN", logger = "org.dmg.balletmaster.lock", step = null),
                    // Missing correlation entirely on store.error — must NOT merge with complete ERROR stop
                    obs(3, "store.error", "ERROR", logger = "org.dmg.balletmaster.store", includeCorrelation = false, attributes = mapOf("storeOp" to "get_config")),
                    obs(4, "chain.stopped", "INFO", step = "CHARGE_CARD", attributes = mapOf("outcome" to "ERROR"))
                )
            )
        )

        val leaseFindings = report.findings.filter { it.type == FindingType.LEASE_EXPIRY_RACE }
        assertEquals(2, leaseFindings.size)
        for (f in leaseFindings) {
            assertEquals(FindingKind.OBSERVED_SIGNAL, f.kind)
            assertEquals(Confidence.LOW, f.confidence)
            assertFalse(f.correlationComplete)
            assertEquals(1, f.evidence.size)
        }

        val storeFindings = report.findings.filter { it.type == FindingType.STORE_INFRASTRUCTURE_ERROR }
        assertEquals(2, storeFindings.size)
        val incompleteStoreError = storeFindings.first { !it.correlationComplete }
        assertEquals(FindingKind.OBSERVED_SIGNAL, incompleteStoreError.kind)
        assertEquals(Confidence.LOW, incompleteStoreError.confidence)
        val completeErrorStop = storeFindings.first { it.correlationComplete }
        assertEquals(FindingKind.OBSERVED_SIGNAL, completeErrorStop.kind)
        assertEquals(Confidence.MEDIUM, completeErrorStop.confidence)

        assertTrue(report.limitations.any { it.contains("incomplete correlation") })
    }

    @Test
    fun scopesBusinessFailureAndUnexpectedExceptionSuppressionPerCorrelationKey() {
        val report = ObservationAnalyzer.analyze(
            query(),
            batch(
                listOf(
                    // STEP_A attempt 0: unexpected fallback exception + matching FAILURE stop (stop suppressed in same scope)
                    obs(1, "step.exception", "WARN", step = "STEP_A", attempt = 0, attributes = mapOf("phase" to "execute", "exception" to "NullPointerException")),
                    obs(2, "chain.stopped", "INFO", step = "STEP_A", attempt = 0, attributes = mapOf("outcome" to "FAILURE")),
                    // STEP_B attempt 1: independent FAILURE stop must NOT be suppressed by STEP_A's unexpected exception
                    obs(3, "chain.stopped", "INFO", step = "STEP_B", attempt = 1, attributes = mapOf("outcome" to "FAILURE"))
                )
            )
        )

        val unexpected = report.findings.filter { it.type == FindingType.UNEXPECTED_STEP_EXCEPTION }
        assertEquals(1, unexpected.size)
        assertEquals("STEP_A", unexpected.single().step)
        assertEquals(FindingKind.OBSERVED_SIGNAL, unexpected.single().kind)
        assertEquals(Confidence.HIGH, unexpected.single().confidence)

        val businessFailures = report.findings.filter { it.type == FindingType.BUSINESS_STEP_FAILURE }
        assertEquals(1, businessFailures.size)
        val stepBFailure = businessFailures.single()
        assertEquals("STEP_B", stepBFailure.step)
        assertEquals(1, stepBFailure.attempt)
        assertEquals(FindingKind.OBSERVED_SIGNAL, stepBFailure.kind)
        assertEquals(Confidence.MEDIUM, stepBFailure.confidence)
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
        val unparsed = report.findings.first { it.type == FindingType.UNPARSED_OBSERVATIONS }
        assertEquals(FindingKind.INCONCLUSIVE, unparsed.kind)
        assertEquals(Confidence.LOW, unparsed.confidence)
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
                        obs(1, "replay.switched_to_live", "INFO", logger = "org.dmg.balletmaster.replay", operationId = query.operationId)
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
