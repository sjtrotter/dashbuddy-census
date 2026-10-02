package cloud.trotter.census.server.jobs

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate

class HealthAlarmsTest {
    private val day = LocalDate.of(2026, 9, 18)
    private val offer = "doordash.screen.offer_popup"
    private val waiting = "doordash.screen.waiting_for_offer"
    private val rare = "doordash.screen.rare"

    @Test
    fun `September 18 acceptance replay detects exactly the dead offer rule`() {
        val history = (1L..28L).map { ago ->
            DayReport(day.minusDays(ago), "doordash", "8.0", 800, ruleCounts = mapOf(offer to 30, waiting to 200, rare to 3))
        }
        val september17 = DayReport(day.minusDays(1), "doordash", "8.0", 812, ruleCounts = mapOf(offer to 28, waiting to 210, rare to 0))
        assertEquals(emptyList<String>(), silentRuleDeath(history, september17))
        val september18 = september17.copy(day = day, ruleCounts = mapOf(offer to 0, waiting to 210))
        assertEquals(listOf(offer), silentRuleDeath(history.drop(1) + september17, september18))
        assertEquals(listOf(offer), silentRuleDeath(history, september18.copy(ruleCounts = mapOf(waiting to 210))))
        assertEquals(emptyList<String>(), silentRuleDeath(history, september18.copy(admitted = 50, ruleCounts = emptyMap())))
        assertEquals(emptyList<String>(), silentRuleDeath(history.take(2), september18))
        assertEquals(emptyList<String>(), silentRuleDeath(history.map { it.copy(admitted = 199) }, september18))
    }

    @Test
    fun `median uses calendar window zeros and distinct dashing days`() {
        val current = DayReport(day, "doordash", "new", 200)
        val history = (1L..3L).map { DayReport(day.minusDays(it), "doordash", "old", 200, ruleCounts = mapOf(offer to 5)) }
        assertEquals(listOf(offer), silentRuleDeath(history, current))
        assertEquals(emptyList<String>(), silentRuleDeath(history.map { it.copy(ruleCounts = mapOf(offer to 3)) }, current))
        assertEquals(emptyList<String>(), silentRuleDeath(history.take(1) + history.take(1) + history.take(1), current))
        assertEquals(emptyList<String>(), silentRuleDeath(history.map { it.copy(day = it.day.minusDays(28)) }, current))
        assertEquals(emptyList<String>(), silentRuleDeath(history.map { it.copy(platform = "uber") }, current))
        assertEquals(emptyList<String>(), silentRuleDeath(history.take(1) + history.drop(1).map { it.copy(ruleCounts = emptyMap()) }, current))
    }

    @Test
    fun `share cliff requires two installs different versions and strictly more than eighty percent loss`() {
        val previous = FleetDay(day.minusDays(1), "doordash", "8.0", 2, 1000, ruleCounts = mapOf(offer to 100, waiting to 200))
        val current = FleetDay(day, "doordash", "8.1", 2, 1000, ruleCounts = mapOf(offer to 19, waiting to 200))
        assertEquals(listOf(offer), ruleShareCliff(previous, current))
        assertEquals(emptyList<String>(), ruleShareCliff(previous, current.copy(ruleCounts = mapOf(offer to 20, waiting to 200))))
        assertEquals(emptyList<String>(), ruleShareCliff(previous, current.copy(installsReporting = 1)))
        assertEquals(emptyList<String>(), ruleShareCliff(previous.copy(installsReporting = 1), current))
        assertEquals(emptyList<String>(), ruleShareCliff(previous, current.copy(platformAppVersion = "8.0")))
        assertEquals(emptyList<String>(), ruleShareCliff(previous, current.copy(admitted = 0)))
        assertEquals(emptyList<String>(), ruleShareCliff(previous, current.copy(day = previous.day)))
    }

    @Test
    fun `unknown surge thresholds include trips fleet ratio and new clusters`() {
        val report = DayReport(day, "doordash", "8.0", 10, trips = 1, installPrefix = "12345678")
        val fleet = FleetDay(day, "doordash", "8.0", 2, 10, unknown = 10)
        val alarms = unknownSurge(listOf(report), listOf(fleet), listOf(NewClusterCount("doordash", "8.0", 5)))
        assertEquals(listOf("trips", "fleet_unknown", "new_clusters"), alarms.map { it.kind })
        assertEquals("12345678", alarms.first().installPrefix)
        assertTrue(unknownSurge(listOf(report.copy(trips = 0)), listOf(fleet.copy(unknown = 9)), listOf(NewClusterCount("doordash", "8.0", 4))).isEmpty())
        assertTrue(unknownSurge(emptyList(), listOf(fleet.copy(installsReporting = 1))).isEmpty())
        assertTrue(unknownSurge(emptyList(), listOf(fleet.copy(admitted = 0, unknown = 0))).isEmpty())
    }

    @Test
    fun `silence boundary is two UTC days and only trusted installs`() {
        assertFalse(silence(day, day, true))
        assertFalse(silence(day.minusDays(1), day, true))
        assertTrue(silence(day.minusDays(2), day, true))
        assertTrue(silence(day.minusDays(3), day, true))
        assertFalse(silence(day.minusDays(2), day, false))
        assertFalse(silence(null, day, true))
        assertFalse(silence(day.plusDays(1), day, true))
    }

    @Test
    fun `logging sink emits only alarm dimensions at warn`() {
        val logger = LoggerFactory.getLogger("Alarm") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            LoggingAlarmSink().raise(Alarm("silent_rule_death", "doordash", "8.0", "12345678", listOf(offer)))
            val event = logs.list.single()
            assertEquals(Level.WARN, event.level)
            assertEquals("alarm kind=silent_rule_death platform=doordash version=8.0 install_prefix=12345678 rule_ids=[$offer]", event.formattedMessage)
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }
}
