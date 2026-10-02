package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.auth.toLowerHex
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

class RevokedInstallException : RuntimeException()

data class Install(val id: UUID, val createdDay: LocalDate, val lastSeenDay: LocalDate, val trusted: Boolean)

data class LedgerRow(
    val bytes: Long = 0,
    val accepted: Int = 0,
    val duplicate: Int = 0,
    val rejectedByReason: Map<String, Int> = emptyMap(),
    val batchIds: List<String> = emptyList(),
)

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
                    update("UPDATE installs SET last_seen_day = ?, last_app_version = ? WHERE install_id = ?", day, appVersion, installId)
                    EnrolOutcome.SameKey
                }
            }
        } ?: error("Concurrent withdrawal during enrolment")
    }

    /** Null combines missing ID and wrong key. Revocation is distinguished only for a matching key. */
    suspend fun authenticate(installId: UUID, keyHash: String): Install? = query {
        select("SELECT * FROM installs WHERE install_id = ? FOR UPDATE", installId) { row ->
            if (!sameHash(row.getString("key_hash"), keyHash)) return@select null
            if (row.getObject("revoked_at") != null) throw RevokedInstallException()
            val day = clock.today()
            if (row.getObject("last_seen_day", LocalDate::class.java) != day) {
                update("UPDATE installs SET last_seen_day = ? WHERE install_id = ?", day, installId)
            }
            Install(installId, row.getObject("created_day", LocalDate::class.java), day, row.getBoolean("trusted"))
        }
    }

    suspend fun rotate(installId: UUID, newKeyHash: String): Boolean = query {
        update("UPDATE installs SET key_hash = ? WHERE install_id = ? AND revoked_at IS NULL", newKeyHash, installId) == 1
    }

    suspend fun revoke(installId: UUID, at: Instant): Boolean = query {
        update("UPDATE installs SET revoked_at = ? WHERE install_id = ?", at.atOffset(ZoneOffset.UTC), installId) == 1
    }

    suspend fun withdraw(installId: UUID): Map<String, Int> = query {
        // Lock the parent first: concurrent child inserts must finish before this lock or wait for deletion.
        select("SELECT install_id FROM installs WHERE install_id = ? FOR UPDATE", installId) { true }
        WITHDRAWAL_TABLES.associateWith { table -> update("DELETE FROM $table WHERE install_id = ?", installId) }
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

    suspend fun recordIngest(
        installId: UUID,
        day: LocalDate,
        bytes: Long,
        accepted: Int,
        duplicate: Int,
        rejectedByReason: Map<String, Int>,
        batchId: String?,
    ): Unit = query {
        require(bytes >= 0 && accepted >= 0 && duplicate >= 0 && rejectedByReason.values.all { it >= 0 })
        // One PostgreSQL upsert locks the row and adds counters, including concurrent first writes.
        update(
            """INSERT INTO ingest_ledger (install_id, day, bytes, accepted, duplicate, rejected, batch_ids)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, CASE WHEN ?::text IS NULL THEN '{}'::text[] ELSE ARRAY[?::text] END)
                ON CONFLICT (install_id, day) DO UPDATE SET
                    bytes = ingest_ledger.bytes + EXCLUDED.bytes,
                    accepted = ingest_ledger.accepted + EXCLUDED.accepted,
                    duplicate = ingest_ledger.duplicate + EXCLUDED.duplicate,
                    rejected = (
                        SELECT COALESCE(jsonb_object_agg(reason, total), '{}'::jsonb) FROM (
                            SELECT reason, sum(amount::integer) AS total FROM (
                                SELECT key AS reason, value AS amount FROM jsonb_each_text(ingest_ledger.rejected)
                                UNION ALL
                                SELECT key AS reason, value AS amount FROM jsonb_each_text(EXCLUDED.rejected)
                            ) counts GROUP BY reason
                        ) totals
                    ),
                    batch_ids = CASE WHEN cardinality(EXCLUDED.batch_ids) = 0
                        OR EXCLUDED.batch_ids[1] = ANY(ingest_ledger.batch_ids) THEN ingest_ledger.batch_ids
                        ELSE ingest_ledger.batch_ids || EXCLUDED.batch_ids END""",
            installId, day, bytes, accepted, duplicate, Json.encodeToString(rejectedByReason), batchId, batchId,
        )
        Unit
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

private fun sameHash(first: String, second: String): Boolean =
    MessageDigest.isEqual(first.toByteArray(Charsets.US_ASCII), second.toByteArray(Charsets.US_ASCII))

private fun Connection.update(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { statement ->
    args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    statement.executeUpdate()
}

private fun <T> Connection.select(sql: String, vararg args: Any?, read: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
    }
