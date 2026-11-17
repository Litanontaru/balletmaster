package org.dmg.balletmaster.investigation

import org.dmg.balletmaster.observation.OperationObservationSource
import org.dmg.balletmaster.observation.OperationQuery

/**
 * Provider-agnostic orchestrator fetching observations via [OperationObservationSource]
 * and diagnosing them with [ObservationAnalyzer].
 */
class OperationInvestigator(
    private val source: OperationObservationSource,
    private val analyzer: ObservationAnalyzer = ObservationAnalyzer
) {

    val providerName: String get() = source.providerName

    fun investigate(query: OperationQuery): InvestigationReport {
        val batch = source.fetchByOperation(query)
        return analyzer.analyze(query, batch)
    }
}
