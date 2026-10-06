package cloud.trotter.census.server.db

import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.server.ops.RenderedNotificationSkeleton
import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.auth.sha256Hex
import cloud.trotter.census.server.ingest.WireGrammars
import cloud.trotter.census.server.ops.RenderedSkeleton
import cloud.trotter.census.server.ops.RenderedWireframe
import cloud.trotter.census.server.ops.SkeletonRender
import cloud.trotter.census.server.ops.WireframeRender
import cloud.trotter.census.server.ops.unblinded
import cloud.trotter.census.server.today
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.log2
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

@Serializable
data class OpsSample(
    val platformAppVersion: String, val receivedDay: String,
    val skeleton: RenderedSkeleton? = null, val notification: RenderedNotificationSkeleton? = null,
) {
    init { require((skeleton == null) != (notification == null)) }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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
    val screenClass: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val hasDraft: Boolean = false,
    @kotlinx.serialization.Transient val draftDay: String? = null,
    @kotlinx.serialization.Transient val wireframe: RenderedWireframe? = null,
    val kind: SkeletonKind = SkeletonKind.SCREEN,
)

@Serializable
data class OpsClusterGroup(val platformAppVersion: String, val clusters: List<OpsCluster>)

@Serializable
data class OpsClusterSummaryRow(val platform: String, val platformAppVersion: String?, val total: Int, val byStatus: Map<String, Int>, val byClass: Map<String, Int> = emptyMap(), val kind: SkeletonKind = SkeletonKind.SCREEN)

@Serializable
data class OpsClusterPage(val platform: String, val platformAppVersion: String?, val status: String?, val total: Int, val page: Int, val pageSize: Int, val pageCount: Int, val clusters: List<OpsCluster>, val byClass: Map<String, Int> = emptyMap(), val kind: SkeletonKind? = null)

/** Hash-free vocabulary display projection; no plaintext or token identifier enters HTML. */
data class OpsVocabularyDisplay(val kind: String, val distinctInstalls: Int, val firstDay: String, val lastDay: String)

/** Review order: what an operator should look at first. The one owner of the status vocabulary. */
val CLUSTER_STATUSES: List<String> = listOf("new", "triaged", "drafted", "resolved", "ignored")

/** distinctInstalls28d × log2(1 + sightings28d) × recency (1.0 within 7 days, 0.5 within 28, else 0.1). */
internal fun reviewScore(row: OpsCluster, today: LocalDate): Double {
    val age = ChronoUnit.DAYS.between(LocalDate.parse(row.lastSeenDay), today)
    val recency = when { age <= 7 -> 1.0; age <= 28 -> 0.5; else -> 0.1 }
    return row.distinctInstalls28d * log2(1.0 + row.sightings28d) * recency
}

