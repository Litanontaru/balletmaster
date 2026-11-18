package org.dmg.balletmaster.investigation.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "balletmaster.investigation")
data class InvestigationProperties(
    val provider: String = "loki",
    val defaultWindowMinutes: Long = 60L,
    val security: SecurityProperties = SecurityProperties(),
    val loki: LokiProperties = LokiProperties()
) {
    data class SecurityProperties(
        val allowedApps: Set<String> = emptySet(),
        val allowedEnvironments: Set<String> = emptySet(),
        val allowUnrestrictedTargets: Boolean = false,
        val allowAllCorrelated: Boolean = false,
        val maxWindow: Duration = Duration.ofHours(6),
        val maxConcurrentQueries: Int = 10,
        val maxParameterLength: Int = 128,
        val auditSubjectHeader: String = "X-Forwarded-User",
        val auditRequestIdHeader: String = "X-Request-Id"
    )

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
    ) {
        override fun toString(): String =
            "LokiProperties(baseUrl=$baseUrl, orgId=$orgId, authHeader=${if (authHeader.isNullOrBlank()) "null" else "***"}, " +
                "connectTimeout=$connectTimeout, requestTimeout=$requestTimeout, maxWindow=$maxWindow, " +
                "defaultPageLimit=$defaultPageLimit, maxTotalEntries=$maxTotalEntries, maxPages=$maxPages)"
    }
}
