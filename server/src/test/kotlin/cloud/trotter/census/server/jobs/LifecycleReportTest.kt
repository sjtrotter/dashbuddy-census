package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class LifecycleReportTest {
    @Test fun `failures backlog and seven hour watchdog carry no private payload`() {
        var now = Instant.parse("2026-10-06T00:00:00Z")
        val alarms = mutableListOf<Alarm>()
        val report = LifecycleReport(object : Clock { override fun now() = now }, AlarmSink { alarms.add(it) })
        report.record(mapOf("tokens" to SweepResult(failed = true)))
        assertNull(report.lastSuccessAgeSeconds())
        report.record(mapOf("tokens" to SweepResult(remainingDue = 1)))
        report.record(mapOf("tokens" to SweepResult(remainingDue = 0)))
        now = now.plusSeconds(7*3600 - 1); report.watchdog()
        assertEquals(listOf("lifecycle_failure", "lifecycle_backlog"), alarms.map { it.kind })
        now = now.plusSeconds(1); report.watchdog()
        assertEquals("lifecycle_stale", alarms.last().kind)
        for (alarm in alarms) {
            val output = renderAlarm(alarm.copy(message = "PRIVATE_FAILURE", installPrefix = null))
            assertTrue(output.startsWith("kind=lifecycle_"))
            assertFalse(output.contains("PRIVATE_FAILURE"))
            assertFalse(output.contains("[redacted]"))
        }
    }
}
