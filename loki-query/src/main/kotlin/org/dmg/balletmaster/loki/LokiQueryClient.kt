package org.dmg.balletmaster.loki

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

data class LokiClientConfig(
    val baseUrl: URI,
    val orgId: String? = null,
    val authHeader: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val requestTimeout: Duration = Duration.ofSeconds(15),
    val maxWindow: Duration = Duration.ofHours(24),
    val defaultPageLimit: Int = 500,
    val maxTotalEntries: Int = 5000,
    val maxPages: Int = 20
)

enum class QueryDirection {
    FORWARD,
    BACKWARD
}

class LokiQueryClient(
    val config: LokiClientConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout)
        .build()
) {

    companion object {
        private const val MAX_LOKI_LIMIT = 5000
    }

    private data class BoundaryKey(
        val streamLabels: Map<String, String>,
        val rawLine: String
    )

    fun queryRange(
        query: LogQlQuery,
        start: Instant,
        end: Instant,
        direction: QueryDirection = QueryDirection.FORWARD,
        pageLimit: Int = config.defaultPageLimit
    ): LokiQueryResult {
        require(start <= end) { "start ($start) must be <= end ($end)" }
        val window = Duration.between(start, end)
        require(window <= config.maxWindow) {
            "Query window of $window exceeds maxWindow limit of ${config.maxWindow}"
        }
        require(pageLimit in 1..MAX_LOKI_LIMIT) { "pageLimit must be between 1 and $MAX_LOKI_LIMIT (got $pageLimit)" }
        require(config.maxPages >= 1) { "maxPages must be >= 1 (got ${config.maxPages})" }
        require(config.maxTotalEntries >= 1) { "maxTotalEntries must be >= 1 (got ${config.maxTotalEntries})" }

        val observations = mutableListOf<LokiLogObservation>()
        var pagesFetched = 0
        var totalRaw = 0
        var duplicatesDropped = 0
        var completeness = Completeness.COMPLETE
        var truncationReason: String? = null

        val isForward = direction == QueryDirection.FORWARD
        var currentStartNs = LokiLogParser.instantToNanos(start)
        var currentEndNs = LokiLogParser.instantToNanos(end)
        var effectiveLimit = pageLimit
        var boundaryTsNs: Long? = null
        val boundarySeenCounts = mutableMapOf<BoundaryKey, Int>()

        while (true) {
            pagesFetched++
            val rawEntries = try {
                executeSinglePage(
                    query = query.logQl,
                    startNs = currentStartNs,
                    endNs = currentEndNs,
                    limit = effectiveLimit,
                    direction = direction
                )
            } catch (e: LokiQueryException) {
                if (effectiveLimit > pageLimit && e.statusCode == 400) {
                    completeness = Completeness.TRUNCATED
                    truncationReason = "SAME_TIMESTAMP_OVERFLOW at ${boundaryTsNs ?: currentStartNs} ns " +
                        "(server rejected escalated limit $effectiveLimit)"
                    break
                }
                throw e
            }

            val entries = if (isForward) {
                rawEntries.sortedWith(
                    compareBy(
                        { it.tsNs },
                        { it.rawLine },
                        { canonicalLabelsKey(it.streamLabels) }
                    )
                )
            } else {
                rawEntries.sortedWith(
                    compareByDescending<RawStreamEntry> { it.tsNs }
                        .thenBy { it.rawLine }
                        .thenBy { canonicalLabelsKey(it.streamLabels) }
                )
            }

            totalRaw += entries.size
            if (entries.isEmpty()) {
                break
            }

            val remainingToSkip = boundarySeenCounts.toMutableMap()
            val acceptedEntries = mutableListOf<RawStreamEntry>()
            var hitMaxEntriesMidPage = false

            for (entry in entries) {
                val activeBoundary = boundaryTsNs
                if (activeBoundary != null) {
                    val isStale = if (isForward) entry.tsNs < activeBoundary else entry.tsNs > activeBoundary
                    if (isStale) {
                        duplicatesDropped++
                        continue
                    }
                    if (entry.tsNs == activeBoundary) {
                        val key = BoundaryKey(entry.streamLabels, entry.rawLine)
                        val skipCount = remainingToSkip[key] ?: 0
                        if (skipCount > 0) {
                            remainingToSkip[key] = skipCount - 1
                            duplicatesDropped++
                            continue
                        }
                    }
                }

                if (observations.size >= config.maxTotalEntries) {
                    completeness = Completeness.TRUNCATED
                    truncationReason = "MAX_ENTRIES (limit: ${config.maxTotalEntries})"
                    hitMaxEntriesMidPage = true
                    break
                }

                observations += LokiLogParser.parseLine(
                    rawLine = entry.rawLine,
                    streamLabels = entry.streamLabels,
                    defaultTsNs = entry.tsNs
                )
                acceptedEntries += entry
            }

            if (hitMaxEntriesMidPage) {
                break
            }

            if (entries.size < effectiveLimit) {
                break
            }

            if (observations.size >= config.maxTotalEntries) {
                completeness = Completeness.TRUNCATED
                truncationReason = "MAX_ENTRIES (limit: ${config.maxTotalEntries})"
                break
            }
            if (pagesFetched >= config.maxPages) {
                completeness = Completeness.TRUNCATED
                truncationReason = "MAX_PAGES (limit: ${config.maxPages})"
                break
            }

            val pageEdgeTsNs = entries.last().tsNs
            if (acceptedEntries.isEmpty()) {
                completeness = Completeness.TRUNCATED
                truncationReason = if (effectiveLimit >= MAX_LOKI_LIMIT) {
                    "SAME_TIMESTAMP_OVERFLOW at $pageEdgeTsNs ns"
                } else {
                    "NO_CURSOR_PROGRESS at $pageEdgeTsNs ns"
                }
                break
            }

            val advanced: Boolean
            val nextCursorEndNs: Long
            if (isForward) {
                advanced = (boundaryTsNs == null && pageEdgeTsNs > currentStartNs) ||
                    (boundaryTsNs != null && pageEdgeTsNs > boundaryTsNs)
                nextCursorEndNs = currentEndNs
            } else {
                nextCursorEndNs = if (pageEdgeTsNs < Long.MAX_VALUE) pageEdgeTsNs + 1 else pageEdgeTsNs
                advanced = nextCursorEndNs < currentEndNs &&
                    (boundaryTsNs == null || pageEdgeTsNs < boundaryTsNs)
            }

            if (advanced) {
                if (isForward) {
                    currentStartNs = pageEdgeTsNs
                } else {
                    currentEndNs = nextCursorEndNs
                }
                boundaryTsNs = pageEdgeTsNs
                boundarySeenCounts.clear()
                for ((streamLabels, tsNs, rawLine) in acceptedEntries) {
                    if (tsNs == pageEdgeTsNs) {
                        val key = BoundaryKey(streamLabels, rawLine)
                        boundarySeenCounts[key] = (boundarySeenCounts[key] ?: 0) + 1
                    }
                }
                val boundaryCount = boundarySeenCounts.values.sum()
                if (boundaryCount >= pageLimit) {
                    if (pageLimit >= MAX_LOKI_LIMIT) {
                        completeness = Completeness.TRUNCATED
                        truncationReason = "SAME_TIMESTAMP_OVERFLOW at $pageEdgeTsNs ns"
                        break
                    }
                    effectiveLimit = maxOf(pageLimit * 2, boundaryCount + 1).coerceAtMost(MAX_LOKI_LIMIT)
                } else {
                    effectiveLimit = pageLimit
                }
            } else {
                val cursorRegressed = if (isForward) {
                    pageEdgeTsNs < currentStartNs || (boundaryTsNs != null && pageEdgeTsNs < boundaryTsNs)
                } else {
                    (boundaryTsNs != null && pageEdgeTsNs > boundaryTsNs) || nextCursorEndNs > currentEndNs
                }
                if (cursorRegressed) {
                    completeness = Completeness.TRUNCATED
                    truncationReason = "NO_CURSOR_PROGRESS at $pageEdgeTsNs ns"
                    break
                }

                if (boundaryTsNs == null) {
                    boundaryTsNs = pageEdgeTsNs
                }
                for ((streamLabels, tsNs, rawLine) in acceptedEntries) {
                    if (tsNs == boundaryTsNs) {
                        val key = BoundaryKey(streamLabels, rawLine)
                        boundarySeenCounts[key] = (boundarySeenCounts[key] ?: 0) + 1
                    }
                }

                if (effectiveLimit >= MAX_LOKI_LIMIT) {
                    completeness = Completeness.TRUNCATED
                    truncationReason = "SAME_TIMESTAMP_OVERFLOW at $pageEdgeTsNs ns"
                    break
                }
                val boundaryCount = boundarySeenCounts.values.sum()
                effectiveLimit = maxOf(effectiveLimit * 2, boundaryCount + 1).coerceAtMost(MAX_LOKI_LIMIT)
            }
        }

        if (completeness == Completeness.COMPLETE && observations.any { it.parseError != null }) {
            completeness = Completeness.PARTIAL_PARSE_ERRORS
        }

        val warnings = mutableListOf<String>()
        warnings += "Loki historical query is an observational view and does not guarantee delivery completeness or prove absence of Store facts."
        if (truncationReason != null) {
            warnings += "Query result was truncated: $truncationReason"
        }
        if (observations.isEmpty()) {
            warnings += "No logs matched query for operationId '${query.operationId}'. On INFO level, normal success and SKIP traffic produce no events."
        }

        return LokiQueryResult(
            query = query.logQl,
            observations = observations,
            completeness = completeness,
            truncationReason = truncationReason,
            pagesFetched = pagesFetched,
            totalRawEntries = totalRaw,
            duplicatesDropped = duplicatesDropped,
            parseFailures = observations.count { it.parseError != null },
            warnings = warnings
        )
    }

    private fun canonicalLabelsKey(labels: Map<String, String>): String =
        if (labels.isEmpty()) "" else labels.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun executeSinglePage(
        query: String,
        startNs: Long,
        endNs: Long,
        limit: Int,
        direction: QueryDirection
    ): List<RawStreamEntry> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val dir = direction.name.lowercase()
        val queryUrl = "${normalizeBaseUrl(config.baseUrl)}/loki/api/v1/query_range?query=$encodedQuery&start=$startNs&end=$endNs&limit=$limit&direction=$dir"

        val requestBuilder = HttpRequest.newBuilder(URI.create(queryUrl))
            .timeout(config.requestTimeout)
            .header("Accept", "application/json")
            .GET()

        config.orgId?.let { requestBuilder.header("X-Scope-OrgID", it) }
        config.authHeader?.let { requestBuilder.header("Authorization", it) }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw LokiQueryException(response.statusCode(), response.body())
        }

        return LokiLogParser.parseLokiStreamEntries(response.body())
    }

    private fun normalizeBaseUrl(base: URI): String {
        val s = base.toString().trimEnd('/')
        return s
    }
}
