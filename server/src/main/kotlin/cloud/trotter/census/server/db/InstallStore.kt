package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.EpochKey
import cloud.trotter.census.server.Retention
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.today
import cloud.trotter.census.server.auth.toLowerHex
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ConsumeOutcome
import cloud.trotter.census.server.secondsToUtcMidnight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class EnrolOutcome { Created, SameKey, KeyMismatch, Revoked }

sealed interface MutationOutcome {
    data class Applied(val deletedRows: Map<String, Int> = emptyMap(), val withdrawnAt: Instant? = null) : MutationOutcome
    data object StaleCredential : MutationOutcome
}

data class WithdrawalReplayReport(val matched: Int, val deletedRows: Map<String, Int>, val keptReenrolled: Int)

data class Install(
    val id: UUID,
    val createdDay: LocalDate,
    val lastSeenDay: LocalDate,
    val trusted: Boolean,
    val keyHash: String,
    val revokedAt: Instant?,
) {
    override fun toString(): String =
        "Install(id=[redacted], keyHash=[redacted], createdDay=$createdDay, " +
            "lastSeenDay=$lastSeenDay, trusted=$trusted, revokedAt=$revokedAt)"
}

data class LedgerRow(
    val bytes: Long = 0,
    val accepted: Int = 0,
    val duplicate: Int = 0,
    val rejectedByReason: Map<String, Int> = emptyMap(),
    val batchIds: List<String> = emptyList(),
)

/** What `/v1/me` may disclose: this credential generation's own dates, trust and today's ledger counters. */
data class MeView(val createdDay: LocalDate, val lastSeenDay: LocalDate, val trusted: Boolean, val ledger: LedgerRow?)

/** All database work uses the explicitly supplied pool, on IO, in an Exposed transaction. */
class InstallStore(private val db: Database, private val clock: Clock) {
    private val random = SecureRandom()

    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            // Do not retry or log SQL parameters on failures (which can contain full install IDs).
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    suspend fun enrol(installId: UUID, keyHash: String, appVersion: String): EnrolOutcome = query {
        val now = clock.now().truncatedTo(ChronoUnit.MICROS)
        val day = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val inserted = update(
            """INSERT INTO installs (install_id, key_hash, created_day, last_seen_day, last_app_version)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (install_id) DO NOTHING""",
            installId, keyHash, day, day, appVersion,
        )
        if (inserted == 1) {
            // #79: no enrolment instant is stored. The new generation records the tombstone it postdates instead,
            // read AFTER the insert succeeded (#1192, Astra review): an insert that raced a withdrawal of the
            // previous generation lands only after that withdrawal committed, so this statement sees its tombstone.
            update(
                """UPDATE installs SET erased_through =
                    (SELECT withdrawn_at FROM withdrawals WHERE install_id_hash = ?) WHERE install_id = ?""",
                installIdHash(installId), installId,
            )
            return@query EnrolOutcome.Created
        }
        select("SELECT key_hash, revoked_at FROM installs WHERE install_id = ? FOR UPDATE", installId) { row ->
            when {
                row.getObject("revoked_at") != null -> EnrolOutcome.Revoked
                !sameHash(row.getString("key_hash"), keyHash) -> EnrolOutcome.KeyMismatch
                else -> {
                    // Review (Astra, round 3): a paused same-key enrol must never move activity backwards.
                    update("UPDATE installs SET last_seen_day = GREATEST(last_seen_day, ?), last_app_version = ? WHERE install_id = ?", day, appVersion, installId)
                    EnrolOutcome.SameKey
                }
            }
        } ?: error("Concurrent withdrawal during enrolment")
    }

