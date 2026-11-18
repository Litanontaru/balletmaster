package org.dmg.balletmaster.investigation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "balletmaster.investigation")
data class InvestigationProperties(
    val provider: String = "loki",
    val defaultWindowMinutes: Long = 60L,
    val loki: LokiProperties = LokiProperties()
) {
    data class LokiProperties(
        val baseUrl: URI = URI.create("http://localhost:3100"),
        val orgId: String? = null,
        val authHeader: String? = null,
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val requestTimeout: Duration = Duration.ofSeconds(15),
        val maxWindow: Duration = Duration.ofHours(24),
        val defaultPageLimit: Int = 500,
        val maxTotalEntries: Int = 5000,
        val maxPages: Int = 20
    )
}
