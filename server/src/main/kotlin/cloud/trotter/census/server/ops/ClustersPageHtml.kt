package cloud.trotter.census.server.ops

import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.db.OpsClusterPage
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
        val filter = ClusterFilter.parse(page.platform, page.platformAppVersion ?: "none", page.status, page.page.toString())
        h1 {
            +"Clusters · ${platform(page.platform)} · "
            +if (page.platformAppVersion == null) "No version recorded" else "Version ${version(page.platformAppVersion)}"
        }
        p("muted") { +"${number(page.total)} clusters · ${if (page.status == null) "untriaged first, then by rank" else "by rank"}" }
        p("muted") { +"Classes · ${classSummary(page.byClass)}" }
        nav("status-filter") {
            attributes["aria-label"] = "Status filter"
            (listOf(null) + CLUSTER_STATUSES).forEach { status ->
                val href = filter?.href(status = status)
                val label = status?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: "All"
                if (href == null) span { +"Unavailable" } else a(href = href, classes = if (page.status == status) "action current" else "action") {
                    if (page.status == status) attributes["aria-current"] = "page"
                    +safe(label)
                }
            }
        }
        if (page.clusters.isEmpty()) emptyState("No clusters match this filter.")
        else div("cluster-grid") { clusterCards(page.clusters, k, showGroupFacts = false) }
        if (page.pageCount > 1) nav("pager") {
            attributes["aria-label"] = "Pages"
            if (page.page > 1) filter?.href(page = page.page - 1)?.let {
                a(href = it, classes = "action") { +"Previous" }
            }
            span { +"Page ${number(page.page)} of ${number(page.pageCount)}" }
            if (page.page < page.pageCount) filter?.href(page = page.page + 1)?.let {
                a(href = it, classes = "action") { +"Next" }
            }
        }
        details {
            summary { +"How ranking works" }
            p { +"distinctInstalls28d × log2(1 + sightings28d) × recency" }
            p("muted") { +"Recency is 1.0 within 7 days, 0.5 within 28 days, and 0.1 otherwise." }
        }
    }

    fun renderInvalid(serverVersion: String, k: Int, today: String): String = errorPage("Invalid cluster filter", serverVersion, k, today)

    /**
     * The shell for a request Ktor refused before any dashboard route ran (a malformed percent-escape in the query is
     * decoded by routing itself). Echoes nothing from the request.
     */
    fun renderBadRequest(serverVersion: String, k: Int, today: String): String = errorPage("Invalid request", serverVersion, k, today)

    private fun errorPage(title: String, serverVersion: String, k: Int, today: String): String = opsPage(
        pageTitle = title,
        headerContent = {
            metadata(serverVersion, k, today)
            a(href = "/ops/#clusters", classes = "action") { +"Back to the dashboard" }
        },
    ) {
        h1 { +title }
    }
}
