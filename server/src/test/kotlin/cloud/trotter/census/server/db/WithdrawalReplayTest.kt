package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Config
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.secret
import cloud.trotter.census.server.testEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: withdrawal replay tests skipped")
class WithdrawalReplayTest {
    @TempDir
    lateinit var temporary: Path

    private val t0 = Instant.parse("2026-10-03T12:00:00Z")
    private var now = t0
    private val clock = object : Clock { override fun now(): Instant = now }
    private val keyHash = hashSecret(secret(97))
    private val day = LocalDate.parse("2026-10-03")

    @BeforeEach
    fun clear() {
        sql { it.update("TRUNCATE installs, clusters, withdrawals CASCADE") }
    }

    @Test
    fun `withdraw writes a tombstone whose hash equals installIdHash and Postgres sha256 of the uuid text`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val id = UUID.randomUUID()
            assertEquals(EnrolOutcome.Created, store.enrol(id, keyHash, "1.0"))
            seedChildren(id)
            now = t0.plusSeconds(1)
            assertEquals(MutationOutcome.StaleCredential, store.withdraw(id, "0".repeat(64)))
            assertTrue(journal().isEmpty())
            val result = store.withdraw(id, keyHash) as MutationOutcome.Applied
            assertEquals(now, result.withdrawnAt)
            assertEquals(InstallStore.WITHDRAWAL_TABLES.associateWith { 1 }, result.deletedRows)
            val hash = InstallStore.installIdHash(id)
            assertEquals(mapOf(hash to now), journal())
            val postgresHash = sql { connection ->
                connection.select("SELECT encode(sha256(?::text::bytea), 'hex')", id.toString()) { it.getString(1) }
            }
            assertEquals(hash, postgresHash)
            assertErased(id)
            assertDraftCleared()
        }
    }

    @Test
    fun `a deletion failure rolls back both the tombstone and all deleted children`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val id = UUID.randomUUID()
            store.enrol(id, keyHash, "1.0")
            seedChildren(id)
            sql { connection ->
                connection.update("""CREATE FUNCTION refuse_install_delete() RETURNS trigger LANGUAGE plpgsql AS
                    'BEGIN RAISE EXCEPTION ''deletion failed''; END'""")
                connection.update("CREATE TRIGGER refuse_delete BEFORE DELETE ON installs FOR EACH ROW EXECUTE FUNCTION refuse_install_delete()")
            }
            try {
                now = t0.plusSeconds(1)
                assertNotNull(runCatching { store.withdraw(id, keyHash) }.exceptionOrNull())
                assertTrue(journal().isEmpty())
                sql { connection ->
                    for (table in InstallStore.WITHDRAWAL_TABLES) {
                        assertEquals(1, connection.select("SELECT count(*) FROM $table WHERE install_id = ?", id) { it.getInt(1) }, table)
                    }
                }
                store.mergeWithdrawalJournal(listOf(InstallStore.installIdHash(id) to now))
                assertNotNull(runCatching { store.reapplyWithdrawals() }.exceptionOrNull())
                val (status, output) = runServer()
                assertEquals(1, status)
                assertTrue(output.contains("withdrawal replay failed ("))
                assertFalse(output.contains(id.toString()))
                assertFalse(output.contains("Responding at"), "startup must not open the listener after replay failure")
                assertNotNull(store.lookup(id))
                assertEquals(1, sql { it.select("SELECT count(*) FROM trusted_envelopes") { rows -> rows.getInt(1) } })
            } finally {
                sql { connection ->
                    connection.update("DROP TRIGGER refuse_delete ON installs")
                    connection.update("DROP FUNCTION refuse_install_delete()")
                }
            }
        }
    }

    @Test
    fun `a restore that resurrects a withdrawn install is undone by reapplyWithdrawals`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val id = UUID.randomUUID()
            assertEquals(EnrolOutcome.Created, store.enrol(id, keyHash, "1.0"))
            seedChildren(id)
            now = t0.plusSeconds(1)
            store.withdraw(id, keyHash)
            sql { connection ->
                connection.update(
                    """INSERT INTO installs (install_id, key_hash, created_day, last_seen_day, enrolled_at)
                        VALUES (?, ?, ?, ?, ?)""", id, keyHash, day, day, t0.atOffset(ZoneOffset.UTC),
                )
            }
            seedChildren(id)
            val report = store.reapplyWithdrawals()
            assertEquals(1, report.matched)
            assertEquals(0, report.keptReenrolled)
            assertEquals(InstallStore.WITHDRAWAL_TABLES.associateWith { 1 }, report.deletedRows)
            assertErased(id)
            assertDraftCleared()
            val again = store.reapplyWithdrawals()
            assertEquals(0, again.matched)
            assertTrue(again.deletedRows.values.all { it == 0 })
            assertEquals(mapOf(InstallStore.installIdHash(id) to now), journal())
        }
    }

    @Test
    fun `one-shot CLI merges replays and exits with private diagnostics`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val id = UUID.randomUUID()
            store.enrol(id, keyHash, "1.0")
            val hash = InstallStore.installIdHash(id)
            val path = temporary.resolve("journal.csv")
            Files.writeString(path, "install_id_hash,withdrawn_at\n$hash,2026-10-03 12:00:01+00\n")
            val (status, output) = runServer("--reapply-withdrawals", path.toString())
            assertEquals(0, status)
            assertTrue(output.lineSequence().any { it == "withdrawal replay merged=1 matched=1 rows=1 kept_reenrolled=0" })
            assertFalse(output.contains(id.toString()))
            assertFalse(output.contains(hash))
            assertNull(store.lookup(id))
            assertEquals(mapOf(hash to t0.plusSeconds(1)), journal())
            val again = runServer("--reapply-withdrawals", path.toString())
            assertEquals(0, again.first)
            assertTrue(again.second.contains("withdrawal replay merged=1 matched=0 rows=0 kept_reenrolled=0"))
            Files.writeString(path, "PRIVATE_INVALID_HASH,2026-10-03T12:00:01Z\n")
            val invalid = runServer("--reapply-withdrawals", path.toString())
            assertEquals(1, invalid.first)
            assertTrue(invalid.second.contains("withdrawal replay failed (IllegalArgumentException)"))
            assertFalse(invalid.second.contains("PRIVATE_INVALID_HASH"))
            assertEquals(2, runServer("--unknown").first)
            assertEquals(2, runServer("--reapply-withdrawals").first)
        }
    }

    @Test
    fun `a re-enrolment AFTER the tombstone survives replay`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val id = UUID.randomUUID()
            store.enrol(id, keyHash, "1.0")
            now = t0.plusSeconds(1)
            store.withdraw(id, keyHash)
            now = t0.plusSeconds(2)
            assertEquals(EnrolOutcome.Created, store.enrol(id, keyHash, "1.0"))
            val replay = store.reapplyWithdrawals()
            assertEquals(0, replay.matched)
            assertEquals(1, replay.keptReenrolled)
            assertNotNull(store.lookup(id))
            // Equal instants belong to the withdrawn generation, not a later re-enrolment.
            store.mergeWithdrawalJournal(listOf(InstallStore.installIdHash(id) to now))
            assertEquals(1, store.reapplyWithdrawals().matched)
            assertNull(store.lookup(id))
        }
    }

    @Test
    fun `mergeWithdrawalJournal is idempotent keeps the latest instant and rejects malformed hashes`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            val rows = listOf(a to t0, b to t0.plusSeconds(1))
            assertEquals(2, store.mergeWithdrawalJournal(rows))
            store.mergeWithdrawalJournal(rows)
            assertEquals(rows.toMap(), journal())
            store.mergeWithdrawalJournal(listOf(a to t0.minusSeconds(1)))
            assertEquals(rows.toMap(), journal())
            store.mergeWithdrawalJournal(listOf(a to t0.plusSeconds(2)))
            val expected = mapOf(a to t0.plusSeconds(2), b to t0.plusSeconds(1))
            assertEquals(expected, journal())
            for (bad in listOf("a".repeat(63), "A".repeat(64), "g".repeat(64))) {
                val failure = runCatching {
                    store.mergeWithdrawalJournal(listOf("c".repeat(64) to t0, bad to t0))
                }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals(expected, journal(), "a malformed row must reject the entire batch")
            }
        }
    }

    @Test
    fun `purgeWithdrawals is bounded and removes tombstones at the half open horizon`() = runBlocking {
        sql { connection ->
            connection.update(
                """INSERT INTO withdrawals SELECT lpad(to_hex(n), 64, '0'), ?::timestamptz
                    FROM generate_series(1, 50001) n""", t0.minusSeconds(1).atOffset(ZoneOffset.UTC),
            )
            // A statement trigger proves the ctid batches never exceed 1000 rows.
            connection.update("""CREATE FUNCTION check_withdrawal_batch() RETURNS trigger LANGUAGE plpgsql AS
                'BEGIN IF (SELECT count(*) FROM erased) > 1000 THEN RAISE EXCEPTION ''unbounded delete''; END IF; RETURN NULL; END'""")
            connection.update("""CREATE TRIGGER withdrawal_batch AFTER DELETE ON withdrawals
                REFERENCING OLD TABLE AS erased FOR EACH STATEMENT EXECUTE FUNCTION check_withdrawal_batch()""")
        }
        try {
            Database.connect(config()).use { db ->
                val store = InstallStore(db, clock)
                store.mergeWithdrawalJournal(listOf("a".repeat(64) to t0, "b".repeat(64) to t0.plusSeconds(1)))
                assertEquals(50000, store.purgeWithdrawals(t0))
                assertEquals(3, journal().size)
                assertEquals(2, store.purgeWithdrawals(t0))
                assertEquals(mapOf("b".repeat(64) to t0.plusSeconds(1)), journal())
            }
        } finally {
            sql { connection ->
                connection.update("DROP TRIGGER withdrawal_batch ON withdrawals")
                connection.update("DROP FUNCTION check_withdrawal_batch()")
            }
        }
    }

    private fun seedChildren(id: UUID) = sql { connection ->
        val fingerprint = "1".repeat(64)
        connection.update("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (?, 'android', ?, ?) ON CONFLICT DO NOTHING", fingerprint, day, day)
        val envelope = connection.select(
            "INSERT INTO trusted_envelopes (install_id, envelope, received_day, purge_after) VALUES (?, '{}', ?, ?) RETURNING id",
            id, day, day.plusDays(1),
        ) { it.getLong(1) }!!
        connection.update("UPDATE clusters SET draft = jsonb_build_object('envelopeId', ?::text), draft_day = ?, screen_class = 'offer' WHERE fingerprint = ?", envelope.toString(), day, fingerprint)
        connection.update("INSERT INTO health_daily (install_id, day, platform, platform_app_version, admitted, unknown, trips, rule_counts) VALUES (?, ?, 'android', '1.0', 1, 0, 0, '{}')", id, day)
        connection.update("INSERT INTO token_sightings_v5 VALUES ('1234567890abcdef', ?, ?, ?, 'test', 1, 1)", id, day, day)
        connection.update("INSERT INTO cluster_sightings_v5 VALUES (?, ?, ?, '1.0', 1, 1, 1)", fingerprint, id, day)
        connection.update("INSERT INTO ingest_ledger (install_id, day) VALUES (?, ?)", id, day)
        connection.update("INSERT INTO nonces (nonce, install_id, issued_at) VALUES (?, ?, ?)", UUID.randomUUID().toString().replace("-", ""), id, t0.atOffset(ZoneOffset.UTC))
    }

    private fun assertErased(id: UUID) = sql { connection ->
        for (table in InstallStore.WITHDRAWAL_TABLES) {
            assertEquals(0, connection.select("SELECT count(*) FROM $table WHERE install_id = ?", id) { it.getInt(1) }, table)
        }
    }

    private fun assertDraftCleared() = sql { connection ->
        connection.select("SELECT draft, draft_day, screen_class FROM clusters") {
            assertNull(it.getString("draft"))
            assertNull(it.getObject("draft_day"))
            assertEquals("offer", it.getString("screen_class"))
        }
    }

    private fun journal(): Map<String, Instant> = sql { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT install_id_hash, withdrawn_at FROM withdrawals").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getObject(2, OffsetDateTime::class.java).toInstant()) }
            }
        }
    }

    private fun <T> sql(block: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

    private fun runServer(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
            requireNotNull(System.getProperty("census.testClasspath")), "cloud.trotter.census.server.MainKt",
        ) + args).apply {
            redirectErrorStream(true)
            environment().putAll(testEnvironment() + mapOf(
                "DATABASE_URL" to postgres.jdbcUrl, "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password,
            ))
        }.start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val output = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "CLI or failed startup must exit without serving")
            return process.exitValue() to output.get(5, TimeUnit.SECONDS)
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            reader.shutdownNow()
        }
    }

    companion object {
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        private fun config(): Config = Config.fromEnv(testEnvironment() + mapOf(
            "DATABASE_URL" to postgres.jdbcUrl, "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password,
        ))
        @BeforeAll
        @JvmStatic
        fun migrate() { Database.migrate(config()) }
        @JvmStatic
        fun dockerAvailable(): Boolean = System.getenv("CI") == "true" || runCatching {
            DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false)
    }
}
