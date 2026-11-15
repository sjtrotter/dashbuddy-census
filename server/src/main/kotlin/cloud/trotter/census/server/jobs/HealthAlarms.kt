package cloud.trotter.census.server.jobs

import cloud.trotter.census.contract.health.HealthReportSchema
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.HealthStore
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.today
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class DayReport(
    val day: LocalDate,
    val platform: String,
    val platformAppVersion: String,
    val admitted: Long,
    val unknown: Long = 0,
    val trips: Long = 0,
    val ruleCounts: Map<String, Long> = emptyMap(),
    /** Per-rule parse samples (#64); null when no report for the day carried them. */
    val parseShortfall: Map<String, ParseSamples>? = null,
)

/**
 * #64: [samples] stored reports sampled a rule, each counting the shortfalls among its last
 * [HealthReportSchema.PARSE_SAMPLE] eligible attempts; [shortfall] sums them. The rate is
 * `shortfall / (samples × PARSE_SAMPLE)`.
 */
data class ParseSamples(val shortfall: Int, val samples: Int)

data class FleetDay(
    val day: LocalDate,
    val platform: String,
    val platformAppVersion: String,
    val installsReporting: Int,
    val admitted: Long,
    val unknown: Long = 0,
    val ruleCounts: Map<String, Long> = emptyMap(),
)

data class NewClusterCount(val platform: String, val version: String, val count: Int)

/** An alarm names a kind, a platform and a version, never an install (#79). */
data class Alarm(
    val kind: String,
    val platform: String,
    val version: String,
    val ruleIds: List<String> = emptyList(),
    val message: String? = null,
)

fun interface AlarmSink {
    fun raise(alarm: Alarm)
}

class LoggingAlarmSink : AlarmSink {
    override fun raise(alarm: Alarm) {
        // The withdrawal journal belongs in the spool/SNS, never in application logs.
        if (alarm.kind == "withdrawal") return
        log.warn("alarm {}", renderAlarm(alarm).replace('\n', ' '))
    }

    private val log = LoggerFactory.getLogger("Alarm")
}

/** The single rendering owner for logs and spool files: validated tokens only, one field per line. */
fun renderAlarm(alarm: Alarm): String {
    if (alarm.kind == "withdrawal") {
        // #1192: the withdrawal journal record — `kind=` first like every spool file (the host publisher's
        // grammar dispatches on it), then exactly two slots the host accepts (or [redacted], fail-closed).
        val body = alarm.message?.takeIf { withdrawalBodyPattern.matches(it) } ?: "install_id_hash=[redacted]\nwithdrawn_at=[redacted]"
        return "kind=withdrawal\n$body"
    }
    val kind = alarm.kind.takeIf { it in alarmKinds } ?: "[redacted]"
    val platform = alarm.platform.takeIf { platformPattern.matches(it) } ?: "[redacted]"
    val version = alarm.version.takeIf { WireGrammars.platformAppVersion.matches(it) } ?: "[redacted]"
    val rules = alarm.ruleIds.map {
        it.takeIf { rule -> rule.length <= MAX_ALARM_RULE_ID_LENGTH && WireGrammars.ruleId.matches(rule) } ?: "[redacted]"
    }
    // #79: alarms carry no install key. The line stays, always `-`, because the deployed host publisher's grammar
    // requires exactly these five lines; dropping it would need every host's publisher updated first.
    return "kind=$kind\nplatform=$platform\nversion=$version\ninstall_prefix=-\nrule_ids=$rules"
}

/** Every kind [renderAlarm] lets through; the host publisher's `KINDS` (deploy/aws/cloud-init.yaml.tftpl) lists the same. */
internal val alarmKinds = setOf("silent_rule_death", "parse_shortfall", "rule_share_cliff", "trips", "fleet_unknown", "new_clusters", "silence", "lifecycle_failure", "lifecycle_backlog", "lifecycle_stale")
private val platformPattern = Regex("^[a-z_][a-z0-9_]{0,31}$")
/** The two-line withdrawal body: `install_id_hash=<64 lower hex>\nwithdrawn_at=<ISO-8601 UTC instant>`. */
internal val withdrawalBodyPattern = Regex("^install_id_hash=[0-9a-f]{64}\\nwithdrawn_at=\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z$")

