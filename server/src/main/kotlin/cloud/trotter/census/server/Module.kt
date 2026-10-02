package cloud.trotter.census.server

import cloud.trotter.census.server.auth.AuthenticatedInstallKey
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.SkeletonStore
import cloud.trotter.census.server.routes.healthRoutes
import cloud.trotter.census.server.routes.identityRoutes
import cloud.trotter.census.server.routes.policyRoutes
import cloud.trotter.census.server.routes.skeletonRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Wires public and identity endpoints, with a clock seam for UTC decisions. */
fun Application.module(config: Config, db: Database?, clock: Clock = SystemClock) {
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
            // Only known paths: unknown paths can contain user-supplied secrets.
            val path = call.request.path().takeIf { it in LOGGABLE_PATHS } ?: "<unmatched>"
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
            val prefix = call.attributes.getOrNull(AuthenticatedInstallKey)?.prefix ?: "-"
            "method=$method " +
                "path=$path status=${call.response.status()?.value ?: 0} " +
                "duration_ms=${call.processingTimeMillis()} install_prefix=$prefix"
        }
    }
    install(RateLimit) {
        register(RateLimitName("enrol")) {
            rateLimiter(limit = 120, refillPeriod = 1.hours)
            requestKey { "global" }
        }
        register(RateLimitName("install")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call -> call.attributes.getOrNull(AuthenticatedInstallKey)?.id ?: "anonymous" }
        }
        register(RateLimitName("nonce")) {
            rateLimiter(limit = 30, refillPeriod = 1.hours)
            requestKey { call -> call.attributes.getOrNull(AuthenticatedInstallKey)?.id ?: "anonymous" }
        }
    }
    install(StatusPages) {
        status(HttpStatusCode.TooManyRequests) { status ->
            // RateLimit sends an empty status; preserve explicit errors such as budget_exhausted.
            if (content is OutgoingContent.NoContent) call.respond(status, ErrorResponse("rate_limited"))
        }
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
        val policy = Policy(serverVersion = config.serverVersion, imageDigest = config.imageDigest)
        policyRoutes(policy)
        val store = db?.let { InstallStore(it, clock) }
        val skeletons = db?.let { SkeletonStore(it, clock) }
        identityRoutes(store, clock, policy) {
            if (store != null && skeletons != null) skeletonRoutes(store, skeletons, clock, policy)
        }
    }
}

private val LOGGABLE_PATHS = setOf(
    "/healthz", "/readyz", "/v1/policy", "/v1/enroll", "/v1/rotate", "/v1/nonce", "/v1/installs/me", "/v1/me", "/v1/skeletons",
)

@Serializable
data class ErrorResponse(val error: String)
