package cloud.trotter.census.server.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireframeRenderTest {
    private fun node(left: Int = 0, top: Int = 0, right: Int = 1080, bottom: Int = 2400, children: List<JsonObject> = emptyList()): JsonObject = buildJsonObject {
        put("bounds", buildJsonObject { put("left", left); put("top", top); put("right", right); put("bottom", bottom) })
        put("children", JsonArray(children))
    }

    private fun render(root: JsonObject): RenderedWireframe = requireNotNull(WireframeRender.render(
        buildJsonObject { put("payload", root); put("metadata", buildJsonObject { put("platformAppVersion", "8.10") }) }.toString(),
        "2026-10-02", "12345678",
    ))

    @Test
    fun `percent math clips to frame and emits parents before children`() {
        val result = render(node(children = listOf(node(108, 600, 648, 1800), node(-108, -240, 1200, 2600), node(1, 1, 2, 2))))
        assertEquals(1080, result.frameWidth)
        assertEquals(2400, result.frameHeight)
        assertFalse(result.frameFallback)
        assertEquals("2026-10-02", result.receivedDay)
        assertEquals("12345678", result.installPrefix)
        assertEquals("8.10", result.platformAppVersion)
        assertEquals(listOf(0, 1, 1, 1), result.boxes.map { it.depth })
        assertEquals(listOf(10.0, 25.0, 50.0, 50.0), result.boxes[1].let { listOf(it.leftPct, it.topPct, it.widthPct, it.heightPct) })
        assertEquals(listOf(0.0, 0.0, 100.0, 100.0), result.boxes[2].let { listOf(it.leftPct, it.topPct, it.widthPct, it.heightPct) })
        assertEquals(0.09, result.boxes[3].leftPct)
        assertEquals(0.04, result.boxes[3].topPct)
        assertEquals(4, result.nodeCount)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `zero root bounds use phone sized fallback and invalid nodes are skipped`() {
        val result = render(node(right = 0, bottom = 0, children = listOf(node(540, 1200), node(1080, 0, 1100, 10), node(0, 0, 0, 10), JsonObject(emptyMap()))))
        assertTrue(result.frameFallback)
        assertEquals(1080, result.frameWidth)
        assertEquals(2400, result.frameHeight)
        assertEquals(5, result.nodeCount)
        assertEquals(4, result.skipped)
        assertEquals(50.0, result.boxes.single().leftPct)
        assertEquals(50.0, result.boxes.single().heightPct)
    }

    @Test
    fun `labels prefer text desc hint then pane and shorten class and id`() {
        val labelled = JsonObject(node() + mapOf("text" to JsonPrimitive(" Text "), "desc" to JsonPrimitive("Desc"),
            "hint" to JsonPrimitive("Hint"), "pane" to JsonPrimitive("Pane"), "class" to JsonPrimitive("android.widget.Button"),
            "id" to JsonPrimitive("com.example:id/go"), "isClickable" to JsonPrimitive(true)))
        val box = render(labelled).boxes.single()
        assertEquals("Text", box.label)
        assertEquals("Button", box.className)
        assertEquals("go", box.viewId)
        assertTrue(box.clickable)
        assertEquals("Desc", render(JsonObject(labelled + ("text" to JsonPrimitive("  ")))).boxes.single().label)
        assertEquals("Hint", render(JsonObject(labelled - "text" - "desc")).boxes.single().label)
        assertEquals("Pane", render(JsonObject(labelled - "text" - "desc" - "hint")).boxes.single().label)
        assertEquals("x".repeat(120), render(JsonObject(labelled + ("text" to JsonPrimitive("x".repeat(121))))).boxes.single().label)
        assertEquals("raw", render(JsonObject(node() + ("id" to JsonPrimitive("raw")))).boxes.single().viewId)
        assertNull(render(node()).boxes.single().label)
        assertNull(render(node()).boxes.single().className)
        assertNull(render(node()).boxes.single().viewId)
    }

    @Test
    fun `class and id are bounded after shortening and empty suffixes become null`() {
        val long = render(JsonObject(node() + mapOf(
            "class" to JsonPrimitive("android.widget.${"x".repeat(200)}"),
            "id" to JsonPrimitive("app:id/${"y".repeat(200)}"),
        ))).boxes.single()
        assertEquals("x".repeat(64), long.className)
        assertEquals("y".repeat(64), long.viewId)
        val empty = render(JsonObject(node() + mapOf(
            "class" to JsonPrimitive("a."), "id" to JsonPrimitive("x:id/"),
        ))).boxes.single()
        assertNull(empty.className)
        assertNull(empty.viewId)
    }

    @Test
    fun `two stacked roots with one hidden draw one box`() {
        val hidden = JsonObject(node() + ("visible" to JsonPrimitive(false)))
        val result = render(node(right = 0, bottom = 0, children = listOf(hidden, node())))
        assertEquals(1, result.boxes.size)
        assertEquals(3, result.nodeCount)
        assertEquals(2, result.skipped) // The hidden node and the zero-sized container.
    }

    @Test
    fun `hidden parents still walk children and only boolean false hides nodes`() {
        val result = render(JsonObject(node(children = listOf(node())) + ("visible" to JsonPrimitive(false))))
        assertEquals(1, result.boxes.single().depth)
        assertEquals(2, result.nodeCount)
        assertEquals(1, result.skipped)
        for (visible in listOf(JsonPrimitive(true), JsonPrimitive("false"), JsonPrimitive(0))) {
            val shown = render(JsonObject(node() + ("visible" to visible)))
            assertEquals(1, shown.boxes.size)
            assertEquals(0, shown.skipped)
        }
    }

    @Test
    fun `implausible frame dimensions and aspect ratios use fallback`() {
        for ((width, height) in listOf(1 to Int.MAX_VALUE, 16_385 to 16_384, 16_384 to 16_385, 1 to 9, 9 to 1)) {
            val result = render(node(right = width, bottom = height))
            assertTrue(result.frameFallback)
            assertEquals(1080, result.frameWidth)
            assertEquals(2400, result.frameHeight)
        }
        for ((width, height) in listOf(1 to 8, 8 to 1, 16_384 to 16_384, 1 to 1)) {
            val result = render(node(right = width, bottom = height))
            assertFalse(result.frameFallback)
            assertEquals(width, result.frameWidth)
            assertEquals(height, result.frameHeight)
        }
    }

    @Test
    fun `box strings mask identifiers before labels are truncated`() {
        val identity = "12345678-1234-4234-8234-123456789abc"
        val label = "x".repeat(85) + identity
        val box = render(JsonObject(node() + mapOf(
            "text" to JsonPrimitive(" $label "),
            "class" to JsonPrimitive("android.widget.$identity"),
            "id" to JsonPrimitive("app:id/${"b".repeat(16)}"),
        ))).boxes.single()
        val boxLabel = requireNotNull(box.label)
        assertEquals("x".repeat(85) + "[redacted]", boxLabel)
        assertFalse(Regex("(?i)[0-9a-f]{8,}").containsMatchIn(boxLabel))
        assertFalse(boxLabel.contains(identity.take(8)))
        assertEquals("[redacted]", box.className)
        assertEquals("[redacted]", box.viewId)
        val hexLabel = render(JsonObject(node() + ("text" to JsonPrimitive("x".repeat(110) + "a".repeat(16))))).boxes.single().label
        assertEquals("x".repeat(110) + "[redacted]", hexLabel)
    }

    @Test
    fun `class and id cuts never split a surrogate pair`() {
        val astral = "\uD801\uDC00"
        val box = render(JsonObject(node() + mapOf(
            "class" to JsonPrimitive("x".repeat(63) + astral),
            "id" to JsonPrimitive("app:id/" + "y".repeat(63) + astral),
        ))).boxes.single()
        assertEquals("x".repeat(63), box.className)
        assertEquals("y".repeat(63), box.viewId)
        val kept = render(JsonObject(node() + ("class" to JsonPrimitive("x".repeat(62) + astral + "tail")))).boxes.single()
        assertEquals("x".repeat(62) + astral, kept.className)
    }

    @Test
    fun `label truncation never splits an emoji surrogate pair`() {
        for ((input, expected) in listOf(
            "x".repeat(119) + "😀" to "x".repeat(119),
            "x".repeat(118) + "😀tail" to "x".repeat(118) + "😀",
        )) {
            val label = requireNotNull(render(JsonObject(node() + ("text" to JsonPrimitive(input)))).boxes.single().label)
            assertEquals(expected, label)
            assertTrue(label.codePoints().toArray().none { it in 0xD800..0xDFFF })
        }
    }

    @Test
    fun `deep raw arrays inside and outside the tree are refused without throwing`() {
        val arrays = "[".repeat(10_000) + "0" + "]".repeat(10_000)
        for (raw in listOf(
            """{"payload":{"children":$arrays}}""",
            """{"payload":{},"metadata":{"extra":$arrays}}""",
        )) assertNull(WireframeRender.render(raw, "2026-10-02", "12345678"))
    }

    @Test
    fun `wide and deep trees stop at visit and depth limits`() {
        val wide = render(node(children = List(2_000) { node() }))
        assertEquals(2_000, wide.nodeCount)
        assertEquals(2_000, wide.boxes.size)
        assertEquals(0, wide.skipped) // Unvisited nodes are not counted as skipped.
        var deep = node()
        repeat(66) { deep = node(children = listOf(deep)) }
        val result = render(deep)
        assertEquals(65, result.nodeCount)
        assertEquals(64, result.boxes.last().depth)
    }

    @Test
    fun `nonzero frame origin and extreme integer bounds stay finite`() {
        val result = render(node(100, 200, 1180, 2600, listOf(node(100, 200, 640, 1400))))
        assertEquals(50.0, result.boxes[1].widthPct)
        assertEquals(0.0, result.boxes[1].leftPct)
        val extreme = render(node(children = listOf(node(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))))
        assertEquals(100.0, extreme.boxes[1].widthPct)
        val quoted = Json.parseToJsonElement("""{"bounds":{"left":"0","top":0,"right":1080,"bottom":2400}}""") as JsonObject
        assertEquals(1, render(quoted).skipped)
    }

    @Test
    fun `malformed json and non object payload return null`() {
        for (raw in listOf("{", "null", "[]", "{}", """{"payload":[]} """, """{"payload":null}""")) {
            assertNull(WireframeRender.render(raw, "2026-10-02", "12345678"))
        }
        assertNull(WireframeRender.render("""{"payload":{}}""", "2026-10-02", "12345678")?.platformAppVersion)
    }

    @Test
    fun `real phone root node renders at least ten boxes`() {
        val raw = requireNotNull(javaClass.getResource("/fixtures/phone-envelope-uber-home.json")).readText()
        val result = requireNotNull(WireframeRender.render(raw, "2026-10-02", "12345678"))
        assertTrue(result.boxes.size >= 10)
        assertEquals(1080, result.frameWidth)
        assertEquals(2400, result.frameHeight)
        assertTrue(result.boxes.any { it.label == "Home" })
    }
}
