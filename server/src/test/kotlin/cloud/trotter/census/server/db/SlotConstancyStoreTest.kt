package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.SlotWalk
import cloud.trotter.census.server.ops.ClusterDetailHtml
import cloud.trotter.census.server.testKeying
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * #80: per-slot constancy from synthetic skeletons. Every skeleton here is one screen, a button whose own text is the
 * slot `root/text` and a child text view whose text is `root/0/text`: same structure, so one cluster, one variant.
 */
class SlotConstancyStoreTest : LifecycleTestDatabase() {
    private val chrome = TextSlot("0123456789abcdef", "words:1")
    private val title = "root/text"
    private val value = "root/0/text"

    private fun hash(n: Int): String = "%016x".format(n.toLong() + 0x1000)
    private fun word(n: Int): TextSlot = TextSlot(hash(n), "words:2")

    private fun screenJson(slot: TextSlot, observed: LocalDate = day, version: String = "8.0", wrapped: Boolean = false): JsonObject {
        val leaf = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to slot))
        // An empty anonymous wrapper is spliced out of the fingerprint, but it moves the leaf's raw path (Sol S4).
        val children = if (wrapped) listOf(UiSkeletonNodeDto(className = "android.view.View"), leaf) else listOf(leaf)
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to chrome), children = children)
        val item = UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = version, engineVersion = 1, day = observed.toString(), root = root)
        return Json.parseToJsonElement(SkeletonSchema.serialize(item)).jsonObject
    }

    private fun accepted(json: JsonObject): ItemVerdict.Accepted = SkeletonValidator.validate(json, policy, day) as ItemVerdict.Accepted
    private fun screen(slot: TextSlot, observed: LocalDate = day): ItemVerdict.Accepted = accepted(screenJson(slot, observed))

    private val fingerprint: String get() = screenJson(chrome).getValue("fingerprint").jsonPrimitive.content
    private fun variant(version: String = "8.0", wrapped: Boolean = false) =
        SlotVariant(version, 1, 1, SlotWalk.layout(accepted(screenJson(chrome, version = version, wrapped = wrapped)).item))

    private fun at(on: LocalDate) { instant = on.atTime(12, 0).toInstant(ZoneOffset.UTC) }

    /** Each install's skeletons go in one at a time, as separate frames. */
    private suspend fun see(db: Database, id: UUID, vararg slots: TextSlot) { for (slot in slots) observe(db, id, listOf(screen(slot))) }

    private suspend fun readings(db: Database): List<SlotVariantReading> = OpsStore(db, clock, policy).cluster(fingerprint)!!.slots.orEmpty()

    /** The operator's live classes of one variant, unknown left out. */
    private suspend fun classes(db: Database, of: SlotVariant = variant()): Map<String, SlotClass> =
        readings(db).singleOrNull { it.variant == of }?.slots.orEmpty().filter { it.slotClass != SlotClass.UNKNOWN }.associate { it.slot to it.slotClass }

    private suspend fun operatorView(db: Database): Map<String, SlotReading> = readings(db).single().slots.associateBy { it.slot }

    private fun installs(n: Int, age: Long = 40): List<UUID> = List(n) { install(age = age) }

    @Test fun `one hash on ten installs is constant and k installs short of that is unknown`() = runBlocking {
        Database.connect(config()).use { db ->
            val nine = installs(9)
            for (id in nine) see(db, id, word(1))
            assertEquals(emptyMap<String, SlotClass>(), classes(db))
            assertEquals(SlotClass.UNKNOWN, operatorView(db).getValue(value).slotClass)
            assertEquals(9, operatorView(db).getValue(value).installs)
            see(db, install(), word(1))
            assertEquals(mapOf(title to SlotClass.CONSTANT, value to SlotClass.CONSTANT), classes(db))
            assertEquals(SlotReading(value, SlotClass.CONSTANT, 10, 1), operatorView(db).getValue(value))
        }
    }

    @Test fun `a new hash per frame is varying`() = runBlocking {
        Database.connect(config()).use { db ->
            for ((index, id) in installs(10).withIndex()) see(db, id, word(index * 3), word(index * 3 + 1), word(index * 3 + 2))
            assertEquals(SlotClass.VARYING, classes(db)[value])
            assertEquals(SlotClass.CONSTANT, classes(db)[title])
            assertEquals(30, operatorView(db).getValue(value).hashes)
        }
    }

    @Test fun `several hashes each on several installs is regional, and installs behind several hashes count once at most`() = runBlocking {
        Database.connect(config()).use { db ->
            // Four stores, each seen by three of twelve dashers.
            for ((index, id) in installs(12).withIndex()) see(db, id, word(index % 4))
            assertEquals(SlotClass.REGIONAL, classes(db)[value])
        }
        reset()
        Database.connect(config()).use { db ->
            // Sol S5: three installs each saw the same two values; nine saw one-offs. Two "shared" values, but three
            // installs behind them, a quarter of twelve: varying.
            val ids = installs(12)
            for (id in ids.take(3)) see(db, id, word(1), word(2))
            for ((index, id) in ids.drop(3).withIndex()) see(db, id, word(100 + index))
            assertEquals(SlotClass.VARYING, classes(db)[value])
        }
    }

    @Test fun `a backstop on one install in ten is withheld even beside a constant hash, and shapes are shape`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(10)
            for (id in ids.take(9)) see(db, id, word(1))
            see(db, ids.last(), TextSlot.WITHHELD)
            assertEquals(SlotClass.WITHHELD, classes(db)[value])

            // Another variant of the same cluster, where every install sees a money shape.
            for (id in installs(10)) observe(db, id, listOf(accepted(screenJson(TextSlot.ofShape(ShapeTemplate.MONEY), version = "8.1"))))
            assertEquals(mapOf(title to SlotClass.CONSTANT, value to SlotClass.SHAPE), classes(db, variant("8.1")))
            // Variants are counted apart: 8.0 is unchanged by the shape installs.
            assertEquals(SlotClass.WITHHELD, classes(db)[value])
        }
    }

    @Test fun `raw layouts are counted apart, so a spliced wrapper never puts another node on a label's path`() = runBlocking {
        Database.connect(config()).use { db ->
            // Ten installs see the label at root/0/text; in the wrapped layout root/0 is an empty wrapper and the value
            // sits at root/1/text. Same fingerprint, two layouts.
            for (id in installs(10)) see(db, id, word(1))
            for (id in installs(1)) observe(db, id, listOf(accepted(screenJson(word(999), wrapped = true))))
            assertEquals(fingerprint, screenJson(chrome, wrapped = true).getValue("fingerprint").jsonPrimitive.content)
            assertNotEquals(variant().layout, variant(wrapped = true).layout)
            assertEquals(2, readings(db).size)
            assertEquals(SlotClass.CONSTANT, classes(db)[value])
            assertEquals(1, readings(db).single { it.variant == variant(wrapped = true) }.slots.single { it.slot == "root/1/text" }.installs)
        }
    }

    @Test fun `trusted and quarantined installs never count toward a class`() = runBlocking {
        Database.connect(config()).use { db ->
            for (id in installs(5)) see(db, id, word(1))
            for (id in installs(5, age = 3)) see(db, id, word(1))
            see(db, install(trusted = true), word(1))
            assertEquals(emptyMap<String, SlotClass>(), classes(db))
            assertEquals(5, operatorView(db).getValue(value).installs)
            // The quarantine ends; the marks clear and those five count.
            at(day.plusDays(6))
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, count("slot_sightings WHERE counts_from IS NOT NULL"))
            assertEquals(SlotClass.CONSTANT, classes(db)[value])
        }
    }

    @Test fun `counts never cross epochs`() = runBlocking {
        Database.connect(config()).use { db ->
            val boundary = Epochs.firstDay(Epochs.of(day)).minusDays(1)
            at(boundary)
            val early = installs(5)
            for (id in early) see(db, id, word(1))
            at(boundary.plusDays(1))
            for (id in installs(5)) see(db, id, word(1))
            // Ten installs, five per epoch: below k in each, so unknown.
            assertEquals(emptyMap<String, SlotClass>(), classes(db))
            assertEquals(2L, sql { it.select("SELECT count(DISTINCT epoch) FROM slot_sightings") { r -> r.getLong(1) } })
            // The early five again, now in the later epoch: ten within one epoch.
            for (id in early) see(db, id, word(1))
            assertEquals(SlotClass.CONSTANT, classes(db)[value])
        }
    }

    @Test fun `each value keeps its own last day and expires on it`() = runBlocking {
        Database.connect(config()).use { db ->
            // Sol S3: one batch carries an old frame and a new one; the old value must not ride the new frame's day.
            val id = install()
            val start = day
            observe(db, id, listOf(screen(word(1), observed = start.minusDays(2)), screen(word(2), observed = start)))
            fun lastDay(of: String) = sql { it.select("SELECT last_day FROM slot_sightings WHERE slot = ? AND value = ?", value, of) { r ->
                r.getObject(1, LocalDate::class.java) } }
            assertEquals(start.minusDays(2), lastDay(testKeying.token(hash(1))))
            assertEquals(start, lastDay(testKeying.token(hash(2))))
            assertEquals(start, lastDay("present"))
            at(start.plusDays(28))
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(null, lastDay(testKeying.token(hash(1))))
            assertEquals(start, lastDay(testKeying.token(hash(2))))
        }
    }

    @Test fun `a data deletion and a withdrawal remove the install's contribution`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(11)
            for (id in ids) see(db, id, word(1))
            assertEquals(SlotClass.CONSTANT, classes(db)[value])
            val store = InstallStore(db, clock)
            assertTrue(sql { it.installRows("slot_sightings", ids[0], day) } > 0)
            assertTrue(store.deleteData(ids[0], key, day) is MutationOutcome.Applied)
            assertEquals(0L, sql { it.installRows("slot_sightings", ids[0], day) })
            assertEquals(SlotClass.CONSTANT, classes(db)[value])
            assertTrue(store.withdraw(ids[1], key) is MutationOutcome.Applied)
            assertEquals(0L, sql { it.installRows("slot_sightings", ids[1], day) })
            assertEquals(emptyMap<String, SlotClass>(), classes(db))
            assertEquals(9, operatorView(db).getValue(value).installs)
        }
    }

    @Test fun `rows name no install, store keyed hashes only, and one install's values carry unrelated tags`() = runBlocking {
        Database.connect(config()).use { db ->
            val id = install()
            see(db, id, word(1), word(2))
            val rows = sql { connection -> connection.prepareStatement("SELECT value, install_tag FROM slot_sightings WHERE slot = ?").use { statement ->
                statement.setString(1, value)
                statement.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1) to r.getString(2)) } }
            } }
            assertEquals(setOf("present", testKeying.token(hash(1)), testKeying.token(hash(2))), rows.map { it.first }.toSet())
            assertFalse(rows.any { it.first == hash(1) || it.first == hash(2) })
            assertEquals(3, rows.map { it.second }.toSet().size)
            assertEquals(0L, sql { it.select("SELECT count(*) FROM information_schema.columns WHERE table_name = 'slot_sightings' AND column_name = 'install_id'") { r -> r.getLong(1) } })
        }
    }

    @Test fun `erasure streams a large slot table in bounded chunks`() = runBlocking {
        Database.connect(config()).use { db ->
            // Sol S6: twenty thousand other objects in the epoch, read 50 at a time, tags matched 100 at a time.
            val target = install()
            see(db, target, word(1))
            // Two slots, each a presence row and a value row.
            assertEquals(4L, sql { it.installRows("slot_sightings", target, day) })
            sql { it.update("""INSERT INTO slot_sightings (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value,
                epoch, install_tag, trusted, last_day)
                SELECT ?, '8.0', 1, 1, ?, 'root/' || n || '/text', 'present', ?, md5(n::text) || md5(n::text || 'x'), false, ?
                FROM generate_series(1, 20000) n""", fingerprint, variant().layout, Epochs.of(day), day) }
            var chunks = 0
            val deleted = db.exposed.let {
                org.jetbrains.exposed.v1.jdbc.transactions.transaction(it) {
                    val connection = connection.connection as java.sql.Connection
                    val key = with(EpochKeyStore) { connection.storedKeys(testKeying) }
                    connection.forInstallTags(key, TaggedTable.SLOTS, target,
                        "DELETE FROM slot_sightings WHERE epoch = ? AND install_tag = ANY(?::bpchar[])", chunkSize = 100, fetchSize = 50,
                    ) { epoch, tags -> chunks++; arrayOf(epoch, tags) }
                }
            }
            assertEquals(4, deleted)
            assertTrue(chunks >= 200, "chunks=$chunks")
            assertEquals(20_000L, count("slot_sightings"))
        }
    }

    @Test fun `the cluster page marks each slot's class`() = runBlocking {
        Database.connect(config()).use { db ->
            for (id in installs(10)) see(db, id, word(1))
            val cluster = requireNotNull(OpsStore(db, clock, policy).cluster(fingerprint))
            val html = ClusterDetailHtml.render("test", policy.k, day.toString(), cluster)
            assertTrue(html.contains("Slot constancy"))
            assertTrue(html.contains("<code>$value</code>"))
            assertTrue(html.contains(">constant<"))
            val page = OpsStore(db, clock, policy).clustersPage("doordash", "8.0", null, 1)
            assertEquals(mapOf(SlotClass.CONSTANT to 2), page.clusters.single().slotClasses)
            // Retention: past the cluster sightings' window the rows go with them.
            at(day.plusDays(policy.retention.clusterSightingsDays.toLong()))
            LifecycleStore(db, clock, policy).runOnce()
            assertEquals(0L, count("slot_sightings"))
        }
    }
}
