package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.auth.AuthenticatedBodyKey
import cloud.trotter.census.server.auth.AuthenticatedInstallKey
import cloud.trotter.census.server.auth.InstallAuth
import cloud.trotter.census.server.auth.InstallRowKey
import cloud.trotter.census.server.auth.RequestInstantKey
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.auth.isValidSecret
import cloud.trotter.census.server.auth.parseBearer
import cloud.trotter.census.server.auth.readLimitedBody
import cloud.trotter.census.server.db.EnrolOutcome
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.MutationOutcome
import cloud.trotter.census.server.ingest.Budget
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.secondsToUtcMidnight
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset

private val identityJson = Json { encodeDefaults = true; explicitNulls = false }
private val appVersionPattern = Regex("[A-Za-z0-9+._-]{1,64}")

fun Route.identityRoutes(store: InstallStore?, clock: Clock, policy: Policy, alarms: HealthAlarms? = null, authenticated: Route.() -> Unit = {}) {
    if (store == null) {
        listOf(
            HttpMethod.Post to "/v1/enroll", HttpMethod.Post to "/v1/rotate", HttpMethod.Post to "/v1/nonce",
            HttpMethod.Delete to "/v1/installs/me", HttpMethod.Get to "/v1/me", HttpMethod.Post to "/v1/skeletons",
            HttpMethod.Post to "/v1/envelopes", HttpMethod.Post to "/v1/health",
        ).forEach { (method, path) ->
            route(path, method) { handle { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("db_unavailable")) } }
        }
        return
    }
    rateLimit(RateLimitName("enrol")) {
        post("/v1/enroll") {
            val bearer = parseBearer(call.request.headers[HttpHeaders.Authorization])
            if (bearer == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            val body = call.readLimitedBody() ?: return@post
            val request = decode<EnrolRequest>(body)
            if (request.installId != bearer.installId.value) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            if (!appVersionPattern.matches(request.appVersion)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
                return@post
            }
            if (!policy.acceptedSchemaIds.containsAll(request.schemaIds)) {
                call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("unsupported_schema"))
                return@post
            }
            when (store.enrol(bearer.installId.toUuid(), hashSecret(bearer.secret), request.appVersion)) {
                EnrolOutcome.Created, EnrolOutcome.SameKey -> call.respond(
                    JsonObject(identityJson.encodeToJsonElement(policy).jsonObject +
                        ("installIdPrefix" to JsonPrimitive(bearer.installId.value.take(8)))),
                )
                EnrolOutcome.KeyMismatch -> call.respond(HttpStatusCode.Conflict, ErrorResponse("install_exists"))
                EnrolOutcome.Revoked -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("revoked"))
            }
        }
    }
    route("/v1") {
        rateLimit(RateLimitName("install")) {
            install(InstallAuth) { this.store = store; this.clock = clock }
            post("/rotate") {
                val request = decode<RotateRequest>(call.attributes[AuthenticatedBodyKey])
                if (!isValidSecret(request.newSecret)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
                    return@post
                }
                val credential = call.attributes[AuthenticatedInstallKey]
                if (store.rotate(call.attributes[InstallRowKey].id, credential.secretHash, hashSecret(request.newSecret)) is MutationOutcome.Applied) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                }
            }
            delete("/installs/me") {
                when (val result = store.withdraw(call.attributes[InstallRowKey].id, call.attributes[AuthenticatedInstallKey].secretHash)) {
                    is MutationOutcome.Applied -> {
                        call.application.environment.log.info("withdraw rows={}", result.deletedRows.values.sum())
                        alarms?.recordWithdrawal(
                            InstallStore.installIdHash(call.attributes[InstallRowKey].id), requireNotNull(result.withdrawnAt),
                        )
                        val now = call.attributes.getOrNull(RequestInstantKey) ?: clock.now()
                        val deadline = now.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)
                        call.respond(HttpStatusCode.Accepted, WithdrawalResponse(completionDeadline = deadline.toString()))
                    }
                    MutationOutcome.StaleCredential -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                }
            }
            rateLimit(RateLimitName("nonce")) {
                post("/nonce") {
                    call.respond(NonceResponse(store.issueNonce(call.attributes[InstallRowKey].id)))
                }
            }
            get("/me") {
                val now = call.attributes.getOrNull(RequestInstantKey) ?: clock.now()
                val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
                val installId = call.attributes[InstallRowKey].id
                // One credential-bound statement for the install AND its ledger (Astra round 4): two reads left a window
                // in which a withdrawn-then-re-enrolled id's old credential could read the replacement's budget.
                val me = store.meView(installId, call.attributes[AuthenticatedInstallKey].secretHash, today)
                if (me == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                    return@get
                }
                val budget = Budget(clock, BudgetPolicy(dailySkeletonBudget = policy.dailySkeletonBudget))
                call.respond(
                    MeResponse(
                        installId.toString().take(8), me.createdDay.toString(), me.lastSeenDay.toString(), me.trusted,
                        BudgetResponse(budget.remainingSkeletons(me.ledger), budget.remainingBytes(me.ledger), secondsToUtcMidnight(now)),
                    ),
                )
            }
            authenticated()
        }
    }
}

private inline fun <reified T> decode(bytes: ByteArray): T = try {
    if (parseBounded(bytes) == null) throw BadRequestException("Invalid JSON")
    identityJson.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true))
} catch (_: SerializationException) {
    throw BadRequestException("Invalid JSON")
} catch (_: CharacterCodingException) {
    throw BadRequestException("Invalid UTF-8")
}

@Serializable
private data class EnrolRequest(val installId: String, val appVersion: String, val schemaIds: List<String>)
@Serializable
private data class RotateRequest(val newSecret: String)
@Serializable
private data class WithdrawalResponse(val status: String = "withdrawn", val completionDeadline: String)
@Serializable
private data class NonceResponse(val nonce: String, val expiresInSeconds: Int = 300)
@Serializable
private data class MeResponse(
    val installIdPrefix: String,
    val createdDay: String,
    val lastSeenDay: String,
    val trusted: Boolean,
    val budget: BudgetResponse,
)
@Serializable
private data class BudgetResponse(val skeletonsRemainingToday: Int, val bytesRemainingToday: Long, val resetInSeconds: Long)
