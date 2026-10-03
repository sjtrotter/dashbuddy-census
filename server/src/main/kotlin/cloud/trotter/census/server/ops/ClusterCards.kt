package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.db.OpsCluster
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.strong
import kotlinx.html.ul
import java.util.Locale

internal fun FlowContent.clusterCards(clusters: List<OpsCluster>, k: Int, showGroupFacts: Boolean = true) {
    val labels = linkedMapOf<String, String>()
    for (cluster in clusters) article("cluster-card") {
        val label = labels.getOrPut(cluster.fingerprint) { "Cluster ${String.format(Locale.ROOT, "%02d", labels.size + 1)}" }
        div("cluster-heading") {
            h2 {
                if (fingerprintPattern.matches(cluster.fingerprint)) a(href = "/ops/clusters/${cluster.fingerprint}/view", classes = "cluster-link") { +label }
                else +label
            }
            statusChip(cluster.status)
        }
        clusterFacts(cluster, showGroupFacts = showGroupFacts)
        p("muted") { +visibility(cluster, k) }
    }
}

internal fun FlowContent.clusterFacts(cluster: OpsCluster, detail: Boolean = false, showGroupFacts: Boolean = true) {
    dl("facts") {
        if (showGroupFacts) fact("Platform") { +platform(cluster.platform) }
        fact("Non-trusted installs · 28 d") { strong { +number(cluster.distinctInstalls28d) } }
        fact("Sightings · 28 d") { strong { +number(cluster.sightings28d) } }
        fact("First seen") { date(cluster.firstSeenDay) }
        fact("Last seen") { date(cluster.lastSeenDay) }
        fact("Seen by trusted") { +if (cluster.seenByTrusted) "Yes" else "No" }
        if (showGroupFacts) fact("Versions") {
            if (cluster.versions.isEmpty()) +"None recorded" else {
                +"${number(cluster.versions.size)} ${if (cluster.versions.size == 1) "version" else "versions"}"
                ul("versions") { cluster.versions.forEach { li { code { +version(it) } } } }
            }
        }
        fact(if (detail) "New with newest observed version" else "New with this version") { yesChip(cluster.newWithVersion, "accent") }
        fact("Label visibility") { chip(if (cluster.unblinded) "Visible" else "Redacted", "neutral") }
        fact("Resolved rule") { cluster.resolvedRuleId?.let { code { +rule(it) } } ?: run { +"None" } }
        fact("Notes") {
            +when {
                cluster.notesWithheld -> "Withheld below privacy gate"
                !cluster.notes.isNullOrEmpty() -> "Present · see detail"
                else -> "None"
            }
        }
    }
}

internal fun visibility(cluster: OpsCluster, k: Int): String = when {
    !cluster.unblinded -> "Labels redacted: ${number(cluster.distinctInstalls28d)} / ${number(k)} non-trusted installs in 28 days; no trusted sighting."
    cluster.seenByTrusted -> "Labels visible: trusted sighting."
    else -> "Labels visible: ${number(cluster.distinctInstalls28d)} ≥ ${number(k)} non-trusted installs in 28 days."
}

internal fun FlowContent.statusChip(status: String) {
    val label = if (status in CLUSTER_STATUSES) status.replaceFirstChar { it.uppercase() } else "[redacted]"
    val tones = mapOf("new" to "warn", "triaged" to "accent", "drafted" to "accent", "resolved" to "good", "ignored" to "neutral")
    chip(label, tones[status] ?: "neutral")
}
