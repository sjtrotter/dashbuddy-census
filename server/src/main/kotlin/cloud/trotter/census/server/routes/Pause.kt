package cloud.trotter.census.server.routes

import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.auth.InstallRowKey
import cloud.trotter.census.server.db.PauseStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** How long a paused install is told to wait before retrying (#79). */
internal const val PAUSE_RETRY_AFTER_SECONDS: Long = 3600

/**
 * #79: while the operator's pause switch is on, an upload from a non-trusted install gets `503 paused` with
 * `Retry-After`, a refusal the client retries later. Answers true when it refused. Trusted installs are never paused.
 */
internal suspend fun ApplicationCall.refusedByPause(pause: PauseStore): Boolean {
    if (attributes[InstallRowKey].trusted || !pause.paused()) return false
    response.headers.append(HttpHeaders.RetryAfter, PAUSE_RETRY_AFTER_SECONDS.toString())
    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("paused"))
    return true
}
