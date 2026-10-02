package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.auth.toLowerHex
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ConsumeOutcome
import cloud.trotter.census.server.secondsToUtcMidnight
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

enum class EnrolOutcome { Created, SameKey, KeyMismatch, Revoked }

sealed interface MutationOutcome {
    data class Applied(val deletedRows: Map<String, Int> = emptyMap()) : MutationOutcome
    data object StaleCredential : MutationOutcome
}

data class Install(
    val id: UUID,
    val createdDay: LocalDate,
    val lastSeenDay: LocalDate,
    val trusted: Boolean,
    val keyHash: String,
    val revokedAt: Instant?,
) {
    override fun toString(): String =
        "Install(id=${id.toString().take(8)}…, keyHash=[redacted], createdDay=$createdDay, " +
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
        val day = clock.today()
        val inserted = update(
            """INSERT INTO installs (install_id, key_hash, created_day, last_seen_day, last_app_version)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (install_id) DO NOTHING""",
            installId, keyHash, day, day, appVersion,
        )
        if (inserted == 1) return@query EnrolOutcome.Created
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

    suspend fun revoke(installId: UUID, at: Instant): Boolean = query {
        update("UPDATE installs SET revoked_at = ? WHERE install_id = ?", at.atOffset(ZoneOffset.UTC), installId) == 1
    }

    suspend fun withdraw(installId: UUID, expectedCurrentKeyHash: String): MutationOutcome = query {
        // Lock the parent first: concurrent child inserts must finish before this lock or wait for deletion.
        val current = select("SELECT key_hash, revoked_at FROM installs WHERE install_id = ? FOR UPDATE", installId) { row ->
            val active = row.getObject("revoked_at") == null
            active && sameHash(row.getString("key_hash"), expectedCurrentKeyHash)
        } ?: false
        if (!current) return@query MutationOutcome.StaleCredential
        MutationOutcome.Applied(WITHDRAWAL_TABLES.associateWith { table -> update("DELETE FROM $table WHERE install_id = ?", installId) })
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

    suspend fun purgeNonces(olderThan: Instant): Int = query {
        update("DELETE FROM nonces WHERE issued_at < ?", olderThan.atOffset(ZoneOffset.UTC))
    }

    suspend fun purgeLedger(olderThan: LocalDate): Int = query {
        update("DELETE FROM ingest_ledger WHERE day < ?", olderThan)
    }

    /** The insert and conflict-update paths both enforce quotas in the same PostgreSQL statement. */
    suspend fun tryConsume(
        installId: UUID,
        day: LocalDate,
        bytes: Long,
        items: Int,
        batchId: String?,
        policy: BudgetPolicy,
    ): ConsumeOutcome = query { consumeLedger(installId, day, bytes, items, batchId, policy, clock.now()) }

    /** Rejections and duplicates do not consume quota; accepted work must use tryConsume. */
    suspend fun recordIngest(
        installId: UUID,
        day: LocalDate,
        duplicate: Int,
        rejectedByReason: Map<String, Int>,
    ): Unit = query {
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
        Unit
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
            batchId, installId, day,
        ) { it.getBoolean("known") } ?: false
    }

    companion object {
        val WITHDRAWAL_TABLES: List<String> = listOf(
            "trusted_envelopes", "health_daily", "token_sightings", "cluster_sightings", "ingest_ledger", "nonces", "installs",
        )
    }
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
