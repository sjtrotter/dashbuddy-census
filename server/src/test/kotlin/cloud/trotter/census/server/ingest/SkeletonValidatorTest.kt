package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.server.Policy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.GZIPInputStream

class SkeletonValidatorTest {
    @Test
    fun `notification admission is explicit bounded and uses contract reason vocabulary`() {
        val day = LocalDate.of(2026, 10, 2)
        val raw = cloud.trotter.census.server.notificationFixture(day)
        val enabled = cloud.trotter.census.server.notificationPolicy
        fun verdict(value: JsonObject) = SkeletonValidator.validate(value, enabled, day)
        fun changed(key: String, value: JsonElement) = JsonObject(raw + (key to value))
        assertEquals(ItemVerdict.Rejected("unknown_schema"), SkeletonValidator.validate(raw, Policy(), day))
        assertTrue(verdict(raw) is ItemVerdict.Accepted)
        assertEquals(5, (verdict(raw) as ItemVerdict.Accepted).tokens.size)
        for ((key, value, reason) in listOf(
            Triple("kind", JsonPrimitive("mystery"), "unknown_skeleton_kind"),
            Triple("kind", JsonPrimitive("screen"), "kind_schema_mismatch"),
            Triple("channelId", JsonPrimitive("bad channel"), "bad_channel"),
            Triple("root", JsonObject(emptyMap()), "unknown_field"),
            Triple("slots", JsonPrimitive("PRIVATE_TEXT"), "plaintext_field"),
            Triple("slots", JsonObject(raw.getValue("slots").jsonObject - "title"), "bad_item"),
            Triple("slots", JsonObject(raw.getValue("slots").jsonObject + ("desc" to JsonObject(emptyMap()))), "unknown_field"),
        )) assertEquals(ItemVerdict.Rejected(reason), verdict(changed(key, value)))
        assertEquals(ItemVerdict.Rejected("kind_schema_mismatch"), verdict(JsonObject(raw - "kind")))
        val slots = raw.getValue("slots").jsonObject
        for (kind in listOf("expired", "PRIVATE_TEXT")) {
            val invalid = JsonObject(slots + ("title" to JsonObject(mapOf("kind" to JsonPrimitive(kind)))))
            assertEquals(ItemVerdict.Rejected("bad_kind"), verdict(changed("slots", invalid)))
        }
        assertTrue(SkeletonValidator.validate(raw, enabled.copy(acceptedTextKeys = emptyList()), day) is ItemVerdict.Accepted)
        assertEquals(ItemVerdict.Rejected("too_large"), SkeletonValidator.validate(raw, enabled.copy(maxSkeletonBytes = 1), day))
    }

    private val golden = GZIPInputStream(Files.newInputStream(
        Path.of(System.getProperty("census.contractDir"), "conformance", "skeletons.jsonl.gz"),
    )).bufferedReader().use { Json.parseToJsonElement(it.readLine()).jsonObject }
    private val skeleton = golden.getValue("skeleton").jsonObject
    private val today = LocalDate.parse(skeleton.getValue("day").jsonPrimitive.content)
    private val policy = Policy()

    @Test
    fun `golden is accepted with all hashes and measured canonical JSON`() {
        val verdict = SkeletonValidator.validate(skeleton, policy, today)
        assertTrue(verdict is ItemVerdict.Accepted)
        val accepted = verdict as ItemVerdict.Accepted
        assertEquals(golden.getValue("hashes").jsonArray.map { it.jsonPrimitive.content }.toSet(), accepted.tokens.map { it.hash }.toSet())
        assertEquals(cloud.trotter.census.contract.CensusSkeletonSchema.measure(accepted.item).json, accepted.canonicalJson)
        assertEquals(accepted.canonicalJson.toByteArray().size, accepted.bytes)
    }

