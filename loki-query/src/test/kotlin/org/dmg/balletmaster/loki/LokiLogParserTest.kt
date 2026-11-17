package org.dmg.balletmaster.loki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LokiLogParserTest {

    @Test
    fun parsesLogbackJsonEncoderFormatWithNestedKvpListAndMdc() {
        val rawLine = """
            {
              "timestamp": "2026-11-12T16:38:45.833Z",
              "level": "INFO",
              "logger_name": "org.dmg.balletmaster.step",
              "message": "chain.stopped",
              "mdc": {
                "ballet.operation": "orderFlow",
                "ballet.operationId": "ord-1",
                "ballet.attempt": "0",
                "ballet.suffix": "",
                "ballet.step": "STEP_A",
                "ballet.replay": "false"
              },
              "kvpList": [
                { "key": "executedSteps", "value": 1 },
                { "key": "totalSteps", "value": 2 },
                { "key": "outcome", "value": "FAILURE" },
                { "key": "durationMs", "value": 12 }
              ]
            }
        """.trimIndent()

        val obs = LokiLogParser.parseLine(rawLine, mapOf("app" to "pay"), defaultTsNs = 1731429525833000000L)

        assertNull(obs.parseError)
        assertEquals("org.dmg.balletmaster.step", obs.logger)
        assertEquals("INFO", obs.level)
        assertEquals("chain.stopped", obs.event)
        assertEquals(1731429525833000000L, obs.timestampNs)
        assertEquals(Instant.ofEpochMilli(1731429525833L), obs.timestamp)

        val corr = obs.correlation
        assertNotNull(corr)
        assertEquals("orderFlow", corr!!.operation)
        assertEquals("ord-1", corr.operationId)
        assertEquals(0, corr.attempt)
        assertEquals("", corr.suffix)
        assertEquals("STEP_A", corr.step)
        assertEquals(false, corr.replay)

        assertEquals("FAILURE", obs.attributes["outcome"])
        assertEquals(1, obs.attributes["executedSteps"])
        assertEquals(2, obs.attributes["totalSteps"])
        assertEquals(12, obs.attributes["durationMs"])
    }

    @Test
    fun parsesFlatStructuredJsonFormat() {
        val rawLine = """
            {
              "time": 1731429525000,
              "level": "WARN",
              "logger": "org.dmg.balletmaster.lock",
              "event": "lock.stolen_expired",
              "ballet_operation": "lockOp",
              "ballet_operationId": "lock-42",
              "ballet_attempt": 2,
              "leaseMs": 500,
              "attempts": 3
            }
        """.trimIndent()

        val obs = LokiLogParser.parseLine(rawLine)

        assertNull(obs.parseError)
        assertEquals("org.dmg.balletmaster.lock", obs.logger)
        assertEquals("WARN", obs.level)
        assertEquals("lock.stolen_expired", obs.event)

        val corr = obs.correlation
        assertNotNull(corr)
        assertEquals("lockOp", corr!!.operation)
        assertEquals("lock-42", corr.operationId)
        assertEquals(2, corr.attempt)

        assertEquals(500, obs.attributes["leaseMs"])
        assertEquals(3, obs.attributes["attempts"])
    }

    @Test
    fun handlesMalformedJsonGracefullyWithoutThrowing() {
        val nonJson = "This is a plain text log line from an unformatted logger"
        val obs = LokiLogParser.parseLine(nonJson, defaultTsNs = 1000L)

        assertNotNull(obs.parseError)
        assertTrue(obs.parseError!!.contains("Invalid JSON"))
        assertEquals(nonJson, obs.rawLine)
        assertEquals(1000L, obs.timestampNs)
    }

    @Test
    fun parsesLokiStreamResponseWithMultipleStreams() {
        val response = """
            {
              "status": "success",
              "data": {
                "resultType": "streams",
                "result": [
                  {
                    "stream": { "app": "app-a", "env": "prod" },
                    "values": [
                      [ "1000000000", "{\"message\":\"event-1\"}" ],
                      [ "2000000000", "{\"message\":\"event-2\"}" ]
                    ]
                  },
                  {
                    "stream": { "app": "app-b", "env": "prod" },
                    "values": [
                      [ "1500000000", "{\"message\":\"event-1.5\"}" ]
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val entries = LokiLogParser.parseLokiStreamEntries(response)
        assertEquals(3, entries.size)

        assertEquals(1000000000L, entries[0].tsNs)
        assertEquals("app-a", entries[0].streamLabels["app"])

        assertEquals(2000000000L, entries[1].tsNs)
        assertEquals("app-a", entries[1].streamLabels["app"])

        assertEquals(1500000000L, entries[2].tsNs)
        assertEquals("app-b", entries[2].streamLabels["app"])
    }
}
