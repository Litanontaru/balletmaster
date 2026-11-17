package org.dmg.balletmaster.investigation

import org.dmg.balletmaster.observation.BalletObservation
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.ObservationBatch
import org.dmg.balletmaster.observation.OperationQuery

/**
 * Pure, provider-neutral diagnostic analyzer converting a batch of normalized Balletmaster
 * observations into a structured [InvestigationReport].
 */
object ObservationAnalyzer {

    private val EVIDENCE_ATTRIBUTE_KEYS = setOf(
        "outcome",
        "status",
        "phase",
        "exception",
        "storeOp",
        "key",
        "leaseMs",
        "leaseUntil",
        "expectedLeaseUntil",
        "waitMs",
        "attempts",
        "interrupted",
        "executedSteps",
        "totalSteps",
        "durationMs"
    )

    fun analyze(query: OperationQuery, batch: ObservationBatch): InvestigationReport {
        val observations = batch.observations
        val findings = mutableListOf<Finding>()

        if (observations.isEmpty()) {
            findings += Finding(
                type = FindingType.NO_EVENTS_INCONCLUSIVE,
                severity = FindingSeverity.INFO,
                confidence = Confidence.LOW,
                summary = "No observations found for operationId '${query.operationId}' in the requested window",
                details = "Zero matching records were returned by provider '${batch.provider}'. On the standard INFO profile, successful executions and expected SKIP outcomes emit no SDK logs; verify authoritative state in Store if needed."
            )

            return InvestigationReport(
                operationId = query.operationId,
                app = query.app,
                environment = query.environment,
                windowStart = query.start,
                windowEnd = query.end,
                provider = batch.provider,
                summaryStatus = InvestigationStatus.INCONCLUSIVE_NO_EVENTS,
                findings = findings,
                completeness = batch.completeness,
                truncationReason = batch.truncationReason,
                limitations = buildLimitations(batch, isEmpty = true),
                timelineCount = 0,
                parseFailures = batch.parseFailures,
                warnings = batch.warnings
            )
        }

        // 1. Store infrastructure errors
        val storeErrors = observations.filter { obs ->
            obs.event == "store.error" ||
                (obs.event == "chain.stopped" && obs.attrText("outcome") == "ERROR")
        }
        if (storeErrors.isNotEmpty()) {
            val ops = storeErrors.mapNotNull { it.attrText("storeOp") }.distinct()
            val opsDesc = if (ops.isNotEmpty()) " (storeOp=${ops.joinToString(",")})" else ""
            findings += buildFinding(
                type = FindingType.STORE_INFRASTRUCTURE_ERROR,
                severity = FindingSeverity.CRITICAL,
                confidence = Confidence.HIGH,
                summary = "Store infrastructure error interrupted operation execution$opsDesc",
                details = "Observed ${storeErrors.size} Store error / ERROR stop event(s). Check database/cache availability, timeouts, and driver exceptions.",
                sourceObservations = storeErrors
            )
        }

        // 2. Unexpected fallback step exceptions (ContextImpl.execute WARN)
        val unexpectedExceptions = observations.filter { obs ->
            obs.event == "step.exception" &&
                (obs.level.equals("WARN", ignoreCase = true) || obs.attrText("phase") == "execute")
        }
        if (unexpectedExceptions.isNotEmpty()) {
            val classes = unexpectedExceptions.mapNotNull { it.attrText("exception") }.distinct()
            val classesDesc = if (classes.isNotEmpty()) ": ${classes.joinToString(", ")}" else ""
            findings += buildFinding(
                type = FindingType.UNEXPECTED_STEP_EXCEPTION,
                severity = FindingSeverity.CRITICAL,
                confidence = Confidence.HIGH,
                summary = "Unexpected fallback exception caught in ContextImpl.execute$classesDesc",
                details = "Observed ${unexpectedExceptions.size} WARN-level fallback step.exception event(s), indicating an exception escaped normal gate/map/milestone handling.",
                sourceObservations = unexpectedExceptions
            )
        }

        // 3. Business step failures
        val businessExceptions = observations.filter { obs ->
            obs.event == "step.exception" &&
                !(obs.level.equals("WARN", ignoreCase = true) || obs.attrText("phase") == "execute")
        }
        val failureStops = observations.filter { obs ->
            obs.event == "chain.stopped" && obs.attrText("outcome") == "FAILURE"
        }
        val businessFailures = (businessExceptions + failureStops).distinct()
        if (businessFailures.isNotEmpty() && (businessExceptions.isNotEmpty() || unexpectedExceptions.isEmpty())) {
            val exNames = businessExceptions.mapNotNull { it.attrText("exception") }.distinct()
            val phases = businessExceptions.mapNotNull { it.attrText("phase") }.distinct()
            val stepName = businessFailures.firstNotNullOfOrNull { it.correlation?.step }
            val stepPart = stepName?.let { " at step '$it'" } ?: ""
            val exPart = if (exNames.isNotEmpty()) " (${exNames.joinToString(", ")})" else ""
            val phasePart = if (phases.isNotEmpty()) " in phase ${phases.joinToString("/")}" else ""
            findings += buildFinding(
                type = FindingType.BUSINESS_STEP_FAILURE,
                severity = FindingSeverity.HIGH,
                confidence = Confidence.HIGH,
                summary = "Chain stopped due to business step failure$stepPart$exPart",
                details = "Observed ${businessFailures.size} failure event(s)$phasePart. SDK logs intentionally omit exception messages/stack traces; correlate with host application logs if detailed payload context is needed.",
                sourceObservations = businessFailures
            )
        }

        // 4. Lease expiry race (stolen expired lease / stale unlock skipped)
        val stolenEvents = observations.filter { it.event == "lock.stolen_expired" }
        val staleUnlockEvents = observations.filter { it.event == "lock.stale_unlock_skipped" }
        val leaseRaceEvents = (stolenEvents + staleUnlockEvents).distinct()
        if (leaseRaceEvents.isNotEmpty()) {
            val confidence = if (stolenEvents.isNotEmpty() && staleUnlockEvents.isNotEmpty()) {
                Confidence.HIGH
            } else {
                Confidence.MEDIUM
            }
            val detailParts = buildList {
                if (stolenEvents.isNotEmpty()) add("${stolenEvents.size} lock.stolen_expired")
                if (staleUnlockEvents.isNotEmpty()) add("${staleUnlockEvents.size} lock.stale_unlock_skipped")
            }
            findings += buildFinding(
                type = FindingType.LEASE_EXPIRY_RACE,
                severity = FindingSeverity.HIGH,
                confidence = confidence,
                summary = "Expired lock lease takeover / concurrent worker race detected",
                details = "Observed ${detailParts.joinToString(" and ")}. A previous worker likely exceeded its step lease duration (slow external call or GC pause) and another worker took over the lock.",
                sourceObservations = leaseRaceEvents
            )
        }

        // 5. Lock contention (BLOCKED)
        val blockedEvents = observations.filter { obs ->
            obs.event == "lock.blocked" ||
                (obs.event == "chain.stopped" && obs.attrText("status") == "BLOCKED")
        }
        if (blockedEvents.isNotEmpty()) {
            findings += buildFinding(
                type = FindingType.LOCK_CONTENTION_BLOCKED,
                severity = FindingSeverity.MEDIUM,
                confidence = Confidence.HIGH,
                summary = "Execution blocked waiting for concurrent step lock",
                details = "Observed ${blockedEvents.size} lock.blocked / BLOCKED stop event(s). Another worker held the step lease for the entire wait window.",
                sourceObservations = blockedEvents
            )
        }

        // 6. Lock wait interrupted
        val waitInterrupted = observations.filter { it.event == "lock.wait_interrupted" }
        if (waitInterrupted.isNotEmpty()) {
            findings += buildFinding(
                type = FindingType.LOCK_WAIT_INTERRUPTED,
                severity = FindingSeverity.MEDIUM,
                confidence = Confidence.HIGH,
                summary = "Worker thread was interrupted while waiting for step lock",
                details = "Observed ${waitInterrupted.size} lock.wait_interrupted event(s), typically caused by shutdown, timeout cancellation, or thread interruption.",
                sourceObservations = waitInterrupted
            )
        }

        // 7. Replay switched to live
        val switchedToLive = observations.filter { it.event == "replay.switched_to_live" }
        if (switchedToLive.isNotEmpty()) {
            findings += buildFinding(
                type = FindingType.REPLAY_SWITCHED_TO_LIVE,
                severity = FindingSeverity.INFO,
                confidence = Confidence.HIGH,
                summary = "Replay execution switched to Live mode",
                details = "Observed ${switchedToLive.size} replay.switched_to_live transition(s).",
                sourceObservations = switchedToLive
            )
        }

        // 8. Unparsed observations
        val unparsed = observations.filter { it.parseError != null }
        if (unparsed.isNotEmpty()) {
            findings += buildFinding(
                type = FindingType.UNPARSED_OBSERVATIONS,
                severity = FindingSeverity.MEDIUM,
                confidence = Confidence.LOW,
                summary = "${unparsed.size} log line(s) could not be parsed as structured JSON",
                details = "Some entries in the batch had malformed or non-JSON payloads and could not be fully classified.",
                sourceObservations = unparsed
            )
        }

        val sortedFindings = findings.sortedByDescending { it.severity.rank }
        val summaryStatus = resolveSummaryStatus(sortedFindings)

        return InvestigationReport(
            operationId = query.operationId,
            app = query.app,
            environment = query.environment,
            windowStart = query.start,
            windowEnd = query.end,
            provider = batch.provider,
            summaryStatus = summaryStatus,
            findings = sortedFindings,
            completeness = batch.completeness,
            truncationReason = batch.truncationReason,
            limitations = buildLimitations(batch, isEmpty = false),
            timelineCount = observations.size,
            parseFailures = batch.parseFailures,
            warnings = batch.warnings
        )
    }

