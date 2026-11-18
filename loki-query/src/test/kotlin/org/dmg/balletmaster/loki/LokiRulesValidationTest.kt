package org.dmg.balletmaster.loki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

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

    private fun resolveMonitoringFile(relativePath: String): File {
        val candidates = listOf(
            File("../monitoring/$relativePath"),
            File("monitoring/$relativePath")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull(file, "Monitoring file not found in candidates: $candidates")
        assertTrue(file!!.length() > 0, "Monitoring file must not be empty: $file")
        return file
    }

    @Test
    fun validatesRuleFileSyntaxAndSafety() {
        val file = resolveMonitoringFile("rules/loki/balletmaster-loki-rules.yaml")

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

    @Test
    fun validatesGitOpsKubernetesManifestsAndParityWithCanonicalRules() {
        val canonicalFile = resolveMonitoringFile("rules/loki/balletmaster-loki-rules.yaml")
        val canonicalRules = parseAlertRules(canonicalFile.readText())

        // 1. Loki Operator AlertingRule CR
        val crFile = resolveMonitoringFile("deploy/k8s/alerting-rule-cr.yaml")
        val crText = crFile.readText()
        assertTrue(crText.contains("apiVersion: loki.grafana.com/v1"), "AlertingRule CR must use loki.grafana.com/v1")
        assertTrue(crText.contains("kind: AlertingRule"), "AlertingRule CR must declare kind: AlertingRule")
        assertEquals(canonicalRules, parseAlertRules(crText), "AlertingRule CR rules must stay in sync with canonical rule file")

        // 2. Static ConfigMap
        val cmFile = resolveMonitoringFile("deploy/k8s/configmap.yaml")
        val cmText = cmFile.readText()
        assertTrue(cmText.contains("apiVersion: v1"), "ConfigMap must use apiVersion: v1")
        assertTrue(cmText.contains("kind: ConfigMap"), "ConfigMap must declare kind: ConfigMap")
        assertTrue(cmText.contains("balletmaster-loki-rules.yaml: |"), "ConfigMap must embed balletmaster-loki-rules.yaml")
        assertEquals(canonicalRules, parseAlertRules(cmText), "ConfigMap rules must stay in sync with canonical rule file")

        // 3. Canonical Kustomize base (monitoring/rules/loki)
        val rulesBaseKustomizeFile = resolveMonitoringFile("rules/loki/kustomization.yaml")
        val rulesBaseText = rulesBaseKustomizeFile.readText()
        assertTrue(rulesBaseText.contains("apiVersion: kustomize.config.k8s.io/v1beta1"))
        assertTrue(rulesBaseText.contains("kind: Kustomization"))
        assertTrue(rulesBaseText.contains("configMapGenerator:"))
        assertTrue(rulesBaseText.contains("- balletmaster-loki-rules.yaml"))
        assertFalse(
            rulesBaseText.contains(".."),
            "Canonical rules kustomization must reference local balletmaster-loki-rules.yaml without '..' traversal"
        )
        assertEquals(
            canonicalFile.canonicalFile,
            File(rulesBaseKustomizeFile.parentFile, "balletmaster-loki-rules.yaml").canonicalFile
        )

        // 4. K8s Kustomize overlay (monitoring/deploy/k8s)
        val kustomizeFile = resolveMonitoringFile("deploy/k8s/kustomization.yaml")
        val kustomizeText = kustomizeFile.readText()
        assertTrue(kustomizeText.contains("apiVersion: kustomize.config.k8s.io/v1beta1"))
        assertTrue(kustomizeText.contains("kind: Kustomization"))
        assertTrue(kustomizeText.contains("resources:"))
        assertTrue(kustomizeText.contains("- ../../rules/loki"))
        assertFalse(
            kustomizeText.contains("configMapGenerator:"),
            "deploy/k8s kustomization must not load files outside its root via configMapGenerator"
        )
        val resolvedBaseDir = File(kustomizeFile.parentFile, "../../rules/loki").canonicalFile
        assertEquals(rulesBaseKustomizeFile.parentFile.canonicalFile, resolvedBaseDir)
    }

    @Test
    fun validatesRealKustomizeBuildAndGitOpsOverlay() {
        val kustomizeCommand = detectKustomizeCommand()
        val requireKustomize = System.getProperty("balletmaster.requireKustomize")?.toBoolean() == true
        if (kustomizeCommand == null) {
            if (requireKustomize) {
                throw AssertionError(
                    "Neither 'kustomize' nor 'kubectl' CLI was found in PATH, " +
                        "and -Dballetmaster.requireKustomize=true is set."
                )
            }
            assumeTrue(false, "Skipping real Kustomize build test: neither 'kustomize' nor 'kubectl' is available in PATH")
            return
        }

        val canonicalFile = resolveMonitoringFile("rules/loki/balletmaster-loki-rules.yaml")
        val canonicalText = normalizeNewlines(canonicalFile.readText()).trimEnd('\n')
        val canonicalRules = parseAlertRules(canonicalText)

        // 1. Real build of canonical base: monitoring/rules/loki (default strict LoadRestrictionsRootOnly)
        val rulesBaseDir = resolveMonitoringFile("rules/loki/kustomization.yaml").parentFile.canonicalFile
        val baseManifest = parseRenderedConfigMap(runKustomizeBuild(kustomizeCommand, rulesBaseDir))
        assertEquals("v1", baseManifest.apiVersion)
        assertEquals("ConfigMap", baseManifest.kind)
        assertTrue(
            baseManifest.name.matches(Regex("^balletmaster-loki-rules-[a-z0-9]+$")),
            "Expected generated ConfigMap name with hash suffix, got '${baseManifest.name}'"
        )
        assertEquals(canonicalText, normalizeNewlines(baseManifest.rulesYaml).trimEnd('\n'))
        assertEquals(canonicalRules, parseAlertRules(baseManifest.rulesYaml))

        // 2. Real build of K8s overlay: monitoring/deploy/k8s (default strict LoadRestrictionsRootOnly)
        val deployK8sDir = resolveMonitoringFile("deploy/k8s/kustomization.yaml").parentFile.canonicalFile
        val deployManifest = parseRenderedConfigMap(runKustomizeBuild(kustomizeCommand, deployK8sDir))
        assertEquals("v1", deployManifest.apiVersion)
        assertEquals("ConfigMap", deployManifest.kind)
        assertTrue(
            deployManifest.name.matches(Regex("^balletmaster-loki-rules-[a-z0-9]+$")),
            "Expected generated ConfigMap name with hash suffix, got '${deployManifest.name}'"
        )
        assertEquals("balletmaster", deployManifest.labels["app.kubernetes.io/part-of"])
        assertEquals("observability", deployManifest.labels["app.kubernetes.io/component"])
        assertEquals(canonicalText, normalizeNewlines(deployManifest.rulesYaml).trimEnd('\n'))
        assertEquals(canonicalRules, parseAlertRules(deployManifest.rulesYaml))

        // 3. Real build of a downstream GitOps overlay referencing monitoring/deploy/k8s
        val targetRoot = File("target").apply { mkdirs() }.toPath().toAbsolutePath().normalize()
        val overlayDir = Files.createTempDirectory(targetRoot, "kustomize-gitops-overlay-").toFile()
        try {
            val relativeDeployPath = overlayDir.toPath().toAbsolutePath().normalize()
                .relativize(deployK8sDir.toPath().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/')
            File(overlayDir, "kustomization.yaml").writeText(
                """
                apiVersion: kustomize.config.k8s.io/v1beta1
                kind: Kustomization

                namespace: monitoring

                resources:
                  - $relativeDeployPath

                labels:
                  - pairs:
                      loki_rule: "1"
                """.trimIndent()
            )

            val overlayManifest = parseRenderedConfigMap(runKustomizeBuild(kustomizeCommand, overlayDir))
            assertEquals("v1", overlayManifest.apiVersion)
            assertEquals("ConfigMap", overlayManifest.kind)
            assertEquals("monitoring", overlayManifest.namespace)
            assertEquals(deployManifest.name, overlayManifest.name)
            assertEquals("balletmaster", overlayManifest.labels["app.kubernetes.io/part-of"])
            assertEquals("observability", overlayManifest.labels["app.kubernetes.io/component"])
            assertEquals("1", overlayManifest.labels["loki_rule"])
            assertEquals(canonicalText, normalizeNewlines(overlayManifest.rulesYaml).trimEnd('\n'))
            assertEquals(canonicalRules, parseAlertRules(overlayManifest.rulesYaml))
        } finally {
            overlayDir.deleteRecursively()
        }
    }

    private data class RenderedConfigMap(
        val apiVersion: String,
        val kind: String,
        val name: String,
        val namespace: String?,
        val labels: Map<String, String>,
        val rulesYaml: String
    )

    private fun detectKustomizeCommand(): List<String>? {
        val candidates = listOf(
            listOf("kustomize", "version") to listOf("kustomize", "build"),
            listOf("kubectl", "version", "--client") to listOf("kubectl", "kustomize")
        )
        for ((probe, buildCmd) in candidates) {
            try {
                val process = ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) {
                    return buildCmd
                }
                process.destroyForcibly()
            } catch (_: Exception) {
                // Binary not found or not executable; try next candidate
            }
        }
        return null
    }

    private fun runKustomizeBuild(buildCommand: List<String>, targetDir: File): String {
        val fullCommand = buildCommand + targetDir.absolutePath
        val process = ProcessBuilder(fullCommand).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw AssertionError("Command timed out: ${fullCommand.joinToString(" ")}")
        }
        assertEquals(
            0,
            process.exitValue(),
            "Kustomize build failed for $targetDir.\nCommand: ${fullCommand.joinToString(" ")}\nSTDERR:\n$stderr\nSTDOUT:\n$stdout"
        )
        assertTrue(stdout.isNotBlank(), "Kustomize build returned empty output for $targetDir")
        return stdout
    }

    private fun parseRenderedConfigMap(yamlText: String): RenderedConfigMap {
        val lines = normalizeNewlines(yamlText).lines()
        var apiVersion = ""
        var kind = ""
        var name = ""
        var namespace: String? = null
        val labels = linkedMapOf<String, String>()
        val rulesLines = mutableListOf<String>()

        var topSection: String? = null
        var inMetadataLabels = false
        var inRulesBlock = false
        var rulesBlockIndent: Int? = null

        for (line in lines) {
            val indent = line.indexOfFirst { it != ' ' }.let { if (it == -1) line.length else it }
            val trimmed = line.trim()

            if (inRulesBlock) {
                if (trimmed.isEmpty()) {
                    rulesLines += ""
                    continue
                }
                if (indent == 0) {
                    inRulesBlock = false
                } else {
                    val baseIndent = rulesBlockIndent ?: indent.also { rulesBlockIndent = it }
                    if (indent >= baseIndent) {
                        rulesLines += line.substring(baseIndent)
                        continue
                    } else {
                        inRulesBlock = false
                    }
                }
            }

            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (indent == 0) {
                inMetadataLabels = false
                when {
                    trimmed.startsWith("apiVersion:") -> {
                        apiVersion = trimmed.removePrefix("apiVersion:").trim().trim('"', '\'')
                        topSection = null
                    }
                    trimmed.startsWith("kind:") -> {
                        kind = trimmed.removePrefix("kind:").trim().trim('"', '\'')
                        topSection = null
                    }
                    trimmed == "data:" -> topSection = "data"
                    trimmed == "metadata:" -> topSection = "metadata"
                    else -> topSection = null
                }
                continue
            }

            when (topSection) {
                "data" -> {
                    if (indent == 2 && trimmed == "balletmaster-loki-rules.yaml: |") {
                        inRulesBlock = true
                        rulesBlockIndent = null
                    }
                }
                "metadata" -> {
                    if (indent == 2) {
                        inMetadataLabels = false
                        when {
                            trimmed.startsWith("name:") ->
                                name = trimmed.removePrefix("name:").trim().trim('"', '\'')
                            trimmed.startsWith("namespace:") ->
                                namespace = trimmed.removePrefix("namespace:").trim().trim('"', '\'')
                            trimmed == "labels:" ->
                                inMetadataLabels = true
                        }
                    } else if (inMetadataLabels && indent == 4 && trimmed.contains(":")) {
                        val (k, v) = trimmed.split(":", limit = 2)
                        labels[k.trim()] = v.trim().trim('"', '\'')
                    }
                }
            }
        }

        return RenderedConfigMap(
            apiVersion = apiVersion,
            kind = kind,
            name = name,
            namespace = namespace,
            labels = labels,
            rulesYaml = rulesLines.joinToString("\n")
        )
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

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
