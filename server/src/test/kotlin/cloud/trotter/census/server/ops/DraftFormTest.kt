package cloud.trotter.census.server.ops

import cloud.trotter.census.contract.authoring.*
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DraftFormTest {
    private fun nodes(count: Int = 5): List<WalkedNode> = EnvelopeWalk.walk(buildJsonObject {
        put("class", "Frame")
        put("children", JsonArray(List(count) { i -> buildJsonObject {
            put("class", "TextView"); put("id", "app:id/item_$i"); put("text", "Dropoff: Alex")
            put("bounds", buildJsonObject { put("left", 0); put("top", 0); put("right", 100); put("bottom", 100) })
        } }))
    }).drop(1)
    private val rows = nodes().mapIndexed { i, node -> DraftNode(i + 1, node) }
    private val codec = DraftForm(rows)
    private val selections = Selections("task:dropoff:navigation", "task", "dropoff", 501,
        modeHint = "online", anchors = listOf(PathRef(rows[0].node.path)),
        fields = listOf(
            FieldAssignment(PathRef(rows[1].node.path), "deadlineText", null, "Dropoff: "),
            FieldAssignment(PathRef(rows[1].node.path), "deadlineMillis", null, "Dropoff: "),
            FieldAssignment(PathRef(rows[2].node.path), "storeName", emptyList()),
        ), binds = listOf(BindAssignment(PathRef(rows[3].node.path), "acceptButton")),
        redacts = listOf(PathRef(rows[4].node.path)), constants = listOf(Constant("arrivalConfirmed", JsonPrimitive(true))),
        comment = "Check the negative corpus")

    private fun Parameters.changed(vararg entries: Pair<String, String>): Parameters = ParametersBuilder().apply {
        this@changed.entries().forEach { (k, v) -> if (entries.none { it.first == k }) appendAll(k, v) }
        entries.forEach { (k, v) -> append(k, v) }
    }.build()
    private fun base() = codec.encode(selections).changed("envelopeId" to "7", "notes" to "Review")

    @Test
    fun `round trip preserves paths two fields prefix transforms constants and optional controls`() {
        val result = codec.decode(base()) as DraftDecode.Ok
        assertEquals(selections, result.value.selections)
        assertEquals("Review", result.value.notes)
        assertEquals(7L, result.value.envelopeId)
        val single = selections.copy(fields = listOf(FieldAssignment(PathRef(rows[1].node.path), "storeName", listOf("trim"))))
        assertEquals(single, (codec.decode(codec.encode(single).changed("envelopeId" to "7")) as DraftDecode.Ok).value.selections)
    }

    @Test
    fun `empty and missing notes do not encode an update`() {
        for (params in listOf(base().changed("notes" to ""), codec.encode(selections).changed("envelopeId" to "7"))) {
            val input = (codec.decode(params) as DraftDecode.Ok).value
            assertNull(input.notes)
            assertFalse("notes" in DraftForm.selectionsJson(input))
        }
    }

    @Test
    fun `click action alone makes an otherwise unlabelled node selectable`() {
        val envelope = buildJsonObject {
            put("payload", buildJsonObject {
                put("class", "Frame")
                put("bounds", buildJsonObject { put("left", 0); put("top", 0); put("right", 100); put("bottom", 100) })
                put("children", JsonArray(listOf(buildJsonObject {
                    put("class", "Button"); put("isClickable", false); put("clickAction", true)
                    put("bounds", buildJsonObject { put("left", 0); put("top", 0); put("right", 50); put("bottom", 50) })
                })))
            })
        }
        val walked = EnvelopeWalk.walk(envelope.getValue("payload").jsonObject)
        val frame = requireNotNull(WireframeRender.render(envelope, walked, "2026-10-02", "12345678"))
        val row = DraftForm.rows(walked, frame).single()
        assertEquals(2, row.number)
        assertEquals(listOf(0), row.node.path)
        assertFalse(row.node.clickable)
        assertTrue(row.node.takesClick)
        assertTrue(frame.boxes.last().clickable)
    }

    @Test
    fun `rejects each bounded scalar vocabulary grammar reference and typed constant`() {
        val invalid = listOf(
            "screenClass" to "unknown", "shape" to "invented", "intent" to "Bad!", "intent" to "x".repeat(49),
            "priority" to "0", "priority" to "999", "priority" to "x", "modeHint" to "none", "offerSurface" to "none",
            "comment" to "x".repeat(501), "notes" to "x".repeat(2001), "envelopeId" to "0", "envelopeId" to "-1",
            "envelopeId" to "9".repeat(30), "role_1" to "reject", "field_2" to "payAmount",
            "field2_2" to "invented", "transform_2" to "invented", "stripPrefix_2" to "x".repeat(41),
            "bind_4" to "invented", "role_150" to "anchor", "role_0" to "anchor",
            "constName_1" to "customerNameHash", "constName_1" to "redCardTotal", "constValue_1" to "yes",
            "constName_2" to "itemsRemaining", "constName_1" to "x".repeat(49), "constValue_1" to "x".repeat(2001),
        )
        for ((key, value) in invalid) assertTrue(codec.decode(base().changed(key to value)) is DraftDecode.Errors, key)
        assertTrue(codec.decode(base().changed("field_2" to "", "field2_2" to "")) is DraftDecode.Errors)
        assertTrue(codec.decode(base().changed("bind_4" to "")) is DraftDecode.Errors)
        assertTrue(codec.decode(base().changed("unknown_control" to "ignored")) is DraftDecode.Ok)
    }

    @Test
    fun `shape refresh accepts previous shape choices and incomplete intent but enforces vocabularies and bounds`() {
        val refresh = base().changed("shape" to "offer", "intent" to "", "screenClass" to "unknown")
        assertTrue(codec.decode(refresh, shapeRefresh = true) is DraftDecode.Ok)
        assertTrue(codec.decode(refresh) is DraftDecode.Errors)
        assertTrue(codec.decode(refresh.changed("transform_2" to "invented"), shapeRefresh = true) is DraftDecode.Errors)
        assertTrue(codec.decode(refresh.changed("stripPrefix_2" to "x".repeat(41)), shapeRefresh = true) is DraftDecode.Errors)
    }

    @Test
    fun `64 assignment cap counts each field slot and constants`() {
        val many = DraftForm(nodes(65).mapIndexed { i, n -> DraftNode(i + 1, n) })
        fun selected(n: Int) = Selections("idle", "idle", "home", 500, anchors = many.nodes.take(n).map { PathRef(it.node.path) })
        fun decode(s: Selections) = many.decode(many.encode(s).changed("envelopeId" to "7"))
        assertTrue(decode(selected(64)) is DraftDecode.Ok)
        assertTrue(decode(selected(65)) is DraftDecode.Errors)
        assertTrue(decode(selected(64).copy(constants = listOf(Constant("startingSession", JsonPrimitive(true))))) is DraftDecode.Errors)
        assertTrue(decode(selected(63).copy(fields = listOf(
            FieldAssignment(PathRef(many.nodes.last().node.path), "zoneName"),
            FieldAssignment(PathRef(many.nodes.last().node.path), "sessionType"),
        ))) is DraftDecode.Errors)
    }

    @Test
    fun `mapping follows drawn badges skips unselectable boxes and keeps original child paths`() {
        val walked = nodes(151)
        val frame = RenderedWireframe("2026-10-02", "12345678", null, 100, 100,
            walked.map { WireBox(0.0, 0.0, 100.0, 100.0, 1, it.text, it.simpleClass, it.idSuffix, false, it.path) }, 151, 0)
        val rows = DraftForm.rows(walked, frame)
        assertEquals(151, rows.size)
        assertEquals(listOf(0), rows.first().node.path)
        val capped = DraftForm(rows.take(DraftForm.MAX_ROWS))
        assertEquals(150, capped.nodes.size)
        assertThrows(IllegalArgumentException::class.java) { DraftForm(rows) }
        assertTrue(capped.decode(base().changed("role_151" to "anchor")) is DraftDecode.Errors)
    }

    @Test
    fun `constants accept int string and boolean with native json types`() {
        val s = selections.copy(constants = listOf(Constant("itemsRemaining", JsonPrimitive(2)), Constant("storeName", JsonPrimitive("Shop")), Constant("arrivalConfirmed", JsonPrimitive(false))))
        val decoded = codec.decode(codec.encode(s).changed("envelopeId" to "7")) as DraftDecode.Ok
        assertEquals(s.constants, decoded.value.selections.constants)
        val json = DraftForm.selectionsJson(decoded.value)
        assertFalse(json.toString().contains("totp"))
        assertEquals(JsonArray(listOf(JsonPrimitive(0))), json.getValue("anchors").jsonArray.single())
    }
}
