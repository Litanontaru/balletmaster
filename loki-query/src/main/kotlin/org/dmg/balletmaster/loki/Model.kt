package org.dmg.balletmaster.loki

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
 * Diagnostic indicator of historical query result completeness.
 */
enum class Completeness {
    /** All matching log entries in the time window were retrieved. */
    COMPLETE,

    /** Result was truncated due to budget constraints (page count, total entries, or nanosecond collision overflow). */
    TRUNCATED,

    /** Entries were retrieved, but one or more records failed structured JSON parsing. */
    PARTIAL_PARSE_ERRORS
}

/**
 * Normalized log record extracted from Loki stream output.
 */
data class LokiLogObservation(
    val timestampNs: Long,
    val timestamp: Instant,
    val streamLabels: Map<String, String> = emptyMap(),
    val rawLine: String,
    val logger: String? = null,
    val level: String? = null,
    val event: String? = null,
    val correlation: BalletCorrelation? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val parseError: String? = null
) : Serializable

/**
 * Result of executing a paginated range query against Loki.
 */
data class LokiQueryResult(
    val query: String,
    val observations: List<LokiLogObservation>,
    val completeness: Completeness,
    val truncationReason: String? = null,
    val pagesFetched: Int = 1,
    val totalRawEntries: Int = observations.size,
    val duplicatesDropped: Int = 0,
    val parseFailures: Int = observations.count { it.parseError != null },
    val warnings: List<String> = emptyList()
) : Serializable

/**
 * Thrown when Loki HTTP API responds with a non-2xx status code.
 */
class LokiQueryException(
    val statusCode: Int,
    val responseBody: String,
    message: String = "Loki HTTP query failed with status $statusCode: $responseBody"
) : RuntimeException(message)
