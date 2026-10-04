package cloud.trotter.census.server

import cloud.trotter.census.contract.authoring.EnvelopeWalk
import cloud.trotter.census.server.db.OpsCluster
import cloud.trotter.census.server.db.PinnedEnvelope
import cloud.trotter.census.server.ops.*
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI

class ClusterDraftRenderTest {
    private val cluster = OpsCluster("a".repeat(64), "doordash", "new", "2026-10-01", "2026-10-02", 1, true, 4, listOf("8.10"), false, true)
    private val capture = PinnedEnvelope(7, "", "b".repeat(64), "2026-10-02", "12345678", "8.10")
    private val identity = "12345678-1234-4234-8234-123456789abc"
    private fun envelope(count: Int) = buildJsonObject {
        put("payload", buildJsonObject {
            put("bounds", buildJsonObject { put("left", 0); put("top", 0); put("right", 100); put("bottom", 200) })
            put("children", JsonArray(List(count) { buildJsonObject {
                put("class", "TextView"); put("id", "app:id/title"); put("text", "Label $identity")
                put("isClickable", true)
                put("bounds", buildJsonObject { put("left", 0); put("top", 0); put("right", 100); put("bottom", 100) })
            } }))
        })
    }
    private fun render(count: Int = 1, state: Parameters = Parameters.Empty, nested: Boolean = false): String {
        val envelope = envelope(count)
        val nodes = EnvelopeWalk.walk(envelope.getValue("payload").jsonObject)
        val frame = requireNotNull(WireframeRender.render(envelope, nodes, capture.receivedDay, capture.installPrefix))
        return ClusterDraftHtml.render("0.11.0", 10, "2026-10-02", cluster, capture, frame, DraftForm.rows(nodes, frame), state,
            errors = listOf("Check $identity"), warnings = listOf("Review $identity"), nested = nested)
    }

    @Test
    fun `every control preserves state masks labels and never echoes TOTP`() {
        val state = ParametersBuilder().apply {
            mapOf("screenClass" to "offer:presented", "shape" to "offer", "intent" to "offer_card", "priority" to "601",
                "modeHint" to "online", "offerSurface" to "card", "comment" to "Review comment", "notes" to "Review notes",
                "constName_1" to "offerKind", "constValue_1" to "shop", "role_2" to "field", "field_2" to "payAmount",
                "field2_2" to "distance", "transform_2" to "parseCurrency", "stripPrefix_2" to "Total: ", "bind_2" to "acceptButton",
                "totp" to "654321").forEach { (k, v) -> append(k, v) }
        }.build()
        val page = render(state = state)
        for (key in state.names()) assertTrue(page.contains("name=\"$key\""), key)
        for ((key, values) in state.entries()) if (key != "totp") assertTrue(page.contains(values.single()), key)
        assertFalse(page.contains("654321"))
        assertTrue(page.contains("✓ payAmount")); assertTrue(page.contains("✓ distance")); assertTrue(page.contains("✗ deliveryTimeText or timeToCompleteMinutes"))
        assertTrue(page.contains("class=\"wire-n\"")); assertTrue(page.contains("Label [redacted]"))
        assertEquals(1, Regex("<style\\b").findAll(page).count())
        assertFalse(page.contains("<script"))
        assertTrue(page.contains("name=\"stripPrefix_2\""))
        assertTrue(page.contains("maxlength=\"40\""))
        assertPrivate(page)
        for (style in Regex(" style=\"([^\"]*)\"").findAll(page).map { it.groupValues[1] }) {
            assertTrue(Regex("aspect-ratio:[0-9]+/[0-9]+|left:[0-9.]+%;top:[0-9.]+%;width:[0-9.]+%;height:[0-9.]+%").matches(style), style)
        }
    }

    @Test
    fun `150 node rows fit parser parameter budget and report remainder`() {
        val page = render(170)
        assertEquals(150, Regex("name=\"role_[0-9]+\"").findAll(page).count())
        assertTrue(page.contains("20 more nodes not shown"))
        assertEquals(171, Regex("class=\"wire-n\"").findAll(page).count())
        assertTrue(Regex("name=\"").findAll(page.substringAfter("<form action=\"draft\"")).count() <= 1000)
        assertPrivate(page)
    }

    @Test
    fun `relative buttons resolve at GET and after pure POST re-render`() {
        for ((path, nested) in listOf("draft" to false, "draft/preview" to true, "draft/shape" to true)) {
            val page = render(nested = nested)
            val uri = URI("https://operator.invalid/ops/clusters/${cluster.fingerprint}/$path")
            val actions = Regex("(?:action|formaction)=\"([^\"]+)\"").findAll(page).map { it.groupValues[1] }.filter { it != "/ops/logout" }.toList()
            assertEquals(listOf("draft", "draft/shape", "draft/preview"), actions.map { uri.resolve(it).path.substringAfter("${cluster.fingerprint}/") })
            assertPrivate(page)
        }
    }

    @Test
    fun `unclassified DTO retains old bytes and drafts never enter serialization`() {
        val json = Json { explicitNulls = false; encodeDefaults = true }
        val before = json.encodeToString(OpsCluster.serializer(), cluster)
        val expected = """{"fingerprint":"${cluster.fingerprint}","platform":"doordash","status":"new","firstSeenDay":"2026-10-01","lastSeenDay":"2026-10-02","distinctInstalls28d":1,"seenByTrusted":true,"sightings28d":4,"versions":["8.10"],"newWithVersion":false,"unblinded":true,"notesWithheld":false}"""
        assertEquals(expected, before)
        val after = json.parseToJsonElement(json.encodeToString(OpsCluster.serializer(), cluster.copy(screenClass = "idle", hasDraft = true, draftDay = "2026-10-02"))).jsonObject
        assertEquals(JsonPrimitive("idle"), after["screenClass"])
        assertEquals(JsonPrimitive(true), after["hasDraft"])
        assertFalse("draftDay" in after)
        assertFalse("draft" in after)
    }

    @Test
    fun `no capture renders classification and notes without drafting controls`() {
        val page = ClusterDraftHtml.render("0.11.0", 10, "2026-10-02", cluster, null, null, emptyList())
        assertTrue(page.contains("A trusted capture is needed to draft"))
        assertTrue(page.contains("value=\"classify\""))
        assertTrue(page.contains("name=\"screenClass\"")); assertTrue(page.contains("name=\"notes\""))
        assertFalse(page.contains("name=\"shape\"")); assertFalse(page.contains("Preview draft"))
        assertPrivate(page)
    }
}
