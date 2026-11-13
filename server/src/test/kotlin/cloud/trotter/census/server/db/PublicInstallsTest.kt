package cloud.trotter.census.server.db

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.assertError
import cloud.trotter.census.server.auth.hashSecret
import cloud.trotter.census.server.enrol
import cloud.trotter.census.server.healthFixture
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.jobs.PurgeJob
import cloud.trotter.census.server.module
import cloud.trotter.census.server.ops.Totp
import cloud.trotter.census.server.secret
import cloud.trotter.census.server.signed
import io.ktor.client.HttpClient
import io.ktor.client.request.header
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** #79: the pause switch, per-install quotas, "delete my data", retention folding and logs without install keys. */
class PublicInstallsTest : LifecycleTestDatabase() {
    private val operatorToken = "OPERATOR_TOKEN_SENTINEL"
    private val totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private fun opsConfig() = config().copy(operatorTokenSha256 = hashSecret(operatorToken), operatorTotpSecret = totpSecret)

    @Test fun `the pause switch refuses non-trusted uploads with a retryable 503 and spares trusted installs and deletion`() = runBlocking {
        Database.connect(config()).use { db ->
            testApplication {
                application { module(opsConfig(), db, clock, policy = Policy()) }
                val (open, openKey) = UUID.randomUUID().toString() to secret(11)
                val (own, ownKey) = UUID.randomUUID().toString() to secret(12)
                for ((id, key) in listOf(open to openKey, own to ownKey)) assertEquals(HttpStatusCode.OK, client.enrol(id, key).status)
                sql { it.update("UPDATE installs SET trusted = true WHERE install_id = ?::uuid", own) }

                assertError(client.ops("/ops/pause", """{"paused":true}"""), 401, "totp_required")
                assertEquals("""{"paused":false}""", client.ops("/ops/pause").bodyAsText())
                val paused = client.ops("/ops/pause", """{"paused":true}""", code = nextCode())
                assertEquals(HttpStatusCode.OK, paused.status)
                assertEquals("""{"paused":true,"changedDay":"$day"}""", paused.bodyAsText())

                val refused = client.signed(clock, open, openKey, HttpMethod.Post, "/v1/skeletons", batch("b1", screen("Paused")))
                assertError(refused, 503, "paused")
                assertEquals("3600", refused.headers[HttpHeaders.RetryAfter])
                assertError(client.signed(clock, open, openKey, HttpMethod.Post, "/v1/health", health()), 503, "paused")
                assertEquals(0L, count("cluster_sightings_v5"))
                assertEquals(0L, count("health_daily"))
                assertEquals(0L, count("ingest_ledger"))

                assertEquals(HttpStatusCode.OK, client.signed(clock, own, ownKey, HttpMethod.Post, "/v1/skeletons", batch("b1", screen("Trusted"))).status)
                assertEquals(HttpStatusCode.OK, client.signed(clock, open, openKey, HttpMethod.Delete, "/v1/installs/me/data").status)
                // The switch lives in the database: a fresh store (a restarted process) still reads it.
                assertTrue(PauseStore(db, clock).paused())

                assertEquals("""{"paused":false,"changedDay":"$day"}""", client.ops("/ops/pause", """{"paused":false}""", code = nextCode()).bodyAsText())
                assertEquals(HttpStatusCode.OK, client.signed(clock, open, openKey, HttpMethod.Post, "/v1/skeletons", batch("b2", screen("Resumed"))).status)
            }
        }
    }

    @Test fun `health reports and batches are capped per install per UTC day from Policy`() = runBlocking {
        Database.connect(config()).use { db ->
            val policy = Policy(dailyHealthReportBudget = 3, dailyBatchBudget = 1)
            testApplication {
                application { module(config(), db, clock, policy = policy) }
                val (id, key) = UUID.randomUUID().toString() to secret(21)
                assertEquals(HttpStatusCode.OK, client.enrol(id, key).status)
                assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/health", health("8.0.0", "8.0.1")).status)
                val fleetBefore = fleetRows()
                val breach = client.signed(clock, id, key, HttpMethod.Post, "/v1/health", health("8.0.2", "8.0.3"))
                assertError(breach, 429, "budget_exhausted")
                assertEquals("43200", breach.headers[HttpHeaders.RetryAfter])
                assertEquals(fleetBefore, fleetRows())
                assertEquals(2L, count("health_daily"))
                assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/health", health("8.0.2")).status)
                assertError(client.signed(clock, id, key, HttpMethod.Post, "/v1/health", health("8.0.4")), 429, "budget_exhausted")

                assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("one", screen("Quota"))).status)
                assertError(client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("two", screen("Quota2"))), 429, "budget_exhausted")

