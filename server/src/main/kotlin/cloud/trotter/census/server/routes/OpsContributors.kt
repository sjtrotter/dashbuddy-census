package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Config
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.auth.readLimitedBody
import cloud.trotter.census.server.db.ContributorStore
import cloud.trotter.census.server.db.QuarantineStore
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.atRest
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.ops.OpsForm
import cloud.trotter.census.server.ops.QuarantineHtml
import cloud.trotter.census.server.today
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID

private val contributorOpsLog = LoggerFactory.getLogger("Ops")

/**
 * The operator's contributor-tier routes (#81), mounted inside the ops authentication gate: reads need the operator,
 * every POST also needs a fresh TOTP code (OpsAuth). The quarantine routes serve ciphertext only.
 */
fun Route.opsContributorRoutes(contributors: ContributorStore, quarantine: QuarantineStore?, clock: Clock, policy: Policy, config: Config) {
    get("/contributors") { call.respond(contributors.grants(call.limit())) }
    post("/contributors/{grantId}/revoke") {
        val request = call.opsJson<RevokeGrantRequest>() ?: return@post
        val grantId = call.uuid("grantId")
        val found = contributors.setRevoked(grantId, request.revoked)
        contributorOpsLog.info("contributor grant revoked={} found={}", request.revoked, found)
        if (found) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
    }
    // The words that passed the gate, for the operator to approve through /ops/vocabulary/resolve (source "contributor").
    get("/contributors/words") {
        call.respond(JsonArray(contributors.pendingWords(call.limit()).map { word ->
            buildJsonObject {
                put("fingerprint", word.fingerprint); put("platformAppVersion", word.variant.platformAppVersion)
                put("hashDomain", word.variant.hashDomain); put("filterRev", word.variant.filterRev); put("layout", word.variant.layout)
                put("slot", word.slot); put("tokenHash", word.value); put("word", word.clearText); put("receivedDay", word.receivedDay.toString())
            }
        }))
    }
    if (quarantine == null) {
        get("/quarantine") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("quarantine_unconfigured")) }
        return
    }
    get("/quarantine") {
        call.respond(JsonArray(quarantine.list(clock.today()).map { item ->
            buildJsonObject {
                put("id", item.id.toString()); put("expiresDay", item.expiresDay.toString())
                item.note?.let { put("note", it.wire) }; put("bytes", item.bytes)
            }
        }))
    }
    get("/quarantine/view") {
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        call.respondText(QuarantineHtml.render(policy.serverVersion, policy.k, clock.today().toString(), quarantine.list(clock.today())), ContentType.Text.Html)
    }
    get("/quarantine/{id}") {
        val sealed = quarantine.sealed(call.uuid("id"), clock.today())
        if (sealed == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
            return@get
        }
        val (item, bytes) = sealed
        call.respond(buildJsonObject {
            put("id", item.id.toString()); put("expiresDay", item.expiresDay.toString())
            item.note?.let { put("note", it.wire) }; put("artifact", Base64.getEncoder().encodeToString(bytes))
        })
    }
    post("/quarantine/{id}/delete") {
        if (!call.emptyOpsBody()) return@post
        val found = quarantine.delete(call.uuid("id"))
        contributorOpsLog.info("quarantine deleted={}", found)
        if (found) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
    }
    // The operator's sanitized copy: a skeleton item, validated like an upload (so it holds no screen text) and keyed.
    post("/quarantine/{id}/approve") {
        if (call.attributes.contains(OpsForm)) throw BadRequestException("Invalid operator request")
        val id = call.uuid("id")
        val bytes = call.readLimitedBody() ?: return@post
        val element = parseBounded(bytes) ?: throw BadRequestException("Invalid operator request")
        val accepted = SkeletonValidator.validate(element, policy, clock.today()) as? ItemVerdict.Accepted
        if (accepted == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("bad_artifact"))
            return@post
        }
        val approved = quarantine.approve(id, config.hashKeying.atRest(accepted), clock.today())
        contributorOpsLog.info("quarantine approved={}", approved)
        if (approved) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
    }
}

private fun ApplicationCall.limit(): Int {
    val value = request.queryParameters["limit"] ?: return 50
    return value.toIntOrNull()?.takeIf { it in 1..200 } ?: throw BadRequestException("Invalid operator request")
}

private fun ApplicationCall.uuid(name: String): UUID = parameters[name]
    ?.takeIf { Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(it) }
    ?.let(UUID::fromString) ?: throw BadRequestException("Invalid operator request")

/** This action takes no body: false when one was refused with a response already sent. */
private suspend fun ApplicationCall.emptyOpsBody(): Boolean {
    if (attributes.contains(OpsForm)) throw BadRequestException("Invalid operator request")
    val bytes = readLimitedBody() ?: return false
    if (bytes.isNotEmpty()) throw BadRequestException("Invalid operator request")
    return true
}

private suspend inline fun <reified T> ApplicationCall.opsJson(): T? {
    if (attributes.contains(OpsForm)) throw BadRequestException("Invalid operator request")
    val bytes = readLimitedBody() ?: return null
    val element = parseBounded(bytes) ?: throw BadRequestException("Invalid operator request")
    return try {
        Json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), element)
    } catch (_: SerializationException) {
        throw BadRequestException("Invalid operator request")
    }
}

@Serializable private data class RevokeGrantRequest(val revoked: Boolean)
