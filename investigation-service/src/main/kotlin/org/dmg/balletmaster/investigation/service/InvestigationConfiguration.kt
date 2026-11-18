package org.dmg.balletmaster.investigation.service

import org.dmg.balletmaster.investigation.OperationInvestigator
import org.dmg.balletmaster.loki.LokiClientConfig
import org.dmg.balletmaster.loki.LokiOperationObservationSource
import org.dmg.balletmaster.loki.LokiQueryClient
import org.dmg.balletmaster.observation.OperationObservationSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class InvestigationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun investigationClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean(OperationObservationSource::class)
    @ConditionalOnProperty(
        prefix = "balletmaster.investigation",
        name = ["provider"],
        havingValue = "loki",
        matchIfMissing = true
    )
    fun lokiOperationObservationSource(properties: InvestigationProperties): OperationObservationSource {
        val loki = properties.loki
        val config = LokiClientConfig(
            baseUrl = loki.baseUrl,
            orgId = loki.orgId?.takeIf { it.isNotBlank() },
            authHeader = loki.authHeader?.takeIf { it.isNotBlank() },
            connectTimeout = loki.connectTimeout,
            requestTimeout = loki.requestTimeout,
            maxWindow = loki.maxWindow,
            defaultPageLimit = loki.defaultPageLimit,
            maxTotalEntries = loki.maxTotalEntries,
            maxPages = loki.maxPages
        )
        return LokiOperationObservationSource(LokiQueryClient(config))
    }

    @Bean
    fun operationInvestigator(source: OperationObservationSource): OperationInvestigator =
        OperationInvestigator(source)

    @Bean
    fun investigationAccessPolicy(properties: InvestigationProperties): InvestigationAccessPolicy =
        InvestigationAccessPolicy(properties.security)
}
