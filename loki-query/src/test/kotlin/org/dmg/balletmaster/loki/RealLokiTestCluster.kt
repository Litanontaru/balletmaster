package org.dmg.balletmaster.loki

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Manages access to a real Grafana Loki server for integration tests.
 *
 * Priority order:
 * 1. `-Dballetmaster.test.loki.url=<url>`
 * 2. Environment variable `LOKI_URL`
 * 3. Local standalone Loki / Docker Compose on `http://127.0.0.1:3100` (probed via `/ready`)
 * 4. Testcontainers (`grafana/loki:3.0.0`) if Docker daemon is running
 * 5. If none available:
 *    - Fails if `-Dballetmaster.requireRealLoki=true` (or `-Preal-loki` Maven profile)
 *    - Otherwise skips gracefully via JUnit 5 [assumeTrue].
 */
object RealLokiTestCluster {

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()
    }

    @Volatile
    private var startedContainer: GenericContainer<*>? = null

    @Volatile
    private var resolvedBaseUrl: URI? = null

    fun resolveLokiUri(): URI? {
        resolvedBaseUrl?.let { return it }
        synchronized(this) {
            resolvedBaseUrl?.let { return it }

            // 1. Explicit system property
            System.getProperty("balletmaster.test.loki.url")?.takeIf { it.isNotBlank() }?.let { raw ->
                val uri = URI.create(raw.trimEnd('/'))
                if (isLokiReady(uri)) {
                    resolvedBaseUrl = uri
                    return uri
                }
            }

            // 2. Environment variable
            System.getenv("LOKI_URL")?.takeIf { it.isNotBlank() }?.let { raw ->
                val uri = URI.create(raw.trimEnd('/'))
                if (isLokiReady(uri)) {
                    resolvedBaseUrl = uri
                    return uri
                }
            }

            // 3. Local default endpoint (e.g. started via docker compose)
            val defaultLocalUri = URI.create("http://127.0.0.1:3100")
            if (isLokiReady(defaultLocalUri)) {
                resolvedBaseUrl = defaultLocalUri
                return defaultLocalUri
            }

            // 4. Testcontainers if Docker daemon is running
            try {
                if (DockerClientFactory.instance().isDockerAvailable()) {
                    val container = GenericContainer("grafana/loki:3.0.0")
                        .withExposedPorts(3100)
                        .withCommand("-config.file=/etc/loki/local-config.yaml")
                        .waitingFor(Wait.forHttp("/ready").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)))
                    container.start()
                    startedContainer = container
                    val uri = URI.create("http://${container.host}:${container.getMappedPort(3100)}")
                    resolvedBaseUrl = uri
                    Runtime.getRuntime().addShutdownHook(Thread {
                        try {
                            startedContainer?.stop()
                        } catch (_: Throwable) {}
                    })
                    return uri
                }
            } catch (_: Throwable) {
                // Docker daemon not running or Testcontainers initialization failed
            }

            return null
        }
    }

    fun getOrStartLoki(): URI {
        val uri = resolveLokiUri()
        if (uri != null) return uri

        val requireRealLoki = System.getProperty("balletmaster.requireRealLoki")?.toBoolean() == true
        if (requireRealLoki) {
            throw AssertionError(
                "Real Loki instance was required (-Dballetmaster.requireRealLoki=true / -Preal-loki), " +
                    "but no running Loki was found at -Dballetmaster.test.loki.url, LOKI_URL, http://127.0.0.1:3100, " +
                    "and Docker daemon is not accessible for Testcontainers. " +
                    "To start a local Loki instance, run: 'docker compose -f monitoring/docker-compose-loki.yml up -d'."
            )
        }

        assumeTrue(
            false,
            "Skipping real Loki integration test: no running Loki found at -Dballetmaster.test.loki.url, " +
                "LOKI_URL, http://127.0.0.1:3100, and Docker is not running. " +
                "To execute this test against real Loki, start Loki via: 'docker compose -f monitoring/docker-compose-loki.yml up -d'."
        )
        error("Unreachable")
    }

    fun isLokiReady(baseUrl: URI): Boolean {
        return try {
            val request = HttpRequest.newBuilder(URI.create("${baseUrl.toString().trimEnd('/')}/ready"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            response.statusCode() == 200
        } catch (_: Throwable) {
            false
        }
    }

    fun pushLogs(
        baseUrl: URI,
        streamLabels: Map<String, String>,
        entries: List<Pair<Long, String>>,
        orgId: String? = null
    ) {
        val payloadMap = mapOf(
            "streams" to listOf(
                mapOf(
                    "stream" to streamLabels,
                    "values" to entries.map { (tsNs, line) -> listOf(tsNs.toString(), line) }
                )
            )
        )
        val jsonPayload = LokiLogParser.mapper.writeValueAsString(payloadMap)

        val requestBuilder = HttpRequest.newBuilder(URI.create("${baseUrl.toString().trimEnd('/')}/loki/api/v1/push"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))

        if (!orgId.isNullOrBlank()) {
            requestBuilder.header("X-Scope-OrgID", orgId)
        }

        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in setOf(200, 204)) {
            throw IllegalStateException("Failed to push logs to real Loki: HTTP ${response.statusCode()} ${response.body()}")
        }
    }

    fun waitForQuery(
        client: LokiQueryClient,
        query: LogQlQuery,
        start: Instant,
        end: Instant,
        expectedMinEntries: Int = 1,
        timeout: Duration = Duration.ofSeconds(10)
    ): LokiQueryResult {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        var lastResult: LokiQueryResult? = null

        while (System.currentTimeMillis() < deadline) {
            val result = client.queryRange(query, start, end)
            lastResult = result
            if (result.observations.size >= expectedMinEntries) {
                return result
            }
            Thread.sleep(250)
        }
        return lastResult ?: error("Timed out waiting for LogQL query: ${query.logQl}")
    }
}
