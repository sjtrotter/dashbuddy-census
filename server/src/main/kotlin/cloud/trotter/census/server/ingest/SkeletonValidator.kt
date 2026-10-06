package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.ClassNameGrammar
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.ResourceIdGrammar
import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.contract.SkeletonRejectionReason
import cloud.trotter.census.contract.WireStrings
import cloud.trotter.census.server.Policy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

sealed interface ItemVerdict {
    data class Accepted(val item: CensusSkeletonDto, val canonicalJson: String, val bytes: Int, val tokens: List<Token>) : ItemVerdict
    data class Rejected(val reason: String) : ItemVerdict
}

data class Token(val hash: String, val kind: String)

/**
 * Pure admission checks. Structural inspection never recurses, even through incorrectly typed values.
 * The residual free-text channel in an id NAME / class is handled by the M4 operator unblinding design, NOT here.
 */
object SkeletonValidator {
    fun validate(element: JsonElement, policy: Policy, today: LocalDate): ItemVerdict {
        if (element !is JsonObject) return reject("bad_item")
        val schema = element.string("schemaId")
        if (schema == null || schema !in policy.acceptedSchemaIds) return reject("unknown_schema")
        val notification = schema == NotificationSkeletonSchema.SCHEMA_ID
        val kind = element.string("kind")
        if ("kind" in element && SkeletonKind.fromWire(kind ?: "") == null) return reject("unknown_skeleton_kind")
        if ((notification && kind != SkeletonKind.NOTIFICATION.wire) ||
            (!notification && kind != null && kind != SkeletonKind.SCREEN.wire)) return reject("kind_schema_mismatch")
        val structure = inspect(element, notification)
        if (structure.unknownField || structure.textKeys.any { it !in policy.acceptedTextKeys }) return reject("unknown_field")
        if (structure.plaintext) return reject("plaintext_field")
        if (structure.badType) return reject("bad_item")
        if (structure.tooDeep) return reject("too_deep")
        if (structure.nodes.size > 4096) return reject("too_many_nodes")

        if (notification && !Regex(NotificationSkeletonDto.CHANNEL_ID_PATTERN).matches(element.string("channelId") ?: "")) {
            return reject("bad_channel")
        }
        val tokens = mutableListOf<Token>()
        for (slot in structure.slots) {
            val kind = requireNotNull(slot.string("kind"))
            val hash = slot.string("h")
            if (!KindClassifier.isWireKind(kind)) return reject("bad_kind")
            if (hash != null && !CensusHash.isWellFormed(hash)) return reject("bad_hash")
            if (hash != null && !KindClassifier.isHashableKind(kind)) return reject("hash_on_withheld_kind")
            if (hash == null && KindClassifier.isHashableKind(kind)) return reject("missing_hash")
            if (hash != null) tokens += Token(hash, kind)
        }
        for (node in structure.nodes) {
            val id = node.string("id")
            val className = node.string("class")
            if (id != null && (!WireStrings.isWellFormed(id) || !ResourceIdGrammar.isWireId(id))) return reject("bad_id")
            if (className != null && (!WireStrings.isWellFormed(className) || !ClassNameGrammar.isStatic(className))) {
                return reject("bad_class")
            }
            val checked = (node["isChecked"] as? JsonPrimitive)?.intOrNull ?: 0
            if (checked !in 0..2) return reject("bad_item")
        }
        if ((element["hashDomain"] as JsonPrimitive).intOrNull !in policy.acceptedHashDomains) {
            return reject("hash_domain_mismatch")
        }
        val platform = requireNotNull(element.string("platform"))
        if (!platformPattern.matches(platform) || platform !in policy.acceptedPlatforms) return reject("bad_platform")
        val dayString = requireNotNull(element.string("day"))
        if (!dayPattern.matches(dayString)) return reject("bad_day")
        val day = try {
            LocalDate.parse(dayString)
        } catch (_: DateTimeParseException) {
            return reject("bad_day")
        }
        if (day < today.minusDays(7) || day > today.plusDays(1)) return reject("stale_day")
        for ((key, pattern) in versionPatterns) {
            val version = element.string(key) ?: continue
            if (version.length > 64 || !pattern.matches(version)) return reject("bad_version")
        }

        // A missing / null / malformed declared fingerprint is a fingerprint the recompute cannot match — named as
        // such BEFORE the contract's constructor gate would fold it into `bad_item`.
        val declared = element.string("fingerprint")
        if (declared == null || !CensusFingerprint.isWellFormed(declared)) return reject("fingerprint_mismatch")
        val item = try {
            CensusSkeletonSchema.deserialize(element.toString())
        } catch (_: SerializationException) {
            return reject("bad_item")
        } catch (_: IllegalArgumentException) {
            return reject("bad_item")
        }
        val measured = CensusSkeletonSchema.measure(item)
        if (measured.bytes > policy.maxSkeletonBytes) return reject("too_large")
        if (CensusFingerprint.of(item) != item.fingerprint) return reject("fingerprint_mismatch")
        return ItemVerdict.Accepted(item, measured.json, measured.bytes, tokens)
    }

    private fun reject(reason: String): ItemVerdict.Rejected =
        ItemVerdict.Rejected(requireNotNull(SkeletonRejectionReason.fromWire(reason)).wire)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private enum class Shape {
        ITEM, NOTIFICATION, NOTIF_SLOTS, NODE, SLOT, OPTIONAL_SLOT, TEXT_MAP, CHILDREN, STRING, OPTIONAL_STRING, INTEGER, OPTIONAL_INTEGER, BOOLEAN, INVALID,
    }

    private data class Visit(val value: JsonElement, val shape: Shape, val depth: Int)

