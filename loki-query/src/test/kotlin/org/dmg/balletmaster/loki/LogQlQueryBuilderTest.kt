package org.dmg.balletmaster.loki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogQlQueryBuilderTest {

    @Test
    fun buildsStandardSdkOnlyLogQlQuery() {
        val query = LogQlQueryBuilder.build(
            app = "payment-gateway",
            environment = "prod",
            operationId = "ord-99"
        )

        assertEquals("ord-99", query.operationId)
        assertEquals(LogScope.SDK_ONLY, query.scope)

        val logQl = query.logQl
        assertTrue(logQl.startsWith("{app=\"payment-gateway\", environment=\"prod\"}"))
        assertTrue(logQl.contains("|= \"ord-99\""))
        assertTrue(logQl.contains("| json"))
        assertTrue(logQl.contains("logger_name =~ \"^org\\\\.dmg\\\\.balletmaster(\\\\..*)?$\""))
        assertTrue(logQl.contains("ballet_operationId = \"ord-99\""))
    }

    @Test
    fun buildsAllCorrelatedQueryWithoutSdkLoggerFilter() {
        val query = LogQlQueryBuilder.build(
            app = "auth-service",
            environment = "staging",
            operationId = "usr-123",
            scope = LogScope.ALL_CORRELATED,
            extraStreamLabels = mapOf("cluster" to "eu-central-1"),
            operation = "userLogin",
            step = "VALIDATE_CREDENTIALS",
            attempt = 1,
            level = "WARN"
        )

        assertEquals(LogScope.ALL_CORRELATED, query.scope)
        val logQl = query.logQl

        assertTrue(logQl.contains("cluster=\"eu-central-1\""))
        assertTrue(!logQl.contains("org\\\\.dmg\\\\.balletmaster"))
        assertTrue(logQl.contains("ballet_operation = \"userLogin\""))
        assertTrue(logQl.contains("ballet_step = \"VALIDATE_CREDENTIALS\""))
        assertTrue(logQl.contains("ballet_attempt = 1"))
        assertTrue(logQl.contains("level = \"WARN\""))
    }

    @Test
    fun rejectsHighCardinalityKeysInStreamSelector() {
        val forbidden = listOf(
            "operationId", "ballet.operationId", "step", "ballet.step",
            "suffix", "ballet.suffix", "traceId", "requestId", "executionId"
        )

        for (key in forbidden) {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                LogQlQueryBuilder.build(
                    app = "app",
                    environment = "dev",
                    operationId = "1",
                    extraStreamLabels = mapOf(key to "val")
                )
            }
            assertTrue(ex.message!!.contains("high-cardinality and forbidden"), "Key '$key' should be rejected")
        }
    }

    @Test
    fun rejectsBlankMandatoryParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            LogQlQueryBuilder.build(app = "", environment = "prod", operationId = "1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LogQlQueryBuilder.build(app = "app", environment = "", operationId = "1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LogQlQueryBuilder.build(app = "app", environment = "prod", operationId = "")
        }
    }

    @Test
    fun escapesSpecialCharactersInLiterals() {
        val query = LogQlQueryBuilder.build(
            app = "app\"with\"quotes",
            environment = "prod\\slash",
            operationId = "op\"123\nnewline"
        )

        val logQl = query.logQl
        assertTrue(logQl.contains("app=\"app\\\"with\\\"quotes\""))
        assertTrue(logQl.contains("environment=\"prod\\\\slash\""))
        assertTrue(logQl.contains("|= \"op\\\"123\\nnewline\""))
    }
}