    @Test
    fun `mutations receive their exact reason codes`() {
        assertRejected("bad_item", JsonArray(emptyList()))
        assertRejected("unknown_schema", item("schemaId", JsonPrimitive("uinode.skeleton.v2")))
        assertRejected("unknown_schema", item("schemaId", JsonPrimitive(1)))
        assertRejected("unknown_schema", JsonObject(skeleton - "schemaId"))
        assertRejected("unknown_field", slot("t" to JsonPrimitive("plain")))
        assertRejected("plaintext_field", node("isClickable", JsonPrimitive("yes")))
        assertRejected("bad_item", node("isChecked", JsonPrimitive(7)))
        assertRejected("too_deep", item("root", nested(65)))
        assertRejected("too_many_nodes", item("root", children(4096)))
        assertRejected("bad_kind", slot("kind" to JsonPrimitive("shout")))
        assertRejected("bad_hash", slot("h" to JsonPrimitive("ABCDEF0123456789")))
        assertRejected("hash_on_withheld_kind", slot("kind" to JsonPrimitive("digits")))
        assertRejected("missing_hash", item("windowTitle", JsonObject(mapOf("kind" to JsonPrimitive("words:2")))))
        assertRejected("bad_id", node("id", JsonPrimitive("12345")))
        assertRejected("bad_class", node("class", JsonPrimitive("not a class")))
        assertRejected("hash_domain_mismatch", item("hashDomain", JsonPrimitive(2)))
        assertRejected("bad_platform", item("platform", JsonPrimitive("Door Dash")))
        assertRejected("fingerprint_mismatch", JsonObject(skeleton - "fingerprint"))
        assertRejected("fingerprint_mismatch", item("fingerprint", JsonPrimitive("ABC")))
        assertRejected("bad_day", item("day", JsonPrimitive("2026-02-31")))
        assertRejected("bad_day", item("day", JsonPrimitive("2026-9-30")))
        assertRejected("stale_day", item("day", JsonPrimitive(today.minusDays(8).toString())))
        assertRejected("stale_day", item("day", JsonPrimitive(today.plusDays(2).toString())))
        assertRejected("bad_version", item("platformAppVersion", JsonPrimitive("x".repeat(65))))
        assertRejected("bad_version", item("appVersion", JsonPrimitive("plain text")))
        val oversized = item("root", children(2000, JsonObject(mapOf("class" to JsonPrimitive("android.widget.TextView")))))
        assertTrue(oversized.toString().toByteArray().size < policy.maxBatchBytes)
        assertRejected("too_large", oversized)
        val fingerprint = skeleton.getValue("fingerprint").jsonPrimitive.content
        val flipped = (if (fingerprint.first() == '0') "1" else "0") + fingerprint.drop(1)
        assertRejected("fingerprint_mismatch", item("fingerprint", JsonPrimitive(flipped)))
    }

    @Test
    fun `plaintext metadata probes receive their exact reason codes`() {
        assertRejected("bad_platform", item("platform", JsonPrimitive("alice_smith_hiv_positive")))
        assertRejected("bad_version", item("platformAppVersion", JsonPrimitive("Alice-Smith-HIV-positive")))
        assertRejected("bad_version", item("appVersion", JsonPrimitive("AliceSmith")))
        assertRejected("bad_version", item("rulesetReleaseTag", JsonPrimitive("Alice-Smith-HIV-positive")))
        assertRejected("unknown_field", node("text", JsonObject(mapOf(
            "aliceSmith" to JsonObject(mapOf("kind" to JsonPrimitive("digits"))),
        ))))
    }

    @Test
    fun `fleet metadata grammars and policy allowlists are enforced`() {
        val versions = mapOf(
            "platformAppVersion" to listOf("8.97.8", "0.0.0", "12345.12345.12345.12345"),
            "appVersion" to listOf("test", "1.2.3", "1.2.3+abcdef0", "1.2.3+abcdef0.dirty", "1.2.3+nogit", "9999.9999.9999+" + "a".repeat(40)),
            "rulesetReleaseTag" to listOf("corpus", "dev", "v1.2.3", "1.2.3-rc1", "12345.12345.12345.12345-abcdefghijkl"),
        )
        for ((key, values) in versions) {
            for (value in values) assertTrue(SkeletonValidator.validate(item(key, JsonPrimitive(value)), policy, today) is ItemVerdict.Accepted)
        }
        val invalidVersions = mapOf(
            "platformAppVersion" to listOf("", "123456", "1.2.3.4.5", "v1.2.3", "1.2.3-beta"),
            "appVersion" to listOf("1.2", "10000.1.1", "1.2.3+42", "1.2.3+ABCDEF0", "1.2.3+nogit.dirty"),
            "rulesetReleaseTag" to listOf("", "123456", "v1.2-RC1", "1-abcdefghijklm", "corpus-extra"),
        )
        for ((key, values) in invalidVersions) {
            for (value in values) assertRejected("bad_version", item(key, JsonPrimitive(value)))
        }
        for (platform in policy.acceptedPlatforms) {
            assertTrue(SkeletonValidator.validate(item("platform", JsonPrimitive(platform)), policy, today) is ItemVerdict.Accepted)
        }
        assertRejected("bad_platform", skeleton, policy.copy(acceptedPlatforms = emptyList()))
        for (key in policy.acceptedTextKeys) {
            val candidate = node("text", JsonObject(mapOf(key to JsonObject(mapOf("kind" to JsonPrimitive("digits"))))))
            val decoded = SkeletonSchema.deserialize(candidate.toString())
            val corrected = JsonObject(candidate + ("fingerprint" to JsonPrimitive(requireNotNull(CensusFingerprint.of(decoded.root)))))
            assertTrue(SkeletonValidator.validate(corrected, policy, today) is ItemVerdict.Accepted)
            assertRejected("unknown_field", corrected, policy.copy(acceptedTextKeys = emptyList()))
        }
    }

