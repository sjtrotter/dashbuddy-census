package cloud.trotter.census.server.ops

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class OpsSessionsTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")

    @Test
    fun `created ids are unpadded base64url and only the newest session validates`() {
        val sessions = OpsSessions()
        val first = sessions.create(now)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first))
        assertTrue(sessions.validate(first, now))
        val second = sessions.create(now)
        assertFalse(sessions.validate(first, now))
        assertTrue(sessions.validate(second, now))
    }

    @Test
    fun `idle expiry drops the session including at the exact boundary`() {
        for (seconds in listOf(3600L, 3660L)) {
            val sessions = OpsSessions()
            val raw = sessions.create(now)
            assertFalse(sessions.validate(raw, now.plusSeconds(seconds)))
            assertFalse(sessions.validate(raw, now), "an expired session cannot return after clock rollback")
        }
    }

    @Test
    fun `touches extend idle time but cannot extend the twelve hour lifetime`() {
        val sessions = OpsSessions()
        val raw = sessions.create(now)
        for (halfHour in 1..23) assertTrue(sessions.validate(raw, now.plusSeconds(halfHour * 1800L)))
        assertFalse(sessions.validate(raw, now.plusSeconds(43200)))
        assertFalse(sessions.validate(raw, now))
    }

    @Test
    fun `absent malformed and mismatched ids do not revoke or refresh a session`() {
        assertFalse(OpsSessions().validate("a".repeat(43), now))
        val sessions = OpsSessions()
        val raw = sessions.create(now)
        val other = (if (raw[0] == 'a') "b" else "a") + raw.drop(1)
        val invalidIds = listOf(null, "", "short", "a".repeat(42), "a".repeat(44), "!".repeat(43), "a".repeat(42) + "\n", other)
        for (invalid in invalidIds) {
            assertFalse(sessions.validate(invalid, now.plusSeconds(1800)))
        }
        assertFalse(sessions.validate(raw, now.plusSeconds(3600)), "invalid input must not extend idle time")
        val fresh = sessions.create(now)
        for (invalid in invalidIds) assertFalse(sessions.validate(invalid, now))
        assertTrue(sessions.validate(fresh, now), "invalid input must leave the current session intact")
    }

    @Test
    fun `revocation immediately invalidates the cookie`() {
        val sessions = OpsSessions()
        val raw = sessions.create(now)
        sessions.revoke()
        assertFalse(sessions.validate(raw, now))
    }
}
