package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.SensitiveMarkerScan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface EnvelopeVerdict {
    data class Accepted(val canonicalJson: String) : EnvelopeVerdict {
        override fun toString(): String = "Accepted(canonicalJson=[redacted])"
    }
    data class Rejected(val reason: String, val marker: String? = null) : EnvelopeVerdict
}

/** Pure checks; only the public contract's marker name leaves a failed scan. */
object EnvelopeValidator {
    fun validate(element: JsonElement): EnvelopeVerdict {
        if (element !is JsonObject) return reject("bad_item")
        if (element.string("schemaId") != "uinode.v1") return reject("unknown_schema")
        val metadata = element["metadata"] as? JsonObject ?: return reject("bad_item")
        val timestamp = element["timestamp"] as? JsonPrimitive
        if (requiredStrings.any { element.string(it) == null } || timestamp == null || timestamp.isString ||
            timestamp.longOrNull == null || "payload" !in element
        ) return reject("bad_item")
        if (element.keys.any { it !in fields } || metadata.keys.any { it !in metadataFields }) return reject("unknown_field")
        if (!uuidPattern.matches(requireNotNull(element.string("captureId")))) return reject("bad_item")
        if (!platformPattern.matches(requireNotNull(element.string("platform")))) return reject("bad_platform")

        val window = element["windowContext"]
        if (window != null && window != JsonNull && window !is JsonObject) return reject("bad_item")
        val title = (window as? JsonObject)?.get("windowTitle")
        if (title != null && title != JsonNull && (title !is JsonPrimitive || !title.isString)) return reject("bad_item")
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        if (title != null) pending.addLast(title to 1)
        pending.addLast(element.getValue("payload") to 1)
        var strings = 0
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            if (depth > 64) return reject("bad_item")
            when (value) {
                is JsonObject -> for (child in value.values.toList().asReversed()) pending.addLast(child to depth + 1)
                is JsonArray -> for (child in value.asReversed()) pending.addLast(child to depth + 1)
                is JsonPrimitive -> if (value.isString) {
                    if (++strings > 20_000) return reject("bad_item")
                    val marker = SensitiveMarkerScan.findMarker(value.content)
                    if (marker != null) return EnvelopeVerdict.Rejected("sensitive_leak", marker)
                }
            }
        }
        if (Json.encodeToString(JsonElement.serializer(), element).toByteArray().size > 262_144) return reject("too_large")
        val pruned = JsonObject(element + ("metadata" to JsonObject(metadata - "deviceFingerprint" - "rulesetSignature")))
        return EnvelopeVerdict.Accepted(Json.encodeToString(JsonObject.serializer(), pruned))
    }

    private fun reject(reason: String): EnvelopeVerdict.Rejected = EnvelopeVerdict.Rejected(reason)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private val requiredStrings = setOf("captureId", "pipelineId", "schemaId", "platform")
    private val fields = requiredStrings + setOf("timestamp", "ruleId", "classificationName", "metadata", "payload", "windowContext")
    private val metadataFields = setOf(
        "engineVersion", "rulesetFormatVersion", "rulesetReleaseTag", "rulesetSignature", "pipelineVersions",
        "stateMachineApiVersion", "appVersion", "deviceFingerprint", "platformAppVersion",
    )
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val platformPattern = Regex("[a-z_][a-z0-9_]{0,31}")
}
