package cloud.trotter.census.server.db

import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.assertPrivate
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.enrol
import cloud.trotter.census.server.healthFixture
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.ingest.HealthReportValidator
import cloud.trotter.census.server.ingest.HealthReportVerdict
import cloud.trotter.census.server.jobs.Alarm
import cloud.trotter.census.server.jobs.AlarmSink
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.module
import cloud.trotter.census.server.secret
import cloud.trotter.census.server.signed
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** #102: shadow-rule counts and refused probes ride the per-install health row and fold into the fleet row. */
class HealthShadowTest : LifecycleTestDatabase() {
    private val operatorToken = "OPERATOR_TOKEN_SENTINEL"
    private val rule = "doordash.screen.offer_shadow"
    private val probe = "doordash.probe.store_banner"
    private val first = "0123456789abcdef"
    private val second = "00000000000000aa"
    private val digest = "fedcba9876543210"

    private fun entry(generation: String = first, candidate: String = rule, digest: String = this.digest, metrics: Map<String, Int> = emptyMap()) =
        JsonObject(mapOf("generation" to JsonPrimitive(generation), "rule" to JsonPrimitive(candidate), "digest" to JsonPrimitive(digest)) +
            metrics.mapValues { JsonPrimitive(it.value) })
    private fun ref(generation: String = first) = entry(generation, probe)

    private fun reportJson(reportDay: LocalDate, shadow: List<JsonObject>? = null, probeRefused: List<JsonObject>? = null, version: String = "8.0.0") =
        JsonObject(healthFixture() + buildMap<String, JsonElement> {
            put("day", JsonPrimitive(reportDay.toString())); put("platformAppVersion", JsonPrimitive(version))
            shadow?.let { put("shadow", JsonArray(it)) }
            probeRefused?.let { put("probeRefused", JsonArray(it)) }
        })

    /** Through the contract, as the route accepts it on [reportDay] itself (retention tests store days the route no longer takes). */
    private fun report(reportDay: LocalDate, shadow: List<JsonObject>? = null, probeRefused: List<JsonObject>? = null): HealthReport =
        (HealthReportValidator.validate(reportJson(reportDay, shadow, probeRefused), reportDay, Policy()) as HealthReportVerdict.Accepted).report

    private suspend fun store(health: HealthStore, id: UUID, vararg reports: HealthReport) =
        assertEquals(HealthOutcome.Stored, health.upsert(id, key, day, reports.toList(), emptyMap(), 1, BudgetPolicy()))

    /** The per-install row's stored (shadow, probe_refused), parsed; null where the column is NULL. */
    private fun columns(id: UUID, reportDay: LocalDate): Pair<JsonElement?, JsonElement?> = sql { connection ->
        val tag = connection.testTag(TagPurpose.HEALTH, id, "", day, reportDay)
        connection.select("SELECT shadow::text, probe_refused::text FROM health_daily WHERE install_tag = ? AND day = ?", tag.tag, reportDay) {
            it.getString(1)?.let(Json::parseToJsonElement) to it.getString(2)?.let(Json::parseToJsonElement)
        }!!
    }

    /** The fleet row's folded shadow metrics as (generation, rule, digest, metric) to (total, installs). */
    private fun fleetShadow(fleetDay: LocalDate): Map<List<String>, Pair<Long, Long>> = sql { connection ->
        connection.select("SELECT shadow::text FROM health_fleet_daily WHERE day = ?", fleetDay) { it.getString(1) }!!
    }.let(Json::parseToJsonElement).jsonArray.associate { item ->
        val row = item.jsonObject
        listOf("generation", "rule", "digest", "metric").map { (row.getValue(it) as JsonPrimitive).content } to
            ((row.getValue("total") as JsonPrimitive).content.toLong() to (row.getValue("installs") as JsonPrimitive).content.toLong())
    }

    private fun fleetRefused(fleetDay: LocalDate): JsonElement = Json.parseToJsonElement(sql { connection ->
        connection.select("SELECT probe_refused::text FROM health_fleet_daily WHERE day = ?", fleetDay) { it.getString(1) }!!
    })

    private suspend fun HttpClient.ops(path: String): HttpResponse = request(path) {
        method = HttpMethod.Get
        header(HttpHeaders.Authorization, "Bearer $operatorToken")
    }

