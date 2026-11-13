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
import cloud.trotter.census.server.db.installRows
import cloud.trotter.census.server.db.select
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.atRest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            .schemas(schema).defaultSchema(schema).target("7")
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
            // The app names a single-notification batch after that notification's public fingerprint (CensusApi.batchId).
            val batchId = cloud.trotter.census.server.auth.sha256Hex(notification.fingerprint.toByteArray(Charsets.UTF_8)).take(32)
            run("INSERT INTO ingest_ledger (install_id, day, batch_ids) VALUES (?::uuid, ?, ARRAY[?, 'second'])", install, day, batchId)
            val guesses = public.values + notification.fingerprint + batchId
            val publicDump = connection.dumpRows(schema)
            assertEquals(guesses.toSet(), matches(publicDump, guesses))

            assertEquals(1, flyway().load().migrate().migrationsExecuted)
            assertEquals(0, flyway().load().migrate().migrationsExecuted)

            fun strings(sql: String) = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            val keyedFingerprint = testKeying.atRest(ItemVerdict.Accepted(notification, "", 0, emptyList())).item.fingerprint
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
            assertEquals(setOf(listOf(batchId, "second").map { testKeying.batch(it) }.joinToString(",")), strings("SELECT array_to_string(batch_ids, ',') FROM ingest_ledger"))
            // A thief's guess list (public hashes, the public notification fingerprint) matches nothing in the dump.
            val keyedDump = connection.dumpRows(schema)
            assertEquals(emptySet<String>(), matches(keyedDump, guesses))
            assertEquals(setOf(screen.fingerprint), matches(keyedDump, listOf(screen.fingerprint)))

            KeyedHashStorageMigration.verify(connection, testKeying)
            assertThrows(HashPepperMismatchException::class.java) {
                KeyedHashStorageMigration.verify(connection, HashKeying.fromHex("0".repeat(63) + "1"))
            }
        }
    }

    @Test
    fun `populated V7 upgrades to V8 with every content row under an epoch tag and no install id`() {
        val schema = "epoch_upgrade"
        val today = java.time.LocalDate.of(2026, 10, 6)
        val clock = object : Clock { override fun now(): java.time.Instant = today.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC) }
        fun flyway() = Database.flyway(postgres.jdbcUrl, postgres.username, postgres.password, testKeying, clock)
            .schemas(schema).defaultSchema(schema)
        flyway().target("7").load().migrate()
        val current = Epochs.of(today)
        val recent = today.minusDays(1)
        val previous = Epochs.firstDay(current).minusDays(2)
        val overdue = Epochs.firstDay(current - 1).minusDays(10)
        assertEquals(current - 1, Epochs.of(previous))
        val (plain, trusted, revoked, quarantined) = (1..4).map { java.util.UUID.fromString("00000000-0000-4000-8000-00000000008$it") }
        val tokens = listOf("1111111111111111", "2222222222222222", "3333333333333333")
        val fingerprint = "c".repeat(64)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("SET search_path TO $schema") }
            fun run(sql: String, vararg args: Any?) = connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
            for ((id, created) in listOf(plain to today.minusDays(40), trusted to today.minusDays(40), revoked to today.minusDays(40), quarantined to today.minusDays(5))) {
                run("INSERT INTO installs (install_id, key_hash, created_day, last_seen_day) VALUES (?, repeat('a',64), ?, ?)", id, created, recent)
            }
            run("UPDATE installs SET trusted = true WHERE install_id = ?", trusted)
            run("UPDATE installs SET revoked_at = now() WHERE install_id = ?", revoked)
            run("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (?, 'doordash', ?, ?)", fingerprint, previous, recent)
            fun token(id: java.util.UUID, token: String, last: java.time.LocalDate) =
                run("INSERT INTO token_sightings_v5 VALUES (?, ?, ?, ?, 'words:1', 1, 1)", token, id, last, last)
            token(plain, tokens[0], recent); token(plain, tokens[1], previous); token(plain, tokens[2], overdue)
            for (id in listOf(trusted, revoked, quarantined)) token(id, tokens[0], recent)
            for (day in listOf(recent, previous)) run("INSERT INTO cluster_sightings_v5 VALUES (?, ?, ?, '8.0', 1, 1, 1)", fingerprint, plain, day)
            for (id in listOf(trusted, revoked)) run("INSERT INTO cluster_sightings_v5 VALUES (?, ?, ?, '8.0', 1, 1, 1)", fingerprint, id, recent)
            for ((id, day) in listOf(plain to recent, plain to previous, revoked to recent)) {
                run("INSERT INTO health_daily (install_id, day, platform, platform_app_version, admitted, unknown, trips, rule_counts) VALUES (?, ?, 'doordash', '8.0', 1, 0, 0, '{}')", id, day)
            }

            assertEquals(1, flyway().load().migrate().migrationsExecuted)
            assertEquals(0, flyway().load().migrate().migrationsExecuted)

            fun strings(sql: String) = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            // Keys for the previous and current epoch only; the pre-V5 tables are gone; no content table names an install.
            assertEquals(listOf("${current - 1}", "$current"), strings("SELECT epoch::text FROM epoch_keys ORDER BY epoch"))
            assertEquals(emptyList<String>(), strings("""SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema'
                AND table_name IN ('token_sightings', 'cluster_sightings', 'vocabulary')"""))
            assertEquals(listOf("ingest_ledger", "installs", "nonces", "trusted_envelopes"), strings("""SELECT table_name FROM information_schema.columns
                WHERE table_schema = '$schema' AND column_name = 'install_id' ORDER BY table_name"""))
            val content = listOf("token_sightings_v5", "cluster_sightings_v5", "health_daily").flatMap { strings("SELECT row_to_json(t)::text FROM $it t") }
            assertEquals(emptySet<String>(), matches(content, listOf(plain, trusted, revoked, quarantined).map { it.toString() }))

            // Each install's rows are found again through its tags under the live keys, exactly as erasure finds them;
            // a revoked install's sightings are gone (k counting no longer joins installs), its health row is kept.
            fun rows(table: String, id: java.util.UUID) = connection.installRows(table, id, today)
            assertEquals(listOf(3L, 1L, 0L, 1L), listOf(plain, trusted, revoked, quarantined).map { rows("token_sightings_v5", it) })
            assertEquals(listOf(2L, 1L, 0L, 0L), listOf(plain, trusted, revoked, quarantined).map { rows("cluster_sightings_v5", it) })
            assertEquals(listOf(2L, 0L, 1L, 0L), listOf(plain, trusted, revoked, quarantined).map { rows("health_daily", it) })
            assertEquals(5L, connection.select("SELECT count(*) FROM token_sightings_v5") { it.getLong(1) })
            // Tagged under the epoch of the day each row describes; a row already due for retention, under the previous one.
            assertEquals(listOf("$current", "${current - 1}", "${current - 1}"), tokens.map { token ->
                strings("SELECT epoch::text FROM token_sightings_v5 WHERE token_hash = '$token' AND NOT trusted AND counts_from IS NULL ORDER BY epoch DESC").first()
            })
            // One install's sightings of one cluster carry unrelated tags in the two epochs.
            assertEquals(2, strings("SELECT DISTINCT install_tag FROM cluster_sightings_v5 WHERE NOT trusted").size)
            // Trust and the quarantine moved onto the rows.
            assertEquals(listOf("true", "true"), strings("SELECT trusted::text FROM token_sightings_v5 WHERE trusted UNION ALL SELECT trusted::text FROM cluster_sightings_v5 WHERE trusted"))
            assertEquals(listOf(today.minusDays(5).plusDays(8).toString()), strings("SELECT counts_from::text FROM token_sightings_v5 WHERE counts_from IS NOT NULL"))
            // The stored keys open only under this pepper.
            val wrapped = connection.select("SELECT wrapped FROM epoch_keys WHERE epoch = ?", current) { it.getBytes(1) }!!
            EpochKey.unwrap(testKeying, current, wrapped)
            assertThrows(EpochKeyUnwrapException::class.java) { EpochKey.unwrap(HashKeying.fromHex("0".repeat(63) + "1"), current, wrapped) }
            assertThrows(EpochKeyUnwrapException::class.java) { EpochKey.unwrap(testKeying, current - 1, wrapped) }
        }
    }

    @Test
    fun `V6 with legacy samples upgrades through V7 and the cutover keeps a valid one and removes an invalid one`() = kotlinx.coroutines.runBlocking {
        val day = java.time.LocalDate.of(2026, 10, 6)
        val config = Config.fromEnv(testEnvironment() + mapOf("DATABASE_URL" to schemaUrl("legacy_upgrade"),
            "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password))
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS legacy_upgrade") }
        }
        Database.flyway(config.databaseUrl, config.databaseUser, config.databasePassword, testKeying).target("6").load().migrate()
        val publicHash = requireNotNull(CensusHash.of("Order ready"))
        val valid = CensusSkeletonSchema.deserialize(notificationFixture(day, channel = "VALID", hash = publicHash).toString())
        val tampered = CensusSkeletonSchema.deserialize(notificationFixture(day, channel = "TAMPERED", hash = publicHash).toString())
        // An invalid legacy body: a slot hash that no longer matches the fingerprint it sits under.
        val tamperedBody = CensusSkeletonSchema.measure(tampered).json.replace(publicHash, "0123456789abcdef")
        val install = "00000000-0000-4000-8000-000000000002"
        DriverManager.getConnection(config.databaseUrl, config.databaseUser, config.databasePassword).use { connection ->
            fun run(sql: String, vararg args: Any?) = connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
            run("INSERT INTO installs (install_id, key_hash, created_day, last_seen_day) VALUES (?::uuid, repeat('a',64), ?, ?)", install, day, day)
            for ((item, body) in listOf(valid to CensusSkeletonSchema.measure(valid).json, tampered to tamperedBody)) {
                run("INSERT INTO clusters (fingerprint, platform, kind, first_seen_day, last_seen_day) VALUES (?, 'doordash', 'notification', ?, ?)", item.fingerprint, day, day)
                // Null provenance: a pre-V5 sample the V5 cutover has not judged yet.
                run("INSERT INTO cluster_samples (fingerprint, platform_app_version, received_day, skeleton) VALUES (?, '8.0', ?, ?::jsonb)", item.fingerprint, day, body)
            }
        }
        val clock = object : Clock { override fun now(): java.time.Instant = day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC) }
        Database.migrate(config, clock)
        Database.connect(config).use { db -> cloud.trotter.census.server.db.FilterPolicyStore(db, clock).bootstrap(1) }
        val keyed = testKeying.atRest(ItemVerdict.Accepted(valid, "", 0, emptyList())).item.fingerprint
        DriverManager.getConnection(config.databaseUrl, config.databaseUser, config.databasePassword).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT fingerprint, hash_domain, filter_rev, skeleton::text FROM cluster_samples").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(keyed, rows.getString(1)); assertEquals(1, rows.getInt(2)); assertEquals(1, rows.getInt(3))
                    // The cutover scrubbed it: legacy hashes become expired slots; its fingerprint field is the keyed one.
                    val body = Json.parseToJsonElement(rows.getString(4)).jsonObject
                    assertEquals(keyed, body.getValue("fingerprint").jsonPrimitive.content)
                    assertFalse(rows.getString(4).contains("\"h\""))
                    assertTrue(!rows.next(), "the tampered legacy sample is removed")
                }
            }
            assertEquals(emptySet<String>(), matches(connection.dumpRows("legacy_upgrade"), listOf(publicHash, valid.fingerprint, tampered.fingerprint)))
        }
    }

    @Test
    fun `a resolved sampleless notification keeps its identity across V7 and a repeat upload joins it`() = kotlinx.coroutines.runBlocking {
        val day = java.time.LocalDate.of(2026, 10, 6)
        val clock = object : Clock { override fun now(): java.time.Instant = day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC) }
        val config = Config.fromEnv(testEnvironment() + mapOf("DATABASE_URL" to schemaUrl("resolved_identity"),
            "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password))
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS resolved_identity") }
        }
        Database.flyway(config.databaseUrl, config.databaseUser, config.databasePassword, testKeying).target("6").load().migrate()
        val raw = notificationFixture(day, channel = "RESOLVED", version = "9.0")
        val public = raw.getValue("fingerprint").jsonPrimitive.content
        val install = java.util.UUID.fromString("00000000-0000-4000-8000-000000000003")
        DriverManager.getConnection(config.databaseUrl, config.databaseUser, config.databasePassword).use { connection ->
            fun run(sql: String, vararg args: Any?) = connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
            run("INSERT INTO installs (install_id, key_hash, created_day, last_seen_day) VALUES (?, repeat('a',64), ?, ?)", install, day.minusDays(20), day)
            // Resolved 40 days ago: its samples expired at resolved_day + 30, but sightings are recent.
            run("""INSERT INTO clusters (fingerprint, platform, kind, first_seen_day, last_seen_day, status, resolved_rule_id, resolved_day, screen_class)
                VALUES (?, 'doordash', 'notification', ?, ?, 'resolved', 'doordash.notification.offer', ?, 'idle')""", public, day.minusDays(60), day.minusDays(2), day.minusDays(40))
            run("INSERT INTO cluster_sightings_v5 VALUES (?, ?, ?, '8.0', 1, 1, 1)", public, install, day.minusDays(2))
        }
        Database.migrate(config, clock)
        Database.connect(config).use { db ->
            val accepted = cloud.trotter.census.server.ingest.SkeletonValidator.validate(raw, notificationPolicy, day) as ItemVerdict.Accepted
            val outcome = cloud.trotter.census.server.db.SkeletonStore(db, clock).ingest(install, "a".repeat(64), day, listOf(accepted), 0,
                emptyMap(), "repeat", 1, cloud.trotter.census.server.ingest.BudgetPolicy())
            assertTrue(outcome is cloud.trotter.census.server.db.IngestOutcome.Stored)
            assertEquals(0, (outcome as cloud.trotter.census.server.db.IngestOutcome.Stored).summary.newClusters)
        }
        val keyed = testKeying.fingerprint(public)
        DriverManager.getConnection(config.databaseUrl, config.databaseUser, config.databasePassword).use { connection ->
            fun strings(sql: String) = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            assertEquals(listOf("$keyed|resolved|doordash.notification.offer|${day.minusDays(40)}|idle|${day.minusDays(60)}|$day"),
                strings("SELECT fingerprint || '|' || status || '|' || resolved_rule_id || '|' || resolved_day || '|' || screen_class || '|' || first_seen_day || '|' || last_seen_day FROM clusters"))
            // Both versions' presence rows sit on the one cluster; the resolution deadline still refuses a fresh sample.
            assertEquals(listOf("$keyed|8.0|${day.minusDays(2)}", "$keyed|9.0|$day"),
                strings("SELECT fingerprint || '|' || platform_app_version || '|' || day FROM cluster_sightings_v5 ORDER BY day"))
            assertEquals(listOf("0"), strings("SELECT count(*) FROM cluster_samples"))
            assertEquals(emptySet<String>(), matches(connection.dumpRows("resolved_identity"), listOf(public)))
        }
    }

    @Test
    fun `startup migration refuses a pepper other than the one the database was keyed under`() {
        fun config(pepper: String) = Config.fromEnv(testEnvironment() + mapOf("DATABASE_URL" to schemaUrl("pepper_check"),
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
    fun `empty database applies every migration and contains exactly sixteen application tables`() {
        val flyway = Database.flyway(postgres.jdbcUrl, postgres.username, postgres.password, testKeying).load()
        // V1 schema, V2 classification, V3 tombstones, V4 notifications, V5 lifecycle, V6 keep less (#79), V7 keyed (#85),
        // V8 epoch pseudonyms (#86, which drops the three pre-V5 tables).
        assertEquals(8, flyway.migrate().migrationsExecuted)
        val expected = setOf(
            "installs", "clusters", "cluster_samples", "trusted_envelopes", "health_daily", "health_fleet_daily", "ingest_ledger",
            "nonces", "withdrawals", "token_sightings_v5", "cluster_sightings_v5", "vocabulary_v5", "filter_floor", "ingest_pause",
            "hash_keying", "epoch_keys",
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
                    assertEquals(listOf("character", "16", "NO", null), columns["token_sightings_v5.token_hash"])
                    assertEquals(listOf("timestamp with time zone", null, "NO", null), columns["nonces.issued_at"])
                    assertEquals(listOf("text", null, "NO", null), columns["withdrawals.install_id_hash"])
                    assertEquals(listOf("timestamp with time zone", null, "NO", null), columns["withdrawals.withdrawn_at"])
                    // #79: no enrolment instant; created_day is the enrolment day, erased_through orders replay.
                    assertEquals(null, columns["installs.enrolled_at"])
                    assertEquals(listOf("date", null, "NO", null), columns["installs.created_day"])
                    assertEquals(listOf("timestamp with time zone", null, "YES", null), columns["installs.erased_through"])
                    assertEquals(listOf("integer", null, "NO", "1"), columns["cluster_sightings_v5.count"])
                    assertEquals(listOf("character", "64", "NO", null), columns["hash_keying.key_check"])
                    // #86: content rows carry an epoch and an epoch tag; no content table carries an install id.
                    for (table in listOf("token_sightings_v5", "cluster_sightings_v5", "health_daily")) {
                        assertEquals(listOf("integer", null, "NO", null), columns["$table.epoch"], table)
                        assertEquals(listOf("character", "64", "NO", null), columns["$table.install_tag"], table)
                        assertEquals(null, columns["$table.install_id"], table)
                    }
                    assertEquals(listOf("bytea", null, "NO", null), columns["epoch_keys.wrapped"])
                    assertEquals(setOf("installs", "nonces", "ingest_ledger", "trusted_envelopes"),
                        columns.keys.filter { it.endsWith(".install_id") }.map { it.substringBefore('.') }.toSet())
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

    /** The container URL already carries a query string; a schema is one more parameter. */
    private fun schemaUrl(schema: String): String =
        postgres.jdbcUrl + (if ('?' in postgres.jdbcUrl) "&" else "?") + "currentSchema=$schema"

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
