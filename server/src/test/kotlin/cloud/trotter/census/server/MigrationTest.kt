package cloud.trotter.census.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/** Verifies real PostgreSQL; Docker is mandatory in CI and optional locally (#1157 S1). */
@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: PostgreSQL migration test skipped")
class MigrationTest {
    @Test
    fun `readiness is available with a database`() {
        val config = Config.fromEnv(
            testEnvironment() + mapOf(
                "DATABASE_URL" to postgres.jdbcUrl,
                "DATABASE_USER" to postgres.username,
                "DATABASE_PASSWORD" to postgres.password,
            ),
        )
        Database.connect(config).use { db ->
            testApplication {
                application { module(config, db) }
                val response = client.get("/readyz")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
            }
        }
    }

    @Test
    fun `empty database applies every migration and contains exactly eleven application tables`() {
        val flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
        // V1 (schema) + V2 (cluster classification: screen_class, draft, draft_day — DashBuddy #1188).
        assertEquals(2, flyway.migrate().migrationsExecuted)
        val expected = setOf(
            "installs", "clusters", "cluster_samples", "cluster_sightings", "token_sightings",
            "vocabulary", "trusted_envelopes", "health_daily", "health_fleet_daily", "ingest_ledger", "nonces",
        )
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                ).use { rows ->
                    val actual = buildSet {
                        while (rows.next()) add(rows.getString(1))
                    }
                    assertEquals(expected, actual)
                }
                statement.executeQuery(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = 'clusters' AND column_name IN ('screen_class', 'draft', 'draft_day')",
                ).use { rows ->
                    val columns = buildSet { while (rows.next()) add(rows.getString(1)) }
                    assertEquals(setOf("screen_class", "draft", "draft_day"), columns)
                }
                statement.executeQuery(
                    """
                    SELECT table_name, column_name, data_type, character_maximum_length, is_nullable, column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                    """.trimIndent(),
                ).use { rows ->
                    val columns = buildMap {
                        while (rows.next()) {
                            put(
                                "${rows.getString("table_name")}.${rows.getString("column_name")}",
                                listOf(
                                    rows.getString("data_type"), rows.getString("character_maximum_length"),
                                    rows.getString("is_nullable"), rows.getString("column_default"),
                                ),
                            )
                        }
                    }
                    assertEquals(listOf("character", "64", "NO", null), columns["installs.key_hash"])
                    assertEquals(listOf("character", "16", "NO", null), columns["token_sightings.token_hash"])
                    assertEquals(listOf("integer", null, "NO", "1"), columns["cluster_sightings.count"])
                    assertEquals(listOf("timestamp with time zone", null, "NO", null), columns["nonces.issued_at"])
                }
                statement.executeQuery(
                    """
                    SELECT table_name, column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                        AND (column_name ~* '(^|_)(ip|ipv4|ipv6|addr|address|client_ip|remote_ip|user_agent|device_id|imei|android_id)($|_)')
                    """.trimIndent(),
                ).use { rows ->
                    assertTrue(!rows.next(), "Application tables must not contain IP / address / device-identifier columns (token match, not substring: `trips` is the recognition-health field)")
                }
            }
        }
        assertEquals(0, flyway.migrate().migrationsExecuted)
    }

    /** Shares a disposable PostgreSQL instance and the availability condition (#1157 S1). */
    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @JvmStatic
        fun dockerAvailable(): Boolean = System.getenv("CI") == "true" || runCatching {
            DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false)
    }
}
