package cloud.trotter.census.server

import cloud.trotter.census.contract.*
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.db.*
import cloud.trotter.census.server.ingest.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: notification integration tests skipped")
class NotificationIngestTest {
    private var instant = Instant.parse("2026-10-02T12:00:00Z")
    private val clock = object : Clock { override fun now(): Instant = instant }
    private val day = LocalDate.of(2026, 10, 2)
    @BeforeEach fun clean() { sql { it.update("TRUNCATE installs, clusters CASCADE") } }

    @Test
    fun `mixed HTTP batches spend one quota before dedup and retries do not spend again`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(config(), db, clock, policy = notificationPolicy) }
            val id = UUID.randomUUID().toString(); val key = secret(121)
            assertEquals(200, client.enrol(id, key).status.value)
            val screen = screen()
            val notification = notificationFixture(day)
            // 150 of each, though all 300 accepted items collapse to just two cluster identities.
            val mixed = List(100) { if (it % 2 == 0) screen else notification }
            repeat(3) { index ->
                val response = client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("mixed-$index", mixed))
                assertEquals(200, response.status.value)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(2, body.getValue("accepted").jsonPrimitive.int)
                assertEquals(98, body.getValue("duplicate").jsonPrimitive.int)
            }
            val installs = InstallStore(db, clock)
            val ledger = requireNotNull(installs.ledgerFor(UUID.fromString(id), day))
            assertEquals(300, ledger.accepted)
            assertEquals(294, ledger.duplicate)
            assertError(client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("301", listOf(notification))), 429, "budget_exhausted")
            assertEquals(200, client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("mixed-0", mixed)).status.value)
            assertEquals(ledger.copy(duplicate = 394), installs.ledgerFor(UUID.fromString(id), day))
            assertEquals(2, sql { it.select("SELECT count(*) FROM clusters") { row -> row.getInt(1) } })
            val stored = sql { it.select("SELECT skeleton::text FROM cluster_samples WHERE fingerprint = ?", notification.getValue("fingerprint").jsonPrimitive.content) { row -> row.getString(1) } }
            assertEquals(notification, Json.parseToJsonElement(requireNotNull(stored)))
            assertEquals(1, sql { it.select("SELECT count(*) FROM token_sightings_v5") { row -> row.getInt(1) } })
            // Rejected items never produce samples or token rows.
            val invalid = JsonObject(notification + ("channelId" to JsonPrimitive("PRIVATE TEXT")))
            assertEquals(422, client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("invalid", listOf(invalid))).status.value)
            assertEquals(2, sql { it.select("SELECT count(*) FROM cluster_samples") { row -> row.getInt(1) } })
            assertEquals(mapOf("bad_channel" to 1), installs.ledgerFor(UUID.fromString(id), day)?.rejectedByReason)

            val racingId = UUID.randomUUID().toString()
            assertEquals(200, client.enrol(racingId, key).status.value)
            val responses = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val tasks = List(4) { i -> async {
                    start.await()
                    client.signed(clock, racingId, key, HttpMethod.Post, "/v1/skeletons", batch("race-$i", mixed)).status.value
                } }
                start.complete(Unit); tasks.awaitAll()
            }
            assertEquals(listOf(200, 200, 200, 429), responses.sorted())
            assertEquals(300, installs.ledgerFor(UUID.fromString(racingId), day)?.accepted)
        } }
    }

    @Test
    fun `identity dimensions sample cap and kind conflict are enforced transactionally`() = runBlocking {
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            val id = UUID.randomUUID(); val key = hashSecret(secret(122))
            installs.enrol(id, key, "1.0")
            val store = SkeletonStore(db, clock)
            suspend fun ingest(raw: List<JsonObject>, batchId: String, date: LocalDate = day): IngestOutcome {
                val accepted = raw.map { SkeletonValidator.validate(it, notificationPolicy, date) as ItemVerdict.Accepted }
                return store.ingest(id, key, date, accepted, accepted.size - accepted.map { it.item.fingerprint }.toSet().size,
                    emptyMap(), batchId, 2000, BudgetPolicy(), clock.now())
            }
            val variants = listOf(notificationFixture(day), notificationFixture(day, channel = "other"),
                notificationFixture(day, platform = "uber"), notificationFixture(day, hash = "fedcba9876543210"))
            assertTrue(ingest(variants, "variants") is IngestOutcome.Stored)
            assertEquals(4, sql { it.select("SELECT count(*) FROM clusters") { row -> row.getInt(1) } })
            repeat(7) { offset ->
                val date = day.plusDays(offset.toLong()); instant = date.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC)
                assertTrue(ingest(listOf(notificationFixture(date, version = "8.0"), notificationFixture(date, version = "9.0")), "cap-$offset", date) is IngestOutcome.Stored)
            }
            val fp = variants.first().getValue("fingerprint").jsonPrimitive.content
            assertEquals(10, sql { it.select("SELECT count(*) FROM cluster_samples WHERE fingerprint = ?", fp) { row -> row.getInt(1) } })
            for (version in listOf("8.0", "9.0")) assertEquals(5, sql {
                it.select("SELECT count(*) FROM cluster_samples WHERE fingerprint = ? AND platform_app_version = ?", fp, version) { row -> row.getInt(1) }
            })
            // A conflicting persisted kind must roll back both storage and quota, even with a valid wire item.
            sql { it.update("UPDATE clusters SET kind = 'screen' WHERE fingerprint = ?", fp) }
            val before = installs.ledgerFor(id, day)
            val failure = runCatching { ingest(listOf(variants.first()), "conflict") }
            assertTrue(failure.isFailure)
            assertEquals(before, installs.ledgerFor(id, day))
            assertEquals("screen", sql { it.select("SELECT kind FROM clusters WHERE fingerprint = ?", fp) { row -> row.getString(1) } })
        }
    }

    @Test
    fun `channel and notes rehide after withdrawal cohort decline and loss of trusted exception`() = runBlocking {
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock); val store = SkeletonStore(db, clock)
            val ops = OpsStore(db, clock, notificationPolicy)
            val item = notificationAccepted(day); val key = hashSecret(secret(123))
            val ids = List(10) { UUID.randomUUID() }
            for ((index, id) in ids.withIndex()) {
                installs.enrol(id, key, "1.0")
                store.ingest(id, key, day, listOf(item), 0, emptyMap(), "gate", 1000, BudgetPolicy())
                if (index == 8) assertNull(ops.cluster(item.item.fingerprint)?.samples?.single()?.notification?.channelId)
            }
            ops.saveClassification(item.item.fingerprint, "idle", "GATED_NOTES")
            fun visible(row: OpsCluster?) = row?.samples?.single()?.notification?.channelId != null
            assertTrue(visible(ops.cluster(item.item.fingerprint)))
            assertTrue(installs.withdraw(ids.last(), key) is MutationOutcome.Applied)
            val hidden = requireNotNull(ops.cluster(item.item.fingerprint))
            assertFalse(visible(hidden)); assertNull(hidden.notes); assertTrue(hidden.notesWithheld)
            ops.trust(ids.first(), true)
            assertTrue(visible(ops.cluster(item.item.fingerprint)))
            ops.trust(ids.first(), false)
            assertFalse(visible(ops.cluster(item.item.fingerprint)))
            val replacement = UUID.randomUUID(); installs.enrol(replacement, key, "1.0")
            store.ingest(replacement, key, day, listOf(item), 0, emptyMap(), "gate", 1000, BudgetPolicy())
            assertTrue(visible(ops.cluster(item.item.fingerprint)))
            instant = instant.plusSeconds(28 * 86400L)
            assertFalse(visible(ops.cluster(item.item.fingerprint)))
            assertNull(ops.cluster(item.item.fingerprint)?.notes)
        }
    }

    @Test fun `configuration toggles off on off and advertised floor matches enrolment and mixed admission`() {
        Database.connect(config()).use { db ->
            for ((index, enabled) in listOf(false, true, false).withIndex()) testApplication {
                val configured = config().copy(notificationsEnabled=enabled, minimumFilterRev=2)
                application { module(configured, db, clock) }
                val published = Json.parseToJsonElement(client.get("/v1/policy").bodyAsText()).jsonObject
                assertEquals(2, published.getValue("minimumFilterRev").jsonPrimitive.int)
                assertEquals(if (enabled) 2 else 1, published.getValue("acceptedSchemaIds").jsonArray.size)
                val id = UUID.randomUUID().toString(); val key=secret(121)
                val enrol = Json.parseToJsonElement(client.enrol(id,key).bodyAsText()).jsonObject
                assertEquals(published, JsonObject(enrol - "installIdPrefix"))
                val screen = JsonObject(screen() + ("filterRev" to JsonPrimitive(2)))
                val notification = JsonObject(notificationFixture(day) + ("filterRev" to JsonPrimitive(2)))
                val items = List(4) { screen } + notification
                val response=client.signed(clock,id,key,HttpMethod.Post,"/v1/skeletons",batch("flag-$index",items))
                assertEquals(200,response.status.value)
                val ledger=InstallStore(db,clock).ledgerFor(UUID.fromString(id),day)!!
                assertEquals(if (enabled) emptyMap<String,Int>() else mapOf("unknown_schema" to 1),ledger.rejectedByReason)
                val old=List(4) { screen } + screen()
                assertEquals(200,client.signed(clock,id,key,HttpMethod.Post,"/v1/skeletons",batch("floor-$index",old)).status.value)
                assertEquals(1,InstallStore(db,clock).ledgerFor(UUID.fromString(id),day)!!.rejectedByReason["filter_rev_too_old"])
            }
        }
    }

    private fun screen(): JsonObject {
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to TextSlot("0123456789abcdef", "words:1")))
        val raw = """{"schemaId":"uinode.skeleton.v1","hashDomain":1,"filterRev":1,"fingerprint":"${CensusFingerprint.of(root)}","platform":"doordash","platformAppVersion":"8.0","engineVersion":1,"day":"$day","root":${SkeletonSchema.json.encodeToString(UiSkeletonNodeDto.serializer(), root)}}"""
        return Json.parseToJsonElement(raw).jsonObject
    }
    private fun batch(id: String, items: List<JsonObject>) = JsonObject(mapOf("batchId" to JsonPrimitive(id), "items" to JsonArray(items))).toString()
    private fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        private fun config() = Config.fromEnv(testEnvironment() + mapOf("DATABASE_URL" to postgres.jdbcUrl,
            "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password))
        @BeforeAll @JvmStatic fun migrate() { Database.migrate(config()) }
        @JvmStatic fun dockerAvailable(): Boolean = System.getenv("CI") == "true" || runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }
}
