package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class VocabularyEligibilityTest : LifecycleTestDatabase() {
    @Test fun `seven elapsed days and nine versus ten contributors agree across queue count display resolve`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = List(10) { install() }
            for (id in ids) observe(db, id)
            val tenth = ids.last()
            sql { it.update("UPDATE installs SET enrolled_at=? WHERE install_id=?", instant.minusSeconds(7*86400L - 1).atOffset(ZoneOffset.UTC), tenth) }
            val ops = OpsStore(db, clock, policy)
            assertEquals(0, ops.vocabularyQueueCount()); assertTrue(ops.vocabularyQueueDisplay().isEmpty())
            assertTrue(ops.vocabularyQueue().isEmpty())
            assertFalse(ops.resolve("0123456789abcdef", "reviewed", "corpus", false))
            instant = instant.plusSeconds(1)
            assertEquals(1, ops.vocabularyQueueCount()); assertEquals(10, ops.vocabularyQueueDisplay().single().distinctInstalls)
            assertEquals("0123456789abcdef", ops.vocabularyQueue().single().jsonObject["tokenHash"]!!.jsonPrimitive.content)
            assertTrue(ops.resolve("0123456789abcdef", "reviewed", "corpus", false))
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
            sql { it.update("UPDATE token_sightings_v5 SET last_day=? WHERE install_id=? AND hash_domain=1 AND filter_rev=1", day.minusDays(30), ids.last()) }
            assertEquals(2, ops.vocabularyQueueCount())
            assertFalse(ops.resolve("0123456789abcdef", null, "corpus", true, 1, 1))
            assertTrue(ops.resolve("0123456789abcdef", null, "corpus", true, 1, 2))
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
            val workers = listOf(async(Dispatchers.IO) { gate.await(); ops.resolve("0123456789abcdef", "reviewed", "corpus", false) },
                async(Dispatchers.IO) { gate.await(); InstallStore(db,clock).withdraw(ids.last(),key) })
            gate.complete(Unit); workers.awaitAll()
            assertEquals(0,ops.vocabularyQueueCount())
            LifecycleStore(db,clock,policy).runOnce()
            assertEquals(0,count("vocabulary_v5"))
        }
    }
}