/** One day's parse samples summed across its stored versions; null when none of them carried any. */
private fun sumParseSamples(rows: List<DayReport>): Map<String, ParseSamples>? {
    val carried = rows.mapNotNull { it.parseShortfall }.ifEmpty { return null }
    val sums = linkedMapOf<String, ParseSamples>()
    for (samples in carried) for ((rule, sample) in samples) {
        val sum = sums[rule]
        sums[rule] = if (sum == null) sample else ParseSamples(sum.shortfall + sample.shortfall, sum.samples + sample.samples)
    }
    return sums
}

/** The host publisher (deploy/aws/cloud-init.yaml.tftpl) deletes a spool file larger than this. */
internal const val PUBLISHER_MAX_BYTES = 4_096

/** Each delivered alarm renders within this, a 512-byte margin under the publisher; see [splitForPublisher]. */
internal const val ALARM_FILE_BUDGET = PUBLISHER_MAX_BYTES - 512

/** A rule id longer than this renders as `[redacted]`, so a single rule always fits [ALARM_FILE_BUDGET]. */
internal const val MAX_ALARM_RULE_ID_LENGTH = 128

/**
 * Splits [alarm] into alarms with the same dimensions whose rule lists, in order, cover its rules exactly once and each
 * render within [budget] bytes. An alarm without rules, or one that already fits, comes back unchanged.
 */
internal fun splitForPublisher(alarm: Alarm, budget: Int = ALARM_FILE_BUDGET): List<Alarm> {
    fun size(rules: List<String>) = renderAlarm(alarm.copy(ruleIds = rules)).toByteArray(Charsets.UTF_8).size
    if (alarm.ruleIds.isEmpty() || size(alarm.ruleIds) <= budget) return listOf(alarm)
    val parts = mutableListOf<Alarm>()
    var chunk = mutableListOf<String>()
    for (rule in alarm.ruleIds) {
        if (chunk.isNotEmpty() && size(chunk + rule) > budget) {
            parts += alarm.copy(ruleIds = chunk)
            chunk = mutableListOf()
        }
        chunk += rule
    }
    parts += alarm.copy(ruleIds = chunk)
    return parts
}

/** Process-local counters, like PipelineStats: no identities or report contents are retained. */
class AlarmStats {
    private val raised = ConcurrentHashMap<String, AtomicLong>()
    fun record(kind: String) { raised.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet() }
    fun snapshot(): Map<String, Long> = raised.mapValues { it.value.get() }
}

fun silentRuleDeath(history: List<DayReport>, today: DayReport, minMedian: Int = 5, dashedAdmitted: Int = 200): List<String> {
    if (today.admitted < dashedAdmitted) return emptyList()
    // A version change during a day still contributes exactly one day to the median.
    val days = history.filter {
        it.platform == today.platform && it.day >= today.day.minusDays(28) && it.day < today.day
    }.groupBy { it.day }.values.filter { rows -> rows.sumOf { it.admitted } >= dashedAdmitted }
    if (days.size < 3) return emptyList()
    return days.flatMap { rows -> rows.flatMap { it.ruleCounts.keys } }.toSortedSet().filter { rule ->
        val counts = days.map { rows -> rows.sumOf { it.ruleCounts.getOrDefault(rule, 0) } }.sorted()
        val median = if (counts.size % 2 == 0) {
            (counts[counts.size / 2 - 1].toDouble() + counts[counts.size / 2]) / 2
        } else counts[counts.size / 2].toDouble()
        median >= minMedian && today.ruleCounts.getOrDefault(rule, 0) == 0L
    }
}

/**
 * #64: rules whose sampled parse shortfall rate jumped against the rule's own history on one install (k = 1).
 *
 * A report names a rule only when the rule had at least [HealthReportSchema.PARSE_SAMPLE] eligible attempts that day,
 * so presence is the minimum denominator. A day's rate is its summed shortfalls over (its samples × PARSE_SAMPLE),
 * summed across app versions. The rule needs at least [minDays] days carrying it in the 28 calendar days before the
 * reporting day, and fires when the reporting day's rate is at least [risePercent] percentage points above the median
 * of those days' rates. Exact rational arithmetic: a rise of exactly the threshold fires, one below never rounds up.
 */
