package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.OpsStore
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.ops.ClusterDetailHtml
import cloud.trotter.census.server.ops.DashboardHtml
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
            store.clusters(includeSamples = false), store.health(), store.installs(), store.ledger(), store.vocabularyQueueCount(), store.vocabularyQueueDisplay(),
        )
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(html, ContentType.Text.Html)
    }
    get("/clusters/{fingerprint}/view") {
        val fingerprint = call.parameters["fingerprint"]
        val cluster = if (fingerprint != null && Regex("[0-9a-f]{64}").matches(fingerprint)) store.cluster(fingerprint) else null
        val html = ClusterDetailHtml.render(policy.serverVersion, policy.k, clock.today().toString(), cluster)
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(html, ContentType.Text.Html, if (cluster == null) HttpStatusCode.NotFound else HttpStatusCode.OK)
    }
}
