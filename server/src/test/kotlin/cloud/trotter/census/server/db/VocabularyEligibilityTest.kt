package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class VocabularyEligibilityTest : LifecycleTestDatabase() {
    /** #85: the fixtures' word hash as stored, keyed under the test pepper; the queue and resolve speak this value. */
    private val storedToken = testKeying.token("0123456789abcdef")

    @Test fun `seven elapsed days and nine versus ten contributors agree across queue count display resolve`() = runBlocking {
        Database.connect(config()).use { db ->
            // #79: quarantine counts whole UTC days after the enrolment day (Sol S6); the tenth enrolled on day - 7,
            // which may have been 23:59, so it qualifies only tomorrow, when seven full days have certainly passed.
            // #86: ingest stamps that day onto the tenth's sightings, which no longer join the installs table.
            val ids = List(9) { install() } + install(age = 7)
            for (id in ids) observe(db, id)
            val tenth = ids.last()
            val ops = OpsStore(db, clock, policy)
            assertEquals(0, ops.vocabularyQueueCount()); assertTrue(ops.vocabularyQueueDisplay().isEmpty())
            assertTrue(ops.vocabularyQueue().isEmpty())
            assertFalse(ops.resolve(storedToken, "reviewed", "corpus", false))
            instant = instant.plusSeconds(86_400)
            assertEquals(1, ops.vocabularyQueueCount()); assertEquals(10, ops.vocabularyQueueDisplay().single().distinctInstalls)
            assertEquals(storedToken, ops.vocabularyQueue().single().jsonObject["tokenHash"]!!.jsonPrimitive.content)
            assertTrue(ops.resolve(storedToken, "reviewed", "corpus", false))
            ops.trust(tenth, true)
            assertEquals(1, LifecycleStore(db, clock, policy).runOnce().getValue("vocabulary").deleted)
            assertTrue(ops.vocabularyQueue().isEmpty())
            ops.trust(tenth, false); ops.revoke(tenth)
            assertTrue(ops.vocabularyQueue().isEmpty())
        }
    }

    @Test fun `stale tenth withdraw reenrol and duplicate surfaces never pool revisions or domains`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = List(10) { install() }
            for (id in ids) repeat(2) { observe(db, id, listOf(item(1), item(2), screenItem())); sightDomain2(id) }
            val ops = OpsStore(db, clock, policy.copy(acceptedHashDomains=listOf(1,2)))
            assertEquals(3, ops.vocabularyQueueCount())
            assertTrue(ops.vocabularyQueueDisplay().all { it.distinctInstalls == 10 })
            sql { it.update("UPDATE token_sightings_v5 SET last_day=? WHERE install_tag=? AND hash_domain=1 AND filter_rev=1", day.minusDays(30), tokenTag(ids.last())) }
            assertEquals(2, ops.vocabularyQueueCount())
            assertFalse(ops.resolve(storedToken, null, "corpus", true, 1, 1))
            assertTrue(ops.resolve(storedToken, null, "corpus", true, 1, 2))
            InstallStore(db, clock).withdraw(ids.last(), key)
            InstallStore(db, clock).enrol(ids.last(), key, "1.0")
            observe(db, ids.last(), listOf(item(),item(2)))
            assertTrue(ops.vocabularyQueue().isEmpty())
            sql { it.update("UPDATE token_sightings_v5 SET last_day=?", day.minusDays(30)) }
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0, count("vocabulary_v5"))
        }
    }

    @Test fun `resolution racing withdrawal cannot retain a live eligible entry`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = List(10) { install() }; for (id in ids) observe(db,id)
            val ops = OpsStore(db,clock,policy)
            val gate = CompletableDeferred<Unit>()
            val workers = listOf(async(Dispatchers.IO) { gate.await(); ops.resolve(storedToken, "reviewed", "corpus", false) },
                async(Dispatchers.IO) { gate.await(); InstallStore(db,clock).withdraw(ids.last(),key) })
            gate.complete(Unit); workers.awaitAll()
            assertEquals(0,ops.vocabularyQueueCount())
            LifecycleStore(db,clock,policy).runOnce()
            assertEquals(0,count("vocabulary_v5"))
        }
    }

    @Test fun `a sighting stored after promotion locks its cohort is not counted`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = List(9) { install() }; for (id in ids) observe(db, id)
            val tenth = install()
            val ops = OpsStore(db, clock, policy)
            // #86 (Sol S5): the tenth install's sighting commits between the lock and the count.
            ops.afterResolveLock = { runBlocking { observe(db, tenth) } }
            assertFalse(ops.resolve(storedToken, "reviewed", "corpus", false))
            ops.afterResolveLock = null
            assertEquals(0, count("vocabulary_v5"))
            assertEquals(1, ops.vocabularyQueueCount())
            assertTrue(ops.resolve(storedToken, "reviewed", "corpus", false))
        }
    }

    @Test fun `a withdrawal of a counted install waits for the promotion that counted it`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = List(10) { install() }; for (id in ids) observe(db, id)
            val ops = OpsStore(db, clock, policy)
            val executor = Executors.newSingleThreadExecutor()
            try {
                var withdrawal: Future<MutationOutcome>? = null
                ops.afterResolveLock = {
                    withdrawal = executor.submit<MutationOutcome> { runBlocking { InstallStore(db, clock).withdraw(ids.last(), key) } }
                    // The withdrawal reaches the locked sighting rows and waits there.
                    val deadline = System.nanoTime() + 30_000_000_000L
                    while (sql { it.select("""SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'DELETE FROM token_sightings_v5%'""") { r -> r.getInt(1) }!! } == 0) {
                        check(System.nanoTime() < deadline) { "withdrawal never waited on the cohort lock" }
                        Thread.sleep(10)
                    }
                }
                assertTrue(ops.resolve(storedToken, "reviewed", "corpus", false))
                ops.afterResolveLock = null
                assertTrue(withdrawal!!.get(30, TimeUnit.SECONDS) is MutationOutcome.Applied)
                assertEquals(10, sql { it.select("SELECT distinct_installs_at_promotion FROM vocabulary_v5") { r -> r.getInt(1) } })
                // Nine remain: the sweep removes the entry whose evidence fell below k.
                assertEquals(0, ops.vocabularyQueueCount())
                assertEquals(1, LifecycleStore(db, clock, policy).runOnce().getValue("vocabulary").deleted)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