fun parseShortfallRise(
    history: List<DayReport>,
    today: DayReport,
    minDays: Int = 3,
    risePercent: Int = 25,
): List<String> {
    val current = today.parseShortfall ?: return emptyList()
    val days = history.filter {
        it.platform == today.platform && it.day >= today.day.minusDays(28) && it.day < today.day
    }.groupBy { it.day }.values.mapNotNull(::sumParseSamples)
    return current.keys.sorted().filter { rule ->
        val rates = days.mapNotNull { it[rule]?.rate() }.sorted()
        if (rates.size < minDays) return@filter false
        val median = if (rates.size % 2 == 1) rates[rates.size / 2] else rates[rates.size / 2 - 1].midpoint(rates[rates.size / 2])
        val now = current.getValue(rule).rate()
        // now - median >= risePercent/100, cross-multiplied.
        (now.num * median.den - median.num * now.den) * 100.toBigInteger() >= risePercent.toBigInteger() * now.den * median.den
    }
}

private fun ParseSamples.rate() = Rate(shortfall.toBigInteger(), samples.toBigInteger() * HealthReportSchema.PARSE_SAMPLE.toBigInteger())

/** A non-negative rational with a positive denominator. */
private data class Rate(val num: BigInteger, val den: BigInteger) : Comparable<Rate> {
    override fun compareTo(other: Rate): Int = (num * other.den).compareTo(other.num * den)
    fun midpoint(other: Rate) = Rate(num * other.den + other.num * den, 2.toBigInteger() * den * other.den)
}

fun ruleShareCliff(previous: FleetDay, current: FleetDay, minInstalls: Int = 2, drop: Double = 0.8): List<String> {
    if (previous.platform != current.platform || previous.platformAppVersion == current.platformAppVersion ||
        previous.day >= current.day || previous.installsReporting < minInstalls || current.installsReporting < minInstalls ||
        previous.admitted <= 0 || current.admitted <= 0
    ) return emptyList()
    return previous.ruleCounts.keys.sorted().filter { rule ->
        // Cross-multiply exactly: a loss of precisely 80% must never round into an alarm.
        val before = previous.ruleCounts.getValue(rule).toBigDecimal() * current.admitted.toBigDecimal()
        val after = current.ruleCounts.getOrDefault(rule, 0).toBigDecimal() * previous.admitted.toBigDecimal()
        before.signum() > 0 && before - after > before * drop.toBigDecimal()
    }
}

/** The additional inputs are fleet aggregates and today's cluster counts, never payloads. */
fun unknownSurge(
    reports: List<DayReport>,
    fleet: List<FleetDay> = emptyList(),
    newClusters: List<NewClusterCount> = emptyList(),
): List<Alarm> = buildList {
    for (report in reports) if (report.trips > 0) {
        add(Alarm("trips", report.platform, report.platformAppVersion))
    }
    for (day in fleet) if (day.installsReporting >= 2 && day.admitted + day.unknown > 0 &&
        day.unknown.toDouble() / (day.admitted + day.unknown) >= 0.5
    ) add(Alarm("fleet_unknown", day.platform, day.platformAppVersion))
    for (clusters in newClusters) if (clusters.count >= 5) add(Alarm("new_clusters", clusters.platform, clusters.version))
}

fun silence(
    lastReportAt: Instant?,
    startedAt: Instant,
    now: Instant,
    lastReportDay: LocalDate?,
    today: LocalDate,
    trusted: Boolean,
): Boolean = trusted && lastReportDay != null && lastReportDay <= today.minusDays(2) &&
    Duration.between(lastReportAt ?: startedAt, now) >= Duration.ofHours(48)

