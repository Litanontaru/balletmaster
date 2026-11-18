package org.dmg.balletmaster.investigation

import org.dmg.balletmaster.observation.Completeness
import java.io.Serializable
import java.time.Instant

enum class InvestigationStatus {
    INFRASTRUCTURE_ERROR,
    LEASE_RACE_DETECTED,
    FAILURE_DETECTED,
    CONTENTION_DETECTED,
    MIXED_ANOMALIES,
    INCONCLUSIVE_NO_EVENTS,
    OBSERVATIONS_ONLY
}

enum class FindingType {
    STORE_INFRASTRUCTURE_ERROR,
    UNEXPECTED_STEP_EXCEPTION,
    BUSINESS_STEP_FAILURE,
    LEASE_EXPIRY_RACE,
    LOCK_CONTENTION_BLOCKED,
    LOCK_WAIT_INTERRUPTED,
    REPLAY_SWITCHED_TO_LIVE,
    UNPARSED_OBSERVATIONS,
    NO_EVENTS_INCONCLUSIVE
}

enum class FindingSeverity(val rank: Int) {
    CRITICAL(4),
    HIGH(3),
    MEDIUM(2),
    INFO(1)
}

/**
 * Epistemic classification of a [Finding].
 *
 * Log-based investigation can only produce observed log signals, correlated diagnostic
 * hypotheses, or inconclusive results. Authoritative execution facts must always be
 * verified directly in the Store.
 */
enum class FindingKind {
    /** Direct observation of a single SDK log signal type without cross-event causal claims. */
    OBSERVED_SIGNAL,

    /**
     * Multi-event diagnostic hypothesis grouped by available SDK correlation keys
     * (operation, operationId, attempt, suffix, step). Because Core does not emit a unique
     * per-execution executionId, correlation does not prove single-execution causality.
     */
    CORRELATED_HYPOTHESIS,

    /** Insufficient or unparseable observational evidence to reach a conclusion. */
    INCONCLUSIVE
}

/**
 * Diagnostic confidence of a [Finding] based on signal directness and correlation completeness.
 *
 * - [HIGH]: Direct unambiguous SDK signal (or primary signal with correlated stop) carrying complete
 *   correlation keys; still observational and not an authoritative Store fact.
 * - [MEDIUM]: Cross-event hypothesis without executionId proof (such as [FindingType.LEASE_EXPIRY_RACE])
 *   or indirect stop-only signal with complete correlation keys.
 * - [LOW]: Unpaired partial race signal, incomplete correlation keys, unparsed entries, or empty batch.
 */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW
}

data class EvidenceRef(
    val timestamp: Instant,
    val timestampNs: Long,
    val event: String?,
    val level: String?,
    val logger: String?,
    val step: String?,
    val attributes: Map<String, Any?> = emptyMap(),
    val operation: String? = null,
    val operationId: String? = null,
    val attempt: Int? = null,
    val suffix: String? = null
) : Serializable

data class Finding(
    val type: FindingType,
    val severity: FindingSeverity,
    val confidence: Confidence,
    val summary: String,
    val details: String,
    val operation: String? = null,
    val step: String? = null,
    val attempt: Int? = null,
    val evidence: List<EvidenceRef> = emptyList(),
    val suffix: String? = null,
    val operationId: String? = null,
    val kind: FindingKind = FindingKind.OBSERVED_SIGNAL,
    val correlationComplete: Boolean = false
) : Serializable

data class InvestigationReport(
    val operationId: String,
    val app: String,
    val environment: String,
    val windowStart: Instant,
    val windowEnd: Instant,
    val provider: String,
    val summaryStatus: InvestigationStatus,
    val findings: List<Finding>,
    val completeness: Completeness,
    val truncationReason: String? = null,
    val limitations: List<String> = emptyList(),
    val timelineCount: Int = 0,
    val parseFailures: Int = 0,
    val warnings: List<String> = emptyList()
) : Serializable
