package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.ops.SkeletonRender
import cloud.trotter.census.server.ops.unblinded
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.LocalDate

/** One rule with matched samples inside retention (#69): the per-rule list's row. */
@Serializable
data class OpsMatchedRule(
    val ruleId: String,
    val platform: String,
    /** Distinct layouts (fingerprints) with a live sample. */
    val layouts: Int,
    val versions: List<String>,
    /**
     * The most non-trusted installs any ONE layout of the rule was seen by within one epoch, in the ops window. An
     * install's tags differ per layout, so the layouts' counts are never summed.
     */
    val installs: Int,
    val newestReceivedDay: String,
)

/** One layout of a rule (#69): its samples newest first, its slot evidence, and whether a cluster already holds it. */
@Serializable
data class OpsMatchedLayout(
    val fingerprint: String,
    /** True when a cluster with this fingerprint is resolved to the rule: the layout the rule was drafted from. */
    val sameAsCluster: Boolean,
    val installs28d: Int,
    val seenByTrusted: Boolean,
    val unblinded: Boolean,
    val versions: List<String>,
    val samples: List<OpsSample>,
    val slots: List<SlotVariantReading>,
)

/** A cluster resolved to the rule (#69): one of the rule's older layouts, from the UNKNOWN catalogue. */
@Serializable
data class OpsOlderLayout(val fingerprint: String, val status: String, val lastSeenDay: String)

/** The per-rule view (#69): the rule's matched layouts, newest sample first, and its older layouts. */
@Serializable
data class OpsMatchedRuleDetail(
    val ruleId: String,
    val platform: String,
    val layouts: List<OpsMatchedLayout>,
    val olderLayouts: List<OpsOlderLayout>,
)

/**
 * The operator's reads of the matched tables (#69). Install counts follow the cluster rule: distinct non-trusted
 * epoch tags within one epoch in the 28-day ops window, the largest epoch; a sample's class and view id render
 * blinded until `unblinded(installs, seenByTrusted, k)`, and slot classes read `unknown` below k. Hashes never
 * render. Matched samples are not hash-expiry rewritten: they expire whole at their purge day.
 */
class MatchedOpsStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    private val provenance: String
        get() = "hash_domain IN (${policy.acceptedHashDomains.joinToString(",")}) AND filter_rev >= ${policy.minimumFilterRev}"

    /** Rules with a live matched sample, the newest receipt first, then by rule id. */
    suspend fun rules(): List<OpsMatchedRule> = query {
        val today = clock.today()
        val installs = layoutInstalls(today, ruleId = null)
        list("""SELECT rule_id, min(platform) AS platform, count(DISTINCT fingerprint) AS layouts,
                COALESCE(jsonb_agg(DISTINCT platform_app_version), '[]'::jsonb) AS versions, max(received_day) AS newest
            FROM matched_samples WHERE purge_after > ? AND $provenance
            GROUP BY rule_id ORDER BY max(received_day) DESC, rule_id""", today) { rows ->
            val ruleId = rows.getString("rule_id")
            OpsMatchedRule(ruleId, rows.getString("platform"), rows.getInt("layouts"), versions(rows.getString("versions")),
                installs.filterKeys { it.first == ruleId }.values.maxOfOrNull { it.first } ?: 0, rows.getString("newest"))
        }
    }

    /** Whether [ruleId] has a live matched sample: the cluster detail links to the rule view only then. */
    suspend fun hasSamples(ruleId: String): Boolean = query {
        select("SELECT 1 FROM matched_samples WHERE rule_id = ? AND purge_after > ? AND $provenance LIMIT 1", ruleId, clock.today()) { true } ?: false
    }

    /** The rule's view, or null when it has no live matched sample. */
    suspend fun rule(ruleId: String): OpsMatchedRuleDetail? = query {
        val today = clock.today()
        data class Row(val fingerprint: String, val platform: String, val version: String, val received: String, val skeleton: String)
        val rows = list("""SELECT fingerprint, platform, platform_app_version, received_day, skeleton::text AS skeleton FROM matched_samples
            WHERE rule_id = ? AND purge_after > ? AND $provenance ORDER BY received_day DESC, platform_app_version, fingerprint""", ruleId, today) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }
        if (rows.isEmpty()) return@query null
        val installs = layoutInstalls(today, ruleId)
        val resolved = list("SELECT fingerprint, status, last_seen_day FROM clusters WHERE resolved_rule_id = ? ORDER BY last_seen_day DESC, fingerprint", ruleId) {
            OpsOlderLayout(it.getString(1), it.getString(2), it.getString(3))
        }
        val groups = rows.groupBy { it.fingerprint }
        val slots = slotReadings(policy, today, groups.keys.toList(), SlotTable.matched(ruleId))
        val layouts = groups.map { (fingerprint, samples) ->
            val (count, trusted) = installs[ruleId to fingerprint] ?: (0 to false)
            val visible = unblinded(count, trusted, policy.k)
            OpsMatchedLayout(
                fingerprint, resolved.any { it.fingerprint == fingerprint }, count, trusted, visible,
                samples.map { it.version }.distinct().sortedWith(opsVersionOrder.reversed()),
                samples.map { OpsSample(it.version, it.received, skeleton = SkeletonRender.render(it.skeleton, visible)) },
                slots[fingerprint].orEmpty(),
            )
        }
        OpsMatchedRuleDetail(ruleId, rows.first().platform, layouts, resolved)
    }

    /**
     * Per (rule, fingerprint): distinct non-trusted tags of the largest epoch in the 28-day ops window, and whether a
     * trusted install saw it inside retention: the `clusterRows` rule (OpsStore), over `matched_sightings`.
     */
    private fun Connection.layoutInstalls(today: LocalDate, ruleId: String?): Map<Pair<String, String>, Pair<Int, Boolean>> {
        val retained = "day + ${policy.retention.clusterSightingsDays} > ? AND $provenance"
        return list("""SELECT rule_id, fingerprint,
                COALESCE(max(installs) FILTER (WHERE NOT trusted_epoch), 0) AS installs, bool_or(trusted_epoch) AS trusted FROM (
                    SELECT rule_id, fingerprint, epoch, false AS trusted_epoch, count(DISTINCT install_tag) AS installs
                    FROM matched_sightings WHERE NOT trusted AND day >= ? AND day <= ? AND $retained AND (?::text IS NULL OR rule_id = ?)
                    GROUP BY rule_id, fingerprint, epoch
                    UNION ALL
                    SELECT rule_id, fingerprint, epoch, true, 0 FROM matched_sightings
                    WHERE trusted AND $retained AND (?::text IS NULL OR rule_id = ?)
                ) per_epoch GROUP BY rule_id, fingerprint""",
            today.minusDays(27), today, today, ruleId, ruleId, today, ruleId, ruleId) {
            (it.getString(1) to it.getString(2)) to (it.getInt(3) to it.getBoolean(4))
        }.toMap()
    }

    private fun versions(json: String): List<String> = Json.decodeFromString<List<String>>(json).sortedWith(opsVersionOrder.reversed())

    private fun <T> Connection.list(sql: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        select(sql, *args) { rows -> buildList { do { add(read(rows)) } while (rows.next()) } } ?: emptyList()
}
