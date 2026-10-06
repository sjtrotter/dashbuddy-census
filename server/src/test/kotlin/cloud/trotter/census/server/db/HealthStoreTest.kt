package cloud.trotter.census.server.db

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Config
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.assertError
import cloud.trotter.census.server.assertPrivateLogs
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.enrol
import cloud.trotter.census.server.healthFixture
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.jobs.Alarm
import cloud.trotter.census.server.jobs.AlarmSink
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.jobs.PurgeJob
import cloud.trotter.census.server.module
import cloud.trotter.census.server.secret
import cloud.trotter.census.server.signed
import cloud.trotter.census.server.testEnvironment
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: health store tests skipped")
class HealthStoreTest {
    @Test
    fun `purge bounds every delete isolates every sweep and propagates cancellation`() = runBlocking {
        var instant = Instant.parse("2000-01-01T12:00:00Z")
        val day = LocalDate.of(2000, 1, 1)
        val clock = object : Clock { override fun now(): Instant = instant }
        val id = UUID.randomUUID()
        val hash = hashSecret(secret(95))
        val tables = mapOf("nonces" to "nonces", "ledger" to "ingest_ledger", "envelopes" to "trusted_envelopes", "health" to "health_daily")
        val logger = LoggerFactory.getLogger("Purge") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        fun seed(nonces: Int, rows: Int) = sql { connection ->
            connection.update(
                "INSERT INTO nonces (nonce, install_id, issued_at) SELECT md5(?::text || n::text), ?, ?::timestamptz FROM generate_series(1, ?) n",
                id, id, instant.minusSeconds(3601).atOffset(ZoneOffset.UTC), nonces,
            )
            connection.update(
                "INSERT INTO ingest_ledger (install_id, day) SELECT ?, ?::date - n FROM generate_series(1, ?) n", id, day.minusDays(7), rows,
            )
            connection.update(
                """INSERT INTO trusted_envelopes (install_id, envelope, received_day, purge_after)
                    SELECT ?, '{}'::jsonb, ?::date, ?::date FROM generate_series(1, ?)""", id, day.minusDays(31), day.minusDays(1), rows,
            )
            connection.update(
                """INSERT INTO health_daily (install_id, day, platform, platform_app_version, admitted, unknown, trips, rule_counts)
                    SELECT ?, ?::date - n, 'doordash', '1.0.0', 0, 0, 0, '{}'::jsonb FROM generate_series(1, ?) n""", id, day.minusDays(180), rows,
            )
        }
        try {
            Database.connect(config()).use { db ->
                val store = InstallStore(db, clock)
                assertEquals(EnrolOutcome.Created, store.enrol(id, hash, "1.0.0"))
                try {
                    seed(50_001, 1001)
                    val job = PurgeJob(store, clock)
                    val report = job.runOnce()
                    assertEquals(50_000, report.nonces)
                    assertEquals(1001, report.ledgerRows)
                    assertEquals(1001, report.trustedEnvelopes)
                    assertEquals(1001, report.healthRows)
                    assertEquals(1, count("nonces", id))
                    assertTrue(logs.list.any { it.formattedMessage == "purge sweep=nonces deleted=50000 batches=50 capped=true" })
                    for (name in listOf("ledger", "envelopes", "health")) {
                        assertTrue(logs.list.any { it.formattedMessage == "purge sweep=$name deleted=1001 batches=2 capped=false" })
                    }
                    assertEquals(1, job.runOnce().nonces)
                    sql { it.update("CREATE FUNCTION census_test_purge_failure() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''PRIVATE_SWEEP_FAILURE''; END;'") }
                    try {
                        for ((name, table) in tables) {
                            seed(1, 1)
                            sql { it.update("CREATE TRIGGER census_test_purge_failure BEFORE DELETE ON $table FOR EACH STATEMENT EXECUTE FUNCTION census_test_purge_failure()") }
                            logs.list.clear()
                            try {
                                job.runOnce()
                                for (candidate in tables.values) assertEquals(if (candidate == table) 1 else 0, count(candidate, id))
                                val warning = logs.list.single { it.level == Level.WARN }
                                assertTrue(warning.formattedMessage.matches(Regex("purge sweep=$name failed class=[A-Za-z0-9_]+")))
                                assertNull(warning.throwableProxy)
                                assertPrivateLogs(logs.list, listOf("PRIVATE_SWEEP_FAILURE"))
                            } finally {
                                sql { connection ->
                                    connection.update("DROP TRIGGER census_test_purge_failure ON $table")
                                    connection.update("DELETE FROM $table WHERE install_id = ?", id)
                                }
                            }
                        }
                    } finally {
                        sql { it.update("DROP FUNCTION census_test_purge_failure()") }
                    }
                    sql { it.update("UPDATE installs SET trusted = true WHERE install_id = ?", id) }
                    val alarms = HealthAlarms(HealthStore(db, clock), clock, startedAt = clock.now(), sink = AlarmSink { throw IllegalStateException("PRIVATE_SILENCE_FAILURE") })
                    instant = instant.plusSeconds(48 * 3600)
                    seed(1, 1)
                    logs.list.clear()
                    val afterSilence = PurgeJob(store, clock, alarms = alarms).runOnce()
                    assertEquals(listOf(1, 1, 1, 1), listOf(afterSilence.nonces, afterSilence.ledgerRows, afterSilence.trustedEnvelopes, afterSilence.healthRows))
                    assertEquals("purge sweep=silence failed class=IllegalStateException", logs.list.single { it.level == Level.WARN }.formattedMessage)
                    assertPrivateLogs(logs.list, listOf("PRIVATE_SILENCE_FAILURE"))

                    val cancelling = HealthAlarms(HealthStore(db, clock), clock, startedAt = clock.now(), sink = AlarmSink { throw CancellationException("PRIVATE_CANCELLATION") })
                    instant = instant.plusSeconds(48 * 3600)
                    seed(1, 1)
                    logs.list.clear()
                    assertTrue(runCatching { PurgeJob(store, clock, alarms = cancelling).runOnce() }.exceptionOrNull() is CancellationException)
                    assertTrue(logs.list.none { it.level == Level.WARN })
                    for (table in tables.values) assertEquals(1, count(table, id))
                } finally {
                    store.withdraw(id, hash)
                }
            }
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `rule death sums earlier current versions and fleet dedupe includes platform`() = runBlocking {
        val day = LocalDate.of(2026, 10, 2)
        val clock = object : Clock { override fun now(): Instant = Instant.parse("2026-10-02T12:00:00Z") }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val hash = hashSecret(secret(93))
        val initial = HealthReport(day, "doordash", "1.0.0", "1.0.0", "v1", 200, 0, 0, mapOf("a.b" to 10))
        val delivered = mutableListOf<Alarm>()
        var failNextRule = true
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            val store = HealthStore(db, clock)
            val evaluator = HealthAlarms(store, clock, startedAt = clock.now(), sink = AlarmSink {
                if (it.kind == "silent_rule_death" && failNextRule) {
                    failNextRule = false
                    throw IllegalStateException("PRIVATE_DELIVERY_FAILURE")
                }
                delivered += it
            })
            suspend fun write(id: UUID, report: HealthReport) {
                assertEquals(HealthOutcome.Stored, store.upsert(id, hash, day, listOf(report), emptyMap(), 100, BudgetPolicy()))
            }
            for (id in listOf(first, second)) {
                assertEquals(EnrolOutcome.Created, installs.enrol(id, hash, "1.0.0"))
                for (ago in 1L..3L) write(id, initial.copy(day = day.minusDays(ago)))
            }
            try {
                write(first, initial)
                val newVersion = initial.copy(platformAppVersion = "2.0.0", ruleCounts = emptyMap())
                write(first, newVersion)
                evaluator.evaluate(first, listOf(newVersion), hash)
                assertTrue(delivered.isEmpty())
                val stored = requireNotNull(store.alarmInputs(first, hash, listOf(newVersion), day)).current
                assertEquals(setOf("1.0.0", "2.0.0"), stored.map { it.platformAppVersion }.toSet())

                write(second, initial.copy(admitted = 100, ruleCounts = emptyMap()))
                val half = newVersion.copy(admitted = 100)
                write(second, half)
                assertTrue(runCatching { evaluator.evaluate(second, listOf(half), hash) }.exceptionOrNull() is IllegalStateException)
                evaluator.evaluate(second, listOf(half), hash)
                evaluator.evaluate(second, listOf(half), hash)
                assertEquals(listOf(Alarm("silent_rule_death", "doordash", "2.0.0", second.toString().take(8), listOf("a.b"))), delivered)

                for (platform in listOf("doordash", "uber")) {
                    val report = initial.copy(platform = platform, unknown = 400)
                    write(first, report)
                    write(second, report)
                    evaluator.evaluate(first, listOf(report), hash)
                }
                assertEquals(setOf("doordash", "uber"), delivered.filter { it.kind == "fleet_unknown" }.map { it.platform }.toSet())
                assertEquals(2, delivered.count { it.kind == "fleet_unknown" && it.version == "1.0.0" })
            } finally {
                for (id in listOf(first, second)) installs.withdraw(id, hash)
            }
        }
    }

    @Test
    fun `alarm delivery failure preserves accepted response and retries but cancellation propagates`() {
        val day = LocalDate.of(2026, 10, 3)
        val clock = object : Clock { override fun now(): Instant = Instant.parse("2026-10-03T12:00:00Z") }
        val id = UUID.randomUUID()
        val key = secret(94)
        val hash = hashSecret(key)
        val logger = LoggerFactory.getLogger("Alarm") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            Database.connect(config()).use { db ->
                val store = HealthStore(db, clock)
                var attempts = 0
                val evaluator = HealthAlarms(store, clock, startedAt = clock.now(), sink = AlarmSink {
                    attempts++
                    if (attempts == 1) throw IllegalStateException("PRIVATE_DELIVERY_FAILURE")
                })
                testApplication {
                    application { module(config(), db, clock, evaluator) }
                    assertEquals(HttpStatusCode.OK, client.enrol(id.toString(), key).status)
                    val report = JsonObject(healthFixture() + mapOf("day" to JsonPrimitive(day.toString()), "trips" to JsonPrimitive(1)))
                    repeat(3) {
                        val response = client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/health", body(listOf(report)))
                        assertEquals(HttpStatusCode.OK, response.status)
                        assertEquals(Json.parseToJsonElement("""{"status":"accepted","accepted":1,"rejected":{}}"""), Json.parseToJsonElement(response.bodyAsText()))
                    }
                    assertEquals(2, attempts)
                    assertEquals(mapOf("trips" to 1L), evaluator.stats.snapshot())
                    assertEquals(1, count("health_daily", id))
                    val cancelling = HealthAlarms(store, clock, startedAt = clock.now(), sink = AlarmSink { throw CancellationException("PRIVATE_CANCELLATION") })
                    val accepted = HealthReport(day, "doordash", "8.0.0", "1.0.0", "v1", 812, 12, 1, emptyMap())
                    assertTrue(runCatching { cancelling.evaluate(id, listOf(accepted), hash) }.exceptionOrNull() is CancellationException)
                    InstallStore(db, clock).withdraw(id, hash)
                }
            }
            val warning = logs.list.single { it.level == Level.WARN }
            assertEquals("alarm_evaluation_failed class=IllegalStateException", warning.formattedMessage)
            assertNull(warning.throwableProxy)
            assertPrivateLogs(logs.list, listOf("PRIVATE_DELIVERY_FAILURE", "PRIVATE_CANCELLATION"))
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `idempotent upsert greatest counters concurrent fleet sums routes and alarm evaluation`() {
        val day = LocalDate.of(2026, 9, 18)
        var instant = Instant.parse("2026-09-18T12:00:00Z")
        val clock = object : Clock { override fun now(): Instant = instant }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val key = secret(91)
        val hash = hashSecret(key)
        val initial = HealthReport(day, "doordash", "8.0.0", "1.0.0", "v1", 100, 40, 1, mapOf("a.b" to 30))
        val alarms = mutableListOf<Alarm>()
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            val store = HealthStore(db, clock)
            val evaluator = HealthAlarms(store, clock, startedAt = clock.now(), sink = AlarmSink { alarms += it })
            testApplication {
                application { module(config(), db, clock, evaluator) }
                assertEquals(HttpStatusCode.OK, client.enrol(first.toString(), key).status)
                assertEquals(HttpStatusCode.OK, client.enrol(second.toString(), key).status)
                suspend fun write(id: UUID, report: HealthReport): HealthOutcome =
                    store.upsert(id, hash, day, listOf(report), emptyMap(), 100, BudgetPolicy())
                assertEquals(HealthOutcome.Stored, write(first, initial))
                val firstFleet = fleet(day)
                assertEquals(HealthOutcome.Stored, write(first, initial))
                assertEquals(firstFleet, fleet(day))
                assertEquals(listOf(1L, 100L, 40L, 30L), firstFleet)
                assertEquals(1, count("health_daily", first))
                assertEquals(HealthOutcome.Stored, write(first, initial.copy(admitted = 80, unknown = 20, trips = 0, rulesetVersion = "v2", ruleCounts = mapOf("a.b" to 10))))
                assertEquals(listOf(1L, 100L, 40L, 10L), fleet(day))
                sql { connection ->
                    connection.prepareStatement("SELECT admitted, unknown, trips, ruleset_version, rule_counts FROM health_daily WHERE install_id = ?").use { statement ->
                        statement.setObject(1, first)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(100, rows.getInt("admitted"))
                            assertEquals(40, rows.getInt("unknown"))
                            assertEquals(1, rows.getInt("trips"))
                            assertEquals("v2", rows.getString("ruleset_version"))
                            assertEquals(mapOf("a.b" to 10), Json.decodeFromString<Map<String, Int>>(rows.getString("rule_counts")))
                        }
                    }
                }
                val fuller = initial.copy(admitted = 150, unknown = 60, ruleCounts = mapOf("a.b" to 40))
                val other = initial.copy(admitted = 200, unknown = 100, trips = 0, ruleCounts = mapOf("a.b" to 50))
                val outcomes = coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val work = listOf(first to fuller, second to other).map { (id, report) -> async {
                        start.await()
                        write(id, report)
                    } }
                    start.complete(Unit)
                    work.awaitAll()
                }
                assertTrue(outcomes.all { it == HealthOutcome.Stored })
                assertEquals(listOf(2L, 350L, 160L, 90L), fleet(day))
                val ledger = requireNotNull(installs.ledgerFor(first, day))
                assertEquals(0, ledger.accepted)
                assertEquals(emptyList<String>(), ledger.batchIds)
                assertEquals(400L, ledger.bytes)
                val before = fleet(day)
                assertTrue(store.upsert(first, hash, day, listOf(initial), emptyMap(), 1, BudgetPolicy(dailyBytes = 0)) is HealthOutcome.BudgetExhausted)
                assertEquals(before, fleet(day))
                assertEquals(ledger, installs.ledgerFor(first, day))
                assertEquals(HealthOutcome.BatchQuality, store.upsert(first, hash, day, emptyList(), mapOf("bad_count" to 1), 100, BudgetPolicy()))
                assertEquals(before, fleet(day))
                assertEquals(ledger.copy(rejectedByReason = mapOf("bad_count" to 1)), installs.ledgerFor(first, day))

                // All three unknown-surge sources use stored aggregates; dedupe is shared with purge.
                assertEquals(HealthOutcome.Stored, write(first, fuller.copy(unknown = 500)))
                sql { connection ->
                    for (index in 1..5) {
                        val fingerprint = index.toString(16).padStart(64, '0')
                        connection.update("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (?, ?, ?, ?)", fingerprint, "doordash", day, day)
                        connection.update("INSERT INTO cluster_sightings_v5 (fingerprint, install_id, day, platform_app_version, hash_domain, filter_rev) VALUES (?, ?, ?, ?, 1, 1)", fingerprint, first, day, "8.0.0")
                    }
                    connection.update("UPDATE installs SET trusted = true WHERE install_id = ?", first)
                }
                evaluator.evaluate(first, listOf(fuller), hash)
                evaluator.evaluate(first, listOf(fuller), hash)
                assertEquals(listOf("trips", "fleet_unknown", "new_clusters"), alarms.map { it.kind })
                assertEquals(mapOf("trips" to 1L, "fleet_unknown" to 1L, "new_clusters" to 1L), evaluator.stats.snapshot())
                assertEquals(first.toString().take(8), alarms.first().installPrefix)

                val fixture = healthFixture()
                for (invalid in listOf("[]", "{}", body(emptyList()), "[".repeat(97))) {
                    assertError(client.signed(clock, second.toString(), key, HttpMethod.Post, "/v1/health", invalid), 400, "bad_request")
                }
                assertError(client.signed(clock, second.toString(), key, HttpMethod.Post, "/v1/health", body(List(31) { fixture })), 413, "batch_too_large")
                val bad = JsonObject(fixture - "platformAppVersion")
                val rejected = client.signed(clock, second.toString(), key, HttpMethod.Post, "/v1/health", body(listOf(bad)))
                assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
                assertEquals(Json.parseToJsonElement("""{"error":"batch_quality","rejected":{"bad_version":1}}"""), Json.parseToJsonElement(rejected.bodyAsText()))
                val acceptedBody = body(List(4) { fixture } + bad)
                val accepted = client.signed(clock, second.toString(), key, HttpMethod.Post, "/v1/health", acceptedBody)
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertEquals(Json.parseToJsonElement("""{"status":"accepted","accepted":4,"rejected":{"bad_version":1}}"""), Json.parseToJsonElement(accepted.bodyAsText()))
                val after = fleet(day)
                assertEquals(HttpStatusCode.OK, client.signed(clock, second.toString(), key, HttpMethod.Post, "/v1/health", acceptedBody).status)
                assertEquals(after, fleet(day))
                assertEquals(1, count("health_daily", second))
                val secondLedger = requireNotNull(installs.ledgerFor(second, day))
                assertEquals(0, secondLedger.accepted)
                assertEquals(emptyList<String>(), secondLedger.batchIds)
                assertEquals(100L + 2 * acceptedBody.toByteArray().size, secondLedger.bytes)

                instant = instant.plusSeconds(2 * 86400)
                val purge = PurgeJob(installs, clock, alarms = evaluator)
                purge.runOnce()
                purge.runOnce()
                assertEquals(1, alarms.count { it.kind == "silence" })
                assertEquals(first.toString().take(8), alarms.last().installPrefix)
                evaluator.evaluate(first, listOf(fuller), hash)
                assertEquals(2, alarms.count { it.kind == "trips" })

                val cutoff = day.plusDays(2).minusDays(Policy().retention.healthDailyDays.toLong())
                sql { connection ->
                    for (oldDay in listOf(cutoff.minusDays(1), cutoff)) connection.update(
                        """INSERT INTO health_daily (install_id, day, platform, platform_app_version, admitted, unknown, trips, rule_counts)
                            VALUES (?, ?, 'doordash', 'old', 0, 0, 0, '{}'::jsonb)""", first, oldDay,
                    )
                }
                assertEquals(2, purge.runOnce().healthRows)
                assertEquals(1, count("health_daily", first))
                assertEquals(HttpStatusCode.Accepted, client.signed(clock, first.toString(), key, HttpMethod.Delete, "/v1/installs/me").status)
                assertEquals(0, count("health_daily", first))
                assertEquals(HttpStatusCode.OK, client.enrol(first.toString(), secret(92)).status)
                assertEquals(HealthOutcome.StaleCredential, write(first, initial))
                assertEquals(HealthOutcome.StaleCredential, store.upsert(first, hash, day, emptyList(), mapOf("bad_count" to 1), 100, BudgetPolicy()))
                assertNull(installs.ledgerFor(first, day))
                val alarmsBeforeStale = alarms.size
                evaluator.evaluate(first, listOf(initial), hash)
                assertEquals(alarmsBeforeStale, alarms.size)
                assertFalse(requireNotNull(installs.lookup(first)).trusted)
            }
        }
    }

    @Test
    fun `a later backfill for another version never re-emits an older version's trips alarm (review round 2)`() {
        var instant = Instant.parse("2026-10-01T12:00:00Z")
        val clock = object : Clock { override fun now(): Instant = instant }
        val day = LocalDate.parse("2026-10-01")
        val id = UUID.randomUUID()
        val key = secret(93)
        val hash = hashSecret(key)
        val alarms = mutableListOf<Alarm>()
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            val store = HealthStore(db, clock)
            val evaluator = HealthAlarms(store, clock, startedAt = clock.now(), sink = AlarmSink { alarms += it })
            runBlocking {
                installs.enrol(id, hash, "1.0.0")
                val tripped = HealthReport(day, "doordash", "8.0.0", "1.0.0", "v1", 300, 10, 1, mapOf("a.b" to 30))
                assertEquals(HealthOutcome.Stored, store.upsert(id, hash, day, listOf(tripped), emptyMap(), 100, BudgetPolicy()))
                evaluator.evaluate(id, listOf(tripped), hash)
                assertEquals(listOf("trips"), alarms.map { it.kind })
                // Next UTC day (dedupe reset): a backfill for the SAME day under another version with trips = 0.
                instant = Instant.parse("2026-10-02T12:00:00Z")
                val backfill = HealthReport(day, "doordash", "9.0.0", "1.0.0", "v1", 300, 10, 0, mapOf("a.b" to 30))
                assertEquals(HealthOutcome.Stored, store.upsert(id, hash, day.plusDays(1), listOf(backfill), emptyMap(), 100, BudgetPolicy()))
                evaluator.evaluate(id, listOf(backfill), hash)
                assertEquals(listOf("trips"), alarms.map { it.kind }, "the 8.0.0 trips alarm must not be re-emitted by a 9.0.0 backfill")
            }
        }
    }

    private fun body(reports: List<JsonElement>): String = JsonObject(mapOf("reports" to JsonArray(reports))).toString()
    private fun fleet(day: LocalDate): List<Long> = sql { connection ->
        connection.prepareStatement("SELECT installs_reporting, admitted, unknown, COALESCE((rule_counts->>'a.b')::bigint, 0) FROM health_fleet_daily WHERE day = ? AND platform = 'doordash' AND platform_app_version = '8.0.0'").use { statement ->
            statement.setObject(1, day)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); (1..4).map { rows.getLong(it) } }
        }
    }
    private fun count(table: String, id: UUID): Int = sql { connection ->
        connection.prepareStatement("SELECT count(*) FROM $table WHERE install_id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }
    private fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

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
