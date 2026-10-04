package cloud.trotter.census.server.ops

import cloud.trotter.census.server.ingest.parseBounded
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
    val frameFallback: Boolean = false,
)

/** Pure, bounded projection of the phone's root-node payload. No envelope identifiers enter the projection. */
object WireframeRender {
    fun render(envelopeJson: String, receivedDay: String, installPrefix: String): RenderedWireframe? {
        // Each tree level has a node object and children array; allow the 64-level walk plus a small margin.
        val envelope = parseBounded(envelopeJson.toByteArray(), maxDepth = 144) as? JsonObject ?: return null
        val root = envelope["payload"] as? JsonObject ?: return null
        val bounds = root.bounds()
        val validFrame = bounds != null && bounds.width in 1..16_384L && bounds.height in 1..16_384L &&
            minOf(bounds.width, bounds.height) * 8 >= maxOf(bounds.width, bounds.height)
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
            val hidden = (node?.get("visible") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } == true
            val b = node?.bounds()
            val left = maxOf(b?.left ?: 0, frame.left)
            val top = maxOf(b?.top ?: 0, frame.top)
            val right = minOf(b?.right ?: 0, frame.right)
            val bottom = minOf(b?.bottom ?: 0, frame.bottom)
            if (node != null && !hidden && b != null && b.width > 0 && b.height > 0 && right > left && bottom > top) {
                boxes += WireBox(
                    percent(left - frame.left, frame.width), percent(top - frame.top, frame.height),
                    percent(right - left, frame.width), percent(bottom - top, frame.height), depth,
                    listOf("text", "desc", "hint", "pane").firstNotNullOfOrNull { key ->
                        node.string(key)?.trim()?.takeIf { it.isNotEmpty() }
                    }?.let { safe(it) }?.let { cut(it, 120) },
                    node.string("class")?.let { safe(it) }?.substringAfterLast('.')?.let { cut(it, 64) }?.takeIf { it.isNotEmpty() },
                    node.string("id")?.let { safe(it) }?.substringAfter(":id/")?.let { cut(it, 64) }?.takeIf { it.isNotEmpty() },
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
            frame.width.toInt(), frame.height.toInt(), boxes, visited, skipped, frameFallback = !validFrame,
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
    /** Cut on a code-point boundary: never leave a lone high surrogate at the end (titles and the text list render it). */
    private fun cut(s: String, max: Int): String =
        if (s.length <= max) s else s.take(if (Character.isHighSurrogate(s[max - 1])) max - 1 else max)

    private fun percent(value: Long, extent: Long): Double = round(value.toDouble() / extent * 10_000) / 100
}
