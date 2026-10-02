package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.InstallStore
import java.time.ZoneOffset

data class PurgeReport(val nonces: Int, val ledgerRows: Int, val trustedEnvelopes: Int, val healthRows: Int)

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
        alarms?.evaluateSilence()
        return PurgeReport(
            store.purgeNonces(now.minusSeconds(3600)),
            store.purgeLedger(today.minusDays(policy.retention.ingestLedgerDays.toLong())),
            store.purgeTrustedEnvelopes(today),
            store.purgeHealthDaily(today.minusDays(policy.retention.healthDailyDays.toLong())),
        )
    }
}
