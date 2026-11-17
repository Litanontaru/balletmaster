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
        require(pageLimit in 1..5000) { "pageLimit must be between 1 and 5000 (got $pageLimit)" }

        val observations = mutableListOf<LokiLogObservation>()
        var pagesFetched = 0
        var totalRaw = 0
        var duplicatesDropped = 0
        var completeness = Completeness.COMPLETE
        var truncationReason: String? = null

        val boundaryDedupKeys = mutableSetOf<String>()

        if (direction == QueryDirection.FORWARD) {
            var currentStartNs = LokiLogParser.instantToNanos(start)
            val targetEndNs = LokiLogParser.instantToNanos(end)

            while (pagesFetched < config.maxPages && observations.size < config.maxTotalEntries) {
                pagesFetched++
                val entries = executeSinglePage(
                    query = query.logQl,
                    startNs = currentStartNs,
                    endNs = targetEndNs,
                    limit = pageLimit,
                    direction = direction
                ).sortedWith(compareBy({ it.tsNs }, { it.rawLine }))

                totalRaw += entries.size
                if (entries.isEmpty()) {
                    break
                }

                for (entry in entries) {
                    if (entry.tsNs == currentStartNs && entry.dedupKey in boundaryDedupKeys) {
                        duplicatesDropped++
                        continue
                    }
                    observations += LokiLogParser.parseLine(
                        rawLine = entry.rawLine,
                        streamLabels = entry.streamLabels,
                        defaultTsNs = entry.tsNs
                    )
                }

                if (entries.size < pageLimit) {
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

                val lastTsNs = entries.last().tsNs
                if (lastTsNs == currentStartNs) {
                    completeness = Completeness.TRUNCATED
                    truncationReason = "SAME_TIMESTAMP_OVERFLOW at $lastTsNs ns"
                    break
                }

                currentStartNs = lastTsNs
                boundaryDedupKeys.clear()
                for (e in entries) {
                    if (e.tsNs == lastTsNs) {
                        boundaryDedupKeys += e.dedupKey
                    }
                }
            }
        } else {
            var currentEndNs = LokiLogParser.instantToNanos(end)
            val targetStartNs = LokiLogParser.instantToNanos(start)

            while (pagesFetched < config.maxPages && observations.size < config.maxTotalEntries) {
                pagesFetched++
                val entries = executeSinglePage(
                    query = query.logQl,
                    startNs = targetStartNs,
                    endNs = currentEndNs,
                    limit = pageLimit,
                    direction = direction
                ).sortedWith(compareByDescending<RawStreamEntry> { it.tsNs }.thenBy { it.rawLine })

                totalRaw += entries.size
                if (entries.isEmpty()) {
                    break
                }

                for (entry in entries) {
                    if (entry.tsNs == currentEndNs && entry.dedupKey in boundaryDedupKeys) {
                        duplicatesDropped++
                        continue
                    }
                    observations += LokiLogParser.parseLine(
                        rawLine = entry.rawLine,
                        streamLabels = entry.streamLabels,
                        defaultTsNs = entry.tsNs
                    )
                }

                if (entries.size < pageLimit) {
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

                val minTsNs = entries.last().tsNs
                if (minTsNs == currentEndNs) {
                    completeness = Completeness.TRUNCATED
                    truncationReason = "SAME_TIMESTAMP_OVERFLOW at $minTsNs ns"
                    break
                }

                currentEndNs = minTsNs
                boundaryDedupKeys.clear()
                for (e in entries) {
                    if (e.tsNs == minTsNs) {
                        boundaryDedupKeys += e.dedupKey
                    }
                }
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