    private fun resolveSummaryStatus(findings: List<Finding>): InvestigationStatus {
        val types = findings.map { it.type }.toSet()
        val hasInfra = FindingType.STORE_INFRASTRUCTURE_ERROR in types ||
            FindingType.UNEXPECTED_STEP_EXCEPTION in types
        val hasLeaseRace = FindingType.LEASE_EXPIRY_RACE in types
        val hasFailure = FindingType.BUSINESS_STEP_FAILURE in types
        val hasContention = FindingType.LOCK_CONTENTION_BLOCKED in types ||
            FindingType.LOCK_WAIT_INTERRUPTED in types

        val activeBuckets = listOf(hasInfra, hasLeaseRace, hasFailure, hasContention).count { it }
        return when {
            activeBuckets == 0 -> InvestigationStatus.OBSERVATIONS_ONLY
            activeBuckets > 1 -> InvestigationStatus.MIXED_ANOMALIES
            hasInfra -> InvestigationStatus.INFRASTRUCTURE_ERROR
            hasLeaseRace -> InvestigationStatus.LEASE_RACE_DETECTED
            hasFailure -> InvestigationStatus.FAILURE_DETECTED
            else -> InvestigationStatus.CONTENTION_DETECTED
        }
    }

    private fun buildFinding(
        type: FindingType,
        severity: FindingSeverity,
        confidence: Confidence,
        summary: String,
        details: String,
        sourceObservations: List<BalletObservation>
    ): Finding {
        val ordered = sourceObservations.sortedBy { it.timestampNs }
        val firstCorr = ordered.firstNotNullOfOrNull { it.correlation }
        return Finding(
            type = type,
            severity = severity,
            confidence = confidence,
            summary = summary,
            details = details,
            operation = firstCorr?.operation,
            step = ordered.firstNotNullOfOrNull { it.correlation?.step },
            attempt = firstCorr?.attempt,
            evidence = ordered.map(::toEvidence)
        )
    }

