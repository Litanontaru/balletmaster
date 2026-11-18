package org.dmg.balletmaster.investigation.service

import org.dmg.balletmaster.observation.ObservationSourceException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ApiErrorResponse(
    val error: String,
    val message: String,
    val provider: String? = null
)

@RestControllerAdvice
class ApiExceptionHandler {

    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                error = "INVALID_REQUEST",
                message = ex.message ?: "Invalid request parameters"
            )
        )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                error = "INVALID_REQUEST",
                message = "Missing required parameter '${ex.parameterName}'"
            )
        )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                error = "INVALID_REQUEST",
                message = "Invalid value for parameter '${ex.name}'"
            )
        )

    @ExceptionHandler(InvestigationAccessDeniedException::class)
    fun handleAccessDenied(ex: InvestigationAccessDeniedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                error = "ACCESS_DENIED",
                message = ex.message ?: "Access denied"
            )
        )

    @ExceptionHandler(InvestigationRateLimitException::class)
    fun handleRateLimit(ex: InvestigationRateLimitException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
            ApiErrorResponse(
                error = "TOO_MANY_REQUESTS",
                message = ex.message ?: "Too many concurrent requests"
            )
        )

    @ExceptionHandler(ObservationSourceException::class)
    fun handleUpstreamFailure(ex: ObservationSourceException): ResponseEntity<ApiErrorResponse> {
        logger.warn("Upstream observation source '{}' failed (statusCode={}): {}", ex.provider, ex.statusCode, ex.message)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            ApiErrorResponse(
                error = "UPSTREAM_OBSERVATION_SOURCE_ERROR",
                message = "Observation source '${ex.provider}' is unavailable or returned an error",
                provider = ex.provider
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        logger.error("Unexpected error while handling investigation request", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                error = "INTERNAL_ERROR",
                message = "Internal server error"
            )
        )
    }
}
