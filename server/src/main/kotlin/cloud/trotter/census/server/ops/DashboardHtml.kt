package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.db.OpsClusterSummaryRow
import cloud.trotter.census.server.db.OpsVocabularyDisplay
import cloud.trotter.census.server.ingest.WireGrammars
import kotlinx.html.DL
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.LocalDate
import java.util.Locale

/** Read-only operator data with a logout control. Only link destinations contain fingerprints. */
object DashboardHtml {
    fun render(
        serverVersion: String,
        k: Int,
        today: String,
        alarms: JsonObject,
        clusters: List<OpsClusterSummaryRow>,
        health: JsonObject,
        installs: JsonArray,
        ledger: JsonObject,
        vocabularyCount: Long,
        vocabularyRows: List<OpsVocabularyDisplay> = emptyList(),
    ): String = opsPage(headerContent = {
        h1 { +"Census operator" }
        panel("identity", "Reporting context", hiddenHeading = true) { metadata(serverVersion, k, today) }
    }) {
        panel("snapshot", "Snapshot", hiddenHeading = true) {
            nav("metrics") {
                attributes["aria-label"] = "Snapshot"
                metric("alarms", "Alarms today") { +number(alarms.rows("today").size) }
                metric("health", "Latest health day", date = true) {
                    val latest = health.rows("fleet").map { it.text("day") }.maxOrNull()
                    if (latest == null) +"No reports" else date(latest)
                }
                metric("ledger", "Accepted today") { +number(ledger.obj("totals").count("accepted")) }
                metric("vocabulary", "Vocabulary queue") { +number(vocabularyCount) }
            }
            nav("section-nav") {
                attributes["aria-label"] = "Sections"
                listOf("Alarms", "Health", "Clusters", "Ledger", "Installs", "Vocabulary").forEach {
                    a(href = "#${it.lowercase(Locale.ROOT)}") { +it }
                }
            }
            p("muted") { +"Snapshot at page load. All dates UTC." }
        }
        alarmSection(alarms)
        healthSection(health, today)
        panel("clusters", "Clusters") {
            p("muted") { +"One row per platform and app version. Review opens the clusters of that row, untriaged first." }
            if (clusters.isEmpty()) emptyState("No clusters to review yet. Rows appear when cluster sightings arrive.")
            else dataTable("Clusters by platform and app version · latest version first",
                listOf("Platform", "Version", "Clusters", "New", "Triaged", "Drafted", "Resolved", "Ignored", "Classes", "Review"), clusters.map { row ->
                    listOf(
                        cell { +platform(row.platform) },
                        cell { row.platformAppVersion?.let { code { +version(it) } } ?: run { +"Not recorded" } },
                        numeric(row.total),
                    ) + CLUSTER_STATUSES.map { numeric(row.byStatus[it] ?: 0) } + cell { +classSummary(row.byClass) } + cell {
                        val filter = ClusterFilter.parse(row.platform, row.platformAppVersion ?: "none", null, null)
                        if (filter == null) +"Unavailable" else a(href = filter.href(), classes = "action") {
                            attributes["aria-label"] = "Review ${platform(row.platform)} ${row.platformAppVersion?.let { version(it) } ?: "no version"}"
                            +"Review"
                        }
                    }
                })
        }
        ledgerSection(ledger, today)
        panel("installs", "Installs") {
            p("muted") { +"${number(installs.size)} shown · most recently seen first · maximum 50." }
            if (installs.isEmpty()) emptyState("No installs registered yet.")
            details {
                summary { +"Install records · ${number(installs.size)} shown" }
                if (installs.isNotEmpty()) dataTable("Install records · most recently seen first", listOf("Install", "Created", "Last seen", "Trusted", "Revoked", "Last app version", "Attestation verdict"), installs.map { element ->
                    val row = element.jsonObject
                    listOf(
                        cell { code { +prefix(row.text("installIdPrefix")) } },
                        cell { date(row.text("createdDay")) }, cell { date(row.text("lastSeenDay")) },
                        cell { yesChip(row.flag("trusted"), "accent") }, cell { yesChip(row.flag("revoked"), "bad") },
                        cell { row.optionalText("lastAppVersion")?.let { code { +version(it) } } ?: run { +"Not recorded" } },
                        cell { +if (row.flag("attested")) "Recorded" else "Not recorded" },
                    )
                })
            }
        }
        panel("vocabulary", "Vocabulary queue", badge = number(vocabularyCount)) {
            p("muted") { +"Eligible entries awaiting review. Eligibility counts non-trusted installs; identifiers are withheld here." }
            if (vocabularyRows.isEmpty()) emptyState("No vocabulary entries currently meet k and await review.")
            details {
                summary { +"Queue entries · ${number(vocabularyRows.size)} shown of ${number(vocabularyCount)}" }
                if (vocabularyRows.isNotEmpty()) dataTable("Eligible vocabulary entries · maximum 50", listOf("Kind", "Non-trusted installs", "First seen", "Last seen"), vocabularyRows.map { row ->
                    listOf(cell { chip(safe(row.kind), "neutral") }, numeric(row.distinctInstalls), cell { date(row.firstDay) }, cell { date(row.lastDay) })
                })
            }
        }
    }
}

