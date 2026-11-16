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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** #104: daily shadow-rule levels ride the per-install health row and fold into install-count histograms on the fleet row. */
class HealthShadowDailyTest : LifecycleTestDatabase() {
    private val operatorToken = "OPERATOR_TOKEN_SENTINEL"
    private val rule = "doordash.screen.offer_shadow"
    private val probe = "doordash.probe.store_banner"
    private val first = "0123456789abcdef"
    private val second = "00000000000000aa"
    private val digest = "fedcba9876543210"
    private val visited = mapOf("visits" to "1-4", "evaluated" to "5-19", "tied" to "none", "onUnknown" to "some")
    private val unvisited = mapOf("visits" to "0", "evaluated" to "20+")

    private fun ids(generation: String = first, candidate: String = rule, digest: String = this.digest) =
        mapOf("generation" to generation, "rule" to candidate, "digest" to digest)
    private fun entry(generation: String = first, candidate: String = rule, digest: String = this.digest, levels: Map<String, Any> = visited) =
        JsonObject((ids(generation, candidate, digest) + levels).mapValues { (_, value) ->
            if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toString())
        })
    private fun ref(generation: String = first) = JsonObject(ids(generation, probe).mapValues { JsonPrimitive(it.value) })

    private fun reportJson(
        reportDay: LocalDate, shadowDaily: List<JsonObject>? = null, probeRefused: List<JsonObject>? = null,
        version: String = "8.0.0", truncated: Boolean = false,
    ) = JsonObject(healthFixture() + buildMap<String, JsonElement> {
        put("day", JsonPrimitive(reportDay.toString())); put("platformAppVersion", JsonPrimitive(version))
        shadowDaily?.let { put("shadowDaily", JsonArray(it)) }
        if (truncated) put("shadowDailyTruncated", JsonPrimitive(true))
        probeRefused?.let { put("probeRefused", JsonArray(it)) }
    })

    /** Through the contract, as the route accepts it on [reportDay] itself (retention tests store days the route no longer takes). */
    private fun report(
        reportDay: LocalDate, shadowDaily: List<JsonObject>? = null, probeRefused: List<JsonObject>? = null,
        version: String = "8.0.0", truncated: Boolean = false,
    ): HealthReport = (HealthReportValidator.validate(reportJson(reportDay, shadowDaily, probeRefused, version, truncated), reportDay, Policy())
        as HealthReportVerdict.Accepted).report

    private suspend fun store(health: HealthStore, id: UUID, vararg reports: HealthReport) =
        assertEquals(HealthOutcome.Stored, health.upsert(id, key, day, reports.toList(), emptyMap(), 1, BudgetPolicy()))

    /** The per-install row's stored (shadow_daily, shadow_daily_truncated, probe_refused); null where the column is NULL. */
    private fun columns(id: UUID, reportDay: LocalDate, version: String = "8.0.0"): Triple<JsonElement?, Boolean?, JsonElement?> = sql { connection ->
        val tag = connection.testTag(TagPurpose.HEALTH, id, "", day, reportDay)
        connection.select(
            "SELECT shadow_daily::text, shadow_daily_truncated, probe_refused::text FROM health_daily WHERE install_tag = ? AND day = ? AND platform_app_version = ?",
            tag.tag, reportDay, version,
        ) { Triple(it.getString(1)?.let(Json::parseToJsonElement), it.getObject(2) as Boolean?, it.getString(3)?.let(Json::parseToJsonElement)) }!!
    }

    /** The fleet row's folded shadow levels as (generation, rule, digest) to {field: {level: installs}}. */
    private fun fleetShadow(fleetDay: LocalDate, version: String = "8.0.0"): Map<List<String>, JsonObject> = sql { connection ->
        connection.select("SELECT shadow_daily::text FROM health_fleet_daily WHERE day = ? AND platform_app_version = ?", fleetDay, version) { it.getString(1) }!!
    }.let(Json::parseToJsonElement).jsonArray.associate { item ->
        val row = item.jsonObject
        listOf("generation", "rule", "digest").map { (row.getValue(it) as JsonPrimitive).content } to JsonObject(row - "generation" - "rule" - "digest")
    }

    private fun fleetTruncated(fleetDay: LocalDate, version: String = "8.0.0"): Int = sql { connection ->
        connection.select("SELECT shadow_daily_truncated_installs FROM health_fleet_daily WHERE day = ? AND platform_app_version = ?", fleetDay, version) { it.getInt(1) }!!
    }

    private fun fleetRefused(fleetDay: LocalDate): JsonElement = Json.parseToJsonElement(sql { connection ->
        connection.select("SELECT probe_refused::text FROM health_fleet_daily WHERE day = ? AND platform_app_version = '8.0.0'", fleetDay) { it.getString(1) }!!
    })

    /** Install counts per field and level, as the fleet row stores them. */
    private fun histograms(vararg fields: Pair<String, Map<String, Int>>) =
        JsonObject(fields.associate { (field, levels) -> field to JsonObject(levels.mapValues { JsonPrimitive(it.value) }) })

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
                val shadowDaily = listOf(
                    entry(levels = visited + mapOf("visits" to "20+", "maskFail" to "half_or_more")),
                    entry(second, levels = unvisited),
                )
                val reports = JsonArray(listOf(reportJson(day, shadowDaily, listOf(ref()), truncated = true), reportJson(day, version = "8.0.1")))
                val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/health", JsonObject(mapOf("reports" to reports)).toString())
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(Json.parseToJsonElement("""{"status":"accepted","accepted":2,"rejected":{}}"""), Json.parseToJsonElement(response.bodyAsText()))
                assertEquals(Triple(JsonArray(shadowDaily), true, JsonArray(listOf(ref()))), columns(UUID.fromString(id), day))
                assertEquals(Triple(null, null, null), columns(UUID.fromString(id), day, "8.0.1"))

                val health = Json.parseToJsonElement(client.ops("/ops/health").bodyAsText()).jsonObject
                val rows = health.getValue("installs").jsonArray.map { it.jsonObject }.associateBy { (it.getValue("version") as JsonPrimitive).content }
                assertEquals(JsonArray(shadowDaily), rows.getValue("8.0.0")["shadowDaily"])
                assertEquals(JsonPrimitive(true), rows.getValue("8.0.0")["shadowDailyTruncated"])
                assertEquals(JsonArray(listOf(ref())), rows.getValue("8.0.0")["probeRefused"])
                for (field in listOf("shadowDaily", "shadowDailyTruncated", "probeRefused")) assertEquals(JsonNull, rows.getValue("8.0.1")[field])
                assertFalse("shadow" in rows.getValue("8.0.0"))
                val fleet = health.getValue("fleet").jsonArray.map { it.jsonObject }.associateBy { (it.getValue("platformAppVersion") as JsonPrimitive).content }
                assertEquals(2, fleet.getValue("8.0.0").getValue("shadowDaily").jsonArray.size)
                assertEquals(JsonPrimitive(1), fleet.getValue("8.0.0")["shadowDailyTruncatedInstalls"])
                assertEquals(JsonArray(emptyList()), fleet.getValue("8.0.1")["shadowDaily"])
                assertEquals(JsonPrimitive(0), fleet.getValue("8.0.1")["shadowDailyTruncatedInstalls"])
                assertEquals(listOf(JsonObject(ref() + ("installs" to JsonPrimitive(1)))), fleet.getValue("8.0.0").getValue("probeRefused").jsonArray.toList())

                val page = client.ops("/ops/").bodyAsText()
                assertTrue(page.contains("Visits: no recorded visits"))
                assertTrue(page.contains("Visits: 20+"))
                assertTrue(page.contains("Frames evaluated: 5-19"))
                assertTrue(page.contains("Tied with a live rule: none of the visits"))
                assertTrue(page.contains("On an UNKNOWN frame: under half of the visits"))
                assertTrue(page.contains("Value survived the masks: half or more of the visits"))
                assertTrue(page.contains("Required field empty: unavailable"))
                assertTrue(page.contains("Installs reporting this day"))
                assertTrue(page.contains("no recorded visits: 1"))
                assertTrue(page.contains("unavailable: 1"))
                assertTrue(page.contains("Truncated"))
                assertTrue(page.contains(rule) && page.contains(probe) && page.contains("Probe refused"))
                assertTrue(page.contains(first.take(8)) && page.contains(second.take(8)) && page.contains(digest.take(8)))
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
                    reportJson(day, listOf(entry(levels = visited - "onUnknown"))),
                    reportJson(day, listOf(entry(levels = visited + ("maskFail" to 3)))),
                    reportJson(day, listOf(entry(levels = unvisited + ("tied" to "none") + ("onUnknown" to "none")))),
                    reportJson(day, listOf(entry(), entry(levels = visited + ("maskFail" to "all")))),
                    reportJson(day, listOf(entry(candidate = "uber.screen.offer"))),
                    JsonObject(reportJson(day) + ("shadowDaily" to JsonArray(listOf(JsonObject(entry() + ("extra" to JsonPrimitive(1))))))),
                    JsonObject(reportJson(day) + ("shadow" to JsonArray(emptyList()))),
                )
                val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/health", JsonObject(mapOf("reports" to JsonArray(bad))).toString())
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
                assertEquals(
                    Json.parseToJsonElement("""{"error":"batch_quality","rejected":{"bad_count":3,"bad_rule_id":2,"unknown_field":2}}"""),
                    Json.parseToJsonElement(response.bodyAsText()),
                )
                assertEquals(0L, count("health_daily"))
            }
        }
    }

    @Test fun `a retry replaces the stored snapshot and an omitted field or flag clears it, in the fleet fold too`() = runBlocking {
        Database.connect(config()).use { db ->
            val health = HealthStore(db, clock)
            val id = install()
            store(health, id, report(day, listOf(entry(levels = visited + ("maskFail" to "all"))), listOf(ref()), truncated = true))
            assertEquals(
                mapOf(ids().values.toList() to histograms("visits" to mapOf("1-4" to 1), "evaluated" to mapOf("5-19" to 1),
                    "tied" to mapOf("none" to 1), "onUnknown" to mapOf("some" to 1), "maskFail" to mapOf("all" to 1))),
                fleetShadow(day),
            )
            assertEquals(1, fleetTruncated(day))

            val retry = listOf(entry(levels = unvisited))
            store(health, id, report(day, retry))
            assertEquals(Triple(JsonArray(retry), null, null), columns(id, day))
            assertEquals(mapOf(ids().values.toList() to histograms("visits" to mapOf("0" to 1), "evaluated" to mapOf("20+" to 1))), fleetShadow(day))
            assertEquals(0, fleetTruncated(day))
            assertEquals(JsonArray(emptyList()), fleetRefused(day))

            store(health, id, report(day))
            assertEquals(Triple(null, null, null), columns(id, day))
            assertEquals(emptyMap<List<String>, JsonObject>(), fleetShadow(day))
        }
    }

    @Test fun `two generations, two digests and two versions on one day stay apart in storage and in the fleet fold`() = runBlocking {
        Database.connect(config()).use { db ->
            val health = HealthStore(db, clock)
            val (a, b) = install() to install()
            val other = "1111111111111111"
            store(health, a, report(day, listOf(entry())))
            val both = listOf(entry(levels = unvisited), entry(second), entry(digest = other, levels = visited + ("visits" to "5-19")))
            store(health, b, report(day, both), report(day, listOf(entry(levels = visited + ("visits" to "20+"))), version = "8.0.1", truncated = true))
            assertEquals(JsonArray(both), columns(b, day).first)
            assertEquals(
                mapOf(
                    ids().values.toList() to histograms("visits" to mapOf("0" to 1, "1-4" to 1), "evaluated" to mapOf("5-19" to 1, "20+" to 1),
                        "tied" to mapOf("none" to 1), "onUnknown" to mapOf("some" to 1)),
                    ids(second).values.toList() to histograms("visits" to mapOf("1-4" to 1), "evaluated" to mapOf("5-19" to 1),
                        "tied" to mapOf("none" to 1), "onUnknown" to mapOf("some" to 1)),
                    ids(digest = other).values.toList() to histograms("visits" to mapOf("5-19" to 1), "evaluated" to mapOf("5-19" to 1),
                        "tied" to mapOf("none" to 1), "onUnknown" to mapOf("some" to 1)),
                ),
                fleetShadow(day),
            )
            assertEquals(0, fleetTruncated(day))
            assertEquals(
                mapOf(ids().values.toList() to histograms("visits" to mapOf("20+" to 1), "evaluated" to mapOf("5-19" to 1),
                    "tied" to mapOf("none" to 1), "onUnknown" to mapOf("some" to 1))),
                fleetShadow(day, "8.0.1"),
            )
            assertEquals(1, fleetTruncated(day, "8.0.1"))
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
            val levels = listOf(entry())
            for (id in listOf(a, b)) {
                store(health, id, report(folded, levels, listOf(ref()), truncated = true), report(kept, levels, listOf(ref()), truncated = true))
            }
            val twoInstalls = histograms("visits" to mapOf("1-4" to 2), "evaluated" to mapOf("5-19" to 2), "tied" to mapOf("none" to 2), "onUnknown" to mapOf("some" to 2))
            val oneInstall = histograms("visits" to mapOf("1-4" to 1), "evaluated" to mapOf("5-19" to 1), "tied" to mapOf("none" to 1), "onUnknown" to mapOf("some" to 1))

            assertEquals(2, installs.purgeHealthDaily(folded))
            assertEquals(0L, sql { it.select("SELECT count(*) FROM health_daily WHERE day = ?", folded) { r -> r.getLong(1) }!! })
            assertEquals(mapOf(ids().values.toList() to twoInstalls), fleetShadow(folded))
            assertEquals(2, fleetTruncated(folded))
            assertEquals(JsonArray(listOf(JsonObject(ref() + ("installs" to JsonPrimitive(2))))), fleetRefused(folded))

            assertTrue(installs.deleteData(a, key, day) is MutationOutcome.Applied)
            assertEquals(0L, sql { it.installRows("health_daily", a, null) })
            assertEquals(mapOf(ids().values.toList() to oneInstall), fleetShadow(kept))
            assertEquals(1, fleetTruncated(kept))
            assertEquals(JsonArray(listOf(JsonObject(ref() + ("installs" to JsonPrimitive(1))))), fleetRefused(kept))
            // The folded day exists only as fleet totals, which no deletion can reach or needs to.
            assertEquals(mapOf(ids().values.toList() to twoInstalls), fleetShadow(folded))
            assertEquals(2, fleetTruncated(folded))
            assertNull(sql { it.select("SELECT 1 FROM health_daily WHERE (shadow_daily IS NOT NULL OR shadow_daily_truncated IS NOT NULL) AND day = ?", folded) { true } })
        }
    }
}
