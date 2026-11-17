package org.dmg.balletmaster.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class ObservationModelTest {

    @Test
    fun rejectsInvalidOperationQueryParameters() {
        val now = Instant.now()
        val earlier = now.minusSeconds(60)

        assertThrows(IllegalArgumentException::class.java) {
            OperationQuery(app = "", environment = "prod", operationId = "1", start = earlier, end = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationQuery(app = "app", environment = " ", operationId = "1", start = earlier, end = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationQuery(app = "app", environment = "prod", operationId = "", start = earlier, end = now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationQuery(app = "app", environment = "prod", operationId = "1", start = now, end = earlier)
        }
    }

    @Test
    fun createsValidOperationQuery() {
        val now = Instant.now()
        val earlier = now.minusSeconds(300)
        val query = OperationQuery(
            app = "payments",
            environment = "prod",
            operationId = "ord-42",
            start = earlier,
            end = now,
            scope = ObservationScope.ALL_CORRELATED,
            operation = "orderFlow",
            step = "CHARGE_CARD",
            attempt = 1
        )

        assertEquals("payments", query.app)
        assertEquals("prod", query.environment)
        assertEquals("ord-42", query.operationId)
        assertEquals(ObservationScope.ALL_CORRELATED, query.scope)
        assertEquals(1, query.attempt)
    }
}