    /** Read-only, including for revoked rows; the caller verifies the credential before using it. */
    suspend fun lookup(installId: UUID): Install? = query {
        select("SELECT * FROM installs WHERE install_id = ?", installId) { row ->
            Install(
                installId, row.getObject("created_day", LocalDate::class.java),
                row.getObject("last_seen_day", LocalDate::class.java), row.getBoolean("trusted"),
                row.getString("key_hash"), row.getObject("revoked_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            )
        }
    }

    /** [lookup] bound to the CURRENT credential and generation (review, Astra round 3): a withdrawn-and-re-enrolled id with a new key is NOT visible to the old key. */
    suspend fun lookupActive(installId: UUID, keyHash: String): Install? = query {
        select("SELECT * FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL", installId, keyHash) { row ->
            Install(
                installId, row.getObject("created_day", LocalDate::class.java),
                row.getObject("last_seen_day", LocalDate::class.java), row.getBoolean("trusted"),
                row.getString("key_hash"), row.getObject("revoked_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            )
        }
    }

    suspend fun touchLastSeen(installId: UUID, keyHash: String, day: LocalDate): Boolean = query {
        update(
            "UPDATE installs SET last_seen_day = ? WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL AND last_seen_day < ?",
            day, installId, keyHash, day,
        ) == 1
    }

    suspend fun rotate(installId: UUID, expectedCurrentKeyHash: String, newKeyHash: String): MutationOutcome = query {
        val changed = update(
            "UPDATE installs SET key_hash = ? WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL",
            newKeyHash, installId, expectedCurrentKeyHash,
        )
        if (changed == 1) MutationOutcome.Applied() else MutationOutcome.StaleCredential
    }

    /**
     * #86: revocation also deletes the install's stored sightings. k counting used to drop a revoked install through a
     * join on the installs table; the sightings no longer name it, so its evidence goes instead.
     */
    suspend fun revoke(installId: UUID, at: Instant): Boolean = query {
        val changed = update("UPDATE installs SET revoked_at = ? WHERE install_id = ?", at.atOffset(ZoneOffset.UTC), installId) == 1
        if (changed) deleteSightings(erasureKeys(), installId)
        changed
    }

    /** #86: erasure uses every stored key, expired ones included until the sweep destroys them. */
    private fun Connection.erasureKeys() = with(EpochKeyStore) { storedKeys(db.keying) }

    suspend fun withdraw(installId: UUID, expectedCurrentKeyHash: String): MutationOutcome = query {
        // Lock the parent first: concurrent child inserts must finish before this lock or wait for deletion.
        val current = select("SELECT key_hash, revoked_at FROM installs WHERE install_id = ? FOR UPDATE", installId) { row ->
            val active = row.getObject("revoked_at") == null
            active && sameHash(row.getString("key_hash"), expectedCurrentKeyHash)
        } ?: false
        if (!current) return@query MutationOutcome.StaleCredential
        val withdrawnAt = advanceTombstone(installId, clock.now().truncatedTo(ChronoUnit.MICROS))
        MutationOutcome.Applied(deleteInstallRows(installId), withdrawnAt.toInstant())
    }

    /**
     * #79: "delete my data" without withdrawing. One transaction, parent locked first like [withdraw]: every row keyed
     * to the install goes except the install row itself and today's ledger row, which keeps the daily quota and the
     * batch ids (so a deletion cannot reset the quota, and a batch sent before it cannot be re-admitted as new). The
     * tombstone is the withdrawal tombstone, so the off-host journal and restore replay carry it unchanged; the
     * install copies its instant into `erased_through`, which tells replay this generation already applied it.
     */
    suspend fun deleteData(installId: UUID, expectedCurrentKeyHash: String, today: LocalDate): MutationOutcome = query {
        val current = select("SELECT key_hash, revoked_at FROM installs WHERE install_id = ? FOR UPDATE", installId) { row ->
            val active = row.getObject("revoked_at") == null
            active && sameHash(row.getString("key_hash"), expectedCurrentKeyHash)
        } ?: false
        if (!current) return@query MutationOutcome.StaleCredential
        val tombstone = advanceTombstone(installId, clock.now().truncatedTo(ChronoUnit.MICROS))
        update("UPDATE installs SET erased_through = ? WHERE install_id = ?", tombstone, installId)
        MutationOutcome.Applied(deleteInstallRows(installId, keepInstallFrom = today), tombstone.toInstant())
    }

    /**
     * Sol S3: every erasure (withdrawal or data deletion) moves the install's tombstone strictly forward, to the later
     * of the request instant and one microsecond past the stored one. A clock rollback or a journal row merged with
     * host skew can therefore never reuse an older tombstone, which a backup's `erased_through` copy would cover.
     */
    private fun Connection.advanceTombstone(installId: UUID, requestedAt: Instant): java.time.OffsetDateTime = select(
        """INSERT INTO withdrawals (install_id_hash, withdrawn_at) VALUES (?, ?)
            ON CONFLICT (install_id_hash) DO UPDATE SET
                withdrawn_at = GREATEST(withdrawals.withdrawn_at + interval '1 microsecond', EXCLUDED.withdrawn_at)
            RETURNING withdrawn_at""",
        installIdHash(installId), requestedAt.atOffset(ZoneOffset.UTC),
    ) { it.getObject(1, java.time.OffsetDateTime::class.java) } ?: error("Missing tombstone")

    /**
     * The one per-install erasure. [keepInstallFrom] null = withdrawal: everything, the install row last. Non-null =
     * data deletion: the install row and the ledger rows from that day on stay ([deleteData]).
     *
     * #86: the content tables are found through the install's epoch tags under every stored key, an expired one the
     * sweep has not yet destroyed included. Rows under a destroyed key are not found and need not be: nobody can tie
     * them to this install any more. [tagged] false skips them for
     * an install inactive for a year ([purgeInactive]), which cannot have written a row under a live key.
     */
    private fun Connection.deleteInstallRows(installId: UUID, keepInstallFrom: LocalDate? = null, tagged: Boolean = true): Map<String, Int> {
        val keys = if (tagged) erasureKeys() else emptyList()
        return (if (keepInstallFrom == null) WITHDRAWAL_TABLES else DATA_TABLES).associateWith { table ->
            when (table) {
                "ingest_ledger" -> if (keepInstallFrom == null) {
                    update("DELETE FROM ingest_ledger WHERE install_id = ?", installId)
                } else update("DELETE FROM ingest_ledger WHERE install_id = ? AND day < ?", installId, keepInstallFrom)
                // Delete/lock envelopes before clusters, the same lock order as retention and draft saves.
                "trusted_envelopes" -> deleteEnvelopesAndClearDrafts("DELETE FROM trusted_envelopes WHERE install_id = ? RETURNING id", installId)
                "health_daily" -> deleteHealthAndRefold(keys, installId)
                in TAGGED_TABLES -> forInstallTags(keys, TaggedTable.entries.single { it.table == table }, installId,
                    "DELETE FROM $table WHERE epoch = ? AND install_tag = ANY(?::bpchar[])")
                else -> update("DELETE FROM $table WHERE install_id = ?", installId)
            }
        }
    }

    /**
     * #79: the install's per-install health rows go, and the fleet totals they still feed are recomputed without
     * them. Fleet rows are locked before the health rows, the order ingest and the retention fold use. Only keys
     * this DELETE actually removed are recomputed (Sol S1): if the fold committed between the key read and the
     * locks, those rows are already folded and gone, and recomputing their key from an empty `health_daily` would
     * zero every install's folded total.
     */
    private fun Connection.deleteHealthAndRefold(keys: List<EpochKey>, installId: UUID): Int {
        val tags = keys.map { EpochTag(it.epoch, it.tag(TagPurpose.HEALTH, installId, "")) }
        val candidates = tags.flatMap { (epoch, tag) ->
            select(
                "SELECT DISTINCT day, platform, platform_app_version FROM health_daily WHERE epoch = ? AND install_tag = ?", epoch, tag,
            ) { rows -> buildList { do { add(rows.fleetKey()) } while (rows.next()) } } ?: emptyList()
        }
        lockFleet(candidates)
        val removed = tags.flatMap { (epoch, tag) ->
            prepareStatement(
                "DELETE FROM health_daily WHERE epoch = ? AND install_tag = ? RETURNING day, platform, platform_app_version",
            ).use { statement ->
                statement.setInt(1, epoch)
                statement.setString(2, tag)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.fleetKey()) } }
            }
        }
        // The install row is locked, so no new health row of this install can appear: removed keys were all locked.
        recomputeFleet(removed.distinct())
        return removed.size
    }

    private fun ResultSet.fleetKey(): FleetKey = FleetKey(getObject(1, LocalDate::class.java), getString(2), getString(3))

    /**
     * One transaction, parent locks first, with the exact live-withdrawal deletion and lock order. A generation is
     * erased unless its `erased_through` marker already covers the tombstone (#79): it enrolled after that
     * withdrawal, or it applied that data deletion while still enrolled.
     */
    suspend fun reapplyWithdrawals(): WithdrawalReplayReport = query {
        val candidates = prepareStatement(
            """SELECT i.install_id, (i.erased_through IS NULL OR i.erased_through < w.withdrawn_at) AS erase
                FROM installs i JOIN withdrawals w
                    ON w.install_id_hash = encode(sha256(i.install_id::text::bytea), 'hex')
                ORDER BY i.install_id FOR UPDATE OF i""",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getObject("install_id", UUID::class.java) to rows.getBoolean("erase"))
                }
            }
        }
        val deleted = WITHDRAWAL_TABLES.associateWith { 0 }.toMutableMap()
        for ((id, erase) in candidates) if (erase) {
            for ((table, count) in deleteInstallRows(id)) deleted[table] = deleted.getValue(table) + count
        }
        WithdrawalReplayReport(candidates.count { it.second }, deleted, candidates.count { !it.second })
    }

    suspend fun mergeWithdrawalJournal(rows: List<Pair<String, Instant>>): Int {
        // Validate the entire batch before entering the transaction or writing anything.
        require(rows.all { (tombstone, _) -> TOMBSTONE_HASH.matches(tombstone) }) {
            "Invalid withdrawal journal hash"
        }
        // Fable review F1: a future instant (a typo'd year, a skewed host) would be sticky under GREATEST, never
        // purged, and would erase every later re-enrolment of that id at each boot — reject the batch.
        val horizon = clock.now().plusSeconds(JOURNAL_FUTURE_SKEW_SECONDS)
        require(rows.all { (_, at) -> !at.isAfter(horizon) }) { "Invalid withdrawal journal instant (in the future)" }
        return query {
            rows.sumOf { (hash, at) ->
                update(
                    """INSERT INTO withdrawals (install_id_hash, withdrawn_at) VALUES (?, ?)
                        ON CONFLICT (install_id_hash) DO UPDATE SET
                            withdrawn_at = GREATEST(withdrawals.withdrawn_at, EXCLUDED.withdrawn_at)""",
                    hash, at.atOffset(ZoneOffset.UTC),
                )
            }
        }
    }

    /**
     * #79: the ordering marker on installs lives no longer than the tombstone it copies, and the two go together
     * (Sol S4). Each batch picks one bounded set of expired tombstones, locks their install rows first (the lock order
     * of withdrawal and deletion), then clears those markers and deletes those tombstones in the same transaction, so
     * no restart can find a cleared marker beside a surviving tombstone and erase the install.
     */
    suspend fun purgeWithdrawals(olderThan: Instant): Int = purge("withdrawals") {
        val cutoff = olderThan.atOffset(ZoneOffset.UTC)
        val hashes = prepareStatement(
            "SELECT install_id_hash FROM withdrawals WHERE withdrawn_at <= ? ORDER BY install_id_hash LIMIT 1000",
        ).use { statement ->
            statement.setObject(1, cutoff)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        if (hashes.isEmpty()) return@purge 0
        val batch = createArrayOf("text", hashes.toTypedArray())
        try {
            select(
                """SELECT install_id FROM installs WHERE encode(sha256(install_id::text::bytea), 'hex') = ANY(?)
                    ORDER BY install_id FOR UPDATE""",
                batch,
            ) { rows -> buildList { do { add(rows.getObject(1, UUID::class.java)) } while (rows.next()) } }
            // Rechecked under the locks: an erasure that committed first moved both past the cutoff.
            update(
                """UPDATE installs SET erased_through = NULL
                    WHERE encode(sha256(install_id::text::bytea), 'hex') = ANY(?) AND erased_through <= ?""",
                batch, cutoff,
            )
            update("DELETE FROM withdrawals WHERE install_id_hash = ANY(?) AND withdrawn_at <= ?", batch, cutoff)
        } finally {
            batch.free()
        }
    }

    suspend fun issueNonce(installId: UUID?): String = query {
        // A collision does not abort the transaction; simply generate another random value.
        var nonce: String
        do {
            nonce = ByteArray(16).also(random::nextBytes).toLowerHex()
        } while (update(
                "INSERT INTO nonces (nonce, install_id, issued_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                nonce, installId, clock.now().atOffset(ZoneOffset.UTC),
            ) == 0
        )
        nonce
    }

    suspend fun consumeNonce(nonce: String, installId: UUID?): Boolean = query {
        if (nonce.length != 32 || nonce.any { it !in "0123456789abcdef" }) return@query false
        val now = clock.now()
        // An unbound challenge is usable by any install; a bound one cannot be used anonymously.
        update(
            """UPDATE nonces SET used = true WHERE nonce = ? AND used = false
                AND issued_at >= ? AND issued_at <= ? AND (install_id = ? OR install_id IS NULL)""",
            nonce, now.minusSeconds(300).atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), installId,
        ) == 1
    }

    suspend fun remainingDue(today: LocalDate, now: Instant, retention: Retention = Retention()): Map<String, Long> = query {
        mapOf(
            "nonces" to (select("SELECT count(*) FROM nonces WHERE issued_at < ?", now.minusSeconds(3600).atOffset(ZoneOffset.UTC)) { it.getLong(1) } ?: 0L),
            "ledger" to (select("SELECT count(*) FROM ingest_ledger WHERE day + 7 <= ?", today) { it.getLong(1) } ?: 0L),
            "envelopes" to (select("SELECT count(*) FROM trusted_envelopes WHERE purge_after <= ?", today) { it.getLong(1) } ?: 0L),
            "health" to (select("SELECT count(*) FROM health_daily WHERE day + ? <= ?", retention.healthDailyDays, today) { it.getLong(1) } ?: 0L),
            "withdrawals" to (select("SELECT count(*) FROM withdrawals WHERE withdrawn_at <= ?", now.minusSeconds(retention.withdrawalsDays * 86400L).atOffset(ZoneOffset.UTC)) { it.getLong(1) } ?: 0L),
        )
    }

    suspend fun purgeInactive(today: LocalDate): Int = query {
        val ids = select("SELECT install_id FROM installs WHERE last_seen_day + 365 <= ? ORDER BY install_id LIMIT 1000 FOR UPDATE", today) { rows ->
            buildList { do { add(rows.getObject(1, UUID::class.java)) } while (rows.next()) }
        } ?: emptyList()
        for (id in ids) deleteInstallRows(id, tagged = false)
        ids.size
    }

    suspend fun purgeNonces(olderThan: Instant): Int = purge("nonces") {
        update("DELETE FROM nonces WHERE ctid IN (SELECT ctid FROM nonces WHERE issued_at < ? LIMIT 1000)", olderThan.atOffset(ZoneOffset.UTC))
    }

    suspend fun purgeLedger(olderThan: LocalDate): Int = purge("ledger") {
        update("DELETE FROM ingest_ledger WHERE ctid IN (SELECT ctid FROM ingest_ledger WHERE day <= ? LIMIT 1000)", olderThan)
    }

    suspend fun purgeTrustedEnvelopes(today: LocalDate): Int = purge("envelopes") {
        deleteEnvelopesAndClearDrafts("""DELETE FROM trusted_envelopes WHERE ctid IN
            (SELECT ctid FROM trusted_envelopes WHERE purge_after <= ? LIMIT 1000) RETURNING id""", today)
    }

    internal fun Connection.deleteEnvelopesAndClearDrafts(sql: String, argument: Any): Int {
        val ids = prepareStatement(sql).use { statement ->
            statement.setObject(1, argument)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString("id")) }
            }
        }
        // A separate statement gets a fresh snapshot after DELETE waited for a draft save's envelope share lock.
        // Keep both statements in this transaction, with envelopes locked before clusters.
        val deletedIds = createArrayOf("text", ids.toTypedArray())
        try {
            update("UPDATE clusters SET draft = NULL, draft_day = NULL WHERE draft->>'envelopeId' = ANY(?)", deletedIds)
        } finally {
            deletedIds.free()
        }
        return ids.size
    }

    /**
     * #79: fold, then forget. Each batch locks and recomputes the fleet rows of up to 100 (day, platform, version)
     * keys from the per-install rows still present, then deletes those rows, in one transaction. After this the
     * day exists only as fleet totals; a later deletion request cannot (and need not) find the install in them.
     * Health reports older than 7 days are refused at ingest, so a folded day is never recomputed from a remnant.
     */
    suspend fun purgeHealthDaily(olderThan: LocalDate): Int = purgeBatches("health") {
        val keys = select(
            """SELECT DISTINCT day, platform, platform_app_version FROM health_daily WHERE day <= ?
                ORDER BY day, platform, platform_app_version LIMIT $HEALTH_FOLD_KEYS""",
            olderThan,
        ) { rows -> buildList { do { add(FleetKey(rows.getObject(1, LocalDate::class.java), rows.getString(2), rows.getString(3))) } while (rows.next()) } }
            ?: emptyList()
        refreshFleet(keys)
        val deleted = keys.sumOf { key ->
            update("DELETE FROM health_daily WHERE day = ? AND platform = ? AND platform_app_version = ?", key.day, key.platform, key.version)
        }
        deleted to (keys.size == HEALTH_FOLD_KEYS)
    }

    private suspend fun purge(name: String, batch: Connection.() -> Int): Int =
        purgeBatches(name) { batch().let { count -> count to (count == 1000) } }

    /** Each batch reports the rows it deleted and whether it was full (so another batch may be due). */
    private suspend fun purgeBatches(name: String, batch: Connection.() -> Pair<Int, Boolean>): Int {
        var deleted = 0
        var batches = 0
        var full: Boolean
        do {
            currentCoroutineContext().ensureActive()
            val (count, more) = try { query(batch) } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                throw SweepFailure(deleted.toLong(), batches, failure)
            }
            full = more
            deleted += count
            batches++
        } while (full && batches < 50)
        purgeLog.info("purge sweep={} deleted={} batches={} capped={}", name, deleted, batches, full && batches == 50)
        return deleted
    }

    private val purgeLog = LoggerFactory.getLogger("Purge")

    /** The insert and conflict-update paths both enforce quotas in the same PostgreSQL statement. */
    suspend fun tryConsume(
        installId: UUID,
        day: LocalDate,
        bytes: Long,
        items: Int,
        batchId: String?,
        policy: BudgetPolicy,
    ): ConsumeOutcome = query { consumeLedger(installId, day, bytes, items, batchId?.let { db.keying.batch(it) }, policy, clock.now()) }

    /**
     * The credential-bound form of [recordIngest] (Astra, S4 round 2): a request authenticated under a key that has since
     * been withdrawn (and its id re-enrolled) must not write ANY counter into the replacement generation's ledger —
     * not even a rejection. False = not this generation; the caller answers 401 and records nothing.
     */
    suspend fun recordIngestIfCurrent(
        installId: UUID,
        keyHash: String,
        day: LocalDate,
        duplicate: Int,
        rejectedByReason: Map<String, Int>,
    ): Boolean = query {
        val current = select(
            "SELECT 1 FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL FOR SHARE",
            installId, keyHash,
        ) { true } ?: false
        if (current) recordIngestCounters(installId, day, duplicate, rejectedByReason)
        current
    }

    /** Rejections and duplicates do not consume quota; accepted work must use tryConsume. */
    suspend fun recordIngest(
        installId: UUID,
        day: LocalDate,
        duplicate: Int,
        rejectedByReason: Map<String, Int>,
    ): Unit = query {
        recordIngestCounters(installId, day, duplicate, rejectedByReason)
    }

    /**
     * The `/me` read as ONE credential-bound statement (Astra, round 4): the install row and its ledger row for
     * [day] come from a single snapshot filtered on `key_hash` + `revoked_at IS NULL`, so an old credential whose
     * id was withdrawn and re-enrolled can never see the replacement generation's budget. Null = not this generation.
     */
    suspend fun meView(installId: UUID, keyHash: String, day: LocalDate): MeView? = query {
        select(
            """SELECT i.created_day, i.last_seen_day, i.trusted, l.bytes, l.accepted
                FROM installs i LEFT JOIN ingest_ledger l ON l.install_id = i.install_id AND l.day = ?
                WHERE i.install_id = ? AND i.key_hash = ? AND i.revoked_at IS NULL""",
            day, installId, keyHash,
        ) { row ->
            val bytes = row.getLong("bytes"); val hasLedger = !row.wasNull()
            MeView(
                row.getObject("created_day", LocalDate::class.java), row.getObject("last_seen_day", LocalDate::class.java),
                row.getBoolean("trusted"), if (hasLedger) LedgerRow(bytes = bytes, accepted = row.getInt("accepted")) else null,
            )
        }
    }

    suspend fun ledgerFor(installId: UUID, day: LocalDate): LedgerRow? = query {
        select("SELECT * FROM ingest_ledger WHERE install_id = ? AND day = ?", installId, day) { row ->
            val array = row.getArray("batch_ids")
            val ids = try { (array.array as Array<*>).map { it as String } } finally { array.free() }
            LedgerRow(
                row.getLong("bytes"), row.getInt("accepted"), row.getInt("duplicate"),
                Json.decodeFromString(row.getString("rejected")), ids,
            )
        }
    }

    suspend fun isBatchKnown(installId: UUID, day: LocalDate, batchId: String): Boolean = query {
        select(
            "SELECT ? = ANY(batch_ids) AS known FROM ingest_ledger WHERE install_id = ? AND day = ?",
            db.keying.batch(batchId), installId, day,
        ) { it.getBoolean("known") } ?: false
    }

    companion object {
        /** A tombstone key: exactly 64 lower-hex characters (the ConstantTimeGuard scans this file — a tombstone is not a credential, but the shape check is a pattern, not a comparison). */
        val TOMBSTONE_HASH: Regex = Regex("^[0-9a-f]{64}$")

        /** Fleet keys folded per health retention batch (#79). */
        const val HEALTH_FOLD_KEYS: Int = 100

        /** How far ahead of this server's clock a journal instant may sit (host clock skew), #1192. */
        const val JOURNAL_FUTURE_SKEW_SECONDS: Long = 300

        fun installIdHash(installId: UUID): String =
            MessageDigest.getInstance("SHA-256").digest(installId.toString().toByteArray(Charsets.UTF_8)).toLowerHex()

        /**
         * Every table a withdrawal erases, children first (#79): the [TAGGED_TABLES] through the install's epoch tags
         * (#86), the rest by `install_id`. `PublicInstallsTest` fails when a table that can hold the install is missing.
         */
        val WITHDRAWAL_TABLES: List<String> = listOf(
            "trusted_envelopes", "health_daily", "token_sightings_v5", "cluster_sightings_v5", "ingest_ledger", "nonces", "installs",
        )

        /** The content tables keyed by epoch tags instead of the install id (#86). */
        val TAGGED_TABLES: Set<String> = TaggedTable.entries.map { it.table }.toSet()

        /** What "delete my data" erases (#79): every install-keyed table but the install row. */
        val DATA_TABLES: List<String> = WITHDRAWAL_TABLES - "installs"
    }
}

