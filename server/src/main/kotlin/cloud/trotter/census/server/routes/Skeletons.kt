package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.auth.AuthenticatedBodyKey
import cloud.trotter.census.server.auth.AuthenticatedInstallKey
import cloud.trotter.census.server.auth.InstallRowKey
import cloud.trotter.census.server.auth.RequestInstantKey
import cloud.trotter.census.server.db.IngestOutcome
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.SkeletonStore
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.secondsToUtcMidnight
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

private val batchIdPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val ingestLog = LoggerFactory.getLogger("Ingest")

/** Mounted inside identityRoutes' authenticated install rate limit; the body was already read and signed. */
fun Route.skeletonRoutes(store: InstallStore, skeletons: SkeletonStore, clock: Clock, policy: Policy) {
    post("/skeletons") {
        val rawBody = call.attributes[AuthenticatedBodyKey]
        val body = parseBounded(rawBody) as? JsonObject
        val batchId = (body?.get("batchId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val items = body?.get("items") as? JsonArray
        if (batchId == null || !batchIdPattern.matches(batchId) || items == null || items.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            return@post
        }
        if (items.size > policy.maxBatchItems) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("batch_too_large"))
            return@post
        }
        val now = call.attributes.getOrNull(RequestInstantKey) ?: clock.now()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val installId = call.attributes[InstallRowKey].id
        val prefix = installId.toString().take(8)
        val accepted = mutableListOf<ItemVerdict.Accepted>()
        val rejected = linkedMapOf<String, Int>()
        for (element in items) {
            when (val verdict = SkeletonValidator.validate(element, policy, today)) {
                is ItemVerdict.Accepted -> accepted += verdict
                is ItemVerdict.Rejected -> rejected[verdict.reason] = rejected.getOrDefault(verdict.reason, 0) + 1
            }
        }
        val keyHash = call.attributes[AuthenticatedInstallKey].secretHash
        if (rejected.values.sum() * 5 > items.size) {
            // Round 2: the rejection counters are credential-bound too — a stale key writes nothing, anywhere.
            if (!store.recordIngestIfCurrent(installId, keyHash, today, 0, rejected)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }
            logBatch(prefix, 0, 0, rejected, rawBody.size, "batch_quality")
            call.respond(HttpStatusCode.UnprocessableEntity, BatchQualityResponse(rejected = rejected))
            return@post
        }
        val duplicate = accepted.size - accepted.map { it.item.fingerprint }.toSet().size
        val outcome = skeletons.ingest(
            installId, keyHash, today, accepted, duplicate, rejected,
            batchId, rawBody.size.toLong(),
            BudgetPolicy(dailySkeletonBudget = policy.dailySkeletonBudget), now,
        )
        when (outcome) {
            IngestOutcome.StaleCredential -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            IngestOutcome.Duplicate -> {
                logBatch(prefix, 0, items.size, emptyMap(), rawBody.size, "duplicate")
                call.respond(SkeletonBatchResponse("duplicate", 0, items.size, emptyMap()))
            }
            is IngestOutcome.BudgetExhausted -> {
                logBatch(prefix, 0, 0, rejected, rawBody.size, "budget_exhausted")
                call.response.headers.append(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("budget_exhausted"))
            }
            is IngestOutcome.Stored -> {
                logBatch(prefix, accepted.size - duplicate, duplicate, rejected, rawBody.size, "accepted")
                val budget = outcome.consumed
                call.respond(SkeletonBatchResponse(
                    "accepted", accepted.size - duplicate, duplicate, rejected,
                    SkeletonBudgetResponse(
                        budget.skeletonsRemaining, budget.bytesRemaining, budget.batchesRemaining, secondsToUtcMidnight(now),
                    ),
                ))
            }
        }
    }
}

private fun logBatch(prefix: String, accepted: Int, duplicate: Int, rejected: Map<String, Int>, bytes: Int, status: String) {
    ingestLog.info(
        "ingest install_prefix={} accepted={} duplicate={} rejected={} bytes={} status={}",
        prefix, accepted, duplicate, rejected.values.sum(), bytes, status,
    )
    ingestLog.debug("ingest rejected_by_reason={}", rejected)
}

@Serializable
internal data class SkeletonBatchResponse(
    val status: String,
    val accepted: Int,
    val duplicate: Int,
    val rejected: Map<String, Int>,
    val budget: SkeletonBudgetResponse? = null,
)

@Serializable
internal data class SkeletonBudgetResponse(
    val skeletonsRemainingToday: Int,
    val bytesRemainingToday: Long,
    val batchesRemainingToday: Int,
    val resetInSeconds: Long,
)

@Serializable
internal data class BatchQualityResponse(val error: String = "batch_quality", val rejected: Map<String, Int>)
