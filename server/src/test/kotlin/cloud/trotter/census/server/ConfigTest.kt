package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Checks fail-fast configuration and redaction without reading the host environment (#1157 S1). */
class ConfigTest {
    @Test
    fun `SNS topic is optional and validated without disclosing its value`() {
        assertEquals(null, Config.fromEnv(testEnvironment()).alertsTopicArn)
        val arn = "arn:aws:sns:us-east-2:000000000000:census-alerts_1"
        val config = Config.fromEnv(testEnvironment() + ("ALERTS_TOPIC_ARN" to arn))
        assertEquals(arn, config.alertsTopicArn)
        assertEquals("Config([redacted])", config.toString())
        val prefix = "arn:aws:sns:us-east-2:000000000000:"
        assertEquals(prefix + "a".repeat(256), config.copy(alertsTopicArn = prefix + "a".repeat(256)).alertsTopicArn)
        for (invalid in listOf("", "CHANGE-ME", "$arn\n", "$arn.fifo", arn.replace("sns:", "sqs:"), prefix, prefix + "a".repeat(257))) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Config.fromEnv(testEnvironment() + ("ALERTS_TOPIC_ARN" to invalid))
            }
            assertEquals("Invalid variable: ALERTS_TOPIC_ARN", failure.message)
            assertThrows(IllegalArgumentException::class.java) { config.copy(alertsTopicArn = invalid) }
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
