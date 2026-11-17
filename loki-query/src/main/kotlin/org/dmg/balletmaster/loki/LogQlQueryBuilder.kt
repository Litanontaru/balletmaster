package org.dmg.balletmaster.loki

enum class LogScope {
    /** Selects only Balletmaster SDK system events (loggers matching org.dmg.balletmaster.*). */
    SDK_ONLY,

    /** Selects both SDK system events and application-level logs carrying the correlated operationId in MDC. */
    ALL_CORRELATED
}

data class LogQlQuery(
    val logQl: String,
    val operationId: String,
    val scope: LogScope
)

object LogQlQueryBuilder {

    private val FORBIDDEN_HIGH_CARDINALITY_LABEL_KEYS = setOf(
        "ballet.operationId",
        "ballet_operationId",
        "operationId",
        "ballet.suffix",
        "ballet_suffix",
        "suffix",
        "ballet.step",
        "ballet_step",
        "step",
        "traceId",
        "requestId",
        "executionId"
    )

    fun build(
        app: String,
        environment: String,
        operationId: String,
        scope: LogScope = LogScope.SDK_ONLY,
        extraStreamLabels: Map<String, String> = emptyMap(),
        operation: String? = null,
        step: String? = null,
        attempt: Int? = null,
        level: String? = null
    ): LogQlQuery {
        require(app.isNotBlank()) { "app must not be blank" }
        require(environment.isNotBlank()) { "environment must not be blank" }
        require(operationId.isNotBlank()) { "operationId must not be blank" }

        for (key in extraStreamLabels.keys) {
            if (key in FORBIDDEN_HIGH_CARDINALITY_LABEL_KEYS) {
                throw IllegalArgumentException(
                    "Label '$key' is high-cardinality and forbidden in Loki stream selector. Use structured filtering instead."
                )
            }
        }

        val allLabels = LinkedHashMap<String, String>().apply {
            put("app", app)
            put("environment", environment)
            putAll(extraStreamLabels)
        }

        val streamSelector = allLabels.entries.joinToString(
            separator = ", ",
            prefix = "{",
            postfix = "}"
        ) { (k, v) -> "$k=\"${escapeLogQl(v)}\"" }

        val escapedOpId = escapeLogQl(operationId)
        val textFilter = "|= \"$escapedOpId\""
        val jsonPipeline = "| json"

        val filters = mutableListOf<String>()

        if (scope == LogScope.SDK_ONLY) {
            filters += "(logger_name =~ \"^org\\\\.dmg\\\\.balletmaster(\\\\..*)?$\" or logger =~ \"^org\\\\.dmg\\\\.balletmaster(\\\\..*)?$\")"
        }

        filters += "(ballet_operationId = \"$escapedOpId\" or mdc_ballet_operationId = \"$escapedOpId\" or operationId = \"$escapedOpId\")"

        if (!operation.isNullOrBlank()) {
            val escapedOp = escapeLogQl(operation)
            filters += "(ballet_operation = \"$escapedOp\" or mdc_ballet_operation = \"$escapedOp\" or operation = \"$escapedOp\")"
        }

        if (!step.isNullOrBlank()) {
            val escapedStep = escapeLogQl(step)
            filters += "(ballet_step = \"$escapedStep\" or mdc_ballet_step = \"$escapedStep\" or step = \"$escapedStep\")"
        }

        if (attempt != null) {
            filters += "(ballet_attempt = $attempt or mdc_ballet_attempt = \"$attempt\" or attempt = $attempt)"
        }

        if (!level.isNullOrBlank()) {
            val escapedLevel = escapeLogQl(level.uppercase())
            filters += "level = \"$escapedLevel\""
        }

        val queryParts = mutableListOf(streamSelector, textFilter, jsonPipeline)
        for (f in filters) {
            queryParts += "| $f"
        }

        return LogQlQuery(
            logQl = queryParts.joinToString(" "),
            operationId = operationId,
            scope = scope
        )
    }

    private fun escapeLogQl(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
