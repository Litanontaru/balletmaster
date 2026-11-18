package org.dmg.balletmaster.investigation.service

import org.dmg.balletmaster.observation.ObservationScope
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InvestigationAccessPolicyTest {

    private val end = Instant.parse("2026-11-18T12:00:00Z")
    private val start = end.minus(Duration.ofHours(1))

    @Test
    fun defaultsAreFailClosedWhenAllowlistsAreEmpty() {
        val policy = InvestigationAccessPolicy(InvestigationProperties.SecurityProperties())

        assertThrows(InvestigationAccessDeniedException::class.java) {
            policy.validateRequest("payments", "prod", "ord-1", ObservationScope.SDK_ONLY, start, end, null, null)
        }
    }

    @Test
    fun allowsConfiguredTargetsAndOptionalAllCorrelated() {
        val policy = InvestigationAccessPolicy(
            InvestigationProperties.SecurityProperties(
                allowedApps = setOf("payments"),
                allowedEnvironments = setOf("prod"),
                allowAllCorrelated = true
            )
        )

        policy.validateRequest("payments", "prod", "ord-1", ObservationScope.ALL_CORRELATED, start, end, "pay", "STEP_A")
    }

    @Test
    fun rejectsOverlongParametersAndControlCharacters() {
        val policy = InvestigationAccessPolicy(
            InvestigationProperties.SecurityProperties(
                allowedApps = setOf("payments"),
                allowedEnvironments = setOf("prod"),
                maxParameterLength = 16
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            policy.validateRequest("payments", "prod", "a".repeat(17), ObservationScope.SDK_ONLY, start, end, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.validateRequest("payments", "prod", "ord-1\nforged", ObservationScope.SDK_ONLY, start, end, null, null)
        }
    }

    @Test
    fun lokiPropertiesToStringMasksAuthHeader() {
        val props = InvestigationProperties.LokiProperties(authHeader = "Bearer SUPER_SECRET_TOKEN")
        val rendered = props.toString()

        assertFalse(rendered.contains("SUPER_SECRET_TOKEN"))
        assertTrue(rendered.contains("authHeader=***"))
    }
}
