package cloud.trotter.census.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LogPrivacyTest {
    @Test
    fun `privacy assertion fails on rendered messages nested causes and suppressed failures`() {
        val secret = "PRIVATE_ASSERTION_SENTINEL"
        val logger = LoggerFactory.getLogger("census.privacy.test") as Logger
        val nested = RuntimeException("safe", IllegalStateException("safe", IllegalArgumentException(secret)))
        val suppressed = RuntimeException("safe").apply {
            addSuppressed(IllegalStateException("safe", IllegalArgumentException(secret)))
        }
        listOf(nested, suppressed).forEach { failure ->
            val event = LoggingEvent(javaClass.name, logger, Level.ERROR, "safe", failure, null)
            assertThrows(AssertionError::class.java) { assertPrivateLogs(listOf(event), listOf(secret)) }
        }
        val formatted = LoggingEvent(javaClass.name, logger, Level.INFO, "value={}", null, arrayOf<Any>(secret))
        assertThrows(AssertionError::class.java) { assertPrivateLogs(listOf(formatted), listOf(secret)) }
        val safe = LoggingEvent(javaClass.name, logger, Level.INFO, "safe", RuntimeException("safe"), null)
        assertPrivateLogs(listOf(safe), listOf(secret))
    }
}
