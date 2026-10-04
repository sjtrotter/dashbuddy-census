package cloud.trotter.census.server

import cloud.trotter.census.server.db.OpsCluster
import cloud.trotter.census.server.db.OpsSample
import cloud.trotter.census.server.ops.ClusterDetailHtml
import cloud.trotter.census.server.ops.RenderedSkeleton
import cloud.trotter.census.server.ops.RenderedWireframe
import cloud.trotter.census.server.ops.WireBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class ClusterDetailRenderTest {
    private val fingerprint = "a".repeat(64)
    private val cluster = OpsCluster(
        fingerprint, "doordash", "new", "2026-10-01", "2026-10-02", 9, false, 12480,
        listOf("8.10", "8.0"), true, false, notesWithheld = true,
        samples = listOf(
            OpsSample("8.10", "2026-10-02", RenderedSkeleton(
                null, "~e5f60718", listOf("digits", "words:2", "digits"),
                listOf(RenderedSkeleton("~a1b2c3d4")),
            )),
            OpsSample("8.0", "2026-10-01", RenderedSkeleton()),
        ),
    )

    @Test
    fun `detail expands every sample and preserves tree order duplicates and missing labels`() {
        val page = render(cluster)
        assertTrue(page.contains("<title>Cluster detail · Census operator</title>"))
        assertTrue(page.contains("href=\"/ops/#clusters\""))
        assertTrue(page.contains(">Back to clusters</a>"))
        assertTrue(page.contains("href=\"/ops/clusters/$fingerprint\""))
        assertTrue(page.contains(">Cluster JSON</a>"))
        assertTrue(page.contains("New with newest observed version"))
        assertTrue(page.contains("<dt>Platform</dt>"))
        assertTrue(page.contains("<dt>Versions</dt>"))
        assertTrue(page.contains("Labels redacted: 9 / 10 non-trusted installs in 28 days; no trusted sighting."))
        assertTrue(page.contains("12,480"))
        assertEquals(2, Regex("<article class=\"sample\"").findAll(page).count())
        assertEquals(2, Regex("aria-label=\"Skeleton sample [12]\"").findAll(page).count())
        assertFalse(page.contains("<details"))
        assertTrue(page.indexOf("Version 8.10") < page.indexOf("Version 8.0"))
        val tree = page.substringAfter("<ul class=\"tree\">").substringBefore("</article>")
        assertEquals(listOf("digits", "words:2", "digits"), Regex("kind: ([^<]+)</span>").findAll(tree).map { it.groupValues[1] }.toList())
        assertTrue(tree.contains("Class Not supplied"))
        assertTrue(tree.contains("ID Not supplied"))
        assertTrue(tree.contains("No text slots."))
        assertTrue(tree.contains("class=\"redacted\" aria-label=\"Redacted class, ~a1b2c3d4\""))
        assertTrue(tree.contains("class=\"redacted\" aria-label=\"Redacted ID, ~e5f60718\""))
        val samples = page.substringAfter("id=\"samples\"")
        assertEquals(3, Regex("<ul\\b").findAll(samples).count()) // Two roots and one nonempty child list.
        assertFalse(Regex("<ul[^>]*>\\s*</ul>").containsMatchIn(page))
        assertTrue(page.contains("datetime=\"2026-10-02\""))
        assertTrue(page.contains("Dashed labels are privacy-redacted class or ID values. Text slots show kind only."))
        assertShellAndPrivacy(page)
    }

    @Test
    fun `notes withheld takes precedence and visible notes remain escaped sanitized plain text`() {
        assertTrue(render(cluster.copy(notes = "WITHHELD_SENTINEL")).contains("Notes withheld below the privacy gate."))
        assertFalse(render(cluster.copy(notes = "WITHHELD_SENTINEL")).contains("WITHHELD_SENTINEL"))
        val visible = cluster.copy(
            unblinded = true, seenByTrusted = true, notesWithheld = false,
            notes = "First line\n<script>danger</script> ${"b".repeat(64)} 12345678-1234-4234-8234-123456789abc",
            samples = listOf(OpsSample("8.10", "2026-10-02", RenderedSkeleton("android.widget.Button", "com.example:id/action"))),
        )
        val page = render(visible)
        assertTrue(page.contains("Labels visible: trusted sighting."))
        assertTrue(page.contains("First line\n&lt;script&gt;danger&lt;/script&gt; [redacted] [redacted]"))
        assertTrue(page.contains("class=\"notes\""))
        assertTrue(page.contains("<code>android.widget.Button</code>"))
        assertTrue(page.contains("<code>com.example:id/action</code>"))
        assertFalse(page.contains("class=\"redacted\""))
        assertTrue(render(visible.copy(notes = null, samples = emptyList())).contains("No operator notes."))
        assertTrue(render(visible.copy(notes = "", samples = null)).contains("No skeleton samples retained for this cluster."))
        assertShellAndPrivacy(page)
    }

    @Test
    fun `wireframe precedes samples with sanitized labels titles facts and numeric styles`() {
        val identity = "12345678-1234-4234-8234-123456789abc"
        val wireframe = RenderedWireframe("2026-10-02", "12345678", "8.10", 1080, 2400, listOf(
            WireBox(0.0, 0.0, 100.0, 100.0, 0, null, null, null, false),
            WireBox(10.25, 20.5, 30.0, 40.0, 1, "Go $identity <script>", "Button", "go", true),
            WireBox(0.0, 0.0, 1.0, 1.0, 1, null, identity, "b".repeat(64), false),
        ), 4, 1)
        val originalLocale = Locale.getDefault()
        val page = try {
            Locale.setDefault(Locale.GERMANY)
            render(cluster.copy(wireframe = wireframe))
        } finally {
            Locale.setDefault(originalLocale)
        }
        assertTrue(page.indexOf("Screen wireframe") < page.indexOf("Skeleton samples"))
        assertTrue(page.contains("class=\"wire-frame\" style=\"aspect-ratio:1080/2400\""))
        assertTrue(page.contains("class=\"wire-box clickable\""))
        assertTrue(page.contains("left:10.25%;top:20.50%;width:30.00%;height:40.00%"))
        assertTrue(page.contains("title=\"Button · go\""))
        assertTrue(page.contains("title=\"view · no id\""))
        assertTrue(page.contains("title=\"[redacted] · [redacted]\""))
        assertTrue(page.contains("Go [redacted] &lt;script&gt;"))
        assertTrue(page.contains("Trusted capture · received <time datetime=\"2026-10-02\">2026-10-02</time> · install 12345678 · app version <code>8.10</code> · 3 of 4 nodes drawn"))
        val styles = Regex("style=\"([^\"]*)\"").findAll(page).map { it.groupValues[1] }.toList()
        assertEquals(4, styles.size)
        val numericStyles = Regex("""^(aspect-ratio:[0-9]+/[0-9]+|((left|top|width|height):[0-9]+(\.[0-9]+)?%;?)+)$""")
        assertTrue(styles.all { numericStyles.matches(it) })
        assertTrue(render(cluster.copy(wireframe = wireframe.copy(platformAppVersion = null))).contains("app version Not recorded"))
        assertShellAndPrivacy(page)
    }

    @Test
    fun `wireframe empty state explains pairing and has no inline styles`() {
        val page = render(cluster)
        assertTrue(page.contains("Screen wireframe"))
        assertTrue(page.contains("No trusted capture is paired with this cluster yet. A wireframe appears once a trusted install uploads a capture that names this cluster."))
        assertFalse(page.contains("style=\""))
        assertShellAndPrivacy(page)
    }

    @Test
    fun `missing cluster returns the shared shell and dashboard link`() {
        val page = render(null)
        assertTrue(page.contains("Cluster not found"))
        assertTrue(page.contains("href=\"/ops/#clusters\""))
        assertFalse(page.contains("Cluster JSON"))
        assertShellAndPrivacy(page)
    }

    private fun render(value: OpsCluster?): String = ClusterDetailHtml.render("1.2.3+abc1234", 10, "2026-10-02", value)

    private fun assertShellAndPrivacy(page: String) {
        assertEquals(1, Regex("<style\\b").findAll(page).count())
        assertEquals(1, Regex("<form\\b").findAll(page).count())
        assertFalse(page.contains("<script"))
        assertTrue(page.contains("1.2.3+[build withheld]"))
        assertTrue(page.contains("name=\"color-scheme\" content=\"dark light\""))
        val withoutLinks = page.replace(Regex("href=\"/ops/clusters/[0-9a-f]{64}\""), "")
        assertFalse(Regex("(?i)[0-9a-f]{16}").containsMatchIn(withoutLinks))
        assertFalse(Regex("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").containsMatchIn(page))
    }
}
