package org.dmg.balletmaster.loki

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        server.createContext("/loki/api/v1/query_range", HttpHandler { exchange ->
            try {
                handler(exchange)
            } catch (e: Exception) {
                val err = e.message?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
                exchange.sendResponseHeaders(500, err.size.toLong())
                exchange.responseBody.write(err)
                exchange.close()
            }
        })
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

    @Test
    fun sameTimestampOverflowHaltsWithTruncated() {
        handler = { ex ->
            // Page limit = 2, but both items have timestamp 5000000000
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
                          [ "5000000000", "{\"message\":\"m1\",\"ballet_operationId\":\"op-1\"}" ],
                          [ "5000000000", "{\"message\":\"m2\",\"ballet_operationId\":\"op-1\"}" ]
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
        val start = Instant.ofEpochSecond(5)
        val end = Instant.ofEpochSecond(10)

        val result = client.queryRange(query, start, end, pageLimit = 2)

        assertEquals(Completeness.TRUNCATED, result.completeness)
        assertTrue(result.truncationReason!!.contains("SAME_TIMESTAMP_OVERFLOW"))
        assertEquals(2, result.observations.size)
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
