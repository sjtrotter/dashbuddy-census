package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.server.MATCHED_RULE
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.matchedFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/** #69 (S1): matched skeletons through the validator, against the contract's own vectors. */
class MatchedSkeletonValidatorTest {
    /** Every vector names this day (the contract's `matched-vectors.jsonl`). */
    private val today = LocalDate.of(2026, 11, 20)

    @Test fun `the contract's matched vectors get their server verdicts here`() {
        val vectors = File(System.getProperty("census.contractDir"), "conformance/matched-vectors.jsonl").readLines().filter { it.isNotBlank() }
        assertTrue(vectors.size >= 14)
        val seen = mutableSetOf<String>()
        for (line in vectors) {
            val vector = Json.parseToJsonElement(line).jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val expected = vector.getValue("server").jsonPrimitive.content
            val verdict = SkeletonValidator.validate(vector.getValue("item"), Policy(), today)
            val actual = when (verdict) {
                is ItemVerdict.Accepted -> "accepted"
                is ItemVerdict.Rejected -> verdict.reason
            }
            assertEquals(expected, actual, name)
            seen += expected
        }
        assertTrue(setOf("accepted", "unknown_field", "bad_item", "bad_rule_id", "missing_hash", "plaintext_field").all { it in seen })
    }

    @Test fun `an accepted matched item keeps its rule id and is a v2 screen body`() {
        val item = (SkeletonValidator.validate(matchedFixture(today), Policy(), today) as ItemVerdict.Accepted).item as UiSkeletonDto
        assertEquals(SkeletonSchema.SCHEMA_ID_MATCHED, item.schemaId)
        assertEquals(MATCHED_RULE, item.ruleId)
        assertTrue(item.isMatched)
    }

    @Test fun `a matched item is refused where the policy omits it, and the unknown platform never carries a rule`() {
        val v2Only = Policy(acceptedSchemaIds = listOf(SkeletonSchema.SCHEMA_ID, SkeletonSchema.SCHEMA_ID_V2))
        assertEquals(ItemVerdict.Rejected("unknown_schema"), SkeletonValidator.validate(matchedFixture(today), v2Only, today))
        // The contract refuses to build one, so the wire form is edited by hand: the grammar's leading letter refuses it.
        val unknownPlatform = JsonObject(matchedFixture(today) + mapOf("platform" to JsonPrimitive("_unknown"),
            "ruleId" to JsonPrimitive("_unknown.screen.offer")))
        assertEquals(ItemVerdict.Rejected("bad_rule_id"), SkeletonValidator.validate(unknownPlatform, Policy(), today))
        val uber = JsonObject(matchedFixture(today) + mapOf("platform" to JsonPrimitive("uber"), "ruleId" to JsonPrimitive("uber.screen.offer")))
        assertTrue(SkeletonValidator.validate(uber, Policy(), today) is ItemVerdict.Accepted)
    }
}
