package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.db.InstallStore
import java.time.ZoneOffset

data class PurgeReport(val nonces: Int, val ledgerRows: Int)

class PurgeJob(private val store: InstallStore, private val clock: Clock) {
    suspend fun runOnce(): PurgeReport {
        val now = clock.now()
        return PurgeReport(
            store.purgeNonces(now.minusSeconds(3600)),
            store.purgeLedger(now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(7)),
        )
    }
}
