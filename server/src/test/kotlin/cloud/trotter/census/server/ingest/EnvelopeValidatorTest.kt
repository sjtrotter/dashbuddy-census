package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.SensitiveMarkerScan
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvelopeValidatorTest {
    private val fixture = envelopeFixture()

    @Test
    fun `three node capture is accepted with identity and signature removed`() {
        val accepted = EnvelopeValidator.validate(fixture) as EnvelopeVerdict.Accepted
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
        reject("bad_item", item("windowContext", JsonPrimitive("Dasher")))
        reject("bad_item", item("windowContext", JsonObject(mapOf("windowTitle" to JsonArray(emptyList())))))
        reject("too_large", item("payload", JsonPrimitive("x".repeat(262_144))))
        reject("bad_item", item("payload", JsonArray(List(20_001) { JsonPrimitive("") })))
        var nested: JsonElement = JsonNull
        repeat(64) { nested = JsonArray(listOf(nested)) }
        reject("bad_item", item("payload", nested))
        assertTrue(EnvelopeValidator.validate(item("payload", JsonNull)) is EnvelopeVerdict.Accepted)
        for (platform in listOf("_unknown", "doordash", "uber")) {
            assertTrue(EnvelopeValidator.validate(item("platform", JsonPrimitive(platform))) is EnvelopeVerdict.Accepted)
        }
        reject("unknown_schema", JsonObject(item("extra", JsonPrimitive(1)) + ("schemaId" to JsonPrimitive("other"))))
    }

    @Test
    fun `every payload string and window title pass through contract scan`() {
        // SensitiveMarkerData.KEYWORDS contains DasherDirect; the balance is a synthetic sentinel.
        val text = "DasherDirect balance PRIVATE_BALANCE_SENTINEL"
        val expected = EnvelopeVerdict.Rejected("sensitive_leak", SensitiveMarkerScan.findMarker(text))
        for (payload in listOf(
            JsonPrimitive(text), JsonArray(listOf(JsonPrimitive(text))),
            JsonObject(mapOf("children" to JsonArray(listOf(JsonObject(mapOf("desc" to JsonPrimitive(text))))))),
        )) assertEquals(expected, EnvelopeValidator.validate(item("payload", payload)))
        assertEquals(expected, EnvelopeValidator.validate(item("windowContext", JsonObject(mapOf("windowTitle" to JsonPrimitive(text))))))
        assertEquals(EnvelopeVerdict.Rejected("sensitive_leak", "DasherDirect"), expected)
    }

    private fun item(key: String, value: JsonElement): JsonObject = JsonObject(fixture + (key to value))
    private fun reject(reason: String, item: JsonElement) { assertEquals(EnvelopeVerdict.Rejected(reason), EnvelopeValidator.validate(item)) }
}
