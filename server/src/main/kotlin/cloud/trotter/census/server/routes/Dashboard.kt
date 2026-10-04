package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.OpsStore
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.ops.ClusterDetailHtml
import cloud.trotter.census.server.ops.ClusterFilter
import cloud.trotter.census.server.ops.ClustersPageHtml
import cloud.trotter.census.server.ops.DashboardHtml
import cloud.trotter.census.server.ops.fingerprintPattern
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
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        // A malformed percent-escape never reaches here: routing decodes the query first and the module's
        // bad-request handler answers the HTML shell for ops pages.
        val parameters = call.request.queryParameters
        val filter = ClusterFilter.parse(parameters["platform"], parameters["version"], parameters["status"], parameters["page"])
        val today = clock.today().toString()
        if (filter == null) {
            call.respondText(ClustersPageHtml.renderInvalid(policy.serverVersion, policy.k, today), ContentType.Text.Html, HttpStatusCode.BadRequest)
        } else {
            val result = store.clustersPage(filter.platform, filter.version, filter.status, filter.page)
            call.respondText(ClustersPageHtml.render(policy.serverVersion, policy.k, today, result), ContentType.Text.Html)
        }
    }
    get("/clusters/{fingerprint}/view") {
        val fingerprint = call.parameters["fingerprint"]
        val cluster = if (fingerprint != null && fingerprintPattern.matches(fingerprint)) store.cluster(fingerprint, withWireframe = true) else null
        val html = ClusterDetailHtml.render(policy.serverVersion, policy.k, clock.today().toString(), cluster)
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(html, ContentType.Text.Html, if (cluster == null) HttpStatusCode.NotFound else HttpStatusCode.OK)
    }
}
