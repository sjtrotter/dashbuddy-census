package cloud.trotter.census.server.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
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