/** Rejection and duplicate accounting, shared with the ingest transaction. */
internal fun Connection.recordIngestCounters(
    installId: UUID,
    day: LocalDate,
    duplicate: Int,
    rejectedByReason: Map<String, Int>,
) {
    require(duplicate >= 0 && rejectedByReason.values.all { it >= 0 })
    update(
        """INSERT INTO ingest_ledger (install_id, day, duplicate, rejected)
            VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (install_id, day) DO UPDATE SET
                duplicate = ingest_ledger.duplicate + EXCLUDED.duplicate,
                rejected = (
                    SELECT COALESCE(jsonb_object_agg(reason, total), '{}'::jsonb) FROM (
                        SELECT reason, sum(amount::integer) AS total FROM (
                            SELECT key AS reason, value AS amount FROM jsonb_each_text(ingest_ledger.rejected)
                            UNION ALL
                            SELECT key AS reason, value AS amount FROM jsonb_each_text(EXCLUDED.rejected)
                        ) counts GROUP BY reason
                    ) totals
                )""",
        installId, day, duplicate, Json.encodeToString(rejectedByReason),
    )
}

/**
 * The ONE ledger-consume statement (atomic INSERT … ON CONFLICT with the three limits in its WHERE), callable inside a
 * caller's transaction so that consuming the budget and storing the work it paid for commit TOGETHER
 * (`SkeletonStore.ingest`) — a failure between the two must never leave a batch recorded as known but unstored.
 */
