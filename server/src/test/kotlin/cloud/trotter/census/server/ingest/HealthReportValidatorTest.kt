package cloud.trotter.census.server.ingest

import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.healthFixture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    private fun item(key: String, value: JsonElement): JsonObject = JsonObject(fixture + (key to value))
    private fun reject(reason: String, item: JsonElement) {
        assertEquals(HealthReportVerdict.Rejected(reason), HealthReportValidator.validate(item, today, policy))
    }
}
