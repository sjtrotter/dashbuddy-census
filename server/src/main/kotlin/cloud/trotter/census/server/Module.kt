package cloud.trotter.census.server

import cloud.trotter.census.server.routes.healthRoutes
import cloud.trotter.census.server.routes.policyRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/** Wires the public S1 endpoints; a missing database keeps readiness unavailable (#1157 S1). */
fun Application.module(config: Config, db: Database?) {
    install(ContentNegotiation) {
        json(
            Json {
                explicitNulls = false
                ignoreUnknownKeys = false
                encodeDefaults = true
            },
        )
    }
    install(DefaultHeaders) {
        header("Server", "")
    }
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            // Only known public paths: unknown paths can contain user-supplied secrets.
            val path = call.request.path().takeIf { it in PUBLIC_PATHS } ?: "<unmatched>"
            val method = when (call.request.httpMethod.value) {
                "GET" -> "GET"
                "HEAD" -> "HEAD"
                "POST" -> "POST"
                "PUT" -> "PUT"
                "PATCH" -> "PATCH"
                "DELETE" -> "DELETE"
                "OPTIONS" -> "OPTIONS"
                else -> "OTHER"
            }
            val prefix = call.request.headers["X-Install-Id-Prefix"]
                ?.takeIf { it.length in 1..8 && it.all(Char::isHexDigit) }
                ?.lowercase() ?: "-"
            "method=$method " +
                "path=$path status=${call.response.status()?.value ?: 0} " +
                "duration_ms=${call.processingTimeMillis()} install_prefix=$prefix"
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
        }
        exception<Throwable> { call, failure ->
            if (failure is CancellationException) throw failure
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ErrorResponse(if (status == HttpStatusCode.NotFound) "not_found" else "method_not_allowed"))
        }
    }
    routing {
        healthRoutes(db)
        policyRoutes(Policy(serverVersion = config.serverVersion, imageDigest = config.imageDigest))
    }
}

private val PUBLIC_PATHS = setOf("/healthz", "/readyz", "/v1/policy")

@Serializable
private data class ErrorResponse(val error: String)
