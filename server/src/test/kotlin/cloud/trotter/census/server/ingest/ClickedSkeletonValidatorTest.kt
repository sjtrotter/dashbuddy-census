package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.server.CLICK_GENERATION
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.clickedFixture
import cloud.trotter.census.server.routes.duplicateKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

/** #87: clicked skeletons through the validator, against the contract's own vectors, and their in-batch key. */
class ClickedSkeletonValidatorTest {
    /** Every vector names this day (the contract's `clicked-vectors.jsonl`). */
    private val today = LocalDate.of(2026, 11, 20)

    private fun verdict(item: kotlinx.serialization.json.JsonElement, policy: Policy = Policy()): String =
        when (val verdict = SkeletonValidator.validate(item, policy, today)) {
            is ItemVerdict.Accepted -> "accepted"
            is ItemVerdict.Rejected -> verdict.reason
        }

    @Test fun `the contract's clicked vectors get their server verdicts here`() {
        val vectors = File(System.getProperty("census.contractDir"), "conformance/clicked-vectors.jsonl").readLines().filter { it.isNotBlank() }
        assertTrue(vectors.size >= 39)
        val seen = mutableSetOf<String>()
        for (line in vectors) {
            val vector = Json.parseToJsonElement(line).jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val expected = vector.getValue("server").jsonPrimitive.content
            assertEquals(expected, verdict(vector.getValue("item")), name)
            seen += expected
        }
        assertTrue(setOf("accepted", "bad_item", "bad_tap_ref", "unknown_field", "missing_hash").all { it in seen }, seen.toString())
    }

    @Test fun `an accepted clicked item keeps its tap and is a v2 screen body`() {
        val accepted = SkeletonValidator.validate(clickedFixture(today, ownerPath = "root", tapPath = "root/0"), Policy(), today) as ItemVerdict.Accepted
        val item = accepted.item as UiSkeletonDto
        assertEquals(SkeletonSchema.SCHEMA_ID_CLICKED, item.schemaId)
        assertEquals("root", item.tap?.ownerPath)
        assertEquals("root/0", item.tap?.tapPath)
        assertEquals(CLICK_GENERATION, item.tap?.generation)
        assertTrue(accepted.isClicked)
        assertFalse(accepted.isMatched)
        // The stored canonical body carries the tap: the view reads the owner from it by path.
        assertEquals("root", Json.parseToJsonElement(accepted.canonicalJson).jsonObject.getValue("tap").jsonObject.getValue("ownerPath").jsonPrimitive.content)
    }

    @Test fun `a clicked item is refused where the policy omits it, and a tap off its own tree is bad_tap_ref`() {
        val withoutClicked = Policy(acceptedSchemaIds = listOf(SkeletonSchema.SCHEMA_ID, SkeletonSchema.SCHEMA_ID_V2, SkeletonSchema.SCHEMA_ID_MATCHED))
        assertEquals("unknown_schema", verdict(clickedFixture(today), withoutClicked))
        // `root/0` takes no click, and `root/9` is off the tree: the body holds, so the tap is what failed.
        for (owner in listOf("root/0", "root/9")) {
            val badTap = JsonObject(clickedFixture(today) + ("tap" to Json.parseToJsonElement("""{"ownerPath":"$owner","generation":"$CLICK_GENERATION"}""")))
            assertEquals("bad_tap_ref", verdict(badTap), owner)
        }
        // A tap on a matched item, or a rule id on a clicked one, is a key outside the allowlist.
        assertEquals("unknown_field", verdict(JsonObject(clickedFixture(today) + ("ruleId" to JsonPrimitive("doordash.screen.offer")))))
    }

    @Test fun `the in-batch key of a clicked item is its full identity, never its fingerprint`() {
        fun key(item: JsonObject) = (SkeletonValidator.validate(item, Policy(), today) as ItemVerdict.Accepted).duplicateKey()
        val owner = key(clickedFixture(today))
        // Two owners of one screen, and one owner in two layouts of one fingerprint, are different items.
        assertNotEquals(owner, key(clickedFixture(today, ownerPath = "root", tapPath = "root/0")))
        assertNotEquals(owner, key(clickedFixture(today, extraWrapper = true)))
        assertEquals(clickedFixture(today).getValue("fingerprint"), clickedFixture(today, extraWrapper = true).getValue("fingerprint"))
        // Another day is another identity.
        assertNotEquals(owner, key(clickedFixture(today.minusDays(1))))
        // The same tap twice is one item.
        assertEquals(owner, key(clickedFixture(today)))
        // A clicked key never collides with the unknown or matched key of the same layout.
        val unknown = (SkeletonValidator.validate(cloud.trotter.census.server.screenV2Fixture(today), Policy(), today) as ItemVerdict.Accepted).duplicateKey()
        assertNotEquals(owner, unknown)
    }
}
