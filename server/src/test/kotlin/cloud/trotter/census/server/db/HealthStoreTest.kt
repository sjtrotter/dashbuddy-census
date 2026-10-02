package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Config
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.assertError
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: health store tests skipped")
class HealthStoreTest {
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
            val evaluator = HealthAlarms(store, clock, AlarmSink { alarms += it })
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
                        connection.update("INSERT INTO cluster_sightings (fingerprint, install_id, day, platform_app_version) VALUES (?, ?, ?, ?)", fingerprint, first, day, "8.0.0")
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
                assertEquals(1, purge.runOnce().healthRows)
                assertEquals(2, count("health_daily", first))
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
