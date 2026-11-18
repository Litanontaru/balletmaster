package org.dmg.balletmaster.investigation.service

import org.dmg.balletmaster.observation.ObservationSourceException
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

    @ExceptionHandler(
        IllegalArgumentException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class
    )
    fun handleBadRequest(ex: Exception): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiErrorResponse(
                error = "INVALID_REQUEST",
                message = ex.message ?: "Invalid request parameters"
            )
        )

    @ExceptionHandler(ObservationSourceException::class)
    fun handleUpstreamFailure(ex: ObservationSourceException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            ApiErrorResponse(
                error = "UPSTREAM_OBSERVATION_SOURCE_ERROR",
                message = ex.message ?: "Observation source '${ex.provider}' failed",
                provider = ex.provider
            )
        )
}
