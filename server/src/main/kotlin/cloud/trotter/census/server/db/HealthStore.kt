package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ConsumeOutcome
import cloud.trotter.census.server.ingest.HealthReport
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
        when (val consumed = consumeLedger(installId, day, bodyBytes, 0, null, policy, now)) {
            is ConsumeOutcome.BudgetExhausted -> return@query HealthOutcome.BudgetExhausted(consumed.retryAfterSeconds)
            is ConsumeOutcome.Consumed -> Unit
        }
        val touched = reports.map { FleetKey(it.day, it.platform, it.platformAppVersion) }.distinct()
            .sortedWith(compareBy({ it.day }, { it.platform }, { it.version }))
        // Claim and lock each fleet row BEFORE the daily writes. Recompute in a subsequent statement's
        // snapshot, so a concurrent second install includes the first install's committed contribution.
        for (key in touched) {
            update(
                """INSERT INTO health_fleet_daily (day, platform, platform_app_version, installs_reporting, admitted, unknown, rule_counts)
                    VALUES (?, ?, ?, 0, 0, 0, '{}'::jsonb) ON CONFLICT DO NOTHING""", key.day, key.platform, key.version,
            )
            select(
                "SELECT 1 FROM health_fleet_daily WHERE day = ? AND platform = ? AND platform_app_version = ? FOR UPDATE",
                key.day, key.platform, key.version,
            ) { true } ?: error("Missing fleet row")
        }
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
        for (key in touched) {
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
        val prefix = installId.toString().take(8)
        val history = select(
            "SELECT * FROM health_daily WHERE install_id = ? AND day >= ? AND day <= ? ORDER BY day, platform, platform_app_version",
            installId, firstDay, lastDay,
        ) { rows -> buildList { do { add(rows.dayReport(prefix)) } while (rows.next()) } } ?: emptyList()
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
                FROM clusters c JOIN cluster_sightings s ON s.fingerprint = c.fingerprint
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

    private data class FleetKey(val day: LocalDate, val platform: String, val version: String)
    private fun ResultSet.dayReport(prefix: String): DayReport = DayReport(
        getObject("day", LocalDate::class.java), getString("platform"), getString("platform_app_version"),
        getLong("admitted"), getLong("unknown"), getLong("trips"), Json.decodeFromString(getString("rule_counts")), prefix,
    )
    private fun ResultSet.fleetDay(): FleetDay = FleetDay(
        getObject("day", LocalDate::class.java), getString("platform"), getString("platform_app_version"),
        getInt("installs_reporting"), getLong("admitted"), getLong("unknown"), Json.decodeFromString(getString("rule_counts")),
    )
}
