package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.envelopeFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvelopeValidatorTest {
    private val policy = Policy()
    private val fixture = envelopeFixture()

    @Test
    fun `three node capture is accepted with identity and signature removed`() {
        val accepted = EnvelopeValidator.validate(fixture, policy) as EnvelopeVerdict.Accepted
        val stored = Json.parseToJsonElement(accepted.canonicalJson).jsonObject
        assertFalse("deviceFingerprint" in stored.getValue("metadata").jsonObject)
        assertFalse("rulesetSignature" in stored.getValue("metadata").jsonObject)
        assertEquals(fixture.getValue("payload"), stored.getValue("payload"))
        assertEquals(fixture - "metadata", stored - "metadata")
        assertFalse(accepted.toString().contains("Looking for offers"))
    }

    @Test
    fun `mutations receive exact reasons and first failure wins`() {
        reject("bad_item", JsonArray(emptyList()))
        reject("unknown_schema", item("schemaId", JsonPrimitive("uinode.v2")))
        reject("unknown_schema", JsonObject(fixture - "schemaId"))
        for (field in listOf("captureId", "pipelineId", "platform", "timestamp", "metadata", "payload")) {
            reject("bad_item", JsonObject(fixture - field))
        }
        reject("bad_item", item("timestamp", JsonPrimitive("123")))
        reject("bad_item", item("timestamp", JsonPrimitive(1.5)))
        reject("bad_item", item("captureId", JsonPrimitive("12345678-1234-4234-8234-123456789ABC")))
        reject("bad_item", item("captureId", JsonPrimitive("1-2-3-4-5")))
        reject("unknown_field", item("extra", JsonPrimitive(1)))
        reject("unknown_field", item("metadata", JsonObject(fixture.getValue("metadata").jsonObject + ("extra" to JsonPrimitive(1)))))
        reject("bad_platform", item("platform", JsonPrimitive("Door Dash")))
        reject("bad_platform", item("platform", JsonPrimitive("private_customer_jane")))
        assertEquals(EnvelopeVerdict.Rejected("bad_platform"), EnvelopeValidator.validate(fixture, policy.copy(acceptedPlatforms = listOf("uber"))))
        reject("bad_item", item("windowContext", JsonPrimitive("Dasher")))
        reject("bad_item", item("windowContext", JsonObject(mapOf("windowTitle" to JsonArray(emptyList())))))
        reject("too_large", item("payload", JsonPrimitive("x".repeat(262_144))))
        reject("bad_item", item("payload", JsonArray(List(20_001) { JsonPrimitive("") })))
        var nested: JsonElement = JsonNull
        repeat(64) { nested = JsonArray(listOf(nested)) }
        reject("bad_item", item("payload", nested))
        assertTrue(EnvelopeValidator.validate(item("payload", JsonNull), policy) is EnvelopeVerdict.Accepted)
        for (platform in listOf("_unknown", "doordash", "uber")) {
            assertTrue(EnvelopeValidator.validate(item("platform", JsonPrimitive(platform)), policy) is EnvelopeVerdict.Accepted)
        }
        reject("unknown_schema", JsonObject(item("extra", JsonPrimitive(1)) + ("schemaId" to JsonPrimitive("other"))))
    }

    @Test
    fun `retained tokens metadata and window context are typed and bounded`() {
        for (value in listOf("", "Screen", "screen name", "s".repeat(65))) reject("bad_item", item("pipelineId", JsonPrimitive(value)))
        for (value in listOf("a", "A.b", "a.b.c.d.e.f")) reject("bad_rule_id", item("ruleId", JsonPrimitive(value)))
        for (value in listOf("", "1NAME", "NAME WITH SPACE", "a".repeat(65))) reject("bad_item", item("classificationName", JsonPrimitive(value)))
        for (key in listOf("ruleId", "classificationName")) {
            reject("bad_item", item(key, JsonPrimitive(1)))
            assertTrue(EnvelopeValidator.validate(item(key, JsonNull), policy) is EnvelopeVerdict.Accepted)
        }
        for (key in listOf("engineVersion", "rulesetFormatVersion")) {
            for (value in listOf(JsonPrimitive("1"), JsonPrimitive(true), JsonPrimitive(1.5), JsonPrimitive(2_147_483_648L))) {
                reject("bad_item", metadata(key, value))
            }
        }
        reject("bad_item", metadata("engineVersion", JsonNull))
        assertTrue(EnvelopeValidator.validate(metadata("rulesetFormatVersion", JsonNull), policy) is EnvelopeVerdict.Accepted)
        for (key in listOf("rulesetReleaseTag", "appVersion", "platformAppVersion", "stateMachineApiVersion")) {
            reject("bad_version", metadata(key, JsonPrimitive("PRIVATE_CUSTOMER_JANE")))
            for (value in listOf(JsonNull, JsonPrimitive(1), JsonArray(emptyList()))) reject("bad_item", metadata(key, value))
        }
        reject("bad_version", metadata("stateMachineApiVersion", JsonPrimitive("123456")))
        reject("bad_version", metadata("stateMachineApiVersion", JsonPrimitive("1.2.3.4.5")))
        reject("bad_item", metadata("pipelineVersions", JsonArray(emptyList())))
        reject("bad_item", metadata("pipelineVersions", JsonObject((1..33).associate { "p$it" to JsonPrimitive(1) })))
        assertTrue(EnvelopeValidator.validate(metadata("pipelineVersions", JsonObject((1..32).associate { "p$it" to JsonPrimitive(1) })), policy) is EnvelopeVerdict.Accepted)
        for (key in listOf("Screen", "Bad.Key", "p".repeat(65))) {
            reject("bad_item", metadata("pipelineVersions", JsonObject(mapOf(key to JsonPrimitive(1)))))
        }
        reject("bad_item", metadata("pipelineVersions", JsonObject(mapOf("screen" to JsonPrimitive("1")))))
        reject("unknown_field", window("extra", JsonPrimitive(1)))
        for (key in listOf("windowId", "windowType", "windowLayer", "totalWindowCount")) {
            for (value in listOf(JsonPrimitive("1"), JsonPrimitive(true), JsonNull)) reject("bad_item", window(key, value))
        }
        for (key in listOf("isActive", "isFocused")) {
            for (value in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) reject("bad_item", window(key, value))
        }
        assertTrue(EnvelopeValidator.validate(window("windowTitle", JsonNull), policy) is EnvelopeVerdict.Accepted)
        assertTrue(EnvelopeValidator.validate(item("windowContext", JsonNull), policy) is EnvelopeVerdict.Accepted)
    }

    @Test
    fun `pipeline IDs permit dotted keys and reject non integer versions`() {
        for (key in listOf("screen.name", "accessibility.window", "p".repeat(17), "p".repeat(64))) {
            assertTrue(EnvelopeValidator.validate(metadata("pipelineVersions", JsonObject(mapOf(key to JsonPrimitive(1)))), policy) is EnvelopeVerdict.Accepted)
        }
        for (value in listOf(JsonPrimitive(1.5), JsonPrimitive(true), JsonNull, JsonPrimitive(2_147_483_648L))) {
            reject("bad_item", metadata("pipelineVersions", JsonObject(mapOf("accessibility.window" to value))))
        }
    }

    @Test
    fun `fingerprint is optional nullable and strictly lowercase sha256`() {
        assertNull((EnvelopeValidator.validate(fixture, policy) as EnvelopeVerdict.Accepted).fingerprint)
        assertNull((EnvelopeValidator.validate(item("fingerprint", JsonNull), policy) as EnvelopeVerdict.Accepted).fingerprint)
        val fingerprint = "abcdef0123456789".repeat(4)
        val accepted = EnvelopeValidator.validate(item("fingerprint", JsonPrimitive(fingerprint)), policy) as EnvelopeVerdict.Accepted
        assertEquals(fingerprint, accepted.fingerprint)
        assertFalse(accepted.toString().contains(fingerprint))
        for (value in listOf(JsonPrimitive("a".repeat(63)), JsonPrimitive(fingerprint.uppercase()), JsonPrimitive(123), JsonArray(emptyList()))) {
            reject("bad_item", item("fingerprint", value))
        }
    }

    @Test
    fun `real phone envelope validates without retaining device fingerprint`() {
        val raw = requireNotNull(javaClass.getResource("/fixtures/phone-envelope-uber-home.json")).readText()
        val verdict = EnvelopeValidator.validate(Json.parseToJsonElement(raw), policy.copy(acceptedPlatforms = listOf("uber")))
        assertTrue(verdict is EnvelopeVerdict.Accepted)
        assertFalse((verdict as EnvelopeVerdict.Accepted).canonicalJson.contains("deviceFingerprint"))
        val metadata = fixture.getValue("metadata").jsonObject
        for (key in listOf("rulesetReleaseTag", "platformAppVersion")) {
            assertTrue(EnvelopeValidator.validate(item("metadata", JsonObject(metadata - key)), policy) is EnvelopeVerdict.Accepted)
        }
        for (key in listOf("appVersion", "stateMachineApiVersion")) {
            reject("bad_item", item("metadata", JsonObject(metadata - key)))
        }
    }

    @Test
    fun `whole element keys and values pass through contract scan without truncation`() {
        // SensitiveMarkerData.KEYWORDS contains DasherDirect; the balance is a synthetic sentinel.
        val text = "DasherDirect balance PRIVATE_BALANCE_SENTINEL"
        val expected = EnvelopeVerdict.Rejected("sensitive_leak", SensitiveMarkerScan.findMarker(text))
        for (payload in listOf(
            JsonPrimitive(text), JsonArray(listOf(JsonPrimitive(text))),
            JsonObject(mapOf(text to JsonNull)),
            JsonObject(mapOf("children" to JsonArray(listOf(JsonObject(mapOf("desc" to JsonPrimitive(text))))))),
        )) assertEquals(expected, EnvelopeValidator.validate(item("payload", payload), policy))
        assertEquals(expected, EnvelopeValidator.validate(item("windowContext", JsonObject(mapOf("windowTitle" to JsonPrimitive(text)))), policy))
        assertEquals(expected, EnvelopeValidator.validate(item("classificationName", JsonPrimitive("DasherDirect")), policy))
        for (key in listOf("deviceFingerprint", "rulesetSignature")) {
            assertEquals(expected, EnvelopeValidator.validate(metadata(key, JsonPrimitive(text)), policy))
        }
        reject("bad_item", item("payload", JsonArray(List(19_999) { JsonPrimitive("") })))
        reject("bad_item", item("payload", JsonObject((1..10_001).associate { "k$it" to JsonPrimitive("") })))
        var nested: JsonElement = JsonNull
        repeat(64) { nested = JsonArray(listOf(nested)) }
        reject("bad_item", metadata("deviceFingerprint", nested))
        assertEquals(EnvelopeVerdict.Rejected("sensitive_leak", "DasherDirect"), expected)
    }

    private fun item(key: String, value: JsonElement): JsonObject = JsonObject(fixture + (key to value))
    private fun metadata(key: String, value: JsonElement): JsonObject = item("metadata", JsonObject(fixture.getValue("metadata").jsonObject + (key to value)))
    private fun window(key: String, value: JsonElement): JsonObject = item("windowContext", JsonObject(fixture.getValue("windowContext").jsonObject + (key to value)))
    private fun reject(reason: String, item: JsonElement) { assertEquals(EnvelopeVerdict.Rejected(reason), EnvelopeValidator.validate(item, policy)) }
}
