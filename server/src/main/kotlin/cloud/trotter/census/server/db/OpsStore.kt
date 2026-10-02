package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.ops.RenderedSkeleton
import cloud.trotter.census.server.ops.SkeletonRender
import cloud.trotter.census.server.ops.unblinded
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.log2

@Serializable
data class OpsSample(val platformAppVersion: String, val receivedDay: String, val skeleton: RenderedSkeleton)

@Serializable
data class OpsCluster(
    val fingerprint: String,
    val platform: String,
    val status: String,
    val firstSeenDay: String,
    val lastSeenDay: String,
    val distinctInstalls28d: Int,
    val seenByTrusted: Boolean,
    val sightings28d: Long,
    val versions: List<String>,
    val newWithVersion: Boolean,
    val unblinded: Boolean,
    val resolvedRuleId: String? = null,
    /** Free text an operator typed about a cluster — serialised only when [unblinded] (#1175: an annotation can quote a label). */
    val notes: String? = null,
    val notesWithheld: Boolean = false,
    val samples: List<OpsSample>? = null,
)

@Serializable
data class OpsClusterGroup(val platformAppVersion: String, val clusters: List<OpsCluster>)

/** Numerical segments, with missing segments treated as zero; unknown sorts before numeric versions. */
internal val opsVersionOrder: Comparator<String> = Comparator { left, right ->
    val a = left.split('.').map { it.toIntOrNull() ?: -1 }
    val b = right.split('.').map { it.toIntOrNull() ?: -1 }
    (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { index ->
        (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 }).takeIf { it != 0 }
    } ?: 0
}

