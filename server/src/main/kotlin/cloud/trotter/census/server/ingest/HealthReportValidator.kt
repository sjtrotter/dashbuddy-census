package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.health.HealthReportCheck
import cloud.trotter.census.contract.health.HealthReportSchema
import cloud.trotter.census.contract.health.RuleParseCountDto
import cloud.trotter.census.server.Policy
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

data class HealthReport(
    val day: LocalDate,
    val platform: String,
    val platformAppVersion: String,
    val appVersion: String,
    val rulesetVersion: String,
    val admitted: Int,
    val unknown: Int,
    val trips: Int,
    val ruleCounts: Map<String, Int>,
    /** Per-rule parse counts (#64); null when the report did not carry them. */
    val parseShortfall: Map<String, RuleParseCountDto>? = null,
)

sealed interface HealthReportVerdict {
    data class Accepted(val report: HealthReport) : HealthReportVerdict
    data class Rejected(val reason: String) : HealthReportVerdict
}

/** The contract's [HealthReportSchema] decides the shape (#64); this server adds its date window and platform list. */
object HealthReportValidator {
    fun validate(element: JsonElement, today: LocalDate, policy: Policy): HealthReportVerdict = when (val check = HealthReportSchema.check(
        element,
        dayAccepted = { it >= today.minusDays(7) && it <= today.plusDays(1) },
        platformAccepted = { it in policy.acceptedPlatforms },
    )) {
        is HealthReportCheck.Invalid -> HealthReportVerdict.Rejected(check.reason.wire)
        is HealthReportCheck.Valid -> with(check.report) {
            HealthReportVerdict.Accepted(HealthReport(
                LocalDate.parse(day), platform, platformAppVersion, appVersion, rulesetVersion, admitted, unknown, trips,
                ruleCounts, parseShortfall,
            ))
        }
    }
}
