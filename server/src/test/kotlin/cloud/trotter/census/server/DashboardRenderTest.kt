package cloud.trotter.census.server

import cloud.trotter.census.server.db.OpsClusterSummaryRow
import cloud.trotter.census.server.db.OpsVocabularyDisplay
import cloud.trotter.census.server.ops.DashboardHtml
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
    private val summaries = listOf(
        OpsClusterSummaryRow("doordash", "8.10", 3, mapOf("new" to 2, "triaged" to 1)),
        OpsClusterSummaryRow("doordash", "8.0", 1, mapOf("resolved" to 1)),
        OpsClusterSummaryRow("doordash", null, 1, mapOf("new" to 1)),
        OpsClusterSummaryRow("uber", "1.2", 1, mapOf("ignored" to 1)),
    )

    @Test
    fun `read only page has no raw hashes identities secrets or home samples`() {
        val page = render(summaries)
        assertFalse(page.contains("class=\"cluster-card\""))
        assertFalse(Regex("/ops/clusters/[0-9a-f]{64}/view").containsMatchIn(page))
        assertFalse(page.contains("How ranking works"))
        assertPrivate(page)
        for (forbidden in listOf("android.widget", "com.example:id/action", "OPERATOR_TOKEN_SENTINEL", "<script", "tree-wrap\"", "~")) assertFalse(page.contains(forbidden), forbidden)
        assertEquals(1, Regex("<form\\b").findAll(page).count())
        assertTrue(page.substringBefore("</header>").contains("action=\"/ops/logout\""))
        assertTrue(page.contains("method=\"post\""))
    }

    @Test
    fun `sections metrics and shared shell follow the design`() {
        val page = render(summaries)
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
            "No clusters to review yet. Rows appear when cluster sightings arrive.",
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

    @Test
    fun `summary table keeps platform version order counts and validated review destinations`() {
        val page = render(summaries)
        val section = page.substringAfter("id=\"clusters\"").substringBefore("</section>")
        assertTrue(section.contains("Clusters by platform and app version · latest version first"))
        val rows = Regex("<tr\\b.*?</tr>", RegexOption.DOT_MATCHES_ALL)
            .findAll(section.substringAfter("<tbody")).map { it.value }.toList()
        assertEquals(4, rows.size)
        val expectedCounts = listOf(listOf(3, 2, 1, 0, 0, 0), listOf(1, 0, 0, 0, 1, 0), listOf(1, 1, 0, 0, 0, 0), listOf(1, 0, 0, 0, 0, 1))
        rows.forEachIndexed { index, row ->
            val expected = summaries[index]
            val values = Regex("<span class=\"cell-value\">(.*?)</span>").findAll(row).map { it.groupValues[1] }.toList()
            assertEquals(expected.platform, values.first())
            assertEquals(expected.platformAppVersion?.let { "<code>$it</code>" } ?: "Not recorded", values[1])
            assertEquals(expectedCounts[index].map { it.toString() }, values.subList(2, 8))
            assertTrue(row.contains("<th scope=\"row\""))
            assertTrue(row.contains("href=\"/ops/clusters/view?platform=${expected.platform}&amp;version=${expected.platformAppVersion ?: "none"}\""))
            assertTrue(row.contains("aria-label=\"Review ${expected.platform} ${expected.platformAppVersion ?: "no version"}\""))
        }
        assertPrivate(page)
        for (invalid in listOf(summaries.first().copy(platform = "Bad!"), summaries.first().copy(platformAppVersion = "1.0.0.0.0"))) {
            val html = render(listOf(invalid))
            assertTrue(html.contains("Unavailable"))
            assertFalse(html.contains("href=\"/ops/clusters/view?"))
            assertPrivate(html)
        }
    }

    private fun render(groups: List<OpsClusterSummaryRow> = emptyList()): String =
        DashboardHtml.render("1.2.3+abc1234", 10, "2026-10-02", empty, groups, empty, JsonArray(emptyList()), empty, 0)

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
