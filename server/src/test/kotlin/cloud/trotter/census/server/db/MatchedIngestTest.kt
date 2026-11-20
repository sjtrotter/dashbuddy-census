package cloud.trotter.census.server.db

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/** #69: matched skeletons through the route, the store, the lifecycle sweeps and the filter floor (S3-S6, S8, S9, S12). */
class MatchedIngestTest : LifecycleTestDatabase() {
    private val clusterTables = listOf("clusters", "cluster_samples", "cluster_sightings_v5", "token_sightings_v5", "slot_sightings")

    private fun batch(id: String, items: List<JsonObject>) =
        JsonObject(mapOf("batchId" to JsonPrimitive(id), "items" to JsonArray(items))).toString()

    private fun strings(query: String, vararg args: Any?): List<String> = sql { connection ->
        connection.prepareStatement(query).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun rootLogs(): ListAppender<ILoggingEvent> = ListAppender<ILoggingEvent>().apply {
        start()
        (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).addAppender(this)
    }

    @Test fun `a matched skeleton is stored apart from the clusters and consumes one skeleton, and no log line names its rule`() {
        val logs = rootLogs()
        val id = UUID.randomUUID().toString()
        try {
            Database.connect(config()).use { db -> testApplication {
                application { module(config(), db, clock, policy = policy) }
                val secret = secret(169)
                assertEquals(200, client.enrolSchemas(id, secret, CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS).status.value)
                val item = matchedFixture(day)
                val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/skeletons", batch("m1", listOf(item)))
                assertEquals(200, response.status.value)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(1, body.getValue("accepted").jsonPrimitive.int)
                assertEquals(0, body.getValue("duplicate").jsonPrimitive.int)
                val fingerprint = item.getValue("fingerprint").jsonPrimitive.content
                // S3: the sample, one presence row and its slot rows; nothing in any cluster table.
                assertEquals(listOf("$MATCHED_RULE|$fingerprint|doordash|8.1"),
                    strings("SELECT rule_id || '|' || fingerprint || '|' || platform || '|' || platform_app_version FROM matched_samples"))
                for (table in clusterTables) assertEquals(0L, count(table), table)
                assertEquals(1L, count("matched_sightings"))
                assertTrue(count("matched_slot_sightings") > 0)
                assertEquals(1, InstallStore(db, clock).ledgerFor(UUID.fromString(id), day)!!.accepted)
                // The stored sample is keyed at rest (#85) and keeps its rule id.
                val stored = strings("SELECT skeleton::text FROM matched_samples").single()
                assertFalse(stored.contains("0123456789abcdef"))
                assertTrue(stored.contains(testKeying.token("0123456789abcdef")))
                assertEquals(MATCHED_RULE, Json.parseToJsonElement(stored).jsonObject.getValue("ruleId").jsonPrimitive.content)
                // The same batch again is a duplicate and stores nothing.
                val again = client.signed(clock, id, secret, HttpMethod.Post, "/v1/skeletons", batch("m1", listOf(item)))
                assertEquals("duplicate", Json.parseToJsonElement(again.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals(1L, count("matched_sightings"))
            } }
            // S12: counts only; no ingest or request line names the rule or any part of the install id.
            assertTrue(logs.list.any { it.formattedMessage.startsWith("ingest accepted=1") })
            assertPrivateLogs(logs.list, listOf(MATCHED_RULE, id.take(8)))
        } finally {
            (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).detachAppender(logs)
            logs.stop()
        }
    }

    @Test fun `one layout sent unmatched and matched in one batch is two items, each in its own tables`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(config(), db, clock, policy = policy) }
            val id = UUID.randomUUID().toString(); val secret = secret(170)
            assertEquals(200, client.enrolSchemas(id, secret, CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS).status.value)
            val unknown = screenV2Fixture(day)
            val matched = matchedFixture(day)
            val fingerprint = unknown.getValue("fingerprint").jsonPrimitive.content
            assertEquals(fingerprint, matched.getValue("fingerprint").jsonPrimitive.content)
            val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/skeletons", batch("both", listOf(unknown, matched)))
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body.getValue("accepted").jsonPrimitive.int)
            assertEquals(0, body.getValue("duplicate").jsonPrimitive.int)
            // S4: the cluster holds the unknown item only; the matched one is beside it.
            assertEquals(listOf(fingerprint), strings("SELECT fingerprint FROM clusters"))
            assertEquals(1L, count("cluster_samples"))
            assertEquals(listOf("$MATCHED_RULE|$fingerprint"), strings("SELECT rule_id || '|' || fingerprint FROM matched_samples"))
            assertFalse(strings("SELECT skeleton::text FROM cluster_samples").single().contains("ruleId"))
        } }
    }

    @Test fun `a matched skeleton of a resolved cluster's layout leaves the cluster untouched and is still stored`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            val accepted = matchedAccepted(day)
            val fingerprint = accepted.item.fingerprint
            val old = day.minusDays(40)
            sql { it.update("""INSERT INTO clusters (fingerprint, platform, first_seen_day, last_seen_day, status, resolved_day, resolved_rule_id)
                VALUES (?, 'doordash', ?, ?, 'resolved', ?, ?)""", fingerprint, old, old, old, MATCHED_RULE) }
            observe(db, id, listOf(accepted))
            // S5: the cluster's last observation is not moved, and its resolution gate does not stop the sample.
            assertEquals(listOf(old.toString()), strings("SELECT last_seen_day::text FROM clusters"))
            assertEquals(0L, count("cluster_samples"))
            assertEquals(0L, count("cluster_sightings_v5"))
            assertEquals(1L, count("matched_samples"))
        }
    }

    @Test fun `samples are capped at five per rule layout and version, one per receipt day`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            // S6: six days of the same layout and version, then a second version on the last day.
            repeat(6) {
                observe(db, id, listOf(matchedAccepted(day, version = "8.99.20")))
                if (it < 5) instant = instant.plusSeconds(86_400)
            }
            assertEquals(5L, count("matched_samples"))
            observe(db, id, listOf(matchedAccepted(day, version = "8.99.21")))
            assertEquals(listOf("5", "1"), strings("SELECT count(*)::text FROM matched_samples GROUP BY platform_app_version ORDER BY platform_app_version"))
            // A second item the same receipt day adds no sample.
            observe(db, id, listOf(matchedAccepted(day, version = "8.99.21")))
            assertEquals(6L, count("matched_samples"))
        }
    }

    @Test fun `a late upload's sample expires with its observation day, not its receipt day`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            val observed = day.minusDays(7)
            val accepted = SkeletonValidator.validate(matchedFixture(observed), policy, day) as ItemVerdict.Accepted
            observe(db, id, listOf(accepted))
            assertEquals(listOf("$day|${observed.plusDays(30)}|$observed"),
                strings("SELECT s.received_day::text || '|' || s.purge_after::text || '|' || x.day::text FROM matched_samples s, matched_sightings x"))
            // A future-dated item is clamped to its receipt day, as sightings clamp it.
            instant = instant.plusSeconds(86_400)
            val ahead = SkeletonValidator.validate(matchedFixture(day.plusDays(1), rootId = "com.example.app:id/later"), policy, day) as ItemVerdict.Accepted
            observe(db, id, listOf(ahead))
            assertEquals(listOf(day.plusDays(30).toString()),
                strings("SELECT purge_after::text FROM matched_samples WHERE fingerprint = ?", ahead.item.fingerprint))
        }
    }

    @Test fun `the lifecycle sweeps expire matched rows on their own days and report what is due`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(age = 1)
            val first = day
            observe(db, id, listOf(matchedAccepted(day)))
            instant = instant.plusSeconds(86_400)
            observe(db, id, listOf(matchedAccepted(day)))
            assertEquals(2L, count("matched_samples"))
            assertEquals(2L, count("matched_sightings"))
            // A first-week install's slot rows carry the quarantine mark, cleared by the sweep once passed.
            assertTrue(strings("SELECT count(*)::text FROM matched_slot_sightings WHERE counts_from IS NOT NULL").single().toInt() > 0)

            // S8: at first + 30 the first day's sample and sighting are due; the second day's are not.
            instant = instant.plusSeconds(29 * 86_400L)
            assertEquals(first.plusDays(30), day)
            val lifecycle = LifecycleStore(db, clock, policy)
            val due = lifecycle.remainingDue()
            assertEquals(1L, due.getValue("matched_samples"))
            assertEquals(1L, due.getValue("matched_sightings"))
            assertEquals(0L, due.getValue("matched_slots"))
            assertTrue(due.getValue("matched_slot_quarantine_marks") > 0)
            val result = lifecycle.runOnce()
            assertEquals(1L, result.getValue("matched_samples").deleted)
            assertEquals(listOf(first.plusDays(1).toString()), strings("SELECT received_day::text FROM matched_samples"))
            assertEquals(listOf(first.plusDays(1).toString()), strings("SELECT day::text FROM matched_sightings"))
            assertTrue(count("matched_slot_sightings") > 0)
            assertEquals(0L, count("matched_slot_sightings") - strings("SELECT count(*)::text FROM matched_slot_sightings WHERE counts_from IS NULL").single().toLong())
            assertEquals(0L, lifecycle.remainingDue().filterKeys { it.startsWith("matched_") }.values.sum())

            // A day later the rest goes, slot rows included (their last day is the second observation).
            instant = instant.plusSeconds(86_400)
            lifecycle.runOnce()
            for (table in listOf("matched_samples", "matched_sightings", "matched_slot_sightings")) assertEquals(0L, count(table), table)
        }
    }

    @Test fun `raising the filter floor deletes matched rows below it`() = runBlocking {
        Database.connect(config()).use { db ->
            val floors = FilterPolicyStore(db, clock)
            assertEquals(1, floors.bootstrap(1))
            val id = install()
            observe(db, id, listOf(matchedAccepted(day, filterRev = 1), matchedAccepted(day, filterRev = 2, rootId = "com.example.app:id/two")))
            assertEquals(2L, count("matched_samples"))
            // S9: the floor goes to 2; every revision-1 matched row goes, the revision-2 ones stay.
            assertEquals(2, floors.bootstrap(2))
            for (table in listOf("matched_samples", "matched_sightings", "matched_slot_sightings")) {
                assertEquals(listOf("2"), strings("SELECT DISTINCT filter_rev::text FROM $table"), table)
            }
        }
    }

    @Test fun `a trust change marks the install's matched rows like its other sightings`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            observe(db, id, listOf(matchedAccepted(day)))
            assertTrue(OpsStore(db, clock, policy).trust(id, true))
            for (table in listOf("matched_sightings", "matched_slot_sightings")) {
                assertEquals(listOf("true"), strings("SELECT DISTINCT trusted::text FROM $table"), table)
            }
        }
    }
}