internal fun Connection.consumeLedger(
    installId: UUID,
    day: LocalDate,
    bytes: Long,
    items: Int,
    batchId: String?,
    policy: BudgetPolicy,
    now: Instant,
): ConsumeOutcome {
    require(bytes >= 0 && items >= 0)
    return select(
        """INSERT INTO ingest_ledger (install_id, day, bytes, accepted, batch_ids)
            SELECT ?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN '{}'::text[] ELSE ARRAY[?::text] END
            WHERE ?::bigint <= ? AND ?::integer <= ? AND (CASE WHEN ?::text IS NULL THEN 0 ELSE 1 END) <= ?
            ON CONFLICT (install_id, day) DO UPDATE SET
                bytes = ingest_ledger.bytes + EXCLUDED.bytes,
                accepted = ingest_ledger.accepted + EXCLUDED.accepted,
                batch_ids = CASE WHEN ?::text IS NULL OR ? = ANY(ingest_ledger.batch_ids)
                    THEN ingest_ledger.batch_ids ELSE array_append(ingest_ledger.batch_ids, ?::text) END
            WHERE ingest_ledger.bytes::numeric + EXCLUDED.bytes <= ?
                AND ingest_ledger.accepted::bigint + EXCLUDED.accepted <= ?
                AND cardinality(ingest_ledger.batch_ids) +
                    (CASE WHEN ?::text IS NULL OR ? = ANY(ingest_ledger.batch_ids) THEN 0 ELSE 1 END) <= ?
            RETURNING bytes, accepted, cardinality(batch_ids) AS batches""",
        installId, day, bytes, items, batchId, batchId,
        bytes, policy.dailyBytes, items, policy.dailySkeletonBudget, batchId, policy.dailyBatches,
        batchId, batchId, batchId, policy.dailyBytes, policy.dailySkeletonBudget, batchId, batchId, policy.dailyBatches,
    ) { row ->
        ConsumeOutcome.Consumed(
            policy.dailyBytes - row.getLong("bytes"), policy.dailySkeletonBudget - row.getInt("accepted"),
            policy.dailyBatches - row.getInt("batches"),
        )
    } ?: ConsumeOutcome.BudgetExhausted(secondsToUtcMidnight(now))
}

private fun sameHash(first: String, second: String): Boolean =
    MessageDigest.isEqual(first.toByteArray(Charsets.US_ASCII), second.toByteArray(Charsets.US_ASCII))

internal fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { statement ->
    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    statement.executeUpdate()
}

internal fun <T> Connection.select(sql: String, vararg args: Any?, read: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
    }

/** Internal progress carrier; never rendered into an ops response or logged as exception text. */
internal class SweepFailure(val deleted: Long, val batches: Int, cause: Exception) : RuntimeException(null, cause)