/** The review page's order: status rank (unknown last) → score descending → fingerprint. The one owner; pure. */
internal fun reviewOrder(today: LocalDate): Comparator<OpsCluster> =
    compareBy<OpsCluster> { CLUSTER_STATUSES.indexOf(it.status).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
        .thenByDescending { reviewScore(it, today) }.thenBy { it.fingerprint }

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

    suspend fun clusters(version: String? = null, status: String? = null, limit: Int = 50, includeSamples: Boolean = false, platform: String? = null, kind: SkeletonKind? = null): List<OpsClusterGroup> = query {
        val today = clock.today()
        val rows = clusterRows(today, status = status).filter { (platform == null || it.platform == platform) && (kind == null || it.kind == kind) }
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

    suspend fun clusterSummary(): List<OpsClusterSummaryRow> = query {
        clusterRows(clock.today()).flatMap { row ->
            displayVersions(row).map { Triple(row.platform, it, row.kind) to row }
        }.groupBy({ it.first }, { it.second }).map { (key, rows) ->
            OpsClusterSummaryRow(key.first, key.second, rows.size, CLUSTER_STATUSES.associateWith { status -> rows.count { it.status == status } }, classCounts(rows), key.third)
        }.sortedWith(compareBy<OpsClusterSummaryRow> { it.platform }
            .thenBy(nullsLast(opsVersionOrder.reversed())) { it.platformAppVersion }.thenBy { it.kind.wire })
    }

    suspend fun clustersPage(platform: String, version: String?, status: String?, page: Int, pageSize: Int = 25, kind: SkeletonKind? = null): OpsClusterPage = query {
        require(pageSize > 0)
        val today = clock.today()
        val rows = clusterRows(today, status = status).filter { row ->
            row.platform == platform && version in displayVersions(row) && (kind == null || row.kind == kind)
        }.sortedWith(reviewOrder(today))
        val pageCount = if (rows.isEmpty()) 1 else (rows.size - 1) / pageSize + 1
        val currentPage = page.coerceIn(1, pageCount)
        val firstDays = if (version == null) emptyMap() else versionFirstDays()
        val clusters = rows.drop((currentPage - 1) * pageSize).take(pageSize).map { row ->
            row.copy(newWithVersion = version != null && newWithVersion(row, version, firstDays), samples = null)
        }
        OpsClusterPage(platform, version, status, rows.size, currentPage, pageSize, pageCount, clusters, classCounts(rows), kind)
    }

    private fun classCounts(rows: List<OpsCluster>): Map<String, Int> =
        (RuleAuthoringVocabulary.SCREEN_CLASSES + "unclassified").associateWith { screenClass ->
            rows.count { (it.screenClass ?: "unclassified") == screenClass }
        }

    private fun displayVersions(row: OpsCluster): List<String?> =
        row.versions.map { it.takeUnless { version -> version == "unknown" } }.ifEmpty { listOf(null) }

    suspend fun pinnedEnvelope(fingerprint: String, envelopeId: Long? = null): PinnedEnvelope? = query {
        pinned(fingerprint, envelopeId)
    }

    private fun Connection.pinned(fingerprint: String, envelopeId: Long?, lock: Boolean = false): PinnedEnvelope? = select(
        """SELECT e.id, e.envelope::text AS bytes, e.received_day, left(e.install_id::text, 8) AS prefix,
            e.envelope->'metadata'->>'platformAppVersion' AS version
            FROM trusted_envelopes e JOIN installs i ON i.install_id = e.install_id
            WHERE e.fingerprint = ? AND EXISTS (SELECT 1 FROM clusters c WHERE c.fingerprint = e.fingerprint AND c.kind = 'screen') AND i.trusted AND i.revoked_at IS NULL
            AND (?::bigint IS NULL OR e.id = ?) ORDER BY e.received_day DESC, e.id DESC LIMIT 1""" +
            if (lock) " FOR SHARE OF e" else "",
        fingerprint, envelopeId, envelopeId,
    ) { PinnedEnvelope(it.getLong("id"), it.getString("bytes"), sha256Hex(it.getString("bytes").toByteArray(Charsets.UTF_8)),
        it.getString("received_day"), it.getString("prefix"), it.getString("version")) }

    suspend fun saveClassification(fp: String, screenClass: String?, notes: String? = null): Boolean = query {
        require(screenClass == null || screenClass in RuleAuthoringVocabulary.SCREEN_CLASSES)
        require(notes == null || notes.length <= 2000)
        update("UPDATE clusters SET screen_class = ?, notes = COALESCE(?, notes) WHERE fingerprint = ?", screenClass, notes, fp) == 1
    }

    /** Pin metadata travels separately from selections in the stored document; never through a DTO. */
    suspend fun saveDraft(fp: String, screenClass: String, selectionsJson: JsonObject, json5: String, day: LocalDate): Boolean = query {
        check(select("SELECT kind FROM clusters WHERE fingerprint = ?", fp) { it.getString(1) } == "screen") {
            "unsupported_skeleton_kind"
        }
        require(screenClass in RuleAuthoringVocabulary.SCREEN_CLASSES)
        val id = (selectionsJson["envelopeId"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@query false
        // Lock the install before the envelope, matching withdrawal's parent-first lock order.
        // Lock provenance through commit: withdrawal/revocation and retention cannot race a successful save.
        val eligible = select("""SELECT i.install_id FROM installs i
            WHERE i.install_id = (SELECT install_id FROM trusted_envelopes WHERE id = ? AND fingerprint = ?)
            AND i.trusted AND i.revoked_at IS NULL FOR SHARE""", id, fp) { true } ?: false
        if (!eligible) return@query false
        val pinned = pinned(fp, id, lock = true) ?: return@query false
        val notes = (selectionsJson["notes"] as? JsonPrimitive)?.content
        require(notes == null || notes.length <= 2000)
        val draft = buildJsonObject {
            put("selections", JsonObject(selectionsJson - setOf("envelopeId", "envelopeSha256", "notes")))
            put("json5", json5); put("envelopeId", id); put("envelopeSha256", pinned.sha256Hex)
        }
        update("""UPDATE clusters SET screen_class = ?, draft = ?::jsonb, draft_day = ?, status = 'drafted',
            notes = COALESCE(?, notes) WHERE fingerprint = ? AND kind = 'screen'""", screenClass, draft.toString(), day, notes, fp) == 1
    }

    suspend fun draftJson5(fp: String): String? = query {
        select("SELECT draft->>'json5' FROM clusters WHERE fingerprint = ? AND kind = 'screen'", fp) { it.getString(1) }
    }

    suspend fun cluster(fingerprint: String, withWireframe: Boolean = false): OpsCluster? = query {
        val row = clusterRows(clock.today(), fingerprint = fingerprint).singleOrNull() ?: return@query null
        val newest = row.versions.maxWithOrNull(opsVersionOrder)
        val wireframe = if (withWireframe && row.kind == SkeletonKind.SCREEN) select(
            """SELECT e.envelope, e.received_day, left(e.install_id::text, 8) AS prefix
                FROM trusted_envelopes e JOIN installs i ON i.install_id = e.install_id
                WHERE e.fingerprint = ? AND EXISTS (SELECT 1 FROM clusters c WHERE c.fingerprint = e.fingerprint AND c.kind = 'screen') AND i.trusted AND i.revoked_at IS NULL
                ORDER BY e.received_day DESC, e.id DESC LIMIT 1""",
            fingerprint,
        ) { WireframeRender.render(it.getString("envelope"), it.getString("received_day"), it.getString("prefix")) } else null
        row.copy(newWithVersion = newest != null && newWithVersion(row, newest, versionFirstDays()), samples = samples(row), wireframe = wireframe)
    }

    private fun Connection.clusterRows(today: LocalDate, status: String? = null, fingerprint: String? = null): List<OpsCluster> =
        select(
            """SELECT c.fingerprint, c.platform, c.kind, c.status, c.first_seen_day, c.last_seen_day, c.resolved_rule_id, c.notes,
                c.screen_class, c.draft IS NOT NULL AS has_draft, c.draft_day, count(DISTINCT s.install_id) FILTER (WHERE NOT i.trusted AND s.day >= ? AND s.day <= ?) AS installs,
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
                    screenClass = rows.getString("screen_class"),
                    hasDraft = rows.getString("kind") == "screen" && rows.getBoolean("has_draft"),
                    draftDay = rows.getString("draft_day").takeIf { rows.getString("kind") == "screen" },
                    kind = requireNotNull(SkeletonKind.fromWire(rows.getString("kind"))),
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

    private fun score(row: OpsCluster, today: LocalDate): Double = reviewScore(row, today)

    private fun Connection.samples(row: OpsCluster): List<OpsSample> = select(
        "SELECT platform_app_version, received_day, skeleton FROM cluster_samples WHERE fingerprint = ? ORDER BY received_day DESC, platform_app_version",
        row.fingerprint,
    ) { rows -> buildList {
        do {
            val sample = rows.getString("skeleton")
            add(OpsSample(rows.getString("platform_app_version"), rows.getString("received_day"),
                skeleton = if (row.kind == SkeletonKind.SCREEN) SkeletonRender.render(sample, row.unblinded) else null,
                notification = if (row.kind == SkeletonKind.NOTIFICATION) SkeletonRender.renderNotification(sample, row.unblinded) else null))
        } while (rows.next())
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

    suspend fun vocabularyQueueDisplay(limit: Int = 50): List<OpsVocabularyDisplay> = query {
        select("SELECT kind, installs, first_day, last_day FROM ($QUEUE_SQL) queue ORDER BY first_day, token_hash LIMIT ?", policy.k, limit) { rows -> buildList {
            do { add(OpsVocabularyDisplay(rows.getString("kind"), rows.getInt("installs"), rows.getString("first_day"), rows.getString("last_day"))) } while (rows.next())
        } } ?: emptyList()
    }

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

/** Private capture provenance, deliberately not serializable. */
data class PinnedEnvelope(
    val id: Long, val bytes: String, val sha256Hex: String, val receivedDay: String,
    val installPrefix: String, val platformAppVersion: String?,
)
