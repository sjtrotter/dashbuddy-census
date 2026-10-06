package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Checks fail-fast configuration and redaction without reading the host environment (#1157 S1). */
class ConfigTest {
    @Test fun `notification and revision settings are strict with safe defaults`() {
        val defaults = Config.fromEnv(testEnvironment())
        assertFalse(defaults.notificationsEnabled)
        assertEquals(1, defaults.minimumFilterRev)
        assertEquals(listOf("uinode.skeleton.v1"), defaults.policy().acceptedSchemaIds)
        val enabled = Config.fromEnv(testEnvironment() + mapOf("CENSUS_NOTIFICATIONS_ENABLED" to "true", "CENSUS_MIN_FILTER_REV" to "3"))
        assertEquals(listOf("uinode.skeleton.v1", "notification.skeleton.v1"), enabled.policy().acceptedSchemaIds)
        assertEquals(5, enabled.policy(5).minimumFilterRev)
        assertEquals(3, enabled.policy(1).minimumFilterRev)
        for ((key, values) in mapOf("CENSUS_NOTIFICATIONS_ENABLED" to listOf("", "TRUE", "1", " true"),
            "CENSUS_MIN_FILTER_REV" to listOf("", "0", "-1", "1.0", "2147483648", "PRIVATE_SENTINEL"))) {
            for (value in values) assertEquals("Invalid variable: $key", assertThrows(IllegalArgumentException::class.java) {
                Config.fromEnv(testEnvironment() + (key to value))
            }.message)
        }
    }

    @Test
    fun `alarm spool is optional and must be an absolute path without disclosing its value`() {
        assertEquals(null, Config.fromEnv(testEnvironment()).alarmSpoolDir)
        val dir = "/var/spool/census-alarms"
        val config = Config.fromEnv(testEnvironment() + ("ALARM_SPOOL_DIR" to dir))
        assertEquals(dir, config.alarmSpoolDir)
        assertEquals("Config([redacted])", config.toString())
        for (invalid in listOf("", "relative/private-dir", "/private-dir\u0000")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Config.fromEnv(testEnvironment() + ("ALARM_SPOOL_DIR" to invalid))
            }
            assertEquals("Invalid variable: ALARM_SPOOL_DIR", failure.message)
            assertThrows(IllegalArgumentException::class.java) { config.copy(alarmSpoolDir = invalid) }
        }
    }

    @Test
    fun `missing database URL names only the variable`() {
        val env = testEnvironment() - "DATABASE_URL"
        val failure = assertThrows(IllegalArgumentException::class.java) { Config.fromEnv(env) }
        val message = requireNotNull(failure.message)
        assertTrue(message.contains("DATABASE_URL"))
        env.values.forEach { assertFalse(message.contains(it)) }
        assertFalse(message.contains('\n'))
    }

    @Test
    fun `port is fixed at 8080 regardless of environment`() {
        assertEquals(8080, Config.fromEnv(testEnvironment()).port)
        assertEquals(8080, Config.fromEnv(testEnvironment() + ("PORT" to "9090")).port)
        assertEquals(8080, Config.fromEnv(testEnvironment() + ("PORT" to "private-invalid-value")).port)
    }

    @Test
    fun `invalid configuration is redacted`() {
        val env = testEnvironment() + ("OPERATOR_TOKEN_SHA256" to "private-invalid-value")
        val failure = assertThrows(IllegalArgumentException::class.java) { Config.fromEnv(env) }
        assertEquals("Invalid variable: OPERATOR_TOKEN_SHA256", failure.message)
        val config = Config.fromEnv(testEnvironment())
        assertFalse(config.toString().contains(config.databasePassword))
    }
}

internal fun testEnvironment(): Map<String, String> = mapOf(
    "DATABASE_URL" to "jdbc:postgresql://unused.invalid/census",
    "DATABASE_USER" to "test-database-user",
    "DATABASE_PASSWORD" to "private-password-sentinel",
    "PUBLIC_HOST" to "census.test.invalid",
    "OPERATOR_TOKEN_SHA256" to "a".repeat(64),
)
