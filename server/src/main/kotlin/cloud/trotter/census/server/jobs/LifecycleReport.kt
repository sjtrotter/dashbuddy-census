package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.Clock
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
data class SweepResult(
    val deleted: Long = 0, val rewritten: Long = 0, val batches: Int = 0,
    val capped: Boolean = false, val failed: Boolean = false, val remainingDue: Long? = null,
)

@Serializable
data class LifecycleView(
    val notificationsEnabled: Boolean, val configuredFloor: Int, val effectiveFloor: Int,
    val sweeps: Map<String, SweepResult>, val lastSuccessAgeSeconds: Long?,
    val eligibleQueueCount: Long, val filterRevTooOld: Long,
)

/** Contains only counts, bounded sweep names and age; failures never carry Throwable text. */
class LifecycleReport(
    private val clock: Clock, private val sink: AlarmSink = LoggingAlarmSink(),
) {
    private val startedAt = clock.now()
    @Volatile private var lastSuccess: Instant? = null
    @Volatile var sweeps: Map<String, SweepResult> = emptyMap()
        private set

    fun record(results: Map<String, SweepResult>) {
        sweeps = results.toMap()
        if (results.values.any { it.failed }) sink.raise(Alarm("lifecycle_failure", "_unknown", "0"))
        if (results.values.any { (it.remainingDue ?: 0) > 0 }) sink.raise(Alarm("lifecycle_backlog", "_unknown", "0"))
        if (results.isNotEmpty() && results.values.all { !it.failed && it.remainingDue == 0L }) lastSuccess = clock.now()
    }

    fun lastSuccessAgeSeconds(): Long? = lastSuccess?.let { Duration.between(it, clock.now()).seconds.coerceAtLeast(0) }
    fun watchdog() {
        if (Duration.between(lastSuccess ?: startedAt, clock.now()) >= Duration.ofHours(7)) {
            sink.raise(Alarm("lifecycle_stale", "_unknown", "0"))
        }
    }
}
