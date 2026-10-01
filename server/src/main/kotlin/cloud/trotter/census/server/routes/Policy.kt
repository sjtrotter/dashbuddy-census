package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Policy
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Publishes cacheable policy without exposing runtime credentials (#1157 S1). */
fun Route.policyRoutes(policy: Policy) {
    get("/v1/policy") {
        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=300")
        call.respond(policy)
    }
}
