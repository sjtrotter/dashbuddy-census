package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.census.server.Policy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

sealed interface EnvelopeVerdict {
    data class Accepted(val canonicalJson: String, val platform: String, val fingerprint: String? = null) : EnvelopeVerdict {
        override fun toString(): String = "Accepted(canonicalJson=[redacted])"
    }
    data class Rejected(val reason: String, val marker: String? = null) : EnvelopeVerdict
}

/** Pure checks; only the public contract's marker name leaves a failed scan. */
object EnvelopeValidator {
    fun validate(element: JsonElement, policy: Policy): EnvelopeVerdict {
        if (element !is JsonObject) return reject("bad_item")
        if (element.string("schemaId") != "uinode.v1") return reject("unknown_schema")
        val metadata = element["metadata"] as? JsonObject ?: return reject("bad_item")
        val timestamp = element["timestamp"] as? JsonPrimitive
        if (requiredStrings.any { element.string(it) == null } || timestamp == null || timestamp.isString ||
            timestamp.longOrNull == null || "payload" !in element
        ) return reject("bad_item")
        if (element.keys.any { it !in fields } || metadata.keys.any { it !in metadataFields }) return reject("unknown_field")
        if (!uuidPattern.matches(requireNotNull(element.string("captureId")))) return reject("bad_item")
        if (element.string("platform") !in policy.acceptedPlatforms) return reject("bad_platform")
        if (!WireGrammars.pipelineId.matches(requireNotNull(element.string("pipelineId")))) return reject("bad_item")
        for ((key, grammar) in nullableTokens) {
            val value = element[key]
            if (value == null || value == JsonNull) continue
            val token = element.string(key) ?: return reject("bad_item")
            if (!grammar.matches(token)) return reject(if (key == "ruleId") "bad_rule_id" else "bad_item")
        }
        if (!metadata["engineVersion"].isInteger()) return reject("bad_item")
        val formatVersion = metadata["rulesetFormatVersion"]
        if (formatVersion != null && formatVersion != JsonNull && !formatVersion.isInteger()) return reject("bad_item")
        for ((key, grammar) in metadataVersions) {
            // Real phone captures may omit these version hints; supplied values remain strictly typed.
            if (key in optionalMetadataVersions && key !in metadata) continue
            val version = metadata.string(key) ?: return reject("bad_item")
            if (!grammar.matches(version)) return reject("bad_version")
        }
        val pipelines = metadata["pipelineVersions"] as? JsonObject ?: return reject("bad_item")
        if (pipelines.size > 32 || pipelines.any { (key, value) -> !WireGrammars.pipelineId.matches(key) || !value.isInteger() }) {
            return reject("bad_item")
        }

        val window = element["windowContext"]
        if (window != null && window != JsonNull && window !is JsonObject) return reject("bad_item")
        if (window is JsonObject) {
            if (window.keys.any { it !in windowFields }) return reject("unknown_field")
            for ((key, value) in window) {
                if (key in windowIntegers && !value.isInteger()) return reject("bad_item")
                if (key in windowBooleans && (value !is JsonPrimitive || value.isString || value.booleanOrNull == null)) {
                    return reject("bad_item")
                }
            }
        }
        val title = (window as? JsonObject)?.get("windowTitle")
        if (title != null && title != JsonNull && (title !is JsonPrimitive || !title.isString)) return reject("bad_item")
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.addLast(element to 0)
        var strings = 0
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            if (depth > 64) return reject("bad_item")
            when (value) {
                is JsonObject -> for ((key, child) in value.entries.toList().asReversed()) {
                    pending.addLast(child to depth + 1)
                    pending.addLast(JsonPrimitive(key) to depth + 1)
                }
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
        return EnvelopeVerdict.Accepted(Json.encodeToString(JsonObject.serializer(), pruned),
            requireNotNull(element.string("platform")), element.string("fingerprint"))
    }

    private fun reject(reason: String): EnvelopeVerdict.Rejected = EnvelopeVerdict.Rejected(reason)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.isInteger(): Boolean = this is JsonPrimitive && !isString && intOrNull != null
    private val requiredStrings = setOf("captureId", "pipelineId", "schemaId", "platform")
    private val fields = requiredStrings + setOf("timestamp", "ruleId", "classificationName", "metadata", "payload", "windowContext", "fingerprint")
    private val metadataFields = setOf(
        "engineVersion", "rulesetFormatVersion", "rulesetReleaseTag", "rulesetSignature", "pipelineVersions",
        "stateMachineApiVersion", "appVersion", "deviceFingerprint", "platformAppVersion",
    )
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val nullableTokens = mapOf("ruleId" to WireGrammars.ruleId, "classificationName" to WireGrammars.identifier, "fingerprint" to Regex("[0-9a-f]{64}"))
    private val optionalMetadataVersions = setOf("rulesetReleaseTag", "platformAppVersion")
    private val metadataVersions = mapOf(
        "rulesetReleaseTag" to WireGrammars.rulesetReleaseTag, "appVersion" to WireGrammars.appVersion,
        "platformAppVersion" to WireGrammars.platformAppVersion, "stateMachineApiVersion" to WireGrammars.stateMachineApiVersion,
    )
    private val windowIntegers = setOf("windowId", "windowType", "windowLayer", "totalWindowCount")
    private val windowBooleans = setOf("isActive", "isFocused")
    private val windowFields = windowIntegers + windowBooleans + "windowTitle"
}
