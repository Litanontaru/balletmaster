package org.dmg.balletmaster.loki

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant

/**
 * Validates consistency between SLF4J 2.0 Core events, Logback serialization profiles,
 * Loki Ruler alerting rules, [LogQlQueryBuilder], and [LokiLogParser].
 *
 * Covers:
 * 1. Recommended Native Flat JSON Profile (LogstashEncoder / Profile 1).
 * 2. Standard Logback JsonEncoder (nested mdc and kvpList / Profile 2) and SDK parser resilience.
 * 3. Collector-Normalized Stream (Alloy / Vector transform).
 * 4. Integration against real Loki engine when available.
 */
class LoggingFormatCompatibilityTest {

    private val mapper = jacksonObjectMapper()

    private fun resolveMonitoringRulesFile(): File {
        val candidates = listOf(
            File("../monitoring/rules/loki/balletmaster-loki-rules.yaml"),
            File("monitoring/rules/loki/balletmaster-loki-rules.yaml")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull(file, "balletmaster-loki-rules.yaml must exist")
        return file!!
    }

    @Test
    fun flatJsonProfileEmitsDirectlyMappableLabelsForLokiRulerAndQueryBuilder() {
        val opId = "ord-flat-100"
        val opName = "OrderCheckout"
        val stepName = "AuthorizePayment"

        // Flat JSON payloads as produced by net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder
        // with <mdc/> and <keyValuePairs/> providers (Profile 1)
        val noCallJson = """
            {
                "timestamp": "2026-11-18T10:00:00Z",
                "level": "INFO",
                "logger": "org.dmg.balletmaster.step",
                "message": "step.no_call",
                "ballet_operation": "$opName",
                "ballet_operationId": "$opId",
                "ballet_step": "$stepName",
                "ballet_attempt": 0,
                "ballet_suffix": "",
                "ballet_replay": false
            }
        """.trimIndent()

        val storeErrorJson = """
            {
                "timestamp": "2026-11-18T10:00:01Z",
                "level": "ERROR",
                "logger": "org.dmg.balletmaster.store",
                "message": "store.error",
                "ballet_operation": "$opName",
                "ballet_operationId": "$opId",
                "ballet_step": "$stepName",
                "ballet_attempt": 0,
                "ballet_suffix": "",
                "storeOp": "CAS",
                "key": "order:100:lock"
            }
        """.trimIndent()

        val leaseTakeoverJson = """
            {
                "timestamp": "2026-11-18T10:00:02Z",
                "level": "WARN",
                "logger": "org.dmg.balletmaster.lock",
                "message": "lock.stolen_expired",
                "ballet_operation": "$opName",
                "ballet_operationId": "$opId",
                "ballet_step": "$stepName",
                "ballet_attempt": 0,
                "ballet_suffix": "",
                "leaseMs": 5000,
                "leaseUntil": 1700000005000
            }
        """.trimIndent()

        val stepExceptionJson = """
            {
                "timestamp": "2026-11-18T10:00:03Z",
                "level": "ERROR",
                "logger": "org.dmg.balletmaster.step",
                "message": "step.exception",
                "ballet_operation": "$opName",
                "ballet_operationId": "$opId",
                "ballet_step": "$stepName",
                "ballet_attempt": 0,
                "ballet_suffix": "",
                "exception": "IllegalStateException"
            }
        """.trimIndent()

        // 1. Verify LokiLogParser extracts all correlation and custom attributes directly
        val parsedNoCall = LokiLogParser.parseLine(noCallJson)
        assertEquals(opId, parsedNoCall.correlation?.operationId)
        assertEquals(stepName, parsedNoCall.correlation?.step)
        assertEquals(opName, parsedNoCall.correlation?.operation)
        assertNull(parsedNoCall.parseError)

        val parsedStore = LokiLogParser.parseLine(storeErrorJson)
        assertEquals("CAS", parsedStore.attributes["storeOp"])
        assertEquals("order:100:lock", parsedStore.attributes["key"])

        val parsedLease = LokiLogParser.parseLine(leaseTakeoverJson)
        assertEquals(opName, parsedLease.correlation?.operation)
        assertEquals(stepName, parsedLease.correlation?.step)
        assertEquals(5000, (parsedLease.attributes["leaseMs"] as? Number)?.toInt())

        val parsedStepEx = LokiLogParser.parseLine(stepExceptionJson)
        assertEquals("IllegalStateException", parsedStepEx.attributes["exception"])

        // 2. Verify LogQlQueryBuilder produces query matching flat fields
        val query = LogQlQueryBuilder.build(
            app = "order-service",
            environment = "prod",
            operationId = opId,
            scope = LogScope.SDK_ONLY,
            operation = opName,
            step = stepName
        )

        // LogQL query must check flat field ballet_operationId and SDK logger
        assertTrue(query.logQl.contains("ballet_operationId = \"$opId\""))
        assertTrue(query.logQl.contains("ballet_operation = \"$opName\""))
        assertTrue(query.logQl.contains("ballet_step = \"$stepName\""))

        // 3. Verify all 4 Loki Ruler rules find their aggregation labels in flat JSON
        val rulesFile = resolveMonitoringRulesFile()
        val rulesText = rulesFile.readText()

        // Rule 1: BalletmasterNoCallDetected -> sum by (app, environment, ballet_step)
        assertTrue(rulesText.contains("sum by (app, environment, ballet_step)"))
        assertTrue(parsedNoCall.correlation?.step == stepName)
        val noCallRoot = mapper.readTree(noCallJson)
        assertTrue(noCallRoot.has("ballet_step"), "Flat JSON must have ballet_step at root")

        // Rule 2: BalletmasterStoreErrorsHigh -> sum by (app, environment, storeOp)
        assertTrue(rulesText.contains("sum by (app, environment, storeOp)"))
        val storeRoot = mapper.readTree(storeErrorJson)
        assertTrue(storeRoot.has("storeOp"), "Flat JSON must have storeOp at root")

        // Rule 3: BalletmasterLeaseTakeoverDetected -> sum by (app, environment, ballet_operation, ballet_step)
        assertTrue(rulesText.contains("sum by (app, environment, ballet_operation, ballet_step)"))
        val leaseRoot = mapper.readTree(leaseTakeoverJson)
        assertTrue(leaseRoot.has("ballet_operation"), "Flat JSON must have ballet_operation at root")
        assertTrue(leaseRoot.has("ballet_step"), "Flat JSON must have ballet_step at root")

        // Rule 4: BalletmasterStepFailureRateSpike -> sum by (app, environment, ballet_step, exception)
        assertTrue(rulesText.contains("sum by (app, environment, ballet_step, exception)"))
        val stepExRoot = mapper.readTree(stepExceptionJson)
        assertTrue(stepExRoot.has("ballet_step"), "Flat JSON must have ballet_step at root")
        assertTrue(stepExRoot.has("exception"), "Flat JSON must have exception at root")
    }

    @Test
    fun standardLogbackJsonEncoderOutputDemonstratesResilienceAndArrayUnpackingNeed() {
        val opId = "ord-std-200"

        // Unflattened standard Logback JsonEncoder output (nested mdc and kvpList)
        val rawLogbackJson = """
            {
                "timestamp": "2026-11-18T10:05:00Z",
                "level": "ERROR",
                "logger_name": "org.dmg.balletmaster.store",
                "message": "store.error",
                "mdc": {
                    "ballet.operation": "OrderCheckout",
                    "ballet.operationId": "$opId",
                    "ballet.step": "SaveOrder",
                    "ballet.attempt": "1",
                    "ballet.suffix": "sub"
                },
                "kvpList": [
                    { "storeOp": "CAS" },
                    { "key": "order:200:lock" }
                ]
            }
        """.trimIndent()

        // 1. LokiLogParser correctly unpacks both nested mdc and kvpList
        val observation = LokiLogParser.parseLine(rawLogbackJson)
        assertNull(observation.parseError)
        assertEquals(opId, observation.correlation?.operationId)
        assertEquals("OrderCheckout", observation.correlation?.operation)
        assertEquals("SaveOrder", observation.correlation?.step)
        assertEquals(1, observation.correlation?.attempt)
        assertEquals("sub", observation.correlation?.suffix)
        assertEquals("CAS", observation.attributes["storeOp"])
        assertEquals("order:200:lock", observation.attributes["key"])

        // 2. Demonstrate Loki ingestion gap:
        // Loki's | json parser evaluates top-level properties and nested objects,
        // but does NOT unpack arrays like kvpList into individual labels.
        val rootNode = mapper.readTree(rawLogbackJson)
        assertTrue(rootNode.has("mdc"), "Standard Logback has nested 'mdc' object")
        assertTrue(rootNode.has("kvpList"), "Standard Logback has nested 'kvpList' array")
        assertFalse(rootNode.has("storeOp"), "Standard Logback does NOT have 'storeOp' at root")
        assertFalse(rootNode.has("ballet_step"), "Standard Logback does NOT have 'ballet_step' at root")

        // This proves why Loki Ruler rules `sum by (storeOp)` require either Profile 1 (Flat JSON)
        // or Profile 2 (Collector Normalization) to extract storeOp.
    }

    @Test
    fun collectorNormalizedStreamTransformsNestedKvpAndMdcIntoFlatLokiLabels() {
        val opId = "ord-collector-300"
        val rawLogbackJson = """
            {
                "timestamp": "2026-11-18T10:10:00Z",
                "level": "ERROR",
                "logger_name": "org.dmg.balletmaster.step",
                "message": "step.exception",
                "mdc": {
                    "ballet.operation": "OrderCheckout",
                    "ballet.operationId": "$opId",
                    "ballet.step": "ChargeCard",
                    "ballet.attempt": "0",
                    "ballet.suffix": ""
                },
                "kvpList": [
                    { "exception": "TimeoutException" },
                    { "gateway": "Stripe" }
                ]
            }
        """.trimIndent()

        // Simulate Vector / Alloy Remap normalization logic (collectors/vector-balletmaster.vrl)
        val normalizedJson = simulateCollectorNormalization(rawLogbackJson)
        val normalizedRoot = mapper.readTree(normalizedJson)

        // Verify collector flattened mdc and unpacked kvpList
        assertTrue(normalizedRoot.has("ballet_operationId"), "Normalized stream must have ballet_operationId at root")
        assertEquals(opId, normalizedRoot.get("ballet_operationId").asText())

        assertTrue(normalizedRoot.has("ballet_operation"), "Normalized stream must have ballet_operation at root")
        assertEquals("OrderCheckout", normalizedRoot.get("ballet_operation").asText())

        assertTrue(normalizedRoot.has("ballet_step"), "Normalized stream must have ballet_step at root")
        assertEquals("ChargeCard", normalizedRoot.get("ballet_step").asText())

        assertTrue(normalizedRoot.has("exception"), "Normalized stream must have exception at root")
        assertEquals("TimeoutException", normalizedRoot.get("exception").asText())

        assertTrue(normalizedRoot.has("gateway"), "Normalized stream must have gateway at root")
        assertEquals("Stripe", normalizedRoot.get("gateway").asText())

        // Verify LokiLogParser parses normalized line identically to native flat JSON
        val parsed = LokiLogParser.parseLine(normalizedJson)
        assertNull(parsed.parseError)
        assertEquals(opId, parsed.correlation?.operationId)
        assertEquals("ChargeCard", parsed.correlation?.step)
        assertEquals("TimeoutException", parsed.attributes["exception"])
        assertEquals("Stripe", parsed.attributes["gateway"])
    }

    @Test
    fun realLokiIngestionAndQueryMatchingForFlatAndNormalizedStreams() {
        val lokiUri = RealLokiTestCluster.resolveLokiUri()
        if (lokiUri == null) {
            val requireRealLoki = System.getProperty("balletmaster.requireRealLoki")?.toBoolean() == true
            if (requireRealLoki) {
                RealLokiTestCluster.getOrStartLoki() // Throws descriptive error
            }
            // Skip gracefully if real Loki not available in offline environment
            return
        }

        val app = "balletmaster-compat-test"
        val env = "test"
        val now = Instant.now()
        val tsNs = (now.toEpochMilli() - 5000) * 1_000_000L

        val targetOpId = "ord-real-comp-42"
        val distractorOpId = "ord-real-comp-4200" // Substring collision

        // Stream 1: Flat JSON log for target operation
        val targetFlatLog = """
            {"timestamp":"${now}","level":"ERROR","logger":"org.dmg.balletmaster.store","message":"store.error","ballet_operation":"Payment","ballet_operationId":"$targetOpId","ballet_step":"save","storeOp":"CAS","key":"order:42"}
        """.trimIndent()

        // Stream 2: Collector-normalized log for target operation
        val targetNormalizedLog = simulateCollectorNormalization("""
            {"timestamp":"${now.plusMillis(100)}","level":"ERROR","logger_name":"org.dmg.balletmaster.step","message":"step.exception","mdc":{"ballet.operation":"Payment","ballet.operationId":"$targetOpId","ballet.step":"charge"},"kvpList":[{"exception":"PaymentFailed"}]}
        """.trimIndent())

        // Stream 3: Distractor log with substring operation ID collision
        val distractorLog = """
            {"timestamp":"${now.plusMillis(200)}","level":"ERROR","logger":"org.dmg.balletmaster.store","message":"store.error","ballet_operation":"Payment","ballet_operationId":"$distractorOpId","ballet_step":"save","storeOp":"PUT","key":"order:4200"}
        """.trimIndent()

        val streamLabels = mapOf("app" to app, "environment" to env)
        RealLokiTestCluster.pushLogs(
            lokiUri,
            streamLabels,
            listOf(
                (tsNs + 10_000_000L) to targetFlatLog,
                (tsNs + 20_000_000L) to targetNormalizedLog,
                (tsNs + 30_000_000L) to distractorLog
            )
        )

        val client = LokiQueryClient(LokiClientConfig(baseUrl = lokiUri))
        val query = LogQlQueryBuilder.build(
            app = app,
            environment = env,
            operationId = targetOpId,
            scope = LogScope.SDK_ONLY
        )

        // Wait for Loki to index and query
        val result = RealLokiTestCluster.waitForQuery(
            client = client,
            query = query,
            start = now.minusSeconds(30),
            end = now.plusSeconds(30),
            expectedMinEntries = 2
        )

        // Strict validation: exactly 2 entries for targetOpId, NONE for distractorOpId
        assertEquals(2, result.observations.size, "Expected exactly 2 target observations for $targetOpId")
        for (obs in result.observations) {
            assertEquals(targetOpId, obs.correlation?.operationId)
            assertFalse(obs.rawLine.contains(distractorOpId))
        }

        // Verify parsed attributes from real Loki stream
        val storeObs = result.observations.first { it.event == "store.error" }
        assertEquals("CAS", storeObs.attributes["storeOp"])
        assertEquals("save", storeObs.correlation?.step)

        val stepExObs = result.observations.first { it.event == "step.exception" }
        assertEquals("PaymentFailed", stepExObs.attributes["exception"])
        assertEquals("charge", stepExObs.correlation?.step)
    }

    /**
     * Implements the exact transformation defined in `monitoring/collectors/vector-balletmaster.vrl`
     * and `alloy-balletmaster.alloy` for test verification.
     */
    private fun simulateCollectorNormalization(rawJson: String): String {
        val root = mapper.readTree(rawJson) as com.fasterxml.jackson.databind.node.ObjectNode

        // 1. Flatten MDC object
        val mdc = root.remove("mdc")
        if (mdc != null && mdc.isObject) {
            for (field in mdc.fieldNames()) {
                val flatKey = field.replace('.', '_')
                root.set<JsonNode>(flatKey, mdc.get(field))
            }
        }

        // 2. Unpack KeyValuePairs array
        val kvpList = root.remove("kvpList") ?: root.remove("keyValuePairs")
        if (kvpList != null && kvpList.isArray) {
            for (item in kvpList) {
                if (item.isObject) {
                    if (item.has("key") && item.has("value") && item.size() <= 2) {
                        val flatKey = item.get("key").asText().replace('.', '_')
                        root.set<JsonNode>(flatKey, item.get("value"))
                    } else {
                        for (field in item.fieldNames()) {
                            val flatKey = field.replace('.', '_')
                            root.set<JsonNode>(flatKey, item.get(field))
                        }
                    }
                }
            }
        }

        // 3. Normalize logger
        if (!root.has("logger") && root.has("logger_name")) {
            root.set<JsonNode>("logger", root.get("logger_name"))
        }

        return mapper.writeValueAsString(root)
    }
}
