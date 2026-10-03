package cloud.trotter.census.server

import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.db.OpsCluster
import cloud.trotter.census.server.db.OpsClusterPage
import cloud.trotter.census.server.db.OpsSample
import cloud.trotter.census.server.ops.ClustersPageHtml
import cloud.trotter.census.server.ops.SkeletonRender
import cloud.trotter.census.server.ops.SkeletonRenderTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClustersPageRenderTest {
    private val clusters = listOf("ignored", "new", "triaged", "new").mapIndexed { index, status ->
        OpsCluster(
            ('a' + index).toString().repeat(64), "doordash", status, "2026-10-01", "2026-10-02", 9, false, 12480,
            listOf("8.10", "8.0"), true, false, notesWithheld = true,
            samples = listOf(OpsSample("8.0", "2026-10-02", SkeletonRender.render(SkeletonRenderTest.sample, false))),
        )
    }
    private val page = OpsClusterPage("doordash", "8.10", "new", 54, 1, 25, 3, clusters)

    @Test
    fun `cards preserve supplied order and local labels without exposing samples or identifiers`() {
        val html = render()
        val links = Regex("href=\"(/ops/clusters/[0-9a-f]{64}/view)\" class=\"cluster-link\">(.*?)</a>")
            .findAll(html).toList()
        assertEquals(clusters.map { "/ops/clusters/${it.fingerprint}/view" }, links.map { it.groupValues[1] })
        assertEquals(listOf("Cluster 01", "Cluster 02", "Cluster 03", "Cluster 04"), links.map { it.groupValues[2] })
        assertTrue(html.contains("Clusters · doordash · Version 8.10"))
        assertTrue(html.contains("54 clusters · untriaged first, then by rank · page 1 of 3"))
        assertTrue(html.contains("How ranking works"))
        for (copy in listOf("Non-trusted installs · 28 d", "12,480", "New with this version",
            "Labels redacted: 9 / 10 non-trusted installs in 28 days; no trusted sighting.", "Withheld below privacy gate")) assertTrue(html.contains(copy), copy)
        for (forbidden in listOf("android.widget", "com.example:id/action", "OPERATOR_TOKEN_SENTINEL", "<script", "tree-wrap\"", "~")) assertFalse(html.contains(forbidden), forbidden)
        assertEquals(1, Regex("<style\\b").findAll(html).count())
        assertPrivate(html)
        val visible = render(page.copy(clusters = listOf(
            clusters[0].copy(seenByTrusted = true, unblinded = true),
            clusters[1].copy(distinctInstalls28d = 10, unblinded = true),
            clusters[0],
        )))
        assertTrue(visible.contains("Labels visible: trusted sighting."))
        assertTrue(visible.contains("Labels visible: 10 ≥ 10 non-trusted installs in 28 days."))
        assertEquals(2, Regex(">Cluster 01</a>").findAll(visible).count())
        assertEquals(1, Regex(">Cluster 02</a>").findAll(visible).count())
        assertPrivate(visible)
    }

    @Test
    fun `six status links keep platform and version reset paging and mark only the current filter`() {
        for (status in listOf(null) + CLUSTER_STATUSES) {
            val html = render(page.copy(page = 2, status = status))
            val nav = html.substringAfter("aria-label=\"Status filter\"").substringBefore("</nav>")
            val links = Regex("<a\\b.*?</a>").findAll(nav).map { it.value }.toList()
            assertEquals(6, links.size)
            (listOf(null) + CLUSTER_STATUSES).forEachIndexed { index, filter ->
                val suffix = filter?.let { "&amp;status=$it" } ?: ""
                assertTrue(links[index].contains("href=\"/ops/clusters/view?platform=doordash&amp;version=8.10$suffix\""))
                assertFalse(links[index].contains("&amp;page="))
                assertEquals(filter == status, links[index].contains("aria-current=\"page\""))
                assertEquals(filter == status, links[index].contains("class=\"action current\""))
                assertTrue(links[index].contains(">${filter?.replaceFirstChar { it.uppercaseChar() } ?: "All"}</a>"))
            }
            assertPrivate(html)
        }
    }

    @Test
    fun `paging preserves the filter and only offers existing adjacent pages`() {
        for (current in 1..3) {
            val html = render(page.copy(page = current))
            val nav = html.substringAfter("aria-label=\"Pages\"").substringBefore("</nav>")
            assertEquals(current > 1, nav.contains(">Previous</a>"))
            assertEquals(current < 3, nav.contains(">Next</a>"))
            for (target in listOf(current - 1, current + 1).filter { it in 1..3 }) {
                assertTrue(nav.contains("href=\"/ops/clusters/view?platform=doordash&amp;version=8.10&amp;status=new&amp;page=$target\""))
            }
            assertTrue(nav.contains("Page $current of 3"))
            assertPrivate(html)
        }
    }

    @Test
    fun `empty missing version and invalid pages retain the shared shell and back link`() {
        val empty = render(page.copy(platformAppVersion = null, clusters = emptyList(), total = 0, pageCount = 1, status = null))
        assertTrue(empty.contains("No clusters match this filter."))
        assertTrue(empty.contains("Clusters · doordash · No version recorded"))
        assertTrue(empty.contains("href=\"/ops/clusters/view?platform=doordash&amp;version=none\""))
        assertFalse(empty.contains("class=\"cluster-card\""))
        assertPrivate(empty)
        val invalid = ClustersPageHtml.renderInvalid("dev", 10, "2026-10-02")
        assertTrue(invalid.contains("<title>Invalid cluster filter</title>"))
        assertTrue(invalid.contains("<h1>Invalid cluster filter</h1>"))
        assertTrue(invalid.contains("href=\"/ops/#clusters\""))
        assertEquals(1, Regex("<style\\b").findAll(invalid).count())
        assertPrivate(invalid)
        for (bad in listOf(page.copy(platform = "Bad!"), page.copy(platformAppVersion = "1.0.0.0.0"))) {
            val html = render(bad)
            assertTrue(html.contains("Unavailable"))
            assertFalse(html.contains("href=\"/ops/clusters/view?"))
            assertPrivate(html)
        }
        val invalidStatus = render(page.copy(page = 2, status = "bogus"))
        assertFalse(invalidStatus.substringAfter("aria-label=\"Pages\"").substringBefore("</nav>").contains("href="))
        val invalidFingerprint = render(page.copy(clusters = listOf(clusters.first().copy(fingerprint = "bad!"))))
        assertFalse(invalidFingerprint.contains("class=\"cluster-link\""))
        assertTrue(invalidFingerprint.contains("Cluster 01"))
    }

    private fun render(value: OpsClusterPage = page): String = ClustersPageHtml.render("dev", 10, "2026-10-02", value)
}
