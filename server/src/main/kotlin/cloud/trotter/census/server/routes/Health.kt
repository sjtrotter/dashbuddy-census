package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Database
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Liveness is independent of PostgreSQL; readiness probes it on the IO dispatcher (#1157 S1). */
fun Route.healthRoutes(db: Database?) {
    get("/healthz") {
        call.respond(HealthResponse("ok"))
    }
    get("/readyz") {
        val ready = withContext(Dispatchers.IO) {
            try {
                db?.checkReady() == true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                false
            }
        }
        call.respond(
            if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(if (ready) "ok" else "db_unavailable"),
        )
    }
}

@Serializable
private data class HealthResponse(val status: String)
