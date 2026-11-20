package cloud.trotter.census.server.db

import cloud.trotter.census.contract.NodePath
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.ops.RenderedSkeleton
import cloud.trotter.census.server.ops.SkeletonRender
import cloud.trotter.census.server.ops.unblinded
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate

/** One screen with tapped buttons inside retention (#87): the tap list's row. */
data class OpsTapScreen(
    val fingerprint: String,
    val platform: String,
    /** Distinct raw layouts of the fingerprint with a live sample. */
    val layouts: Int,
    /** Distinct targets (layout and owner path) with a live sample. */
    val targets: Int,
    val versions: List<String>,
    /**
     * The most non-trusted installs any ONE target was tapped by within one epoch, in the ops window. An install's tags
     * differ per target, so the targets' counts are never summed.
     */
    val installs: Int,
    val newestReceivedDay: String,
)

/** The node that took the tap, read from the target's newest sample at its owner path (#87); labels follow the gate. */
data class OpsTapOwner(
    val className: String?,
    val id: String?,
    val clickable: Boolean,
    val clickAction: Boolean,
    val hidden: Boolean,
)

/** One tapped button (#87): an owner path in one raw layout, its owner node and its support. */
data class OpsTapTarget(
    val layout: String,
    val ownerPath: String,
    /** Null when the newest sample holds no node at the path (never for a sample the validator accepted). */
    val owner: OpsTapOwner?,
    val installs28d: Int,
    val seenByTrusted: Boolean,
    val unblinded: Boolean,
    val versions: List<String>,
    val newestReceivedDay: String,
)

/** One screen's taps (#87): its targets, by layout then owner path, and whether a cluster holds the fingerprint. */
data class OpsTapDetail(
    val fingerprint: String,
    val platform: String,
    val cluster: Boolean,
    val targets: List<OpsTapTarget>,
)

/**
 * The operator's reads of the clicked tables (#87), read-only. Support follows the `/ops/rules` rule
 * ([MatchedOpsStore]): distinct non-trusted epoch tags per target within one epoch in the 28-day ops window, the
 * largest epoch, never summed across epochs or targets. An owner node's class and view id render blinded until
 * `unblinded(installs, seenByTrusted, k)`; a trusted install's own sighting unblinds its target. Hashes never render.
 * Clicked samples are not hash-expiry rewritten: they expire whole at their purge day.
 */
class ClickedOpsStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    private val provenance: String
        get() = "hash_domain IN (${policy.acceptedHashDomains.joinToString(",")}) AND filter_rev >= ${policy.minimumFilterRev}"

    /** Screens with a live clicked sample, the newest receipt first, then by fingerprint. */
    suspend fun screens(): List<OpsTapScreen> = query {
        val today = clock.today()
        val support = targetSupport(today, fingerprint = null)
        list("""SELECT fingerprint, min(platform) AS platform, count(DISTINCT layout) AS layouts,
                count(DISTINCT (layout, owner_path)) AS targets,
                COALESCE(jsonb_agg(DISTINCT platform_app_version), '[]'::jsonb)::text AS versions, max(received_day) AS newest
            FROM clicked_samples WHERE purge_after > ? AND $provenance
            GROUP BY fingerprint ORDER BY max(received_day) DESC, fingerprint""", today) { rows ->
            val fingerprint = rows.getString("fingerprint")
            OpsTapScreen(
                fingerprint, rows.getString("platform"), rows.getInt("layouts"), rows.getInt("targets"),
                versions(rows.getString("versions")),
                support.filterKeys { it.fingerprint == fingerprint }.values.maxOfOrNull { it.first } ?: 0,
                rows.getString("newest"),
            )
        }
    }

    /** One screen's taps, or null when the fingerprint has no live clicked sample. */
    suspend fun screen(fingerprint: String): OpsTapDetail? = query {
        val today = clock.today()
        data class Row(val layout: String, val ownerPath: String, val platform: String, val version: String, val received: String, val skeleton: String)
        val rows = list("""SELECT layout, owner_path, platform, platform_app_version, received_day, skeleton::text AS skeleton
            FROM clicked_samples WHERE fingerprint = ? AND purge_after > ? AND $provenance
            ORDER BY received_day DESC, day DESC, platform_app_version DESC, layout, owner_path""", fingerprint, today) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
        }
        if (rows.isEmpty()) return@query null
        val support = targetSupport(today, fingerprint)
        val cluster = select("SELECT 1 FROM clusters WHERE fingerprint = ?", fingerprint) { true } ?: false
        val targets = rows.groupBy { ClickedTarget(fingerprint, it.layout, it.ownerPath) }
            .toSortedMap(ClickedTarget.ORDER)
            .map { (target, samples) ->
                val (count, trusted) = support[target] ?: (0 to false)
                val visible = unblinded(count, trusted, policy.k)
                // The rows are newest first: the first sample is the one the owner is read from.
                val owner = ownerOf(SkeletonRender.render(samples.first().skeleton, visible), target.ownerPath)
                OpsTapTarget(
                    target.layout, target.ownerPath, owner, count, trusted, visible,
                    samples.map { it.version }.distinct().sortedWith(opsVersionOrder.reversed()),
                    samples.first().received,
                )
            }
        OpsTapDetail(fingerprint, rows.first().platform, cluster, targets)
    }

    /** The node at [ownerPath] in the gated projection, or null when the path leaves the tree. */
    private fun ownerOf(root: RenderedSkeleton, ownerPath: String): OpsTapOwner? {
        var node = root
        for (step in NodePath.parse(ownerPath) ?: return null) node = node.children.getOrNull(step) ?: return null
        return OpsTapOwner(node.className, node.id, node.clickable, node.clickAction, node.hidden)
    }

    /**
     * Per target: distinct non-trusted tags of the largest epoch in the 28-day ops window, and whether a trusted
     * install tapped it inside retention: the `layoutInstalls` rule ([MatchedOpsStore]), over `clicked_sightings`.
     */
    private fun Connection.targetSupport(today: LocalDate, fingerprint: String?): Map<ClickedTarget, Pair<Int, Boolean>> {
        val retained = "day + ${policy.retention.clusterSightingsDays} > ? AND $provenance"
        return list("""SELECT fingerprint, layout, owner_path,
                COALESCE(max(installs) FILTER (WHERE NOT trusted_epoch), 0) AS installs, bool_or(trusted_epoch) AS trusted FROM (
                    SELECT fingerprint, layout, owner_path, epoch, false AS trusted_epoch, count(DISTINCT install_tag) AS installs
                    FROM clicked_sightings WHERE NOT trusted AND day >= ? AND day <= ? AND $retained AND (?::text IS NULL OR fingerprint = ?)
                    GROUP BY fingerprint, layout, owner_path, epoch
                    UNION ALL
                    SELECT fingerprint, layout, owner_path, epoch, true, 0 FROM clicked_sightings
                    WHERE trusted AND $retained AND (?::text IS NULL OR fingerprint = ?)
                ) per_epoch GROUP BY fingerprint, layout, owner_path""",
            today.minusDays(27), today, today, fingerprint, fingerprint, today, fingerprint, fingerprint) {
            ClickedTarget(it.getString(1), it.getString(2), it.getString(3)) to (it.getInt(4) to it.getBoolean(5))
        }.toMap()
    }

    private fun versions(json: String): List<String> =
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(json).sortedWith(opsVersionOrder.reversed())

    private fun <T> Connection.list(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> =
        select(sql, *args) { rows -> buildList { do { add(read(rows)) } while (rows.next()) } } ?: emptyList()
}
