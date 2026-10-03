package cloud.trotter.census.server.routes

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Config
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.auth.InstallId
import cloud.trotter.census.server.auth.readLimitedBody
import cloud.trotter.census.server.db.OpsStore
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.ops.LoginHtml
import cloud.trotter.census.server.ops.OpsAuth
import cloud.trotter.census.server.ops.OpsSessions
import cloud.trotter.census.server.ops.TotpDecision
import cloud.trotter.census.server.ops.TotpReplay
import cloud.trotter.census.server.ops.fingerprintPattern
import cloud.trotter.census.server.ops.operatorTokenMatches
import cloud.trotter.census.server.today
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val opsJson = Json { explicitNulls = false; encodeDefaults = true }
/** The form's bound on the operator token (the bearer header has none); the login page's maxlength mirrors it. */
internal const val MAX_TOKEN_LENGTH = 1024
private val clusterStatuses = setOf("new", "triaged", "drafted", "resolved", "ignored")

fun Route.opsRoutes(
    config: Config,
    store: OpsStore?,
    alarms: HealthAlarms?,
    clock: Clock,
    policy: Policy,
    // Created once at module's route installation; shared by the auth plugin and login handlers.
    sessions: OpsSessions = OpsSessions(),
    replay: TotpReplay = TotpReplay(),
) {
    route("/ops") {
        install(OpsAuth) {
            this.config = config
            this.clock = clock
            this.sessions = sessions
            this.replay = replay
        }
        get("/login") {
            call.loginHeaders()
            if (sessions.validate(call.request.cookies["census_ops"], clock.now())) {
                call.seeOther("/ops/")
            } else {
                call.respondText(LoginHtml.render(), ContentType.Text.Html)
            }
        }
        post("/login") {
            call.loginHeaders()
            val bytes = call.readLimitedBody() ?: return@post
            if (bytes.size > 4096 || call.request.contentType().withoutParameters() != ContentType.Application.FormUrlEncoded) badRequest()
            // application/x-www-form-urlencoded: a browser sends a space as `+` (a literal plus arrives as %2B), and
            // Ktor's parser keeps `+` literal — translate it BEFORE percent-decoding so a token that authenticates
            // as a bearer also authenticates through the form (review, Astra).
            val fields = try {
                bytes.toString(Charsets.UTF_8).replace('+', ' ').parseUrlEncodedParameters()
            } catch (_: IllegalArgumentException) { badRequest() }
            val token = fields["token"] ?: badRequest()
            val code = fields["code"] ?: badRequest()
            if (token.length > MAX_TOKEN_LENGTH || !Regex("[0-9]{6}").matches(code)) badRequest()
            val secret = config.operatorTotpSecret
            if (secret == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("totp_unconfigured"))
                return@post
            }
            val now = clock.now()
            // Check the token FIRST: an incorrect token must never consume a valid second-factor code.
            if (!config.operatorTokenMatches(token) || replay.verify(secret, code, now) != TotpDecision.Accepted) {
                call.respondText(LoginHtml.render(failed = true), ContentType.Text.Html, HttpStatusCode.Unauthorized)
                return@post
            }
            call.opsCookie(sessions.create(now), 43200)
            call.seeOther("/ops/")
        }
        post("/logout") {
            sessions.revoke()
            call.opsCookie("", 0)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.seeOther("/ops/login")
        }
        run {
            if (store == null || alarms == null) {
                route("/{rest...}") { handle { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("db_unavailable")) } }
                return@run
            }
            dashboardRoute(store, alarms, clock, policy)
            get("/clusters") {
                val version = call.request.queryParameters["version"]
                val status = call.request.queryParameters["status"]
                if (version != null && !WireGrammars.platformAppVersion.matches(version) || status != null && status !in clusterStatuses) badRequest()
                call.respond(store.clusters(version, status, call.bound("limit", 50, 200)))
            }
            get("/clusters/{fingerprint}") {
                val row = store.cluster(call.fingerprint())
                if (row == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) else call.respond(row)
            }
            post("/clusters/{fingerprint}/status") {
                val request = call.opsBody<StatusRequest>() ?: return@post
                if (request.status !in clusterStatuses || request.notes != null && request.notes.length > 2000 ||
                    request.resolvedRuleId != null && !WireGrammars.ruleId.matches(request.resolvedRuleId)
                ) badRequest()
                call.mutation(store.status(call.fingerprint(), request.status, request.resolvedRuleId, request.notes))
            }
            get("/installs") { call.respond(store.installs(call.bound("limit", 50, 200))) }
            post("/installs/{uuid}/trust") {
                val request = call.opsBody<TrustRequest>() ?: return@post
                call.mutation(store.trust(call.installId(), request.trusted))
            }
            post("/installs/{uuid}/revoke") {
                val bytes = call.readLimitedBody() ?: return@post
                // This action has no body. If one is supplied it must still pass the bounded parser, then be refused.
                if (bytes.isNotEmpty()) { parseBounded(bytes); badRequest() }
                call.mutation(store.revoke(call.installId()))
            }
            get("/health") { call.respond(store.health(call.bound("days", 7, 90))) }
            get("/alarms") { call.respond(opsAlarms(alarms)) }
            get("/ledger") {
                val value = call.request.queryParameters["day"]
                val day = if (value == null) clock.today() else try {
                    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) badRequest()
                    LocalDate.parse(value)
                } catch (_: DateTimeParseException) { badRequest() }
                call.respond(store.ledger(day))
            }
            get("/vocabulary/queue") { call.respond(store.vocabularyQueue(call.bound("limit", 50, 200))) }
            post("/vocabulary/resolve") {
                val request = call.opsBody<ResolveRequest>() ?: return@post
                if (!CensusHash.isWellFormed(request.tokenHash) || request.clearText != null && request.clearText.length > 128 ||
                    !Regex("trusted:[0-9a-f]{8}|corpus|rule_anchor").matches(request.source) || !request.reject && request.clearText == null
                ) badRequest()
                if (request.clearText != null && CensusHash.of(request.clearText) != request.tokenHash) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("hash_mismatch"))
                    return@post
                }
                call.mutation(store.resolve(request.tokenHash, request.clearText, request.source, request.reject))
            }
            // Keep unknown paths behind the same authentication and TOTP gates.
            route("/{rest...}") { handle { call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) } }
        }
    }
}

