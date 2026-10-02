package cloud.trotter.census.server.ingest

import cloud.trotter.census.server.Policy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

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
)

sealed interface HealthReportVerdict {
    data class Accepted(val report: HealthReport) : HealthReportVerdict
    data class Rejected(val reason: String) : HealthReportVerdict
}

object HealthReportValidator {
    fun validate(element: JsonElement, today: LocalDate, policy: Policy): HealthReportVerdict {
        if (element !is JsonObject) return reject("bad_item")
        if (element.keys.any { it !in fields }) return reject("unknown_field")
        val date = element.string("day") ?: return reject("bad_day")
        if (!dayPattern.matches(date)) return reject("bad_day")
        val day = try { LocalDate.parse(date) } catch (_: DateTimeParseException) { return reject("bad_day") }
        if (day < today.minusDays(7) || day > today.plusDays(1)) return reject("stale_day")
        val platform = element.string("platform") ?: return reject("bad_platform")
        if (platform !in policy.acceptedPlatforms) return reject("bad_platform")
        val versions = versionPatterns.map { (key, grammar) ->
            val version = element.string(key) ?: return reject("bad_version")
            if (!grammar.matches(version)) return reject("bad_version")
            version
        }
        val admitted = element["admitted"].count() ?: return reject("bad_count")
        val unknown = element["unknown"].count() ?: return reject("bad_count")
        val trips = element["trips"].count() ?: return reject("bad_count")
        val counts = element["ruleCounts"] as? JsonObject ?: return reject("bad_count")
        if (counts.size > 512) return reject("bad_count")
        val ruleCounts = linkedMapOf<String, Int>()
        for ((rule, value) in counts) {
            if (!WireGrammars.ruleId.matches(rule)) return reject("bad_rule_id")
            ruleCounts[rule] = value.count() ?: return reject("bad_count")
        }
        if (ruleCounts.values.sumOf { it.toLong() } > admitted) return reject("bad_count")
        if (Json.encodeToString(JsonElement.serializer(), element).toByteArray().size > 8192) return reject("too_large")
        return HealthReportVerdict.Accepted(HealthReport(day, platform, versions[0], versions[1], versions[2], admitted, unknown, trips, ruleCounts))
    }

    private fun reject(reason: String): HealthReportVerdict.Rejected = HealthReportVerdict.Rejected(reason)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.count(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..1_000_000 }
    private val fields = setOf("day", "platform", "platformAppVersion", "appVersion", "rulesetVersion", "admitted", "unknown", "trips", "ruleCounts")
    private val versionPatterns = mapOf(
        "platformAppVersion" to WireGrammars.platformAppVersion, "appVersion" to WireGrammars.appVersion,
        "rulesetVersion" to WireGrammars.rulesetReleaseTag,
    )
    private val dayPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
}
