package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.*
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** #87: clicked skeletons through the route, the store, the lifecycle sweeps, the filter floor, trust and erasure. */
class ClickedIngestTest : LifecycleTestDatabase() {
    private val clusterTables = listOf("clusters", "cluster_samples", "cluster_sightings_v5", "token_sightings_v5", "slot_sightings",
        "matched_samples", "matched_sightings", "matched_slot_sightings")

    /** A phone's clicked batch id: `c-` and 32 hex of the batch's identities (the value is opaque to the server). */
    private fun clickedBatchId(seed: Char) = "c-" + seed.toString().repeat(32)

    private fun batch(id: String, items: List<JsonObject>) =
        JsonObject(mapOf("batchId" to JsonPrimitive(id), "items" to JsonArray(items))).toString()

    private fun strings(query: String, vararg args: Any?): List<String> = sql { connection ->
        connection.prepareStatement(query).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun clickedRows(id: UUID): Long = sql { it.installRows("clicked_sightings", id, null) }

    private suspend fun HttpClient.send(id: String, secret: String, batchId: String, items: List<JsonObject>): JsonObject {
        val response = signed(clock, id, secret, HttpMethod.Post, "/v1/skeletons", batch(batchId, items))
        val text = response.bodyAsText()
        assertEquals(200, response.status.value, text)
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun `a clicked skeleton is stored apart from the clusters, keyed at rest, and a resent batch is a duplicate`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(config(), db, clock, policy = policy) }
            val id = UUID.randomUUID().toString(); val secret = secret(187)
            assertEquals(200, client.enrolSchemas(id, secret, CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS).status.value)
            val item = clickedFixture(day)
            val body = client.send(id, secret, clickedBatchId('a'), listOf(item))
            assertEquals("accepted", body.text("status"))
            assertEquals(1, body.int("accepted"))
            assertEquals(0, body.int("duplicate"))
            val fingerprint = item.getValue("fingerprint").jsonPrimitive.content
            // One anonymous sample and one presence row; nothing in any cluster or matched table.
            assertEquals(listOf("$fingerprint|root/1|doordash|8.1|$day|${day.plusDays(30)}"), strings(
                "SELECT fingerprint || '|' || owner_path || '|' || platform || '|' || platform_app_version || '|' || day || '|' || purge_after FROM clicked_samples"))
            for (table in clusterTables) assertEquals(0L, count(table), table)
            assertEquals(1L, count("clicked_sightings"))
            assertEquals(1, InstallStore(db, clock).ledgerFor(UUID.fromString(id), day)!!.accepted)
            // The stored sample is keyed at rest (#85) and keeps its tap.
            val stored = strings("SELECT skeleton::text FROM clicked_samples").single()
            assertFalse(stored.contains("0123456789abcdef"))
            assertTrue(stored.contains(testKeying.token("0123456789abcdef")))
            val tap = Json.parseToJsonElement(stored).jsonObject.getValue("tap").jsonObject
            assertEquals("root/1", tap.text("ownerPath"))
            assertEquals(CLICK_GENERATION, tap.text("generation"))
            // The layout column is the raw tree's layout id, the one the target is keyed by.
            val accepted = SkeletonValidator.validate(item, policy, day) as ItemVerdict.Accepted
            assertEquals(listOf(SlotWalk.layout(accepted.item)), strings("SELECT layout FROM clicked_sightings"))
            // The same batch again is a duplicate and stores nothing.
            val again = client.send(id, secret, clickedBatchId('a'), listOf(item))
            assertEquals("duplicate", again.text("status"))
            assertEquals(1L, count("clicked_sightings"))
        } }
    }

    @Test fun `two owners of one screen are two targets, and the same tap twice in one batch is one item`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(config(), db, clock, policy = policy) }
            val id = UUID.randomUUID().toString(); val secret = secret(188)
            assertEquals(200, client.enrolSchemas(id, secret, CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS).status.value)
            val button = clickedFixture(day)
            val root = clickedFixture(day, ownerPath = "root", tapPath = "root/0")
            assertEquals(button.getValue("fingerprint"), root.getValue("fingerprint"))
            val body = client.send(id, secret, clickedBatchId('b'), listOf(button, root, button))
            assertEquals(2, body.int("accepted"))
            assertEquals(1, body.int("duplicate"))
            // Keyed by owner path, never by fingerprint alone: two samples, two sightings, two tag objects.
            assertEquals(listOf("root", "root/1"), strings("SELECT owner_path FROM clicked_samples ORDER BY owner_path"))
            assertEquals(listOf("root", "root/1"), strings("SELECT owner_path FROM clicked_sightings ORDER BY owner_path"))
            assertEquals(2, strings("SELECT DISTINCT install_tag FROM clicked_sightings").size)
        } }
    }

    @Test fun `two layouts of one fingerprint stay apart`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            val plain = clickedAccepted(day)
            val wrapped = clickedAccepted(day, extraWrapper = true)
            assertEquals(plain.item.fingerprint, wrapped.item.fingerprint)
            assertNotEquals(SlotWalk.layout(plain.item), SlotWalk.layout(wrapped.item))
            observe(db, id, listOf(plain, wrapped))
            // One owner path, two raw layouts: two samples and two sightings, under unrelated tags.
            assertEquals(2, strings("SELECT DISTINCT layout FROM clicked_samples").size)
            assertEquals(listOf("root/1", "root/1"), strings("SELECT owner_path FROM clicked_sightings"))
            assertEquals(2, strings("SELECT DISTINCT install_tag FROM clicked_sightings").size)
            // Each tag is this install's tag for its own target object.
            for (layout in listOf(SlotWalk.layout(plain.item), SlotWalk.layout(wrapped.item))) {
                val tag = tag(TagPurpose.CLICKED, id, ClickedTarget(plain.item.fingerprint, layout, "root/1").objectKey)
                assertEquals(listOf(tag), strings("SELECT install_tag FROM clicked_sightings WHERE layout = ?", layout))
            }
        }
    }

    @Test fun `split halves and a backlogged day each carry their own batch id and none is a false duplicate`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(config(), db, clock, policy = policy) }
            val id = UUID.randomUUID().toString(); val secret = secret(189)
            assertEquals(200, client.enrolSchemas(id, secret, CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS).status.value)
            // A 413 split sends each half under its own id; a frozen batch of an earlier day goes the same UTC day.
            val first = client.send(id, secret, clickedBatchId('c'), listOf(clickedFixture(day)))
            val second = client.send(id, secret, clickedBatchId('d'), listOf(clickedFixture(day, ownerPath = "root", tapPath = "root/0")))
            val backlog = client.send(id, secret, clickedBatchId('e'), listOf(clickedFixture(day.minusDays(3))))
            for (body in listOf(first, second, backlog)) {
                assertEquals("accepted", body.text("status"))
                assertEquals(1, body.int("accepted"))
                assertEquals(0, body.int("duplicate"))
            }
            assertEquals(3L, count("clicked_sightings"))
            assertEquals(listOf(day.minusDays(3).toString(), day.toString(), day.toString()),
                strings("SELECT day::text FROM clicked_sightings ORDER BY day, owner_path"))
            // A retry of one half resends its frozen bytes under the same id: a duplicate, nothing stored twice.
            assertEquals("duplicate", client.send(id, secret, clickedBatchId('d'), listOf(clickedFixture(day, ownerPath = "root", tapPath = "root/0"))).text("status"))
            assertEquals(3L, count("clicked_sightings"))
            assertEquals(3, InstallStore(db, clock).ledgerFor(UUID.fromString(id), day)!!.accepted)
        } }
    }

    @Test fun `the lifecycle sweeps expire clicked rows on their own days and report what is due`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            val first = day
            observe(db, id, listOf(clickedAccepted(day)))
            instant = instant.plusSeconds(86_400)
            observe(db, id, listOf(clickedAccepted(day)))
            assertEquals(2L, count("clicked_samples"))
            assertEquals(2L, count("clicked_sightings"))

            // At first + 30 the first day's sample and sighting are due; the second day's are not.
            instant = instant.plusSeconds(29 * 86_400L)
            assertEquals(first.plusDays(30), day)
            val lifecycle = LifecycleStore(db, clock, policy)
            val due = lifecycle.remainingDue()
            assertEquals(1L, due.getValue("clicked_samples"))
            assertEquals(1L, due.getValue("clicked_sightings"))
            val result = lifecycle.runOnce()
            assertEquals(1L, result.getValue("clicked_samples").deleted)
            assertEquals(1L, result.getValue("clicked_sightings").deleted)
            assertEquals(listOf(first.plusDays(1).toString()), strings("SELECT day::text FROM clicked_samples"))
            assertEquals(listOf(first.plusDays(1).toString()), strings("SELECT day::text FROM clicked_sightings"))
            assertEquals(0L, lifecycle.remainingDue().filterKeys { it.startsWith("clicked_") }.values.sum())

            // A day later the rest goes.
            instant = instant.plusSeconds(86_400)
            lifecycle.runOnce()
            for (table in listOf("clicked_samples", "clicked_sightings")) assertEquals(0L, count(table), table)
        }
    }

    @Test fun `a late upload's sample expires with its observation day, and a future day is clamped to receipt`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            val observed = day.minusDays(7)
            observe(db, id, listOf(SkeletonValidator.validate(clickedFixture(observed), policy, day) as ItemVerdict.Accepted))
            assertEquals(listOf("$day|${observed.plusDays(30)}|$observed"),
                strings("SELECT s.received_day::text || '|' || s.purge_after::text || '|' || x.day::text FROM clicked_samples s, clicked_sightings x"))
            val ahead = SkeletonValidator.validate(clickedFixture(day.plusDays(1), rootId = "com.example.app:id/later"), policy, day) as ItemVerdict.Accepted
            observe(db, id, listOf(ahead))
            assertEquals(listOf("$day|${day.plusDays(30)}"),
                strings("SELECT day::text || '|' || purge_after::text FROM clicked_samples WHERE fingerprint = ?", ahead.item.fingerprint))
        }
    }

    @Test fun `raising the filter floor deletes clicked rows below it`() = runBlocking {
        Database.connect(config()).use { db ->
            val floors = FilterPolicyStore(db, clock)
            assertEquals(1, floors.bootstrap(1))
            val id = install()
            observe(db, id, listOf(clickedAccepted(day, filterRev = 1), clickedAccepted(day, filterRev = 2, rootId = "com.example.app:id/two")))
            assertEquals(2L, count("clicked_samples"))
            assertEquals(2, floors.bootstrap(2))
            for (table in listOf("clicked_samples", "clicked_sightings")) {
                assertEquals(listOf("2"), strings("SELECT DISTINCT filter_rev::text FROM $table"), table)
            }
        }
    }

    @Test fun `a trust change marks the install's clicked rows like its other sightings`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            observe(db, id, listOf(clickedAccepted(day)))
            assertEquals(listOf("false"), strings("SELECT DISTINCT trusted::text FROM clicked_sightings"))
            assertTrue(OpsStore(db, clock, policy).trust(id, true))
            assertEquals(listOf("true"), strings("SELECT DISTINCT trusted::text FROM clicked_sightings"))
            assertTrue(OpsStore(db, clock, policy).trust(id, false))
            assertEquals(listOf("false"), strings("SELECT DISTINCT trusted::text FROM clicked_sightings"))
        }
    }

    @Test fun `revocation, data deletion and withdrawal erase one install's clicked sightings and keep another's and the sample`() = runBlocking {
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            suspend fun pair(): Pair<UUID, UUID> {
                val mine = install()
                val other = install()
                for (id in listOf(mine, other)) observe(db, id, listOf(clickedAccepted(day)))
                assertEquals(1L, clickedRows(mine))
                assertEquals(1L, clickedRows(other))
                return mine to other
            }
            fun erased(mine: UUID, other: UUID, name: String) {
                assertEquals(0L, clickedRows(mine), name)
                assertEquals(1L, clickedRows(other), name)
            }
            val (revoked, revokedOther) = pair()
            assertTrue(installs.revoke(revoked, instant))
            erased(revoked, revokedOther, "revocation")
            val (deleted, deletedOther) = pair()
            assertTrue(installs.deleteData(deleted, key, day) is MutationOutcome.Applied)
            erased(deleted, deletedOther, "data deletion")
            val (withdrawn, withdrawnOther) = pair()
            assertTrue(installs.withdraw(withdrawn, key) is MutationOutcome.Applied)
            erased(withdrawn, withdrawnOther, "withdrawal")
            // The anonymous sample names no install and stays until its purge day.
            assertEquals(1L, count("clicked_samples"))
        }
    }

    @Test fun `the restore replay erases a withdrawn install's restored clicked sightings and keeps another's`() = runBlocking {
        Database.connect(config()).use { db ->
            val installs = InstallStore(db, clock)
            val (mine, other) = install() to install()
            for (id in listOf(mine, other)) observe(db, id, listOf(clickedAccepted(day)))
            val withdrawn = installs.withdraw(mine, key) as MutationOutcome.Applied
            val journal = listOf(InstallStore.installIdHash(mine) to requireNotNull(withdrawn.withdrawnAt))
            assertEquals(0L, clickedRows(mine))
            // A backup older than the withdrawal: the install and its rows are back, the tombstone is gone.
            sql { connection ->
                connection.update("""INSERT INTO installs(install_id,key_hash,created_day,last_seen_day,trusted) VALUES(?,?,?,?,false)""",
                    mine, key, day.minusDays(8), day)
                connection.update("DELETE FROM withdrawals")
            }
            observe(db, mine, listOf(clickedAccepted(day)))
            assertEquals(1L, clickedRows(mine))
            installs.mergeWithdrawalJournal(journal)
            assertEquals(1, installs.reapplyWithdrawals().matched)
            assertEquals(0L, clickedRows(mine))
            assertEquals(1L, clickedRows(other))
            assertEquals(1L, count("clicked_samples"))
        }
    }
}
