package org.dmg.balletmaster.investigation.service

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Verifies that booting with default application.yml fails closed on /api/v1/investigations
 * while keeping /actuator/health available for Kubernetes probes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvestigationFailClosedDefaultTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun deniesInvestigationRequestsByDefaultWhenAllowlistsAreUnconfigured() {
        mockMvc.get("/api/v1/investigations") {
            param("app", "payments")
            param("env", "prod")
            param("operationId", "ord-1")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("ACCESS_DENIED") }
        }
    }

    @Test
    fun keepsActuatorHealthAvailableWithoutDetails() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components") { doesNotExist() }
        }
    }
}
