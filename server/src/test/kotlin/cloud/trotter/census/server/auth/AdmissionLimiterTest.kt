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
    fun `LRU is bounded and access keeps an exhausted bucket resident`() {
        val limiter = AdmissionLimiter(MutableClock())
        repeat(120) { assertTrue(limiter.admit("oldest")) }
        repeat(120) { assertTrue(limiter.admit("recent")) }
        repeat(9_998) { assertTrue(limiter.admit("id-$it")) }
        assertFalse(limiter.admit("recent"))
        assertTrue(limiter.admit("new"))
        assertEquals(10_000, limiter.size)
        assertFalse(limiter.admit("recent"))
        assertTrue(limiter.admit("oldest"))
        assertEquals(10_000, limiter.size)
    }
}
