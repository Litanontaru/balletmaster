package org.dmg.balletmaster.investigation

import org.dmg.balletmaster.observation.BalletObservation
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.ObservationBatch
import org.dmg.balletmaster.observation.OperationQuery

/**
 * Pure, provider-neutral diagnostic analyzer converting a batch of normalized Balletmaster
 * observations into a structured [InvestigationReport].
 *
 * Correlation is scoped strictly by `(operation, operationId, attempt, suffix, step)`.
 * Observations with incomplete correlation attributes are never merged across events.
 * Because Core does not emit a unique per-execution `executionId`, multi-event findings
 * are classified as [FindingKind.CORRELATED_HYPOTHESIS] rather than proven causal facts.
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

    private data class CorrelationKey(
        val operation: String?,
        val operationId: String?,
        val attempt: Int?,
        val suffix: String?,
        val step: String?
    ) {
        val isComplete: Boolean
            get() = !operation.isNullOrBlank() &&
                !operationId.isNullOrBlank() &&
                attempt != null &&
                suffix != null &&
                !step.isNullOrBlank()

        fun describe(): String =
            "operation=${operation ?: "<missing>"}, operationId=${operationId ?: "<missing>"}, " +
                "attempt=${attempt?.toString() ?: "<missing>"}, suffix=${suffix?.let { "'$it'" } ?: "<missing>"}, " +
                "step=${step ?: "<missing>"}"
    }

    private data class ScopedGroup(
        val key: CorrelationKey,
        val complete: Boolean,
        val observations: List<BalletObservation>
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
                details = "Zero matching records were returned by provider '${batch.provider}'. On the standard INFO profile, successful executions and expected SKIP outcomes emit no SDK logs; verify authoritative state in Store if needed.",
                operationId = query.operationId,
                kind = FindingKind.INCONCLUSIVE,
                correlationComplete = false
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
                limitations = buildLimitations(batch, isEmpty = true, hasIncompleteCorrelation = false),
                timelineCount = 0,
                parseFailures = batch.parseFailures,
                warnings = batch.warnings
            )
        }

        // 1. Store infrastructure errors (scoped by full correlation key)
        val storeErrorEvents = observations.filter { obs ->
            obs.event == "store.error" ||
                (obs.event == "chain.stopped" && obs.attrText("outcome") == "ERROR")
        }
        for (group in scopeObservations(storeErrorEvents)) {
            val directStoreErrors = group.observations.filter { it.event == "store.error" }
            val errorStops = group.observations.filter { it.event == "chain.stopped" }
            val ops = directStoreErrors.mapNotNull { it.attrText("storeOp") }.distinct()
            val opsDesc = if (ops.isNotEmpty()) " (storeOp=${ops.joinToString(",")})" else ""
            val hasBoth = group.complete && directStoreErrors.isNotEmpty() && errorStops.isNotEmpty()

            val kind = if (hasBoth) FindingKind.CORRELATED_HYPOTHESIS else FindingKind.OBSERVED_SIGNAL
            val confidence = when {
                !group.complete -> Confidence.LOW
                directStoreErrors.isNotEmpty() -> Confidence.HIGH
                else -> Confidence.MEDIUM
            }
            val summary = if (directStoreErrors.isNotEmpty()) {
                "Store infrastructure error interrupted operation execution$opsDesc"
            } else {
                "Chain stopped with ERROR outcome (store.error event not observed in batch)"
            }
            val details = when {
                hasBoth ->
                    "Observed ${directStoreErrors.size} store.error and ${errorStops.size} ERROR chain.stopped event(s) sharing (${group.key.describe()}). " +
                        "Linking them to a single execution is a correlated hypothesis because Core does not emit executionId. Check database/cache availability, timeouts, and driver exceptions."
                !group.complete ->
                    "Observed ${group.observations.size} Store error / ERROR stop signal with incomplete correlation (${group.key.describe()}); kept un-correlated with LOW confidence."
                directStoreErrors.isNotEmpty() ->
                    "Observed ${directStoreErrors.size} direct store.error signal(s) on (${group.key.describe()}). Check database/cache availability, timeouts, and driver exceptions."
                else ->
                    "Observed ${errorStops.size} chain.stopped(outcome=ERROR) signal(s) on (${group.key.describe()}) without a matching store.error event in this batch."
            }

            findings += buildFinding(
                type = FindingType.STORE_INFRASTRUCTURE_ERROR,
                severity = FindingSeverity.CRITICAL,
                confidence = confidence,
                kind = kind,
                group = group,
                summary = summary,
                details = details
            )
        }

        // 2. Unexpected fallback step exceptions (ContextImpl.execute WARN)
        val unexpectedExceptions = observations.filter { obs ->
            obs.event == "step.exception" &&
                (obs.level.equals("WARN", ignoreCase = true) || obs.attrText("phase") == "execute")
        }
        val unexpectedCompleteKeys = unexpectedExceptions
            .map { it.correlationKey() }
            .filter { it.isComplete }
            .toSet()

        for (group in scopeObservations(unexpectedExceptions)) {
            val classes = group.observations.mapNotNull { it.attrText("exception") }.distinct()
            val classesDesc = if (classes.isNotEmpty()) ": ${classes.joinToString(", ")}" else ""
            val confidence = if (group.complete) Confidence.HIGH else Confidence.LOW
            val details = if (group.complete) {
                "Observed ${group.observations.size} WARN-level fallback step.exception signal(s) on (${group.key.describe()}), indicating an exception escaped normal gate/map/milestone handling."
            } else {
                "Observed WARN-level fallback step.exception signal with incomplete correlation (${group.key.describe()}); kept un-correlated with LOW confidence."
            }
            findings += buildFinding(
                type = FindingType.UNEXPECTED_STEP_EXCEPTION,
                severity = FindingSeverity.CRITICAL,
                confidence = confidence,
                kind = FindingKind.OBSERVED_SIGNAL,
                group = group,
                summary = "Unexpected fallback exception caught in ContextImpl.execute$classesDesc",
                details = details
            )
        }

        // 3. Business step failures (scoped per correlation key)
        val businessExceptions = observations.filter { obs ->
            obs.event == "step.exception" &&
                !(obs.level.equals("WARN", ignoreCase = true) || obs.attrText("phase") == "execute")
        }
        val failureStops = observations.filter { obs ->
            obs.event == "chain.stopped" && obs.attrText("outcome") == "FAILURE"
        }
        for (group in scopeObservations((businessExceptions + failureStops).distinct())) {
            val groupBusinessEx = group.observations.filter { it.event == "step.exception" }
            val groupFailureStops = group.observations.filter { it.event == "chain.stopped" }

            // Suppress FAILURE stop only when an unexpected fallback exception occurred in the SAME complete scope
            if (group.complete && groupBusinessEx.isEmpty() && group.key in unexpectedCompleteKeys) {
                continue
            }

            val exNames = groupBusinessEx.mapNotNull { it.attrText("exception") }.distinct()
            val phases = groupBusinessEx.mapNotNull { it.attrText("phase") }.distinct()
            val stepPart = group.key.step?.let { " at step '$it'" } ?: ""
            val exPart = if (exNames.isNotEmpty()) " (${exNames.joinToString(", ")})" else ""
            val phasePart = if (phases.isNotEmpty()) " in phase ${phases.joinToString("/")}" else ""
            val hasBoth = group.complete && groupBusinessEx.isNotEmpty() && groupFailureStops.isNotEmpty()

            val kind = if (hasBoth) FindingKind.CORRELATED_HYPOTHESIS else FindingKind.OBSERVED_SIGNAL
            val confidence = when {
                !group.complete -> Confidence.LOW
                groupBusinessEx.isNotEmpty() -> Confidence.HIGH
                else -> Confidence.MEDIUM
            }
            val details = when {
                hasBoth ->
                    "Observed ${groupBusinessEx.size} business step.exception and ${groupFailureStops.size} FAILURE chain.stopped event(s)$phasePart sharing (${group.key.describe()}). " +
                        "Correlating them to one execution is a diagnostic hypothesis without executionId. SDK logs intentionally omit exception messages/stack traces."
                !group.complete ->
                    "Observed business failure signal$phasePart with incomplete correlation (${group.key.describe()}); kept un-correlated with LOW confidence."
                groupBusinessEx.isNotEmpty() ->
                    "Observed ${groupBusinessEx.size} business step.exception signal(s)$phasePart on (${group.key.describe()}). SDK logs intentionally omit exception messages/stack traces."
                else ->
                    "Observed ${groupFailureStops.size} chain.stopped(outcome=FAILURE) signal(s) on (${group.key.describe()}) without a matching step.exception in this batch."
            }

            findings += buildFinding(
                type = FindingType.BUSINESS_STEP_FAILURE,
                severity = FindingSeverity.HIGH,
                confidence = confidence,
                kind = kind,
                group = group,
                summary = "Chain stopped due to business step failure$stepPart$exPart",
                details = details
            )
        }

        // 4. Lease expiry race (strictly scoped by full correlation key; never Confidence.HIGH without executionId)
        val leaseRaceEvents = observations.filter {
            it.event == "lock.stolen_expired" || it.event == "lock.stale_unlock_skipped"
        }
        for (group in scopeObservations(leaseRaceEvents)) {
            val stolen = group.observations.filter { it.event == "lock.stolen_expired" }
            val staleUnlock = group.observations.filter { it.event == "lock.stale_unlock_skipped" }
            val hasBoth = group.complete && stolen.isNotEmpty() && staleUnlock.isNotEmpty()

            val kind = if (hasBoth) FindingKind.CORRELATED_HYPOTHESIS else FindingKind.OBSERVED_SIGNAL
            // Even with matching (operation, operationId, attempt, suffix, step), Core lacks executionId,
            // so a two-worker lease race remains a correlated hypothesis (MEDIUM), never HIGH.
            val confidence = if (hasBoth) Confidence.MEDIUM else Confidence.LOW

            val detailParts = buildList {
                if (stolen.isNotEmpty()) add("${stolen.size} lock.stolen_expired")
                if (staleUnlock.isNotEmpty()) add("${staleUnlock.size} lock.stale_unlock_skipped")
            }
            val multipleEpisodesNote = if (stolen.size > 1 || staleUnlock.size > 1) {
                " Multiple events of the same type occurred on this key and may represent separate concurrent contention episodes that cannot be distinguished without executionId."
            } else {
                ""
            }
            val summary = when {
                hasBoth -> "Correlated expired lock lease takeover / stale unlock hypothesis detected"
                stolen.isNotEmpty() -> "Expired lock lease takeover signal observed (lock.stolen_expired)"
                else -> "Stale lock unlock skipped signal observed (lock.stale_unlock_skipped)"
            }
            val details = when {
                hasBoth ->
                    "Observed ${detailParts.joinToString(" and ")} sharing (${group.key.describe()}). " +
                        "Correlating these signals is a diagnostic hypothesis of a lease-expiry worker race rather than proof of a single execution episode, because Core does not emit executionId.$multipleEpisodesNote"
                !group.complete ->
                    "Observed ${detailParts.joinToString(" and ")} with incomplete correlation (${group.key.describe()}); kept un-correlated with LOW confidence."
                stolen.isNotEmpty() ->
                    "Observed ${detailParts.joinToString(" and ")} on (${group.key.describe()}) without a correlated lock.stale_unlock_skipped on the same key. An expired lease takeover can result from a concurrent slow worker or a previously crashed worker."
                else ->
                    "Observed ${detailParts.joinToString(" and ")} on (${group.key.describe()}) without a correlated lock.stolen_expired on the same key."
            }

            findings += buildFinding(
                type = FindingType.LEASE_EXPIRY_RACE,
                severity = FindingSeverity.HIGH,
                confidence = confidence,
                kind = kind,
                group = group,
                summary = summary,
                details = details
            )
        }

        // 5. Lock contention (BLOCKED)
        val blockedEvents = observations.filter { obs ->
            obs.event == "lock.blocked" ||
                (obs.event == "chain.stopped" && obs.attrText("status") == "BLOCKED")
        }
        for (group in scopeObservations(blockedEvents)) {
            val directBlocked = group.observations.filter { it.event == "lock.blocked" }
            val blockedStops = group.observations.filter { it.event == "chain.stopped" }
            val hasBoth = group.complete && directBlocked.isNotEmpty() && blockedStops.isNotEmpty()

            val kind = if (hasBoth) FindingKind.CORRELATED_HYPOTHESIS else FindingKind.OBSERVED_SIGNAL
            val confidence = when {
                !group.complete -> Confidence.LOW
                directBlocked.isNotEmpty() -> Confidence.HIGH
                else -> Confidence.MEDIUM
            }
            val details = when {
                hasBoth ->
                    "Observed ${directBlocked.size} lock.blocked and ${blockedStops.size} BLOCKED chain.stopped event(s) sharing (${group.key.describe()}). " +
                        "Linking them to one execution is a correlated hypothesis without executionId. Another worker held the step lease for the wait window."
                !group.complete ->
                    "Observed lock contention signal with incomplete correlation (${group.key.describe()}); kept un-correlated with LOW confidence."
                directBlocked.isNotEmpty() ->
                    "Observed ${directBlocked.size} lock.blocked signal(s) on (${group.key.describe()}). Another worker held the step lease for the wait window."
                else ->
                    "Observed ${blockedStops.size} chain.stopped(status=BLOCKED) signal(s) on (${group.key.describe()}) without a matching lock.blocked event in this batch."
            }

            findings += buildFinding(
                type = FindingType.LOCK_CONTENTION_BLOCKED,
                severity = FindingSeverity.MEDIUM,
                confidence = confidence,
                kind = kind,
                group = group,
                summary = "Execution blocked waiting for concurrent step lock",
                details = details
            )
        }

        // 6. Lock wait interrupted
        val waitInterrupted = observations.filter { it.event == "lock.wait_interrupted" }
        for (group in scopeObservations(waitInterrupted)) {
            val confidence = if (group.complete) Confidence.HIGH else Confidence.LOW
            val details = if (group.complete) {
                "Observed ${group.observations.size} lock.wait_interrupted signal(s) on (${group.key.describe()}), typically caused by shutdown, timeout cancellation, or thread interruption."
            } else {
                "Observed lock.wait_interrupted signal with incomplete correlation (${group.key.describe()}); kept un-correlated with LOW confidence."
            }
            findings += buildFinding(
                type = FindingType.LOCK_WAIT_INTERRUPTED,
                severity = FindingSeverity.MEDIUM,
                confidence = confidence,
                kind = FindingKind.OBSERVED_SIGNAL,
                group = group,
                summary = "Worker thread was interrupted while waiting for step lock",
                details = details
            )
        }

        // 7. Replay switched to live
        val switchedToLive = observations.filter { it.event == "replay.switched_to_live" }
        for (group in scopeObservations(switchedToLive)) {
            val confidence = if (group.complete) Confidence.HIGH else Confidence.LOW
            val details = if (group.complete) {
                "Observed ${group.observations.size} replay.switched_to_live transition signal(s) on (${group.key.describe()})."
            } else {
                "Observed replay.switched_to_live transition signal with incomplete correlation (${group.key.describe()}); kept un-correlated with LOW confidence."
            }
            findings += buildFinding(
                type = FindingType.REPLAY_SWITCHED_TO_LIVE,
                severity = FindingSeverity.INFO,
                confidence = confidence,
                kind = FindingKind.OBSERVED_SIGNAL,
                group = group,
                summary = "Replay execution switched to Live mode",
                details = details
            )
        }

        // 8. Unparsed observations
        val unparsed = observations.filter { it.parseError != null }
        if (unparsed.isNotEmpty()) {
            val ordered = unparsed.sortedBy { it.timestampNs }
            findings += Finding(
                type = FindingType.UNPARSED_OBSERVATIONS,
                severity = FindingSeverity.MEDIUM,
                confidence = Confidence.LOW,
                summary = "${unparsed.size} log line(s) could not be parsed as structured JSON",
                details = "Some entries in the batch had malformed or non-JSON payloads and could not be fully classified.",
                evidence = ordered.map(::toEvidence),
                kind = FindingKind.INCONCLUSIVE,
                correlationComplete = false
            )
        }

        val sortedFindings = findings.sortedWith(
            compareByDescending<Finding> { it.severity.rank }
                .thenBy { it.evidence.minOfOrNull { ev -> ev.timestampNs } ?: Long.MAX_VALUE }
                .thenBy { it.type.ordinal }
        )
        val summaryStatus = resolveSummaryStatus(sortedFindings)
        val hasIncompleteCorrelation = sortedFindings.any {
            !it.correlationComplete &&
                it.type != FindingType.UNPARSED_OBSERVATIONS &&
                it.type != FindingType.NO_EVENTS_INCONCLUSIVE
        }

        val mismatchedOperationIds = observations
            .mapNotNull { it.correlation?.operationId?.takeIf { id -> id.isNotBlank() && id != query.operationId } }
            .distinct()
        val warnings = buildList {
            addAll(batch.warnings)
            if (mismatchedOperationIds.isNotEmpty()) {
                add(
                    "Observation batch included events with correlation.operationId different from requested '${query.operationId}': " +
                        "${mismatchedOperationIds.joinToString(", ")}; they were isolated into separate correlation groups."
                )
            }
        }

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
            limitations = buildLimitations(batch, isEmpty = false, hasIncompleteCorrelation = hasIncompleteCorrelation),
            timelineCount = observations.size,
            parseFailures = batch.parseFailures,
            warnings = warnings
        )
    }

    private fun scopeObservations(events: List<BalletObservation>): List<ScopedGroup> {
        if (events.isEmpty()) return emptyList()
        val completeGroups = linkedMapOf<CorrelationKey, MutableList<BalletObservation>>()
        val incompleteGroups = mutableListOf<ScopedGroup>()
        for (event in events) {
            val key = event.correlationKey()
            if (key.isComplete) {
                completeGroups.getOrPut(key) { mutableListOf() } += event
            } else {
                // Missing correlation keys represent unknown scope and must not be merged across events.
                incompleteGroups += ScopedGroup(key = key, complete = false, observations = listOf(event))
            }
        }
        val result = mutableListOf<ScopedGroup>()
        for ((key, list) in completeGroups) {
            result += ScopedGroup(key = key, complete = true, observations = list)
        }
        result += incompleteGroups
        return result
    }

    private fun BalletObservation.correlationKey(): CorrelationKey = CorrelationKey(
        operation = correlation?.operation?.takeIf { it.isNotBlank() },
        operationId = correlation?.operationId?.takeIf { it.isNotBlank() },
        attempt = correlation?.attempt,
        suffix = correlation?.suffix,
        step = correlation?.step?.takeIf { it.isNotBlank() }
    )

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
        kind: FindingKind,
        group: ScopedGroup,
        summary: String,
        details: String
    ): Finding {
        val ordered = group.observations.sortedBy { it.timestampNs }
        return Finding(
            type = type,
            severity = severity,
            confidence = confidence,
            summary = summary,
            details = details,
            operation = group.key.operation,
            step = group.key.step,
            attempt = group.key.attempt,
            evidence = ordered.map(::toEvidence),
            suffix = group.key.suffix,
            operationId = group.key.operationId,
            kind = kind,
            correlationComplete = group.complete
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
            attributes = filtered,
            operation = obs.correlation?.operation,
            operationId = obs.correlation?.operationId,
            attempt = obs.correlation?.attempt,
            suffix = obs.correlation?.suffix
        )
    }

    private fun buildLimitations(
        batch: ObservationBatch,
        isEmpty: Boolean,
        hasIncompleteCorrelation: Boolean
    ): List<String> = buildList {
        add("Log-based findings represent observed signals or correlated diagnostic hypotheses, never authoritative Store facts; confirm persisted state in Store before taking compensating or replay actions.")
        add("Cross-node timestamp ordering is approximate due to potential clock skew and does not prove strict happens-before causality.")
        add("Without an explicit executionId in Core, concurrent or repeated executions sharing the same (operation, operationId, attempt, suffix, step) cannot be proven to belong to a single execution episode.")
        if (hasIncompleteCorrelation) {
            add("One or more observations had incomplete correlation attributes (missing operation, operationId, attempt, suffix, or step) and were kept un-correlated with LOW confidence.")
        }
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
