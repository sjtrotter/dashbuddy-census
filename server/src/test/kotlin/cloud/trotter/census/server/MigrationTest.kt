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
    @Test fun `populated V4 upgrades additively to V5 and validates repeated DDL`() {
        fun migration(target: String) = Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password)
            .schemas("lifecycle_upgrade").defaultSchema("lifecycle_upgrade").locations("classpath:db/migration").target(target).load()
        migration("4").migrate()
        DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO lifecycle_upgrade")
                statement.execute("INSERT INTO installs(install_id,key_hash,created_day,last_seen_day) VALUES('00000000-0000-4000-8000-000000000001',repeat('a',64),'2026-10-01','2026-10-01')")
                statement.execute("INSERT INTO token_sightings VALUES('0123456789abcdef','00000000-0000-4000-8000-000000000001','2026-10-01','2026-10-01','words:1')")
                assertEquals(1,migration("5").migrate().migrationsExecuted)
                assertEquals(0,migration("5").migrate().migrationsExecuted)
                val ddl=javaClass.getResourceAsStream("/db/migration/V5__skeleton_lifecycle.sql")!!.bufferedReader().use { it.readText() }
                statement.execute(ddl)
                statement.executeQuery("SELECT count(*) FROM token_sightings").use { it.next(); assertEquals(1,it.getInt(1)) }
                statement.executeQuery("SELECT count(*) FROM token_sightings_v5").use { it.next(); assertEquals(0,it.getInt(1)) }
                assertTrue(runCatching { statement.execute("INSERT INTO filter_floor VALUES(false,1,'2026-10-01')") }.isFailure)
                assertTrue(runCatching { statement.execute("INSERT INTO filter_floor VALUES(true,0,'2026-10-01')") }.isFailure)
                statement.execute("ALTER TABLE token_sightings_v5 DROP CONSTRAINT token_sightings_v5_filter_rev_check")
                assertTrue(runCatching { statement.execute(ddl) }.isFailure)
            }
        }
    }

    @Test
    fun `V3 to V4 backfills screen and preserves sample relationship`() {
        fun migration(target: String) = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("notification_upgrade").defaultSchema("notification_upgrade")
            .locations("classpath:db/migration").target(target).load()
        migration("3").migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { stmt ->
                stmt.execute("SET search_path TO notification_upgrade")
                stmt.execute("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (repeat('a',64), 'uber', '2026-10-02', '2026-10-02')")
                stmt.execute("INSERT INTO cluster_samples VALUES (repeat('a',64), '1.0', '2026-10-02', '{}'::jsonb)")
                assertEquals(1, migration("4").migrate().migrationsExecuted)
                stmt.executeQuery("SELECT c.kind, s.platform_app_version FROM clusters c JOIN cluster_samples s USING (fingerprint)").use { rows ->
                    assertTrue(rows.next()); assertEquals("screen", rows.getString(1)); assertEquals("1.0", rows.getString(2))
                    assertTrue(!rows.next())
                }
                org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException::class.java) {
                    stmt.execute("UPDATE clusters SET kind = 'invalid'")
                }
                org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException::class.java) {
                    stmt.execute("UPDATE clusters SET kind = NULL")
                }
            }
        }
    }

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
    fun `empty database applies every migration and contains exactly sixteen application tables`() {
        val flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
        // V1 schema, V2 cluster classification, V3 withdrawal tombstones and enrolment instants.
        assertEquals(5, flyway.migrate().migrationsExecuted)
        val expected = setOf(
            "installs", "clusters", "cluster_samples", "cluster_sightings", "token_sightings",
            "vocabulary", "trusted_envelopes", "health_daily", "health_fleet_daily", "ingest_ledger", "nonces", "withdrawals", "token_sightings_v5", "cluster_sightings_v5", "vocabulary_v5", "filter_floor",
        )
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'withdrawals'",
                ).use { rows ->
                    val indexes = buildSet { while (rows.next()) add(rows.getString(1)) }
                    assertEquals(setOf("withdrawals_pkey", "withdrawals_withdrawn_at_idx"), indexes)
                }
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
                    assertEquals(listOf("text", null, "NO", null), columns["withdrawals.install_id_hash"])
                    assertEquals(listOf("timestamp with time zone", null, "NO", null), columns["withdrawals.withdrawn_at"])
                    assertEquals(listOf("timestamp with time zone", null, "NO", "CURRENT_TIMESTAMP"), columns["installs.enrolled_at"])
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
