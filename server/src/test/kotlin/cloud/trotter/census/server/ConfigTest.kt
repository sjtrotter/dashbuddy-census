package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Checks fail-fast configuration and redaction without reading the host environment (#1157 S1). */
class ConfigTest {
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
