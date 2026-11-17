package org.dmg.balletmaster.loki.cli

import org.dmg.balletmaster.loki.BalletCorrelation
import org.dmg.balletmaster.loki.LogQlQueryBuilder
import org.dmg.balletmaster.loki.LogScope
import org.dmg.balletmaster.loki.LokiClientConfig
import org.dmg.balletmaster.loki.LokiLogObservation
import org.dmg.balletmaster.loki.LokiQueryClient
import java.net.URI
import java.time.Duration
import java.time.Instant

object LokiQueryCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args)
        if (code != 0) {
            System.exit(code)
        }
    }

    fun run(args: Array<String>): Int {
        val parsed = parseArgs(args)
        if (parsed == null) {
            printHelp()
            return 1
        }

        val (url, app, env, opId, scope, windowMinutes, orgId, token, pageLimit) = parsed

        val config = LokiClientConfig(
            baseUrl = URI.create(url),
            orgId = orgId,
            authHeader = token?.let { "Bearer $it" },
            defaultPageLimit = pageLimit
        )
        val client = LokiQueryClient(config)

        val query = LogQlQueryBuilder.build(
            app = app,
            environment = env,
            operationId = opId,
            scope = scope
        )

        val end = Instant.now()
        val start = end.minus(Duration.ofMinutes(windowMinutes.toLong()))

        println("================================================================================")
        println("BALLETMASTER LOKI HISTORICAL QUERY")
        println("================================================================================")
        println("Operation ID : $opId")
        println("App / Env    : $app / $env")
        println("Scope        : $scope")
        println("Time Window  : $start .. $end ($windowMinutes min)")
        println("LogQL        : ${query.logQl}")
        println("--------------------------------------------------------------------------------")

        try {
            val result = client.queryRange(query, start, end, pageLimit = pageLimit)

            if (result.observations.isEmpty()) {
                println("No matching events found.")
            } else {
                for (obs in result.observations) {
                    println(formatObservation(obs))
                }
            }

            println("--------------------------------------------------------------------------------")
            println("Result       : ${result.completeness}" + (result.truncationReason?.let { " ($it)" } ?: ""))
            println("Stats        : ${result.observations.size} observations, ${result.pagesFetched} pages, ${result.duplicatesDropped} duplicates dropped, ${result.parseFailures} parse failures")
            if (result.warnings.isNotEmpty()) {
                println("Warnings     :")
                result.warnings.forEach { println("  - $it") }
            }
            println("================================================================================")
            return 0
        } catch (e: Exception) {
            System.err.println("Query execution failed: ${e.message}")
            return 2
        }
    }

    private fun formatObservation(obs: LokiLogObservation): String {
        if (obs.parseError != null) {
            return "[${obs.timestamp}] [PARSE_ERROR] ${obs.parseError}: ${obs.rawLine}"
        }
        val lvl = (obs.level ?: "INFO").padEnd(5)
        val eventOrLogger = obs.event ?: obs.logger ?: "unknown"
        val step = obs.correlation?.step?.let { " step=$it" } ?: ""
        val outcome = obs.attributes["outcome"]?.let { " outcome=$it" } ?: ""
        val status = obs.attributes["status"]?.let { " status=$it" } ?: ""
        val remainingAttrs = obs.attributes
            .filterKeys { it !in setOf("outcome", "status", "ballet.step", "ballet.operationId", "ballet.operation") }
            .entries
            .joinToString(" ") { (k, v) -> "$k=$v" }
            .let { if (it.isNotBlank()) " [$it]" else "" }

        return "[${obs.timestamp}] $lvl $eventOrLogger$step$outcome$status$remainingAttrs"
    }

    private data class CliOptions(
        val url: String,
        val app: String,
        val env: String,
        val opId: String,
        val scope: LogScope,
        val windowMinutes: Int,
        val orgId: String?,
        val token: String?,
        val pageLimit: Int
    )

    private fun parseArgs(args: Array<String>): CliOptions? {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val key = args[i]
            if (key.startsWith("--") && i + 1 < args.size) {
                map[key.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                i++
            }
        }

        val url = map["url"] ?: return null
        val app = map["app"] ?: return null
        val env = map["env"] ?: return null
        val opId = map["op-id"] ?: return null
        val scope = map["scope"]?.let { LogScope.valueOf(it.uppercase()) } ?: LogScope.SDK_ONLY
        val window = map["window"]?.toIntOrNull() ?: 60
        val orgId = map["org-id"]
        val token = map["token"]
        val pageLimit = map["page-limit"]?.toIntOrNull() ?: 500

        return CliOptions(
            url = url,
            app = app,
            env = env,
            opId = opId,
            scope = scope,
            windowMinutes = window,
            orgId = orgId,
            token = token,
            pageLimit = pageLimit
        )
    }

    private fun printHelp() {
        println(
            """
            Balletmaster Loki Query CLI
            Usage:
              java -cp <classpath> org.dmg.balletmaster.loki.cli.LokiQueryCli [options]

            Required options:
              --url <url>         Loki HTTP endpoint (e.g. http://localhost:3100)
              --app <appName>     Application name stream label
              --env <envName>     Environment stream label (e.g. prod, staging, dev)
              --op-id <id>        Balletmaster operationId to query

            Optional options:
              --scope <scope>     SDK_ONLY (default) or ALL_CORRELATED
              --window <minutes>  Time window in minutes backwards from now (default: 60)
              --org-id <orgId>    Tenant ID header (X-Scope-OrgID)
              --token <token>     Bearer token for Authorization header
              --page-limit <num>  Maximum entries per query page (default: 500)
            """.trimIndent()
        )
    }
}
