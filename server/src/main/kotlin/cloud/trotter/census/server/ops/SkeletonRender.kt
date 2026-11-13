package cloud.trotter.census.server.ops

import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.ingest.parseBounded
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class RenderedSkeleton(
    @SerialName("class") val className: String? = null,
    val id: String? = null,
    /** One entry per text slot: its kind, or `shape:<template>` for a shape slot (#78). */
    val kinds: List<String> = emptyList(),
    val children: List<RenderedSkeleton> = emptyList(),
    /** #78: the node's flags. A v1 sample has only [clickable]; a v2 one adds [clickAction] and [hidden]. */
    val clickable: Boolean = false,
    val clickAction: Boolean = false,
    val hidden: Boolean = false,
)

@Serializable
data class RenderedNotificationSkeleton(
    val channelId: String?,
    val channelWithheld: Boolean,
    val slots: Map<NotifTextField, String>,
    /** #78: a v2 notification's action labels, in order, each shown as a slot kind (never a hash or a label). */
    val actions: List<String> = emptyList(),
)

/** #1175: an allowlisted projection, never a serialization of the stored sample or its text hashes. */
object SkeletonRender {
    fun renderNotification(sample: String, unblinded: Boolean): RenderedNotificationSkeleton {
        val value = parseBounded(sample.toByteArray()) as? JsonObject ?: error("Invalid stored notification")
        val channel = (value["channelId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        check(channel != null && Regex(NotificationSkeletonDto.CHANNEL_ID_PATTERN).matches(channel)) { "Invalid stored notification" }
        val slots = value["slots"] as? JsonObject ?: error("Invalid stored notification")
        check(slots.keys == NotifTextField.entries.map { it.wire }.toSet()) { "Invalid stored notification" }
        fun kind(slot: JsonElement?): String {
            val kind = slotKind(slot)
            // Expiry is a storage transition, not an inbound kind; never recompute historical identity here.
            check(kind != null && (kind == "expired" || KindClassifier.isWireKind(kind))) { "Invalid stored notification" }
            return requireNotNull(slotLabel(slot))
        }
        val kinds = NotifTextField.entries.associateWith { field -> kind(slots[field.wire]) }
        val actions = when (val stored = value["actions"]) {
            null -> emptyList()
            is JsonArray -> stored.map { kind(it) }
            else -> error("Invalid stored notification")
        }
        check(actions.size <= NotificationSkeletonDto.MAX_ACTIONS) { "Invalid stored notification" }
        return RenderedNotificationSkeleton(channel.takeIf { unblinded }, !unblinded, kinds, actions)
    }

    private const val SHAPE_PREFIX = "shape:"

    private fun slotKind(slot: JsonElement?): String? =
        ((slot as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * A stored slot's display (#78): its kind, or `shape:<template>` when the slot is a shape of the closed list. A
     * shape whose stored template is not on the list reads plain `shape` (fail closed: the stored text is never shown).
     */
    private fun slotLabel(slot: JsonElement?): String? {
        val kind = slotKind(slot) ?: return null
        if (kind != KindClassifier.SHAPE) return kind
        val template = ((slot as JsonObject)["shape"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { ShapeTemplate.isWire(it) }
        return if (template == null) kind else SHAPE_PREFIX + template
    }

    fun render(sample: String, unblinded: Boolean): RenderedSkeleton {
        val root = (parseBounded(sample.toByteArray()) as? JsonObject)?.get("root") as? JsonObject
            ?: error("Invalid stored skeleton")
        return node(root, unblinded, 0)
    }

    private fun node(value: JsonObject, unblinded: Boolean, depth: Int): RenderedSkeleton {
        check(depth <= 64) { "Invalid stored skeleton" }
        fun label(key: String): String? = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
            if (unblinded) it else "~" + hashSecret(it).take(8)
        }
        val kinds = (value["text"] as? JsonObject)?.values.orEmpty().mapNotNull { slotLabel(it) }
        fun flag(key: String): Boolean? = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val children = (value["children"] as? JsonArray).orEmpty().map {
            node(it as? JsonObject ?: error("Invalid stored skeleton"), unblinded, depth + 1)
        }
        return RenderedSkeleton(
            label("class"), label("id"), kinds, children,
            clickable = flag("isClickable") == true, clickAction = flag("hasClickAction") == true,
            hidden = flag("isVisibleToUser") == false,
        )
    }
}
