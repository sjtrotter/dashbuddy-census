package cloud.trotter.census.server

import cloud.trotter.census.contract.authoring.EnvelopeWalk
import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary as V
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
    fun `two node rows associate every control and wireframe link with the row header`() {
        val page = render(2)
        for (n in 2..3) {
            val row = Regex("""<tr>\s*<th[^>]*id="node-$n"[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value
            assertNotNull(row)
            val html = requireNotNull(row)
            assertTrue(html.substringBefore("</th>").contains("scope=\"row\""))
            for ((key, label) in listOf("role" to "Role", "field" to "Field", "field2" to "Second field",
                "transform" to "Transform", "stripPrefix" to "Strip prefix", "bind" to "Bind")) {
                val control = Regex("""<(?:select|input)\b[^>]*name="${key}_$n"[^>]*>""").find(html)?.value
                assertNotNull(control, key)
                assertTrue(requireNotNull(control).contains("aria-label=\"$label for node $n\""))
                assertTrue(control.contains("aria-describedby=\"node-$n\""))
            }
            assertTrue(page.contains("<a href=\"#node-$n\" class=\"wire-n\">$n</a>"))
        }
        val form = page.substringAfter("<form action=\"draft\"").substringBefore("</form>")
        assertTrue(Regex("""<ul[^>]*role="alert"[^>]*>""").containsMatchIn(form))
        assertFalse(page.contains("envelopePin")); assertFalse(page.contains(capture.sha256Hex))
        assertPrivate(page)
    }

    @Test
    fun `privacy assertion rejects grouped and compacted attribute hex while permitting cluster links`() {
        for (value in listOf("abcd-abcd-abcd-abcd", "abcd abcd abcd", "abcdefabcdef", "prefix'ABCD ABCD ABCD")) {
            assertThrows(AssertionError::class.java) { assertPrivate("<input value=\"$value\">") }
        }
        assertThrows(AssertionError::class.java) { assertPrivate("<p>abcd-abcd-abcd-abcd</p>") }
        assertPrivate("<a href=\"/ops/clusters/${cluster.fingerprint}/view\">Cluster</a>")
    }

    @Test
    fun `grouped and spaced hex never survives in control attributes`() {
        for (value in listOf("abcd-abcd-abcd-abcd", "abcd abcd abcd", "abcdefabcdef")) {
            val page = render(state = ParametersBuilder().apply { append("intent", value); append("field_2", value) }.build())
            assertPrivate(page)
        }
    }

    @Test
    fun `unlabelled non-clickable root has a plain badge and selectable child has a link`() {
        val page = render(1)
        assertEquals(1, Regex("""<a href="#node-\d+" class="wire-n">""").findAll(page).count())
        assertEquals(1, Regex("""<span class="wire-n">""").findAll(page).count())
        assertTrue(page.contains("<span class=\"wire-n\">1</span>"))
        assertTrue(page.contains("<a href=\"#node-2\" class=\"wire-n\">2</a>"))
        assertPrivate(page)
    }

    @Test
    fun `150 node rows fit parser parameter budget and report remainder`() {
        val page = render(170)
        assertEquals(150, Regex("name=\"role_[0-9]+\"").findAll(page).count())
        assertTrue(page.contains("20 more nodes not shown"))
        assertEquals(171, Regex("class=\"wire-n\"").findAll(page).count())
        assertEquals(150, Regex("""<a href="#node-\d+" class="wire-n">""").findAll(page).count())
        assertEquals(21, Regex("""<span class="wire-n">""").findAll(page).count())
        assertFalse(page.contains("href=\"#node-152\""))
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
    fun `shape choices follow class legality including active and unknown`() {
        for (screenClass in V.SCREEN_CLASSES + "unknown") {
            val page = render(state = Parameters.build { append("screenClass", screenClass) })
            val select = Regex("""<select[^>]*name="shape"[^>]*>(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
                .find(page)!!.groupValues[1]
            val choices = Regex("""<option value="([^"]*)"""").findAll(select).map { it.groupValues[1] }.toList()
            assertEquals(V.LEGAL_SHAPES_BY_CLASS[screenClass] ?: V.LEGAL_SHAPES_BY_CLASS.values.flatten().distinct(), choices)
            for (illegal in listOf("paused", "timeline", "ratings")) assertFalse(illegal in choices)
            if (screenClass == "task:active") assertTrue(select.contains("value=\"none\" selected"))
            assertPrivate(page)
        }
        val changed = render(state = Parameters.build { append("screenClass", "idle"); append("shape", "ratings") })
        assertFalse(changed.contains("value=\"ratings\""))
    }

    @Test
    fun `click action alone is flagged clickable in the node table`() {
        val node = EnvelopeWalk.walk(buildJsonObject {
            put("class", "Button"); put("isClickable", false); put("clickAction", true)
        }).single()
        val page = ClusterDraftHtml.render("0.11.0", 10, "2026-10-02", cluster, capture, null, listOf(DraftNode(1, node)))
        assertTrue(Regex("<td>clickable</td>").containsMatchIn(page))
        assertPrivate(page)
    }

    @Test
    fun `withheld notes have no control and never echo stored or posted text`() {
        for (pinned in listOf(null, capture)) {
            val page = ClusterDraftHtml.render("0.11.0", 10, "2026-10-02",
                cluster.copy(notes = "Private stored notes", notesWithheld = true), pinned, null, emptyList(),
                Parameters.build { append("notes", "Private submitted notes") })
            assertTrue(page.contains("Notes withheld below the privacy gate"))
            assertFalse(page.contains("name=\"notes\""))
            assertFalse(page.contains("Private stored notes")); assertFalse(page.contains("Private submitted notes"))
            assertPrivate(page)
        }
    }

    @Test
    fun `stale capture preserves typed draft entries without capture data or TOTP`() {
        val state = Parameters.build {
            append("envelopeId", "7"); append("screenClass", "idle"); append("shape", "idle")
            append("intent", "home"); append("priority", "601"); append("modeHint", "online")
            append("offerSurface", "card"); append("comment", "Review comment"); append("notes", "Review notes")
            append("constName_1", "zoneName"); append("constValue_1", "Midtown")
            append("role_2", "field"); append("field_2", "zoneName"); append("field2_2", "sessionType")
            append("transform_2", "trim"); append("stripPrefix_2", "Zone: "); append("bind_2", "acceptButton")
            append("totp", "654321"); append("unknown_control", identity)
        }
        val page = ClusterDraftHtml.render("0.11.0", 10, "2026-10-02", cluster, null, null, emptyList(), state)
        for ((key, values) in state.entries()) if (key !in setOf("envelopeId", "totp", "unknown_control")) {
            assertTrue(page.contains("name=\"$key\""), key)
            assertTrue(page.contains(values.single()), key)
        }
        assertFalse(page.contains("name=\"envelopeId\"")); assertFalse(page.contains("654321"))
        assertFalse(page.contains(identity)); assertFalse(page.contains("unknown_control"))
        assertPrivate(page)
    }

    @Test
    fun `no capture renders classification and notes without drafting controls`() {
        val page = ClusterDraftHtml.render("0.11.0", 10, "2026-10-02", cluster, null, null, emptyList())
        assertTrue(page.contains("A trusted capture is needed to draft"))
        assertTrue(page.contains("value=\"classify\""))
        assertTrue(page.contains("name=\"screenClass\"")); assertTrue(page.contains("name=\"notes\""))
        assertFalse(page.contains("name=\"shape\"")); assertFalse(page.contains("Preview draft"))
        assertTrue(page.contains("Save classification")); assertFalse(page.contains("Save draft"))
        assertPrivate(page)
    }

    @Test
    fun `a capture-bearing page offers classification-only and draft saves and hints at it on a blank intent`() {
        val page = render()
        assertTrue(Regex("<button[^>]*name=\"mode\"[^>]*value=\"classify\"[^>]*formnovalidate[^>]*>Save classification only</button>").containsMatchIn(page)
            || Regex("<button[^>]*formnovalidate[^>]*name=\"mode\"[^>]*value=\"classify\"[^>]*>Save classification only</button>").containsMatchIn(page))
        assertFalse(page.contains("<input type=\"hidden\" name=\"mode\""))
        assertTrue(page.contains("Save draft"))
        assertFalse(page.contains("To record the screen class without drafting"))
        val envelope = envelope(1)
        val nodes = EnvelopeWalk.walk(envelope.getValue("payload").jsonObject)
        val frame = requireNotNull(WireframeRender.render(envelope, nodes, capture.receivedDay, capture.installPrefix))
        val refused = ClusterDraftHtml.render("0.11.0", 10, "2026-10-02", cluster, capture, frame, DraftForm.rows(nodes, frame),
            Parameters.Empty, errors = listOf("Invalid intent"))
        assertTrue(refused.contains("To record the screen class without drafting a rule, use &quot;Save classification only&quot; (no intent needed)."))
    }
}
