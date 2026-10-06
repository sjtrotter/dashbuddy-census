package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FilterReplayTest : LifecycleTestDatabase() {
    @Test fun `legacy provenance cutover scrubs valid bodies deletes malformed and expires resolved samples idempotently`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(); val sample = item(); observe(db,id,listOf(sample))
            sql {
                it.update("INSERT INTO token_sightings VALUES('0123456789abcdef',?,?,?,'words:1')",id,day,day)
                it.update("INSERT INTO cluster_sightings VALUES(?,?,?,'8.0',1)",sample.item.fingerprint,id,day)
                it.update("INSERT INTO vocabulary VALUES('0123456789abcdef','words:1',10,?,NULL,NULL,NULL,'rejected')",day)
                it.update("UPDATE cluster_samples SET hash_domain=NULL,filter_rev=NULL,purge_after=NULL")
                it.update("UPDATE clusters SET notes='PRIVATE_NOTE',screen_class='HOME'")
            }
            val floors = FilterPolicyStore(db,clock)
            assertEquals(1,floors.bootstrap(1))
            assertEquals(0,count("token_sightings")); assertEquals(0,count("cluster_sightings")); assertEquals(0,count("vocabulary"))
            val body = sql { it.select("SELECT skeleton::text FROM cluster_samples") { r -> r.getString(1) }!! }
            assertFalse(body.contains("0123456789abcdef")); assertTrue(body.contains("expired"))
            assertEquals("HOME",sql { it.select("SELECT screen_class FROM clusters") { r -> r.getString(1) } })
            assertNull(sql { it.select("SELECT notes FROM clusters") { r -> r.getString(1) } })
            assertEquals(1,floors.bootstrap(1)); assertEquals(0,floors.results.getValue("legacy_samples").rewritten)
            sql { it.update("INSERT INTO token_sightings VALUES('0123456789abcdef',?,?,?,'words:1')",id,day,day) }
            assertTrue(runCatching { floors.bootstrap(1) }.isFailure)
        }
    }

    @Test fun `resolved and unattributable legacy samples receive no new retention grace`() = runBlocking {
        Database.connect(config()).use { db ->
            val id=install()
            observe(db,id,listOf(item(channel="RESOLVED"),item(channel="MALFORMED")))
            sql {
                it.update("UPDATE cluster_samples SET hash_domain=NULL,filter_rev=NULL,purge_after=NULL")
                it.update("UPDATE clusters SET status='resolved' WHERE fingerprint=?",item(channel="RESOLVED").item.fingerprint)
                it.update("UPDATE cluster_samples SET skeleton='{}'::jsonb WHERE fingerprint=?",item(channel="MALFORMED").item.fingerprint)
            }
            FilterPolicyStore(db,clock).bootstrap(1)
            assertEquals(0,count("cluster_samples"))
            observe(db,id,listOf(item(channel="RESOLVED")))
            assertEquals(0,count("cluster_samples"))
        }
    }

    @Test fun `older dump with new journal cannot restore removed revision or withdrawn generation`() = runBlocking {
        Database.connect(config()).use { db ->
            val id=install(); observe(db,id,listOf(item(1),item(2,channel="SAFE_CHANNEL")))
            val floors=FilterPolicyStore(db,clock)
            floors.bootstrap(1)
            val withdrawnAt=instant
            InstallStore(db,clock).withdraw(id,key)
            val tombstone=InstallStore.installIdHash(id) to withdrawnAt
            assertEquals(2,floors.bootstrap(2))
            val journal=floors.current()!!
            // Recreate the old dump's install/evidence/floor state, then merge the latest off-host journals.
            sql {
                it.update("DELETE FROM withdrawals")
                it.update("UPDATE filter_floor SET min_filter_rev=1")
                it.update("""INSERT INTO installs(install_id,key_hash,created_day,last_seen_day,enrolled_at)
                    VALUES(?,?,?,?,?)""",id,key,day.minusDays(7),day,instant.minusSeconds(7*86400L).atOffset(java.time.ZoneOffset.UTC))
            }
            observe(db,id,listOf(item(1)))
            val survivor=install()
            observe(db,survivor,listOf(item(1),item(2,channel="SAFE_CHANNEL")))
            val installs=InstallStore(db,clock)
            installs.mergeWithdrawalJournal(listOf(tombstone))
            assertEquals(1,installs.reapplyWithdrawals().matched)
            assertEquals(2,floors.bootstrap(1,journal))
            LifecycleStore(db,clock,policy.copy(minimumFilterRev=2)).runOnce()
            assertEquals(1,count("installs"))
            assertEquals(1,count("token_sightings_v5"))
            assertEquals(0,sql { it.select("SELECT count(*) FROM cluster_sightings_v5 WHERE filter_rev < 2") { r -> r.getLong(1) } })
            assertEquals(2,floors.bootstrap(1))
        }
    }

    @Test fun `monotonic floor removes exact mixed revisions and never rolls down`() = runBlocking {
        Database.connect(config()).use { db ->
            val id=install(); observe(db,id,listOf(item(1),item(2)))
            val store=FilterPolicyStore(db,clock)
            store.bootstrap(1)
            assertEquals(2,store.bootstrap(2))
            assertEquals(1,count("token_sightings_v5")); assertEquals(1,count("cluster_sightings_v5"))
            assertEquals(0,count("cluster_samples")) // Selected body was revision 1, not the group's last item.
            assertEquals(2,store.bootstrap(1,FilterFloorJournal(1,day)))
            assertEquals(2,store.current()!!.minimumFilterRev)
        }
    }
}
