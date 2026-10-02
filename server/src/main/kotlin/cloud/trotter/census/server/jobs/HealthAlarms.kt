package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.db.HealthStore
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.today
import org.slf4j.LoggerFactory
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
    val installPrefix: String? = null,
)

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

data class Alarm(
    val kind: String,
    val platform: String,
    val version: String,
    val installPrefix: String? = null,
    val ruleIds: List<String> = emptyList(),
)

fun interface AlarmSink {
    fun raise(alarm: Alarm)
}

class LoggingAlarmSink : AlarmSink {
    override fun raise(alarm: Alarm) {
        log.warn(
            "alarm kind={} platform={} version={} install_prefix={} rule_ids={}",
            alarm.kind, alarm.platform.takeIf { platformPattern.matches(it) } ?: "[redacted]",
            alarm.version.takeIf { WireGrammars.platformAppVersion.matches(it) } ?: "[redacted]",
            alarm.installPrefix ?: "-", alarm.ruleIds.map { it.takeIf { rule -> WireGrammars.ruleId.matches(rule) } ?: "[redacted]" },
        )
    }

    private val log = LoggerFactory.getLogger("Alarm")
    private val platformPattern = Regex("^[a-z_][a-z0-9_]{0,31}$")
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
        add(Alarm("trips", report.platform, report.platformAppVersion, report.installPrefix))
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
    private data class AlarmKey(val kind: String, val install: UUID?, val platform: String, val version: String, val rule: String?)
    private val lastReportAt = ConcurrentHashMap<UUID, Instant>()
    // Read lazily (at the first silence evaluation, not at construction): the process clock is deliberately not touched
    // while wiring the module — a request must see exactly one clock read — and starting the 48 h window at the first
    // purge-cadence evaluation only fails further toward NO alarm after a restart (documented in OPERATOR.md).
    private val startedAt: Instant by lazy { startedAt ?: clock.now() }
    private var dedupeDay: LocalDate? = null
    private val raised = mutableSetOf<AlarmKey>()

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
            )
        }
        for (report in currentDays) {
            val rules = silentRuleDeath(inputs.history, report)
            if (rules.isNotEmpty()) emit(Alarm("silent_rule_death", report.platform, report.platformAppVersion, report.installPrefix, rules), installId)
        }
        for ((previous, current) in inputs.comparisons) {
            val rules = ruleShareCliff(previous, current)
            if (rules.isNotEmpty()) emit(Alarm("rule_share_cliff", current.platform, current.platformAppVersion, ruleIds = rules), null)
        }
        for (alarm in unknownSurge(inputs.current, inputs.fleet, inputs.newClusters)) {
            emit(alarm, if (alarm.installPrefix == null) null else installId)
        }
    }

    suspend fun evaluateSilence() {
        val now = clock.now()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        for (install in store.silenceInputs()) {
            if (silence(lastReportAt[install.id], startedAt, now, install.lastReportDay, today, true)) {
                emit(Alarm("silence", install.platform, install.version, install.id.toString().take(8)), install.id)
            }
        }
    }

    @Synchronized
    private fun emit(alarm: Alarm, installId: UUID?) {
        // Use delivery time under this lock; an evaluation paused across midnight cannot reset
        // the next day's set back to its old input day and allow duplicate deliveries.
        val today = clock.today()
        if (dedupeDay != today) {
            raised.clear()
            dedupeDay = today
        }
        val keys = if (alarm.ruleIds.isEmpty()) {
            listOf(AlarmKey(alarm.kind, installId, alarm.platform, alarm.version, null))
        } else {
            alarm.ruleIds.map { AlarmKey(alarm.kind, installId, alarm.platform, alarm.version, it) }
        }.filter { it !in raised }.ifEmpty { return }
        sink.raise(alarm.copy(ruleIds = keys.mapNotNull { it.rule }))
        raised.addAll(keys)
        stats.record(alarm.kind)
    }
}
