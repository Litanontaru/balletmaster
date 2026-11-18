package org.dmg.balletmaster.investigation.service

import org.dmg.balletmaster.observation.ObservationScope
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Semaphore

class InvestigationAccessDeniedException(message: String) : RuntimeException(message)

class InvestigationRateLimitException(message: String) : RuntimeException(message)

/**
 * Defense-in-depth policy guard enforcing target allowlists (fail-closed by default),
 * scope restrictions, REST query window/parameter bounds, and concurrency caps.
 *
 * Authentication (OIDC/JWT/mTLS) is expected to be enforced at the external API Gateway / Ingress / Service Mesh.
 */
class InvestigationAccessPolicy(
    private val security: InvestigationProperties.SecurityProperties
) {

    init {
        require(security.maxConcurrentQueries >= 1) {
            "security.maxConcurrentQueries must be >= 1 (got ${security.maxConcurrentQueries})"
        }
        require(security.maxParameterLength >= 1) {
            "security.maxParameterLength must be >= 1 (got ${security.maxParameterLength})"
        }
        require(!security.maxWindow.isNegative && !security.maxWindow.isZero) {
            "security.maxWindow must be positive (got ${security.maxWindow})"
        }
    }

    private val concurrencySlots = Semaphore(security.maxConcurrentQueries)

    fun validateRequest(
        app: String,
        env: String,
        operationId: String,
        scope: ObservationScope,
        start: Instant,
        end: Instant,
        operation: String?,
        step: String?
    ) {
        validateParameter("app", app, required = true)
        validateParameter("env", env, required = true)
        validateParameter("operationId", operationId, required = true)
        validateParameter("operation", operation, required = false)
        validateParameter("step", step, required = false)

        require(!start.isAfter(end)) { "start ($start) must be <= end ($end)" }
        val requestedWindow = Duration.between(start, end)
        require(requestedWindow <= security.maxWindow) {
            "Requested query window ($requestedWindow) exceeds service max-window (${security.maxWindow})"
        }

        if (security.allowedApps.isNotEmpty()) {
            if (app !in security.allowedApps) {
                throw InvestigationAccessDeniedException("Access to the requested application is denied by policy")
            }
        } else if (!security.allowUnrestrictedTargets) {
            throw InvestigationAccessDeniedException("Application allowlist is not configured (fail-closed policy)")
        }

        if (security.allowedEnvironments.isNotEmpty()) {
            if (env !in security.allowedEnvironments) {
                throw InvestigationAccessDeniedException("Access to the requested environment is denied by policy")
            }
        } else if (!security.allowUnrestrictedTargets) {
            throw InvestigationAccessDeniedException("Environment allowlist is not configured (fail-closed policy)")
        }

        if (scope == ObservationScope.ALL_CORRELATED && !security.allowAllCorrelated) {
            throw InvestigationAccessDeniedException("Scope ALL_CORRELATED is disabled by service policy")
        }
    }

    fun <T> executeWithinConcurrencyLimit(block: () -> T): T {
        if (!concurrencySlots.tryAcquire()) {
            throw InvestigationRateLimitException("Too many concurrent investigation queries; retry later")
        }
        return try {
            block()
        } finally {
            concurrencySlots.release()
        }
    }

    private fun validateParameter(name: String, value: String?, required: Boolean) {
        if (value == null) {
            require(!required) { "$name must not be null" }
            return
        }
        if (required) {
            require(value.isNotBlank()) { "$name must not be blank" }
        }
        require(value.length <= security.maxParameterLength) {
            "$name exceeds maximum allowed length of ${security.maxParameterLength}"
        }
        require(value.none { it.isISOControl() }) {
            "$name must not contain control characters"
        }
    }
}
