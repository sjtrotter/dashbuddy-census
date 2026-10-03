package cloud.trotter.census.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.db.CLUSTER_STATUSES
import cloud.trotter.census.server.db.HealthStore
import cloud.trotter.census.server.db.IngestOutcome
import cloud.trotter.census.server.db.InstallStore
import cloud.trotter.census.server.db.OpsStore
import cloud.trotter.census.server.db.SkeletonStore
import cloud.trotter.census.server.db.update
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.jobs.AlarmSink
import cloud.trotter.census.server.jobs.HealthAlarms
import cloud.trotter.census.server.ops.Totp
import cloud.trotter.census.server.ops.opsLogPath
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
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker unavailable: operator tests skipped")
class OpsRoutesTest {
    private var instant = Instant.parse("2026-10-02T12:00:00Z")
    private val clock = object : Clock { override fun now(): Instant = instant }
    private val day = LocalDate.of(2026, 10, 2)

    @BeforeEach
    fun clean() {
        sql { it.update("TRUNCATE installs, clusters, health_fleet_daily, vocabulary CASCADE") }
    }

    @Test
    fun `operator bearer and TOTP fail closed with exactly one private log line per request`() {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            Database.connect(config()).use { db ->
                testApplication {
                    environment { log = LoggerFactory.getLogger("io.ktor.server.Application") }
                    application { module(config(), db, clock) }
                    startApplication()
                    logs.list.clear()
                    assertError(client.ops("/ops/clusters?token=QUERY_SENTINEL", token = null), 401, "unauthorized")
                    assertError(client.ops("/ops/installs/12345678-1234-4234-8234-123456789abc/trust", "{}", token = "WRONG_TOKEN_SENTINEL"), 401, "unauthorized")
                    assertError(client.ops("/ops/clusters/" + "a".repeat(64) + "/status", "{}"), 503, "totp_unconfigured")
                    assertError(client.ops("/ops/UNKNOWN_PATH_SENTINEL", token = null), 401, "unauthorized")
                }
            }
            val infos = logs.list.filter { it.level == Level.INFO && it.formattedMessage.contains("method=") }
            assertEquals(4, infos.size)
            assertTrue(infos.all { it.loggerName == "Ops" })
            assertTrue(infos.all { Regex("ops method=(GET|POST) path=/ops(/clusters|/installs)? status=(401|503)").matches(it.formattedMessage) })
            assertPrivateLogs(logs.list, listOf(operatorToken, "QUERY_SENTINEL", "WRONG_TOKEN_SENTINEL", "UNKNOWN_PATH_SENTINEL", "12345678-1234-4234-8234-123456789abc", "a".repeat(64)))
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `operator reads gates mutations ledger and vocabulary follow stored sightings`() {
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            val skeletons = SkeletonStore(db, clock)
            val ops = OpsStore(db, clock, Policy())
            val eligible = fixture("Eligible", "Ready now")
            val hidden = fixture("Hidden", "Wait here")
            val trusted = fixture("Trusted", "Drive now")
            val identities = (0..10).map { UUID.randomUUID() }
            val key = secret(73)
            testApplication {
                application { module(config().copy(operatorTotpSecret = totpSecret), db, clock) }
                for ((index, id) in identities.withIndex()) {
                    installs.enrol(id, hashSecret(key), "1.0.0")
                    val items = when {
                        index < 9 -> listOf(eligible, hidden)
                        index == 9 -> listOf(eligible)
                        else -> listOf(trusted)
                    }
                    assertTrue(skeletons.ingest(id, hashSecret(key), day, items, 0, emptyMap(), "seed-$index", 100, BudgetPolicy()) is IngestOutcome.Stored)
                }
                assertTrue(ops.trust(identities.last(), true))
                val list = Json.parseToJsonElement(client.ops("/ops/clusters").bodyAsText()).jsonArray
                val rows = list.single().jsonObject.getValue("clusters").jsonArray.map { it.jsonObject }
                assertEquals(3, rows.size)
                val byFingerprint = rows.associateBy { it.getValue("fingerprint").jsonPrimitive.content }
                assertTrue(byFingerprint.getValue(eligible.item.fingerprint).getValue("unblinded").jsonPrimitive.boolean)
                assertFalse(byFingerprint.getValue(hidden.item.fingerprint).getValue("unblinded").jsonPrimitive.boolean)
                val own = byFingerprint.getValue(trusted.item.fingerprint)
                assertTrue(own.getValue("unblinded").jsonPrimitive.boolean)
                assertEquals(0, own.getValue("distinctInstalls28d").jsonPrimitive.int)
                for ((item, label, visible) in listOf(Triple(eligible, "Eligible", true), Triple(hidden, "Hidden", false), Triple(trusted, "Trusted", true))) {
                    val detail = client.ops("/ops/clusters/${item.item.fingerprint}").bodyAsText()
                    assertEquals(visible, detail.contains("android.widget.$label"))
                }
                val page = client.ops("/ops/")
                assertEquals("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'", page.headers["Content-Security-Policy"])
                val html = page.bodyAsText()
                assertFalse(html.contains("android.widget.Eligible"))
                assertFalse(html.contains("android.widget.Trusted"))
                assertFalse(html.contains("android.widget.Hidden"))
                assertFalse(html.contains("~" + hashSecret("android.widget.Hidden").take(8)))
                for ((item, label, visible) in listOf(Triple(eligible, "Eligible", true), Triple(hidden, "Hidden", false), Triple(trusted, "Trusted", true))) {
                    val response = client.ops("/ops/clusters/${item.item.fingerprint}/view")
                    assertEquals(200, response.status.value)
                    assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
                    assertEquals(page.headers["Content-Security-Policy"], response.headers["Content-Security-Policy"])
                    val detail = response.bodyAsText()
                    assertEquals(visible, detail.contains("android.widget.$label"))
                    if (!visible) assertTrue(detail.contains("~" + hashSecret("android.widget.$label").take(8)))
                    assertTrue(detail.contains("href=\"/ops/clusters/${item.item.fingerprint}\""))
                }
                assertFalse(html.contains(operatorToken))
                val installBody = client.ops("/ops/installs").bodyAsText()
                assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(installBody))
                identities.forEach { assertFalse(installBody.contains(it.toString())) }
                val ledger = Json.parseToJsonElement(client.ops("/ops/ledger").bodyAsText()).jsonObject
                val totals = ledger.getValue("totals").jsonObject
                assertEquals(1100, totals.getValue("bytes").jsonPrimitive.int)
                assertEquals(20, totals.getValue("accepted").jsonPrimitive.int)
                assertEquals(11, totals.getValue("batches").jsonPrimitive.int)
                assertEquals(0, totals.getValue("duplicate").jsonPrimitive.int)
                assertEquals(JsonObject(emptyMap()), totals.getValue("rejected"))

                val statusPath = "/ops/clusters/${eligible.item.fingerprint}/status"
                val statusBody = """{"status":"resolved","resolvedRuleId":"doordash.screen.offer","notes":"reviewed"}"""
                assertError(client.ops(statusPath, statusBody), 401, "totp_required")
                assertError(client.ops(statusPath, statusBody, code = "abcdef"), 401, "totp_required")
                assertError(client.ops(statusPath, statusBody, code = "000000"), 401, "totp_required")
                val code = Totp.code(totpSecret, instant.epochSecond)
                assertEquals(204, client.ops(statusPath, statusBody, code = code).status.value)
                assertError(client.ops(statusPath, statusBody, code = code), 401, "totp_replayed")
                assertEquals("resolved", ops.cluster(eligible.item.fingerprint)?.status)
                assertEquals("reviewed", ops.cluster(eligible.item.fingerprint)?.notes)
                assertEquals("doordash.screen.offer", ops.cluster(eligible.item.fingerprint)?.resolvedRuleId)

                val id = identities.first().toString()
                val envelope = """{"batchId":"ops-envelope","items":[${envelopeFixture()}]}"""
                assertError(client.signed(clock, id, key, HttpMethod.Post, "/v1/envelopes", envelope), 403, "not_trusted")
                assertEquals(204, client.mutate("/ops/installs/$id/trust", """{"trusted":true}""").status.value)
                assertEquals(200, client.signed(clock, id, key, HttpMethod.Post, "/v1/envelopes", envelope).status.value)
                assertEquals(204, client.mutate("/ops/installs/$id/trust", """{"trusted":false}""").status.value)
                assertError(client.signed(clock, id, key, HttpMethod.Post, "/v1/envelopes", envelope), 403, "not_trusted")

                val queue = Json.parseToJsonElement(client.ops("/ops/vocabulary/queue").bodyAsText()).jsonArray
                assertEquals(listOf(CensusHash.of("Ready now")), queue.map { it.jsonObject.getValue("tokenHash").jsonPrimitive.content })
                val display = ops.vocabularyQueueDisplay()
                assertEquals(1, display.size)
                assertEquals("words:2", display.single().kind)
                assertEquals(10, display.single().distinctInstalls)
                assertEquals(day.toString(), display.single().firstDay)
                assertEquals(day.toString(), display.single().lastDay)
                val tokenHash = CensusHash.of("Ready now")
                assertError(client.mutate("/ops/vocabulary/resolve", """{"tokenHash":"$tokenHash","clearText":"Wrong text","source":"corpus","reject":false}"""), 422, "hash_mismatch")
                assertEquals(204, client.mutate("/ops/vocabulary/resolve", """{"tokenHash":"$tokenHash","clearText":"Ready now","source":"corpus","reject":false}""").status.value)
                sql { connection -> connection.prepareStatement("SELECT status, clear_text FROM vocabulary WHERE token_hash = ?").use { statement ->
                    statement.setString(1, tokenHash)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next()); assertEquals("unblinded", result.getString("status")); assertEquals("Ready now", result.getString("clear_text"))
                    }
                } }
                assertEquals("[]", client.ops("/ops/vocabulary/queue").bodyAsText())
                assertEquals(204, client.mutate("/ops/installs/$id/revoke", "").status.value)
                assertEquals(204, client.mutate("/ops/installs/$id/revoke", "").status.value)
                assertError(client.signed(clock, id, key, HttpMethod.Get, "/v1/me"), 401, "revoked")
                assertError(client.mutate("/ops/installs/${UUID.randomUUID()}/trust", """{"trusted":true}"""), 404, "not_found")
                assertError(client.mutate("/ops/clusters/${"f".repeat(64)}/status", """{"status":"new"}"""), 404, "not_found")
                assertError(client.ops("/ops/clusters?limit=201"), 400, "bad_request")
                assertError(client.ops("/ops/health?days=91"), 400, "bad_request")
                assertError(client.ops("/ops/ledger?day=invalid"), 400, "bad_request")
            }
        }
    }

    @Test
    fun `health and alarms expose counters and delivered alarms reset on UTC day change`() {
        Database.connect(config()).use { db ->
            val alarms = HealthAlarms(HealthStore(db, clock), clock, AlarmSink { })
            val id = UUID.randomUUID().toString()
            val key = secret(74)
            testApplication {
                application { module(config(), db, clock, alarms) }
                assertEquals(200, client.enrol(id, key).status.value)
                val report = JsonObject(healthFixture() + mapOf("day" to JsonPrimitive(day.toString()), "trips" to JsonPrimitive(1)))
                assertEquals(200, client.signed(clock, id, key, HttpMethod.Post, "/v1/health", """{"reports":[$report]}""").status.value)
                val health = Json.parseToJsonElement(client.ops("/ops/health").bodyAsText()).jsonObject
                assertEquals(1, health.getValue("fleet").jsonArray.size)
                assertEquals(id.take(8), health.getValue("installs").jsonArray.single().jsonObject.getValue("installIdPrefix").jsonPrimitive.content)
                val result = Json.parseToJsonElement(client.ops("/ops/alarms").bodyAsText()).jsonObject
                assertEquals("trips", result.getValue("today").jsonArray.single().jsonObject.getValue("kind").jsonPrimitive.content)
                assertEquals(1, result.getValue("counts").jsonObject.getValue("trips").jsonPrimitive.int)
                val copy = alarms.raisedToday()
                assertEquals(1, copy.size)
                instant = instant.plusSeconds(86400)
                assertTrue(alarms.raisedToday().isEmpty())
                assertEquals(1, copy.size)
            }
        }
    }

    @Test
    fun `numeric version groups ranking filters and live trust gate are computed on reads`() {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val skeletons = SkeletonStore(db, clock)
            val ops = OpsStore(db, clock, Policy(k = 2))
            val old = fixture("Old", "Old text", "8.9")
            val carried = fixture("Old", "Old text", "8.10")
            val fresh = fixture("Fresh", "Fresh text", "8.10")
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val keyHash = hashSecret(secret(75))
            testApplication {
                application { module(config(), db, clock) }
                for (id in listOf(first, second)) store.enrol(id, keyHash, "1.0.0")
                assertTrue(skeletons.ingest(first, keyHash, day.minusDays(1), listOf(old), 0, emptyMap(), "old", 100, BudgetPolicy()) is IngestOutcome.Stored)
                assertTrue(skeletons.ingest(first, keyHash, day, listOf(carried, fresh), 0, emptyMap(), "first", 100, BudgetPolicy()) is IngestOutcome.Stored)
                assertTrue(skeletons.ingest(second, keyHash, day, listOf(fresh), 0, emptyMap(), "second", 100, BudgetPolicy()) is IngestOutcome.Stored)
                val groups = ops.clusters()
                assertEquals(listOf("8.10", "8.9"), groups.map { it.platformAppVersion })
                assertEquals(fresh.item.fingerprint, groups.first().clusters.first().fingerprint)
                assertTrue(groups.first().clusters.first().newWithVersion)
                assertFalse(groups.first().clusters.last().newWithVersion)
                assertEquals(listOf("8.9"), ops.clusters(version = "8.9").map { it.platformAppVersion })
                assertEquals(1, ops.clusters(limit = 1).sumOf { it.clusters.size })
                assertTrue(ops.cluster(fresh.item.fingerprint)?.unblinded == true)
                assertTrue(ops.trust(first, true))
                val trusted = requireNotNull(ops.cluster(fresh.item.fingerprint))
                assertEquals(1, trusted.distinctInstalls28d)
                assertTrue(trusted.seenByTrusted)
                assertTrue(trusted.unblinded)
                assertTrue(ops.trust(first, false))
                assertEquals(2, ops.cluster(fresh.item.fingerprint)?.distinctInstalls28d)
                // Round 1 (Astra): an annotation can quote a label — below k it is withheld from every JSON read.
                assertTrue(ops.status(fresh.item.fingerprint, "ignored", null, "seen by k installs"))
                assertEquals("seen by k installs", requireNotNull(ops.cluster(fresh.item.fingerprint)).notes)
                assertTrue(ops.status(old.item.fingerprint, "triaged", null, "android.widget.Hidden com.example:id/Hidden"))
                val below = requireNotNull(ops.cluster(old.item.fingerprint))
                assertFalse(below.unblinded)
                assertNull(below.notes)
                assertTrue(below.notesWithheld)
                assertEquals(listOf(fresh.item.fingerprint), ops.clusters(status = "ignored").flatMap { it.clusters }.map { it.fingerprint })
                assertTrue(ops.status(old.item.fingerprint, "new", null, null))
                assertTrue(ops.status(fresh.item.fingerprint, "resolved", null, null))
                val noVersion = "e".repeat(64)
                val uber = "f".repeat(64)
                sql { connection ->
                    connection.update("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (?, 'doordash', ?, ?)", noVersion, day, day)
                    connection.update("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day, status) VALUES (?, 'uber', ?, ?, 'ignored')", uber, day, day)
                    connection.update("INSERT INTO cluster_sightings (fingerprint, install_id, day, platform_app_version) VALUES (?, ?, ?, '1.2')", uber, first, day)
                }
                val summary = ops.clusterSummary()
                assertEquals(listOf("doordash" to "8.10", "doordash" to "8.9", "doordash" to null, "uber" to "1.2"),
                    summary.map { it.platform to it.platformAppVersion })
                assertEquals(listOf(2, 1, 1, 1), summary.map { it.total })
                assertEquals(listOf(listOf(1, 0, 0, 1, 0), listOf(1, 0, 0, 0, 0), listOf(1, 0, 0, 0, 0), listOf(0, 0, 0, 0, 1)),
                    summary.map { row -> CLUSTER_STATUSES.map { row.byStatus.getValue(it) } })
                summary.forEach { assertEquals(CLUSTER_STATUSES.toSet(), it.byStatus.keys) }
                val review = ops.clustersPage("doordash", "8.10", null, 1)
                assertEquals(listOf(old.item.fingerprint, fresh.item.fingerprint), review.clusters.map { it.fingerprint })
                assertEquals(listOf(false, true), review.clusters.map { it.newWithVersion })
                assertTrue(review.clusters.all { it.samples == null })
                assertEquals(2, review.total)
                assertEquals(25, review.pageSize)
                assertEquals(1, review.pageCount)
                // JSON still ranks by score, even though review puts the lower-scoring new cluster first.
                assertEquals(fresh.item.fingerprint, ops.clusters(platform = "doordash").first().clusters.first().fingerprint)
                val lastPage = ops.clustersPage("doordash", "8.10", null, 99, pageSize = 1)
                assertEquals(2, lastPage.page)
                assertEquals(2, lastPage.pageCount)
                assertEquals(2, lastPage.total)
                assertEquals(1, lastPage.pageSize)
                assertEquals(listOf(fresh.item.fingerprint), lastPage.clusters.map { it.fingerprint })
                assertEquals(1, ops.clustersPage("doordash", "8.10", null, 0, pageSize = 1).page)
                val filtered = ops.clustersPage("doordash", "8.10", "resolved", 99)
                assertEquals("resolved", filtered.status)
                assertEquals(1, filtered.total)
                assertEquals(1, filtered.page)
                assertEquals(listOf(fresh.item.fingerprint), filtered.clusters.map { it.fingerprint })
                val missingVersion = ops.clustersPage("doordash", null, null, 1)
                assertEquals(listOf(noVersion), missingVersion.clusters.map { it.fingerprint })
                assertFalse(missingVersion.clusters.single().newWithVersion)
                assertNull(missingVersion.clusters.single().samples)
                val emptyPage = ops.clustersPage("uber", null, null, 99)
                assertTrue(emptyPage.clusters.isEmpty())
                assertEquals(0, emptyPage.total)
                assertEquals(1, emptyPage.page)
                assertEquals(1, emptyPage.pageCount)
            }
        }
    }

    @Test
    fun `one ops bucket limits all read endpoints together`() {
        Database.connect(config()).use { db ->
            testApplication {
                application { module(config(), db, clock) }
                repeat(60) { index ->
                    val path = if (index % 2 == 0) "/ops/installs" else "/ops/alarms"
                    assertEquals(200, client.ops(path).status.value)
                }
                assertError(client.ops("/ops/clusters"), 429, "rate_limited")
            }
        }
    }

    @Test
    fun `failed TOTP attempts spend the ops bucket and an exhausted bucket never consumes a valid code`() {
        Database.connect(config()).use { db ->
            testApplication {
                application { module(config().copy(operatorTotpSecret = totpSecret), db, clock) }
                val path = "/ops/clusters/" + "a".repeat(64) + "/status"
                val body = """{"status":"triaged"}"""
                val statuses = (1..100).map { client.ops(path, body, code = "000000").status.value }
                assertEquals(60, statuses.count { it == 401 }, "a wrong second factor spends a token")
                assertEquals(40, statuses.count { it == 429 })
                instant = instant.plusSeconds(120) // full refill
                repeat(60) { assertEquals(200, client.ops("/ops/alarms").status.value) }
                val code = Totp.code(totpSecret, instant.epochSecond)
                assertError(client.ops(path, body, code = code), 429, "rate_limited")
                instant = instant.plusSeconds(1) // one token back; the same code is still inside its 30 s step
                assertEquals(404, client.ops(path, body, code = code).status.value, "the refused request must not have consumed the code")
            }
        }
    }

    @Test
    fun `session cookie opens the dashboard with its logout form`() {
        Database.connect(config()).use { db ->
            testApplication {
                application { module(config().copy(operatorTotpSecret = totpSecret), db, clock) }
                val browser = createClient { followRedirects = false }
                val login = browser.post("/ops/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(listOf("token" to operatorToken, "code" to Totp.code(totpSecret, instant.epochSecond)).formUrlEncode())
                }
                assertEquals(303, login.status.value)
                val cookie = requireNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
                val page = browser.get("/ops/") { header(HttpHeaders.Cookie, cookie) }
                assertEquals(200, page.status.value)
                assertEquals(ContentType.Text.Html, page.contentType()?.withoutParameters())
                val html = page.bodyAsText()
                assertEquals(1, Regex("<form\\b").findAll(html).count())
                assertTrue(html.contains("action=\"/ops/logout\""))
                assertTrue(html.contains("method=\"post\""))
                assertTrue(html.contains("Log out"))
                assertFalse(html.contains("<script"))
            }
        }
    }

    @Test
    fun `cluster detail accepts bearer and cookie with HTML CSP while JSON is unchanged`() {
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            val skeletons = SkeletonStore(db, clock)
            val ops = OpsStore(db, clock, Policy())
            val sample = fixture("Detail", "Only kind is displayed", "1.0.0")
            val id = UUID.randomUUID()
            val key = secret(79)
            testApplication {
                application { module(config().copy(operatorTotpSecret = totpSecret), db, clock) }
                installs.enrol(id, hashSecret(key), "1.0.0")
                assertTrue(skeletons.ingest(id, hashSecret(key), day, listOf(sample), 0, emptyMap(), "detail-seed", 100, BudgetPolicy()) is IngestOutcome.Stored)
                val browser = createClient { followRedirects = false }
                val login = browser.post("/ops/login") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(listOf("token" to operatorToken, "code" to Totp.code(totpSecret, instant.epochSecond)).formUrlEncode())
                }
                assertEquals(303, login.status.value)
                val cookie = requireNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
                val path = "/ops/clusters/${sample.item.fingerprint}"
                val jsonBefore = client.ops(path)
                assertEquals(200, jsonBefore.status.value)
                assertEquals(ContentType.Application.Json, jsonBefore.contentType()?.withoutParameters())
                val expected = Json.parseToJsonElement(jsonBefore.bodyAsText()).jsonObject
                assertEquals(sample.item.fingerprint, expected.getValue("fingerprint").jsonPrimitive.content)
                assertEquals(1, expected.getValue("samples").jsonArray.size)
                for (useCookie in listOf(false, true)) {
                    for ((requestedPath, status) in listOf(
                        "$path/view" to 200,
                        "/ops/clusters/${"f".repeat(64)}/view" to 404,
                        "/ops/clusters/invalid/view" to 404,
                        "/ops/clusters/${"a".repeat(63)}/view" to 404,
                    )) {
                        val response = browser.get(requestedPath) {
                            if (useCookie) header(HttpHeaders.Cookie, cookie) else header(HttpHeaders.Authorization, "Bearer $operatorToken")
                        }
                        assertEquals(status, response.status.value)
                        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
                        assertEquals("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'", response.headers["Content-Security-Policy"])
                        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                        val html = response.bodyAsText()
                        assertTrue(html.contains("href=\"/ops/#clusters\""))
                        assertTrue(html.contains(if (status == 404) "Cluster not found" else "Skeleton samples"))
                        assertEquals(1, Regex("<style\\b").findAll(html).count())
                        assertFalse(html.contains(operatorToken))
                    }
                    for ((query, status) in listOf(
                        "platform=doordash&version=1.0.0" to 200,
                        "platform=doordash&version=none" to 200,
                        "platform=Bad!&version=1.0.0" to 400,
                        "platform=doordash&version=1.0.0.0.0" to 400,
                        "platform=doordash&version=1.0.0&status=bogus" to 400,
                        "platform=doordash&version=1.0.0&page=0" to 400,
                        "platform=doordash&version=1.0.0&page=10001" to 400,
                        "platform=doordash&version=1.0.0&page=oops" to 400,
                        "platform=doordash&version=1.0.0&page=" to 400,
                        "version=1.0.0" to 400,
                        "platform=doordash" to 400,
                    )) {
                        val response = browser.get("/ops/clusters/view?$query") {
                            if (useCookie) header(HttpHeaders.Cookie, cookie) else header(HttpHeaders.Authorization, "Bearer $operatorToken")
                        }
                        assertEquals(status, response.status.value, query)
                        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
                        assertEquals("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'", response.headers["Content-Security-Policy"])
                        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                        val html = response.bodyAsText()
                        when {
                            status == 400 -> assertTrue(html.contains("Invalid cluster filter"))
                            query.endsWith("none") -> assertTrue(html.contains("No clusters match this filter."))
                            else -> {
                                assertTrue(html.contains("Cluster 01"))
                                assertTrue(html.contains("href=\"$path/view\""))
                            }
                        }
                        assertPrivate(html)
                    }
                    val json = browser.get(path) {
                        if (useCookie) header(HttpHeaders.Cookie, cookie) else header(HttpHeaders.Authorization, "Bearer $operatorToken")
                    }
                    assertEquals(ContentType.Application.Json, json.contentType()?.withoutParameters())
                    assertEquals(expected, Json.parseToJsonElement(json.bodyAsText()))
                }
                val allGroups = Json.parseToJsonElement(client.ops("/ops/clusters").bodyAsText()).jsonArray
                val platformResponse = client.ops("/ops/clusters?platform=doordash")
                assertEquals(200, platformResponse.status.value)
                assertEquals(ContentType.Application.Json, platformResponse.contentType()?.withoutParameters())
                val platformGroups = Json.parseToJsonElement(platformResponse.bodyAsText()).jsonArray
                assertEquals(allGroups, platformGroups)
                assertEquals(setOf("platformAppVersion", "clusters"), platformGroups.single().jsonObject.keys)
                assertEquals(sample.item.fingerprint, platformGroups.single().jsonObject.getValue("clusters").jsonArray.single().jsonObject.getValue("fingerprint").jsonPrimitive.content)
                val uberResponse = client.ops("/ops/clusters?platform=uber")
                assertEquals(200, uberResponse.status.value)
                assertTrue(Json.parseToJsonElement(uberResponse.bodyAsText()).jsonArray.isEmpty())
                val invalidPlatform = client.ops("/ops/clusters?platform=Bad!")
                assertEquals(ContentType.Application.Json, invalidPlatform.contentType()?.withoutParameters())
                assertError(invalidPlatform, 400, "bad_request")
                assertError(browser.get("$path/view"), 401, "unauthorized")
                val display = ops.vocabularyQueueDisplay()
                assertTrue(display.isEmpty()) // One non-trusted install is below the vocabulary gate.
            }
        }
    }

    private suspend fun HttpClient.mutate(path: String, body: String): HttpResponse {
        instant = instant.plusSeconds(30)
        return ops(path, body, code = Totp.code(totpSecret, instant.epochSecond))
    }

    private suspend fun HttpClient.ops(path: String, body: String? = null, token: String? = operatorToken, code: String? = null): HttpResponse = request(path) {
        method = if (body == null) HttpMethod.Get else HttpMethod.Post
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        code?.let { header("X-Census-Totp", it) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private fun fixture(name: String, text: String, version: String = "8.0"): ItemVerdict.Accepted {
        val raw = """{"schemaId":"uinode.skeleton.v1","hashDomain":1,"filterRev":1,"fingerprint":"${"0".repeat(64)}",
            "platform":"doordash","platformAppVersion":"$version","engineVersion":1,"day":"$day",
            "root":{"class":"android.widget.$name","id":"com.example:id/$name","text":{"text":{"h":"${CensusHash.of(text)}","kind":"words:2"}}}}"""
        val decoded = SkeletonSchema.deserialize(raw)
        val canonical = SkeletonSchema.serialize(decoded.copy(fingerprint = requireNotNull(CensusFingerprint.of(decoded.root))))
        return SkeletonValidator.validate(Json.parseToJsonElement(canonical), Policy(), day) as ItemVerdict.Accepted
    }

    private fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

    companion object {
        private const val operatorToken = "OPERATOR_TOKEN_SENTINEL"
        private const val totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        @Container
        @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        private fun config(): Config = Config.fromEnv(testEnvironment() + mapOf(
            "DATABASE_URL" to postgres.jdbcUrl, "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password,
            "OPERATOR_TOKEN_SHA256" to hashSecret(operatorToken),
        ))
        @BeforeAll
        @JvmStatic
        fun migrate() { Database.migrate(config()) }
        @JvmStatic
        fun dockerAvailable(): Boolean = System.getenv("CI") == "true" || runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }
}

class OpsLogPathTest {
    @Test
    fun `cluster review log path excludes details and query parameters`() {
        // The caller uses request.path(), so /ops/clusters/view?platform=doordash&version=1.0.0
        // arrives here as the path only; the query string never reaches opsLogPath.
        assertEquals("/ops/clusters", opsLogPath("/ops/clusters/view"))
    }
}
