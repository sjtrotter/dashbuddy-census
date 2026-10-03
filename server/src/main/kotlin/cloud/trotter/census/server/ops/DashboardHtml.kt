package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.OpsClusterGroup
import kotlinx.html.FormMethod
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.unsafe
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.ul
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Read-only operator data with a logout control. Only cluster link destinations contain fingerprints. */
object DashboardHtml {
    fun render(
        serverVersion: String,
        k: Int,
        today: String,
        alarms: JsonObject,
        clusters: List<OpsClusterGroup>,
        health: JsonObject,
        installs: JsonArray,
        ledger: JsonObject,
        vocabularyCount: Long,
    ): String = "<!DOCTYPE html>" + createHTML().html {
        head {
            meta { charset = "utf-8" }
            title { +"Census operator" }
            // A constant stylesheet literal (no interpolation) — kotlinx.html requires `unsafe` for <style> content.
            style { unsafe { raw("body{font-family:system-ui;margin:2rem;max-width:90rem}table{border-collapse:collapse}td,th{padding:.4rem;text-align:left;border-bottom:1px solid #ccc}.badge{padding:.15rem .4rem;background:#eee;border-radius:.3rem}li{margin:.4rem 0}") } }
        }
        body {
            form(action = "/ops/logout", method = FormMethod.post) { button { +"Log out" } }
            h1 { +"Census operator" }
            p { +"Server ${safe(serverVersion)} · k=$k · ${safe(today)}" }
            h2 { +"Alarms today" }
            rows(alarms["today"] as? JsonArray ?: JsonArray(emptyList()))
            h2 { +"Ranked clusters" }
            for (group in clusters) {
                h3 { +"Version ${safe(group.platformAppVersion)}" }
                for (cluster in group.clusters) div {
                    val days = ChronoUnit.DAYS.between(LocalDate.parse(cluster.firstSeenDay), LocalDate.parse(cluster.lastSeenDay)) + 1
                    p {
                        +"${safe(cluster.platform)} · ${safe(cluster.status)} · "
                        +"${cluster.distinctInstalls28d} installs · ${cluster.sightings28d} sightings · ${cluster.versions.size} versions · $days days · "
                        +"${safe(cluster.firstSeenDay)} to ${safe(cluster.lastSeenDay)} · "
                        +"trusted=${cluster.seenByTrusted} · unblinded=${cluster.unblinded} "
                        if (cluster.newWithVersion) span("badge") { +"new with version" }
                        if (Regex("[0-9a-f]{64}").matches(cluster.fingerprint)) {
                            a(href = "/ops/clusters/${cluster.fingerprint}") { +"Cluster JSON" }
                        }
                    }
                    for (sample in cluster.samples.orEmpty()) {
                        p { +"${safe(sample.platformAppVersion)} · ${safe(sample.receivedDay)}" }
                        ul { li { tree(sample.skeleton) } }
                    }
                }
            }
            h2 { +"Health, last 7 days" }
            rows(health["fleet"] as? JsonArray ?: JsonArray(emptyList()))
            h2 { +"Installs" }
            rows(installs)
            h2 { +"Ledger today" }
            rows(ledger["installs"] as? JsonArray ?: JsonArray(emptyList()))
            p { +"Totals: ${safe(ledger["totals"]?.toString() ?: "{}")}" }
            h2 { +"Vocabulary queue ($vocabularyCount)" }
        }
    }

    private fun FlowContent.tree(node: RenderedSkeleton) {
        node.className?.let { span { +safe(it) }; +" " }
        node.id?.let { span { +safe(it) }; +" " }
        node.kinds.forEach { span("badge") { +safe(it) }; +" " }
        if (node.children.isNotEmpty()) ul { node.children.forEach { li { tree(it) } } }
    }

    private fun FlowContent.rows(rows: JsonArray) {
        if (rows.isEmpty()) { p { +"None" }; return }
        val objects = rows.mapNotNull { it as? JsonObject }
        val columns = objects.flatMap { it.keys }.distinct()
        table {
            tr { columns.forEach { th { +safe(it) } } }
            objects.forEach { row -> tr { columns.forEach { key -> td { +safe(display(row[key])) } } } }
        }
    }

    private fun display(value: JsonElement?): String = when (value) {
        null -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun safe(value: String): String = value
        .replace(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "[redacted]")
        .replace(Regex("(?i)[0-9a-f]{16,}"), "[redacted]")
}
