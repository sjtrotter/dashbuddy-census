package cloud.trotter.census.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.server.auth.RequestSigner
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.db.IngestOutcome
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.SkeletonStore
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.GZIPInputStream

@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: conformance tests skipped")
class ConformanceReplayTest {
    @Test
    fun `golden replay enforces budgets deduplication quality privacy and withdrawal`() {
        val golden = GZIPInputStream(Files.newInputStream(
            Path.of(System.getProperty("census.contractDir"), "conformance", "skeletons.jsonl.gz"),
        )).bufferedReader().useLines { lines -> lines.map { Json.parseToJsonElement(it).jsonObject }.toList() }
        assertEquals(876, golden.size)
        val items = golden.map { it.getValue("skeleton").jsonObject }
        val day = LocalDate.parse(items.first().getValue("day").jsonPrimitive.content)
        val clock = object : Clock {
            override fun now(): Instant = day.atTime(12, 0).toInstant(ZoneOffset.UTC)
        }
        val id = UUID.randomUUID().toString()
        val key = secret(41)
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            Database.connect(config()).use { db ->
                val store = InstallStore(db, clock)
                testApplication {
                    environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
                    application { module(config(), db, clock) }
                    assertEquals(HttpStatusCode.OK, client.enrol(id, key).status)
                    val chunks = items.chunked(100)
                    var duplicates = 0
                    for ((index, chunk) in chunks.withIndex()) {
                        val before = tableCounts()
                        val response = client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("golden-$index", chunk))
                        if (index < 3) {
                            val text = response.bodyAsText()
                            assertEquals(HttpStatusCode.OK, response.status, text)
                            val body = Json.parseToJsonElement(text).jsonObject
                            assertEquals("accepted", body.getValue("status").jsonPrimitive.content)
                            assertEquals(JsonObject(emptyMap()), body.getValue("rejected"))
                            val distinct = chunk.map { it.getValue("fingerprint").jsonPrimitive.content }.toSet().size
                            assertEquals(distinct, body.getValue("accepted").jsonPrimitive.int)
                            assertEquals(chunk.size - distinct, body.getValue("duplicate").jsonPrimitive.int)
                            duplicates += chunk.size - distinct
                            val budget = body.getValue("budget").jsonObject
                            assertEquals(300 - (index + 1) * 100, budget.getValue("skeletonsRemainingToday").jsonPrimitive.int)
                            assertEquals(40 - index - 1, budget.getValue("batchesRemainingToday").jsonPrimitive.int)
                            assertEquals(43200, budget.getValue("resetInSeconds").jsonPrimitive.int)
                            val bytesSpent = chunks.take(index + 1).mapIndexed { i, value -> batch("golden-$i", value).toByteArray().size }.sum()
                            assertEquals(10 * 1024 * 1024 - bytesSpent, budget.getValue("bytesRemainingToday").jsonPrimitive.int)
                        } else {
                            assertError(response, 429, "budget_exhausted")
                            assertEquals("43200", response.headers[HttpHeaders.RetryAfter])
                            assertEquals(before, tableCounts())
                        }
                    }
                    val posted = golden.take(300)
                    val fingerprints = posted.groupBy { it.getValue("fingerprint").jsonPrimitive.content }
                    val counts = tableCounts()
                    assertEquals(fingerprints.size, counts.getValue("clusters"))
                    // F6(a), as jsonb normalizes spacing/key order: the stored sample is the item's canonical content
                    // (parsed-equal to SkeletonSchema.measure(...).json), never the client bytes and never a placeholder.
                    for ((fingerprint, group) in fingerprints.entries.take(5)) {
                        val stored = sql { connection ->
                            connection.prepareStatement("SELECT skeleton::text FROM cluster_samples WHERE fingerprint = ?").use { statement ->
                                statement.setString(1, fingerprint)
                                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                            }
                        }
                        assertTrue(stored.isNotEmpty(), "a sample must exist for $fingerprint")
                        val expected = SkeletonSchema.measure(SkeletonSchema.deserialize(group.first().getValue("skeleton").jsonObject.toString())).json
                        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(stored.single()))
                    }
                    val sightings = sql { connection ->
                        connection.prepareStatement("SELECT fingerprint, sum(count) FROM cluster_sightings WHERE install_id = ?::uuid GROUP BY fingerprint").use { statement ->
                            statement.setString(1, id)
                            statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) } }
                        }
                    }
                    assertEquals(fingerprints.mapValues { it.value.size }, sightings)
                    val pairs = fingerprints.filterValues { group -> group.map { it.getValue("file").jsonPrimitive.content }.toSet().size > 1 }
                    assertTrue(pairs.isNotEmpty(), "the admitted golden must exercise pseudonym invariance")
                    for ((fingerprint, _) in pairs) {
                        assertTrue(sightings.getValue(fingerprint) >= 2)
                        assertEquals(1, sql { connection ->
                            connection.prepareStatement("SELECT count(*) FROM clusters WHERE fingerprint = ?").use { statement ->
                                statement.setString(1, fingerprint)
                                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                            }
                        })
                    }
                    assertTrue(sql { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT count(*) FROM (SELECT fingerprint FROM cluster_samples GROUP BY fingerprint HAVING count(*) > 5) excess").use { rows ->
                                rows.next(); rows.getInt(1) == 0
                            }
                        }
                    })
                    val hashes = sql { connection ->
                        connection.prepareStatement("SELECT DISTINCT token_hash FROM token_sightings WHERE install_id = ?::uuid").use { statement ->
                            statement.setString(1, id)
                            statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
                        }
                    }
                    assertEquals(posted.flatMap { it.getValue("hashes").jsonArray.map { hash -> hash.jsonPrimitive.content } }.toSet(), hashes)
                    val ledgerBefore = requireNotNull(store.ledgerFor(UUID.fromString(id), day))
                    assertEquals(300, ledgerBefore.accepted)
                    assertEquals(duplicates, ledgerBefore.duplicate)
                    val replay = client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("golden-0", chunks.first()))
                    assertEquals(HttpStatusCode.OK, replay.status)
                    assertEquals(Json.parseToJsonElement("""{"status":"duplicate","accepted":0,"duplicate":100,"rejected":{}}"""), Json.parseToJsonElement(replay.bodyAsText()))
                    assertEquals(counts, tableCounts())
                    assertEquals(ledgerBefore.copy(duplicate = duplicates + 100), store.ledgerFor(UUID.fromString(id), day))

                    val badItems = chunks.first().mapIndexed { index, item -> if (index < 30) badHash(item) else item }
                    val quality = client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("quality", badItems))
                    assertEquals(HttpStatusCode.UnprocessableEntity, quality.status)
                    assertEquals(Json.parseToJsonElement("""{"error":"batch_quality","rejected":{"bad_hash":30}}"""), Json.parseToJsonElement(quality.bodyAsText()))
                    assertEquals(counts, tableCounts())
                    assertEquals(ledgerBefore.copy(duplicate = duplicates + 100, rejectedByReason = mapOf("bad_hash" to 30)), store.ledgerFor(UUID.fromString(id), day))

                    val freshId = UUID.randomUUID().toString()
                    val freshKey = secret(42)
                    assertEquals(HttpStatusCode.OK, client.enrol(freshId, freshKey).status)
                    val oversizedBody = batch("oversized", listOf(items.first())) + " ".repeat(1_048_577)
                    assertError(client.signed(clock, freshId, freshKey, HttpMethod.Post, "/v1/skeletons", oversizedBody), 413, "payload_too_large")
                    assertEquals(counts, tableCounts())

                    assertEquals(HttpStatusCode.Accepted, client.signed(clock, id, key, HttpMethod.Delete, "/v1/installs/me").status)
                    val afterWithdrawal = tableCounts()
                    assertEquals(0, afterWithdrawal.getValue("cluster_sightings"))
                    assertEquals(0, afterWithdrawal.getValue("token_sightings"))
                    assertEquals(counts.getValue("clusters"), afterWithdrawal.getValue("clusters"))
                    assertEquals(counts.getValue("cluster_samples"), afterWithdrawal.getValue("cluster_samples"))

                    // A failed quality check never claims the batch ID, and exactly 20% rejected is admissible.
                    val failed = client.signed(clock, freshId, freshKey, HttpMethod.Post, "/v1/skeletons", batch("correctable", badItems))
                    assertEquals(HttpStatusCode.UnprocessableEntity, failed.status)
                    assertFalse(store.isBatchKnown(UUID.fromString(freshId), day, "correctable"))
                    val boundaryItems = chunks.first().mapIndexed { index, item -> if (index < 20) badHash(item) else item }
                    val corrected = client.signed(clock, freshId, freshKey, HttpMethod.Post, "/v1/skeletons", batch("correctable", boundaryItems))
                    assertEquals(HttpStatusCode.OK, corrected.status)
                    val correctedBody = Json.parseToJsonElement(corrected.bodyAsText()).jsonObject
                    assertEquals("accepted", correctedBody.getValue("status").jsonPrimitive.content)
                    assertEquals(Json.parseToJsonElement("""{"bad_hash":20}"""), correctedBody.getValue("rejected"))
                    val freshLedger = requireNotNull(store.ledgerFor(UUID.fromString(freshId), day))
                    assertEquals(80, freshLedger.accepted)
                    assertEquals(mapOf("bad_hash" to 50), freshLedger.rejectedByReason)
                    assertEquals(listOf("correctable"), freshLedger.batchIds)
                    assertEquals(batch("correctable", boundaryItems).toByteArray().size.toLong(), freshLedger.bytes)

                    for (body in listOf("[]", "{}", """{"batchId":"bad id","items":[{}]}""", """{"batchId":"a","items":{}}""", batch("empty", emptyList()))) {
                        assertError(client.signed(clock, freshId, freshKey, HttpMethod.Post, "/v1/skeletons", body), 400, "bad_request")
                    }
                    assertError(client.signed(clock, freshId, freshKey, HttpMethod.Post, "/v1/skeletons", batch("too-many", List(101) { JsonObject(emptyMap()) })), 413, "batch_too_large")

                    // Concurrent retries must make their decision under the same ledger row lock.
                    val raceId = UUID.randomUUID().toString()
                    val raceKey = secret(43)
                    assertEquals(HttpStatusCode.OK, client.enrol(raceId, raceKey).status)
                    val raceBody = batch("race", listOf(items.first()))
                    val raceBefore = store.ledgerFor(UUID.fromString(raceId), day)
                    val responses = coroutineScope {
                        val start = CompletableDeferred<Unit>()
                        val requests = List(2) {
                            async {
                                start.await()
                                client.signed(clock, raceId, raceKey, HttpMethod.Post, "/v1/skeletons", raceBody)
                            }
                        }
                        start.complete(Unit)
                        requests.awaitAll()
                    }
                    val statuses = responses.map { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content
                    }
                    assertEquals(listOf("accepted", "duplicate"), statuses.sorted())
                    val raceFingerprint = items.first().getValue("fingerprint").jsonPrimitive.content
                    assertEquals(1, sql { connection ->
                        connection.prepareStatement("SELECT sum(count) FROM cluster_sightings WHERE fingerprint = ? AND install_id = ?::uuid").use { statement ->
                            statement.setString(1, raceFingerprint)
                            statement.setString(2, raceId)
                            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                        }
                    })
                    val raceLedger = requireNotNull(store.ledgerFor(UUID.fromString(raceId), day))
                    assertEquals((raceBefore?.accepted ?: 0) + 1, raceLedger.accepted)
                    assertEquals((raceBefore?.duplicate ?: 0) + 1, raceLedger.duplicate)
                    assertEquals(raceBody.toByteArray().size.toLong(), raceLedger.bytes)
                    assertEquals(listOf("race"), raceLedger.batchIds)

                    // One fingerprint retains the first sample but counts sightings for both versions.
                    val versionRoot = JsonObject(mapOf("class" to JsonPrimitive("SightingVersionProbe")))
                    val versionBase = JsonObject(items.first() + ("root" to versionRoot))
                    val versionFingerprint = requireNotNull(CensusFingerprint.of(SkeletonSchema.deserialize(versionBase.toString()).root))
                    val versionItems = listOf("1.0.0", "2.0.0").map { version ->
                        JsonObject(versionBase + mapOf(
                            "fingerprint" to JsonPrimitive(versionFingerprint), "platformAppVersion" to JsonPrimitive(version),
                        ))
                    }
                    val versions = client.signed(clock, raceId, raceKey, HttpMethod.Post, "/v1/skeletons", batch("versions", versionItems))
                    assertEquals(HttpStatusCode.OK, versions.status)
                    val versionsBody = Json.parseToJsonElement(versions.bodyAsText()).jsonObject
                    assertEquals("accepted", versionsBody.getValue("status").jsonPrimitive.content)
                    assertEquals(1, versionsBody.getValue("accepted").jsonPrimitive.int)
                    assertEquals(1, versionsBody.getValue("duplicate").jsonPrimitive.int)
                    assertEquals(mapOf("1.0.0" to 1, "2.0.0" to 1), sql { connection ->
                        connection.prepareStatement("SELECT platform_app_version, count FROM cluster_sightings WHERE fingerprint = ? AND install_id = ?::uuid").use { statement ->
                            statement.setString(1, versionFingerprint)
                            statement.setString(2, raceId)
                            statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) } }
                        }
                    })
                    assertEquals(listOf("1.0.0"), sql { connection ->
                        connection.prepareStatement("SELECT platform_app_version FROM cluster_samples WHERE fingerprint = ?").use { statement ->
                            statement.setString(1, versionFingerprint)
                            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                        }
                    })

                    // The old signature remains in-window, but neither auth nor ingest may use the replacement.
                    val staleBody = batch("stale", listOf(items.first()))
                    val timestamp = clock.now().epochSecond.toString()
                    val staleSignature = RequestSigner.sign(raceKey, RequestSigner.canonical("POST", "/v1/skeletons", timestamp, staleBody.toByteArray()))
                    val authenticated = requireNotNull(store.lookup(UUID.fromString(raceId)))
                    val acceptedItem = SkeletonValidator.validate(items.first(), Policy(), day) as ItemVerdict.Accepted
                    assertEquals(HttpStatusCode.Accepted, client.signed(clock, raceId, raceKey, HttpMethod.Delete, "/v1/installs/me").status)
                    val replacementKey = secret(44)
                    assertEquals(HttpStatusCode.OK, client.enrol(raceId, replacementKey).status)
                    assertTrue(RequestSigner.timestampInWindow(timestamp, clock.now()))
                    assertError(client.signed(clock, raceId, raceKey, HttpMethod.Post, "/v1/skeletons", staleBody, signature = staleSignature), 401, "unauthorized")
                    val beforeStale = tableCounts()
                    assertEquals(IngestOutcome.StaleCredential, SkeletonStore(db, clock).ingest(
                        authenticated.id, authenticated.keyHash, day, listOf(acceptedItem), 0, emptyMap(),
                        "stale", staleBody.toByteArray().size.toLong(), BudgetPolicy(), clock.now(),
                    ))
                    assertEquals(beforeStale, tableCounts())
                    assertNull(store.ledgerFor(authenticated.id, day))
                    assertEquals(hashSecret(replacementKey), requireNotNull(store.lookup(authenticated.id)).keyHash)
                }
            }
            val ingestInfo = logs.list.filter { it.loggerName == "Ingest" && it.level == Level.INFO }
            assertEquals(16, ingestInfo.size)
            val linePattern = Regex("ingest install_prefix=[0-9a-f]{8} accepted=[0-9]+ duplicate=[0-9]+ rejected=[0-9]+ bytes=[0-9]+ status=(accepted|duplicate|batch_quality|budget_exhausted)")
            val forbiddenHex = Regex("(?<![0-9a-fA-F])(?:[0-9a-fA-F]{64}|[0-9a-fA-F]{16})(?![0-9a-fA-F])")
            for (event in ingestInfo) {
                assertTrue(linePattern.matches(event.formattedMessage))
                assertFalse(forbiddenHex.containsMatchIn(event.formattedMessage))
            }
            assertPrivateLogs(logs.list, listOf(id, key) + items.take(300).map { it.getValue("fingerprint").jsonPrimitive.content })
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    private fun batch(id: String, items: List<JsonElement>): String = JsonObject(mapOf(
        "batchId" to JsonPrimitive(id), "items" to JsonArray(items),
    )).toString()

    private fun badHash(item: JsonObject): JsonObject = JsonObject(item + ("windowTitle" to JsonObject(mapOf(
        "kind" to JsonPrimitive("words:2"), "h" to JsonPrimitive("ABCDEF0123456789"),
    ))))

    private fun tableCounts(): Map<String, Int> = sql { connection ->
        listOf("clusters", "cluster_samples", "cluster_sightings", "token_sightings").associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }

    private fun <T> sql(block: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

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
