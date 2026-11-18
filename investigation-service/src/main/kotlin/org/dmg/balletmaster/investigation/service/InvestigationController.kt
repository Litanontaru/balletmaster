package org.dmg.balletmaster.investigation.service

import jakarta.servlet.http.HttpServletRequest
import org.dmg.balletmaster.investigation.InvestigationReport
import org.dmg.balletmaster.investigation.OperationInvestigator
import org.dmg.balletmaster.observation.ObservationScope
import org.dmg.balletmaster.observation.OperationQuery
import org.slf4j.LoggerFactory
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
    private val accessPolicy: InvestigationAccessPolicy,
    private val properties: InvestigationProperties,
    private val clock: Clock
) {

    private val auditLogger = LoggerFactory.getLogger("org.dmg.balletmaster.investigation.audit")

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
        @RequestParam(required = false) attempt: Int?,
        httpRequest: HttpServletRequest
    ): InvestigationReport {
        val subject = sanitizeAuditHeader(httpRequest.getHeader(properties.security.auditSubjectHeader))
        val requestId = sanitizeAuditHeader(httpRequest.getHeader(properties.security.auditRequestIdHeader))
        val (start, end) = resolveTimeWindow(windowMinutes, from, to)
        val cleanOperation = operation?.takeIf { it.isNotBlank() }
        val cleanStep = step?.takeIf { it.isNotBlank() }

        try {
            accessPolicy.validateRequest(
                app = app,
                env = env,
                operationId = operationId,
                scope = scope,
                start = start,
                end = end,
                operation = cleanOperation,
                step = cleanStep
            )
        } catch (ex: InvestigationAccessDeniedException) {
            logAudit(
                outcome = "DENIED",
                app = app,
                env = env,
                operationId = operationId,
                scope = scope,
                subject = subject,
                requestId = requestId,
                reason = ex.message
            )
            throw ex
        }

        val query = OperationQuery(
            app = app,
            environment = env,
            operationId = operationId,
            start = start,
            end = end,
            scope = scope,
            operation = cleanOperation,
            step = cleanStep,
            attempt = attempt
        )

        return try {
            accessPolicy.executeWithinConcurrencyLimit {
                logAudit(
                    outcome = "ALLOWED",
                    app = app,
                    env = env,
                    operationId = operationId,
                    scope = scope,
                    subject = subject,
                    requestId = requestId,
                    reason = null
                )
                investigator.investigate(query)
            }
        } catch (ex: InvestigationRateLimitException) {
            logAudit(
                outcome = "RATE_LIMITED",
                app = app,
                env = env,
                operationId = operationId,
                scope = scope,
                subject = subject,
                requestId = requestId,
                reason = ex.message
            )
            throw ex
        }
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

    private fun logAudit(
        outcome: String,
        app: String,
        env: String,
        operationId: String,
        scope: ObservationScope,
        subject: String?,
        requestId: String?,
        reason: String?
    ) {
        val builder = if (outcome == "ALLOWED") auditLogger.atInfo() else auditLogger.atWarn()
        builder
            .setMessage("investigation.audit")
            .addKeyValue("outcome", outcome)
            .addKeyValue("app", sanitizeAuditValue(app))
            .addKeyValue("env", sanitizeAuditValue(env))
            .addKeyValue("operationId", sanitizeAuditValue(operationId))
            .addKeyValue("scope", scope.name)
            .apply {
                subject?.let { addKeyValue("subject", it) }
                requestId?.let { addKeyValue("requestId", it) }
                reason?.let { addKeyValue("reason", it) }
            }
            .log()
    }

    private fun sanitizeAuditHeader(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }?.let(::sanitizeAuditValue)

    private fun sanitizeAuditValue(raw: String): String =
        raw.replace(Regex("[\\p{Cntrl}]"), "").take(128)
}
