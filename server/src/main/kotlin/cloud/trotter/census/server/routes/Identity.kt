package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.budget
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
private val schemaIdPattern = Regex("[a-z0-9_.-]{1,64}")
private const val MAX_ENROL_SCHEMA_IDS = 8

fun Route.identityRoutes(
    store: InstallStore?, clock: Clock, policy: Policy, alarms: HealthAlarms? = null, enrolStats: EnrolStats = EnrolStats(),
    authenticated: Route.() -> Unit = {},
) {
    if (store == null) {
        listOf(
            HttpMethod.Post to "/v1/enroll", HttpMethod.Post to "/v1/rotate", HttpMethod.Post to "/v1/nonce",
            HttpMethod.Delete to "/v1/installs/me", HttpMethod.Delete to "/v1/installs/me/data", HttpMethod.Get to "/v1/me", HttpMethod.Post to "/v1/skeletons",
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
                enrolStats.record(EnrolResult.UNAUTHORIZED)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            // Not counted: a body too large (413) or too slow (408), answered inside readLimitedBody, and any answer
            // given before this handler runs (a routing-level 400, the rate limiter's 429, a 500 or 503).
            val body = call.readLimitedBody() ?: return@post
            val request = try {
                decode<EnrolRequest>(body)
            } catch (failure: BadRequestException) {
                enrolStats.record(EnrolResult.BAD_REQUEST)
                throw failure
            }
            if (request.installId != bearer.installId.value) {
                enrolStats.record(EnrolResult.UNAUTHORIZED)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            if (!appVersionPattern.matches(request.appVersion)) {
                enrolStats.record(EnrolResult.BAD_REQUEST)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
                return@post
            }
            // DashBuddy #1522: schemaIds are the schemas the client can produce. Enrol when at least one is accepted
            // and ignore the rest; uploads are still checked per item, so the extra ids grant nothing. Nothing about
            // schemas is stored.
            if (request.schemaIds.size > MAX_ENROL_SCHEMA_IDS || request.schemaIds.any { !schemaIdPattern.matches(it) }) {
                enrolStats.record(EnrolResult.BAD_REQUEST)
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
                return@post
            }
            if (request.schemaIds.none { it in policy.acceptedSchemaIds }) {
                enrolStats.record(EnrolResult.UNSUPPORTED_SCHEMA)
                call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("unsupported_schema"))
                return@post
            }
            when (store.enrol(bearer.installId.toUuid(), hashSecret(bearer.secret), request.appVersion)) {
                EnrolOutcome.Created, EnrolOutcome.SameKey -> {
                    enrolStats.record(EnrolResult.ENROLLED)
                    call.respond(
                        JsonObject(identityJson.encodeToJsonElement(policy).jsonObject +
                            ("installIdPrefix" to JsonPrimitive(bearer.installId.value.take(8)))),
                    )
                }
                EnrolOutcome.KeyMismatch -> {
                    enrolStats.record(EnrolResult.INSTALL_EXISTS)
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("install_exists"))
                }
                EnrolOutcome.Revoked -> {
                    enrolStats.record(EnrolResult.REVOKED)
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("revoked"))
                }
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
                        // #1192: the notice is best-effort — the rows and the tombstone are already committed, so a
                        // sink failure must not turn a successful withdrawal into a 500 (fable review F4).
                        runCatching {
                            alarms?.recordWithdrawal(
                                InstallStore.installIdHash(call.attributes[InstallRowKey].id), requireNotNull(result.withdrawnAt),
                            )
                        }.onFailure { call.application.environment.log.warn("withdrawal notice failed class={}", it.javaClass.simpleName) }
                        val now = call.attributes.getOrNull(RequestInstantKey) ?: clock.now()
                        val deadline = now.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)
                        call.respond(HttpStatusCode.Accepted, WithdrawalResponse(completionDeadline = deadline.toString()))
                    }
                    MutationOutcome.StaleCredential -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                }
            }
            // #79: "delete my data" without withdrawing. Never paused. The credential stays valid; uploads may resume.
            delete("/installs/me/data") {
                val now = call.attributes.getOrNull(RequestInstantKey) ?: clock.now()
                val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
                val installId = call.attributes[InstallRowKey].id
                when (val result = store.deleteData(installId, call.attributes[AuthenticatedInstallKey].secretHash, today)) {
                    is MutationOutcome.Applied -> {
                        call.application.environment.log.info("delete_data rows={}", result.deletedRows.values.sum())
                        // The same off-host journal row as a withdrawal, so a restore re-applies it; best-effort.
                        runCatching {
                            alarms?.recordWithdrawal(InstallStore.installIdHash(installId), requireNotNull(result.withdrawnAt))
                        }.onFailure { call.application.environment.log.warn("deletion notice failed class={}", it.javaClass.simpleName) }
                        call.respond(DeletionResponse(
                            rows = result.deletedRows.values.sum(), deletedDay = today.toString(),
                            completionDeadline = today.plusDays(1).toString(),
                        ))
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
                val budget = Budget(clock, policy.budget())
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
/**
 * #79: the confirmation the app shows. [rows] counts the per-install rows erased now; shared samples lose the hashes
 * only this install sent, and vocabulary entries it alone kept above k drop, at the next lifecycle pass, before
 * [completionDeadline].
 */
@Serializable
private data class DeletionResponse(
    val status: String = "deleted",
    val rows: Int,
    val deletedDay: String,
    val completionDeadline: String,
)
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
