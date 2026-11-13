package cloud.trotter.census.server

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.db.HashPepperMismatchException
import cloud.trotter.census.server.db.KeyedHashStorageMigration
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.atRest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `populated V5 upgrades to V6 keeping the replay order without an enrolment instant`() {
        fun migration(target: String) = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas("keep_less").defaultSchema("keep_less").locations("classpath:db/migration").target(target).load()
        migration("5").migrate()
        val withdrawn = "00000000-0000-4000-8000-000000000001"
        val reenrolled = "00000000-0000-4000-8000-000000000002"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET search_path TO keep_less")
                for (id in listOf(withdrawn, reenrolled)) {
                    statement.execute("INSERT INTO withdrawals VALUES (encode(sha256('$id'::bytea), 'hex'), '2026-10-01T12:00:00Z')")
                }
                // A resurrected generation (enrolled before its tombstone) and a re-enrolment after it.
                statement.execute("INSERT INTO installs (install_id, key_hash, created_day, last_seen_day, enrolled_at) VALUES ('$withdrawn', repeat('a',64), '2026-10-01', '2026-10-01', '2026-10-01T11:00:00Z')")
                statement.execute("INSERT INTO installs (install_id, key_hash, created_day, last_seen_day, enrolled_at) VALUES ('$reenrolled', repeat('a',64), '2026-10-01', '2026-10-01', '2026-10-01T13:00:00Z')")
                statement.execute("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (repeat('a',64), 'uber', '2026-10-01', '2026-10-01')")
                statement.execute("INSERT INTO cluster_sightings_v5 VALUES (repeat('a',64), '$reenrolled', '2026-10-01', '1.0', 7, 1, 1)")
                assertEquals(1, migration("6").migrate().migrationsExecuted)
                statement.executeQuery("SELECT install_id::text, erased_through IS NOT NULL FROM installs ORDER BY install_id").use { rows ->
                    assertTrue(rows.next()); assertEquals(withdrawn, rows.getString(1)); assertEquals(false, rows.getBoolean(2))
                    assertTrue(rows.next()); assertEquals(reenrolled, rows.getString(1)); assertEquals(true, rows.getBoolean(2))
                }
                // Presence only: the old count collapses to 1 and a count above 1 is refused.
                statement.executeQuery("SELECT count FROM cluster_sightings_v5").use { it.next(); assertEquals(1, it.getInt(1)) }
                assertTrue(runCatching { statement.execute("UPDATE cluster_sightings_v5 SET count = 2") }.isFailure)
            }
        }
    }

    @Test
    fun `populated V6 upgrades to V7 with every word hash and notification fingerprint keyed`() {
        val schema = "keyed_upgrade"
        fun flyway() = Database.flyway(postgres.jdbcUrl, postgres.username, postgres.password, testKeying)
            .schemas(schema).defaultSchema(schema)
        flyway().target("6").load().migrate()
        val day = java.time.LocalDate.of(2026, 10, 1)
        val words = listOf("Ready now", "Elm Street", "Order ready")
        val public = words.associateWith { requireNotNull(CensusHash.of(it)) }
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to TextSlot(public.getValue("Ready now"), "words:2")))
        val screen = UiSkeletonDto(SkeletonSchema.SCHEMA_ID, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = "8.0", engineVersion = 1, day = day.toString(),
            windowTitle = TextSlot(public.getValue("Elm Street"), "words:2"), root = root)
        val notification = CensusSkeletonSchema.deserialize(notificationFixture(day, hash = public.getValue("Order ready")).toString())
        val install = "00000000-0000-4000-8000-000000000001"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("SET search_path TO $schema") }
            fun run(sql: String, vararg args: Any?) = connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
            run("INSERT INTO installs (install_id, key_hash, created_day, last_seen_day) VALUES (?::uuid, repeat('a',64), ?, ?)", install, day, day)
            for (item in listOf(screen, notification)) {
                run("INSERT INTO clusters (fingerprint, platform, kind, first_seen_day, last_seen_day, notes, screen_class) VALUES (?, 'doordash', ?, ?, ?, 'NOTE_SENTINEL', 'idle')",
                    item.fingerprint, item.kind.wire, day, day)
                run("INSERT INTO cluster_samples VALUES (?, '8.0', ?, ?::jsonb, 1, 1, ?)", item.fingerprint, day, CensusSkeletonSchema.measure(item).json, day.plusDays(90))
                run("INSERT INTO cluster_sightings_v5 VALUES (?, ?::uuid, ?, '8.0', 1, 1, 1)", item.fingerprint, install, day)
            }
            for (hash in public.values) run("INSERT INTO token_sightings_v5 VALUES (?, ?::uuid, ?, ?, 'words:2', 1, 1)", hash, install, day, day)
            run("INSERT INTO vocabulary_v5 VALUES (?, 'words:2', 10, ?, 'Ready now', ?, 'corpus', 'unblinded', 1, 1)", public.getValue("Ready now"), day, day)
            run("INSERT INTO trusted_envelopes (install_id, fingerprint, envelope, received_day, purge_after) VALUES (?::uuid, ?, '{}', ?, ?)", install, screen.fingerprint, day, day.plusDays(30))
            val publicDump = connection.dumpRows(schema)
            assertEquals(public.values.toSet() + notification.fingerprint, matches(publicDump, public.values + notification.fingerprint))

            assertEquals(1, flyway().load().migrate().migrationsExecuted)
            assertEquals(0, flyway().load().migrate().migrationsExecuted)

            fun strings(sql: String) = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            val keyedFingerprint = testKeying.fingerprint(notification.fingerprint)
            assertEquals(public.values.map { testKeying.token(it) }.toSet(), strings("SELECT token_hash FROM token_sightings_v5"))
            assertEquals(setOf(testKeying.token(public.getValue("Ready now"))), strings("SELECT token_hash FROM vocabulary_v5"))
            assertEquals(setOf("Ready now"), strings("SELECT clear_text FROM vocabulary_v5"))
            assertEquals(setOf(screen.fingerprint, keyedFingerprint), strings("SELECT fingerprint FROM clusters"))
            assertEquals(setOf("notification|NOTE_SENTINEL|idle"), strings("SELECT kind || '|' || notes || '|' || screen_class FROM clusters WHERE fingerprint = '$keyedFingerprint'"))
            assertEquals(setOf(screen.fingerprint, keyedFingerprint), strings("SELECT fingerprint FROM cluster_sightings_v5"))
            assertEquals(setOf(screen.fingerprint), strings("SELECT fingerprint FROM trusted_envelopes"))
            // The migrated rows are exactly what ingest stores today for the same items.
            for (item in listOf(screen, notification)) {
                val expected = testKeying.atRest(ItemVerdict.Accepted(item, "", 0, emptyList()))
                assertEquals(setOf(Json.parseToJsonElement(expected.canonicalJson)),
                    strings("SELECT skeleton::text FROM cluster_samples WHERE fingerprint = '${expected.item.fingerprint}'").map { Json.parseToJsonElement(it) }.toSet())
            }
            assertEquals(setOf(testKeying.keyCheck), strings("SELECT key_check FROM hash_keying"))
            // A thief's guess list (public hashes, the public notification fingerprint) matches nothing in the dump.
            val keyedDump = connection.dumpRows(schema)
            assertEquals(emptySet<String>(), matches(keyedDump, public.values + notification.fingerprint))
            assertEquals(setOf(screen.fingerprint), matches(keyedDump, listOf(screen.fingerprint)))

            KeyedHashStorageMigration.verify(connection, testKeying)
            assertThrows(HashPepperMismatchException::class.java) {
                KeyedHashStorageMigration.verify(connection, HashKeying.fromHex("0".repeat(63) + "1"))
            }
        }
    }

    @Test
    fun `startup migration refuses a pepper other than the one the database was keyed under`() {
        fun config(pepper: String) = Config.fromEnv(testEnvironment() + mapOf("DATABASE_URL" to postgres.jdbcUrl + "?currentSchema=pepper_check",
            "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password, HashKeying.ENV to pepper))
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS pepper_check") }
        }
        Database.migrate(config(TEST_HASH_PEPPER))
        Database.migrate(config(TEST_HASH_PEPPER))
        assertThrows(HashPepperMismatchException::class.java) { Database.migrate(config("ab".repeat(32))) }
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
    fun `empty database applies every migration and contains exactly eighteen application tables`() {
        val flyway = Database.flyway(postgres.jdbcUrl, postgres.username, postgres.password, testKeying).load()
        // V1 schema, V2 classification, V3 tombstones, V4 notifications, V5 lifecycle, V6 keep less (#79), V7 keyed (#85).
        assertEquals(7, flyway.migrate().migrationsExecuted)
        val expected = setOf(
            "installs", "clusters", "cluster_samples", "cluster_sightings", "token_sightings",
            "vocabulary", "trusted_envelopes", "health_daily", "health_fleet_daily", "ingest_ledger", "nonces", "withdrawals", "token_sightings_v5", "cluster_sightings_v5", "vocabulary_v5", "filter_floor", "ingest_pause",
            "hash_keying",
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
                    // #79: no enrolment instant; created_day is the enrolment day, erased_through orders replay.
                    assertEquals(null, columns["installs.enrolled_at"])
                    assertEquals(listOf("date", null, "NO", null), columns["installs.created_day"])
                    assertEquals(listOf("timestamp with time zone", null, "YES", null), columns["installs.erased_through"])
                    assertEquals(listOf("integer", null, "NO", "1"), columns["cluster_sightings_v5.count"])
                    assertEquals(listOf("character", "64", "NO", null), columns["hash_keying.key_check"])
                }
                // #85: a fresh database records the pepper's check value, so a later boot with another pepper refuses.
                statement.executeQuery("SELECT key_check FROM hash_keying").use { rows ->
                    assertTrue(rows.next()); assertEquals(testKeying.keyCheck, rows.getString(1)); assertTrue(!rows.next())
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
