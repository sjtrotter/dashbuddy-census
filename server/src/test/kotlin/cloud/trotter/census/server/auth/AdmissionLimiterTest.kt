package cloud.trotter.census.server.auth

import cloud.trotter.census.server.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AdmissionLimiterTest {
    private class MutableClock(var instant: Instant = Instant.parse("2026-10-02T12:00:00Z")) : Clock {
        override fun now(): Instant = instant
    }

    @Test
    fun `120 token burst refills continuously and tolerates a backwards clock`() {
        val clock = MutableClock()
        val limiter = AdmissionLimiter(clock)
        repeat(120) { assertTrue(limiter.admit("one")) }
        assertFalse(limiter.admit("one"))
        assertTrue(limiter.admit("two"))
        clock.instant = clock.instant.minusSeconds(1)
        assertFalse(limiter.admit("one"))
        clock.instant = clock.instant.plusMillis(1499)
        assertFalse(limiter.admit("one"))
        clock.instant = clock.instant.plusMillis(1)
        assertTrue(limiter.admit("one"))
        assertFalse(limiter.admit("one"))
        clock.instant = clock.instant.plusSeconds(60)
        repeat(120) { assertTrue(limiter.admit("one")) }
        assertFalse(limiter.admit("one"))
    }

    @Test
    fun `LRU stays bounded and exhausted victim remains denied after churn`() {
        val limiter = AdmissionLimiter(MutableClock(), globalAdmissionsPerMinute = 20_000)
        repeat(120) { assertTrue(limiter.admit("V")) }
        repeat(120) { assertTrue(limiter.admit("recent")) }
        repeat(9_998) { assertTrue(limiter.admit("id-$it")) }
        assertFalse(limiter.admit("recent"))
        repeat(100) { assertTrue(limiter.admit("new-$it")) }
        assertEquals(10_000, limiter.size)
        assertFalse(limiter.admit("recent"))
        assertFalse(limiter.admit("V"))
        assertEquals(10_000, limiter.size)
    }

    @Test
    fun `10000 fresh IDs exhaust global admission before the LRU fills`() {
        val clock = MutableClock()
        val limiter = AdmissionLimiter(clock)
        val admitted = (1..10_000).count { limiter.admit("id-$it") }
        assertEquals(600, admitted)
        assertEquals(600, limiter.size)
        assertEquals(0L, limiter.capacityDenials)
        clock.instant = clock.instant.minusSeconds(1)
        assertFalse(limiter.admit("new"))
        clock.instant = clock.instant.plusMillis(1099)
        assertFalse(limiter.admit("new"))
        clock.instant = clock.instant.plusMillis(1)
        assertTrue(limiter.admit("new"))
        assertFalse(limiter.admit("another"))
    }

    @Test
    fun `full indebted LRU fails closed and counts new ID denials`() {
        val clock = MutableClock()
        val limiter = AdmissionLimiter(clock, maxBuckets = 2)
        repeat(120) { assertTrue(limiter.admit("V")) }
        repeat(61) { assertTrue(limiter.admit("other")) }
        assertFalse(limiter.admit("new"))
        assertEquals(1L, limiter.capacityDenials)
        assertEquals(2, limiter.size)
        assertFalse(limiter.admit("V"))
        // Half capacity is eligible; the exhausted oldest bucket must still survive.
        clock.instant = clock.instant.plusMillis(500)
        assertTrue(limiter.admit("new"))
        assertEquals(2, limiter.size)
        assertTrue(limiter.admit("V")) // Only its one refilled token, never a fresh burst.
        assertFalse(limiter.admit("V"))
        assertEquals(1L, limiter.capacityDenials)
    }

    @Test
    fun `least recently used eligible bucket is evicted`() {
        val limiter = AdmissionLimiter(MutableClock(), maxBuckets = 2)
        repeat(60) { assertTrue(limiter.admit("oldest")) }
        repeat(60) { assertTrue(limiter.admit("recent")) }
        assertTrue(limiter.admit("new"))
        repeat(60) { assertTrue(limiter.admit("recent")) }
        assertFalse(limiter.admit("recent"))
        repeat(120) { assertTrue(limiter.admit("oldest")) }
        assertFalse(limiter.admit("oldest"))
    }

    @Test
    fun `explicit request instant avoids another clock read`() {
        val limiter = AdmissionLimiter(object : Clock {
            override fun now(): Instant = error("Unexpected clock read")
        }, globalAdmissionsPerMinute = 1)
        val now = Instant.parse("2026-10-02T12:00:00Z")
        assertTrue(limiter.admit("one", now))
        assertFalse(limiter.admit("two", now))
        assertTrue(limiter.admit("two", now.plusSeconds(60)))
    }

    @Test
    fun `in flight permits are globally bounded and immediately reusable`() {
        val limiter = AdmissionLimiter(MutableClock())
        repeat(32) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        limiter.release()
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        repeat(32) { limiter.release() }
        repeat(32) { assertTrue(limiter.tryAcquire()) }
        repeat(32) { limiter.release() }
    }
}
