package org.dmg.balletmaster.loki

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.dmg.balletmaster.loki.cli.LokiQueryCli
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class LokiQueryCliTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/loki/api/v1/query_range") { exchange: HttpExchange ->
            val json = """{"status":"success","data":{"resultType":"streams","result":[]}}"""
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun returnsExitCode1OnMissingRequiredArgs() {
        val code = LokiQueryCli.run(arrayOf("--app", "test-app"))
        assertEquals(1, code)
    }

    @Test
    fun runsSuccessfullyWithValidArguments() {
        val args = arrayOf(
            "--url", "http://127.0.0.1:$port",
            "--app", "order-service",
            "--env", "test",
            "--op-id", "ord-1",
            "--scope", "SDK_ONLY",
            "--window", "30"
        )
        val code = LokiQueryCli.run(args)
        assertEquals(0, code)
    }
}
