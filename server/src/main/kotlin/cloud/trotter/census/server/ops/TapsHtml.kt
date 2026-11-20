package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.OpsTapDetail
import cloud.trotter.census.server.db.OpsTapScreen
import cloud.trotter.census.server.db.OpsTapTarget
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.code
import kotlinx.html.h1
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span

/**
 * The tapped buttons (#87): `/ops/taps` lists the screens with clicked samples, `/ops/taps/view?fingerprint=…` one
 * screen's targets (owner path, the owner node's class, id and flags, support). Read-only. Owner labels come from the
 * gated projection ([SkeletonRender]) only, never stored skeleton JSON, and a layout shows by its first 8 hex.
 */
object TapsHtml {
    fun renderList(serverVersion: String, k: Int, today: String, screens: List<OpsTapScreen>): String = opsPage(
        pageTitle = "Taps · Census operator",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/#clusters", classes = "action") { +"Back to the dashboard" }
        },
    ) {
        h1 { +"Taps" }
        p("muted") {
            +("Buttons a dasher tapped on a screen the app did not recognize, as a position in the screen's layout, " +
                "never its words. Installs are the most non-trusted installs one button was tapped by within one epoch in 28 days.")
        }
        if (screens.isEmpty()) emptyState("No clicked samples retained.")
        else dataTable(
            "Screens with tapped buttons",
            listOf("Screen", "Platform", "Layouts", "Buttons", "Versions", "Installs", "Newest sample"),
            screens.map { row ->
                listOf(
                    cell {
                        if (fingerprintPattern.matches(row.fingerprint)) {
                            a(href = "/ops/taps/view?fingerprint=${row.fingerprint}") { code { +row.fingerprint.take(8) } }
                        } else code { +"[redacted]" }
                    },
                    cell { +platform(row.platform) },
                    numeric(row.layouts),
                    numeric(row.targets),
                    cell { +row.versions.joinToString(", ") { version(it) }.ifEmpty { "None recorded" } },
                    numeric(row.installs),
                    cell { date(row.newestReceivedDay) },
                )
            },
        )
    }

    fun render(serverVersion: String, k: Int, today: String, detail: OpsTapDetail?): String = opsPage(
        pageTitle = "Taps on one screen · Census operator",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/taps", classes = "action") { +"Back to taps" }
        },
    ) {
        if (detail == null) {
            section("panel") {
                id = "not-found"
                attributes["aria-labelledby"] = "not-found-heading"
                h1 { id = "not-found-heading"; +"Screen not found" }
                p { +"No clicked samples are retained for this screen." }
            }
        } else screenBody(detail, k)
    }

    /** The shell for a malformed fingerprint; echoes nothing from the request. */
    fun renderInvalid(serverVersion: String, k: Int, today: String): String = opsPage(
        pageTitle = "Invalid screen",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/taps", classes = "action") { +"Back to taps" }
        },
    ) { h1 { +"Invalid screen" } }

    private fun FlowContent.screenBody(detail: OpsTapDetail, k: Int) {
        val prefix = detail.fingerprint.take(8).takeIf { fingerprintPattern.matches(detail.fingerprint) } ?: "[redacted]"
        section("panel") {
            id = "screen"
            attributes["aria-labelledby"] = "screen-heading"
            h1 { id = "screen-heading"; +"Taps on screen "; code { +prefix } }
            p("muted") {
                +("Platform ${platform(detail.platform)} · ${number(detail.targets.size)} " +
                    "${if (detail.targets.size == 1) "button" else "buttons"}. A button is one owner path in one raw layout.")
            }
            if (detail.cluster && fingerprintPattern.matches(detail.fingerprint)) {
                p { a(href = "/ops/clusters/${detail.fingerprint}/view") { +"Cluster $prefix" } }
            }
        }
        val layouts = detail.targets.map { it.layout }.distinct()
        panel("targets", "Buttons") {
            dataTable(
                "Tapped buttons by layout, then owner path",
                listOf("Owner path", "Layout", "Owner node", "Installs", "Labels", "Versions", "Newest sample"),
                detail.targets.map { target -> row(target, layouts.indexOf(target.layout) + 1, k) },
            )
        }
    }

    private fun row(target: OpsTapTarget, layoutNumber: Int, k: Int): List<DisplayCell> = listOf(
        cell { code { +safe(target.ownerPath) } },
        cell { +"Layout $layoutNumber · "; code { +safe(target.layout.take(8)) } },
        blockCell {
            val owner = target.owner
            if (owner == null) +"Not in the sample" else {
                with(ClusterDetailHtml) {
                    nodeField("Class", owner.className)
                    nodeField("ID", owner.id)
                }
                if (owner.clickable) chip("clickable", "neutral")
                if (owner.clickAction) chip("click action", "neutral")
                if (owner.hidden) chip("hidden", "neutral")
            }
        },
        numeric(target.installs28d),
        cell {
            span {
                +when {
                    !target.unblinded -> "Redacted: ${number(target.installs28d)} / ${number(k)} non-trusted installs; no trusted sighting."
                    target.seenByTrusted -> "Visible: trusted sighting."
                    else -> "Visible: ${number(target.installs28d)} ≥ ${number(k)} non-trusted installs."
                }
            }
        },
        cell { +target.versions.joinToString(", ") { version(it) }.ifEmpty { "None recorded" } },
        cell { date(target.newestReceivedDay) },
    )
}
