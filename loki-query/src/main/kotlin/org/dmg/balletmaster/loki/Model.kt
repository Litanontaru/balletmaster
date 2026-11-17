package org.dmg.balletmaster.loki

import java.io.Serializable
import java.time.Instant

typealias BalletCorrelation = org.dmg.balletmaster.observation.BalletCorrelation

typealias Completeness = org.dmg.balletmaster.observation.Completeness

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
