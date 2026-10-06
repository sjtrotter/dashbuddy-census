package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.ingest.LifecycleDeadline
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ConsumeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class IngestSummary(val clustersTouched: Int, val newClusters: Int, val samplesStored: Int, val tokensTouched: Int)

/** Credential, duplicate, budget, storage and accounting decisions share one transaction. */
sealed interface IngestOutcome {
    data object StaleCredential : IngestOutcome
    data object Duplicate : IngestOutcome
    data class Stored(val summary: IngestSummary, val consumed: ConsumeOutcome.Consumed) : IngestOutcome
    data class BudgetExhausted(val retryAfterSeconds: Long) : IngestOutcome
}

/** Canonical samples and install-keyed sightings, written together in one transaction. */
class SkeletonStore(private val db: Database, private val clock: Clock) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    /**
     * ONE transaction: the ledger consume (bytes / items / batch, [consumeLedger]) and every row the batch produces.
     * Review (session, S4): two transactions let a failure after the consume record the batch as known while
     * storing nothing — the client's retry would then be answered "duplicate" and the data lost.
     */
    suspend fun ingest(
        installId: UUID,
        keyHash: String,
        day: LocalDate,
        accepted: List<ItemVerdict.Accepted>,
        duplicateInBatch: Int,
        rejected: Map<String, Int>,
        batchId: String,
        bodyBytes: Long,
        policy: BudgetPolicy,
        now: Instant = clock.now(),
    ): IngestOutcome = query {
        val current = select(
            "SELECT 1 FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL FOR SHARE",
            installId, keyHash,
        ) { true } ?: false
        if (!current) return@query IngestOutcome.StaleCredential
        update("INSERT INTO ingest_ledger (install_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING", installId, day)
        val known = select(
            "SELECT batch_ids FROM ingest_ledger WHERE install_id = ? AND day = ? FOR UPDATE",
            installId, day,
        ) { row ->
            val array = row.getArray("batch_ids")
            try { batchId in (array.array as Array<*>) } finally { array.free() }
        } ?: error("Missing ledger row")
        if (known) {
            update(
                "UPDATE ingest_ledger SET duplicate = duplicate + ? WHERE install_id = ? AND day = ?",
                accepted.size + rejected.values.sum(), installId, day,
            )
            return@query IngestOutcome.Duplicate
        }
        val consumed = when (val outcome = consumeLedger(installId, day, bodyBytes, accepted.size, batchId, policy, now)) {
            is ConsumeOutcome.BudgetExhausted -> return@query IngestOutcome.BudgetExhausted(outcome.retryAfterSeconds)
            is ConsumeOutcome.Consumed -> outcome
        }
        val groups = accepted.groupBy { it.item.fingerprint }
        var newClusters = 0
        var samplesStored = 0
        var tokensTouched = 0
        for ((fingerprint, group) in groups.toSortedMap()) {
            val first = group.first()
            check(group.all { it.item.kind == first.item.kind }) { "Conflicting skeleton kind" }
            val lastObservation = group.maxOf { LifecycleDeadline.observation(LocalDate.parse(it.item.day), day) }
            val firstObservation = group.minOf { LifecycleDeadline.observation(LocalDate.parse(it.item.day), day) }
            val inserted = select(
                """INSERT INTO clusters (fingerprint, platform, kind, first_seen_day, last_seen_day) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (fingerprint) DO UPDATE SET
                        last_seen_day = GREATEST(clusters.last_seen_day, EXCLUDED.last_seen_day),
                        first_seen_day = LEAST(clusters.first_seen_day, EXCLUDED.first_seen_day)
                    WHERE clusters.kind = EXCLUDED.kind
                    RETURNING (xmax = 0) AS inserted""",
                fingerprint, first.item.platform, first.item.kind.wire, firstObservation, lastObservation,
            ) { it.getBoolean("inserted") } ?: error("Missing cluster result")
            if (inserted) newClusters++
            // Ingest and lifecycle always hold this cluster lock before touching samples.
            val deadline = select("SELECT last_seen_day, resolved_day FROM clusters WHERE fingerprint = ?", fingerprint) {
                LifecycleDeadline.sample(it.getObject(1, LocalDate::class.java), it.getObject(2, LocalDate::class.java))
            }!!
            // Fresh observations may extend unresolved samples, but never revive an expired copy.
            update("""UPDATE cluster_samples SET purge_after = ? WHERE fingerprint = ? AND purge_after > ?
                AND NOT EXISTS (SELECT 1 FROM clusters WHERE fingerprint = ? AND resolved_day IS NOT NULL)""",
                deadline, fingerprint, day, fingerprint)
            if (deadline > day) for ((version, samples) in group.groupBy { it.item.platformAppVersion ?: "unknown" }) {
                val selected = samples.first()
                samplesStored += update(
                    """INSERT INTO cluster_samples (fingerprint, platform_app_version, received_day, skeleton, hash_domain, filter_rev, purge_after)
                        SELECT ?, ?, ?, ?::jsonb, ?, ?, ? WHERE (SELECT count(*) FROM cluster_samples
                        WHERE fingerprint = ? AND platform_app_version = ?) < 5 ON CONFLICT DO NOTHING""",
                    fingerprint, version, day, selected.canonicalJson, selected.item.hashDomain, selected.item.filterRev,
                    deadline, fingerprint, version,
                )
            }
            for (acceptedItem in group) {
                val item = acceptedItem.item
                val observed = LifecycleDeadline.observation(LocalDate.parse(item.day), day)
                update(
                    """INSERT INTO cluster_sightings_v5 (fingerprint, install_id, day, platform_app_version, count, hash_domain, filter_rev)
                        VALUES (?, ?, ?, ?, 1, ?, ?)
                        ON CONFLICT (fingerprint, install_id, day, platform_app_version, hash_domain, filter_rev) DO UPDATE SET
                            count = cluster_sightings_v5.count + 1""",
                    fingerprint, installId, observed, item.platformAppVersion ?: "unknown", item.hashDomain, item.filterRev,
                )
                for (token in acceptedItem.tokens.distinct()) {
                    tokensTouched += update(
                        """INSERT INTO token_sightings_v5 (token_hash, install_id, first_day, last_day, kind, hash_domain, filter_rev)
                            VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (hash_domain, token_hash, filter_rev, install_id) DO UPDATE SET
                                first_day = LEAST(token_sightings_v5.first_day, EXCLUDED.first_day),
                                last_day = GREATEST(token_sightings_v5.last_day, EXCLUDED.last_day)""",
                        token.key.hash, installId, observed, observed, token.kind, token.key.hashDomain, token.key.filterRev,
                    )
                }
            }
        }
        recordIngestCounters(installId, day, duplicateInBatch, rejected)
        IngestOutcome.Stored(IngestSummary(groups.size, newClusters, samplesStored, tokensTouched), consumed)
    }
}
