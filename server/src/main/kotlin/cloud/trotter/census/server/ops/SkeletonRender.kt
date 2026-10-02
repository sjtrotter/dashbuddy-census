package cloud.trotter.census.server.ops

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

/** #1175: an allowlisted projection, never a serialization of the stored sample or its text hashes. */
object SkeletonRender {
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
