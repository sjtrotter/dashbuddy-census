package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
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
            val inserted = select(
                """INSERT INTO clusters (fingerprint, platform, kind, first_seen_day, last_seen_day) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (fingerprint) DO UPDATE SET
                        last_seen_day = GREATEST(clusters.last_seen_day, EXCLUDED.last_seen_day)
                    WHERE clusters.kind = EXCLUDED.kind
                    RETURNING (xmax = 0) AS inserted""",
                fingerprint, first.item.platform, first.item.kind.wire, day, day,
            ) { it.getBoolean("inserted") } ?: error("Missing cluster result")
            if (inserted) newClusters++
            // The cluster upsert holds its row lock, serializing this sample cap for the fingerprint.
            for ((version, samples) in group.groupBy { it.item.platformAppVersion ?: "unknown" }) {
                samplesStored += update(
                    """INSERT INTO cluster_samples (fingerprint, platform_app_version, received_day, skeleton)
                        SELECT ?, ?, ?, ?::jsonb WHERE (SELECT count(*) FROM cluster_samples WHERE fingerprint = ? AND platform_app_version = ?) < 5
                        ON CONFLICT DO NOTHING""",
                    fingerprint, version, day, samples.first().canonicalJson, fingerprint, version,
                )
            }
            for ((sightingVersion, occurrences) in group.groupBy { it.item.platformAppVersion ?: "unknown" }) {
                update(
                    """INSERT INTO cluster_sightings (fingerprint, install_id, day, platform_app_version, count)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (fingerprint, install_id, day, platform_app_version) DO UPDATE SET
                            count = cluster_sightings.count + EXCLUDED.count""",
                    fingerprint, installId, day, sightingVersion, occurrences.size,
                )
            }
            for (token in group.flatMap { it.tokens }.distinct()) {
                tokensTouched += update(
                    """INSERT INTO token_sightings (token_hash, install_id, first_day, last_day, kind)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT (token_hash, install_id) DO UPDATE SET
                            first_day = LEAST(token_sightings.first_day, EXCLUDED.first_day),
                            last_day = GREATEST(token_sightings.last_day, EXCLUDED.last_day)""",
                    token.hash, installId, day, day, token.kind,
                )
            }
        }
        recordIngestCounters(installId, day, duplicateInBatch, rejected)
        IngestOutcome.Stored(IngestSummary(groups.size, newClusters, samplesStored, tokensTouched), consumed)
    }
}
