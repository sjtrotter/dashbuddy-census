package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.LifecycleStore
import kotlinx.coroutines.CancellationException
import java.time.ZoneOffset

data class PurgeReport(val nonces: Int, val ledgerRows: Int, val trustedEnvelopes: Int, val healthRows: Int, val withdrawals: Int,
    val sweeps: Map<String, SweepResult> = emptyMap())

class PurgeJob(
    private val store: InstallStore,
    private val clock: Clock,
    private val policy: Policy = Policy(),
    private val alarms: HealthAlarms? = null,
    private val lifecycle: LifecycleStore? = null,
    private val report: LifecycleReport? = null,
    private val startupResults: Map<String, SweepResult> = emptyMap(),
) {
    suspend fun runOnce(): PurgeReport {
        val now = clock.now()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val results = linkedMapOf<String, SweepResult>()
        suspend fun sweep(name: String, block: suspend () -> Int): Int = try {
            val count = block()
            results[name] = SweepResult(deleted = count.toLong(), batches = count / 1000 + if (count < 50000) 1 else 0,
                capped = count == 50000, remainingDue = 0)
            count
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val progress = failure as? cloud.trotter.census.server.db.SweepFailure
            org.slf4j.LoggerFactory.getLogger("Purge").warn("purge sweep={} failed class={}", name, (progress?.cause ?: failure).javaClass.simpleName)
            results[name] = SweepResult(deleted=progress?.deleted ?: 0, batches=progress?.batches ?: 0, failed = true)
            -1 // Failure is never represented as zero work.
        }
        sweep("silence") { alarms?.evaluateSilence(); 0 }
        val nonces = sweep("nonces") { store.purgeNonces(now.minusSeconds(3600)) }
        val ledger = sweep("ledger") { store.purgeLedger(today.minusDays(policy.retention.ingestLedgerDays.toLong())) }
        val envelopes = sweep("envelopes") { store.purgeTrustedEnvelopes(today) }
        val health = sweep("health") { store.purgeHealthDaily(today.minusDays(policy.retention.healthDailyDays.toLong())) }
        val withdrawals = sweep("withdrawals") { store.purgeWithdrawals(now.minusSeconds(policy.retention.withdrawalsDays * 86400L)) }
        try {
            for ((name, due) in store.remainingDue(today, now)) results[name] = results.getValue(name).copy(remainingDue = due)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            results["retention_counts"] = SweepResult(failed = true)
        }
        lifecycle?.runOnce()?.let { results.putAll(it) }
        results.putAll(startupResults)
        report?.record(results)
        return PurgeReport(nonces, ledger, envelopes, health, withdrawals, results)
    }
}
