package cloud.trotter.census.server

import cloud.trotter.census.server.db.OpsCluster
import cloud.trotter.census.server.db.OpsClusterGroup
import cloud.trotter.census.server.db.OpsSample
import cloud.trotter.census.server.ops.DashboardHtml
import cloud.trotter.census.server.ops.SkeletonRender
import cloud.trotter.census.server.ops.SkeletonRenderTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DashboardRenderTest {
    @Test
    fun `read only page has no raw hashes identities secrets or below gate labels`() {
        val fingerprint = "a".repeat(64)
        val cluster = OpsCluster(
            fingerprint, "doordash", "new", "2026-10-02", "2026-10-02", 9, false, 12,
            listOf("8.0"), true, false, samples = listOf(OpsSample("8.0", "2026-10-02", SkeletonRender.render(SkeletonRenderTest.sample, false))),
        )
        val empty = JsonObject(emptyMap())
        val page = DashboardHtml.render("dev", 10, "2026-10-02", empty, listOf(OpsClusterGroup("8.0", listOf(cluster))), empty, JsonArray(emptyList()), empty, 3)
        assertTrue(page.contains("/ops/clusters/$fingerprint"))
        val withoutLinks = page.replace(Regex("href=\"/ops/clusters/[0-9a-f]{64}\""), "")
        assertFalse(Regex("[0-9a-f]{16}").containsMatchIn(withoutLinks))
        assertFalse(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").containsMatchIn(page))
        for (forbidden in listOf("android.widget", "com.example:id/action", "OPERATOR_TOKEN_SENTINEL", "<form", "<script")) assertFalse(page.contains(forbidden))
        assertTrue(page.contains("9 installs"))
        assertTrue(page.contains("12 sightings"))
        assertTrue(page.contains("new with version"))
        assertTrue(page.contains("Vocabulary queue (3)"))
    }
}
