package cloud.trotter.census.server.jobs

import cloud.trotter.census.server.alertsTopicArnPattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/** WARN is the system of record; SNS is a bounded, best-effort copy owned by the application. */
class SnsAlarmSink(
    topicArn: String,
    private val publisher: (subject: String, message: String) -> Unit,
    private val fallback: AlarmSink = LoggingAlarmSink(),
) : AlarmSink {
    init {
        require(alertsTopicArnPattern.matches(topicArn)) { "Invalid variable: ALERTS_TOPIC_ARN" }
    }

    private val pending = Channel<String>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val started = AtomicBoolean()
    private val log = LoggerFactory.getLogger("Alarm")

    override fun raise(alarm: Alarm) {
        try {
            fallback.raise(alarm)
        } catch (_: Exception) {
            // A broken fallback must not turn alarm delivery into a data-path failure either.
        }
        pending.trySend(renderAlarm(alarm))
    }

    /** Start exactly one consumer in the application scope; monotonic time is a test seam. */
    fun start(scope: CoroutineScope, stats: AlarmStats, nanoTime: () -> Long = System::nanoTime): Job {
        check(started.compareAndSet(false, true)) { "SNS alarm consumer already started" }
        return scope.launch(Dispatchers.IO) {
            var lastWarning: Long? = null
            try {
                for (message in pending) {
                    try {
                        val kind = message.substringBefore('\n').removePrefix("kind=")
                        publisher("census alarm: $kind", message)
                    } catch (failure: Exception) {
                        if (failure is CancellationException && !isActive) throw failure
                        stats.record("sns_failed")
                        val now = nanoTime()
                        val previous = lastWarning
                        if (previous == null || now - previous >= 10.minutes.inWholeNanoseconds) {
                            lastWarning = now
                            log.warn("sns_publish_failed class={}", failure.javaClass.simpleName)
                        }
                    }
                }
            } finally {
                pending.cancel()
            }
        }
    }
}
