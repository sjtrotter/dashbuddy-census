package cloud.trotter.census.server.auth

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.SystemClock
import java.time.Duration
import java.time.Instant

/** Unverified install ID strings only; no request address is read or retained. */
class AdmissionLimiter(private val clock: Clock = SystemClock) {
    private data class Bucket(var tokens: Double, var updatedAt: Instant)

    private val buckets = LinkedHashMap<String, Bucket>(16, 0.75f, true)

    @Synchronized
    fun admit(installId: String): Boolean {
        val now = clock.now()
        val bucket = buckets.getOrPut(installId) { Bucket(120.0, now) }
        if (buckets.size > 10_000) {
            val oldest = buckets.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        if (now.isAfter(bucket.updatedAt)) {
            val elapsed = Duration.between(bucket.updatedAt, now)
            bucket.tokens = (bucket.tokens + elapsed.seconds * 2.0 + elapsed.nano / 500_000_000.0).coerceAtMost(120.0)
            bucket.updatedAt = now
        }
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    internal val size: Int
        @Synchronized get() = buckets.size
}
