package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.QuarantineCrypto
import cloud.trotter.census.server.QuarantineNote
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.db.ContributorGate.activeInstall
import cloud.trotter.census.server.db.ContributorGate.consume
import cloud.trotter.census.server.db.ContributorGate.gateOpen
import cloud.trotter.census.server.db.ContributorGate.liveGrant
import cloud.trotter.census.server.db.ContributorGate.liveKeysByEpoch
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.LifecycleDeadline
import cloud.trotter.census.server.secondsToUtcMidnight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.PublicKey
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** One quarantine item as the operator's views list it (#81): never its content, which only the operator can open. */
data class QuarantineEntry(val id: UUID, val expiresDay: LocalDate, val note: QuarantineNote?, val bytes: Int)

/** The answer to a quarantine submission (#81). */
sealed interface QuarantineResult {
    data class Stored(val expiresDay: LocalDate) : QuarantineResult

    /** The artifact carries a word whose gate is not open for this install: nothing is stored. */
    data object GateClosed : QuarantineResult
}

/**
 * "Ask the developer" (#81, ADR-0011 §15, gig.log#1627): the exact outbound artifact, sealed on receipt to the
 * operator's public key ([QuarantineCrypto]) and kept in `quarantine_items` until the start of the UTC day
 * [cloud.trotter.census.server.ContributorPolicy.quarantineDays] after receipt (at most 72 hours). No cluster, count,
 * draft, vocabulary or agent read touches that table; only the operator's quarantine views list it, and they hold
 * ciphertext. `backup.sh` dumps none of its rows. An item is tagged by the install's epoch tag for the item id, so
 * erasure finds it.
 */
class QuarantineStore(private val db: Database, private val clock: Clock, private val policy: Policy, private val operatorKey: PublicKey) {
    private suspend fun <T> query(isolation: Int? = null, block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        var attempt = 0
        var result: Result<T>
        do {
            result = runCatching {
                transaction(db.exposed, isolation, false) {
                    maxAttempts = 1
                    (connection.connection as Connection).block()
                }
            }
            val retry = result.exceptionOrNull()?.isSerializationFailure() == true && ++attempt < 3
        } while (retry)
        result.getOrThrow()
    }

    /**
     * Stores [artifact] sealed. The quota is spent and the grant checked in one transaction; the words the artifact
     * carries (each a scope and an item already checked with [ContributorGate.checkWord]) must find their gates open
     * in the snapshot that writes the item, the grant share-locked there too, so a word whose gate is closed never
     * reaches the quarantine as text.
     */
    suspend fun submit(
        installId: UUID, keyHash: String, today: LocalDate, artifact: ByteArray, note: QuarantineNote?,
        words: List<Triple<SlotScope, String, String>>,
    ): ContributorOutcome<QuarantineResult> {
        val admitted = query {
            activeInstall(installId, keyHash, lock = true) ?: return@query ContributorOutcome.Stale
            liveGrant(installId, today, lock = true) ?: return@query ContributorOutcome.NoGrant
            if (!consume(installId, today, "contributor_quarantine", 1, policy.contributor.dailyQuarantine)) {
                return@query ContributorOutcome.BudgetExhausted(secondsToUtcMidnight(clock.now()))
            }
            ContributorOutcome.Ok(Unit)
        }
        admitted.refusal()?.let { return it }
        return query(Connection.TRANSACTION_REPEATABLE_READ) {
            activeInstall(installId, keyHash, lock = false) ?: return@query ContributorOutcome.Stale
            liveGrant(installId, today, lock = true) ?: return@query ContributorOutcome.NoGrant
            if (words.isNotEmpty()) {
                val keys = liveKeysByEpoch(db.keying, today)
                for ((scope, slot, value) in words) {
                    if (!gateOpen(policy, keys, installId, today, scope, slot, value)) return@query ContributorOutcome.Ok(QuarantineResult.GateClosed)
                }
            }
            val id = UUID.randomUUID()
            val tag = Tagger(this, db.keying, today).tag(TagPurpose.QUARANTINE, installId, id.toString(), today)
            val expires = today.plusDays(policy.contributor.quarantineDays.toLong())
            update(
                "INSERT INTO quarantine_items (id, epoch, install_tag, expires_day, note, artifact) VALUES (?, ?, ?, ?, ?, ?)",
                id, tag.epoch, tag.tag, expires, note?.wire, QuarantineCrypto.seal(operatorKey, id, artifact),
            )
            ContributorOutcome.Ok(QuarantineResult.Stored(expires))
        }
    }

