package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.db.OpsClusterPage
import cloud.trotter.census.server.ingest.WireGrammars
import kotlinx.html.a
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.summary
import java.util.Locale

object ClustersPageHtml {
    fun render(serverVersion: String, k: Int, today: String, page: OpsClusterPage): String = opsPage(
        pageTitle = "Clusters · Census operator",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/#clusters", classes = "action") { +"Back to the dashboard" }
        },
    ) {
        h1 {
            +"Clusters · ${platform(page.platform)} · "
            +if (page.platformAppVersion == null) "No version recorded" else "Version ${version(page.platformAppVersion)}"
        }
        p("muted") { +"${number(page.total)} clusters · untriaged first, then by rank · page ${number(page.page)} of ${number(page.pageCount)}" }
        nav("status-filter") {
            attributes["aria-label"] = "Status filter"
            (listOf(null) + CLUSTER_STATUSES).forEach { status ->
                val href = clustersPageHref(page.platform, page.platformAppVersion, status)
                val label = status?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "All"
                if (href == null) span { +"Unavailable" } else a(href = href, classes = if (page.status == status) "action current" else "action") {
                    if (page.status == status) attributes["aria-current"] = "page"
                    +safe(label)
                }
            }
        }
        if (page.clusters.isEmpty()) emptyState("No clusters match this filter.")
        else div("cluster-grid") { clusterCards(page.clusters, k, linkedMapOf()) }
        nav("pager") {
            attributes["aria-label"] = "Pages"
            if (page.page > 1) clustersPageHref(page.platform, page.platformAppVersion, page.status, page.page - 1)?.let {
                a(href = it, classes = "action") { +"Previous" }
            }
            span { +"Page ${number(page.page)} of ${number(page.pageCount)}" }
            if (page.page < page.pageCount) clustersPageHref(page.platform, page.platformAppVersion, page.status, page.page + 1)?.let {
                a(href = it, classes = "action") { +"Next" }
            }
        }
        details {
            summary { +"How ranking works" }
            p { +"distinctInstalls28d × log2(1 + sightings28d) × recency" }
            p("muted") { +"Recency is 1.0 within 7 days, 0.5 within 28 days, and 0.1 otherwise." }
        }
    }

    fun renderInvalid(serverVersion: String, k: Int, today: String): String = opsPage(
        pageTitle = "Invalid cluster filter",
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/#clusters", classes = "action") { +"Back to the dashboard" }
        },
    ) {
        h1 { +"Invalid cluster filter" }
    }
}

/** Every token is checked here even when supplied by an already-validated route. */
internal fun clustersPageHref(platform: String, version: String?, status: String? = null, page: Int? = null): String? {
    if (!platformPattern.matches(platform) || (version != null && !WireGrammars.platformAppVersion.matches(version)) ||
        (status != null && status !in CLUSTER_STATUSES) || (page != null && page !in 1..10_000)
    ) return null
    return "/ops/clusters/view?platform=$platform&version=${version ?: "none"}" +
        (status?.let { "&status=$it" } ?: "") + (page?.let { "&page=$it" } ?: "")
}
