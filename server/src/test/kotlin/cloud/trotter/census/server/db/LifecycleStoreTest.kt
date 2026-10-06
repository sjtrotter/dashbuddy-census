package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class LifecycleStoreTest : LifecycleTestDatabase() {
    @Test fun `per install token TTL and orphan sample rewrite use exact partition`() = runBlocking {
        Database.connect(config()).use { db ->
            val first = install(); val second = install()
            observe(db, first, listOf(item(), item(2)))
            sightDomain2(second)
            sql { it.update("UPDATE token_sightings_v5 SET last_day=? WHERE hash_domain=1", day.minusDays(30)) }
            val result = LifecycleStore(db, clock, policy).runOnce()
            assertEquals(2, result.getValue("tokens").deleted)
            assertEquals(1, result.getValue("samples_notification").rewritten)
            assertFalse(sql { it.select("SELECT skeleton::text FROM cluster_samples") { r -> r.getString(1) }!! }.contains("0123456789abcdef"))
            assertEquals(1, count("token_sightings_v5"))
            assertEquals(0, LifecycleStore(db, clock, policy).runOnce().getValue("samples_notification").rewritten)
            observe(db, second, listOf(item(channel = "ORPHAN_CHANNEL")))
            InstallStore(db, clock).withdraw(second, key)
            assertEquals(1, LifecycleStore(db, clock, policy).runOnce().getValue("samples_notification").rewritten)
        }
    }

    @Test fun `both kinds rewrite orphan copies independently including nested and window slots`() = runBlocking {
        Database.connect(config()).use { db ->
            val id=install(); val accepted=listOf(item(),screenItem())
            observe(db,id,accepted)
            InstallStore(db,clock).withdraw(id,key)
            val lifecycle=LifecycleStore(db,clock,policy)
            assertEquals(1,lifecycle.remainingDue().getValue("samples_screen"))
            assertEquals(1,lifecycle.remainingDue().getValue("samples_notification"))
            val results=lifecycle.runOnce()
            for (kind in listOf("screen","notification")) assertEquals(1,results.getValue("samples_$kind").rewritten)
            sql { it.select("SELECT skeleton::text FROM cluster_samples") { rows -> do {
                assertFalse(rows.getString(1).contains("0123456789abcdef")); assertFalse(rows.getString(1).contains("\"h\""))
            } while(rows.next()) } }
            assertEquals(accepted.map { it.item.fingerprint }.toSet(),sql { it.select("SELECT fingerprint FROM cluster_samples") { rows ->
                buildSet { do { add(rows.getString(1)) } while(rows.next()) }
            } })
        }
    }

    @Test fun `trusted envelope selection and saved draft expire before physical deletion`() = runBlocking {
        Database.connect(config()).use { db ->
            val id=install(trusted=true); val fp="f".repeat(64)
            sql {
                it.update("INSERT INTO clusters(fingerprint,platform,first_seen_day,last_seen_day) VALUES(?,'doordash',?,?)",fp,day,day)
                it.update("INSERT INTO trusted_envelopes(install_id,fingerprint,envelope,received_day,purge_after) VALUES(?,?,'{}',?,?)",id,fp,day,day.plusDays(30))
            }
            val ops=OpsStore(db,clock,policy)
            val capture=ops.pinnedEnvelope(fp)!!
            assertTrue(ops.saveDraft(fp,"idle",kotlinx.serialization.json.buildJsonObject { put("envelopeId",kotlinx.serialization.json.JsonPrimitive(capture.id)) },"PRIVATE_DRAFT",day))
            instant=instant.plusSeconds(29*86400L)
            assertNotNull(ops.pinnedEnvelope(fp)); assertEquals("PRIVATE_DRAFT",ops.draftJson5(fp))
            instant=instant.plusSeconds(86400)
            assertNull(ops.pinnedEnvelope(fp)); assertNull(ops.draftJson5(fp)); assertFalse(ops.cluster(fp)!!.hasDraft)
            assertEquals(1,count("trusted_envelopes"))
            assertEquals(1,InstallStore(db,clock).purgeTrustedEnvelopes(day))
            assertNull(sql { it.select("SELECT draft FROM clusters WHERE fingerprint=?",fp) { r -> r.getString(1) } })
        }
    }

    @Test fun `one continuing install cannot refresh the expired tenth contributor`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids=List(10) { install() }; for(id in ids) observe(db,id)
            sql { it.update("UPDATE token_sightings_v5 SET last_day=?",day.minusDays(29)) }
            val ops=OpsStore(db,clock,policy)
            assertEquals(1,ops.vocabularyQueueCount())
            instant=instant.plusSeconds(86400)
            observe(db,ids.first())
            assertEquals(0,ops.vocabularyQueueCount())
            val result=LifecycleStore(db,clock,policy).runOnce()
            assertEquals(9,result.getValue("tokens").deleted)
            assertEquals(1,count("token_sightings_v5"))
        }
    }

    @Test fun `90 day sample and sighting expiry clears notes and catalogue on boundary`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(); val accepted = item(); observe(db, id, listOf(accepted))
            sql { it.update("UPDATE clusters SET notes='PRIVATE_SAMPLE_NOTE'") }
            instant = instant.plusSeconds(89*86400L)
            assertTrue(OpsStore(db, clock, policy).cluster(accepted.item.fingerprint)!!.samples!!.isNotEmpty())
            instant = instant.plusSeconds(86400)
            assertTrue(OpsStore(db, clock, policy).cluster(accepted.item.fingerprint)!!.samples!!.isEmpty())
            assertNull(OpsStore(db, clock, policy).cluster(accepted.item.fingerprint)!!.notes)
            val result = LifecycleStore(db, clock, policy).runOnce()
            assertEquals(1, result.getValue("sightings").deleted)
            assertEquals(1, result.getValue("samples_notification").deleted)
            assertEquals(0, count("clusters"))
        }
    }

    @Test fun `resolution repeated reopening and fresh ingest never extend resolution deadline`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(); val accepted = item(); observe(db, id, listOf(accepted))
            val ops = OpsStore(db, clock, policy)
            ops.status(accepted.item.fingerprint, "resolved", null, "PRIVATE_NOTE")
            val resolved = day
            instant = instant.plusSeconds(29*86400L)
            ops.status(accepted.item.fingerprint, "new", null, null)
            observe(db, id, listOf(item()))
            ops.status(accepted.item.fingerprint, "resolved", null, null)
            assertEquals(resolved, sql { it.select("SELECT resolved_day FROM clusters") { r -> r.getObject(1, java.time.LocalDate::class.java) } })
            assertEquals(2, count("cluster_samples"))
            instant = instant.plusSeconds(86400)
            observe(db, id, listOf(item()))
            assertEquals(2, count("cluster_samples"))
            assertTrue(ops.cluster(accepted.item.fingerprint)!!.samples!!.isEmpty())
            assertEquals(2, LifecycleStore(db, clock, policy).runOnce().getValue("samples_notification").deleted)
        }
    }

    @Test fun `observation day future clamp retries provenance and sample caps`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            observe(db, id, listOf(item(1, observation=day.minusDays(7)), item(2, observation=day.plusDays(1))))
            assertEquals(day.minusDays(7).toString(), sql { it.select("SELECT last_day::text FROM token_sightings_v5 WHERE filter_rev=1") { r -> r.getString(1) } })
            assertEquals(day.toString(), sql { it.select("SELECT last_day::text FROM token_sightings_v5 WHERE filter_rev=2") { r -> r.getString(1) } })
            assertEquals(1, sql { it.select("SELECT filter_rev FROM cluster_samples") { r -> r.getInt(1) } })
            repeat(6) { instant = instant.plusSeconds(86400); observe(db, id, listOf(item(2))) }
            assertEquals(5, count("cluster_samples"))
        }
    }

    @Test fun `inactive install expiry uses parent erasure at 365 without withdrawal tombstone`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(); observe(db, id)
            sql { it.update("UPDATE installs SET last_seen_day=?", day.minusDays(364)) }
            val lifecycle = LifecycleStore(db, clock, policy)
            assertEquals(0, lifecycle.runOnce().getValue("inactive_installs").deleted)
            instant = instant.plusSeconds(86400)
            assertEquals(1, lifecycle.runOnce().getValue("inactive_installs").deleted)
            assertEquals(0, count("installs")); assertEquals(0, count("withdrawals"))
        }
    }

    @Test fun `capped backlog continues failures isolate and cancellation propagates`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            sql { it.update("""INSERT INTO token_sightings_v5 SELECT lpad(to_hex(n),16,'0'), ?, ?, ?, 'words:1', 1, 1
                FROM generate_series(1,50001) n""", id, day.minusDays(31), day.minusDays(30)) }
            val lifecycle = LifecycleStore(db, clock, policy)
            val first = lifecycle.runOnce().getValue("tokens")
            assertEquals(50000, first.deleted); assertTrue(first.capped); assertEquals(1, first.remainingDue)
            assertEquals(1, lifecycle.runOnce().getValue("tokens").deleted)
            observe(db, id)
            sql { it.update("UPDATE token_sightings_v5 SET last_day=?", day.minusDays(30))
                it.update("CREATE FUNCTION fail_lifecycle() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''PRIVATE_FAILURE_SENTINEL''; END;'")
                it.update("CREATE TRIGGER fail_lifecycle BEFORE DELETE ON token_sightings_v5 FOR EACH STATEMENT EXECUTE FUNCTION fail_lifecycle()") }
            try {
                val failed = lifecycle.runOnce()
                assertTrue(failed.getValue("tokens").failed)
                assertFalse(failed.getValue("samples_notification").failed)
                assertEquals(1, failed.getValue("samples_notification").rewritten)
                assertFalse(failed.toString().contains("PRIVATE_FAILURE_SENTINEL"))
                val job = launch(start=CoroutineStart.LAZY) { lifecycle.runOnce() }
                job.cancel(); job.join(); assertTrue(job.isCancelled)
            } finally { sql { it.update("DROP TRIGGER fail_lifecycle ON token_sightings_v5"); it.update("DROP FUNCTION fail_lifecycle()") } }
        }
    }

    @Test fun `cancelling an active sweep stops before the next bounded transaction`() = runBlocking {
        Database.connect(config()).use { db ->
            val id=install()
            sql {
                it.update("""INSERT INTO token_sightings_v5 SELECT lpad(to_hex(n),16,'0'), ?, ?, ?, 'words:1',1,1
                    FROM generate_series(1,5000) n""",id,day.minusDays(31),day.minusDays(30))
                it.update("CREATE FUNCTION pause_lifecycle() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_sleep(0.5); RETURN NULL; END;'")
                it.update("CREATE TRIGGER pause_lifecycle BEFORE DELETE ON token_sightings_v5 FOR EACH STATEMENT EXECUTE FUNCTION pause_lifecycle()")
            }
            try {
                val job=launch(Dispatchers.IO) { LifecycleStore(db,clock,policy).runOnce() }
                withTimeout(10000) {
                    while(sql { it.select("SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND state='active' AND query LIKE 'DELETE FROM token_sightings_v5%'") { r -> r.getInt(1) }!! } == 0) delay(10)
                }
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(count("token_sightings_v5") in 4000L..5000L)
            } finally { sql { it.update("DROP TRIGGER pause_lifecycle ON token_sightings_v5"); it.update("DROP FUNCTION pause_lifecycle()") } }
        }
    }

    @Test fun `concurrent fresh ingest and sample sweep preserve fresh evidence under cluster lock`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(); val accepted = item(); observe(db, id, listOf(accepted))
            sql { it.update("UPDATE token_sightings_v5 SET last_day=?", day.minusDays(30)) }
            val gate = CompletableDeferred<Unit>()
            val workers = listOf(async(Dispatchers.IO) { gate.await(); observe(db, id, listOf(accepted)) },
                async(Dispatchers.IO) { gate.await(); LifecycleStore(db, clock, policy).runOnce() })
            gate.complete(Unit); workers.awaitAll()
            assertEquals(day, sql { it.select("SELECT last_day FROM token_sightings_v5") { r -> r.getObject(1, java.time.LocalDate::class.java) } })
            assertEquals(1, count("cluster_samples"))
            assertEquals(accepted.item.fingerprint, sql { it.select("SELECT fingerprint FROM cluster_samples") { r -> r.getString(1) } })
        }
    }
}
