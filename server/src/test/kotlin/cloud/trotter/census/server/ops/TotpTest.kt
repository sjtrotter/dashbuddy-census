package cloud.trotter.census.server.ops

import cloud.trotter.census.server.Config
import cloud.trotter.census.server.testEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class TotpTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun `RFC 6238 SHA1 vectors truncated to six digits`() {
        mapOf(59L to "287082", 1111111109L to "081804", 1234567890L to "005924", 2000000000L to "279037").forEach { (time, code) ->
            assertEquals(code, Totp.code(secret, time))
        }
    }

    @Test
    fun `adjacent steps only and malformed digits fail closed`() {
        val now = Instant.ofEpochSecond(1234567890)
        for (offset in -1L..1L) assertTrue(Totp.matches(secret, Totp.code(secret, now.epochSecond + offset * 30), now))
        for (offset in listOf(-2L, 2L)) assertFalse(Totp.matches(secret, Totp.code(secret, now.epochSecond + offset * 30), now))
        for (code in listOf(null, "", "00592", "0005924", " 005924", "005924\n", "abcdef", "００５９２４")) {
            assertFalse(Totp.matches(secret, code, now))
        }
    }

    @Test
    fun `accepted codes cannot be replayed across adjacent steps`() {
        val replay = TotpReplay()
        val now = Instant.ofEpochSecond(1234567890)
        val code = Totp.code(secret, now.epochSecond)
        assertEquals(TotpDecision.Required, replay.verify(secret, null, now))
        assertEquals(TotpDecision.Accepted, replay.verify(secret, code, now))
        assertEquals(TotpDecision.Replayed, replay.verify(secret, code, now))
        assertEquals(TotpDecision.Replayed, replay.verify(secret, code, now.plusSeconds(30)))
        assertEquals(TotpDecision.Required, replay.verify(secret, code, now.plusSeconds(90)))
        assertEquals(TotpDecision.Accepted, replay.verify(secret, Totp.code(secret, now.epochSecond + 90), now.plusSeconds(90)))
    }

    @Test
    fun `startup accepts canonical base32 and rejects invalid secrets without disclosing values`() {
        assertEquals(secret, Config.fromEnv(testEnvironment() + ("OPERATOR_TOTP_SECRET" to secret)).operatorTotpSecret)
        for (value in listOf("", "A".repeat(15), "A".repeat(65), "a".repeat(16), "0".repeat(16), "A".repeat(17), "A".repeat(17) + "B")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Config.fromEnv(testEnvironment() + ("OPERATOR_TOTP_SECRET" to value))
            }
            assertEquals("Invalid variable: OPERATOR_TOTP_SECRET", failure.message)
        }
    }
}
