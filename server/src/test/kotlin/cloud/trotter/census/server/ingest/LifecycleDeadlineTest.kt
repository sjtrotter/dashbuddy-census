package cloud.trotter.census.server.ingest

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class LifecycleDeadlineTest {
    @Test fun `all retention boundaries are half open and future tolerance never adds retention`() {
        val day = LocalDate.of(2026, 10, 6)
        for (ttl in listOf(7L, 30L, 90L, 180L, 365L)) {
            assertFalse(LifecycleDeadline.expired(day.plusDays(ttl), day.plusDays(ttl - 1)))
            assertTrue(LifecycleDeadline.expired(day.plusDays(ttl), day.plusDays(ttl)))
        }
        assertEquals(day, LifecycleDeadline.observation(day.plusDays(1), day))
        assertEquals(day.minusDays(7), LifecycleDeadline.observation(day.minusDays(7), day))
        assertEquals(day.plusDays(90), LifecycleDeadline.sample(day, null))
        assertEquals(day.plusDays(30), LifecycleDeadline.sample(day.plusDays(100), day))
    }
}
