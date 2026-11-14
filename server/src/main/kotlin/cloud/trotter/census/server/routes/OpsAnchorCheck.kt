package cloud.trotter.census.server.routes

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.server.ChromeShape
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.AnchorCaller
import cloud.trotter.census.server.db.AnchorCheckStore
import cloud.trotter.census.server.db.AnchorOutcome
import cloud.trotter.census.server.db.SlotScope
import cloud.trotter.census.server.db.SlotVariant
import cloud.trotter.census.server.ingest.WireGrammars
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val anchorLog = LoggerFactory.getLogger("Ops")

/**
 * The operator's anchor check (#82, ADR-0011 §16), mounted inside the ops authentication gate, so a query needs the
 * operator and a fresh TOTP code like every operator POST. The caller is [AnchorCaller.operator]; the agent token
 * (#84) will mount its own route with its own caller. Logs carry the candidate count and the answer class, never a
 * candidate, a slot or a cluster.
 */
fun Route.opsAnchorCheckRoutes(store: AnchorCheckStore, policy: Policy) {
    post("/anchor-check") {
        val request = call.opsJson<AnchorCheckRequest>() ?: return@post
        val scope = request.variant.scope(policy) ?: badRequest()
        if (!slotPattern.matches(request.slot) || request.candidates.size !in 1..policy.anchorCheck.maxCandidates ||
            request.candidates.any { it.isEmpty() || it.length > ChromeShape.MAX_TEXT_LENGTH }) badRequest()
        val outcome = store.check(AnchorCaller.operator(policy.anchorCheck), scope, request.slot, request.candidates)
        when (outcome) {
            is AnchorOutcome.Answered -> {
                anchorLog.info("anchor check candidates={} matched={}", outcome.matches.size, outcome.matches.count { it != null })
                call.respond(AnchorCheckResponse(outcome.matches.map { text ->
                    if (text == null) AnchorAnswer(NO_ANSWER) else AnchorAnswer(MATCH, text)
                }))
            }
            is AnchorOutcome.Refused -> {
                anchorLog.info("anchor check candidates={} refused={}", request.candidates.size, outcome.indexes.size)
                call.respond(HttpStatusCode.UnprocessableEntity, AnchorRefusal("not_chrome", outcome.indexes))
            }
            is AnchorOutcome.BudgetExhausted -> {
                anchorLog.info("anchor check candidates={} budget_exhausted", request.candidates.size)
                call.response.headers.append(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("budget_exhausted"))
            }
        }
    }
    get("/anchor-checks") {
        val limit = call.request.queryParameters["limit"]?.let { value -> value.toIntOrNull()?.takeIf { it in 1..200 } ?: badRequest() } ?: 50
        call.respond(store.auditLog(limit))
    }
}

private const val MATCH = "match"
private const val NO_ANSWER = "no_answer"

private fun badRequest(): Nothing = throw BadRequestException("Invalid operator request")

/**
 * The cluster variant the operator names (#82): the STORED cluster key as `/ops/clusters` lists it (a screen's public
 * fingerprint, a notification's keyed one) and the #80 variant.
 */
@Serializable
private data class OpsVariantDto(
    val fingerprint: String,
    val platformAppVersion: String,
    val hashDomain: Int,
    val filterRev: Int,
    val layout: String,
) {
    fun scope(policy: Policy): SlotScope? {
        if (!CensusFingerprint.isWellFormed(fingerprint) || !CensusFingerprint.isWellFormed(layout)) return null
        if (platformAppVersion != "unknown" && !WireGrammars.platformAppVersion.matches(platformAppVersion)) return null
        if (hashDomain !in policy.acceptedHashDomains || filterRev < policy.minimumFilterRev) return null
        return SlotScope(fingerprint, SlotVariant(platformAppVersion, hashDomain, filterRev, layout))
    }
}

@Serializable private data class AnchorCheckRequest(val variant: OpsVariantDto, val slot: String, val candidates: List<String>)
@Serializable private data class AnchorAnswer(val answer: String, val text: String? = null)
@Serializable private data class AnchorCheckResponse(val results: List<AnchorAnswer>)
@Serializable private data class AnchorRefusal(val error: String, val refused: List<Int>)