                instant = instant.plusSeconds(86_400)
                assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/health", health("8.0.4")).status)
                assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("two", screen("Quota2"))).status)
            }
        }
    }

    @Test fun `delete my data erases every install-keyed row keeps the credential and confirms, and withdrawal still erases all`() = runBlocking {
        Database.connect(config()).use { db ->
            val logs = rootLogs()
            try {
                testApplication {
                    application { module(config(), db, clock, policy = Policy()) }
                    val id = "7e57ab1e-0000-4000-8000-000000000079"
                    val key = secret(31)
                    val (other, otherKey) = UUID.randomUUID().toString() to secret(32)
                    for ((install, secret) in listOf(id to key, other to otherKey)) {
                        assertEquals(HttpStatusCode.OK, client.enrol(install, secret).status)
                        assertEquals(HttpStatusCode.OK, client.signed(clock, install, secret, HttpMethod.Post, "/v1/health", health()).status)
                    }
                    assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("before", screen("Mine"))).status)
                    assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/nonce").status)
                    seedEnvelopeAndOldLedger(UUID.fromString(id))
                    assertEquals(2L, fleetRows().single()[0])
                    for (table in InstallStore.DATA_TABLES) assertTrue(rowsFor(table, id) > 0, table)

                    val deleted = client.signed(clock, id, key, HttpMethod.Delete, "/v1/installs/me/data")
                    assertEquals(HttpStatusCode.OK, deleted.status)
                    val body = Json.parseToJsonElement(deleted.bodyAsText()).jsonObject
                    assertEquals("deleted", body.getValue("status").jsonPrimitive.content)
                    assertEquals(day.toString(), body.getValue("deletedDay").jsonPrimitive.content)
                    assertEquals(day.plusDays(1).toString(), body.getValue("completionDeadline").jsonPrimitive.content)
                    assertEquals(1L, InstallStore.DATA_TABLES.sumOf { rowsFor(it, id) }, "only today's quota row remains")
                    for (table in InstallStore.DATA_TABLES - "ingest_ledger") assertEquals(0L, rowsFor(table, id), table)
                    assertEquals(1L, rowsFor("ingest_ledger", id))
                    assertTrue(body.getValue("rows").jsonPrimitive.content.toInt() >= 6)
                    // The fleet total it fed is recomputed without it; the other install is untouched.
                    assertEquals(1L, fleetRows().single()[0])
                    assertTrue(rowsFor("health_daily", other) > 0)
                    val tombstone = tombstone(UUID.fromString(id))
                    assertEquals(tombstone, erasedThrough(UUID.fromString(id)))
                    assertNull(sql { it.select("SELECT draft FROM clusters WHERE draft IS NOT NULL") { r -> r.getString(1) } })

                    // Today's ledger row keeps the quota and the batch ids: a pre-deletion batch is not re-admitted.
                    val retried = client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("before", screen("Mine")))
                    assertEquals("duplicate", Json.parseToJsonElement(retried.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
                    assertEquals(0L, rowsFor("cluster_sightings_v5", id))
                    // The credential still works and new uploads land.
                    assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Get, "/v1/me").status)
                    instant = instant.plusSeconds(1)
                    assertEquals(HttpStatusCode.OK, client.signed(clock, id, key, HttpMethod.Post, "/v1/skeletons", batch("after", screen("Later"))).status)
                    assertEquals(1L, rowsFor("cluster_sightings_v5", id))

                    // Startup replay sees the deletion already applied to this generation and keeps the new rows.
                    val replay = InstallStore(db, clock).reapplyWithdrawals()
                    assertEquals(0, replay.matched)
                    assertEquals(1, replay.keptReenrolled)
                    assertEquals(1L, rowsFor("cluster_sightings_v5", id))

                    val withdrawn = client.signed(clock, id, key, HttpMethod.Delete, "/v1/installs/me")
                    assertEquals(HttpStatusCode.Accepted, withdrawn.status)
                    for (table in InstallStore.WITHDRAWAL_TABLES) assertEquals(0L, rowsFor(table, id), table)
                    assertError(client.signed(clock, id, key, HttpMethod.Delete, "/v1/installs/me/data"), 401, "unauthorized")
                }
                // #79: no request, ingest or deletion log line carries any part of the install id.
                assertTrue(logs.list.any { it.formattedMessage.startsWith("method=DELETE path=/v1/installs/me/data status=200") })
                assertTrue(logs.list.any { it.formattedMessage.startsWith("delete_data rows=") })
                for (event in logs.list) {
                    assertFalse(event.formattedMessage.contains("7e57ab1e"), event.formattedMessage)
                    assertFalse(event.formattedMessage.contains("install_prefix"), event.formattedMessage)
                }
            } finally {
                stop(logs)
            }
        }
    }

    @Test fun `a restore from an older backup re-applies a data deletion and a newer backup keeps later rows`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val id = UUID.randomUUID()
            assertEquals(EnrolOutcome.Created, store.enrol(id, key, "1.0"))
            observe(db, id)
            val applied = store.deleteData(id, key, day) as MutationOutcome.Applied
            val journal = listOf(InstallStore.installIdHash(id) to requireNotNull(applied.withdrawnAt))
            assertEquals(0L, rowsFor("cluster_sightings_v5", id.toString()))

            // A newer backup (taken after the deletion) carries the marker and only post-deletion rows: kept.
            instant = instant.plusSeconds(60)
            observe(db, id, listOf(item(channel = "LATER_CHANNEL")))
            store.mergeWithdrawalJournal(journal)
            assertEquals(0, store.reapplyWithdrawals().matched)
            assertEquals(1L, rowsFor("cluster_sightings_v5", id.toString()))

            // An older backup: the install has no marker, its pre-deletion rows are back and the tombstone is gone.
            sql { connection ->
                connection.update("UPDATE installs SET erased_through = NULL WHERE install_id = ?", id)
                connection.update("DELETE FROM withdrawals")
            }
            observe(db, id, listOf(item(channel = "RESTORED_CHANNEL")))
            store.mergeWithdrawalJournal(journal)
            val replay = store.reapplyWithdrawals()
            assertEquals(1, replay.matched)
            // The journal cannot tell a data deletion from a withdrawal, so replay erases the generation whole.
            for (table in InstallStore.WITHDRAWAL_TABLES) assertEquals(0L, rowsFor(table, id.toString()), table)
            assertNull(store.lookup(id))
        }
    }

    @Test fun `retention folds per-install health into fleet totals before deleting it, and sightings keep presence for 30 days`() = runBlocking {
        Database.connect(config()).use { db ->
            val health = HealthStore(db, clock)
            val installs = InstallStore(db, clock)
            val (first, second) = install() to install()
            val retention = Policy().retention
            val folded = day.minusDays(retention.healthDailyDays.toLong())
            val kept = folded.plusDays(1)
            for ((id, admitted) in listOf(first to 100, second to 50)) for (reportDay in listOf(folded, kept)) {
                assertEquals(HealthOutcome.Stored, health.upsert(id, key, day, listOf(report(reportDay, admitted)), emptyMap(), 1, BudgetPolicy()))
            }
            // A stale fleet row (as an older withdrawal left it) is corrected by the fold, not frozen.
            sql { it.update("UPDATE health_fleet_daily SET installs_reporting = 99, admitted = 1 WHERE day = ?", folded) }
            assertEquals(2, installs.purgeHealthDaily(day.minusDays(retention.healthDailyDays.toLong())))
            assertEquals(listOf(2L, 150L), fleet(folded))
            assertEquals(0L, sql { it.select("SELECT count(*) FROM health_daily WHERE day = ?", folded) { r -> r.getLong(1) }!! })
            assertEquals(2L, sql { it.select("SELECT count(*) FROM health_daily WHERE day = ?", kept) { r -> r.getLong(1) }!! })
            assertEquals(0L, installs.remainingDue(day, instant, retention).getValue("health"))

            // A withdrawal after the fold cannot find the install in a folded day; it does leave the open day.
            assertEquals(MutationOutcome.Applied::class, installs.withdraw(first, key)::class)
            assertEquals(listOf(2L, 150L), fleet(folded))
            assertEquals(listOf(1L, 50L), fleet(kept))

            // Cluster sightings: one presence row per install and day, deleted 30 days after the observation.
            observe(db, second, listOf(item(), item()))
            observe(db, second)
            assertEquals(1L, count("cluster_sightings_v5"))
            instant = instant.plusSeconds(retention.clusterSightingsDays * 86_400L - 86_400L)
            LifecycleStore(db, clock, Policy()).runOnce()
            assertEquals(1L, count("cluster_sightings_v5"))
            instant = instant.plusSeconds(86_400L)
            LifecycleStore(db, clock, Policy()).runOnce()
            assertEquals(0L, count("cluster_sightings_v5"))
            assertEquals(0L, PurgeJob(installs, clock, Policy()).runOnce().sweeps.getValue("health").remainingDue ?: 0L)
        }
    }

    @Test fun `every install-keyed table is in the erasure lists`() {
        val keyed = sql { connection ->
            connection.select(
                """SELECT string_agg(table_name, ',' ORDER BY table_name) FROM information_schema.columns
                    WHERE table_schema = current_schema() AND column_name = 'install_id'""",
            ) { it.getString(1) }!!.split(',').toSet()
        }
        // The pre-V5 tables are empty and refused at startup if written; nothing new may join them silently.
        assertEquals(InstallStore.WITHDRAWAL_TABLES.toSet(), keyed - setOf("token_sightings", "cluster_sightings"))
        assertEquals(InstallStore.WITHDRAWAL_TABLES - "installs", InstallStore.DATA_TABLES)
    }

    @Test fun `deletion keeps folded fleet totals when the fold commits between its key read and its locks`() = runBlocking {
        Database.connect(config()).use { db ->
            val health = HealthStore(db, clock)
            val (first, second) = install() to install()
            val folded = day.minusDays(Policy().retention.healthDailyDays.toLong())
            for ((id, admitted) in listOf(first to 100, second to 50)) {
                assertEquals(HealthOutcome.Stored, health.upsert(id, key, day, listOf(report(folded, admitted)), emptyMap(), 1, BudgetPolicy()))
            }
            assertEquals(listOf(2L, 150L), fleet(folded))
            DriverManager.getConnection(LifecycleTestDatabase.postgres.jdbcUrl, LifecycleTestDatabase.postgres.username, LifecycleTestDatabase.postgres.password).use { fold ->
                fold.autoCommit = false
                // The fold holds the fleet row while it folds the day.
                fold.select("SELECT 1 FROM health_fleet_daily WHERE day = ? FOR UPDATE", folded) { true }
                val deletion = async(Dispatchers.IO) { InstallStore(db, clock).deleteData(first, key, day) }
                // The deletion has read its keys and now waits for the fleet lock.
                withTimeout(30_000) {
                    while (sql { connection ->
                            connection.select(
                                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%health_fleet_daily%'",
                            ) { it.getLong(1) }!!
                        } == 0L
                    ) delay(10)
                }
                fold.update("DELETE FROM health_daily WHERE day = ?", folded)
                fold.commit()
                assertTrue(deletion.await() is MutationOutcome.Applied)
            }
            // Sol S1: the folded day keeps both installs' totals; nothing was recomputed from an empty day.
            assertEquals(listOf(2L, 150L), fleet(folded))
        }
    }

    @Test fun `every erasure moves the tombstone strictly forward, even after a clock rollback`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val id = install()
            val first = requireNotNull((store.deleteData(id, key, day) as MutationOutcome.Applied).withdrawnAt)
            instant = instant.minusSeconds(3600)
            val second = requireNotNull((store.deleteData(id, key, day) as MutationOutcome.Applied).withdrawnAt)
            assertTrue(second.isAfter(first), "a rollback must not reuse the first tombstone")
            assertEquals(second, erasedThrough(id)?.toInstant())
            // A backup taken between the two deletions carries the first marker and rows uploaded after it.
            sql { it.update("UPDATE installs SET erased_through = ? WHERE install_id = ?", first.atOffset(ZoneOffset.UTC), id) }
            observe(db, id, listOf(item(channel = "BETWEEN_CHANNEL")))
            assertEquals(1, store.reapplyWithdrawals().matched)
            assertNull(store.lookup(id))
            // A withdrawal after a further rollback cannot move the tombstone back either.
            assertEquals(EnrolOutcome.Created, store.enrol(id, key, "1.0"))
            instant = instant.minusSeconds(3600)
            val withdrawn = requireNotNull((store.withdraw(id, key) as MutationOutcome.Applied).withdrawnAt)
            assertTrue(withdrawn.isAfter(second))
            assertEquals(withdrawn, tombstone(id).toInstant())
        }
    }

    @Test fun `the tombstone purge clears each marker together with its own tombstone`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val old = instant.minusSeconds(61 * 86_400L).atOffset(ZoneOffset.UTC)
            sql { connection ->
                // 1,000 tombstones of installs long gone, then 1,001 installs that still carry marker and tombstone.
                connection.update("INSERT INTO withdrawals SELECT lpad(to_hex(n), 64, '0'), ? FROM generate_series(1, 1000) n", old)
                connection.update(
                    """INSERT INTO installs (install_id, key_hash, created_day, last_seen_day, erased_through)
                        SELECT gen_random_uuid(), ?, ?, ?, ? FROM generate_series(1, 1001)""",
                    key, day, day, old,
                )
                connection.update("INSERT INTO withdrawals SELECT encode(sha256(install_id::text::bytea), 'hex'), erased_through FROM installs")
                // A restart after the first committed batch: the second batch's delete fails.
                connection.update("CREATE SEQUENCE census_test_purge_batches")
                connection.update(
                    """CREATE FUNCTION census_test_stop_second_batch() RETURNS trigger LANGUAGE plpgsql AS
                        'BEGIN IF nextval(''census_test_purge_batches'') > 1 THEN RAISE EXCEPTION ''interrupted''; END IF; RETURN NULL; END'""",
                )
                connection.update(
                    """CREATE TRIGGER census_test_stop_second_batch AFTER DELETE ON withdrawals
                        FOR EACH STATEMENT EXECUTE FUNCTION census_test_stop_second_batch()""",
                )
            }
            val cutoff = instant.minusSeconds(60 * 86_400L)
            try {
                assertTrue(runCatching { store.purgeWithdrawals(cutoff) }.isFailure)
                assertEquals(1001L, count("withdrawals"), "exactly the first batch committed")
                // Sol S4: no install lost its marker while its tombstone survives (replay would erase it).
                assertEquals(0L, sql { connection ->
                    connection.select(
                        """SELECT count(*) FROM installs i JOIN withdrawals w
                            ON w.install_id_hash = encode(sha256(i.install_id::text::bytea), 'hex') WHERE i.erased_through IS NULL""",
                    ) { it.getLong(1) }!!
                })
            } finally {
                sql { connection ->
                    connection.update("DROP TRIGGER census_test_stop_second_batch ON withdrawals")
                    connection.update("DROP FUNCTION census_test_stop_second_batch()")
                    connection.update("DROP SEQUENCE census_test_purge_batches")
                }
            }
            assertEquals(1001, store.purgeWithdrawals(cutoff))
            assertEquals(0L, count("withdrawals"))
            assertEquals(0L, sql { it.select("SELECT count(*) FROM installs WHERE erased_through IS NOT NULL") { r -> r.getLong(1) }!! })
            assertEquals(0, store.reapplyWithdrawals().matched)
        }
    }

    @Test fun `vocabulary provenance names no install`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = List(3) { install(trusted = true) }
            testApplication {
                application { module(opsConfig(), db, clock, policy = Policy()) }
                val prefix = ids.first().toString().take(8)
                fun resolve(source: String) = """{"tokenHash":"0123456789abcdef","clearText":null,"source":"$source","reject":true}"""
                // Plain `trusted` passes validation (and finds nothing to resolve); the prefix form is refused.
                assertError(client.ops("/ops/vocabulary/resolve", resolve("trusted"), code = nextCode()), 404, "not_found")
                assertError(client.ops("/ops/vocabulary/resolve", resolve("trusted:$prefix"), code = nextCode()), 400, "bad_request")
            }
            val insert = """INSERT INTO vocabulary_v5 (token_hash, kind, distinct_installs_at_promotion, promoted_day, source, status, hash_domain, filter_rev)
                VALUES ('0123456789abcdef', 'words:1', 10, ?, ?, 'rejected', 1, 1)"""
            assertTrue(runCatching { sql { it.update(insert, day, "trusted:" + ids.first().toString().take(8)) } }.isFailure)
            sql { it.update(insert, day, "trusted") }
            // Guard: no text column in the schema holds any install's ID prefix.
            val columns = sql { connection ->
                connection.prepareStatement(
                    """SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = current_schema()
                        AND data_type IN ('text', 'character', 'character varying', 'jsonb')""",
                ).use { statement ->
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
                }
            }
            assertTrue(columns.any { it == ("vocabulary_v5" to "source") })
            for ((table, column) in columns) for (id in ids) {
                val hits = sql { it.select("SELECT count(*) FROM \"$table\" WHERE \"$column\"::text LIKE ?", "%${id.toString().take(8)}%") { r -> r.getLong(1) }!! }
                assertEquals(0L, hits, "$table.$column holds an install prefix")
            }
        }
    }

    private fun nextCode(): String {
        instant = instant.plusSeconds(30)
        return Totp.code(totpSecret, instant.epochSecond)
    }

    private suspend fun HttpClient.ops(path: String, body: String? = null, code: String? = null): HttpResponse = request(path) {
        method = if (body == null) HttpMethod.Get else HttpMethod.Post
        header(HttpHeaders.Authorization, "Bearer $operatorToken")
        code?.let { header("X-Census-Totp", it) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private fun screen(name: String): JsonObject {
        val raw = """{"schemaId":"uinode.skeleton.v1","hashDomain":1,"filterRev":1,"fingerprint":"${"0".repeat(64)}",
            "platform":"doordash","platformAppVersion":"8.0","engineVersion":1,"day":"$day",
            "root":{"class":"android.widget.$name","id":"com.example:id/$name","text":{"text":{"h":"${CensusHash.of("Ready now")}","kind":"words:2"}}}}"""
        val decoded = SkeletonSchema.deserialize(raw)
        return Json.parseToJsonElement(SkeletonSchema.serialize(decoded.copy(fingerprint = requireNotNull(CensusFingerprint.of(decoded.root))))).jsonObject
    }

    private fun batch(id: String, vararg items: JsonObject): String =
        JsonObject(mapOf("batchId" to JsonPrimitive(id), "items" to JsonArray(items.toList()))).toString()

    private fun health(vararg versions: String = arrayOf("8.0.0")): String = JsonObject(mapOf("reports" to JsonArray(versions.map { version ->
        JsonObject(healthFixture() + mapOf("day" to JsonPrimitive(day.toString()), "platformAppVersion" to JsonPrimitive(version)))
    }))).toString()

    private fun report(reportDay: LocalDate, admitted: Int) =
        HealthReport(reportDay, "doordash", "8.0.0", "1.0.0", "v1", admitted, 0, 0, mapOf("a.b" to 1))

    private fun fleet(fleetDay: LocalDate): List<Long> = sql {
        it.select("SELECT installs_reporting, admitted FROM health_fleet_daily WHERE day = ?", fleetDay) { r -> listOf(r.getLong(1), r.getLong(2)) }!!
    }

    private fun fleetRows(): List<List<Long>> = sql { connection ->
        connection.prepareStatement("SELECT installs_reporting, admitted FROM health_fleet_daily ORDER BY day, platform_app_version").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(listOf(rows.getLong(1), rows.getLong(2))) } }
        }
    }

    private fun rowsFor(table: String, id: String): Long =
        sql { it.select("SELECT count(*) FROM $table WHERE install_id = ?::uuid", id) { r -> r.getLong(1) }!! }

    private fun tombstone(id: UUID): OffsetDateTime = sql {
        it.select("SELECT withdrawn_at FROM withdrawals WHERE install_id_hash = ?", InstallStore.installIdHash(id)) { r -> r.getObject(1, OffsetDateTime::class.java) }!!
    }

    private fun erasedThrough(id: UUID): OffsetDateTime? = sql {
        it.select("SELECT erased_through FROM installs WHERE install_id = ?", id) { r -> r.getObject(1, OffsetDateTime::class.java) }
    }

    private fun seedEnvelopeAndOldLedger(id: UUID) = sql { connection ->
        val fingerprint = "1".repeat(64)
        connection.update("INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day) VALUES (?, 'doordash', ?, ?) ON CONFLICT DO NOTHING", fingerprint, day, day)
        val envelope = connection.select(
            "INSERT INTO trusted_envelopes (install_id, fingerprint, envelope, received_day, purge_after) VALUES (?, ?, '{}', ?, ?) RETURNING id",
            id, fingerprint, day, day.plusDays(30),
        ) { it.getLong(1) }!!
        connection.update("UPDATE clusters SET draft = jsonb_build_object('envelopeId', ?::text), draft_day = ? WHERE fingerprint = ?", envelope.toString(), day, fingerprint)
        connection.update("INSERT INTO ingest_ledger (install_id, day) VALUES (?, ?)", id, day.minusDays(1))
    }

    private fun rootLogs(): ListAppender<ILoggingEvent> = ListAppender<ILoggingEvent>().apply {
        start()
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(this)
    }

    private fun stop(logs: ListAppender<ILoggingEvent>) {
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logs)
        logs.stop()
    }
}
