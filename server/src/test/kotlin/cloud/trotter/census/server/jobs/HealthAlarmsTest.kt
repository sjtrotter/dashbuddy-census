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
import java.io.File
import java.time.Instant
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
    fun `a rule whose sampled shortfall jumps against its own history raises parse_shortfall and a steady one does not`() {
        // Each value is the shortfalls among the rule's last 20 eligible attempts that day: offer 2 of 20 normally.
        val history = (1L..5L).map { ago -> parseDay(day.minusDays(ago), offer to 2, waiting to 1) }
        assertEquals(emptyList<String>(), parseShortfallRise(history, parseDay(day, offer to 3, waiting to 1)))
        assertEquals(listOf(offer), parseShortfallRise(history, parseDay(day, offer to 15, waiting to 1)))
        // A rule absent today had fewer than 20 eligible attempts: no evidence, no alarm.
        assertEquals(emptyList<String>(), parseShortfallRise(history, parseDay(day, waiting to 1)))
    }

    @Test
    fun `parse_shortfall needs three reporting history days and a 25-point rise over the median`() {
        val history = (1L..3L).map { parseDay(day.minusDays(it), offer to 2) }
        // 10% normal; 7 of 20 (35%) is exactly 25 points up and fires, 6 of 20 (30%) does not.
        assertEquals(listOf(offer), parseShortfallRise(history, parseDay(day, offer to 7)))
        assertEquals(emptyList<String>(), parseShortfallRise(history, parseDay(day, offer to 6)))
        val jump = parseDay(day, offer to 20)
        assertEquals(emptyList<String>(), parseShortfallRise(history.take(2), jump), "two days of history are too few")
        assertEquals(emptyList<String>(), parseShortfallRise(history.map { it.copy(parseShortfall = null) }, jump), "history without counts")
        assertEquals(emptyList<String>(), parseShortfallRise(history.map { parseDay(it.day, waiting to 2) }, jump), "another rule's history")
        assertEquals(emptyList<String>(), parseShortfallRise(history.map { it.copy(day = it.day.minusDays(28)) }, jump), "outside the 28-day window")
        assertEquals(emptyList<String>(), parseShortfallRise(history.map { it.copy(platform = "uber") }, jump))
        assertEquals(emptyList<String>(), parseShortfallRise(history, jump.copy(parseShortfall = null)), "a report without counts")
        // Two versions on one day are two samples: 1 and 3 of 20 each is 4 of 40, one 10% day.
        val split = history.take(2) + listOf(parseDay(history[2].day, offer to 1), parseDay(history[2].day, offer to 3).copy(platformAppVersion = "8.1"))
        assertEquals(listOf(offer), parseShortfallRise(split, parseDay(day, offer to 7)))
        assertEquals(emptyList<String>(), parseShortfallRise(split.take(2), parseDay(day, offer to 7)))
        // The reporting day's two samples: 7 + 7 of 40 is 35%, still exactly 25 points over 10%.
        assertEquals(listOf(offer), parseShortfallRise(history, DayReport(day, "doordash", "8.0", 800, parseShortfall = mapOf(offer to ParseSamples(14, 2)))))
    }

    @Test
    fun `the median of daily rates sets the normal level, so one bad past day does not mask a break`() {
        val history = listOf(2, 2, 20, 2).mapIndexed { index, shortfalls -> parseDay(day.minusDays(index + 1L), offer to shortfalls) }
        // Even count: the median is the mean of the two middle rates, (10% + 10%) / 2.
        assertEquals(listOf(offer), parseShortfallRise(history, parseDay(day, offer to 7)))
        assertEquals(emptyList<String>(), parseShortfallRise(history, parseDay(day, offer to 6)))
    }

    @Test
    fun `parse_shortfall renders and both alarm-kind allowlists list every kind`() {
        assertEquals(
            "kind=parse_shortfall\nplatform=doordash\nversion=8.0\ninstall_prefix=-\nrule_ids=[$offer]",
            renderAlarm(Alarm("parse_shortfall", "doordash", "8.0", listOf(offer))),
        )
        // The host publisher (cloud-init) drops a spool file whose kind it does not list, so the two lists must agree.
        val kinds = Regex("""KINDS = r'\(\?:([a-z_|]+)\|\\\[redacted\\\]\)'""").find(template)
        assertTrue(kinds != null, "KINDS line not found in cloud-init.yaml.tftpl")
        assertEquals(alarmKinds, kinds!!.groupValues[1].split('|').toSet())
        assertTrue("parse_shortfall" in alarmKinds)
    }

    @Test
    fun `a long rule list splits into spool files the host publisher accepts, losing and repeating no rule`() {
        // The publisher rejects (and deletes) a spool file over this many bytes.
        assertTrue("info.st_size > $PUBLISHER_MAX_BYTES" in template && "len(data) > $PUBLISHER_MAX_BYTES" in template)
        assertTrue(ALARM_FILE_BUDGET <= PUBLISHER_MAX_BYTES - 512)
        val longest = "a." + "b".repeat(MAX_ALARM_RULE_ID_LENGTH - 2)
        val cases = listOf(
            // The parse field's own maximum: 128 rules of 96 characters, on the longest platform and version.
            (0 until 128).map { "a.${"c".repeat(91)}${"%03d".format(it)}" },
            // Legacy rule counts: 512 rule ids at the render cap, and ids beyond it, which render as [redacted].
            (0 until 512).map { "${longest.dropLast(3)}${"%03d".format(it)}" },
            (0 until 40).map { "$longest${"b".repeat(it + 1)}" },
            emptyList(),
        )
        for (rules in cases) {
            val alarm = Alarm("parse_shortfall", "a".repeat(32), "12345.12345.12345.12345", rules)
            val parts = splitForPublisher(alarm)
            for (part in parts) {
                val bytes = renderAlarm(part).toByteArray(Charsets.UTF_8).size
                assertTrue(bytes <= ALARM_FILE_BUDGET, "rendered $bytes bytes")
                assertEquals(alarm.copy(ruleIds = part.ruleIds), part)
            }
            assertEquals(rules, parts.flatMap { it.ruleIds })
            if (rules.isEmpty()) assertEquals(listOf(alarm), parts)
        }
        assertEquals(1, splitForPublisher(Alarm("parse_shortfall", "doordash", "8.0", listOf(offer, waiting))).size)
        assertEquals("kind=silent_rule_death\nplatform=doordash\nversion=8.0\ninstall_prefix=-\nrule_ids=[[redacted]]",
            renderAlarm(Alarm("silent_rule_death", "doordash", "8.0", listOf(longest + "b"))))
        assertTrue(renderAlarm(Alarm("silent_rule_death", "doordash", "8.0", listOf(longest))).endsWith("[$longest]"))
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
        val report = DayReport(day, "doordash", "8.0", 10, trips = 1)
        val fleet = FleetDay(day, "doordash", "8.0", 2, 10, unknown = 10)
        val alarms = unknownSurge(listOf(report), listOf(fleet), listOf(NewClusterCount("doordash", "8.0", 5)))
        assertEquals(listOf("trips", "fleet_unknown", "new_clusters"), alarms.map { it.kind })
        assertEquals("kind=trips\nplatform=doordash\nversion=8.0\ninstall_prefix=-\nrule_ids=[]", renderAlarm(alarms.first()))
        assertTrue(unknownSurge(listOf(report.copy(trips = 0)), listOf(fleet.copy(unknown = 9)), listOf(NewClusterCount("doordash", "8.0", 4))).isEmpty())
        assertTrue(unknownSurge(emptyList(), listOf(fleet.copy(installsReporting = 1))).isEmpty())
        assertTrue(unknownSurge(emptyList(), listOf(fleet.copy(admitted = 0, unknown = 0))).isEmpty())
    }

    @Test
    fun `silence requires 48 elapsed hours and an old stored day for a trusted install`() {
        val now = Instant.parse("2026-09-18T12:00:00Z")
        val startedAt = now.minusSeconds(3 * 86400)
        val boundary = now.minusSeconds(48 * 3600)
        assertTrue(silence(boundary, startedAt, now, day.minusDays(2), day, true))
        assertFalse(silence(boundary.plusSeconds(60), startedAt, now, day.minusDays(2), day, true))
        assertFalse(silence(now, startedAt, now, day.minusDays(2), day, true))
        assertTrue(silence(null, boundary, now, day.minusDays(2), day, true))
        assertFalse(silence(null, boundary.plusSeconds(60), now, day.minusDays(2), day, true))
        assertFalse(silence(null, now, now, day.minusDays(3), day, true))
        assertFalse(silence(boundary, startedAt, now, day.minusDays(1), day, true))
        assertFalse(silence(boundary, startedAt, now, day.minusDays(2), day, false))
        assertFalse(silence(boundary, startedAt, now, null, day, true))
    }

    private val template = File("../deploy/aws/cloud-init.yaml.tftpl").readText()

    /** One stored report: each value is one install's sampled shortfall count for that rule. */
    private fun parseDay(day: LocalDate, vararg counts: Pair<String, Int>) =
        DayReport(day, "doordash", "8.0", 800, parseShortfall = counts.associate { (rule, k) -> rule to ParseSamples(k, 1) })

    @Test
    fun `logging sink emits only alarm dimensions at warn`() {
        val logger = LoggerFactory.getLogger("Alarm") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            LoggingAlarmSink().raise(Alarm("silent_rule_death", "doordash", "8.0", listOf(offer)))
            val event = logs.list.single()
            assertEquals(Level.WARN, event.level)
            assertEquals("alarm kind=silent_rule_death platform=doordash version=8.0 install_prefix=- rule_ids=[$offer]", event.formattedMessage)
            LoggingAlarmSink().raise(Alarm("trips", "doordash", "PRIVATE_CUSTOMER_JANE"))
            assertEquals("alarm kind=trips platform=doordash version=[redacted] install_prefix=- rule_ids=[]", logs.list.last().formattedMessage)
            LoggingAlarmSink().raise(Alarm("silent_rule_death", "PRIVATE_PLATFORM", "1.0.0", ruleIds = listOf("PRIVATE_RULE", offer)))
            assertEquals("alarm kind=silent_rule_death platform=[redacted] version=1.0.0 install_prefix=- rule_ids=[[redacted], $offer]", logs.list.last().formattedMessage)
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }
}
