package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.health.ShadowRefDto
import cloud.trotter.census.contract.health.ShadowTallyDto
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.healthFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

class HealthReportValidatorTest {
    private val policy = Policy()
    private val today = LocalDate.of(2026, 9, 18)
    private val fixture = healthFixture()

    @Test
    fun `valid report and inclusive boundaries are accepted`() {
        assertTrue(HealthReportValidator.validate(fixture, today, policy) is HealthReportVerdict.Accepted)
        for (day in listOf(today.minusDays(7), today.plusDays(1))) {
            assertTrue(HealthReportValidator.validate(item("day", JsonPrimitive(day.toString())), today, policy) is HealthReportVerdict.Accepted)
        }
        for (key in listOf("admitted", "unknown", "trips")) {
            assertTrue(HealthReportValidator.validate(item(key, JsonPrimitive(1_000_000)), today, policy) is HealthReportVerdict.Accepted)
        }
        val empty = JsonObject(fixture + mapOf("admitted" to JsonPrimitive(0), "ruleCounts" to JsonObject(emptyMap())))
        assertTrue(HealthReportValidator.validate(empty, today, policy) is HealthReportVerdict.Accepted)
        val versions = mapOf(
            "platformAppVersion" to listOf("1", "12345.12345.12345.12345"),
            "appVersion" to listOf("test", "1.2.3+abcdef0.dirty", "1.2.3+nogit"),
            "rulesetVersion" to listOf("corpus", "dev", "v1.2.3-rc1"),
        )
        for ((key, values) in versions) for (value in values) {
            assertTrue(HealthReportValidator.validate(item(key, JsonPrimitive(value)), today, policy) is HealthReportVerdict.Accepted)
        }
        for (platform in policy.acceptedPlatforms) {
            assertTrue(HealthReportValidator.validate(item("platform", JsonPrimitive(platform)), today, policy) is HealthReportVerdict.Accepted)
        }
    }

    @Test
    fun `all reason codes and strict scalar types are enforced`() {
        reject("bad_item", JsonArray(emptyList()))
        reject("unknown_field", item("extra", JsonPrimitive(1)))
        reject("bad_day", item("day", JsonPrimitive("2026-02-30")))
        reject("bad_day", item("day", JsonPrimitive("2026-9-18")))
        reject("stale_day", item("day", JsonPrimitive(today.minusDays(8).toString())))
        reject("stale_day", item("day", JsonPrimitive(today.plusDays(2).toString())))
        reject("bad_platform", item("platform", JsonPrimitive("Door Dash")))
        reject("bad_platform", item("platform", JsonPrimitive("private_customer_jane")))
        assertEquals(HealthReportVerdict.Rejected("bad_platform"), HealthReportValidator.validate(fixture, today, policy.copy(acceptedPlatforms = listOf("uber"))))
        for (key in listOf("platformAppVersion", "appVersion", "rulesetVersion")) {
            reject("bad_version", JsonObject(fixture - key))
            for (value in listOf(JsonPrimitive(""), JsonPrimitive("a".repeat(65)), JsonPrimitive("plain text"), JsonPrimitive("PRIVATE_CUSTOMER_JANE"), JsonPrimitive("a".repeat(64)), JsonPrimitive(1))) {
                reject("bad_version", item(key, value))
            }
        }
        for (key in listOf("admitted", "unknown", "trips")) {
            for (value in listOf(JsonPrimitive(-1), JsonPrimitive(1_000_001), JsonPrimitive("1"), JsonPrimitive(1.5))) {
                reject("bad_count", item(key, value))
            }
        }
        reject("bad_count", item("ruleCounts", JsonArray(emptyList())))
        for (rule in listOf("one", "DoorDash.screen", "a.b.c.d.e.f", "a..b", "a.b-")) {
            reject("bad_rule_id", item("ruleCounts", JsonObject(mapOf(rule to JsonPrimitive(1)))))
        }
        reject("bad_count", item("ruleCounts", JsonObject(mapOf("a.b" to JsonPrimitive("1")))))
        reject("bad_count", item("ruleCounts", JsonObject(mapOf("a.b" to JsonPrimitive(813)))))
        reject("bad_count", item("ruleCounts", JsonObject((0..512).associate { "a.b$it" to JsonPrimitive(0) })))
        reject("too_large", item("ruleCounts", JsonObject((0..100).associate { "a.${"b".repeat(90)}$it" to JsonPrimitive(0) })))
        reject("unknown_field", JsonObject(item("extra", JsonPrimitive(1)) + ("day" to JsonPrimitive("bad"))))
    }

