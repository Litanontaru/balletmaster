package org.dmg.balletmaster.observation

import java.io.Serializable
import java.time.Instant

/**
 * Normalized correlation attributes corresponding to Balletmaster's unified MDC and KeyValuePair dictionary.
 */
data class BalletCorrelation(
    val operation: String? = null,
    val operationId: String? = null,
    val attempt: Int? = null,
    val suffix: String? = null,
    val step: String? = null,
    val replay: Boolean? = null
) : Serializable

/**
 * Diagnostic indicator of historical observation batch completeness.
 */
enum class Completeness {
    /** All matching log entries in the requested time window were retrieved within budget. */
    COMPLETE,

    /** Result was truncated due to budget constraints (page count, total entries, or timestamp collision overflow). */
    TRUNCATED,

    /** Entries were retrieved, but one or more records failed structured parsing. */
    PARTIAL_PARSE_ERRORS
}

/**
 * Controls whether only Balletmaster SDK system events or also correlated host application logs are fetched.
 */
enum class ObservationScope {
    /** Selects only Balletmaster SDK system events (loggers matching org.dmg.balletmaster.*). */
    SDK_ONLY,

    /** Selects both SDK system events and host logs carrying the correlated operationId in MDC. */
    ALL_CORRELATED
}

/**
 * Provider-neutral normalized observation record extracted from a log backend.
 */
data class BalletObservation(
    val timestampNs: Long,
    val timestamp: Instant,
    val provider: String,
    val originLabels: Map<String, String> = emptyMap(),
    val rawLine: String,
    val logger: String? = null,
    val level: String? = null,
    val event: String? = null,
    val correlation: BalletCorrelation? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val parseError: String? = null
) : Serializable

/**
 * Provider-neutral batch of historical observations with completeness metadata and provenance.
 */
data class ObservationBatch(
    val provider: String,
    val sourceQuery: String,
    val observations: List<BalletObservation>,
    val completeness: Completeness,
    val truncationReason: String? = null,
    val pagesFetched: Int = 1,
    val totalRawEntries: Int = observations.size,
    val duplicatesDropped: Int = 0,
    val parseFailures: Int = observations.count { it.parseError != null },
    val warnings: List<String> = emptyList()
) : Serializable

/**
 * Provider-neutral query descriptor for fetching observations of a single operation instance.
 */
data class OperationQuery(
    val app: String,
    val environment: String,
    val operationId: String,
    val start: Instant,
    val end: Instant,
    val scope: ObservationScope = ObservationScope.SDK_ONLY,
    val operation: String? = null,
    val step: String? = null,
    val attempt: Int? = null,
    val extraFilters: Map<String, String> = emptyMap()
) : Serializable {
    init {
        require(app.isNotBlank()) { "app must not be blank" }
        require(environment.isNotBlank()) { "environment must not be blank" }
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(!start.isAfter(end)) { "start ($start) must be <= end ($end)" }
    }
}

/**
 * Port implemented by log platform adapters (Loki, CloudWatch, OpenSearch, etc.) to supply normalized observations.
 */
interface OperationObservationSource {
    val providerName: String

    fun fetchByOperation(query: OperationQuery): ObservationBatch
}

/**
 * Provider-neutral exception signalling an upstream observation backend failure or unavailability.
 */
class ObservationSourceException(
    val provider: String,
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)
