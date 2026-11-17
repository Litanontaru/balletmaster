package org.dmg.balletmaster.loki

import org.dmg.balletmaster.observation.BalletObservation
import org.dmg.balletmaster.observation.ObservationBatch
import org.dmg.balletmaster.observation.ObservationScope
import org.dmg.balletmaster.observation.ObservationSourceException
import org.dmg.balletmaster.observation.OperationObservationSource
import org.dmg.balletmaster.observation.OperationQuery
import java.io.IOException

/**
 * Adapter bridging [LokiQueryClient] into the provider-neutral [OperationObservationSource] port.
 */
class LokiOperationObservationSource(
    private val client: LokiQueryClient,
    private val direction: QueryDirection = QueryDirection.FORWARD
) : OperationObservationSource {

    override val providerName: String = "loki"

    override fun fetchByOperation(query: OperationQuery): ObservationBatch {
        val logQlQuery = LogQlQueryBuilder.build(
            app = query.app,
            environment = query.environment,
            operationId = query.operationId,
            scope = query.scope.toLogScope(),
            extraStreamLabels = query.extraFilters,
            operation = query.operation,
            step = query.step,
            attempt = query.attempt
        )

        val lokiResult = try {
            client.queryRange(
                query = logQlQuery,
                start = query.start,
                end = query.end,
                direction = direction
            )
        } catch (e: LokiQueryException) {
            throw ObservationSourceException(
                provider = providerName,
                message = "Loki query failed with HTTP ${e.statusCode}",
                statusCode = e.statusCode,
                cause = e
            )
        } catch (e: IOException) {
            throw ObservationSourceException(
                provider = providerName,
                message = "Failed to communicate with Loki backend: ${e.message ?: e.javaClass.simpleName}",
                cause = e
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ObservationSourceException(
                provider = providerName,
                message = "Loki query interrupted",
                cause = e
            )
        }

        return lokiResult.toObservationBatch(providerName)
    }

    private fun ObservationScope.toLogScope(): LogScope = when (this) {
        ObservationScope.SDK_ONLY -> LogScope.SDK_ONLY
        ObservationScope.ALL_CORRELATED -> LogScope.ALL_CORRELATED
    }

    private fun LokiQueryResult.toObservationBatch(provider: String): ObservationBatch =
        ObservationBatch(
            provider = provider,
            sourceQuery = query,
            observations = observations.map { it.toBalletObservation(provider) },
            completeness = completeness,
            truncationReason = truncationReason,
            pagesFetched = pagesFetched,
            totalRawEntries = totalRawEntries,
            duplicatesDropped = duplicatesDropped,
            parseFailures = parseFailures,
            warnings = warnings
        )

    private fun LokiLogObservation.toBalletObservation(provider: String): BalletObservation =
        BalletObservation(
            timestampNs = timestampNs,
            timestamp = timestamp,
            provider = provider,
            originLabels = streamLabels,
            rawLine = rawLine,
            logger = logger,
            level = level,
            event = event,
            correlation = correlation,
            attributes = attributes,
            parseError = parseError
        )
}
