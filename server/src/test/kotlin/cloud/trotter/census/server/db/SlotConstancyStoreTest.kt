package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.enrol
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.module
import cloud.trotter.census.server.ops.ClusterDetailHtml
import cloud.trotter.census.server.secret
import cloud.trotter.census.server.signed
import cloud.trotter.census.server.testKeying
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private fun screenJson(slot: TextSlot, observed: LocalDate = day, version: String = "8.0"): JsonObject {
        val child = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to slot))
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to chrome), children = listOf(child))
        val item = UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = version, engineVersion = 1, day = observed.toString(), root = root)
        return Json.parseToJsonElement(SkeletonSchema.serialize(item)).jsonObject
    }

    private fun screen(slot: TextSlot, observed: LocalDate = day): ItemVerdict.Accepted =
        SkeletonValidator.validate(screenJson(slot, observed), policy, day) as ItemVerdict.Accepted

    private val fingerprint: String get() = screenJson(chrome).getValue("fingerprint").jsonPrimitive.content

    private fun at(on: LocalDate) { instant = on.atTime(12, 0).toInstant(ZoneOffset.UTC) }

    /** Each install's skeletons go in one at a time, as separate frames. */
    private suspend fun see(db: Database, id: UUID, vararg slots: TextSlot) { for (slot in slots) observe(db, id, listOf(screen(slot))) }

    private suspend fun classes(db: Database): Map<String, SlotClass> = SlotStore(db, clock, policy)
        .appClasses(SkeletonKind.SCREEN, fingerprint, SlotVariant("8.0", 1, 1))

    private suspend fun operatorView(db: Database): Map<String, SlotReading> =
        OpsStore(db, clock, policy).cluster(fingerprint)!!.slots.orEmpty().single().slots.associateBy { it.slot }

    private fun installs(n: Int, age: Long = 40): List<UUID> = List(n) { install(age = age) }

    @Test fun `one hash on ten installs is constant and k installs short of that is unknown`() = runBlocking {
        Database.connect(config()).use { db ->
            val nine = installs(9)
            for (id in nine) see(db, id, word(1))
            // Nine of ten: below k, so nothing reaches the app, and the operator sees unknown.
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

    @Test fun `several hashes each on several installs is regional`() = runBlocking {
        Database.connect(config()).use { db ->
            // Four stores, each seen by three of twelve dashers.
            for ((index, id) in installs(12).withIndex()) see(db, id, word(index % 4))
            assertEquals(SlotClass.REGIONAL, classes(db)[value])
        }
    }

    @Test fun `a backstop on one install in ten is withheld even beside a constant hash, and shapes are shape`() = runBlocking {
        Database.connect(config()).use { db ->
            val ids = installs(10)
            for (id in ids.take(9)) see(db, id, word(1))
            see(db, ids.last(), TextSlot.WITHHELD)
            assertEquals(SlotClass.WITHHELD, classes(db)[value])

            // Another variant of the same cluster, where every install sees a money shape.
            val shapes = installs(10)
            for (id in shapes) observe(db, id, listOf(SkeletonValidator.validate(
                screenJson(TextSlot.ofShape(ShapeTemplate.MONEY), version = "8.1"), policy, day) as ItemVerdict.Accepted))
            assertEquals(mapOf(title to SlotClass.CONSTANT, value to SlotClass.SHAPE), SlotStore(db, clock, policy)
                .appClasses(SkeletonKind.SCREEN, fingerprint, SlotVariant("8.1", 1, 1)))
            // Variants are counted apart: 8.0 is unchanged by the shape installs.
            assertEquals(SlotClass.WITHHELD, classes(db)[value])
        }
    }

    @Test fun `trusted and quarantined installs never count toward a class`() = runBlocking {
        Database.connect(config()).use { db ->
            for (id in installs(5)) see(db, id, word(1))
            for (id in installs(5, age = 3)) see(db, id, word(1))
            val trusted = install(trusted = true)
            see(db, trusted, word(1))
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

    @Test fun `the app endpoint returns classes only, nothing below k, and refuses malformed queries`() {
        Database.connect(config()).use { db ->
            runBlocking {
                for (id in installs(10)) see(db, id, word(1))
                for (id in installs(4)) observe(db, id, listOf(SkeletonValidator.validate(screenJson(word(1), version = "9.0"), policy, day) as ItemVerdict.Accepted))
            }
            testApplication {
                application { module(config(), db, clock, policy = policy) }
                val id = UUID.randomUUID().toString(); val secret = secret(80)
                assertEquals(200, client.enrol(id, secret).status.value)
                fun query(version: String, revision: Int = 1, fp: String = fingerprint) = JsonObject(mapOf(
                    "kind" to JsonPrimitive("screen"), "fingerprint" to JsonPrimitive(fp),
                    "platformAppVersion" to JsonPrimitive(version), "hashDomain" to JsonPrimitive(1), "filterRev" to JsonPrimitive(revision),
                ))
                val unseen = screenJson(chrome).getValue("fingerprint").jsonPrimitive.content.reversed()
                val body = JsonObject(mapOf("items" to JsonArray(listOf(query("8.0"), query("9.0"), query("8.0", fp = unseen))))).toString()
                val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/slot-classes", body)
                assertEquals(200, response.status.value)
                val expected = """{"items":[
                    {"fingerprint":"$fingerprint","slots":{"$title":"constant","$value":"constant"}},
                    {"fingerprint":"$fingerprint","slots":{}},
                    {"fingerprint":"$unseen","slots":{}}]}"""
                assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(response.bodyAsText()))
                // No hash, keyed or public, and no count ever appears.
                val text = response.bodyAsText()
                assertFalse(text.contains(hash(1)) || text.contains(testKeying.token(hash(1))) || text.contains("installs"))

                for (bad in listOf(
                    """{"items":[]}""",
                    JsonObject(mapOf("items" to JsonArray(List(9) { query("8.0") }))).toString(),
                    JsonObject(mapOf("items" to JsonArray(listOf(JsonObject(query("8.0") + ("extra" to JsonPrimitive(1))))))).toString(),
                    JsonObject(mapOf("items" to JsonArray(listOf(query("8.0", fp = "nothex"))))).toString(),
                    JsonObject(mapOf("items" to JsonArray(listOf(JsonObject(query("8.0") + ("kind" to JsonPrimitive("envelope"))))))).toString(),
                    "not json",
                )) {
                    val refused = client.signed(clock, id, secret, HttpMethod.Post, "/v1/slot-classes", bad)
                    assertEquals(400, refused.status.value, bad)
                }
                assertEquals(401, client.signed(clock, id, secret(81), HttpMethod.Post, "/v1/slot-classes", body).status.value)
            }
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
