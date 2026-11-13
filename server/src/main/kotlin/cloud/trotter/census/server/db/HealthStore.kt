package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ConsumeOutcome
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.secondsToUtcMidnight
import cloud.trotter.census.server.jobs.DayReport
import cloud.trotter.census.server.jobs.FleetDay
import cloud.trotter.census.server.jobs.NewClusterCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

sealed interface HealthOutcome {
    data object StaleCredential : HealthOutcome
    data object BatchQuality : HealthOutcome
    data object Stored : HealthOutcome
    data class BudgetExhausted(val retryAfterSeconds: Long) : HealthOutcome
}

data class HealthAlarmInputs(
    val history: List<DayReport>,
    val current: List<DayReport>,
    val fleet: List<FleetDay>,
    val comparisons: List<Pair<FleetDay, FleetDay>>,
    val newClusters: List<NewClusterCount>,
)

data class SilenceInput(val id: UUID, val lastReportDay: LocalDate?, val platform: String, val version: String)

class HealthStore(private val db: Database, private val clock: Clock) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    suspend fun upsert(
        installId: UUID,
        keyHash: String,
        day: LocalDate,
        reports: List<HealthReport>,
        rejected: Map<String, Int>,
        bodyBytes: Long,
        policy: BudgetPolicy,
        now: Instant = clock.now(),
    ): HealthOutcome = query {
        val current = select(
            "SELECT 1 FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL FOR SHARE", installId, keyHash,
        ) { true } ?: false
        if (!current) return@query HealthOutcome.StaleCredential
        if (rejected.values.sum() * 5 > reports.size + rejected.values.sum()) {
            recordIngestCounters(installId, day, 0, rejected)
            return@query HealthOutcome.BatchQuality
        }
        // #79: the per-install health-report quota, decided under the ledger row lock before any byte is spent.
        update("INSERT INTO ingest_ledger (install_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING", installId, day)
        val reported = select(
            "SELECT health_reports FROM ingest_ledger WHERE install_id = ? AND day = ? FOR UPDATE", installId, day,
        ) { it.getInt(1) } ?: error("Missing ledger row")
        if (reported.toLong() + reports.size > policy.dailyHealthReports) {
            return@query HealthOutcome.BudgetExhausted(secondsToUtcMidnight(now))
        }
        when (val consumed = consumeLedger(installId, day, bodyBytes, 0, null, policy, now)) {
            is ConsumeOutcome.BudgetExhausted -> return@query HealthOutcome.BudgetExhausted(consumed.retryAfterSeconds)
            is ConsumeOutcome.Consumed -> Unit
        }
        update(
            "UPDATE ingest_ledger SET health_reports = health_reports + ? WHERE install_id = ? AND day = ?",
            reports.size, installId, day,
        )
        val touched = reports.map { FleetKey(it.day, it.platform, it.platformAppVersion) }
        // Claim and lock each fleet row BEFORE the daily writes; recompute after them.
        lockFleet(touched)
        for (report in reports) {
            update(
                """INSERT INTO health_daily (install_id, day, platform, platform_app_version, admitted, unknown, trips, rule_counts, ruleset_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (install_id, day, platform, platform_app_version) DO UPDATE SET
                        admitted = GREATEST(health_daily.admitted, EXCLUDED.admitted),
                        unknown = GREATEST(health_daily.unknown, EXCLUDED.unknown),
                        trips = GREATEST(health_daily.trips, EXCLUDED.trips),
                        rule_counts = EXCLUDED.rule_counts, ruleset_version = EXCLUDED.ruleset_version""",
                installId, report.day, report.platform, report.platformAppVersion, report.admitted, report.unknown,
                report.trips, Json.encodeToString(report.ruleCounts), report.rulesetVersion,
            )
        }
        recomputeFleet(touched)
        recordIngestCounters(installId, day, 0, rejected)
        HealthOutcome.Stored
    }

    /** One credential-bound transaction; reports identify touched keys, while decisions use stored counters. */
    suspend fun alarmInputs(installId: UUID, keyHash: String, reports: List<HealthReport>, today: LocalDate): HealthAlarmInputs? = query {
        val current = select(
            "SELECT 1 FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL FOR SHARE", installId, keyHash,
        ) { true } ?: false
        if (!current || reports.isEmpty()) return@query null
        val firstDay = reports.minOf { it.day }.minusDays(28)
        val lastDay = reports.maxOf { it.day }
        val history = select(
            "SELECT * FROM health_daily WHERE install_id = ? AND day >= ? AND day <= ? ORDER BY day, platform, platform_app_version",
            installId, firstDay, lastDay,
        ) { rows -> buildList { do { add(rows.dayReport()) } while (rows.next()) } } ?: emptyList()
        val touched = reports.map { FleetKey(it.day, it.platform, it.platformAppVersion) }.toSet()
        val touchedDays = reports.map { it.platform to it.day }.toSet()
        val currentReports = history.filter { (it.platform to it.day) in touchedDays }
        val fleet = mutableListOf<FleetDay>()
        val comparisons = mutableListOf<Pair<FleetDay, FleetDay>>()
        for (key in touched) {
            val currentFleet = select(
                "SELECT * FROM health_fleet_daily WHERE day = ? AND platform = ? AND platform_app_version = ?",
                key.day, key.platform, key.version,
            ) { it.fleetDay() } ?: continue
            fleet += currentFleet
            // Latest day of each OTHER version, then the closest version whose last day is earlier.
            val previous = select(
                """SELECT * FROM (
                        SELECT DISTINCT ON (platform_app_version) * FROM health_fleet_daily
                        WHERE platform = ? AND platform_app_version <> ? ORDER BY platform_app_version, day DESC
                    ) versions WHERE day < ? ORDER BY day DESC, platform_app_version LIMIT 1""",
                key.platform, key.version, key.day,
            ) { it.fleetDay() }
            if (previous != null) comparisons += previous to currentFleet
        }
        val newClusters = select(
            """SELECT c.platform, s.platform_app_version, count(DISTINCT c.fingerprint) AS count
                FROM clusters c JOIN cluster_sightings_v5 s ON s.fingerprint = c.fingerprint
                WHERE c.first_seen_day = ? AND s.day = ? GROUP BY c.platform, s.platform_app_version""", today, today,
        ) { rows -> buildList {
            do { add(NewClusterCount(rows.getString("platform"), rows.getString("platform_app_version"), rows.getInt("count"))) } while (rows.next())
        } } ?: emptyList()
        HealthAlarmInputs(history, currentReports, fleet, comparisons, newClusters)
    }

    suspend fun silenceInputs(): List<SilenceInput> = query {
        select(
            """SELECT i.install_id, h.day, h.platform, h.platform_app_version FROM installs i
                LEFT JOIN LATERAL (
                    SELECT day, platform, platform_app_version FROM health_daily WHERE install_id = i.install_id
                    ORDER BY day DESC, platform, platform_app_version LIMIT 1
                ) h ON true WHERE i.trusted = true AND i.revoked_at IS NULL""",
        ) { rows -> buildList {
            do {
                add(SilenceInput(
                    rows.getObject("install_id", UUID::class.java), rows.getObject("day", LocalDate::class.java),
                    rows.getString("platform") ?: "_unknown", rows.getString("platform_app_version") ?: "unknown",
                ))
            } while (rows.next())
        } } ?: emptyList()
    }

    private fun ResultSet.dayReport(): DayReport = DayReport(
        getObject("day", LocalDate::class.java), getString("platform"), getString("platform_app_version"),
        getLong("admitted"), getLong("unknown"), getLong("trips"), Json.decodeFromString(getString("rule_counts")),
    )
    private fun ResultSet.fleetDay(): FleetDay = FleetDay(
        getObject("day", LocalDate::class.java), getString("platform"), getString("platform_app_version"),
        getInt("installs_reporting"), getLong("admitted"), getLong("unknown"), Json.decodeFromString(getString("rule_counts")),
    )
}

