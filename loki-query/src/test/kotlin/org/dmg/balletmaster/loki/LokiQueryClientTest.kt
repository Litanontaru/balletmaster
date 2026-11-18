package org.dmg.balletmaster.loki

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

class LokiQueryClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private var handler: (HttpExchange) -> Unit = {}

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/loki/api/v1/query_range") { exchange ->
            try {
                handler(exchange)
            } catch (e: Exception) {
                val err = e.message?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
                exchange.sendResponseHeaders(500, err.size.toLong())
                exchange.responseBody.write(err)
                exchange.close()
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun respondJson(exchange: HttpExchange, statusCode: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    fun emptyResultGivesCompleteWithZeroObservations() {
        handler = { ex ->
            respondJson(
                ex, 200,
                """{"status":"success","data":{"resultType":"streams","result":[]}}"""
            )
        }

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val now = Instant.now()
        val result = client.queryRange(query, now.minusSeconds(60), now)

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(0, result.observations.size)
        assertEquals(0, result.duplicatesDropped)
        assertTrue(result.warnings.any { it.contains("No logs matched query") })
    }

    @Test
    fun multiPagePaginationAndBoundaryDeduplication() {
        handler = { ex ->
            val uri = ex.requestURI.toString()
            if (uri.contains("start=1000000000")) {
                // Page 1: 2 entries (limit = 2)
                respondJson(
                    ex, 200,
                    """
                    {
                      "status": "success",
                      "data": {
                        "resultType": "streams",
                        "result": [
                          {
                            "stream": { "app": "app" },
                            "values": [
                              [ "1000000000", "{\"message\":\"e1\",\"ballet_operationId\":\"op-1\"}" ],
                              [ "2000000000", "{\"message\":\"e2\",\"ballet_operationId\":\"op-1\"}" ]
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            } else if (uri.contains("start=2000000000")) {
                // Page 2: returns boundary duplicate at 2000000000 + new entry at 3000000000
                respondJson(
                    ex, 200,
                    """
                    {
                      "status": "success",
                      "data": {
                        "resultType": "streams",
                        "result": [
                          {
                            "stream": { "app": "app" },
                            "values": [
                              [ "2000000000", "{\"message\":\"e2\",\"ballet_operationId\":\"op-1\"}" ],
                              [ "3000000000", "{\"message\":\"e3\",\"ballet_operationId\":\"op-1\"}" ]
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            } else {
                // Page 3: empty
                respondJson(
                    ex, 200,
                    """{"status":"success","data":{"resultType":"streams","result":[]}}"""
                )
            }
        }

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val start = Instant.ofEpochSecond(1)
        val end = Instant.ofEpochSecond(10)

        val result = client.queryRange(query, start, end, pageLimit = 2)

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(3, result.observations.size)
        assertEquals(listOf("e1", "e2", "e3"), result.observations.map { it.event })
        assertEquals(1, result.duplicatesDropped)
    }

    private fun parseQueryParams(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty()
            .split("&")
            .filter { it.contains("=") }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx) to it.substring(idx + 1)
            }

    /**
     * Simulates Grafana Loki /loki/api/v1/query_range semantics:
     * start is inclusive (ts >= start), end is exclusive (ts < end),
     * direction=forward returns ascending timestamps, direction=backward returns descending timestamps,
     * and requests exceeding [serverMaxLimit] return HTTP 400 like Loki's max_entries_limit_per_query.
     */
    private fun serveCompliantLokiDataset(
        dataset: List<Pair<Long, String>>,
        serverMaxLimit: Int = 5000,
        streamLabels: Map<String, String> = mapOf("app" to "app"),
        onRequest: ((startNs: Long, endNs: Long, limit: Int, direction: String) -> Unit)? = null
    ) {
        handler = { ex ->
            val params = parseQueryParams(ex.requestURI)
            val startNs = params["start"]!!.toLong()
            val endNs = params["end"]!!.toLong()
            val limit = params["limit"]!!.toInt()
            val direction = params["direction"] ?: "forward"
            onRequest?.invoke(startNs, endNs, limit, direction)

            if (limit > serverMaxLimit) {
                respondJson(ex, 400, "max entries limit per query exceeded, limit > max_entries_limit ($serverMaxLimit)")
            } else {
                val inRange = dataset.filter { (ts, _) -> ts >= startNs && ts < endNs }
                val ordered = if (direction == "backward") {
                    inRange.sortedWith(compareByDescending<Pair<Long, String>> { it.first }.thenBy { it.second })
                } else {
                    inRange.sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second })
                }
                val slice = ordered.take(limit)
                val result = if (slice.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        mapOf(
                            "stream" to streamLabels,
                            "values" to slice.map { (ts, msg) ->
                                listOf(ts.toString(), """{"message":"$msg","ballet_operationId":"op-1"}""")
                            }
                        )
                    )
                }
                val payload = mapOf(
                    "status" to "success",
                    "data" to mapOf(
                        "resultType" to "streams",
                        "result" to result
                    )
                )
                respondJson(ex, 200, LokiLogParser.mapper.writeValueAsString(payload))
            }
        }
    }

    @Test
    fun sameTimestampOverflowHaltsWithTruncatedWhenServerRejectsEscalatedLimit() {
        val dataset = listOf(
            5_000_000_000L to "m1",
            5_000_000_000L to "m2",
            5_000_000_000L to "m3",
            6_000_000_000L to "m4"
        )
        serveCompliantLokiDataset(dataset, serverMaxLimit = 2)

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val start = Instant.ofEpochSecond(5)
        val end = Instant.ofEpochSecond(10)

        val result = client.queryRange(query, start, end, pageLimit = 2)

        assertEquals(Completeness.TRUNCATED, result.completeness)
        assertTrue(result.truncationReason!!.contains("SAME_TIMESTAMP_OVERFLOW"))
        assertEquals(2, result.observations.size)
        assertEquals(listOf("m1", "m2"), result.observations.map { it.event })
    }

    @Test
    fun sameTimestampOverflowAtMaxLokiLimitHaltsWithTruncated() {
        val ts = 5_000_000_000L
        val dataset = (1..5000).map { ts to "m$it" }
        serveCompliantLokiDataset(dataset, serverMaxLimit = 5000)

        val client = LokiQueryClient(
            LokiClientConfig(
                baseUrl = URI.create("http://127.0.0.1:$port"),
                maxTotalEntries = 10_000
            )
        )
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(query, Instant.ofEpochSecond(5), Instant.ofEpochSecond(10), pageLimit = 5000)

        assertEquals(Completeness.TRUNCATED, result.completeness)
        assertTrue(result.truncationReason!!.contains("SAME_TIMESTAMP_OVERFLOW"))
        assertEquals(5000, result.observations.size)
        assertEquals(1, result.pagesFetched)
    }

    @Test
    fun continuesPastRepeatedTimestampOnPageBoundaryForward() {
        val dataset = listOf(
            1_000_000_000L to "A",
            2_000_000_000L to "B",
            2_000_000_000L to "C",
            3_000_000_000L to "D"
        )
        serveCompliantLokiDataset(dataset)

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(10),
            direction = QueryDirection.FORWARD,
            pageLimit = 2
        )

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertNull(result.truncationReason)
        assertEquals(listOf("A", "B", "C", "D"), result.observations.map { it.event })
    }

    @Test
    fun continuesPastRepeatedTimestampOnPageBoundaryBackward() {
        val dataset = listOf(
            1_000_000_000L to "A",
            2_000_000_000L to "B",
            2_000_000_000L to "C",
            3_000_000_000L to "D"
        )
        serveCompliantLokiDataset(dataset)

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(4),
            direction = QueryDirection.BACKWARD,
            pageLimit = 2
        )

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertNull(result.truncationReason)
        assertEquals(listOf("D", "B", "C", "A"), result.observations.map { it.event })
    }

    @Test
    fun readsLargeSameTimestampGroupAndContinuesNormalPaginationAfterward() {
        val dataset = listOf(
            1_000_000_000L to "A",
            2_000_000_000L to "B1",
            2_000_000_000L to "B2",
            2_000_000_000L to "B3",
            2_000_000_000L to "B4",
            2_000_000_000L to "B5",
            3_000_000_000L to "D",
            4_000_000_000L to "E",
            5_000_000_000L to "F"
        )
        val requestedLimits = mutableListOf<Int>()
        serveCompliantLokiDataset(dataset) { _, _, limit, _ -> requestedLimits += limit }

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(10),
            direction = QueryDirection.FORWARD,
            pageLimit = 2
        )

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(listOf("A", "B1", "B2", "B3", "B4", "B5", "D", "E", "F"), result.observations.map { it.event })
        // Limit escalates for the 2ns cluster, then resets to pageLimit (2) once cursor advances past 2ns
        assertTrue(requestedLimits.any { it > 2 })
        assertEquals(2, requestedLimits.last())
    }

    @Test
    fun preservesGenuineIdenticalDuplicateLinesAtSameTimestampWhileDroppingBoundaryOverlaps() {
        val dataset = listOf(
            1_000_000_000L to "A",
            2_000_000_000L to "DUP",
            2_000_000_000L to "DUP",
            2_000_000_000L to "DUP",
            3_000_000_000L to "D"
        )
        serveCompliantLokiDataset(dataset)

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(10),
            direction = QueryDirection.FORWARD,
            pageLimit = 2
        )

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(listOf("A", "DUP", "DUP", "DUP", "D"), result.observations.map { it.event })
        assertTrue(result.duplicatesDropped > 0)
    }

    @Test
    fun strictlyEnforcesMaxTotalEntriesMidPageAndReportsTruncated() {
        val dataset = listOf(
            1_000_000_000L to "e1",
            2_000_000_000L to "e2",
            3_000_000_000L to "e3",
            4_000_000_000L to "e4"
        )
        serveCompliantLokiDataset(dataset)

        val client = LokiQueryClient(
            LokiClientConfig(
                baseUrl = URI.create("http://127.0.0.1:$port"),
                maxTotalEntries = 3
            )
        )
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        // Partial page (4 < pageLimit=10) still exceeds maxTotalEntries=3
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(10),
            pageLimit = 10
        )

        assertEquals(Completeness.TRUNCATED, result.completeness)
        assertTrue(result.truncationReason!!.contains("MAX_ENTRIES"))
        assertEquals(3, result.observations.size)
        assertEquals(listOf("e1", "e2", "e3"), result.observations.map { it.event })
    }

    @Test
    fun exactMatchWithMaxTotalEntriesOnPartialPageIsComplete() {
        val dataset = listOf(
            1_000_000_000L to "e1",
            2_000_000_000L to "e2",
            3_000_000_000L to "e3"
        )
        serveCompliantLokiDataset(dataset)

        val client = LokiQueryClient(
            LokiClientConfig(
                baseUrl = URI.create("http://127.0.0.1:$port"),
                maxTotalEntries = 3
            )
        )
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(10),
            pageLimit = 10
        )

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertNull(result.truncationReason)
        assertEquals(3, result.observations.size)
    }

    @Test
    fun haltsWithoutInfiniteLoopWhenServerMakesNoCursorProgress() {
        var calls = 0
        handler = { ex ->
            calls++
            // Misbehaving server ignores start cursor and always returns the same 2 older entries
            respondJson(
                ex, 200,
                """
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [
                      {
                        "stream": { "app": "app" },
                        "values": [
                          [ "1000000000", "{\"message\":\"e1\",\"ballet_operationId\":\"op-1\"}" ],
                          [ "2000000000", "{\"message\":\"e2\",\"ballet_operationId\":\"op-1\"}" ]
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        }

        val client = LokiQueryClient(
            LokiClientConfig(
                baseUrl = URI.create("http://127.0.0.1:$port"),
                maxPages = 10
            )
        )
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(10),
            pageLimit = 2
        )

        assertEquals(Completeness.TRUNCATED, result.completeness)
        assertTrue(result.truncationReason!!.contains("NO_CURSOR_PROGRESS"))
        assertEquals(2, calls)
        assertEquals(listOf("e1", "e2"), result.observations.map { it.event })
    }

    @Test
    fun backwardPaginationAcrossMultiplePagesWithoutSameTimestampCollision() {
        val dataset = listOf(
            1_000_000_000L to "e1",
            2_000_000_000L to "e2",
            3_000_000_000L to "e3",
            4_000_000_000L to "e4",
            5_000_000_000L to "e5"
        )
        serveCompliantLokiDataset(dataset)

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val result = client.queryRange(
            query = query,
            start = Instant.ofEpochSecond(1),
            end = Instant.ofEpochSecond(10),
            direction = QueryDirection.BACKWARD,
            pageLimit = 2
        )

        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(listOf("e5", "e4", "e3", "e2", "e1"), result.observations.map { it.event })
    }

    @Test
    fun partialParseErrorsRecordedWhenLineIsMalformed() {
        handler = { ex ->
            respondJson(
                ex, 200,
                """
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [
                      {
                        "stream": { "app": "app" },
                        "values": [
                          [ "1000000000", "{\"message\":\"valid\",\"ballet_operationId\":\"op-1\"}" ],
                          [ "2000000000", "RAW_UNPARSED_TEXT_LINE" ]
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        }

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val now = Instant.now()
        val result = client.queryRange(query, now.minusSeconds(10), now)

        assertEquals(Completeness.PARTIAL_PARSE_ERRORS, result.completeness)
        assertEquals(2, result.observations.size)
        assertEquals(1, result.parseFailures)
        assertEquals("valid", result.observations[0].event)
        assertTrue(result.observations[1].parseError != null)
    }

    @Test
    fun httpErrorsThrowLokiQueryException() {
        handler = { ex ->
            val msg = "Unauthorized: invalid tenant token"
            val bytes = msg.toByteArray()
            ex.sendResponseHeaders(401, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }

        val client = LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val now = Instant.now()

        val ex = assertThrows(LokiQueryException::class.java) {
            client.queryRange(query, now.minusSeconds(10), now)
        }
        assertEquals(401, ex.statusCode)
        assertTrue(ex.responseBody.contains("Unauthorized"))
    }

    @Test
    fun windowLimitsEnforced() {
        val client = LokiQueryClient(
            LokiClientConfig(
                baseUrl = URI.create("http://127.0.0.1:$port"),
                maxWindow = Duration.ofHours(2)
            )
        )
        val query = LogQlQueryBuilder.build("app", "dev", "op-1")
        val now = Instant.now()

        // 1. start > end
        assertThrows(IllegalArgumentException::class.java) {
            client.queryRange(query, now, now.minusSeconds(10))
        }

        // 2. window > maxWindow
        assertThrows(IllegalArgumentException::class.java) {
            client.queryRange(query, now.minus(Duration.ofHours(5)), now)
        }
    }
}
