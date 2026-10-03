package cloud.trotter.census.server

import cloud.trotter.census.server.db.OpsCluster
import cloud.trotter.census.server.db.OpsClusterGroup
import cloud.trotter.census.server.db.OpsSample
import cloud.trotter.census.server.db.OpsVocabularyDisplay
import cloud.trotter.census.server.ops.DashboardHtml
import cloud.trotter.census.server.ops.SkeletonRender
import cloud.trotter.census.server.ops.SkeletonRenderTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DashboardRenderTest {
    private val empty = JsonObject(emptyMap())
    private val cluster = OpsCluster(
        "a".repeat(64), "doordash", "new", "2026-10-01", "2026-10-02", 9, false, 12480,
        listOf("8.10", "8.0"), true, false, notesWithheld = true,
        samples = listOf(OpsSample("8.0", "2026-10-02", SkeletonRender.render(SkeletonRenderTest.sample, false))),
    )

    @Test
    fun `read only page has no raw hashes identities secrets or home samples`() {
        val page = render(listOf(OpsClusterGroup("8.10", listOf(cluster))))
        assertTrue(page.contains("href=\"/ops/clusters/${cluster.fingerprint}/view\""))
        assertPrivate(page)
        for (forbidden in listOf("android.widget", "com.example:id/action", "OPERATOR_TOKEN_SENTINEL", "<script", "tree-wrap\"", "~")) assertFalse(page.contains(forbidden), forbidden)
        assertEquals(1, Regex("<form\\b").findAll(page).count())
        assertTrue(page.substringBefore("</header>").contains("action=\"/ops/logout\""))
        assertTrue(page.contains("method=\"post\""))
        assertTrue(page.contains("Non-trusted installs · 28 d"))
        assertTrue(page.contains("12,480"))
        assertTrue(page.contains("New with this version"))
        assertTrue(page.contains("Labels redacted: 9 / 10 non-trusted installs in 28 days; no trusted sighting."))
        assertTrue(page.contains("Withheld below privacy gate"))
    }

    @Test
    fun `sections metrics local labels gate explanations and shared shell follow the design`() {
        val other = cluster.copy(fingerprint = "b".repeat(64), seenByTrusted = true, unblinded = true)
        val page = render(listOf(OpsClusterGroup("8.10", listOf(cluster, other)), OpsClusterGroup("8.0", listOf(cluster))))
        assertEquals(listOf("identity", "snapshot", "alarms", "health", "clusters", "ledger", "installs", "vocabulary"),
            Regex("<section[^>]*id=\"([^\"]+)\"").findAll(page).map { it.groupValues[1] }.toList())
        val metrics = page.substringAfter("aria-label=\"Snapshot\"").substringBefore("</nav>")
        assertEquals(listOf("alarms", "health", "ledger", "vocabulary"), Regex("href=\"#([^\"]+)\"").findAll(metrics).map { it.groupValues[1] }.toList())
        val metricValues = Regex("href=\"#([^\"]+)\" class=\"metric\">.*?<span class=\"metric-value(?: date)?\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
            .findAll(metrics).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("0", metricValues["alarms"])
        assertEquals("No reports", metricValues["health"])
        assertEquals("0", metricValues["ledger"])
        assertEquals("0", metricValues["vocabulary"])
        assertEquals(2, Regex(">Cluster 01</a>").findAll(page).count())
        assertEquals(1, Regex(">Cluster 02</a>").findAll(page).count())
        assertTrue(page.contains("Labels visible: trusted sighting."))
        assertTrue(render(listOf(OpsClusterGroup("8.0", listOf(cluster.copy(unblinded = true, distinctInstalls28d = 10))))).contains("Labels visible: 10 ≥ 10 non-trusted installs in 28 days."))
        assertTrue(page.contains("1.2.3+[build withheld]"))
        assertFalse(page.contains("abc1234"))
        assertTrue(page.contains("name=\"color-scheme\" content=\"dark light\""))
        assertTrue(page.contains("lang=\"en\""))
        assertTrue(page.contains("href=\"#main\""))
        assertEquals(1, Regex("<style\\b").findAll(page).count())
        assertFalse(Regex("<details[^>]*\\bopen").containsMatchIn(page))
        assertPrivate(page)
    }

    @Test
    fun `empty deployment explains every absent section without claiming health`() {
        val page = render()
        listOf(
            "No reports",
            "No alarms recorded today by this process. Check reporting health below; this is not an all-clear.",
            "No fleet health reports in the last 7 UTC days.",
            "No per-install health reports in this window.",
            "No clusters to review yet. Ranked groups appear when cluster sightings arrive.",
            "No ingest ledger entries today.",
            "No installs registered yet.",
            "No vocabulary entries currently meet k and await review.",
            "0 · No rejections", "0 B", "Per-install ledger · 0 rows", "Queue entries · 0 shown of 0",
            "Snapshot at page load. All dates UTC.",
            "Read-only operator view. Mutations use the curl recipes in docs/OPERATOR.md.",
        ).forEach { assertTrue(page.contains(it), it) }
        assertFalse(page.contains("Healthy"))
    }

    @Test
    fun `explicit tables retain rows rules rejection reasons counters and grouped integers`() {
        val alarms = obj("""{"today":[
            {"kind":"silence","platform":"_unknown","version":"unknown","ruleIds":[]},
            {"kind":"trips","platform":"doordash","version":"8.10","installPrefix":"abcd1234","ruleIds":["doordash.z","doordash.a","doordash.z"]}],
            "counts":{"trips":12480,"delivery_failed":2}}""")
        val health = obj("""{"fleet":[
            {"day":"2026-10-01","platform":"doordash","platformAppVersion":"8.0","installsReporting":1,"admitted":100,"unknown":0,"ruleCounts":{}},
            {"day":"2026-10-02","platform":"doordash","platformAppVersion":"8.10","installsReporting":2,"admitted":12480,"unknown":10,"ruleCounts":{"doordash.z":240,"doordash.a":1000}}],
            "installs":[{"installIdPrefix":"abcd1234","day":"2026-10-02","platform":"doordash","version":"8.10","admitted":12480,"unknown":10,"trips":1}]}""")
        val installs = Json.parseToJsonElement("""[
            {"installIdPrefix":"abcd1234","createdDay":"2026-10-01","lastSeenDay":"2026-10-02","trusted":true,"revoked":false,"attested":true,"lastAppVersion":"1.2.3+abc1234"},
            {"installIdPrefix":"abcd1234","createdDay":"2026-10-01","lastSeenDay":"2026-10-01","trusted":false,"revoked":true,"attested":false}]""").jsonArray
        val ledger = obj("""{"totals":{"bytes":12480,"accepted":1240,"duplicate":2,"batches":3,"rejected":{"z_reason":4,"a_reason":1}},
            "installs":[{"installIdPrefix":"abcd1234","bytes":12480,"accepted":1240,"duplicate":2,"batches":3,"rejected":{"z_reason":4,"a_reason":1}}]}""")
        val row = OpsVocabularyDisplay("words:2", 1240, "2026-10-01", "2026-10-02")
        val page = DashboardHtml.render("dev", 10, "2026-10-02", alarms, emptyList(), health, installs, ledger, 12480, listOf(row, row))
        val alarmRows = page.substringAfter("<tbody").substringBefore("</tbody>")
        assertTrue(alarmRows.indexOf("Trips reported") < alarmRows.indexOf("Trusted install silence"))
        assertEquals(2, Regex("doordash.z").findAll(alarmRows).count())
        val rules = page.substringAfter("<summary>Rule counts</summary>").substringBefore("</details>")
        assertTrue(rules.indexOf("doordash.a") < rules.indexOf("doordash.z"))
        assertTrue(page.indexOf("a_reason: 1") < page.indexOf("z_reason: 4"))
        for (copy in listOf("12,480 B", "1,240", "Queue entries · 2 shown of 12,480", "Install records · 2 shown", "delivery_failed", "Not install-specific", "1.2.3+[build withheld]")) assertTrue(page.contains(copy), copy)
        assertEquals(2, Regex(">words:2</span>").findAll(page).count())
        val populatedMetrics = page.substringAfter("aria-label=\"Snapshot\"").substringBefore("</nav>")
        val populatedValues = Regex("href=\"#([^\"]+)\" class=\"metric\">.*?<span class=\"metric-value(?: date)?\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
            .findAll(populatedMetrics).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("2", populatedValues["alarms"])
        assertTrue(requireNotNull(populatedValues["health"]).contains("datetime=\"2026-10-02\""), populatedValues["health"])
        assertEquals("1,240", populatedValues["ledger"])
        assertEquals("12,480", populatedValues["vocabulary"])
        assertTrue(page.contains("Not recorded"))
        assertFalse(page.contains("[redacted]"))
        var bodyCells = 0
        for (table in Regex("<table\\b.*?</table>", RegexOption.DOT_MATCHES_ALL).findAll(page)) {
            val html = table.value
            for (expected in listOf("<caption>", "role=\"table\"", "scope=\"col\"", "scope=\"row\"")) assertTrue(html.contains(expected), expected)
            val columns = Regex("<th scope=\"col\" role=\"columnheader\">(.*?)</th>").findAll(html).map { it.groupValues[1] }.toList()
            assertTrue(columns.isNotEmpty(), "every table declares its columns")
            val body = html.substringAfter("<tbody").substringBefore("</tbody>")
            for (row in Regex("<tr\\b.*?</tr>", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
                val cells = Regex("<t[hd]\\b.*?</t[hd]>", RegexOption.DOT_MATCHES_ALL).findAll(row.value).toList()
                assertEquals(columns.size, cells.size, "every body row carries one cell per column")
                cells.forEachIndexed { index, cell ->
                    bodyCells++
                    assertTrue(cell.value.contains("<span class=\"cell-label\" aria-hidden=\"true\">${columns[index]}</span>"), cell.value)
                    assertTrue(cell.value.contains("class=\"cell-value\""), cell.value)
                }
            }
        }
        assertTrue(bodyCells > 20, "body cells inspected: $bodyCells")
        assertPrivate(page)
    }

    @Test
    fun `untrusted display strings are validated or defensively sanitized`() {
        val uuid = "12345678-1234-4234-8234-123456789abc"
        val hash = "d".repeat(64)
        val alarms = obj("""{"today":[{"kind":"$hash","platform":"<b>bad</b>","version":"$uuid","installPrefix":"$uuid","ruleIds":["$hash","doordash.ok"]}],"counts":{"$hash":1}}""")
        val page = DashboardHtml.render("dev", 10, "2026-10-02", alarms, emptyList(), empty, JsonArray(emptyList()), empty, 0)
        assertFalse(page.contains(uuid))
        assertFalse(page.contains(hash))
        assertFalse(page.contains("<b>bad</b>"))
        assertTrue(page.contains("doordash.ok"))
        assertTrue(page.contains("[redacted]"))
        assertPrivate(page)
    }

    private fun render(groups: List<OpsClusterGroup> = emptyList()): String =
        DashboardHtml.render("1.2.3+abc1234", 10, "2026-10-02", empty, groups, empty, JsonArray(emptyList()), empty, 0)

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun assertPrivate(page: String) {
        val withoutLinks = page.replace(Regex("href=\"/ops/clusters/[0-9a-f]{64}/view\""), "")
        assertFalse(Regex("(?i)[0-9a-f]{16}").containsMatchIn(withoutLinks))
        assertFalse(Regex("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").containsMatchIn(page))
    }
}
