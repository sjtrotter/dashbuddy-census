package cloud.trotter.census.server.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.round

data class WireBox(
    val leftPct: Double, val topPct: Double, val widthPct: Double, val heightPct: Double, val depth: Int,
    val label: String?, val className: String?, val viewId: String?, val clickable: Boolean,
)

data class RenderedWireframe(
    val receivedDay: String, val installPrefix: String, val platformAppVersion: String?,
    val frameWidth: Int, val frameHeight: Int, val boxes: List<WireBox>, val nodeCount: Int, val skipped: Int,
)

/** Pure, bounded projection of the phone's root-node payload. No envelope identifiers enter the projection. */
object WireframeRender {
    fun render(envelopeJson: String, receivedDay: String, installPrefix: String): RenderedWireframe? {
        val envelope = try {
            Json.parseToJsonElement(envelopeJson) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val root = envelope["payload"] as? JsonObject ?: return null
        val bounds = root.bounds()
        val validFrame = bounds != null && bounds.width in 1..Int.MAX_VALUE.toLong() && bounds.height in 1..Int.MAX_VALUE.toLong()
        val frame = if (validFrame) requireNotNull(bounds) else Bounds(0, 0, 1080, 2400)
        val boxes = mutableListOf<WireBox>()
        // Iterator stack preserves pre-order without queuing arbitrarily wide child arrays.
        val pending = ArrayDeque<Pair<Iterator<JsonElement>, Int>>()
        pending.addLast(listOf<JsonElement>(root).iterator() to 0)
        var visited = 0
        var skipped = 0
        while (pending.isNotEmpty() && visited < 2_000) {
            val (siblings, depth) = pending.last()
            if (!siblings.hasNext()) {
                pending.removeLast()
                continue
            }
            val node = siblings.next() as? JsonObject
            visited++
            val b = node?.bounds()
            val left = maxOf(b?.left ?: 0, frame.left)
            val top = maxOf(b?.top ?: 0, frame.top)
            val right = minOf(b?.right ?: 0, frame.right)
            val bottom = minOf(b?.bottom ?: 0, frame.bottom)
            if (node != null && b != null && b.width > 0 && b.height > 0 && right > left && bottom > top) {
                boxes += WireBox(
                    percent(left - frame.left, frame.width), percent(top - frame.top, frame.height),
                    percent(right - left, frame.width), percent(bottom - top, frame.height), depth,
                    listOf("text", "desc", "hint", "pane").firstNotNullOfOrNull { key ->
                        node.string(key)?.trim()?.takeIf { it.isNotEmpty() }
                    }?.take(120),
                    node.string("class")?.substringAfterLast('.'), node.string("id")?.substringAfter(":id/"),
                    (node["isClickable"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true,
                )
            } else {
                skipped++
            }
            // Unvisited descendants beyond either limit are not included in nodeCount or skipped.
            if (depth < 64) (node?.get("children") as? JsonArray)?.let {
                pending.addLast(it.iterator() to depth + 1)
            }
        }
        return RenderedWireframe(
            receivedDay, installPrefix, (envelope["metadata"] as? JsonObject)?.string("platformAppVersion"),
            frame.width.toInt(), frame.height.toInt(), boxes, visited, skipped,
        )
    }

    private data class Bounds(val left: Long, val top: Long, val right: Long, val bottom: Long) {
        val width: Long get() = right - left
        val height: Long get() = bottom - top
    }

    private fun JsonObject.bounds(): Bounds? {
        val bounds = this["bounds"] as? JsonObject ?: return null
        fun integer(key: String): Long? = (bounds[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.toLong()
        return Bounds(integer("left") ?: return null, integer("top") ?: return null,
            integer("right") ?: return null, integer("bottom") ?: return null)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun percent(value: Long, extent: Long): Double = round(value.toDouble() / extent * 10_000) / 100
}
