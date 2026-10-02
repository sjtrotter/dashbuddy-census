package cloud.trotter.census.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.server.db.EnvelopeOutcome
import cloud.trotter.census.server.db.EnvelopeStore
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.update
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.EnvelopeValidator
import cloud.trotter.census.server.ingest.EnvelopeVerdict
import cloud.trotter.census.server.jobs.PurgeJob
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.UUID

@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: envelope tests skipped")
class EnvelopeRoutesTest {
    private val day = LocalDate.of(2026, 9, 18)
    private val clock = object : Clock { override fun now(): Instant = Instant.parse("2026-09-18T12:00:00Z") }

    @Test
    fun `trust admission privacy retries retention and withdrawal`() {
        val id = UUID.randomUUID()
        val key = secret(81)
        val fixture = envelopeFixture()
        val payloadSentinel = "DasherDirect balance PRIVATE_BALANCE_SENTINEL"
        val sensitive = JsonObject(fixture + ("payload" to JsonPrimitive(payloadSentinel)))
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            Database.connect(config()).use { db ->
                val store = InstallStore(db, clock)
                testApplication {
                    environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
                    application { module(config(), db, clock) }
                    assertEquals(HttpStatusCode.OK, client.enrol(id.toString(), key).status)
                    assertError(client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", "not json"), 403, "not_trusted")
                    assertNull(store.ledgerFor(id, day))
                    assertEquals(0, count(id))
                    sql { it.update("UPDATE installs SET trusted = true WHERE install_id = ?", id) }
                    val body = batch("first", listOf(fixture))
                    val accepted = client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", body)
                    assertEquals(HttpStatusCode.OK, accepted.status)
                    assertEquals("accepted", Json.parseToJsonElement(accepted.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
                    assertEquals(1, count(id))
                    sql { connection ->
                        connection.prepareStatement("SELECT fingerprint, envelope, received_day, purge_after FROM trusted_envelopes WHERE install_id = ?").use { statement ->
                            statement.setObject(1, id)
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                assertNull(rows.getString("fingerprint"))
                                assertEquals(day, rows.getObject("received_day", LocalDate::class.java))
                                assertEquals(day.plusDays(30), rows.getObject("purge_after", LocalDate::class.java))
                                val stored = Json.parseToJsonElement(rows.getString("envelope")).jsonObject
                                val expected = EnvelopeValidator.validate(fixture) as EnvelopeVerdict.Accepted
                                assertEquals(Json.parseToJsonElement(expected.canonicalJson), stored)
                            }
                        }
                    }
                    val beforeDuplicate = requireNotNull(store.ledgerFor(id, day))
                    val duplicate = client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", body)
                    assertEquals(Json.parseToJsonElement("""{"status":"duplicate","accepted":0,"duplicate":1,"rejected":{}}"""), Json.parseToJsonElement(duplicate.bodyAsText()))
                    assertEquals(beforeDuplicate.copy(duplicate = 1), store.ledgerFor(id, day))
                    assertEquals(1, count(id))

                    val refused = client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", batch("leak", listOf(sensitive)))
                    assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
                    assertEquals(Json.parseToJsonElement("""{"error":"batch_quality","rejected":{"sensitive_leak":1}}"""), Json.parseToJsonElement(refused.bodyAsText()))
                    assertEquals(1, count(id))
                    assertFalse(store.isBatchKnown(id, day, "leak"))
                    assertEquals(mapOf("sensitive_leak" to 1), requireNotNull(store.ledgerFor(id, day)).rejectedByReason)
                    val boundary = batch("leak", List(4) { fixture } + sensitive)
                    assertEquals(HttpStatusCode.OK, client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", boundary).status)
                    assertEquals(5, count(id))
                    val ledger = requireNotNull(store.ledgerFor(id, day))
                    assertEquals(5, ledger.accepted)
                    assertEquals((body.toByteArray().size + boundary.toByteArray().size).toLong(), ledger.bytes)
                    assertEquals(mapOf("sensitive_leak" to 2), ledger.rejectedByReason)

                    for (bad in listOf("[]", "{}", batch("empty", emptyList()), """{"batchId":"bad id","items":[{}]}""", "[".repeat(97))) {
                        assertError(client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", bad), 400, "bad_request")
                    }
                    assertError(client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", batch("many", List(21) { fixture })), 413, "batch_too_large")
                    assertError(client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", " ".repeat(1_048_577)), 413, "payload_too_large")

                    val raceBody = batch("race", listOf(fixture))
                    val responses = coroutineScope {
                        val start = CompletableDeferred<Unit>()
                        val requests = List(2) { async {
                            start.await()
                            client.signed(clock, id.toString(), key, HttpMethod.Post, "/v1/envelopes", raceBody)
                        } }
                        start.complete(Unit)
                        requests.awaitAll()
                    }
                    assertEquals(listOf("accepted", "duplicate"), responses.map {
                        assertEquals(HttpStatusCode.OK, it.status)
                        Json.parseToJsonElement(it.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content
                    }.sorted())
                    assertEquals(6, count(id))
                    val authenticated = requireNotNull(store.lookup(id))
                    val item = EnvelopeValidator.validate(fixture) as EnvelopeVerdict.Accepted
                    val envelopes = EnvelopeStore(db, clock)
                    assertTrue(envelopes.ingest(id, authenticated.keyHash, day, listOf(item), emptyMap(), "no-budget", 1, BudgetPolicy(dailySkeletonBudget = 0), 30) is EnvelopeOutcome.BudgetExhausted)
                    assertEquals(6, count(id))
                    sql { it.update("UPDATE installs SET trusted = false WHERE install_id = ?", id) }
                    val beforeTrustChange = store.ledgerFor(id, day)
                    assertEquals(EnvelopeOutcome.NotTrusted, envelopes.ingest(id, authenticated.keyHash, day, listOf(item), emptyMap(), "untrusted", 1, BudgetPolicy(), 30))
                    assertEquals(beforeTrustChange, store.ledgerFor(id, day))

                    sql { connection ->
                        connection.update("UPDATE trusted_envelopes SET purge_after = ? WHERE install_id = ?", day, id)
                        connection.update("UPDATE trusted_envelopes SET purge_after = ? WHERE id = (SELECT min(id) FROM trusted_envelopes WHERE install_id = ?)", day.minusDays(1), id)
                    }
                    assertEquals(1, PurgeJob(store, clock).runOnce().trustedEnvelopes)
                    assertEquals(5, count(id))
                    assertEquals(HttpStatusCode.Accepted, client.signed(clock, id.toString(), key, HttpMethod.Delete, "/v1/installs/me").status)
                    assertEquals(0, count(id))
                    assertEquals(HttpStatusCode.OK, client.enrol(id.toString(), secret(82)).status)
                    assertEquals(EnvelopeOutcome.StaleCredential, envelopes.ingest(id, authenticated.keyHash, day, listOf(item), emptyMap(), "stale", 1, BudgetPolicy(), 30))
                    assertEquals(EnvelopeOutcome.StaleCredential, envelopes.ingest(id, authenticated.keyHash, day, emptyList(), mapOf("bad_item" to 1), "stale-bad", 1, BudgetPolicy(), 30))
                    assertNull(store.ledgerFor(id, day))
                }
            }
            val warnings = logs.list.filter { it.loggerName == "Ingest" && it.level == Level.WARN }
            assertEquals(2, warnings.size)
            assertTrue(warnings.all { it.formattedMessage == "ingest kind=envelopes marker=DasherDirect" })
            assertPrivateLogs(logs.list, listOf(id.toString(), key, payloadSentinel, "PRIVATE_BALANCE_SENTINEL", "DEVICE_SENTINEL", "SIGNATURE_SENTINEL", "Looking for offers"))
            val infos = logs.list.filter { it.loggerName == "Ingest" && it.level == Level.INFO }
            assertEquals(6, infos.size)
            assertTrue(infos.all { Regex("ingest kind=envelopes install_prefix=[0-9a-f]{8} accepted=[0-9]+ duplicate=[0-9]+ rejected=[0-9]+ bytes=[0-9]+ status=(accepted|duplicate|batch_quality|budget_exhausted)").matches(it.formattedMessage) })
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    private fun batch(id: String, items: List<JsonElement>): String = JsonObject(mapOf("batchId" to JsonPrimitive(id), "items" to JsonArray(items))).toString()
    private fun count(id: UUID): Int = sql { connection ->
        connection.prepareStatement("SELECT count(*) FROM trusted_envelopes WHERE install_id = ?").use { statement ->
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
