package org.dmg.balletmaster.loki

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.dmg.balletmaster.observation.Completeness
import org.dmg.balletmaster.observation.ObservationScope
import org.dmg.balletmaster.observation.ObservationSourceException
import org.dmg.balletmaster.observation.OperationQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant

class LokiOperationObservationSourceTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private var handler: (HttpExchange) -> Unit = {}

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/loki/api/v1/query_range", HttpHandler { ex -> handler(ex) })
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun mapsLokiResultToProviderNeutralObservationBatch() {
        handler = { ex ->
            val json = """
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [
                      {
                        "stream": { "app": "payments", "environment": "prod" },
                        "values": [
                          [
                            "1731429525000000000",
                            "{\"level\":\"INFO\",\"loggerName\":\"org.dmg.balletmaster.step\",\"message\":\"step.exception\",\"mdc\":{\"ballet.operation\":\"pay\",\"ballet.operationId\":\"ord-7\",\"ballet.step\":\"CHARGE\"},\"kvpList\":[{\"phase\":\"map\"},{\"exception\":\"IllegalStateException\"}]}"
                          ]
                        ]
                      }
                    ]
                  }
                }
            """.trimIndent()
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val source = LokiOperationObservationSource(
            LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        )
        val now = Instant.now()
        val batch = source.fetchByOperation(
            OperationQuery(
                app = "payments",
                environment = "prod",
                operationId = "ord-7",
                start = now.minusSeconds(60),
                end = now,
                scope = ObservationScope.SDK_ONLY
            )
        )

        assertEquals("loki", batch.provider)
        assertEquals(Completeness.COMPLETE, batch.completeness)
        assertEquals(1, batch.observations.size)
        val obs = batch.observations.single()
        assertEquals("loki", obs.provider)
        assertEquals("step.exception", obs.event)
        assertEquals("ord-7", obs.correlation?.operationId)
        assertEquals("CHARGE", obs.correlation?.step)
        assertEquals("IllegalStateException", obs.attributes["exception"])
    }

    @Test
    fun wrapsLokiHttpErrorIntoObservationSourceException() {
        handler = { ex ->
            val bytes = "backend down".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(503, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val source = LokiOperationObservationSource(
            LokiQueryClient(LokiClientConfig(baseUrl = URI.create("http://127.0.0.1:$port")))
        )
        val now = Instant.now()
        val ex = assertThrows(ObservationSourceException::class.java) {
            source.fetchByOperation(
                OperationQuery(
                    app = "payments",
                    environment = "prod",
                    operationId = "ord-7",
                    start = now.minusSeconds(60),
                    end = now
                )
            )
        }

        assertEquals("loki", ex.provider)
        assertEquals(503, ex.statusCode)
        assertTrue(ex.message!!.contains("503"))
    }
}