    @Test fun `a shadow report is accepted, stored and read back by ops, a legacy one still is, and neither raises an alarm`() = runBlocking {
        val alarms = mutableListOf<Alarm>()
        Database.connect(config()).use { db ->
            val evaluator = HealthAlarms(HealthStore(db, clock), clock, startedAt = clock.now(), sink = AlarmSink { alarms += it })
            testApplication {
                application { module(config().copy(operatorTokenSha256 = hashSecret(operatorToken)), db, clock, evaluator, policy = Policy()) }
                val (id, secret) = UUID.randomUUID().toString() to secret(41)
                assertEquals(HttpStatusCode.OK, client.enrol(id, secret).status)
                val shadow = listOf(
                    entry(metrics = mapOf("tied" to 3, "onUnknown" to 20, "maskFail" to 20)),
                    entry(second, metrics = mapOf("emptyParse" to 5)),
                )
                val reports = JsonArray(listOf(reportJson(day, shadow, listOf(ref())), reportJson(day, version = "8.0.1")))
                val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/health", JsonObject(mapOf("reports" to reports)).toString())
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(Json.parseToJsonElement("""{"status":"accepted","accepted":2,"rejected":{}}"""), Json.parseToJsonElement(response.bodyAsText()))
                assertEquals(JsonArray(shadow) to JsonArray(listOf(ref())), columns(UUID.fromString(id), day))

                val health = Json.parseToJsonElement(client.ops("/ops/health").bodyAsText()).jsonObject
                val rows = health.getValue("installs").jsonArray.map { it.jsonObject }.associateBy { (it.getValue("version") as JsonPrimitive).content }
                assertEquals(JsonArray(shadow), rows.getValue("8.0.0")["shadow"])
                assertEquals(JsonArray(listOf(ref())), rows.getValue("8.0.0")["probeRefused"])
                assertEquals(JsonNull, rows.getValue("8.0.1")["shadow"])
                assertEquals(JsonNull, rows.getValue("8.0.1")["probeRefused"])
                val fleet = health.getValue("fleet").jsonArray.map { it.jsonObject }.single { (it.getValue("platformAppVersion") as JsonPrimitive).content == "8.0.0" }
                assertEquals(4, fleet.getValue("shadow").jsonArray.size)
                assertEquals(listOf(JsonObject(ref() + ("installs" to JsonPrimitive(1)))), fleet.getValue("probeRefused").jsonArray.toList())

                val page = client.ops("/ops/").bodyAsText()
                assertTrue(page.contains("Tied with a live rule: 3 of the last 20 eligible visits for this metric"))
                assertTrue(page.contains("Required field empty: unavailable"))
                assertTrue(page.contains(rule) && page.contains(probe) && page.contains("Probe refused"))
                assertTrue(page.contains(first.take(8)) && page.contains(digest.take(8)))
                assertPrivate(page)
                assertEquals(emptyList<Alarm>(), alarms)
            }
        }
    }

    @Test fun `malformed shadow input is refused through the contract and stores nothing`() = runBlocking {
        Database.connect(config()).use { db ->
            testApplication {
                application { module(config(), db, clock, policy = Policy()) }
                val (id, secret) = UUID.randomUUID().toString() to secret(42)
                assertEquals(HttpStatusCode.OK, client.enrol(id, secret).status)
                val bad = listOf(
                    reportJson(day, listOf(entry(metrics = mapOf("tied" to 1)))),
                    reportJson(day, listOf(entry(metrics = mapOf("maskFail" to 21)))),
                    reportJson(day, listOf(entry(metrics = mapOf("maskFail" to 0)), entry(metrics = mapOf("maskFail" to 1)))),
                    reportJson(day, listOf(entry(candidate = "uber.screen.offer", metrics = mapOf("maskFail" to 0)))),
                    JsonObject(reportJson(day) + ("shadow" to JsonArray(listOf(JsonObject(entry(metrics = mapOf("maskFail" to 0)) + ("extra" to JsonPrimitive(1))))))),
                )
                val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/health", JsonObject(mapOf("reports" to JsonArray(bad))).toString())
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
                assertEquals(
                    Json.parseToJsonElement("""{"error":"batch_quality","rejected":{"bad_count":2,"bad_rule_id":2,"unknown_field":1}}"""),
                    Json.parseToJsonElement(response.bodyAsText()),
                )
                assertEquals(0L, count("health_daily"))
            }
        }
    }

