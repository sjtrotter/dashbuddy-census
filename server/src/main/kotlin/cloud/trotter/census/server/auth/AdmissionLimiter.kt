package cloud.trotter.census.server.auth

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.SystemClock
import kotlinx.coroutines.sync.Semaphore
import java.time.Duration
import java.time.Instant

/**
 * Bounds pre-authentication work globally and by unverified install ID; no address is retained.
 * A known victim ID can be starved of its 120/minute by an attacker who knows it. This fairness
 * trade-off is accepted because the ID is a 128-bit random value and the authenticated per-install
 * quota is separate. Depleted buckets stay resident under churn; a full, indebted cache fails closed.
 */
class AdmissionLimiter(
    private val clock: Clock = SystemClock,
    private val globalAdmissionsPerMinute: Int = 600,
    private val maxBuckets: Int = 10_000,
) {
    init {
        require(globalAdmissionsPerMinute > 0 && maxBuckets > 0)
    }

    private data class Bucket(var tokens: Double, var updatedAt: Instant)

    private val buckets = LinkedHashMap<String, Bucket>(16, 0.75f, true)
    private var globalBucket: Bucket? = null
    private val inFlight = Semaphore(32)
    private var capacityDenialCount = 0L

    /** No waiting: callers must release in finally after their authentication decision. */
    internal fun tryAcquire(): Boolean = inFlight.tryAcquire()

    internal fun release() = inFlight.release()

    @Synchronized
    fun admit(installId: String, now: Instant = clock.now()): Boolean {
        val globalCapacity = globalAdmissionsPerMinute.toDouble()
        val global = globalBucket ?: Bucket(globalCapacity, now).also { globalBucket = it }
        refill(global, now, globalCapacity)
        if (global.tokens < 1.0) return false
        // Even failed per-ID admissions spend the global budget before accessing the LRU.
        global.tokens -= 1.0
        val bucket = buckets[installId] ?: run {
            if (buckets.size == maxBuckets && !evictEligible(now)) {
                capacityDenialCount++
                return false
            }
            Bucket(PER_ID_CAPACITY, now).also { buckets[installId] = it }
        }
        refill(bucket, now, PER_ID_CAPACITY)
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    private fun evictEligible(now: Instant): Boolean {
        val entries = buckets.entries.iterator()
        while (entries.hasNext()) {
            val bucket = entries.next().value
            refill(bucket, now, PER_ID_CAPACITY)
            if (bucket.tokens >= PER_ID_CAPACITY / 2) {
                entries.remove()
                return true
            }
        }
        return false
    }

    private fun refill(bucket: Bucket, now: Instant, capacity: Double) {
        if (now.isAfter(bucket.updatedAt)) {
            val elapsed = Duration.between(bucket.updatedAt, now)
            val seconds = elapsed.seconds + elapsed.nano / 1_000_000_000.0
            bucket.tokens = (bucket.tokens + seconds * capacity / 60).coerceAtMost(capacity)
            bucket.updatedAt = now
        }
    }

    internal val size: Int
        @Synchronized get() = buckets.size

    val capacityDenials: Long
        @Synchronized get() = capacityDenialCount

    private companion object {
        const val PER_ID_CAPACITY = 120.0
    }
}