/** Every public call has one transaction; projection never selects install credentials. */
class OpsStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    suspend fun clusters(version: String? = null, status: String? = null, limit: Int = 50, includeSamples: Boolean = false): List<OpsClusterGroup> = query {
        val today = clock.today()
        val rows = clusterRows(today, status = status)
        val firstDays = versionFirstDays()
        val groups = rows.flatMap { row -> row.versions.map { it to row } }.groupBy({ it.first }, { it.second })
        var remaining = limit
        groups.keys.filter { version == null || it == version }.sortedWith(opsVersionOrder.reversed()).mapNotNull { label ->
            val ranked = groups.getValue(label).sortedWith(
                compareByDescending<OpsCluster> { score(it, today) }.thenBy { it.fingerprint },
            ).take(remaining)
            remaining -= ranked.size
            if (ranked.isEmpty()) null else OpsClusterGroup(label, ranked.map { row ->
                row.copy(
                    newWithVersion = newWithVersion(row, label, firstDays),
                    samples = if (includeSamples) samples(row) else null,
                )
            })
        }
    }

    suspend fun cluster(fingerprint: String): OpsCluster? = query {
        val row = clusterRows(clock.today(), fingerprint = fingerprint).singleOrNull() ?: return@query null
        val newest = row.versions.maxWithOrNull(opsVersionOrder)
        row.copy(newWithVersion = newest != null && newWithVersion(row, newest, versionFirstDays()), samples = samples(row))
    }

    private fun Connection.clusterRows(today: LocalDate, status: String? = null, fingerprint: String? = null): List<OpsCluster> =
        select(
            """SELECT c.*, count(DISTINCT s.install_id) FILTER (WHERE NOT i.trusted AND s.day >= ? AND s.day <= ?) AS installs,
                COALESCE(bool_or(i.trusted), false) AS trusted,
                COALESCE(sum(s.count) FILTER (WHERE s.day >= ? AND s.day <= ?), 0) AS sightings,
                COALESCE(jsonb_agg(DISTINCT s.platform_app_version) FILTER (WHERE s.platform_app_version IS NOT NULL), '[]'::jsonb) AS versions
                FROM clusters c LEFT JOIN cluster_sightings s ON s.fingerprint = c.fingerprint
                LEFT JOIN installs i ON i.install_id = s.install_id
                WHERE (?::text IS NULL OR c.status = ?) AND (?::text IS NULL OR c.fingerprint = ?)
                GROUP BY c.fingerprint""",
            today.minusDays(27), today, today.minusDays(27), today, status, status, fingerprint, fingerprint,
        ) { rows -> buildList {
            do {
                val count = rows.getInt("installs")
                val trusted = rows.getBoolean("trusted")
                val visible = unblinded(count, trusted, policy.k)
                val notes = rows.getString("notes")
                add(OpsCluster(
                    rows.getString("fingerprint"), rows.getString("platform"), rows.getString("status"),
                    rows.getString("first_seen_day"), rows.getString("last_seen_day"), count, trusted, rows.getLong("sightings"),
                    Json.decodeFromString<List<String>>(rows.getString("versions")).sortedWith(opsVersionOrder.reversed()),
                    false, visible, rows.getString("resolved_rule_id"), notes?.takeIf { visible }, notesWithheld = notes != null && !visible,
                ))
            } while (rows.next())
        } } ?: emptyList()

    private fun Connection.versionFirstDays(): Map<Pair<String, String>, LocalDate> = select(
        """SELECT c.platform, s.platform_app_version, min(s.day) AS day FROM cluster_sightings s
            JOIN clusters c ON c.fingerprint = s.fingerprint GROUP BY c.platform, s.platform_app_version""",
    ) { rows -> buildMap {
        do { put(rows.getString("platform") to rows.getString("platform_app_version"), rows.getObject("day", LocalDate::class.java)) } while (rows.next())
    } } ?: emptyMap()

    private fun newWithVersion(row: OpsCluster, version: String, firstDays: Map<Pair<String, String>, LocalDate>): Boolean {
        val first = firstDays[row.platform to version] ?: return false
        return LocalDate.parse(row.firstSeenDay) >= first && row.versions.none { opsVersionOrder.compare(it, version) < 0 }
    }

    private fun score(row: OpsCluster, today: LocalDate): Double {
        val age = ChronoUnit.DAYS.between(LocalDate.parse(row.lastSeenDay), today)
        val recency = when { age <= 7 -> 1.0; age <= 28 -> 0.5; else -> 0.1 }
        return row.distinctInstalls28d * log2(1.0 + row.sightings28d) * recency
    }

    private fun Connection.samples(row: OpsCluster): List<OpsSample> = select(
        "SELECT platform_app_version, received_day, skeleton FROM cluster_samples WHERE fingerprint = ? ORDER BY received_day DESC, platform_app_version",
        row.fingerprint,
    ) { rows -> buildList {
        do { add(OpsSample(rows.getString("platform_app_version"), rows.getString("received_day"), SkeletonRender.render(rows.getString("skeleton"), row.unblinded))) } while (rows.next())
    } } ?: emptyList()

    suspend fun status(fingerprint: String, status: String, resolvedRuleId: String?, notes: String?): Boolean = query {
        update("UPDATE clusters SET status = ?, resolved_rule_id = ?, notes = ? WHERE fingerprint = ?", status, resolvedRuleId, notes, fingerprint) == 1
    }

    suspend fun installs(limit: Int = 50): JsonArray = query {
        array("""SELECT left(install_id::text, 8) AS prefix, created_day, last_seen_day, trusted,
            revoked_at IS NOT NULL AS revoked, last_app_version, attested_verdict IS NOT NULL AS attested
            FROM installs ORDER BY last_seen_day DESC, install_id LIMIT ?""", limit) { row -> buildJsonObject {
            put("installIdPrefix", row.getString("prefix")); put("createdDay", row.getString("created_day"))
            put("lastSeenDay", row.getString("last_seen_day")); put("trusted", row.getBoolean("trusted"))
            put("revoked", row.getBoolean("revoked")); put("attested", row.getBoolean("attested"))
            row.getString("last_app_version")?.takeIf {
                WireGrammars.appVersion.matches(it) || WireGrammars.platformAppVersion.matches(it)
            }?.let { put("lastAppVersion", it) }
        } }
    }

    suspend fun trust(id: UUID, trusted: Boolean): Boolean = query {
        update("UPDATE installs SET trusted = ? WHERE install_id = ?", trusted, id) == 1
    }

    suspend fun revoke(id: UUID): Boolean = query {
        update("UPDATE installs SET revoked_at = COALESCE(revoked_at, ?) WHERE install_id = ?", clock.now().atOffset(ZoneOffset.UTC), id) == 1
    }

    suspend fun health(days: Int = 7): JsonObject = query {
        val today = clock.today()
        val first = today.minusDays(days.toLong() - 1)
        val fleet = array("SELECT * FROM health_fleet_daily WHERE day >= ? AND day <= ? ORDER BY day DESC, platform, platform_app_version", first, today) { row -> buildJsonObject {
            put("day", row.getString("day")); put("platform", row.getString("platform")); put("platformAppVersion", row.getString("platform_app_version"))
            put("installsReporting", row.getInt("installs_reporting")); put("admitted", row.getLong("admitted")); put("unknown", row.getLong("unknown"))
            put("ruleCounts", Json.parseToJsonElement(row.getString("rule_counts")))
        } }
        val installs = array("""SELECT left(install_id::text, 8) AS prefix, day, platform, platform_app_version, admitted, unknown, trips
            FROM health_daily WHERE day >= ? AND day <= ? ORDER BY day DESC, install_id, platform, platform_app_version""", first, today) { row -> buildJsonObject {
            put("installIdPrefix", row.getString("prefix")); put("day", row.getString("day")); put("platform", row.getString("platform"))
            put("version", row.getString("platform_app_version")); put("admitted", row.getLong("admitted"))
            put("unknown", row.getLong("unknown")); put("trips", row.getLong("trips"))
        } }
        buildJsonObject { put("fleet", fleet); put("installs", installs) }
    }

    suspend fun ledger(day: LocalDate = clock.today()): JsonObject = query {
        var bytes = 0L
        var accepted = 0L
        var duplicate = 0L
        var batches = 0L
        val rejected = sortedMapOf<String, Long>()
        val installs = array("""SELECT left(install_id::text, 8) AS prefix, bytes, accepted, duplicate, rejected, cardinality(batch_ids) AS batches
            FROM ingest_ledger WHERE day = ? ORDER BY install_id""", day) { row ->
            val reasons = Json.decodeFromString<Map<String, Long>>(row.getString("rejected"))
            bytes += row.getLong("bytes"); accepted += row.getLong("accepted"); duplicate += row.getLong("duplicate"); batches += row.getLong("batches")
            reasons.forEach { (reason, count) -> rejected[reason] = rejected.getOrDefault(reason, 0) + count }
            buildJsonObject {
                put("installIdPrefix", row.getString("prefix")); put("bytes", row.getLong("bytes")); put("accepted", row.getLong("accepted"))
                put("duplicate", row.getLong("duplicate")); put("batches", row.getLong("batches"))
                put("rejected", JsonObject(reasons.mapValues { JsonPrimitive(it.value) }))
            }
        }
        buildJsonObject {
            put("installs", installs)
            put("totals", buildJsonObject {
                put("bytes", bytes); put("accepted", accepted); put("duplicate", duplicate); put("batches", batches)
                put("rejected", JsonObject(rejected.mapValues { JsonPrimitive(it.value) }))
            })
        }
    }

    suspend fun vocabularyQueue(limit: Int = 50): JsonArray = query { queue(limit) }

    suspend fun vocabularyQueueCount(): Long = query {
        select("SELECT count(*) FROM ($QUEUE_SQL) queue", policy.k) { it.getLong(1) } ?: 0L
    }

    private fun Connection.queue(limit: Int): JsonArray = array("$QUEUE_SQL ORDER BY first_day, token_hash LIMIT ?", policy.k, limit) { row -> buildJsonObject {
        put("tokenHash", row.getString("token_hash")); put("kind", row.getString("kind")); put("distinctInstalls", row.getInt("installs"))
        put("firstDay", row.getString("first_day")); put("lastDay", row.getString("last_day"))
    } }

    /** Only a currently eligible hash may be resolved. Concurrent resolutions never overwrite each other. */
    suspend fun resolve(tokenHash: String, clearText: String?, source: String, reject: Boolean): Boolean = query {
        val today = clock.today()
        update(
            """INSERT INTO vocabulary (token_hash, kind, distinct_installs_at_promotion, promoted_day, clear_text, unblinded_day, source, status)
                SELECT token_hash, kind, installs, ?, ?, ?, ?, ? FROM ($QUEUE_SQL) queue WHERE token_hash = ?
                ON CONFLICT (token_hash) DO NOTHING""",
            today, if (reject) null else clearText, if (reject) null else today, source,
            if (reject) "rejected" else "unblinded", policy.k, tokenHash,
        ) == 1
    }

    private fun Connection.array(sql: String, vararg args: Any?, read: (ResultSet) -> JsonObject): JsonArray =
        JsonArray(select(sql, *args) { rows -> buildList { do { add(read(rows)) } while (rows.next()) } } ?: emptyList())

    companion object {
        private const val QUEUE_SQL = """SELECT t.token_hash, min(t.kind) AS kind, count(DISTINCT t.install_id) AS installs,
            min(t.first_day) AS first_day, max(t.last_day) AS last_day FROM token_sightings t
            JOIN installs i ON i.install_id = t.install_id WHERE NOT i.trusted
            AND NOT EXISTS (SELECT 1 FROM vocabulary v WHERE v.token_hash = t.token_hash)
            GROUP BY t.token_hash HAVING count(DISTINCT t.install_id) >= ?"""
    }
}