    @Test fun `a retry replaces the stored snapshot and an omitted field clears it, in the fleet fold too`() = runBlocking {
        Database.connect(config()).use { db ->
            val health = HealthStore(db, clock)
            val id = install()
            store(health, id, report(day, listOf(entry(metrics = mapOf("maskFail" to 4))), listOf(ref())))
            assertEquals(mapOf(listOf(first, rule, digest, "maskFail") to (4L to 1L)), fleetShadow(day))

            val retry = listOf(entry(metrics = mapOf("tied" to 1, "onUnknown" to 0)))
            store(health, id, report(day, retry))
            assertEquals(JsonArray(retry) to null, columns(id, day))
            assertEquals(mapOf(listOf(first, rule, digest, "tied") to (1L to 1L), listOf(first, rule, digest, "onUnknown") to (0L to 1L)), fleetShadow(day))
            assertEquals(JsonArray(emptyList()), fleetRefused(day))

            store(health, id, report(day))
            assertEquals(null to null, columns(id, day))
            assertEquals(emptyMap<List<String>, Pair<Long, Long>>(), fleetShadow(day))
        }
    }

    @Test fun `two generations or digests of one rule on one day stay apart in storage and in the fleet fold`() = runBlocking {
        Database.connect(config()).use { db ->
            val health = HealthStore(db, clock)
            val (a, b) = install() to install()
            val other = "1111111111111111"
            store(health, a, report(day, listOf(entry(metrics = mapOf("maskFail" to 2)))))
            val both = listOf(
                entry(metrics = mapOf("maskFail" to 3)),
                entry(second, metrics = mapOf("maskFail" to 4)),
                entry(digest = other, metrics = mapOf("maskFail" to 5)),
            )
            store(health, b, report(day, both))
            assertEquals(JsonArray(both), columns(b, day).first)
            assertEquals(
                mapOf(
                    listOf(first, rule, digest, "maskFail") to (5L to 2L),
                    listOf(second, rule, digest, "maskFail") to (4L to 1L),
                    listOf(first, rule, other, "maskFail") to (5L to 1L),
                ),
                fleetShadow(day),
            )
        }
    }

    @Test fun `retention folds then deletes the shadow data, and a data deletion removes it and refolds`() = runBlocking {
        Database.connect(config()).use { db ->
            val health = HealthStore(db, clock)
            val installs = InstallStore(db, clock)
            val (a, b) = install() to install()
            val retention = Policy().retention
            val folded = day.minusDays(retention.healthDailyDays.toLong())
            val kept = folded.plusDays(1)
            val counts = listOf(entry(metrics = mapOf("maskFail" to 6)))
            for (id in listOf(a, b)) store(health, id, report(folded, counts, listOf(ref())), report(kept, counts, listOf(ref())))

            assertEquals(2, installs.purgeHealthDaily(folded))
            assertEquals(0L, sql { it.select("SELECT count(*) FROM health_daily WHERE day = ?", folded) { r -> r.getLong(1) }!! })
            assertEquals(mapOf(listOf(first, rule, digest, "maskFail") to (12L to 2L)), fleetShadow(folded))
            assertEquals(JsonArray(listOf(JsonObject(ref() + ("installs" to JsonPrimitive(2))))), fleetRefused(folded))

            assertTrue(installs.deleteData(a, key, day) is MutationOutcome.Applied)
            assertEquals(0L, sql { it.installRows("health_daily", a, null) })
            assertEquals(mapOf(listOf(first, rule, digest, "maskFail") to (6L to 1L)), fleetShadow(kept))
            assertEquals(JsonArray(listOf(JsonObject(ref() + ("installs" to JsonPrimitive(1))))), fleetRefused(kept))
            // The folded day exists only as fleet totals, which no deletion can reach or needs to.
            assertEquals(mapOf(listOf(first, rule, digest, "maskFail") to (12L to 2L)), fleetShadow(folded))
            assertNull(sql { it.select("SELECT 1 FROM health_daily WHERE shadow IS NOT NULL AND day = ?", folded) { true } })
        }
    }
}
