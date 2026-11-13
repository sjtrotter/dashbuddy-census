package cloud.trotter.census.server.db

import cloud.trotter.census.server.Database
import cloud.trotter.census.server.EpochKey
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.ingest.BudgetPolicy
import cloud.trotter.census.server.ingest.HealthReport
import cloud.trotter.census.server.testKeying
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** #86: stored rows carry epoch pseudonyms; a destroyed epoch key leaves its rows unlinkable; k never spans epochs. */
class EpochPseudonymTest : LifecycleTestDatabase() {
    /** Noon on [on]: the test clock. */
    private fun at(on: LocalDate) { instant = on.atTime(12, 0).toInstant(ZoneOffset.UTC) }

    /** The last day of the epoch before the one holding the default test day, so the next day crosses a boundary. */
    private val boundary: LocalDate = Epochs.firstDay(Epochs.of(LocalDate.of(2026, 10, 6))).minusDays(1)

    private data class Row(val table: String, val epoch: Int, val tag: String, val objectKey: String)

    /** Every tagged content row as a dump holds it: epoch, tag, and the object the tag covers. */
    private fun dump(): List<Row> = sql { connection ->
        fun rows(table: String, sql: String) = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(Row(table, rows.getInt(1), rows.getString(2), rows.getString(3))) } }
        }
        rows("token_sightings_v5", "SELECT epoch, install_tag, hash_domain || ':' || filter_rev || ':' || token_hash FROM token_sightings_v5") +
            rows("cluster_sightings_v5", "SELECT epoch, install_tag, fingerprint FROM cluster_sightings_v5") +
            rows("health_daily", "SELECT epoch, install_tag, '' FROM health_daily") +
            rows("slot_sightings", "SELECT epoch, install_tag, ${SlotObject.SQL} FROM slot_sightings")
    }

    private fun Connection.storedKeys(): List<EpochKey> = prepareStatement("SELECT epoch, wrapped FROM epoch_keys").use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(EpochKey.unwrap(testKeying, rows.getInt(1), rows.getBytes(2))) } }
    }

    private val purposes = mapOf("token_sightings_v5" to TagPurpose.TOKEN, "cluster_sightings_v5" to TagPurpose.CLUSTER,
        "health_daily" to TagPurpose.HEALTH, "slot_sightings" to TagPurpose.SLOT)

    /** Which of [installs] each row belongs to, by recomputing every tag under every key the database still holds. */
    private fun owners(rows: List<Row>, installs: List<UUID>): Map<Row, UUID?> = sql { connection ->
        val keys = connection.storedKeys()
        rows.associateWith { row ->
            installs.singleOrNull { id -> keys.any { key -> key.tag(purposes.getValue(row.table), id, row.objectKey) == row.tag } }
        }
    }

    private suspend fun report(db: Database, id: UUID, reportDay: LocalDate) = assertEquals(HealthOutcome.Stored,
        HealthStore(db, clock).upsert(id, key, day, listOf(HealthReport(reportDay, "doordash", "8.0", "1.0.0", "v1", 10, 1, 0, emptyMap())),
            emptyMap(), 1, BudgetPolicy()))

    @Test fun `a two-epoch dump cannot join one install's rows across epochs once the older key is destroyed`() = runBlocking {
        Database.connect(config()).use { db ->
            at(boundary)
            val (first, second) = install(age = 40) to install(age = 40)
            for (id in listOf(first, second)) { observe(db, id); report(db, id, day) }
            at(boundary.plusDays(1))
            for (id in listOf(first, second)) { observe(db, id); report(db, id, day) }
            val older = Epochs.of(boundary)
            val rows = dump()
            assertEquals(setOf(older, older + 1), rows.map { it.epoch }.toSet())
            // No row names an install, and no tag appears in both epochs: a join on the tag finds nothing.
            val tagsByEpoch = rows.groupBy({ it.epoch }, { it.tag }).mapValues { it.value.toSet() }
            assertTrue(tagsByEpoch.getValue(older).intersect(tagsByEpoch.getValue(older + 1)).isEmpty())
            // While both keys live, the server (holding them) can still tie every row to its install.
            assertTrue(owners(rows, listOf(first, second)).values.none { it == null })

            // Two epochs on, the older key is destroyed. The rows below stand in for a dump or backup taken earlier.
            at(Epochs.firstDay(older + 2))
            assertEquals(1, LifecycleStore(db, clock, policy).runOnce().getValue("epoch_keys").deleted)
            val olderRows = rows.filter { it.epoch == older }
            assertTrue(olderRows.isNotEmpty())
            // With every key the server still holds, no row of the older epoch can be tied to either install.
            assertTrue(owners(olderRows, listOf(first, second)).values.all { it == null })
            assertTrue(owners(rows.filter { it.epoch == older + 1 }, listOf(first, second)).values.none { it == null })
        }
    }

    @Test fun `withdrawal deletes every live-epoch row and the restore replay skips rows under a destroyed key`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = InstallStore(db, clock)
            val newest = Epochs.of(boundary) + 1
            at(Epochs.firstDay(newest - 2))
            val id = UUID.randomUUID()
            assertEquals(EnrolOutcome.Created, store.enrol(id, key, "1.0"))
            val other = install(age = 40)
            // Three epochs of rows; creating the newest epoch's key destroys the oldest one's.
            for (epoch in newest - 2..newest) {
                at(Epochs.firstDay(epoch).plusDays(1))
                for (who in listOf(id, other)) { observe(db, who); report(db, who, day) }
            }
            assertEquals(listOf(newest - 1, newest), sql { it.storedKeys() }.map { it.epoch }.sorted())
            val before = dump()
            assertEquals(setOf(newest - 2, newest - 1, newest), before.map { it.epoch }.toSet())

            val withdrawn = store.withdraw(id, key) as MutationOutcome.Applied
            val after = dump()
            // Every row of the install under a live key is gone, the other install's rows all stay, and the rows
            // under the destroyed key stay, unlinkable (they would also be past retention).
            val owned = owners(before, listOf(id, other))
            assertEquals(before.filter { owned[it] == other }.toSet(), after.filter { it.epoch >= newest - 1 }.toSet())
            assertEquals(before.filter { it.epoch == newest - 2 }.toSet(), after.filter { it.epoch == newest - 2 }.toSet())
            assertEquals(listOf(2, 2, 2), listOf("token_sightings_v5", "cluster_sightings_v5", "health_daily").map { withdrawn.deletedRows.getValue(it) })

            // A restore from a backup taken before the withdrawal brings the install and its rows back.
            sql { connection ->
                connection.update("INSERT INTO installs (install_id, key_hash, created_day, last_seen_day) VALUES (?, ?, ?, ?)", id, key, day, day)
                for (row in before.filter { it.epoch == newest - 2 || owned[it] == id }) restore(connection, row)
            }
            val replay = store.reapplyWithdrawals()
            assertEquals(1, replay.matched)
            val restored = dump()
            // The replay erased the live-epoch rows and skipped the rows under the destroyed key without failing.
            assertEquals(after.toSet(), restored.toSet())
            assertEquals(0L, sql { it.installRows("token_sightings_v5", id, day) + it.installRows("cluster_sightings_v5", id, day) + it.installRows("health_daily", id, day) })
        }
    }

    @Test fun `deletion after a boundary but before the sweep removes rows under a key that has expired but still exists`() = runBlocking {
        Database.connect(config()).use { db ->
            at(boundary)
            val (id, other) = install(age = 40) to install(age = 40)
            for (who in listOf(id, other)) { observe(db, who); report(db, who, day) }
            val older = Epochs.of(boundary)
            // Two boundaries later, no sweep has run and no new key was created: the older key is expired but stored.
            at(Epochs.firstDay(older + 2).plusDays(1))
            assertEquals(listOf(older), sql { it.storedKeys() }.map { it.epoch })
            val before = dump()
            val applied = InstallStore(db, clock).deleteData(id, key, day) as MutationOutcome.Applied
            // #86 (Sol S4): erasure uses every stored key, so the install's rows under it go; the other install's stay.
            assertEquals(listOf(1, 1, 1), listOf("token_sightings_v5", "cluster_sightings_v5", "health_daily").map { applied.deletedRows.getValue(it) })
            val owned = owners(before, listOf(id, other))
            assertEquals(before.filter { owned[it] == other }.toSet(), dump().toSet())
            assertEquals(0L, sql { connection -> TaggedTable.entries.sumOf { connection.installRows(it.table, id, null) } })
        }
    }

    /** Re-inserts a dumped row with placeholder content, as a restore would bring it back. */
    private fun restore(connection: Connection, row: Row) {
        val day = Epochs.firstDay(row.epoch)
        when (row.table) {
            "token_sightings_v5" -> {
                val (domain, revision, token) = row.objectKey.split(':')
                connection.update("""INSERT INTO token_sightings_v5 (token_hash, epoch, install_tag, trusted, first_day, last_day, kind, hash_domain, filter_rev)
                    VALUES (?, ?, ?, false, ?, ?, 'words:1', ?, ?) ON CONFLICT DO NOTHING""", token, row.epoch, row.tag, day, day, domain.toInt(), revision.toInt())
            }
            "cluster_sightings_v5" -> connection.update("""INSERT INTO cluster_sightings_v5 (fingerprint, epoch, install_tag, trusted, day, platform_app_version, hash_domain, filter_rev)
                VALUES (?, ?, ?, false, ?, 'restored', 1, 1) ON CONFLICT DO NOTHING""", row.objectKey, row.epoch, row.tag, day)
            "slot_sightings" -> {
                val fields = row.objectKey.split(':', limit = 7)
                connection.update("""INSERT INTO slot_sightings (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value,
                    epoch, install_tag, trusted, first_received, last_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?) ON CONFLICT DO NOTHING""",
                    fields[0], fields[6], fields[1].toInt(), fields[2].toInt(), fields[3], fields[4], fields[5], row.epoch, row.tag, day, day)
            }
            else -> connection.update("""INSERT INTO health_daily (epoch, install_tag, day, platform, platform_app_version, admitted, unknown, trips, rule_counts)
                VALUES (?, ?, ?, 'doordash', 'restored', 1, 0, 0, '{}') ON CONFLICT DO NOTHING""", row.epoch, row.tag, day)
        }
    }

    @Test fun `k is counted within one epoch and never by adding epochs together`() = runBlocking {
        Database.connect(config()).use { db ->
            val ops = OpsStore(db, clock, policy)
            at(boundary)
            val early = List(5) { install(age = 40) }
            for (id in early) observe(db, id)
            at(boundary.plusDays(1))
            val late = List(5) { install(age = 40) }
            for (id in late) observe(db, id)
            // Ten installs in all, but five per epoch: below k = 10 in each, so nothing queues and the cluster stays blind.
            assertEquals(0, ops.vocabularyQueueCount())
            val fingerprint = stored(item()).item.fingerprint
            assertEquals(5, ops.cluster(fingerprint)!!.distinctInstalls28d)
            assertTrue(!ops.cluster(fingerprint)!!.unblinded)
            // The early five again, now in the later epoch: ten distinct installs within one epoch.
            for (id in early) observe(db, id)
            assertEquals(1, ops.vocabularyQueueCount())
            assertEquals(10, ops.vocabularyQueueDisplay().single().distinctInstalls)
            assertEquals(10, ops.cluster(fingerprint)!!.distinctInstalls28d)
        }
    }

    @Test fun `a late health report for the previous epoch's day joins its row and the fleet fold counts the install once`() = runBlocking {
        Database.connect(config()).use { db ->
            at(boundary)
            val (first, second) = install(age = 40) to install(age = 40)
            for (id in listOf(first, second)) report(db, id, boundary)
            at(boundary.plusDays(1))
            report(db, first, boundary)
            assertEquals(2L, sql { it.select("SELECT count(*) FROM health_daily WHERE day = ?", boundary) { r -> r.getLong(1) } })
            assertEquals(2, sql { it.select("SELECT installs_reporting FROM health_fleet_daily WHERE day = ?", boundary) { r -> r.getInt(1) } })
            // The fold, then forget: totals survive the per-install rows (#79), keyed by tags now.
            at(boundary.plusDays(30))
            assertEquals(2, InstallStore(db, clock).purgeHealthDaily(boundary))
            assertEquals(2, sql { it.select("SELECT installs_reporting FROM health_fleet_daily WHERE day = ?", boundary) { r -> r.getInt(1) } })
        }
    }

    @Test fun `trust revocation and quarantine travel with the rows`() = runBlocking {
        Database.connect(config()).use { db ->
            at(LocalDate.of(2026, 10, 6))
            val ops = OpsStore(db, clock, policy)
            val fresh = install(age = 3)
            val trusted = install(age = 40)
            for (id in listOf(fresh, trusted)) observe(db, id)
            assertEquals(day.minusDays(3).plusDays(8), sql { it.select("SELECT counts_from FROM token_sightings_v5 WHERE install_tag = ?", tokenTag(fresh)) { r -> r.getObject(1, LocalDate::class.java) } })
            assertTrue(ops.trust(trusted, true))
            assertEquals(listOf(true, true), sql { connection ->
                listOf("token_sightings_v5" to tokenTag(trusted), "cluster_sightings_v5" to tag(TagPurpose.CLUSTER, trusted, stored(item()).item.fingerprint)).map { (table, tag) ->
                    connection.select("SELECT trusted FROM $table WHERE install_tag = ?", tag) { r -> r.getBoolean(1) }!!
                }
            })
            assertTrue(ops.cluster(stored(item()).item.fingerprint)!!.seenByTrusted)
            // The mark is cleared once its day passes, so it does not outlive the install's first week.
            at(day.plusDays(4))
            assertEquals(0L, LifecycleStore(db, clock, policy).runOnce().getValue("quarantine_marks").rewritten)
            at(day.plusDays(1))
            assertEquals(1L, LifecycleStore(db, clock, policy).runOnce().getValue("quarantine_marks").rewritten)
            assertEquals(0L, count("token_sightings_v5 WHERE counts_from IS NOT NULL"))
            // Revocation deletes the install's evidence, which k counting can no longer filter out by a join.
            assertEquals(1L, sql { it.installRows("token_sightings_v5", fresh, day) })
            assertTrue(ops.revoke(fresh))
            assertEquals(0L, sql { it.installRows("token_sightings_v5", fresh, day) + it.installRows("cluster_sightings_v5", fresh, day) })
        }
    }

    @Test fun `epoch keys are random per epoch and older ones are destroyed`() {
        val today = LocalDate.of(2026, 10, 6)
        val epoch = Epochs.of(today)
        sql { connection ->
            val keys = (epoch - 1..epoch + 1).map { with(EpochKeyStore) { connection.keyForTagging(testKeying, it, today) } }
            val id = UUID.randomUUID()
            assertEquals(3, keys.map { it.tag(TagPurpose.HEALTH, id, "") }.toSet().size)
            assertTrue(runCatching { with(EpochKeyStore) { connection.keyForTagging(testKeying, epoch - 2, today) } }.isFailure)
            assertEquals(listOf(epoch + 1, epoch, epoch - 1), with(EpochKeyStore) { connection.liveKeys(testKeying, today) }.map { it.epoch })
            assertEquals(1, with(EpochKeyStore) { connection.destroyExpired(Epochs.firstDay(epoch + 1)) })
            assertEquals(listOf(epoch + 1, epoch), with(EpochKeyStore) { connection.liveKeys(testKeying, Epochs.firstDay(epoch + 1)) }.map { it.epoch })
        }
    }
}
