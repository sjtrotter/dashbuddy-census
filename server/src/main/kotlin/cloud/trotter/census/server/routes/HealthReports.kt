package cloud.trotter.census.server.routes

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.ErrorResponse
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.auth.AuthenticatedBodyKey
import cloud.trotter.census.server.auth.AuthenticatedInstallKey
import cloud.trotter.census.server.auth.InstallRowKey
import cloud.trotter.census.server.auth.RequestInstantKey
import cloud.trotter.census.server.db.HealthOutcome
import cloud.trotter.census.server.db.HealthStore
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.ingest.HealthReportValidator
import cloud.trotter.census.server.ingest.HealthReportVerdict
import cloud.trotter.census.server.ingest.parseBounded
import cloud.trotter.census.server.jobs.HealthAlarms
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.ZoneOffset

fun Route.healthReportRoutes(health: HealthStore, alarms: HealthAlarms, clock: Clock, policy: Policy) {
    post("/health") {
        val rawBody = call.attributes[AuthenticatedBodyKey]
        val body = parseBounded(rawBody) as? JsonObject
        val reports = body?.get("reports") as? JsonArray
        if (reports == null || reports.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request"))
            return@post
        }
        if (reports.size > 30) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("batch_too_large"))
            return@post
        }
        val now = call.attributes.getOrNull(RequestInstantKey) ?: clock.now()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val accepted = mutableListOf<HealthReport>()
        val rejected = linkedMapOf<String, Int>()
        for (report in reports) {
            when (val verdict = HealthReportValidator.validate(report, today)) {
                is HealthReportVerdict.Accepted -> accepted += verdict.report
                is HealthReportVerdict.Rejected -> rejected[verdict.reason] = rejected.getOrDefault(verdict.reason, 0) + 1
            }
        }
        val installId = call.attributes[InstallRowKey].id
        val keyHash = call.attributes[AuthenticatedInstallKey].secretHash
        when (val outcome = health.upsert(
            installId, keyHash, today, accepted, rejected, rawBody.size.toLong(),
            BudgetPolicy(dailySkeletonBudget = policy.dailySkeletonBudget), now,
        )) {
            HealthOutcome.StaleCredential -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            HealthOutcome.BatchQuality -> call.respond(HttpStatusCode.UnprocessableEntity, BatchQualityResponse(rejected = rejected))
            is HealthOutcome.BudgetExhausted -> {
                call.response.headers.append(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("budget_exhausted"))
            }
            HealthOutcome.Stored -> {
                alarms.evaluate(installId, accepted, keyHash)
                call.respond(HealthBatchResponse(accepted = accepted.size, rejected = rejected))
            }
        }
    }
}

@Serializable
private data class HealthBatchResponse(val status: String = "accepted", val accepted: Int, val rejected: Map<String, Int>)
