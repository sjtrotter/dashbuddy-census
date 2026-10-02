package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ConsumeOutcome
import cloud.trotter.census.server.ingest.EnvelopeVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

sealed interface EnvelopeOutcome {
    data object StaleCredential : EnvelopeOutcome
    data object NotTrusted : EnvelopeOutcome
    data object BatchQuality : EnvelopeOutcome
    data object Duplicate : EnvelopeOutcome
    data class Stored(val consumed: ConsumeOutcome.Consumed) : EnvelopeOutcome
    data class BudgetExhausted(val retryAfterSeconds: Long) : EnvelopeOutcome
}

class EnvelopeStore(private val db: Database, private val clock: Clock) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    suspend fun ingest(
        installId: UUID,
        keyHash: String,
        day: LocalDate,
        accepted: List<EnvelopeVerdict.Accepted>,
        rejected: Map<String, Int>,
        batchId: String,
        bodyBytes: Long,
        policy: BudgetPolicy,
        retentionDays: Int,
        now: Instant = clock.now(),
    ): EnvelopeOutcome = query {
        val trusted = select(
            "SELECT trusted FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL FOR SHARE",
            installId, keyHash,
        ) { it.getBoolean("trusted") } ?: return@query EnvelopeOutcome.StaleCredential
        if (!trusted) return@query EnvelopeOutcome.NotTrusted
        val itemCount = accepted.size + rejected.values.sum()
        if (rejected.values.sum() * 5 > itemCount) {
            recordIngestCounters(installId, day, 0, rejected)
            return@query EnvelopeOutcome.BatchQuality
        }
        // Same ledger claim + duplicate lock as SkeletonStore; quota and rows commit together.
        update("INSERT INTO ingest_ledger (install_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING", installId, day)
        val known = select(
            "SELECT batch_ids FROM ingest_ledger WHERE install_id = ? AND day = ? FOR UPDATE", installId, day,
        ) { row ->
            val array = row.getArray("batch_ids")
            try { batchId in (array.array as Array<*>) } finally { array.free() }
        } ?: error("Missing ledger row")
        if (known) {
            recordIngestCounters(installId, day, itemCount, emptyMap())
            return@query EnvelopeOutcome.Duplicate
        }
        val consumed = when (val result = consumeLedger(installId, day, bodyBytes, accepted.size, batchId, policy, now)) {
            is ConsumeOutcome.BudgetExhausted -> return@query EnvelopeOutcome.BudgetExhausted(result.retryAfterSeconds)
            is ConsumeOutcome.Consumed -> result
        }
        for (item in accepted) {
            update(
                """INSERT INTO trusted_envelopes (install_id, fingerprint, envelope, received_day, purge_after)
                    VALUES (?, NULL, ?::jsonb, ?, ?)""",
                installId, item.canonicalJson, day, day.plusDays(retentionDays.toLong()),
            )
        }
        recordIngestCounters(installId, day, 0, rejected)
        EnvelopeOutcome.Stored(consumed)
    }
}