private fun ApplicationCall.loginHeaders() {
    response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

private fun ApplicationCall.opsCookie(value: String, maxAge: Int) {
    response.cookies.append(Cookie(
        name = "census_ops", value = value, path = "/ops", httpOnly = true, secure = true, maxAge = maxAge,
        // Ktor 3.6 Cookie exposes SameSite through extensions, with no dedicated constructor parameter.
        extensions = mapOf("SameSite" to "Strict"),
    ))
}

private suspend fun ApplicationCall.seeOther(path: String) {
    response.headers.append(HttpHeaders.Location, path)
    respond(HttpStatusCode.SeeOther)
}

internal fun opsAlarms(alarms: HealthAlarms): JsonObject = buildJsonObject {
    put("today", JsonArray(alarms.raisedToday().map { alarm -> buildJsonObject {
        put("kind", alarm.kind); put("platform", alarm.platform); put("version", alarm.version)
        alarm.installPrefix?.let { put("installPrefix", it) }
        put("ruleIds", JsonArray(alarm.ruleIds.map { JsonPrimitive(it) }))
    } }))
    put("counts", JsonObject(alarms.stats.snapshot().mapValues { JsonPrimitive(it.value) }))
}

private fun badRequest(): Nothing = throw BadRequestException("Invalid operator request")
private fun ApplicationCall.fingerprint(): String = parameters["fingerprint"]?.takeIf { fingerprintPattern.matches(it) } ?: badRequest()
private fun ApplicationCall.installId() = parameters["uuid"]?.let(InstallId::parse)?.toUuid() ?: badRequest()
private fun ApplicationCall.bound(key: String, default: Int, maximum: Int): Int {
    val value = request.queryParameters[key] ?: return default
    return value.toIntOrNull()?.takeIf { it in 1..maximum } ?: badRequest()
}
private suspend fun ApplicationCall.mutation(found: Boolean) {
    if (found) respond(HttpStatusCode.NoContent) else respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
}
private suspend inline fun <reified T> ApplicationCall.opsBody(): T? {
    val bytes = readLimitedBody() ?: return null
    val element = parseBounded(bytes) ?: badRequest()
    return try { opsJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), element) } catch (_: SerializationException) { badRequest() }
}

@Serializable
private data class StatusRequest(val status: String, val resolvedRuleId: String? = null, val notes: String? = null)
@Serializable
private data class TrustRequest(val trusted: Boolean)
@Serializable
private data class ResolveRequest(val tokenHash: String, val clearText: String? = null, val source: String, val reject: Boolean)