    private fun toEvidence(obs: BalletObservation): EvidenceRef {
        val filtered = LinkedHashMap<String, Any?>()
        for (key in EVIDENCE_ATTRIBUTE_KEYS) {
            if (obs.attributes.containsKey(key)) {
                filtered[key] = obs.attributes[key]
            }
        }
        if (obs.parseError != null) {
            filtered["parseError"] = obs.parseError
        }
        return EvidenceRef(
            timestamp = obs.timestamp,
            timestampNs = obs.timestampNs,
            event = obs.event,
            level = obs.level,
            logger = obs.logger,
            step = obs.correlation?.step,
            attributes = filtered
        )
    }

    private fun buildLimitations(batch: ObservationBatch, isEmpty: Boolean): List<String> = buildList {
        add("Log observations are not an authoritative source of truth; confirm persisted facts in Store before taking compensating or replay actions.")
        add("Cross-node timestamp ordering is approximate due to potential clock skew and does not prove strict happens-before causality.")
        add("Without an explicit executionId, concurrent workers executing the same (operationId, attempt, step) cannot be separated by SDK correlation keys alone.")
        if (isEmpty) {
            add("On the standard INFO profile, normal successful runs and expected SKIP outcomes emit no SDK logs; silence cannot distinguish success/SKIP from delivery lag or retention limits.")
        }
        if (batch.completeness == Completeness.TRUNCATED) {
            add("Observation batch was truncated (${batch.truncationReason ?: "budget limit reached"}); additional events may exist outside the fetched slice.")
        }
        if (batch.parseFailures > 0) {
            add("${batch.parseFailures} observation(s) failed JSON parsing and could not be fully classified.")
        }
    }

    private fun BalletObservation.attrText(key: String): String? = attributes[key]?.toString()
}
