package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.ContributorPolicy
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.SlotRole
import cloud.trotter.census.server.db.SlotRelease.buildRelease
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.SlotWalk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.concurrent.thread

/**
 * #81: grants, confirmations, the word gate, role tags and the slot classes a contributor reads. Every screen is one
 * cluster: a button whose own text is the chrome slot `root/text` and a child text view whose text is `root/0/text`.
 */
class ContributorTierTest : LifecycleTestDatabase() {
    private val chrome = TextSlot(requireNotNull(CensusHash.of("Order details")), "words:2")
    private val wordText = "Pick up by"
    private val word = TextSlot(requireNotNull(CensusHash.of(wordText)), "words:3")
    private val title = "root/text"
    private val value = "root/0/text"

    private fun other(n: Int): TextSlot = TextSlot("%016x".format(n.toLong() + 0x5000), "words:2")

    private fun screenJson(slot: TextSlot, observed: LocalDate = day): JsonObject {
        val leaf = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to slot))
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to chrome), children = listOf(leaf))
        val item = UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = "8.0", engineVersion = 1, day = observed.toString(), root = root)
        return Json.parseToJsonElement(SkeletonSchema.serialize(item)).jsonObject
    }

    private fun screen(slot: TextSlot, observed: LocalDate = day): ItemVerdict.Accepted =
        SkeletonValidator.validate(screenJson(slot, observed), policy, day) as ItemVerdict.Accepted

    private val scope: SlotScope get() {
        val item = screen(chrome).item
        return SlotScope(item.fingerprint, SlotVariant("8.0", 1, 1, SlotWalk.layout(item)))
    }

    private fun at(on: LocalDate) { instant = on.atTime(12, 0).toInstant(ZoneOffset.UTC) }

    private suspend fun see(db: Database, id: UUID, slot: TextSlot, observed: LocalDate = day) = observe(db, id, listOf(screen(slot, observed)))

    private fun contributors(db: Database, limits: ContributorPolicy = ContributorPolicy()) =
        ContributorStore(db, clock, policy.copy(contributor = limits))

    private suspend fun grant(db: Database, id: UUID): GrantView =
        (contributors(db).requestGrant(id, key, true, day) as GrantOutcome.Granted).view

    private fun grantId(id: UUID): UUID = sql { it.select("SELECT grant_id FROM contributor_grants WHERE install_id = ?", id) { r -> r.getObject(1, UUID::class.java) }!! }

    /** Builds today's release as the sweep does, in its own committed transaction. */
    private fun publish(on: LocalDate = day): Int? = sql { connection ->
        connection.autoCommit = false
        val copied = connection.buildRelease(policy, on)
        connection.commit()
        copied
    }

    private suspend fun classes(db: Database, id: UUID, store: ContributorStore = contributors(db)): Map<String, SlotClass>? =
        (store.classes(id, key, day, scope) as? ContributorOutcome.Ok)?.value?.toMap()

    private suspend fun gate(db: Database, id: UUID, slot: String = value, hash: String = word.h!!): Boolean =
        (contributors(db).gates(id, key, day, scope, listOf(slot to hash)) as ContributorOutcome.Ok).value.single()

    private suspend fun confirm(db: Database, id: UUID, slot: String = value, hash: String = word.h!!): ContributorOutcome<ReviewResult> =
        contributors(db).review(id, key, day, scope, listOf(slot to hash), emptyList())

    private fun installs(n: Int): List<UUID> = List(n) { install(age = 40) }

    @Test fun `a grant needs a yes and an install past quarantine, lasts 30 days, and a revoked one refuses uploads and requests`() = runBlocking {
        Database.connect(config()).use { db ->
            val store = contributors(db)
            assertEquals(GrantOutcome.TooNew, store.requestGrant(install(age = 2), key, true, day))
            val id = install(age = 40)
            assertEquals(GrantOutcome.NotEligible, store.requestGrant(id, key, false, day))
            assertEquals(0L, count("contributor_grants"))
            assertEquals(GrantOutcome.Stale, store.requestGrant(id, "b".repeat(64), true, day))
            val granted = grant(db, id)
            assertEquals(GrantView(ContributorStore.GRANTED, day, day.plusDays(30)), granted)
            assertEquals(granted, grant(db, id))
            assertEquals(1L, count("contributor_grants"))
            // Contributor status is not trust: the installs row is untouched.
            assertEquals(false, sql { it.select("SELECT trusted FROM installs WHERE install_id = ?", id) { r -> r.getBoolean(1) } })

            see(db, id, word)
            assertEquals(ContributorOutcome.Ok(ReviewResult(1, 0, 0)), confirm(db, id))
            // An expired grant is refused, in the transaction that would write.
            at(day.plusDays(30))
            assertEquals(ContributorOutcome.NoGrant, confirm(db, id))
            assertEquals(ContributorOutcome.NoGrant, store.classes(id, key, day, scope))
            assertEquals(ContributorStore.EXPIRED, store.grantStatus(id, key, day)?.status)
            assertEquals(day.plusDays(30), grant(db, id).expiresDay)
            see(db, id, word)
            assertEquals(1, (confirm(db, id) as ContributorOutcome.Ok).value.confirmations)

            // Revocation: uploads stop, the install's confirmations go, and a new request is refused until cleared.
            val live = sql { it.select("SELECT grant_id FROM contributor_grants WHERE install_id = ? AND expires_day > ?", id, day) { r -> r.getObject(1, UUID::class.java) }!! }
            assertTrue(store.setRevoked(live, true))
            assertEquals(ContributorOutcome.NoGrant, confirm(db, id))
            assertEquals(GrantOutcome.Revoked, store.requestGrant(id, key, true, day))
            assertEquals(ContributorStore.REVOKED, store.grantStatus(id, key, day)?.status)
            assertEquals(0L, sql { it.installRows("slot_confirmations", id, day) })
            assertTrue(store.setRevoked(live, false))
            assertTrue(store.requestGrant(id, key, true, day) is GrantOutcome.Granted)
        }
    }

    @Test fun `two confirmations from one install count once, and a word is refused until a second install confirms and the release is constant`() = runBlocking {
        Database.connect(config()).use { db ->
            // Eleven installs show the word: with its own rows left out, each still sees k = 10 others.
            val ids = installs(11)
            for (id in ids) see(db, id, word)
            val (a, b, c) = ids
            for (id in listOf(a, b, c)) grant(db, id)
            // A and B also saw a second value here, and confirm it below: it is not the slot's constant value.
            val stray = other(7)
            for (id in listOf(a, b)) see(db, id, stray)
            val store = contributors(db)
            val items = listOf(WordItem(value, word.h!!, wordText))

            // Before any confirmation and any release: refused.
            assertEquals(ContributorOutcome.Ok(listOf(WordStatus.GATE_CLOSED)), store.words(a, key, day, scope, items))
            assertEquals(1, (confirm(db, a) as ContributorOutcome.Ok).value.confirmations)
            assertEquals(1, (confirm(db, a) as ContributorOutcome.Ok).value.confirmations)
            assertEquals(1L, count("slot_confirmations"))
            assertEquals(1, sql { ContributorGate.run { it.confirmations(policy, day, scope, value, testKeying().token(word.h!!)) } })
            publish()
            assertEquals(false, gate(db, a))
            assertEquals(ContributorOutcome.Ok(listOf(WordStatus.GATE_CLOSED)), store.words(a, key, day, scope, items))

            assertEquals(1, (confirm(db, b) as ContributorOutcome.Ok).value.confirmations)
            assertEquals(true, gate(db, a))
            assertEquals(true, gate(db, b))
            // The gate answers only about values the asker itself confirmed.
            assertEquals(false, gate(db, c))
            // A value that is not the slot's constant value never opens, however many confirm it.
            confirm(db, a, value, stray.h!!); confirm(db, b, value, stray.h!!)
            assertEquals(2, sql { ContributorGate.run { it.confirmations(policy, day, scope, value, testKeying().token(stray.h!!)) } })
            assertEquals(false, gate(db, a, value, stray.h!!))

            assertEquals(ContributorOutcome.Ok(listOf(WordStatus.HASH_MISMATCH)), store.words(a, key, day, scope, listOf(WordItem(value, word.h!!, "Pick up at"))))
            assertEquals(ContributorOutcome.Ok(listOf(WordStatus.NOT_CHROME)), store.words(a, key, day, scope, listOf(WordItem(value, word.h!!, "12 Main St"))))
            assertEquals(0L, count("contributor_words"))
            assertEquals(ContributorOutcome.Ok(listOf(WordStatus.ACCEPTED)), store.words(a, key, day, scope, items))
            val pending = store.pendingWords().single()
            assertEquals(wordText, pending.clearText)
            assertEquals(testKeying().token(word.h!!), pending.value)

            // The operator's resolve (source contributor) takes it into the working vocabulary and drops the waiting copy.
            val ops = OpsStore(db, clock, policy)
            assertTrue(ops.resolve(pending.value, wordText, "contributor", reject = false))
            assertEquals(0L, count("contributor_words"))
            assertEquals("unblinded", sql { it.select("SELECT status FROM vocabulary_v5 WHERE token_hash = ?", pending.value) { r -> r.getString(1) } })
        }
    }

    @Test fun `the gate stays closed on the release day before the evidence lands, and the live rows must agree`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(11)
            for (id in ids) see(db, id, word)
            val (a, b) = ids
            for (id in listOf(a, b)) grant(db, id)
            confirm(db, a); confirm(db, b)
            assertEquals(false, gate(db, a), "no release built yet")
            publish()
            assertEquals(true, gate(db, a))
            // The live rows must agree with the release: three peers' live sightings go (as retention would take
            // them), the release still holds them, and with the reader left out the live slot falls below k.
            val keys = sql { connection -> with(EpochKeyStore) { connection.storedKeys(testKeying()) } }
            for (id in ids.drop(8)) sql { TaggedTable.SLOTS.erase(it, keys, id) }
            assertEquals(false, gate(db, a))
            // And erasure removes an install from the release as well: the gate stays closed.
            for (id in ids.drop(8)) InstallStore(db, clock).deleteData(id, key, day)
            assertEquals(false, gate(db, a))
        }
    }

    @Test fun `a reader's own sightings never open its own gate`() = runBlocking {
        Database.connect(config()).use { db ->
            // Ten installs, k = 10: the slot is constant, but each confirmer sees only nine others.
            val ids = installs(10)
            for (id in ids) see(db, id, word)
            val (a, b) = ids
            for (id in listOf(a, b)) { grant(db, id); confirm(db, id) }
            publish()
            assertEquals(false, gate(db, a))
            assertEquals(false, gate(db, b))
            // An eleventh install: open, from the next day's release on.
            see(db, install(age = 40), word)
            assertEquals(false, gate(db, a), "today's release does not hold the new upload")
            at(day.plusDays(1))
            publish()
            assertEquals(true, gate(db, a))
        }
    }

    @Test fun `role tags carry no value, count one install once per epoch and show on the operator's cluster page`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(3)
            for (id in ids) { see(db, id, word); grant(db, id) }
            val store = contributors(db)
            for (id in ids) {
                assertEquals(ContributorOutcome.Ok(ReviewResult(0, 1, 0)),
                    store.review(id, key, day, scope, emptyList(), listOf(value to SlotRole.DEADLINE)))
            }
            store.review(ids.first(), key, day, scope, emptyList(), listOf(value to SlotRole.DEADLINE, value to SlotRole.TIP))
            // A slot the install never saw is skipped.
            assertEquals(ContributorOutcome.Ok(ReviewResult(0, 0, 1)),
                store.review(ids.first(), key, day, scope, emptyList(), listOf("root/9/text" to SlotRole.TIP)))
            assertEquals(4L, count("slot_roles"))
            val reading = OpsStore(db, clock, policy).cluster(scope.fingerprint)!!.slots!!.single().slots.single { it.slot == value }
            assertEquals(mapOf("deadline" to 3, "tip" to 1), reading.roles)
            assertEquals(0L, sql { it.select("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('slot_roles', 'slot_confirmations', 'quarantine_items') AND column_name = 'install_id'") { r -> r.getLong(1) }!! })
        }
    }

    @Test fun `the reader's own upload never moves its own classes, and another reader sees it no sooner than the next release`() = runBlocking {
        Database.connect(config()).use { db ->
            // Nine peers share the word: one install short of k. X guesses it and uploads; Y only reads.
            val peers = installs(9)
            for (id in peers) see(db, id, word)
            val (x, y) = installs(2)
            grant(db, x); grant(db, y)
            publish()
            val before = classes(db, x)
            assertEquals(emptyMap<String, SlotClass>(), before)
            see(db, x, word)
            assertEquals(before, classes(db, x), "an upload today is not in today's release")
            assertEquals(emptyMap<String, SlotClass>(), classes(db, y))

            at(day.plusDays(1))
            publish()
            // X's guess is now in the release, and X's own answer is computed without it: unchanged.
            assertEquals(before, classes(db, x))
            // The residual SECURITY.md states: a second, colluding reader sees the guess land a day later.
            assertEquals(mapOf(title to SlotClass.CONSTANT, value to SlotClass.CONSTANT), classes(db, y))
        }
    }

    @Test fun `a release is served on its own day only and expires even when the next build fails`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(11)
            for (id in ids) see(db, id, word)
            val reader = ids.first()
            grant(db, reader)
            assertEquals(ContributorOutcome.ReleaseUnavailable, contributors(db).classes(reader, key, day, scope))
            publish()
            assertEquals(mapOf(title to SlotClass.CONSTANT, value to SlotClass.CONSTANT), classes(db, reader))
            val built = day
            at(day.plusDays(1))
            // No build today (a failed sweep): yesterday's release is refused from midnight.
            assertEquals(ContributorOutcome.ReleaseUnavailable, contributors(db).classes(reader, key, day, scope))
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, sql { it.select("SELECT count(*) FROM slot_release WHERE release_day = ?", built) { r -> r.getLong(1) }!! })
            assertTrue(sql { it.select("SELECT count(*) FROM slot_release WHERE release_day = ?", day) { r -> r.getLong(1) }!! } > 0)

            // A release counts only rows inside retention on its own day.
            at(built.plusDays(policy.retention.clusterSightingsDays.toLong()))
            grant(db, reader)
            publish()
            assertEquals(emptyMap<String, SlotClass>(), classes(db, reader))
        }
    }

    @Test fun `classes read the erasure marker, the release and the reader's rows in one snapshot`() = runBlocking {
        Database.connect(config()).use { db ->
            val peers = installs(9)
            for (id in peers) see(db, id, word)
            val x = install(age = 40)
            grant(db, x)
            see(db, x, word)
            publish()
            val store = contributors(db)
            var isolation: String? = null
            // Sol N1: X's data is deleted (its release rows included) after the read's checks, before its evidence.
            store.readSeam = { connection ->
                isolation = connection.select("SHOW transaction_isolation") { it.getString(1) }
                thread { runBlocking { InstallStore(db, clock).deleteData(x, key, day) } }.join()
            }
            assertEquals(emptyMap<String, SlotClass>(), classes(db, x, store))
            assertEquals("repeatable read", isolation)
            assertEquals(0L, sql { it.installRows("slot_release", x, day) })
            store.readSeam = null
            // The deletion took the live grant; a missing install fails closed.
            assertEquals(ContributorOutcome.NoGrant, store.classes(x, key, day, scope))
            InstallStore(db, clock).withdraw(x, key)
            assertEquals(ContributorOutcome.Stale, store.classes(x, key, day, scope))
        }
    }

    @Test fun `a publication during a read never mixes into it`() = runBlocking {
        Database.connect(config()).use { db ->
            val peers = installs(9)
            for (id in peers) see(db, id, word)
            val reader = install(age = 40)
            grant(db, reader)
            publish()
            val late = install(age = 40)
            see(db, late, word)
            val store = contributors(db)
            // Today's release is replaced while the read runs: it answers from the release it started with.
            store.readSeam = {
                thread {
                    sql { connection ->
                        connection.autoCommit = false
                        connection.update("DELETE FROM slot_release_days")
                        connection.buildRelease(policy, day)
                        connection.commit()
                    }
                }.join()
            }
            assertEquals(emptyMap<String, SlotClass>(), classes(db, reader, store))
            store.readSeam = null
            assertEquals(mapOf(title to SlotClass.CONSTANT, value to SlotClass.CONSTANT), classes(db, reader, store))
        }
    }

    @Test fun `an erasure during a release build waits for it and then removes the install's copied rows`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(3)
            for (id in ids) see(db, id, word)
            val erased = ids.first()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { build ->
                build.autoCommit = false
                assertTrue((build.buildRelease(policy, day) ?: 0) > 0)
                val deletion = thread { runBlocking { InstallStore(db, clock).deleteData(erased, key, day) } }
                // The deletion is blocked on the release table lock the uncommitted build holds.
                var waited = false
                repeat(200) {
                    if (!waited) waited = sql { it.select("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") { r -> r.getLong(1) }!! } > 0
                    if (!waited) Thread.sleep(25)
                }
                assertTrue(waited, "the erasure must wait for the build")
                build.commit()
                deletion.join()
            }
            assertEquals(0L, sql { it.installRows("slot_release", erased, day) })
            for (id in ids.drop(1)) assertTrue(sql { it.installRows("slot_release", id, day) } > 0)
        }
    }

    @Test fun `released evidence keeps a value behind every presence, so the regional bound holds on it`() = runBlocking {
        Database.connect(config()).use { db ->
            // Sol N3: nine installs saw the shared value A on the epoch's first day and a one-off later; A's old rows
            // expire while their presence, refreshed by the one-offs, stays. Three more installs saw A and B lately.
            val start = Epochs.firstDay(Epochs.of(day))
            at(start)
            val nine = installs(9)
            val a = other(1)
            val b = other(2)
            for (id in nine) see(db, id, a)
            at(start.plusDays(20))
            for ((index, id) in nine.withIndex()) see(db, id, other(100 + index))
            val three = installs(3)
            for (id in three) { see(db, id, a); see(db, id, b) }
            at(start.plusDays(30))
            val reader = install(age = 40)
            grant(db, reader)
            publish()
            sql { connection ->
                connection.prepareStatement("""SELECT epoch, count(*) FILTER (WHERE value = 'present'), count(*) FILTER (WHERE value <> 'present')
                    FROM slot_release WHERE slot = ? GROUP BY epoch""").use { statement ->
                    statement.setString(1, value)
                    statement.executeQuery().use { rows -> while (rows.next()) assertTrue(rows.getInt(3) >= rows.getInt(2)) }
                }
            }
            assertEquals(SlotClass.VARYING, classes(db, reader)?.get(value))
        }
    }

    @Test fun `reads need a grant, spend a daily budget and leave an audit row naming the grant only`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(age = 40)
            see(db, id, word)
            val store = contributors(db, ContributorPolicy(dailyReads = 2))
            assertEquals(ContributorOutcome.NoGrant, store.classes(id, key, day, scope))
            assertEquals(0L, count("contributor_reads"))
            grant(db, id)
            publish()
            repeat(2) { assertTrue(store.classes(id, key, day, scope) is ContributorOutcome.Ok) }
            assertTrue(store.classes(id, key, day, scope) is ContributorOutcome.BudgetExhausted)
            assertTrue(store.gates(id, key, day, scope, listOf(value to word.h!!)) is ContributorOutcome.BudgetExhausted)
            assertEquals(2L, sql { it.select("SELECT count(*) FROM contributor_reads WHERE grant_id = ? AND kind = 'classes'", grantId(id)) { r -> r.getLong(1) }!! })
            // A deletion keeps today's ledger row, so it does not reset the budget for a new grant.
            InstallStore(db, clock).deleteData(id, key, day)
            assertEquals(0L, count("contributor_reads"))
            grant(db, id)
            assertTrue(store.classes(id, key, day, scope) is ContributorOutcome.BudgetExhausted)
        }
    }

    @Test fun `erasure removes confirmations, role tags and released rows, and a data deletion keeps a revoked grant`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(2)
            val (revoked, withdrawn) = ids
            for (id in ids) {
                see(db, id, word)
                grant(db, id)
                contributors(db).review(id, key, day, scope, listOf(value to word.h!!), listOf(value to SlotRole.TIP))
            }
            publish()
            val tables = listOf("slot_confirmations", "slot_roles", "slot_release")
            for (id in ids) for (table in tables) assertEquals(1L, sql { it.installRows(table, id, day) }.coerceAtMost(1), table)
            assertTrue(contributors(db).setRevoked(grantId(revoked), true))
            InstallStore(db, clock).deleteData(revoked, key, day)
            for (table in tables) assertEquals(0L, sql { it.installRows(table, revoked, day) }, table)
            assertEquals(1L, sql { it.installRows("contributor_grants", revoked, day) }, "a revoked grant outlives a data deletion")
            assertEquals(GrantOutcome.Revoked, contributors(db).requestGrant(revoked, key, true, day))
            InstallStore(db, clock).withdraw(withdrawn, key)
            for (table in tables + "contributor_grants") assertEquals(0L, sql { it.installRows(table, withdrawn, day) }, table)
        }
    }

    @Test fun `a trusted install's confirmations never count, and trusting it removes its released rows`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(12)
            for (id in ids) see(db, id, word)
            val (a, b) = ids
            for (id in listOf(a, b)) grant(db, id)
            confirm(db, a); confirm(db, b)
            publish()
            assertEquals(true, gate(db, a))
            assertTrue(OpsStore(db, clock, policy).trust(b, true))
            assertEquals(1, sql { ContributorGate.run { it.confirmations(policy, day, scope, value, testKeying().token(word.h!!)) } })
            assertEquals(false, gate(db, a))
            assertEquals(0L, sql { it.installRows("slot_release", b, day) })
        }
    }

    @Test fun `the lifecycle sweep keeps confirmations, role tags, audit rows and waiting words no longer than 30 days`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install(age = 40)
            see(db, id, word)
            grant(db, id)
            contributors(db).review(id, key, day, scope, listOf(value to word.h!!), listOf(value to SlotRole.TIP))
            publish()
            contributors(db).classes(id, key, day, scope)
            sql { it.update("""INSERT INTO contributor_words (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value,
                clear_text, received_day) VALUES (?, '8.0', 1, 1, ?, ?, ?, ?, ?)""", scope.fingerprint, scope.variant.layout, value,
                testKeying().token(word.h!!), wordText, day) }
            // One confirmation stands behind the word: below the gate's two, so the sweep drops it at once.
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, count("contributor_words"))
            for (table in listOf("slot_confirmations", "slot_roles", "contributor_reads")) assertEquals(1L, count(table), table)
            at(day.plusDays(30))
            LifecycleStore(db, clock, policy).runOnce()
            for (table in listOf("slot_confirmations", "slot_roles", "contributor_reads")) assertEquals(0L, count(table), table)
            // The expired grant goes too once its audit rows have.
            at(day.plusDays(30))
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, count("contributor_grants"))
            assertEquals(0L, LifecycleStore(db, clock, policy).remainingDue().filterKeys { it in setOf("confirmations", "roles", "contributor_reads", "contributor_grants", "quarantine", "contributor_words", "slot_release_expired") }.values.sum())
        }
    }

    private fun testKeying() = cloud.trotter.census.server.testKeying
}
