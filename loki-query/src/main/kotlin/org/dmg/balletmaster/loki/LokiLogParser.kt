package org.dmg.balletmaster.loki

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException

data class RawStreamEntry(
    val streamLabels: Map<String, String>,
    val tsNs: Long,
    val rawLine: String
) {
    val dedupKey: String get() = "$tsNs:${streamLabels.hashCode()}:$rawLine"
}

object LokiLogParser {

    val mapper: ObjectMapper = jacksonObjectMapper()

    private val STANDARD_ROOT_FIELDS = setOf(
        "timestamp",
        "@timestamp",
        "time",
        "level",
        "logger_name",
        "logger",
        "loggerName",
        "thread_name",
        "thread",
        "message",
        "msg",
        "event",
        "mdc",
        "kvpList",
        "keyValuePairs",
        "context"
    )

    fun parseLine(
        rawLine: String,
        streamLabels: Map<String, String> = emptyMap(),
        defaultTsNs: Long? = null
    ): LokiLogObservation {
        val rootNode: JsonNode
        try {
            rootNode = mapper.readTree(rawLine)
        } catch (e: Exception) {
            val tsNs = defaultTsNs ?: 0L
            return LokiLogObservation(
                timestampNs = tsNs,
                timestamp = if (defaultTsNs != null) nanosToInstant(defaultTsNs) else Instant.EPOCH,
                streamLabels = streamLabels,
                rawLine = rawLine,
                parseError = "Invalid JSON: ${e.message}"
            )
        }

        if (!rootNode.isObject) {
            val tsNs = defaultTsNs ?: 0L
            return LokiLogObservation(
                timestampNs = tsNs,
                timestamp = if (defaultTsNs != null) nanosToInstant(defaultTsNs) else Instant.EPOCH,
                streamLabels = streamLabels,
                rawLine = rawLine,
                parseError = "Root JSON token is not an object: ${rootNode.nodeType}"
            )
        }

        val logger = rootNode.path("logger_name").asTextOrNull()
            ?: rootNode.path("logger").asTextOrNull()
            ?: rootNode.path("loggerName").asTextOrNull()

        val level = rootNode.path("level").asTextOrNull()

        val event = rootNode.path("message").asTextOrNull()
            ?: rootNode.path("msg").asTextOrNull()
            ?: rootNode.path("event").asTextOrNull()

        val timestampNs: Long
        val timestamp: Instant

        if (defaultTsNs != null) {
            timestampNs = defaultTsNs
            timestamp = nanosToInstant(defaultTsNs)
        } else {
            val parsedTs = parseTimestamp(rootNode)
            timestamp = parsedTs ?: Instant.EPOCH
            timestampNs = parsedTs?.let(::instantToNanos) ?: 0L
        }

        val attributes = LinkedHashMap<String, Any?>()
        var op: String? = null
        var opId: String? = null
        var attempt: Int? = null
        var suffix: String? = null
        var step: String? = null
        var replay: Boolean? = null

        fun checkCorrelation(k: String, v: Any?) {
            when (k) {
                "ballet.operation", "ballet_operation", "operation" ->
                    op = op ?: v?.toString()
                "ballet.operationId", "ballet_operationId", "operationId" ->
                    opId = opId ?: v?.toString()
                "ballet.attempt", "ballet_attempt", "attempt" ->
                    attempt = attempt ?: (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull()
                "ballet.suffix", "ballet_suffix", "suffix" ->
                    suffix = suffix ?: v?.toString()
                "ballet.step", "ballet_step", "step" ->
                    step = step ?: v?.toString()
                "ballet.replay", "ballet_replay", "replay" ->
                    replay = replay ?: (v as? Boolean) ?: v?.toString()?.toBooleanStrictOrNull()
            }
        }

        // 1. Process MDC
        val mdcNode = rootNode.path("mdc")
        if (mdcNode.isObject) {
            for (fieldName in mdcNode.fieldNames()) {
                val value = mdcNode.get(fieldName).asTextOrNull()
                attributes[fieldName] = value
                checkCorrelation(fieldName, value)
            }
        }

        // 2. Process KeyValuePairs (Logback JsonEncoder uses kvpList)
        val kvpNode = if (rootNode.path("kvpList").isArray) {
            rootNode.get("kvpList")
        } else if (rootNode.path("keyValuePairs").isArray) {
            rootNode.get("keyValuePairs")
        } else {
            null
        }

        if (kvpNode != null) {
            for (item in kvpNode) {
                if (item.isObject) {
                    if (item.has("key") && item.has("value") && item.size() <= 2) {
                        val k = item.get("key").asText()
                        val v = extractJsonValue(item.get("value"))
                        attributes[k] = v
                        checkCorrelation(k, v)
                    } else {
                        for (fieldName in item.fieldNames()) {
                            val v = extractJsonValue(item.get(fieldName))
                            attributes[fieldName] = v
                            checkCorrelation(fieldName, v)
                        }
                    }
                }
            }
        }

        // 3. Process top-level properties (flat structured format)
        for (fieldName in rootNode.fieldNames()) {
            if (fieldName !in STANDARD_ROOT_FIELDS) {
                val v = extractJsonValue(rootNode.get(fieldName))
                attributes[fieldName] = v
                checkCorrelation(fieldName, v)
            }
        }

        val correlation = if (op != null || opId != null || attempt != null || suffix != null || step != null || replay != null) {
            BalletCorrelation(
                operation = op,
                operationId = opId,
                attempt = attempt,
                suffix = suffix,
                step = step,
                replay = replay
            )
        } else {
            null
        }

        return LokiLogObservation(
            timestampNs = timestampNs,
            timestamp = timestamp,
            streamLabels = streamLabels,
            rawLine = rawLine,
            logger = logger,
            level = level,
            event = event,
            correlation = correlation,
            attributes = attributes,
            parseError = null
        )
    }

    fun parseLokiStreamEntries(responseJson: String): List<RawStreamEntry> {
        val root = mapper.readTree(responseJson)
        val status = root.path("status").asText()
        if (status != "success") {
            val error = root.path("error").asTextOrNull() ?: responseJson
            throw LokiQueryException(200, "Loki responded with status '$status': $error")
        }

        val data = root.path("data")
        val resultType = data.path("resultType").asText()
        if (resultType != "streams") {
            return emptyList()
        }

        val result = data.path("result")
        if (!result.isArray) {
            return emptyList()
        }

        val entries = mutableListOf<RawStreamEntry>()
        for (streamItem in result) {
            val streamLabels = LinkedHashMap<String, String>()
            val streamObj = streamItem.path("stream")
            if (streamObj.isObject) {
                for (fieldName in streamObj.fieldNames()) {
                    streamLabels[fieldName] = streamObj.get(fieldName).asText()
                }
            }

            val values = streamItem.path("values")
            if (values.isArray) {
                for (pair in values) {
                    if (pair.isArray && pair.size() >= 2) {
                        val tsStr = pair.get(0).asText()
                        val rawLine = pair.get(1).asText()
                        val tsNs = tsStr.toLongOrNull() ?: 0L
                        entries += RawStreamEntry(
                            streamLabels = streamLabels,
                            tsNs = tsNs,
                            rawLine = rawLine
                        )
                    }
                }
            }
        }
        return entries
    }

    private fun parseTimestamp(root: JsonNode): Instant? {
        val tsNode = root.path("timestamp").takeIf { !it.isMissingNode && !it.isNull }
            ?: root.path("@timestamp").takeIf { !it.isMissingNode && !it.isNull }
            ?: root.path("time").takeIf { !it.isMissingNode && !it.isNull }
            ?: return null

        if (tsNode.isNumber) {
            val num = tsNode.asLong()
            return if (num > 1_000_000_000_000_000L) {
                nanosToInstant(num)
            } else {
                Instant.ofEpochMilli(num)
            }
        }

        if (tsNode.isTextual) {
            val text = tsNode.asText()
            val asLong = text.toLongOrNull()
            if (asLong != null) {
                return if (asLong > 1_000_000_000_000_000L) nanosToInstant(asLong) else Instant.ofEpochMilli(asLong)
            }
            return try {
                Instant.parse(text)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        return null
    }

    private fun extractJsonValue(node: JsonNode?): Any? = when {
        node == null || node.isNull -> null
        node.isBoolean -> node.asBoolean()
        node.isInt -> node.asInt()
        node.isLong -> node.asLong()
        node.isDouble || node.isFloat -> node.asDouble()
        node.isTextual -> node.asText()
        node.isArray -> node.map { extractJsonValue(it) }
        node.isObject -> node.fieldNames().asSequence().associateWith { extractJsonValue(node.get(it)) }
        else -> node.asText()
    }

    private fun JsonNode.asTextOrNull(): String? = if (isTextual) asText() else null

    fun nanosToInstant(nanos: Long): Instant {
        val sec = Math.floorDiv(nanos, 1_000_000_000L)
        val rem = Math.floorMod(nanos, 1_000_000_000L).toInt()
        return Instant.ofEpochSecond(sec, rem.toLong())
    }

    fun instantToNanos(instant: Instant): Long =
        Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000_000L), instant.nano.toLong())
}
