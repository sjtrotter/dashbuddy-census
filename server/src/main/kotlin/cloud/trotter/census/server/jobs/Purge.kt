package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.InstallStore
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

data class PurgeReport(val nonces: Int, val ledgerRows: Int, val trustedEnvelopes: Int, val healthRows: Int, val withdrawals: Int)

class PurgeJob(
    private val store: InstallStore,
    private val clock: Clock,
    private val policy: Policy = Policy(),
    private val alarms: HealthAlarms? = null,
) {
    suspend fun runOnce(): PurgeReport {
        val now = clock.now()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        // Evaluate before retention removes the oldest available health history.
        sweep("silence") {
            alarms?.evaluateSilence()
            0
        }
        return PurgeReport(
            sweep("nonces") { store.purgeNonces(now.minusSeconds(3600)) },
            sweep("ledger") { store.purgeLedger(today.minusDays(policy.retention.ingestLedgerDays.toLong())) },
            sweep("envelopes") { store.purgeTrustedEnvelopes(today) },
            sweep("health") { store.purgeHealthDaily(today.minusDays(policy.retention.healthDailyDays.toLong())) },
            sweep("withdrawals") { store.purgeWithdrawals(now.minusSeconds(policy.retention.withdrawalsDays * 86_400L)) },
        )
    }

    private suspend fun sweep(name: String, block: suspend () -> Int): Int = runCatching { block() }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        log.warn("purge sweep={} failed class={}", name, failure.javaClass.simpleName)
        0
    }

    private val log = LoggerFactory.getLogger("Purge")
}