    /** The unexpired items, soonest to expire first. Reads honour the deadline even when the sweep has not run. */
    suspend fun list(today: LocalDate): List<QuarantineEntry> = query {
        prepareStatement(
            "SELECT id, expires_day, note, octet_length(artifact) FROM quarantine_items WHERE expires_day > ? ORDER BY expires_day, id",
        ).use { statement ->
            statement.setObject(1, today)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(QuarantineEntry(rows.getObject(1, UUID::class.java), rows.getObject(2, LocalDate::class.java),
                        rows.getString(3)?.let(QuarantineNote::fromWire), rows.getInt(4)))
                }
            }
        }
    }

    /** One unexpired item and its sealed artifact, for the operator to open offline. */
    suspend fun sealed(id: UUID, today: LocalDate): Pair<QuarantineEntry, ByteArray>? = query {
        select("SELECT expires_day, note, artifact FROM quarantine_items WHERE id = ? AND expires_day > ?", id, today) { row ->
            val bytes = row.getBytes(3)
            QuarantineEntry(id, row.getObject(1, LocalDate::class.java), row.getString(2)?.let(QuarantineNote::fromWire), bytes.size) to bytes
        }
    }

    suspend fun delete(id: UUID): Boolean = query { update("DELETE FROM quarantine_items WHERE id = ?", id) == 1 }

    /**
     * The operator approves a sanitized copy (#81): [sanitized] (already validated and keyed) becomes a cluster sample,
     * as an upload's would, with no sighting and no count behind it, and the item is deleted in the same transaction.
     * False when the item is gone or expired, or the copy's cluster exists with another kind.
     */
    suspend fun approve(id: UUID, sanitized: ItemVerdict.Accepted, today: LocalDate): Boolean = query {
        if (select("SELECT 1 FROM quarantine_items WHERE id = ? AND expires_day > ? FOR UPDATE", id, today) { true } == null) return@query false
        val item = sanitized.item
        val observed = LifecycleDeadline.observation(LocalDate.parse(item.day), today)
        val fingerprint = item.fingerprint
        select(
            """INSERT INTO clusters (fingerprint, platform, kind, first_seen_day, last_seen_day) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO UPDATE SET last_seen_day = GREATEST(clusters.last_seen_day, EXCLUDED.last_seen_day),
                    first_seen_day = LEAST(clusters.first_seen_day, EXCLUDED.first_seen_day)
                WHERE clusters.kind = EXCLUDED.kind RETURNING 1""",
            fingerprint, item.platform, item.kind.wire, observed, observed,
        ) { true } ?: return@query false
        val deadline = select("SELECT last_seen_day, resolved_day FROM clusters WHERE fingerprint = ?", fingerprint) {
            LifecycleDeadline.sample(it.getObject(1, LocalDate::class.java), it.getObject(2, LocalDate::class.java))
        }!!
        val version = item.platformAppVersion ?: "unknown"
        if (deadline > today) update(
            """INSERT INTO cluster_samples (fingerprint, platform_app_version, received_day, skeleton, hash_domain, filter_rev, purge_after)
                SELECT ?, ?, ?, ?::jsonb, ?, ?, ? WHERE (SELECT count(*) FROM cluster_samples
                WHERE fingerprint = ? AND platform_app_version = ?) < 5 ON CONFLICT DO NOTHING""",
            fingerprint, version, today, sanitized.canonicalJson, item.hashDomain, item.filterRev, deadline, fingerprint, version,
        )
        update("DELETE FROM quarantine_items WHERE id = ?", id) == 1
    }
}