/** Reads stored counters after admission. Main shares this evaluator with the six-hour purge job. */
class HealthAlarms(
    private val store: HealthStore,
    private val clock: Clock,
    private val sink: AlarmSink = LoggingAlarmSink(),
    val stats: AlarmStats = AlarmStats(),
    /** Process start for the silence clock; null = read the clock at the FIRST silence evaluation (see [startedAt]). */
    startedAt: Instant? = null,
) {
    fun recordWithdrawal(installIdHash: String, withdrawnAt: Instant) {
        require(InstallStore.TOMBSTONE_HASH.matches(installIdHash))
        sink.raise(Alarm(
            kind = "withdrawal", platform = "", version = "",
            message = "install_id_hash=$installIdHash\nwithdrawn_at=$withdrawnAt",
        ))
        stats.record("withdrawal")
    }

    private data class AlarmKey(val kind: String, val install: UUID?, val platform: String, val version: String, val rule: String?)
    private val lastReportAt = ConcurrentHashMap<UUID, Instant>()
    // Read lazily (at the first silence evaluation, not at construction): the process clock is deliberately not touched
    // while wiring the module — a request must see exactly one clock read — and starting the 48 h window at the first
    // purge-cadence evaluation only fails further toward NO alarm after a restart (documented in OPERATOR.md).
    private val startedAt: Instant by lazy { startedAt ?: clock.now() }
    private var dedupeDay: LocalDate? = null
    private val raised = mutableSetOf<AlarmKey>()
    private val delivered = mutableListOf<Alarm>()

    @Synchronized
    fun raisedToday(): List<Alarm> {
        resetDay()
        return delivered.map { it.copy(ruleIds = it.ruleIds.toList()) }
    }

    private fun resetDay() {
        val today = clock.today()
        if (dedupeDay != today) {
            raised.clear()
            delivered.clear()
            dedupeDay = today
        }
    }

    suspend fun evaluate(installId: UUID, reports: List<HealthReport>, keyHash: String) {
        val now = clock.now()
        lastReportAt[installId] = now
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val inputs = store.alarmInputs(installId, keyHash, reports, today) ?: return
        val currentDays = inputs.current.groupBy { it.platform to it.day }.values.map { rows ->
            val counts = linkedMapOf<String, Long>()
            for (row in rows) for ((rule, count) in row.ruleCounts) counts[rule] = counts.getOrDefault(rule, 0) + count
            rows.first().copy(
                platformAppVersion = rows.maxOf { it.platformAppVersion }, admitted = rows.sumOf { it.admitted },
                unknown = rows.sumOf { it.unknown }, trips = rows.sumOf { it.trips }, ruleCounts = counts,
                parseShortfall = sumParseSamples(rows),
            )
        }
        for (report in currentDays) {
            val rules = silentRuleDeath(inputs.history, report)
            if (rules.isNotEmpty()) emit(Alarm("silent_rule_death", report.platform, report.platformAppVersion, rules), installId)
            // #64: delivered once per (platform, version, rule) a UTC day, whichever install trips it first.
            val rotting = parseShortfallRise(inputs.history, report)
            if (rotting.isNotEmpty()) emit(Alarm("parse_shortfall", report.platform, report.platformAppVersion, rotting), null)
        }
        for ((previous, current) in inputs.comparisons) {
            val rules = ruleShareCliff(previous, current)
            if (rules.isNotEmpty()) emit(Alarm("rule_share_cliff", current.platform, current.platformAppVersion, rules), null)
        }
        // Round 2 (Astra): the per-report `trips` alarm is judged on the reports THIS batch accepted only — the wider
        // `current` set (other versions already stored for the day) is for rule-death aggregation, or a backfill for a
        // different version would re-emit an older version's trips alarm after the daily dedupe reset.
        val acceptedKeys = reports.map { Triple(it.day, it.platform, it.platformAppVersion) }.toSet()
        val acceptedCurrent = inputs.current.filter { Triple(it.day, it.platform, it.platformAppVersion) in acceptedKeys }
        // The per-install `trips` alarm still dedupes per install in memory; the delivered alarm names none.
        for (alarm in unknownSurge(acceptedCurrent, inputs.fleet, inputs.newClusters)) {
            emit(alarm, if (alarm.kind == "trips") installId else null)
        }
    }

    suspend fun evaluateSilence() {
        val now = clock.now()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        for (install in store.silenceInputs()) {
            if (silence(lastReportAt[install.id], startedAt, now, install.lastReportDay, today, true)) {
                emit(Alarm("silence", install.platform, install.version), install.id)
            }
        }
    }

    @Synchronized
    private fun emit(alarm: Alarm, installId: UUID?) {
        // Use delivery time under this lock; an evaluation paused across midnight cannot reset
        // the next day's set back to its old input day and allow duplicate deliveries.
        resetDay()
        val keys = if (alarm.ruleIds.isEmpty()) {
            listOf(AlarmKey(alarm.kind, installId, alarm.platform, alarm.version, null))
        } else {
            alarm.ruleIds.map { AlarmKey(alarm.kind, installId, alarm.platform, alarm.version, it) }
        }.filter { it !in raised }.ifEmpty { return }
        // #64: a long rule list goes out as several spool-sized alarms; each part's keys are recorded once it is delivered.
        for (delivery in splitForPublisher(alarm.copy(ruleIds = keys.mapNotNull { it.rule }))) {
            sink.raise(delivery)
            delivered += delivery.copy(ruleIds = delivery.ruleIds.toList())
            val rules = delivery.ruleIds.toSet()
            raised.addAll(if (rules.isEmpty()) keys else keys.filter { it.rule in rules })
            stats.record(alarm.kind)
        }
    }
}
