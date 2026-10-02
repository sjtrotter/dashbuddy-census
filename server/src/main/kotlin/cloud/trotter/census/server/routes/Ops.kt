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
import cloud.trotter.census.server.ops.OpsAuth
import cloud.trotter.census.server.today
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
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
private val clusterStatuses = setOf("new", "triaged", "drafted", "resolved", "ignored")

fun Route.opsRoutes(config: Config, store: OpsStore?, alarms: HealthAlarms?, clock: Clock, policy: Policy) {
    route("/ops") {
        install(OpsAuth) { this.config = config; this.clock = clock }
        rateLimit(RateLimitName("ops")) {
            if (store == null || alarms == null) {
                route("/{rest...}") { handle { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("db_unavailable")) } }
                return@rateLimit
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
            // Keep unknown paths behind the same bearer and TOTP gates.
            route("/{rest...}") { handle { call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found")) } }
        }
    }
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
private fun ApplicationCall.fingerprint(): String = parameters["fingerprint"]?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: badRequest()
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
