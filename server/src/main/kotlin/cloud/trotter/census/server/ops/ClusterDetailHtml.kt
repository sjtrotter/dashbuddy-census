package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.OpsCluster
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.figcaption
import kotlinx.html.figure
import kotlinx.html.h1
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.ul
import java.util.Locale

/** Samples consume only the gated projection, never stored skeleton JSON. */
object ClusterDetailHtml {
    fun render(serverVersion: String, k: Int, today: String, cluster: OpsCluster?): String = opsPage(
        pageTitle = "Cluster detail · Census operator",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/#clusters", classes = "action") { +"Back to clusters" }
        },
    ) {
        if (cluster == null) {
            section("panel") {
                id = "not-found"
                attributes["aria-labelledby"] = "not-found-heading"
                h1 { id = "not-found-heading"; +"Cluster not found" }
                a(href = "/ops/#clusters", classes = "action") { +"Back to clusters" }
            }
        } else {
            section("panel") {
                id = "cluster"
                attributes["aria-labelledby"] = "cluster-heading"
                h1 { id = "cluster-heading"; +"Cluster detail" }
                statusChip(cluster.status)
                classChip(cluster.screenClass)
                cluster.draftDay?.let { day -> p { +"Draft saved "; date(day) } }
                clusterFacts(cluster, detail = true)
                p("notice") { +visibility(cluster, k) }
            }
            panel("notes", "Notes") {
                when {
                    cluster.notesWithheld -> p { +"Notes withheld below the privacy gate." }
                    !cluster.notes.isNullOrEmpty() -> p("notes") { +safe(cluster.notes) }
                    else -> p { +"No operator notes." }
                }
            }
            panel("wireframe", "Screen wireframe") {
                val wireframe = cluster.wireframe
                if (wireframe == null) {
                    emptyState("No trusted capture is paired with this cluster yet. A wireframe appears once a trusted install uploads a capture that names this cluster.")
                } else {
                    figure {
                        figcaption("muted") {
                            +"Trusted capture · received "; date(wireframe.receivedDay)
                            +" · install ${prefix(wireframe.installPrefix)} · app version "
                            val appVersion = wireframe.platformAppVersion
                            if (appVersion == null) +"Not recorded" else code { +version(appVersion) }
                            +" · ${number(wireframe.boxes.size)} of ${number(wireframe.nodeCount)} nodes drawn"
                            if (wireframe.frameFallback) +" · default frame"
                        }
                        // The only inline style attributes on ops pages: numeric geometry only.
                        // The existing CSP permits these via style-src 'unsafe-inline'.
                        div("wire-frame") {
                            attributes["style"] = "aspect-ratio:${wireframe.frameWidth}/${wireframe.frameHeight}"
                            attributes["role"] = "group"
                            attributes["aria-label"] = "Wireframe: ${number(wireframe.boxes.size)} of ${number(wireframe.nodeCount)} nodes drawn"
                            wireframe.boxes.forEach { box ->
                                val classes = "wire-box" + (if (box.label != null) " labelled" else "") +
                                    (if (box.clickable) " clickable" else "")
                                div(classes) {
                                    attributes["style"] = String.format(Locale.ROOT,
                                        "left:%.2f%%;top:%.2f%%;width:%.2f%%;height:%.2f%%",
                                        box.leftPct, box.topPct, box.widthPct, box.heightPct)
                                    attributes["title"] = "${safe(box.className ?: "view")} · ${safe(box.viewId ?: "no id")}"
                                    box.label?.let { span("wire-label") { +safe(it) } }
                                }
                            }
                        }
                        details {
                            summary { +"Nodes as text" }
                            ul("wire-list") {
                                wireframe.boxes.forEach { box ->
                                    li {
                                        +"— ".repeat(box.depth)
                                        +safe(box.label ?: "(no label)")
                                        +" · "; +safe(box.className ?: "view")
                                        +" · "; +safe(box.viewId ?: "no id")
                                        if (box.clickable) +" · Clickable"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            panel("samples", "Skeleton samples") {
                p("muted") { +"Dashed labels are privacy-redacted class or ID values. Text slots show kind only." }
                if (cluster.samples.isNullOrEmpty()) emptyState("No skeleton samples retained for this cluster.")
                cluster.samples.orEmpty().forEachIndexed { index, sample ->
                    article("sample") {
                        h3 { +"Version ${version(sample.platformAppVersion)}" }
                        p { +"Received "; date(sample.receivedDay); +" UTC" }
                        div("tree-wrap") {
                            attributes["role"] = "region"
                            attributes["aria-label"] = "Skeleton sample ${index + 1}"
                            attributes["tabindex"] = "0"
                            ul("tree") { li { tree(sample.skeleton) } }
                        }
                    }
                }
            }
            if (fingerprintPattern.matches(cluster.fingerprint)) {
                a(href = "/ops/clusters/${cluster.fingerprint}/draft", classes = "action") { +"Classify & draft" }
                if (cluster.hasDraft) a(href = "/ops/clusters/${cluster.fingerprint}/draft.json5", classes = "action") { +"Draft (JSON5)" }
                a(href = "/ops/clusters/${cluster.fingerprint}", classes = "action") { +"Cluster JSON" }
            }
        }
    }

    private fun FlowContent.tree(node: RenderedSkeleton) {
        div("node-line") {
            nodeField("Class", node.className)
            nodeField("ID", node.id)
            if (node.kinds.isEmpty()) span("muted") { +"No text slots." }
            node.kinds.forEach { chip("kind: ${safe(it)}", "neutral") }
        }
        if (node.children.isNotEmpty()) ul { node.children.forEach { li { tree(it) } } }
    }

    private fun FlowContent.nodeField(label: String, value: String?) {
        span("node-field") {
            +"$label "
            if (value == null) +"Not supplied" else {
                val redacted = Regex("~[0-9a-f]{8}").matches(value)
                code(classes = if (redacted) "redacted" else null) {
                    if (redacted) attributes["aria-label"] = "Redacted ${if (label == "Class") "class" else "ID"}, $value"
                    +safe(value)
                }
            }
        }
    }
}
