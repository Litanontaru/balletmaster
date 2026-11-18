package org.dmg.balletmaster.investigation.service

import org.dmg.balletmaster.investigation.InvestigationReport
import org.dmg.balletmaster.investigation.OperationInvestigator
import org.dmg.balletmaster.observation.ObservationScope
import org.dmg.balletmaster.observation.OperationQuery
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/api/v1/investigations")
class InvestigationController(
    private val investigator: OperationInvestigator,
    private val properties: InvestigationProperties,
    private val clock: Clock
) {

    @GetMapping
    fun investigate(
        @RequestParam app: String,
        @RequestParam env: String,
        @RequestParam operationId: String,
        @RequestParam(required = false) windowMinutes: Long?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "SDK_ONLY") scope: ObservationScope,
        @RequestParam(required = false) operation: String?,
        @RequestParam(required = false) step: String?,
        @RequestParam(required = false) attempt: Int?
    ): InvestigationReport {
        val (start, end) = resolveTimeWindow(windowMinutes, from, to)

        val query = OperationQuery(
            app = app,
            environment = env,
            operationId = operationId,
            start = start,
            end = end,
            scope = scope,
            operation = operation?.takeIf { it.isNotBlank() },
            step = step?.takeIf { it.isNotBlank() },
            attempt = attempt
        )

        return investigator.investigate(query)
    }

    private fun resolveTimeWindow(windowMinutes: Long?, from: Instant?, to: Instant?): Pair<Instant, Instant> {
        if (from != null || to != null) {
            require(from != null && to != null) {
                "Both 'from' and 'to' query parameters must be provided together"
            }
            return from to to
        }

        val minutes = windowMinutes ?: properties.defaultWindowMinutes
        require(minutes > 0L) { "windowMinutes must be positive (got $minutes)" }
        val end = Instant.now(clock)
        val start = end.minus(Duration.ofMinutes(minutes))
        return start to end
    }
}
