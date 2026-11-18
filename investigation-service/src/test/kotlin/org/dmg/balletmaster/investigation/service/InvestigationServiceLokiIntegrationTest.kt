package org.dmg.balletmaster.investigation.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.dmg.balletmaster.BalletMaster
import org.dmg.balletmaster.StepId
import org.dmg.balletmaster.context.CallContextManager
import org.dmg.balletmaster.loki.LokiLogParser
import org.dmg.balletmaster.store.InMemoryStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Full Spring Boot microservice integration test with the real Loki adapter bean
 * wired from application properties against a local mock Loki HTTP server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvestigationServiceLokiIntegrationTest {

    enum class Steps : StepId {
        AUTHORIZE_PAYMENT
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    companion object {
        private val lokiServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/loki/api/v1/query_range") { ex -> currentHandler(ex) }
            start()
        }

        @Volatile
        private var currentHandler: (HttpExchange) -> Unit = { ex ->
            val bytes = """{"status":"success","data":{"resultType":"streams","result":[]}}""".toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerLokiProperties(registry: DynamicPropertyRegistry) {
            registry.add("balletmaster.investigation.provider") { "loki" }
            registry.add("balletmaster.investigation.loki.base-url") { "http://127.0.0.1:${lokiServer.address.port}" }
            registry.add("balletmaster.investigation.security.allowed-apps") { "checkout-service" }
            registry.add("balletmaster.investigation.security.allowed-environments") { "prod" }
        }
    }

    @AfterAll
    fun stopServer() {
        lokiServer.stop(0)
    }

    @Test
    fun springMicroserviceDiagnosesRealCoreBusinessFailureViaLoki() {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        val sdkLogger = loggerContext.getLogger("org.dmg.balletmaster")
        val previousLevel = sdkLogger.level
        sdkLogger.level = Level.INFO

        val encoder = JsonEncoder().apply {
            context = loggerContext
            start()
        }
        val output = ByteArrayOutputStream()
        val appender = OutputStreamAppender<ILoggingEvent>().apply {
            context = loggerContext
            this.encoder = encoder
            setOutputStream(output)
            start()
        }
        sdkLogger.addAppender(appender)

        val ccm = CallContextManager()
        val bm = BalletMaster(InMemoryStore(), ccm)
        try {
            ccm.store("checkout", "chk-777", isReplay = false, attempt = 0)
            val result = bm.start()
                .step(Steps.AUTHORIZE_PAYMENT)
                .map { _, _ -> throw IllegalStateException("SENSITIVE_CARD_DECLINED") }
                .execute()
            assertTrue(result.isFailure)
        } finally {
            ccm.clear()
            sdkLogger.detachAppender(appender)
            appender.stop()
            sdkLogger.level = previousLevel
        }

        val lines = output.toString(StandardCharsets.UTF_8).lines().map { it.trim() }.filter { it.isNotBlank() }
        val baseTsNs = 1731880000000000000L
        val lokiResponse = LokiLogParser.mapper.writeValueAsString(
            mapOf(
                "status" to "success",
                "data" to mapOf(
                    "resultType" to "streams",
                    "result" to listOf(
                        mapOf(
                            "stream" to mapOf("app" to "checkout-service", "environment" to "prod"),
                            "values" to lines.mapIndexed { idx, line ->
                                listOf((baseTsNs + idx * 1_000_000L).toString(), line)
                            }
                        )
                    )
                )
            )
        )
        currentHandler = { ex ->
            val bytes = lokiResponse.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        mockMvc.get("/api/v1/investigations") {
            param("app", "checkout-service")
            param("env", "prod")
            param("operationId", "chk-777")
            param("windowMinutes", "60")
        }.andExpect {
            status { isOk() }
            jsonPath("$.provider") { value("loki") }
            jsonPath("$.operationId") { value("chk-777") }
            jsonPath("$.summaryStatus") { value("FAILURE_DETECTED") }
            jsonPath("$.completeness") { value("COMPLETE") }
            jsonPath("$.findings[0].type") { value("BUSINESS_STEP_FAILURE") }
            jsonPath("$.findings[0].step") { value("AUTHORIZE_PAYMENT") }
            jsonPath("$.findings[0].evidence.length()") { value(2) }
        }
    }
}
