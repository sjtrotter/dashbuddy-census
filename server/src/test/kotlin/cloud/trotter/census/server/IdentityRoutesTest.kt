package cloud.trotter.census.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.server.auth.AuthStats
import cloud.trotter.census.server.auth.RequestSigner
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.db.EnrolOutcome
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.LedgerRow
import cloud.trotter.census.server.jobs.PurgeJob
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: identity tests skipped")
class IdentityRoutesTest {
    private class FixedClock(var instant: Instant = Instant.parse("2026-10-02T12:00:00Z")) : Clock {
        override fun now(): Instant = instant
    }

    @Test
    fun `enrol authenticate rotate nonce and withdraw preserve privacy`() {
        val clock = FixedClock()
        val id = UUID.randomUUID().toString()
        val secret = secret(1)
        val replacement = secret(2)
        val signatures = mutableListOf<String>()
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            Database.connect(config()).use { db ->
                val store = InstallStore(db, clock)
                testApplication {
                    environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
                    application { module(config(), db, clock) }
                    assertEquals(HttpStatusCode.OK, client.get("/v1/policy").status)
                    val enrol = client.enrol(id, secret)
                    assertEquals(HttpStatusCode.OK, enrol.status)
                    val policy = Json.parseToJsonElement(enrol.bodyAsText()).jsonObject
                    assertEquals(id.take(8), policy.getValue("installIdPrefix").jsonPrimitive.content)
                    assertEquals("300", policy.getValue("dailySkeletonBudget").jsonPrimitive.content)
                    assertTrue("retention" in policy)
                    assertEquals(HttpStatusCode.OK, client.enrol(id, secret).status)
                    assertEquals(HttpStatusCode.Conflict, client.enrol(id, replacement).status)
                    assertEquals(HttpStatusCode.Unauthorized, client.enrol("malformed", secret).status)
                    assertEquals(HttpStatusCode.UnprocessableEntity, client.enrol(id, secret, schema = "unsupported").status)
                    assertEquals(HttpStatusCode.BadRequest, client.enrol(id, secret, version = "bad version").status)
                    assertEquals(HttpStatusCode.Unauthorized, client.enrol(id, secret, bodyId = UUID.randomUUID().toString()).status)
                    val okBefore = AuthStats.ok.get()
                    suspend fun signed(method: HttpMethod, path: String, body: String = "", key: String = secret): HttpResponse {
                        val canonical = RequestSigner.canonical(method.value, path, clock.now().epochSecond.toString(), body.toByteArray())
                        signatures += RequestSigner.sign(key, canonical)
                        return client.signed(clock, id, key, method, path, body)
                    }
                    val me = signed(HttpMethod.Get, "/v1/me")
                    assertEquals(HttpStatusCode.OK, me.status)
                    val view = Json.parseToJsonElement(me.bodyAsText()).jsonObject
                    assertEquals(setOf("installIdPrefix", "createdDay", "lastSeenDay", "trusted", "budget"), view.keys)
                    assertEquals("2026-10-02", view.getValue("createdDay").jsonPrimitive.content)
                    assertEquals("300", view.getValue("budget").jsonObject.getValue("skeletonsRemainingToday").jsonPrimitive.content)
                    assertEquals(HttpStatusCode.OK, signed(HttpMethod.Get, "/v1/me").status) // exact signature replay
                    assertTrue(AuthStats.ok.get() >= okBefore + 2)
                    val badBefore = AuthStats.badSignature.get()
                    assertError(client.signed(clock, id, secret, HttpMethod.Get, "/v1/me", signature = "v1=" + "0".repeat(64)), 401, "unauthorized")
                    assertTrue(AuthStats.badSignature.get() > badBefore)
                    assertError(client.signed(clock, id, secret, HttpMethod.Get, "/v1/me", offset = -301), 401, "unauthorized")
                    assertEquals(HttpStatusCode.OK, client.signed(clock, id, secret, HttpMethod.Get, "/v1/me", offset = 300).status)
                    assertError(client.signed(clock, id, secret, HttpMethod.Post, "/v1/rotate", "{\"newSecret\":\"$replacement\"}", signedBody = "{}"), 401, "unauthorized")
                    assertError(client.signed(clock, id, secret, HttpMethod.Post, "/v1/rotate", "x".repeat(1_048_577)), 413, "payload_too_large")
                    assertError(signed(HttpMethod.Post, "/v1/rotate", "{\"newSecret\":\"short\"}"), 400, "bad_request")
                    assertEquals(HttpStatusCode.NoContent, signed(HttpMethod.Post, "/v1/rotate", "{\"newSecret\":\"$replacement\"}").status)
                    assertError(signed(HttpMethod.Get, "/v1/me"), 401, "unauthorized")
                    assertEquals(HttpStatusCode.OK, signed(HttpMethod.Get, "/v1/me", key = replacement).status)
                    val nonceResponse = signed(HttpMethod.Post, "/v1/nonce", key = replacement)
                    assertEquals(HttpStatusCode.OK, nonceResponse.status)
                    val nonce = Json.parseToJsonElement(nonceResponse.bodyAsText()).jsonObject.getValue("nonce").jsonPrimitive.content
                    assertTrue(Regex("[0-9a-f]{32}").matches(nonce))
                    assertTrue(store.consumeNonce(nonce, UUID.fromString(id)))
                    assertFalse(store.consumeNonce(nonce, UUID.fromString(id)))
                    store.recordIngest(UUID.fromString(id), clock.today(), 1024, 3, 2, mapOf("invalid" to 1), "batch")
                    val budgetView = Json.parseToJsonElement(signed(HttpMethod.Get, "/v1/me", key = replacement).bodyAsText())
                        .jsonObject.getValue("budget").jsonObject
                    assertEquals("297", budgetView.getValue("skeletonsRemainingToday").jsonPrimitive.content)
                    assertEquals("10484736", budgetView.getValue("bytesRemainingToday").jsonPrimitive.content)
                    seedChildren(id)
                    val countsBefore = tableCounts(id)
                    countsBefore.values.forEach { assertTrue(it > 0) }
                    val withdrawn = signed(HttpMethod.Delete, "/v1/installs/me", key = replacement)
                    assertEquals(HttpStatusCode.Accepted, withdrawn.status)
                    assertEquals("{\"status\":\"withdrawn\",\"completionDeadline\":\"2026-10-03\"}", withdrawn.bodyAsText())
                    tableCounts(id).values.forEach { assertEquals(0, it) }
                    assertError(signed(HttpMethod.Get, "/v1/me", key = replacement), 401, "unauthorized")
                }
            }
            val forbidden = listOf(secret, replacement, "Bearer $id.$secret", "Bearer $id.$replacement", id) + signatures
            logs.list.forEach { event ->
                forbidden.forEach { privateValue ->
                    assertFalse(event.formattedMessage.contains(privateValue), "Log disclosed a credential or full ID")
                    assertFalse(event.throwableProxy?.message?.contains(privateValue) == true)
                }
            }
            assertTrue(logs.list.any { it.formattedMessage.contains("install_prefix=${id.take(8)}") })
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `revocation rejects every authenticated route and reenrolment`() {
        val clock = FixedClock()
        val id = UUID.randomUUID()
        val secret = secret(3)
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            testApplication {
                application { module(config(), db, clock) }
                assertEquals(HttpStatusCode.OK, client.enrol(id.toString(), secret).status)
                assertTrue(store.revoke(id, clock.now()))
                sql { connection ->
                    connection.prepareStatement("SELECT revoked_at FROM installs WHERE install_id = ?").use { statement ->
                        statement.setObject(1, id)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(clock.now(), rows.getObject(1, java.time.OffsetDateTime::class.java).toInstant())
                        }
                    }
                }
                assertEquals(EnrolOutcome.Revoked, store.enrol(id, hashSecret(secret), "1.0"))
                listOf(HttpMethod.Get to "/v1/me", HttpMethod.Post to "/v1/rotate", HttpMethod.Post to "/v1/nonce", HttpMethod.Delete to "/v1/installs/me")
                    .forEach { (method, path) -> assertError(client.signed(clock, id.toString(), secret, method, path), 401, "revoked") }
                assertError(client.enrol(id.toString(), secret), 401, "revoked")
                store.withdraw(id)
            }
        }
    }

    @Test
    fun `rate limits use authenticated IDs and nonces have an additional quota`() {
        val clock = FixedClock()
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val secret = secret(4)
        Database.connect(config()).use { db ->
            testApplication {
                application { module(config(), db, clock) }
                assertEquals(HttpStatusCode.OK, client.enrol(first, secret).status)
                assertEquals(HttpStatusCode.OK, client.enrol(second, secret).status)
                repeat(60) { assertEquals(HttpStatusCode.OK, client.signed(clock, first, secret, HttpMethod.Get, "/v1/me").status) }
                val limited = client.signed(clock, first, secret, HttpMethod.Get, "/v1/me")
                assertError(limited, 429, "rate_limited")
                assertTrue(requireNotNull(limited.headers[HttpHeaders.RetryAfter]).toLong() > 0)
                assertEquals(HttpStatusCode.OK, client.signed(clock, second, secret, HttpMethod.Get, "/v1/me").status)
                repeat(30) { assertEquals(HttpStatusCode.OK, client.signed(clock, second, secret, HttpMethod.Post, "/v1/nonce").status) }
                val nonceLimit = client.signed(clock, second, secret, HttpMethod.Post, "/v1/nonce")
                assertError(nonceLimit, 429, "rate_limited")
                assertNotNull(nonceLimit.headers[HttpHeaders.RetryAfter])
                InstallStore(db, clock).withdraw(UUID.fromString(first))
                InstallStore(db, clock).withdraw(UUID.fromString(second))
            }
        }
    }

    @Test
    fun `enrol limit is shared across installs`() {
        val clock = FixedClock()
        Database.connect(config()).use { db ->
            testApplication {
                application { module(config(), db, clock) }
                repeat(120) {
                    // Schema rejection consumes the global bucket without creating an install.
                    assertEquals(HttpStatusCode.UnprocessableEntity, client.enrol(UUID.randomUUID().toString(), secret(5), schema = "unsupported").status)
                }
                val response = client.enrol(UUID.randomUUID().toString(), secret(5))
                assertError(response, 429, "rate_limited")
                assertNotNull(response.headers[HttpHeaders.RetryAfter])
            }
        }
    }

    @Test
    fun `store maintains UTC days nonce binding atomic ledger sums and purge boundaries`() = runBlocking {
        val clock = FixedClock()
        val id = UUID.randomUUID()
        val other = UUID.randomUUID()
        val hash = hashSecret(secret(6))
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            assertEquals(EnrolOutcome.Created, store.enrol(id, hash, "1.0"))
            store.enrol(other, hash, "1.0")
            val oldDay = clock.today()
            clock.instant = Instant.parse("2026-10-03T00:00:00Z")
            val row = requireNotNull(store.authenticate(id, hash))
            assertEquals(oldDay, row.createdDay)
            assertEquals(clock.today(), row.lastSeenDay)
            assertNull(store.authenticate(id, hashSecret(secret(7))))
            val nonce = store.issueNonce(id)
            assertFalse(store.consumeNonce(nonce, other))
            assertFalse(store.consumeNonce(nonce, null))
            clock.instant = clock.instant.plusSeconds(300)
            val consumed = coroutineScope { (1..8).map { async { store.consumeNonce(nonce, id) } }.awaitAll() }
            assertEquals(1, consumed.count { it })
            val expired = store.issueNonce(id)
            clock.instant = clock.instant.plusSeconds(301)
            assertFalse(store.consumeNonce(expired, id))
            assertTrue(store.consumeNonce(store.issueNonce(null), id))
            val future = store.issueNonce(id)
            clock.instant = clock.instant.minusSeconds(1)
            assertFalse(store.consumeNonce(future, id))
            clock.instant = clock.instant.plusSeconds(1)
            val day = clock.today()
            assertNull(store.ledgerFor(id, day))
            coroutineScope {
                (1..8).map { async { store.recordIngest(id, day, 10, 1, 2, mapOf("invalid" to 3), "batch") } }.awaitAll()
            }
            store.recordIngest(id, day, 5, 0, 1, mapOf("invalid" to 1, "oversize" to 2), "second")
            store.recordIngest(id, day, 0, 0, 0, emptyMap(), null)
            assertEquals(LedgerRow(85, 8, 17, mapOf("invalid" to 25, "oversize" to 2), listOf("batch", "second")), store.ledgerFor(id, day))
            assertTrue(store.isBatchKnown(id, day, "batch"))
            assertFalse(store.isBatchKnown(id, day.plusDays(1), "batch"))
            assertFalse(store.isBatchKnown(other, day, "batch"))
            // Clear earlier nonces to make the purge report deterministic.
            store.purgeNonces(clock.now().plusSeconds(1))
            val cutoff = clock.now()
            clock.instant = cutoff.minusSeconds(3601)
            val stale = store.issueNonce(id)
            clock.instant = cutoff.minusSeconds(3600)
            val boundary = store.issueNonce(id)
            clock.instant = cutoff
            store.recordIngest(id, day.minusDays(8), 1, 1, 0, emptyMap(), null)
            store.recordIngest(id, day.minusDays(7), 1, 1, 0, emptyMap(), null)
            val report = PurgeJob(store, clock).runOnce()
            assertEquals(1, report.nonces)
            assertEquals(1, report.ledgerRows)
            assertNull(store.ledgerFor(id, day.minusDays(8)))
            assertNotNull(store.ledgerFor(id, day.minusDays(7)))
            sql { connection ->
                connection.prepareStatement("SELECT nonce FROM nonces WHERE nonce IN (?, ?)").use { statement ->
                    statement.setString(1, stale); statement.setString(2, boundary)
                    statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(boundary, rows.getString(1)); assertFalse(rows.next()) }
                }
            }
            val removed = store.withdraw(id)
            assertEquals(1, removed.getValue("installs"))
            store.withdraw(other)
        }
    }

    private suspend fun HttpClient.enrol(id: String, secret: String, schema: String = "uinode.skeleton.v1", version: String = "1.0", bodyId: String = id): HttpResponse =
        post("/v1/enroll") {
            header(HttpHeaders.Authorization, "Bearer $id.$secret")
            contentType(ContentType.Application.Json)
            setBody("{\"installId\":\"$bodyId\",\"appVersion\":\"$version\",\"schemaIds\":[\"$schema\"]}")
        }

    private suspend fun HttpClient.signed(
        clock: Clock, id: String, secret: String, method: HttpMethod, path: String, body: String = "",
        offset: Long = 0, signature: String? = null, signedBody: String = body,
    ): HttpResponse = request(path) {
        this.method = method
        val timestamp = (clock.now().epochSecond + offset).toString()
        header(HttpHeaders.Authorization, "Bearer $id.$secret")
        header("X-Census-Timestamp", timestamp)
        header("X-Census-Signature", signature ?: RequestSigner.sign(secret, RequestSigner.canonical(method.value, path, timestamp, signedBody.toByteArray())))
        contentType(ContentType.Application.Json)
        if (body.isNotEmpty()) setBody(body)
    }

    private suspend fun assertError(response: HttpResponse, status: Int, error: String) {
        assertEquals(status, response.status.value)
        assertEquals("{\"error\":\"$error\"}", response.bodyAsText())
    }

    private fun seedChildren(id: String) = sql { connection ->
        // Literal test fixtures only; production SQL always binds values.
        val fingerprint = "1".repeat(64)
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES ('$fingerprint', 'android', '2026-10-02', '2026-10-02') ON CONFLICT DO NOTHING")
            statement.executeUpdate("INSERT INTO trusted_envelopes (install_id, envelope, received_day, purge_after) VALUES ('$id', '{}', '2026-10-02', '2026-10-03')")
            statement.executeUpdate("INSERT INTO health_daily VALUES ('$id', '2026-10-02', 'android', '1.0', 1, 0, 0, NULL, '{}')")
            statement.executeUpdate("INSERT INTO token_sightings VALUES ('1234567890abcdef', '$id', '2026-10-02', '2026-10-02', 'test')")
            statement.executeUpdate("INSERT INTO cluster_sightings VALUES ('$fingerprint', '$id', '2026-10-02', '1.0', 1)")
            statement.executeUpdate("INSERT INTO ingest_ledger (install_id, day) VALUES ('$id', '2026-10-02') ON CONFLICT DO NOTHING")
        }
    }

    private fun tableCounts(id: String): Map<String, Int> = sql { connection ->
        InstallStore.WITHDRAWAL_TABLES.associateWith { table ->
            connection.prepareStatement("SELECT count(*) FROM $table WHERE install_id = ?::uuid").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }

    private fun <T> sql(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

    private fun secret(seed: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (seed + it).toByte() })

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
