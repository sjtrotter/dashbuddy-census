package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.SlotConstancyPolicy
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.ingest.LifecycleDeadline
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ConsumeOutcome
import cloud.trotter.census.server.ingest.SlotWalk
import cloud.trotter.census.server.ingest.atRest
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

/** Canonical samples and epoch-tagged sightings (#86), written together in one transaction. */
class SkeletonStore(
    private val db: Database,
    private val clock: Clock,
    // #80: the per-skeleton slot cap, Policy.slotConstancy.maxSlotsPerSkeleton.
    private val slotLimit: Int = SlotConstancyPolicy().maxSlotsPerSkeleton,
) {
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
    ): IngestOutcome {
        // #85: keyed before anything is written; nothing below sees a public word hash, notification fingerprint or
        // batch id (the app derives the id from the batch's public fingerprints).
        val stored = accepted.map { db.keying.atRest(it) }
        return store(installId, keyHash, day, stored, duplicateInBatch, rejected, db.keying.batch(batchId), bodyBytes, policy, now)
    }

    private suspend fun store(
        installId: UUID,
        keyHash: String,
        day: LocalDate,
        accepted: List<ItemVerdict.Accepted>,
        duplicateInBatch: Int,
        rejected: Map<String, Int>,
        batchId: String,
        bodyBytes: Long,
        policy: BudgetPolicy,
        now: Instant,
    ): IngestOutcome = query {
        // #86: the install's trust and quarantine are read under the same share lock that serializes this batch with
        // withdrawal, revocation and a trust change, and stamped onto its sightings, which no longer name it.
        val install = select(
            "SELECT trusted, created_day FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL FOR SHARE",
            installId, keyHash,
        ) { it.getBoolean(1) to it.getObject(2, LocalDate::class.java) } ?: return@query IngestOutcome.StaleCredential
        val (trusted, createdDay) = install
        val countsFrom = VocabularyEligibility.countsFrom(createdDay, day)
        val tagger = Tagger(this, db.keying, day)
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
            val slotSightings = ArrayList<SlotSighting>(group.size)
            for (acceptedItem in group) {
                val item = acceptedItem.item
                val observed = LifecycleDeadline.observation(LocalDate.parse(item.day), day)
                slotSightings += SlotSighting(item, SlotVariant(item.platformAppVersion ?: "unknown", item.hashDomain, item.filterRev,
                    SlotWalk.layout(item)), observed)
                // #86: keyed by an epoch tag of (install, cluster) under the observation day's epoch, never the install.
                val sighting = tagger.tag(TagPurpose.CLUSTER, installId, fingerprint, observed)
                update(
                    // #79: presence only. A second sighting on the same day changes nothing.
                    """INSERT INTO cluster_sightings_v5 (fingerprint, epoch, install_tag, trusted, day, platform_app_version, hash_domain, filter_rev)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (fingerprint, install_tag, day, platform_app_version, hash_domain, filter_rev) DO NOTHING""",
                    fingerprint, sighting.epoch, sighting.tag, trusted, observed, item.platformAppVersion ?: "unknown",
                    item.hashDomain, item.filterRev,
                )
                for (token in acceptedItem.tokens.distinct()) {
                    val tag = tagger.tag(TagPurpose.TOKEN, installId,
                        TokenObject.of(token.key.hashDomain, token.key.filterRev, token.key.hash), observed)
                    tokensTouched += update(
                        """INSERT INTO token_sightings_v5 (token_hash, epoch, install_tag, trusted, counts_from, first_day, last_day, kind, hash_domain, filter_rev)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (hash_domain, token_hash, filter_rev, epoch, install_tag) DO UPDATE SET
                                first_day = LEAST(token_sightings_v5.first_day, EXCLUDED.first_day),
                                last_day = GREATEST(token_sightings_v5.last_day, EXCLUDED.last_day),
                                counts_from = CASE WHEN token_sightings_v5.counts_from IS NULL OR EXCLUDED.counts_from IS NULL
                                    THEN NULL ELSE LEAST(token_sightings_v5.counts_from, EXCLUDED.counts_from) END""",
                        token.key.hash, tag.epoch, tag.tag, trusted, countsFrom, observed, observed, token.kind,
                        token.key.hashDomain, token.key.filterRev,
                    )
                }
            }
            // #80: where each value sat, for per-slot constancy; keyed by epoch tags like the sightings above.
            recordSlotSightings(tagger, installId, trusted, countsFrom, fingerprint, slotSightings, slotLimit)
        }
        recordIngestCounters(installId, day, duplicateInBatch, rejected)
        IngestOutcome.Stored(IngestSummary(groups.size, newClusters, samplesStored, tokensTouched), consumed)
    }
}