/** Shared shell for the dashboard, detail, missing-cluster and login pages. */
internal fun opsPage(
    pageTitle: String = "Census operator · DashBuddy",
    logout: Boolean = true,
    headerContent: FlowContent.() -> Unit,
    content: FlowContent.() -> Unit,
): String = "<!DOCTYPE html>" + createHTML().html {
    lang = "en"
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; this.content = "width=device-width, initial-scale=1" }
        meta { name = "color-scheme"; this.content = "dark light" }
        title { +pageTitle }
        style { unsafe { raw(OpsStyles.CSS) } }
    }
    body {
        a(href = "#main", classes = "skip-link") { +"Skip to content" }
        div("shell") {
            header("page-header") {
                p("eyebrow") { +"DashBuddy / Census" }
                headerContent()
                if (logout) form(action = "/ops/logout", method = FormMethod.post) { button { +"Log out" } }
            }
            main { id = "main"; attributes["tabindex"] = "-1"; content() }
            footer("page-footer") { +"Read-only operator view. Mutations use the curl recipes in docs/OPERATOR.md." }
        }
    }
}

internal fun FlowContent.panel(
    key: String,
    heading: String,
    hiddenHeading: Boolean = false,
    badge: String? = null,
    headingDate: String? = null,
    content: FlowContent.() -> Unit,
) {
    section("panel") {
        id = key
        attributes["aria-labelledby"] = "$key-heading"
        div("section-heading") {
            h2(classes = if (hiddenHeading) "visually-hidden" else null) {
                id = "$key-heading"
                +heading
                if (headingDate != null) { +" · "; date(headingDate); +" UTC" }
            }
            if (badge != null) chip(badge, "neutral")
        }
        content()
    }
}

internal fun FlowContent.metadata(serverVersion: String, k: Int, today: String) {
    dl("header-meta") {
        fact("Server") { code { +version(serverVersion) } }
        fact("Privacy threshold") { +"k = ${number(k)}" }
        fact("UTC date") { date(today) }
    }
}

private fun FlowContent.metric(target: String, label: String, date: Boolean = false, value: FlowContent.() -> Unit) {
    a(href = "#$target", classes = "metric") {
        span("metric-label") { +label }
        span(if (date) "metric-value date" else "metric-value") { value() }
    }
}

internal fun DL.fact(label: String, value: FlowContent.() -> Unit) {
    div { this@fact.dt { +label }; this@fact.dd { value() } }
}

private data class AlarmDisplay(val kind: String, val label: String, val tone: String, val trigger: String)
private val alarmCatalogue = listOf(
    AlarmDisplay("silence", "Trusted install silence", "bad", "Active trusted install: at least 48 hours without a received health batch and stored health day at least two UTC days old."),
    AlarmDisplay("silent_rule_death", "Rule death", "bad", "At least three qualifying historical days in the preceding 28 days, median rule count ≥ 5, and a reporting day with admitted ≥ 200 but zero rule count."),
    AlarmDisplay("rule_share_cliff", "Rule share cliff", "warn", "Rule share falls by more than 80% against the previous-version comparison day; both days need at least two reporting installs."),
    AlarmDisplay("fleet_unknown", "UNKNOWN surge", "warn", "At least two reporting installs and UNKNOWN share ≥ 50%."),
    AlarmDisplay("trips", "Trips reported", "warn", "Stored trip count for an accepted report exceeds zero."),
    AlarmDisplay("new_clusters", "New cluster burst", "warn", "At least five distinct clusters first seen today for a platform/version."),
)

