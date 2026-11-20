package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.OpsMatchedLayout
import cloud.trotter.census.server.db.OpsMatchedRuleDetail
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.ul

/**
 * One rule's matched skeletons (#69): grouped by layout, groups ordered by their newest sample, each saying whether a
 * cluster resolved to the rule already holds that layout; then the clusters resolved to the rule (its older layouts).
 * Samples consume only the gated projection ([SkeletonRender]), never stored skeleton JSON.
 */
object RuleDetailHtml {
    fun render(serverVersion: String, k: Int, today: String, detail: OpsMatchedRuleDetail?): String = opsPage(
        pageTitle = "Matched rule · Census operator",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/rules/view", classes = "action") { +"Back to matched rules" }
        },
    ) {
        if (detail == null) {
            section("panel") {
                id = "not-found"
                attributes["aria-labelledby"] = "not-found-heading"
                h1 { id = "not-found-heading"; +"Rule not found" }
                p { +"No matched samples are retained for this rule." }
            }
        } else ruleBody(detail, k)
    }

    /** The shell for a malformed rule id; echoes nothing from the request. */
    fun renderInvalid(serverVersion: String, k: Int, today: String): String = opsPage(
        pageTitle = "Invalid rule",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/rules/view", classes = "action") { +"Back to matched rules" }
        },
    ) { h1 { +"Invalid rule" } }

    private fun FlowContent.ruleBody(detail: OpsMatchedRuleDetail, k: Int) {
        section("panel") {
            id = "rule"
            attributes["aria-labelledby"] = "rule-heading"
            h1 { id = "rule-heading"; +"Matched rule "; code { +rule(detail.ruleId) } }
            p("muted") {
                +("Platform ${platform(detail.platform)} · ${number(detail.layouts.size)} ${if (detail.layouts.size == 1) "layout" else "layouts"}. " +
                    "Screens the rule matched while it was failing to read them on the phone.")
            }
        }
        detail.layouts.forEachIndexed { index, layout -> layoutPanel(index, layout, k) }
        panel("older", "Older layouts") {
            p("muted") { +"Clusters resolved to this rule: the layouts it was drafted from." }
            if (detail.olderLayouts.isEmpty()) emptyState("No cluster is resolved to this rule.")
            else ul {
                detail.olderLayouts.forEach { older ->
                    li {
                        if (fingerprintPattern.matches(older.fingerprint)) {
                            a(href = "/ops/clusters/${older.fingerprint}/view") { +"Cluster ${older.fingerprint.take(8)}" }
                        } else +"Cluster [redacted]"
                        +" · "; statusChip(older.status); +" · last seen "; date(older.lastSeenDay)
                    }
                }
            }
        }
        a(href = "/ops/rules/${rule(detail.ruleId)}", classes = "action") { +"Rule JSON" }
    }

    private fun FlowContent.layoutPanel(index: Int, layout: OpsMatchedLayout, k: Int) {
        val label = "Layout ${index + 1}"
        panel("layout-${index + 1}", label) {
            p {
                val prefix = layout.fingerprint.take(8).takeIf { fingerprintPattern.matches(layout.fingerprint) } ?: "[redacted]"
                if (layout.sameAsCluster) +"Same layout as cluster $prefix" else +"New layout · $prefix"
            }
            p("muted") {
                +("Versions ${layout.versions.joinToString(", ") { version(it) }.ifEmpty { "none recorded" }} · " +
                    "${number(layout.installs28d)} non-trusted installs in 28 days · seen by trusted: ${if (layout.seenByTrusted) "yes" else "no"}. " +
                    when {
                        !layout.unblinded -> "Labels redacted: ${number(layout.installs28d)} / ${number(k)} non-trusted installs; no trusted sighting."
                        layout.seenByTrusted -> "Labels visible: trusted sighting."
                        else -> "Labels visible: ${number(layout.installs28d)} ≥ ${number(k)} non-trusted installs."
                    })
            }
            if (layout.slots.isEmpty()) emptyState("No slot sightings counted for this layout yet.")
            layout.slots.forEach { reading ->
                val variant = reading.variant
                dataTable(
                    "$label · version ${version(variant.platformAppVersion)} · filter revision ${number(variant.filterRev)} · raw layout ${safe(variant.layout.take(8))}",
                    listOf("Slot", "Class", "Installs", "Distinct hashes"),
                    reading.slots.map { slot ->
                        listOf(cell { code { +safe(slot.slot) } }, cell { slotClassChip(slot.slotClass) }, numeric(slot.installs), numeric(slot.hashes))
                    },
                )
            }
            layout.samples.forEachIndexed { position, sample ->
                article("sample") {
                    h3 { +"Version ${version(sample.platformAppVersion)}" }
                    p { +"Received "; date(sample.receivedDay); +" UTC" }
                    sample.skeleton?.let { skeleton -> div("tree-wrap") {
                        attributes["role"] = "region"
                        attributes["aria-label"] = "$label sample ${position + 1}"
                        attributes["tabindex"] = "0"
                        ul("tree") { li { with(ClusterDetailHtml) { tree(skeleton) } } }
                    } }
                }
            }
        }
    }
}
