package org.dmg.balletmaster.loki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LokiRulesValidationTest {

    private val expectedKnownEvents = setOf(
        "step.no_call",
        "store.error",
        "lock.stolen_expired",
        "step.exception",
        "lock.blocked",
        "chain.stopped"
    )

    private val forbiddenByLabels = setOf(
        "operationId",
        "ballet.operationId",
        "ballet_operationId",
        "suffix",
        "ballet.suffix",
        "ballet_suffix",
        "traceId",
        "executionId"
    )

    @Test
    fun validatesRuleFileSyntaxAndSafety() {
        val candidates = listOf(
            File("../monitoring/rules/loki/balletmaster-loki-rules.yaml"),
            File("monitoring/rules/loki/balletmaster-loki-rules.yaml")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull(file, "Rule file not found in candidates: $candidates")
        assertTrue(file!!.length() > 0, "Rule file must not be empty")

        val rules = parseAlertRules(file.readText())
        assertTrue(rules.size >= 4, "Expected at least 4 rules, got ${rules.size}")

        val ruleNames = rules.map { it.alert }.toSet()
        val expectedRuleNames = setOf(
            "BalletmasterNoCallDetected",
            "BalletmasterStoreErrorsHigh",
            "BalletmasterLeaseTakeoverDetected",
            "BalletmasterStepFailureRateSpike"
        )
        for (expected in expectedRuleNames) {
            assertTrue(expected in ruleNames, "Rule '$expected' must be defined in $ruleNames")
        }

        for (rule in rules) {
            assertTrue(rule.alert.isNotBlank(), "Alert name must not be blank")
            assertTrue(rule.expr.isNotBlank(), "Expression must not be blank for ${rule.alert}")
            assertTrue(rule.duration.matches(Regex("\\d+[smhd]")), "Duration '${rule.duration}' must match Prometheus format (e.g. 1m, 2m)")

            // Labels verification
            assertEquals("balletmaster", rule.labels["subsystem"], "Subsystem must be 'balletmaster' for ${rule.alert}")
            val severity = rule.labels["severity"]
            assertTrue(severity in setOf("critical", "warning", "info"), "Invalid severity: $severity in ${rule.alert}")

            // Annotations verification
            assertTrue(rule.annotations["summary"]?.isNotBlank() == true, "Summary must be present for ${rule.alert}")
            assertTrue(rule.annotations["description"]?.isNotBlank() == true, "Description must be present for ${rule.alert}")
            assertTrue(rule.annotations["runbook_url"]?.startsWith("http") == true, "Runbook URL must be valid for ${rule.alert}")

            // Cardinality safety verification
            for (forbidden in forbiddenByLabels) {
                assertFalse(
                    rule.expr.contains("by (.*$forbidden.*)".toRegex()),
                    "Rule '${rule.alert}' must not aggregate by high-cardinality label '$forbidden'"
                )
            }

            // Target event verification
            val matchedEvent = expectedKnownEvents.firstOrNull { rule.expr.contains("|= \"$it\"") }
            assertNotNull(matchedEvent, "Rule '${rule.alert}' expression '${rule.expr}' must reference a known SDK event")
        }
    }

    private data class ParsedRule(
        val alert: String,
        val expr: String,
        val duration: String,
        val labels: Map<String, String>,
        val annotations: Map<String, String>
    )

    private fun parseAlertRules(yamlText: String): List<ParsedRule> {
        val lines = yamlText.lines()
        val rules = mutableListOf<ParsedRule>()

        var currentAlert: String? = null
        val currentExpr = StringBuilder()
        var currentDuration: String? = null
        val currentLabels = mutableMapOf<String, String>()
        val currentAnnotations = mutableMapOf<String, String>()
        var section: String? = null

        fun flush() {
            if (currentAlert != null) {
                rules += ParsedRule(
                    alert = currentAlert!!,
                    expr = currentExpr.toString().trim(),
                    duration = currentDuration ?: "",
                    labels = LinkedHashMap(currentLabels),
                    annotations = LinkedHashMap(currentAnnotations)
                )
            }
            currentAlert = null
            currentExpr.clear()
            currentDuration = null
            currentLabels.clear()
            currentAnnotations.clear()
            section = null
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- alert:")) {
                flush()
                currentAlert = trimmed.removePrefix("- alert:").trim()
                continue
            }
            if (currentAlert == null) continue

            if (trimmed.startsWith("expr:")) {
                section = "expr"
                val rest = trimmed.removePrefix("expr:").trim()
                if (rest != "|") currentExpr.append(rest).append("\n")
                continue
            }
            if (trimmed.startsWith("for:")) {
                section = null
                currentDuration = trimmed.removePrefix("for:").trim()
                continue
            }
            if (trimmed.startsWith("labels:")) {
                section = "labels"
                continue
            }
            if (trimmed.startsWith("annotations:")) {
                section = "annotations"
                continue
            }

            when (section) {
                "expr" -> {
                    if (trimmed.startsWith("for:") || trimmed.startsWith("labels:")) {
                        section = null
                    } else {
                        currentExpr.append(trimmed).append("\n")
                    }
                }
                "labels" -> {
                    if (trimmed.contains(":")) {
                        val (k, v) = trimmed.split(":", limit = 2)
                        currentLabels[k.trim()] = v.trim().trim('"', '\'')
                    }
                }
                "annotations" -> {
                    if (trimmed.contains(":")) {
                        val (k, v) = trimmed.split(":", limit = 2)
                        currentAnnotations[k.trim()] = v.trim().trim('"', '\'')
                    }
                }
            }
        }
        flush()
        return rules
    }
}
