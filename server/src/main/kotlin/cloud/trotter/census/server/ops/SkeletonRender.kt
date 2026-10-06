package cloud.trotter.census.server.ops

import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.ingest.parseBounded
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RenderedSkeleton(
    @SerialName("class") val className: String? = null,
    val id: String? = null,
    val kinds: List<String> = emptyList(),
    val children: List<RenderedSkeleton> = emptyList(),
)

@Serializable
data class RenderedNotificationSkeleton(
    val channelId: String?,
    val channelWithheld: Boolean,
    val slots: Map<NotifTextField, String>,
)

/** #1175: an allowlisted projection, never a serialization of the stored sample or its text hashes. */
object SkeletonRender {
    fun renderNotification(sample: String, unblinded: Boolean): RenderedNotificationSkeleton {
        val value = parseBounded(sample.toByteArray()) as? JsonObject ?: error("Invalid stored notification")
        val channel = (value["channelId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        check(channel != null && Regex(NotificationSkeletonDto.CHANNEL_ID_PATTERN).matches(channel)) { "Invalid stored notification" }
        val slots = value["slots"] as? JsonObject ?: error("Invalid stored notification")
        check(slots.keys == NotifTextField.entries.map { it.wire }.toSet()) { "Invalid stored notification" }
        val kinds = NotifTextField.entries.associateWith { field ->
            val kind = ((slots[field.wire] as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content
            // Expiry is a storage transition, not an inbound kind; never recompute historical identity here.
            check(kind != null && (kind == "expired" || KindClassifier.isWireKind(kind))) { "Invalid stored notification" }
            kind
        }
        return RenderedNotificationSkeleton(channel.takeIf { unblinded }, !unblinded, kinds)
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
        val kinds = (value["text"] as? JsonObject)?.values.orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { kind -> kind.isString }?.content
        }
        val children = (value["children"] as? JsonArray).orEmpty().map {
            node(it as? JsonObject ?: error("Invalid stored skeleton"), unblinded, depth + 1)
        }
        return RenderedSkeleton(label("class"), label("id"), kinds, children)
    }
}