    @Test
    fun `hash mutations do not change the structural fingerprint`() {
        val original = SkeletonSchema.json.decodeFromJsonElement(UiSkeletonDto.serializer(), skeleton)
        val root = original.root.copy(text = mapOf("text" to cloud.trotter.census.contract.TextSlot("0123456789abcdef", "words:2")))
        val withHash = original.copy(root = root)
        val changedHash = withHash.copy(root = root.copy(text = mapOf("text" to cloud.trotter.census.contract.TextSlot("fedcba9876543210", "words:2"))))
        assertEquals(original.fingerprint, CensusFingerprint.of(withHash.root))
        assertEquals(original.fingerprint, CensusFingerprint.of(changedHash.root))
        val badHash = Json.parseToJsonElement(SkeletonSchema.serialize(changedHash)).jsonObject
        val badRoot = JsonObject(badHash.getValue("root").jsonObject + ("text" to JsonObject(mapOf(
            "text" to JsonObject(mapOf("kind" to JsonPrimitive("words:2"), "h" to JsonPrimitive("FEDCBA9876543210"))),
        ))))
        assertRejected("bad_hash", JsonObject(badHash + ("root" to badRoot)))
    }

    @Test
    fun `structural checks fail closed before semantic checks`() {
        assertRejected("unknown_field", node("extra", JsonPrimitive("plain")))
        assertRejected("unknown_field", item("extra", JsonPrimitive(1)))
        assertRejected("plaintext_field", node("children", JsonPrimitive("plain")))
        assertRejected("plaintext_field", node("children", JsonArray(listOf(JsonPrimitive("plain")))))
        assertRejected("plaintext_field", node("text", JsonObject(mapOf("text" to JsonPrimitive("plain")))))
        assertRejected("plaintext_field", node("isChecked", JsonArray(listOf(JsonPrimitive("plain")))))
        assertRejected("bad_item", node("isEnabled", JsonPrimitive(1)))
        assertRejected("bad_item", node("children", JsonObject(emptyMap())))
        assertRejected("unknown_field", node("text", JsonObject(mapOf("not a key" to JsonObject(mapOf("kind" to JsonPrimitive("digits")))))))
        assertRejected("bad_id", node("id", JsonPrimitive("name\u0000")))
        assertRejected("bad_class", node("class", JsonPrimitive("Name\uD800")))
        assertRejected("bad_item", item("filterRev", JsonPrimitive(0)))
        assertRejected("unknown_field", JsonObject(slot("t" to JsonPrimitive("plain")) + ("root" to nested(65))))
        assertRejected("too_deep", JsonObject(slot("kind" to JsonPrimitive("shout")) + ("root" to nested(65))))
        assertRejected("too_large", item("fingerprint", JsonPrimitive("0".repeat(64))), policy.copy(maxSkeletonBytes = 1))
    }

    @Test
    fun `depth node and date boundaries are inclusive`() {
        for (root in listOf(nested(64), children(4095))) {
            val candidate = item("root", root)
            val decoded = SkeletonSchema.json.decodeFromJsonElement(UiSkeletonDto.serializer(), candidate)
            val corrected = JsonObject(candidate + ("fingerprint" to JsonPrimitive(requireNotNull(CensusFingerprint.of(decoded.root)))))
            assertTrue(SkeletonValidator.validate(corrected, policy, today) is ItemVerdict.Accepted)
        }
        for (day in listOf(today.minusDays(7), today.plusDays(1))) {
            assertTrue(SkeletonValidator.validate(item("day", JsonPrimitive(day.toString())), policy, today) is ItemVerdict.Accepted)
        }
    }

    private fun assertRejected(reason: String, element: JsonElement, policy: Policy = this.policy) {
        assertEquals(ItemVerdict.Rejected(reason), SkeletonValidator.validate(element, policy, today))
    }

    private fun item(key: String, value: JsonElement): JsonObject = JsonObject(skeleton + (key to value))

    private fun node(key: String, value: JsonElement): JsonObject =
        item("root", JsonObject(skeleton.getValue("root").jsonObject + (key to value)))

    private fun slot(change: Pair<String, JsonElement>): JsonObject = item("windowTitle", JsonObject(
        mapOf("h" to JsonPrimitive("0123456789abcdef"), "kind" to JsonPrimitive("words:2")) + change,
    ))

    private fun nested(depth: Int): JsonObject {
        var root = JsonObject(emptyMap())
        repeat(depth - 1) { root = JsonObject(mapOf("children" to JsonArray(listOf(root)))) }
        return root
    }

    private fun children(count: Int, child: JsonObject = JsonObject(emptyMap())): JsonObject =
        JsonObject(mapOf("children" to JsonArray(List(count) { child })))
}