private fun FlowContent.alarmSection(alarms: JsonObject) = panel("alarms", "Alarms today", badge = "${number(alarms.rows("today").size)} delivered") {
    val today = alarms.rows("today")
    val counts = alarms.obj("counts")
    if (today.isEmpty()) emptyState("No alarms recorded today by this process. Check reporting health below; this is not an all-clear.")
    else dataTable("Alarms delivered by this process today · newest recorded first", listOf("Alarm", "Platform", "Version", "Install", "Rules"), today.asReversed().map { row ->
        listOf(
            cell {
                val display = alarmCatalogue.find { it.kind == row.text("kind") }
                chip(display?.label ?: "[redacted]", display?.tone ?: "neutral")
            },
            cell { +platform(row.text("platform")) },
            cell {
                // HealthStore's silence alarm for an install that never reported carries the literal `unknown`
                // sentinel: a missing value, not a privacy event, so it must not read as "[redacted]".
                val version = row.text("version")
                if (version == "unknown" || version == "_unknown") +"Not recorded" else code { +validated(version, WireGrammars.platformAppVersion) }
            },
            cell { row.optionalText("installPrefix")?.let { code { +prefix(it) } } ?: run { +"Not install-specific" } },
            blockCell {
                val rules = row["ruleIds"] as? JsonArray ?: JsonArray(emptyList())
                if (rules.isEmpty()) +"None" else ul("rule-list") { rules.forEach { li { code { +rule(it.jsonPrimitive.content) } } } }
            },
        )
    })
    details {
        summary { +"Alarm catalogue and counters" }
        p("muted") { +"Today’s records and counters are process-local. Counters reset on restart. Local delivery does not confirm email delivery. Silence timing also resets after restart." }
        dataTable("Alarm catalogue and process counters", listOf("Alarm", "Recorded today", "Since restart", "Trigger"), alarmCatalogue.map { display ->
            val delivered = today.count { it.text("kind") == display.kind }
            listOf(
                cell { chip(display.label, display.tone); code { +display.kind } },
                cell { +if (delivered == 0) "None recorded." else "${number(delivered)} delivered" },
                numeric(counts.count(display.kind)), cell { +display.trigger },
            )
        })
        val additional = counts.keys.filter { name -> alarmCatalogue.none { it.kind == name } }.sorted()
        if (additional.isNotEmpty()) dataTable("Additional process counters", listOf("Counter", "Since restart"), additional.map { name ->
            listOf(cell { code { +validated(name, Regex("[a-z][a-z0-9_]{0,63}")) } }, numeric(counts.count(name)))
        })
    }
}

private fun FlowContent.healthSection(health: JsonObject, today: String) = panel("health", "Health, last 7 days") {
    p("muted") { date(LocalDate.parse(today).minusDays(6).toString()); +" through "; date(today); +" UTC" }
    val fleet = health.rows("fleet")
    if (fleet.isEmpty()) emptyState("No fleet health reports in the last 7 UTC days.")
    else dataTable("Fleet health · last 7 UTC days", listOf("Day", "Platform", "Version", "Reporting installs", "Admitted", "UNKNOWN", "Σ rule counts"), fleet.map { row ->
        listOf(
            cell { date(row.text("day")) }, cell { +platform(row.text("platform")) }, cell { code { +version(row.text("platformAppVersion")) } },
            numeric(row.count("installsReporting")), numeric(row.count("admitted")), numeric(row.count("unknown")),
            blockCell(num = true) {
                val rules = row.obj("ruleCounts")
                +number(rules.values.sumOf { it.jsonPrimitive.long })
                if (rules.isNotEmpty()) details {
                    summary { +"Rule counts" }
                    ul { rules.toSortedMap().forEach { (id, value) -> li { code { +rule(id) }; +": ${number(value.jsonPrimitive.long)}" } } }
                }
            },
        )
    })
    p("muted") { +"Reporting installs are counted per row. Σ rule counts sums rule matches; it is not a distinct-install count." }
    val installs = health.rows("installs")
    details {
        summary { +"Per-install health · ${number(installs.size)} rows" }
        if (installs.isEmpty()) emptyState("No per-install health reports in this window.")
        else dataTable("Per-install health · last 7 UTC days", listOf("Install", "Day", "Platform", "Version", "Admitted", "UNKNOWN", "Trips"), installs.map { row ->
            listOf(
                cell { code { +prefix(row.text("installIdPrefix")) } }, cell { date(row.text("day")) },
                cell { +platform(row.text("platform")) }, cell { code { +version(row.text("version")) } },
                numeric(row.count("admitted")), numeric(row.count("unknown")),
                cell(num = true) { val trips = row.count("trips"); if (trips > 0) chip(number(trips), "warn") else +number(trips) },
            )
        })
    }
}

private fun FlowContent.ledgerSection(ledger: JsonObject, today: String) = panel("ledger", "Ledger today", headingDate = today) {
    val totals = ledger.obj("totals")
    dl("totals") {
        fact("Bytes") { +"${number(totals.count("bytes"))} B" }
        fact("Accepted") { +number(totals.count("accepted")) }
        fact("Duplicate") { +number(totals.count("duplicate")) }
        fact("Rejected") { rejections(totals.obj("rejected")) }
        fact("Batches") { +number(totals.count("batches")) }
    }
    val installs = ledger.rows("installs")
    if (installs.isEmpty()) emptyState("No ingest ledger entries today.")
    details {
        summary { +"Per-install ledger · ${number(installs.size)} rows" }
        if (installs.isNotEmpty()) dataTable("Per-install ingest ledger · today", listOf("Install", "Bytes", "Accepted", "Duplicate", "Rejected", "Batches"), installs.map { row ->
            listOf(
                cell { code { +prefix(row.text("installIdPrefix")) } }, cell(num = true) { +"${number(row.count("bytes"))} B" },
                numeric(row.count("accepted")), numeric(row.count("duplicate")), blockCell(num = true) { rejections(row.obj("rejected")) }, numeric(row.count("batches")),
            )
        })
    }
}