    private class Structure {
        var unknownField = false
        var plaintext = false
        var badType = false
        var tooDeep = false
        val nodes = mutableListOf<JsonObject>()
        val slots = mutableListOf<JsonObject>()
        val textKeys = mutableListOf<String>()
    }

    private fun inspect(item: JsonObject, notification: Boolean): Structure {
        val result = Structure()
        val pending = ArrayDeque<Visit>()
        pending.addLast(Visit(item, if (notification) Shape.NOTIFICATION else Shape.ITEM, 0))
        while (pending.isNotEmpty()) {
            val (value, shape, depth) = pending.removeLast()
            if (value is JsonPrimitive && value.isString && shape != Shape.STRING && shape != Shape.OPTIONAL_STRING) {
                result.plaintext = true
            }
            val validType = when (shape) {
                Shape.ITEM, Shape.NOTIFICATION, Shape.NOTIF_SLOTS, Shape.NODE, Shape.SLOT, Shape.TEXT_MAP -> value is JsonObject
                Shape.OPTIONAL_SLOT -> value is JsonObject || value == JsonNull
                Shape.CHILDREN -> value is JsonArray
                Shape.STRING -> value is JsonPrimitive && value.isString
                Shape.OPTIONAL_STRING -> value == JsonNull || value is JsonPrimitive && value.isString
                Shape.INTEGER -> value is JsonPrimitive && !value.isString && value.intOrNull != null
                Shape.OPTIONAL_INTEGER -> value == JsonNull || value is JsonPrimitive && !value.isString && value.intOrNull != null
                Shape.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                Shape.INVALID -> false
            }
            if (!validType) result.badType = true
            when (value) {
                is JsonObject -> {
                    val fields = when (shape) {
                        Shape.ITEM -> itemFields
                        Shape.NOTIFICATION -> notificationFields
                        Shape.NOTIF_SLOTS -> NotifTextField.entries.associate { it.wire to Shape.SLOT }
                        Shape.NODE -> nodeFields
                        Shape.SLOT, Shape.OPTIONAL_SLOT -> slotFields
                        else -> null
                    }
                    if (fields != null && value.keys.any { it !in fields }) result.unknownField = true
                    when (shape) {
                        Shape.ITEM -> if (requiredItemKeys.any { it !in value }) result.badType = true
                        Shape.NOTIFICATION -> if (requiredNotificationKeys.any { it !in value }) result.badType = true
                        Shape.NOTIF_SLOTS -> if (value.keys != NotifTextField.entries.map { it.wire }.toSet()) result.badType = true
                        Shape.NODE -> {
                            result.nodes += value
                            if (depth > 64) result.tooDeep = true
                        }
                        Shape.SLOT, Shape.OPTIONAL_SLOT -> {
                            result.slots += value
                            if ("kind" !in value) result.badType = true
                        }
                        Shape.TEXT_MAP -> result.textKeys += value.keys
                        else -> Unit
                    }
                    // Reverse insertion preserves the incoming order with a LIFO stack.
                    for ((key, child) in value.entries.toList().asReversed()) {
                        val childShape = if (shape == Shape.TEXT_MAP) Shape.SLOT else fields?.get(key) ?: Shape.INVALID
                        pending.addLast(Visit(child, childShape, if (childShape == Shape.NODE) depth + 1 else depth))
                    }
                }
                is JsonArray -> for (child in value.asReversed()) {
                    val childShape = if (shape == Shape.CHILDREN) Shape.NODE else Shape.INVALID
                    pending.addLast(Visit(child, childShape, if (childShape == Shape.NODE) depth + 1 else depth))
                }
                is JsonPrimitive -> Unit
            }
        }
        return result
    }

    private val itemFields = mapOf(
        "schemaId" to Shape.STRING, "hashDomain" to Shape.INTEGER, "filterRev" to Shape.INTEGER,
        "fingerprint" to Shape.OPTIONAL_STRING, "platform" to Shape.STRING,
        "platformAppVersion" to Shape.OPTIONAL_STRING, "appVersion" to Shape.OPTIONAL_STRING,
        "rulesetReleaseTag" to Shape.OPTIONAL_STRING, "engineVersion" to Shape.INTEGER,
        "rulesetFormatVersion" to Shape.OPTIONAL_INTEGER, "day" to Shape.STRING,
        "windowTitle" to Shape.OPTIONAL_SLOT, "root" to Shape.NODE,
    )
    private val requiredItemKeys = setOf("schemaId", "hashDomain", "filterRev", "platform", "engineVersion", "day", "root")
    private val notificationFields = (itemFields - setOf("root", "windowTitle")) + mapOf(
        "kind" to Shape.STRING, "channelId" to Shape.STRING, "slots" to Shape.NOTIF_SLOTS,
    )
    private val requiredNotificationKeys = (requiredItemKeys - "root") + setOf("kind", "channelId", "slots")
    private val nodeFields = mapOf(
        "class" to Shape.OPTIONAL_STRING, "id" to Shape.OPTIONAL_STRING, "isClickable" to Shape.BOOLEAN,
        "isEnabled" to Shape.BOOLEAN, "isChecked" to Shape.INTEGER, "text" to Shape.TEXT_MAP, "children" to Shape.CHILDREN,
    )
    private val slotFields = mapOf("h" to Shape.OPTIONAL_STRING, "kind" to Shape.STRING)
    // The app's wire token for a platform it could not resolve is `_unknown` (the conformance golden carries 471 of
    // them), so a leading underscore is part of the grammar; everything else is lowercase ASCII, ≤ 32 chars.
    private val platformPattern = WireGrammars.platform
    private val dayPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val versionPatterns = mapOf(
        "platformAppVersion" to WireGrammars.platformAppVersion,
        "appVersion" to WireGrammars.appVersion,
        "rulesetReleaseTag" to WireGrammars.rulesetReleaseTag,
    )
}
