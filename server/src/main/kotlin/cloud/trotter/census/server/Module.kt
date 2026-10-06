package cloud.trotter.census.server

import cloud.trotter.census.server.auth.AuthenticatedInstallKey
import cloud.trotter.census.server.db.EnvelopeStore
import cloud.trotter.census.server.db.HealthStore
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.OpsStore
import cloud.trotter.census.server.db.SkeletonStore
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.ops.ClustersPageHtml
import cloud.trotter.census.server.ops.OpsLogging
import cloud.trotter.census.server.ops.isOpsHtmlPath
import cloud.trotter.census.server.ops.isOpsPath
import cloud.trotter.census.server.routes.envelopeRoutes
import cloud.trotter.census.server.routes.healthReportRoutes
import cloud.trotter.census.server.routes.healthRoutes
import cloud.trotter.census.server.routes.identityRoutes
import cloud.trotter.census.server.routes.opsRoutes
import cloud.trotter.census.server.routes.policyRoutes
import cloud.trotter.census.server.routes.skeletonRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Wires public and identity endpoints, with a clock seam for UTC decisions. */
fun Application.module(
    config: Config, db: Database?, clock: Clock = SystemClock, alarmEvaluator: HealthAlarms? = null,
    policy: Policy = config.policy(),
    lifecycle: cloud.trotter.census.server.jobs.LifecycleReport? = null,
) {
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
    install(OpsLogging)
    install(CallLogging) {
        filter { call -> !isOpsPath(call.request.path()) }
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
            // Routing decodes the query before any handler runs, so a malformed percent-escape on a browser page
            // lands here: answer the page's own shell (nothing from the request is echoed), JSON everywhere else.
            if (isOpsHtmlPath(call.request.path())) {
                // The route-scoped OpsAuth plugin (which adds no-store) never ran: routing threw first.
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
                call.respondText(
                    ClustersPageHtml.renderBadRequest(policy.serverVersion, policy.k, clock.today().toString()),
                    ContentType.Text.Html, HttpStatusCode.BadRequest,
                )
            } else {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            }
        }
        // A malformed percent-escape in a query, cookie or form raises Ktor's checked URLDecodeException, whose
        // message embeds the whole input (an operator token in a login form): answer 400, never the generic 500.
        exception<io.ktor.http.URLDecodeException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
        }
        exception<io.ktor.http.BadContentTypeFormatException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
        }
        exception<Throwable> { call, failure ->
            if (failure is CancellationException) throw failure
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { status ->
            // The operator dashboard answers an unknown cluster with its own HTML 404 shell; every other
            // not-found/method-not-allowed stays the JSON error envelope.
            if (content.contentType?.withoutParameters() != ContentType.Text.Html) {
                call.respond(status, ErrorResponse(if (status == HttpStatusCode.NotFound) "not_found" else "method_not_allowed"))
            }
        }
    }
    routing {
        healthRoutes(db)
        policyRoutes(policy)
        val store = db?.let { InstallStore(it, clock) }
        val skeletons = db?.let { SkeletonStore(it, clock) }
        val envelopes = db?.let { EnvelopeStore(it, clock) }
        val health = db?.let { HealthStore(it, clock) }
        val alarms = alarmEvaluator ?: health?.let { HealthAlarms(it, clock) }
        opsRoutes(config, db?.let { OpsStore(it, clock, policy) }, alarms, clock, policy, lifecycle = lifecycle)
        identityRoutes(store, clock, policy, alarms) {
            if (store != null && skeletons != null) skeletonRoutes(store, skeletons, clock, policy)
            if (envelopes != null) envelopeRoutes(envelopes, clock, policy)
            if (health != null && alarms != null) healthReportRoutes(health, alarms, clock, policy)
        }
    }
}

private val LOGGABLE_PATHS = setOf(
    "/healthz", "/readyz", "/v1/policy", "/v1/enroll", "/v1/rotate", "/v1/nonce", "/v1/installs/me", "/v1/me", "/v1/skeletons",
    "/v1/envelopes", "/v1/health", "/ops",
)

@Serializable
data class ErrorResponse(val error: String)
