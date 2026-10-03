package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.db.OpsStore
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.ops.ClusterDetailHtml
import cloud.trotter.census.server.ops.ClustersPageHtml
import cloud.trotter.census.server.ops.DashboardHtml
import cloud.trotter.census.server.ops.fingerprintPattern
import cloud.trotter.census.server.ops.platformPattern
import cloud.trotter.census.server.today
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Mounted only inside the operator authentication gate. Only logout changes state; no secrets or client-side code. */
fun Route.dashboardRoute(store: OpsStore, alarms: HealthAlarms, clock: Clock, policy: Policy) {
    get("/") {
        val html = DashboardHtml.render(
            policy.serverVersion, policy.k, clock.today().toString(), opsAlarms(alarms),
            store.clusterSummary(), store.health(), store.installs(), store.ledger(), store.vocabularyQueueCount(), store.vocabularyQueueDisplay(),
        )
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(html, ContentType.Text.Html)
    }
    get("/clusters/view") {
        val platform = call.request.queryParameters["platform"]
        val version = call.request.queryParameters["version"]
        val status = call.request.queryParameters["status"]
        val pageInput = call.request.queryParameters["page"]
        val page = if (pageInput == null) 1 else pageInput.toIntOrNull()
        val today = clock.today().toString()
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        if (platform == null || !platformPattern.matches(platform) ||
            version == null || (version != "none" && !WireGrammars.platformAppVersion.matches(version)) ||
            (status != null && status !in CLUSTER_STATUSES) || page == null || page !in 1..10_000
        ) {
            call.respondText(ClustersPageHtml.renderInvalid(policy.serverVersion, policy.k, today), ContentType.Text.Html, HttpStatusCode.BadRequest)
        } else {
            val result = store.clustersPage(platform, version.takeUnless { it == "none" }, status, page)
            call.respondText(ClustersPageHtml.render(policy.serverVersion, policy.k, today, result), ContentType.Text.Html)
        }
    }
    get("/clusters/{fingerprint}/view") {
        val fingerprint = call.parameters["fingerprint"]
        val cluster = if (fingerprint != null && fingerprintPattern.matches(fingerprint)) store.cluster(fingerprint) else null
        val html = ClusterDetailHtml.render(policy.serverVersion, policy.k, clock.today().toString(), cluster)
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(html, ContentType.Text.Html, if (cluster == null) HttpStatusCode.NotFound else HttpStatusCode.OK)
    }
}
