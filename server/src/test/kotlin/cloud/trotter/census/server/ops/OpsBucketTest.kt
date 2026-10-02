package cloud.trotter.census.server.ops

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OpsBucketTest {
    @Test
    fun `exactly one token returns at one second and a backwards clock re-bases instead of freezing`() {
        val bucket = OpsBucket()
        val start = Instant.parse("2026-10-02T12:00:00Z")
        repeat(60) { assertTrue(bucket.admit(start)) }
        assertFalse(bucket.admit(start))
        // Ten 100 ms steps: the tenth (T + 1 s exactly) admits — integer millitokens, no float undershoot.
        for (step in 1..9) assertFalse(bucket.admit(start.plusMillis(100L * step)))
        assertTrue(bucket.admit(start.plusMillis(1_000)))
        assertFalse(bucket.admit(start.plusMillis(1_000)))
        // Clock rollback: denied now, but the anchor moves to the new time so refill resumes from there.
        val earlier = start.minusSeconds(60)
        assertFalse(bucket.admit(earlier))
        assertFalse(bucket.admit(earlier.plusMillis(999)))
        assertTrue(bucket.admit(earlier.plusMillis(1_000)))
    }
}