/** One `health_fleet_daily` row's key. */
internal data class FleetKey(val day: LocalDate, val platform: String, val version: String)

/**
 * Claims and locks each fleet row in one global order (day, platform, version), so ingest, deletion and the
 * retention fold never deadlock on each other. Call before changing the `health_daily` rows behind [keys].
 */
internal fun Connection.lockFleet(keys: Collection<FleetKey>) {
    for (key in keys.distinct().sortedWith(compareBy({ it.day }, { it.platform }, { it.version }))) {
        update(
            """INSERT INTO health_fleet_daily (day, platform, platform_app_version, installs_reporting, admitted, unknown, rule_counts)
                VALUES (?, ?, ?, 0, 0, 0, '{}'::jsonb) ON CONFLICT DO NOTHING""", key.day, key.platform, key.version,
        )
        select(
            "SELECT 1 FROM health_fleet_daily WHERE day = ? AND platform = ? AND platform_app_version = ? FOR UPDATE",
            key.day, key.platform, key.version,
        ) { true } ?: error("Missing fleet row")
    }
}

/**
 * Recomputes each fleet row from the per-install rows present now. A separate statement after [lockFleet] reads a
 * fresh snapshot, so a concurrent install's committed contribution is included.
 */
internal fun Connection.recomputeFleet(keys: Collection<FleetKey>) {
    for (key in keys.distinct()) {
        update(
            """WITH daily AS (
                    SELECT * FROM health_daily WHERE day = ? AND platform = ? AND platform_app_version = ?
                ), totals AS (
                    SELECT count(*) AS installs, COALESCE(sum(admitted), 0) AS admitted, COALESCE(sum(unknown), 0) AS unknown FROM daily
                ), rules AS (
                    SELECT key, sum(value::bigint) AS total FROM daily, jsonb_each_text(rule_counts) GROUP BY key
                )
                UPDATE health_fleet_daily SET installs_reporting = totals.installs, admitted = totals.admitted,
                    unknown = totals.unknown, rule_counts = (SELECT COALESCE(jsonb_object_agg(key, total), '{}'::jsonb) FROM rules)
                FROM totals WHERE day = ? AND platform = ? AND platform_app_version = ?""",
            key.day, key.platform, key.version, key.day, key.platform, key.version,
        )
    }
}

/** [lockFleet] then [recomputeFleet]: the fold before per-install rows are forgotten (#79). */
internal fun Connection.refreshFleet(keys: Collection<FleetKey>) {
    lockFleet(keys)
    recomputeFleet(keys)
}