private fun FlowContent.rejections(reasons: JsonObject) {
    val total = reasons.values.sumOf { it.jsonPrimitive.long }
    if (total > 0) chip(number(total), "warn") else +number(total)
    if (reasons.isEmpty()) +" · No rejections" else ul {
        reasons.toSortedMap().forEach { (reason, count) -> li { +"${safe(reason)}: ${number(count.jsonPrimitive.long)}" } }
    }
}

/** Columns are explicitly mapped by callers; JSON keys never define the HTML surface. */
// kotlinx.html 0.12 types TH as inline-only and TD as block: a row-header cell renders PHRASING content, a body
// cell may render block content (lists, disclosures) through [blockCell].
private class DisplayCell(val num: Boolean, val inline: (FlowOrPhrasingContent.() -> Unit)? = null, val block: (FlowContent.() -> Unit)? = null)
private fun cell(num: Boolean = false, render: FlowOrPhrasingContent.() -> Unit) = DisplayCell(num, inline = render)
private fun blockCell(num: Boolean = false, render: FlowContent.() -> Unit) = DisplayCell(num, block = render)
private fun numeric(value: Number) = cell(num = true) { +number(value) }
private fun FlowContent.dataTable(description: String, columns: List<String>, rows: List<List<DisplayCell>>) {
    table("data-table") {
        attributes["role"] = "table"
        caption { +description }
        thead {
            attributes["role"] = "rowgroup"
            tr { attributes["role"] = "row"; columns.forEach { th { attributes["scope"] = "col"; attributes["role"] = "columnheader"; +it } } }
        }
        tbody {
            attributes["role"] = "rowgroup"
            rows.forEach { cells -> tr {
                attributes["role"] = "row"
                cells.forEachIndexed { index, cell ->
                    if (index == 0) th {
                        attributes["scope"] = "row"; attributes["role"] = "rowheader"
                        span("cell-label") { attributes["aria-hidden"] = "true"; +columns[index] }
                        span("cell-value") { requireNotNull(cell.inline) { "row header cells render inline content" }.invoke(this) }
                    } else td(classes = if (cell.num) "num" else null) {
                        attributes["role"] = "cell"
                        span("cell-label") { attributes["aria-hidden"] = "true"; +columns[index] }
                        cell.inline?.let { render -> span("cell-value") { render(this) } }
                        cell.block?.let { render -> div("cell-value") { render(this) } }
                    }
                }
            } }
        }
    }
}

internal fun FlowOrPhrasingContent.chip(label: String, tone: String) { span("chip $tone") { +label } }
internal fun FlowOrPhrasingContent.yesChip(value: Boolean, tone: String) { if (value) chip("Yes", tone) else +"No" }
internal fun FlowContent.emptyState(copy: String) { p("empty") { +copy } }
internal fun FlowOrPhrasingContent.date(value: String) { time { attributes["datetime"] = safe(value); +safe(value) } }
internal fun number(value: Number): String = String.format(Locale.ROOT, "%,d", value.toLong())
internal val fingerprintPattern = WireGrammars.fingerprint
internal fun safe(value: String): String = value
    .replace(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "[redacted]")
    .replace(Regex("(?i)[0-9a-f]{16,}"), "[redacted]")
    .replace(Regex("(?i)[0-9a-f]{4}(-[0-9a-f]{4}){3}"), "[redacted]")
internal fun safeAttribute(value: String): String = if (
    Regex("(?i)[0-9a-f]{12,}").containsMatchIn(value.replace("-", "").replace(" ", ""))
) "[redacted]" else safe(value)
internal fun validated(value: String, choices: Collection<String>): String = if (value in choices) safe(value) else "[redacted]"
internal fun version(value: String): String = safe(value.substringBefore('+')) + if ('+' in value) "+[build withheld]" else ""
private fun validated(value: String, pattern: Regex): String = if (pattern.matches(value)) safe(value) else "[redacted]"
internal fun platform(value: String): String = validated(value, WireGrammars.platform)
internal fun prefix(value: String): String = validated(value, Regex("[a-fA-F0-9]{8}"))
internal fun rule(value: String): String = validated(value, WireGrammars.ruleId)
private fun JsonObject.rows(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().map { it.jsonObject }
private fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.optionalText(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.text(key: String): String = optionalText(key) ?: "Not recorded"
private fun JsonObject.count(key: String): Long = (get(key) as? JsonPrimitive)?.long ?: 0L
private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.boolean ?: false
