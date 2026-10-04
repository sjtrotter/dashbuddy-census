package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.auth.AuthenticatedBodyKey
import cloud.trotter.census.server.auth.AuthenticatedInstallKey
import cloud.trotter.census.server.auth.InstallRowKey
import cloud.trotter.census.server.auth.RequestInstantKey
import cloud.trotter.census.server.db.EnvelopeOutcome
import cloud.trotter.census.server.db.EnvelopeStore
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.EnvelopeValidator
import cloud.trotter.census.server.ingest.EnvelopeVerdict
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.secondsToUtcMidnight
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

private val envelopeBatchIdPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val envelopeLog = LoggerFactory.getLogger("Ingest")

fun Route.envelopeRoutes(envelopes: EnvelopeStore, clock: Clock, policy: Policy) {
    post("/envelopes") {
        val install = call.attributes[InstallRowKey]
        if (!install.trusted) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_trusted"))
            return@post
        }
        val rawBody = call.attributes[AuthenticatedBodyKey]
        val body = parseBounded(rawBody) as? JsonObject
        val batchId = (body?.get("batchId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val items = body?.get("items") as? JsonArray
        if (batchId == null || !envelopeBatchIdPattern.matches(batchId) || items == null || items.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            return@post
        }
        if (items.size > 20) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("batch_too_large"))
            return@post
        }
        val now = call.attributes.getOrNull(RequestInstantKey) ?: clock.now()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val accepted = mutableListOf<EnvelopeVerdict.Accepted>()
        val rejected = linkedMapOf<String, Int>()
        for (item in items) {
            when (val verdict = EnvelopeValidator.validate(item, policy)) {
                is EnvelopeVerdict.Accepted -> accepted += verdict
                is EnvelopeVerdict.Rejected -> {
                    rejected[verdict.reason] = rejected.getOrDefault(verdict.reason, 0) + 1
                    if (verdict.marker != null) envelopeLog.warn("ingest kind=envelopes marker={}", verdict.marker)
                }
            }
        }
        fun logBatch(count: Int, duplicate: Int, reasons: Map<String, Int>, status: String, paired: Int = 0, unpaired: Int = 0) {
            envelopeLog.info(
                "ingest kind=envelopes install_prefix={} accepted={} duplicate={} rejected={} bytes={} status={} paired={} unpaired={}",
                install.id.toString().take(8), count, duplicate, reasons.values.sum(), rawBody.size, status, paired, unpaired,
            )
            envelopeLog.debug("ingest kind=envelopes rejected_by_reason={}", reasons)
        }
        when (val result = envelopes.ingest(
            install.id, call.attributes[AuthenticatedInstallKey].secretHash, today, accepted, rejected, batchId,
            rawBody.size.toLong(), BudgetPolicy(dailySkeletonBudget = policy.dailySkeletonBudget),
            policy.retention.trustedEnvelopesDays, now,
        )) {
            EnvelopeOutcome.StaleCredential -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            EnvelopeOutcome.NotTrusted -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("not_trusted"))
            EnvelopeOutcome.BatchQuality -> {
                logBatch(0, 0, rejected, "batch_quality")
                call.respond(HttpStatusCode.UnprocessableEntity, BatchQualityResponse(rejected = rejected))
            }
            EnvelopeOutcome.Duplicate -> {
                logBatch(0, items.size, emptyMap(), "duplicate")
                call.respond(SkeletonBatchResponse("duplicate", 0, items.size, emptyMap()))
            }
            is EnvelopeOutcome.BudgetExhausted -> {
                logBatch(0, 0, rejected, "budget_exhausted")
                call.response.headers.append(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("budget_exhausted"))
            }
            is EnvelopeOutcome.Stored -> {
                logBatch(accepted.size, 0, rejected, "accepted", result.paired, result.unpaired)
                val budget = result.consumed
                call.respond(SkeletonBatchResponse("accepted", accepted.size, 0, rejected, SkeletonBudgetResponse(
                    budget.skeletonsRemaining, budget.bytesRemaining, budget.batchesRemaining, secondsToUtcMidnight(now),
                )))
            }
        }
    }
}