    @Test
    fun `a report with per-rule parse counts is accepted and one without them still is (#64)`() {
        val without = HealthReportValidator.validate(fixture, today, policy) as HealthReportVerdict.Accepted
        assertEquals(null, without.report.parseShortfall)
        val counts = JsonObject(mapOf("doordash.screen.offer_popup" to JsonPrimitive(3), "doordash.screen.waiting_for_offer" to JsonPrimitive(0)))
        val with = HealthReportValidator.validate(item("parseShortfall", counts), today, policy) as HealthReportVerdict.Accepted
        assertEquals(mapOf("doordash.screen.offer_popup" to 3, "doordash.screen.waiting_for_offer" to 0), with.report.parseShortfall)
        assertEquals(without.report.copy(parseShortfall = with.report.parseShortfall), with.report)
        reject("bad_count", item("parseShortfall", JsonNull))
        // A sample count of 20 at most, and never an attempt total.
        reject("bad_count", item("parseShortfall", JsonObject(mapOf("a.b" to JsonPrimitive(21)))))
        reject("bad_count", item("parseShortfall", JsonObject(mapOf("a.b" to JsonObject(mapOf("shortfall" to JsonPrimitive(2), "eligible" to JsonPrimitive(40)))))))
        reject("bad_rule_id", item("parseShortfall", JsonObject(mapOf("a..b" to JsonPrimitive(0)))))
        reject("bad_rule_id", item("parseShortfall", JsonObject(mapOf("a.${"b".repeat(95)}" to JsonPrimitive(0)))))
    }

    @Test
    fun `a report with shadow counts and refused probes is accepted and one without them still is (#102)`() {
        val without = HealthReportValidator.validate(fixture, today, policy) as HealthReportVerdict.Accepted
        assertEquals(null, without.report.shadow)
        assertEquals(null, without.report.probeRefused)
        val ids = mapOf("generation" to "0123456789abcdef", "rule" to "doordash.screen.offer_shadow", "digest" to "fedcba9876543210")
        val tally = JsonObject(ids.mapValues { JsonPrimitive(it.value) } + mapOf("tied" to JsonPrimitive(20), "onUnknown" to JsonPrimitive(20)))
        val ref = JsonObject(ids.mapValues { JsonPrimitive(it.value) })
        val with = HealthReportValidator.validate(JsonObject(fixture + mapOf("shadow" to JsonArray(listOf(tally)), "probeRefused" to JsonArray(listOf(ref)))), today, policy)
            as HealthReportVerdict.Accepted
        assertEquals(listOf(ShadowTallyDto(ids.getValue("generation"), ids.getValue("rule"), ids.getValue("digest"), tied = 20, onUnknown = 20)), with.report.shadow)
        assertEquals(listOf(ShadowRefDto(ids.getValue("generation"), ids.getValue("rule"), ids.getValue("digest"))), with.report.probeRefused)
        reject("bad_count", item("shadow", JsonNull))
        reject("bad_count", item("shadow", JsonArray(listOf(JsonObject(tally - "onUnknown")))))
        reject("bad_rule_id", item("shadow", JsonArray(listOf(tally, tally))))
        reject("unknown_field", item("probeRefused", JsonArray(listOf(JsonObject(ref + ("tied" to JsonPrimitive(0)))))))
    }

    @Test
    fun `the contract's health vectors get the same verdicts here`() {
        val vectors = File(System.getProperty("census.contractDir"), "conformance/health-report-vectors.jsonl").readLines().filter { it.isNotBlank() }
        assertTrue(vectors.size >= 10)
        for (line in vectors) {
            val row = Json.parseToJsonElement(line).jsonObject
            val report = row.getValue("report").jsonObject
            val day = LocalDate.parse(report.getValue("day").jsonPrimitive.content)
            val verdict = HealthReportValidator.validate(report, day, policy)
            val expected = row.getValue("verdict").jsonPrimitive.content
            val name = row.getValue("name").jsonPrimitive.content
            if (expected == "accepted") assertTrue(verdict is HealthReportVerdict.Accepted, name)
            else assertEquals(HealthReportVerdict.Rejected(expected), verdict, name)
        }
    }

    private fun item(key: String, value: JsonElement): JsonObject = JsonObject(fixture + (key to value))
    private fun reject(reason: String, item: JsonElement) {
        assertEquals(HealthReportVerdict.Rejected(reason), HealthReportValidator.validate(item, today, policy))
    }
}
