package cloud.trotter.census.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LogPrivacyTest {
    @Test
    fun `nested and suppressed messages are private while class chains and outer frames remain`() {
        val secret = "PRIVATE_ASSERTION_SENTINEL"
        val logger = LoggerFactory.getLogger("census.privacy.test") as Logger
        val nested = RuntimeException("safe", IllegalStateException(secret)).apply {
            stackTrace = Array(4) { StackTraceElement("example.Outer", "frame$it", "PrivateFile.kt", it + 1) }
            addSuppressed(IllegalArgumentException("PRIVATE_SUPPRESSED_SENTINEL"))
        }
        val event = LoggingEvent(javaClass.name, logger, Level.ERROR, "safe event", nested, null)
        assertPrivateLogs(listOf(event), listOf(secret, "PRIVATE_SUPPRESSED_SENTINEL", "PrivateFile.kt"))
        val rendered = renderLog(event)
        assertTrue(rendered.contains("java.lang.RuntimeException <- java.lang.IllegalStateException"))
        assertEquals(3, Regex("example\\.Outer\\.frame[0-9]:[0-9]").findAll(rendered).count())
        assertFalse(rendered.contains("example.Outer.frame3"))
        assertFalse(rendered.contains("java.lang.IllegalArgumentException"))
        assertFalse(rendered.contains("RuntimeException: safe"))
    }

    @Test
    fun `privacy assertion still fails on formatted messages`() {
        val secret = "PRIVATE_MESSAGE_SENTINEL"
        val logger = LoggerFactory.getLogger("census.privacy.test") as Logger
        val formatted = LoggingEvent(javaClass.name, logger, Level.INFO, "value={}", null, arrayOf<Any>(secret))
        assertThrows(AssertionError::class.java) { assertPrivateLogs(listOf(formatted), listOf(secret)) }
        val safe = LoggingEvent(javaClass.name, logger, Level.INFO, "safe", RuntimeException(secret), null)
        assertPrivateLogs(listOf(safe), listOf(secret))
        assertTrue(renderLog(safe).contains("java.lang.RuntimeException"))
    }
}
