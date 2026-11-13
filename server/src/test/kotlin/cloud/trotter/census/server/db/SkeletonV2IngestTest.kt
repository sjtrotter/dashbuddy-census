package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.*
import cloud.trotter.census.server.ops.ClusterDetailHtml
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** #78: v2 skeletons through the route, the store, the ops views and the lifecycle, beside the v1 data already held. */
class SkeletonV2IngestTest : LifecycleTestDatabase() {
    private val actionHash = "fedcba9876543210"

    private fun batch(id: String, items: List<JsonObject>) =
        JsonObject(mapOf("batchId" to JsonPrimitive(id), "items" to JsonArray(items))).toString()

    private fun shapeOf(item: JsonObject, shape: String): JsonObject {
        val root = item.getValue("root").jsonObject
        val children = root.getValue("children").jsonArray
        val pay = children[0].jsonObject
        val slot = JsonObject(mapOf("kind" to JsonPrimitive("shape"), "shape" to JsonPrimitive(shape)))
        val changed = JsonObject(pay + ("text" to JsonObject(mapOf("text" to slot))))
        return JsonObject(item + ("root" to JsonObject(root + ("children" to JsonArray(listOf(changed) + children.drop(1))))))
    }

    @Test fun `a phone moving from v1 to v2 keeps its clusters and the route refuses bad shapes and v1 items with v2 keys`() {
        Database.connect(config()).use { db -> testApplication {
            application { module(config(), db, clock, policy = policy) }
            val id = UUID.randomUUID().toString(); val secret = secret(178)
            assertEquals(200, client.enrolSchemas(id, secret, CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS).status.value)
            val screenV2 = screenV2Fixture(day)
            val notificationV2 = notificationV2Fixture(day)

            // The v1 data a populated server already holds, then the same screens from the upgraded phone.
            for ((name, items) in listOf("old" to listOf(asV1(screenV2), asV1(notificationV2)), "new" to listOf(screenV2, notificationV2))) {
                val response = client.signed(clock, id, secret, HttpMethod.Post, "/v1/skeletons", batch(name, items))
                assertEquals(200, response.status.value, name)
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(2, body.getValue("accepted").jsonPrimitive.int, name)
                assertEquals(JsonObject(emptyMap()), body.getValue("rejected"), name)
            }
            val screenFingerprint = screenV2.getValue("fingerprint").jsonPrimitive.content
            val notificationAccepted = SkeletonValidator.validate(notificationV2, policy, day) as ItemVerdict.Accepted
            val notificationKey = stored(notificationAccepted).item.fingerprint
            assertEquals(2L, count("clusters"))
            assertEquals(setOf(screenFingerprint, notificationKey), sql { it.select("SELECT jsonb_agg(fingerprint)::text FROM clusters") { r ->
                Json.parseToJsonElement(r.getString(1)).jsonArray.map { e -> e.jsonPrimitive.content }.toSet()
            } })
            // One sample per app version in each cluster: the v1 one (8.0) and the v2 one (8.1).
            assertEquals(4L, count("cluster_samples"))
            assertEquals(4L, count("cluster_sightings_v5"))
            val storedV2 = sql { it.select("SELECT skeleton::text FROM cluster_samples WHERE fingerprint = ? AND platform_app_version = '8.1'", notificationKey) { r -> r.getString(1) } }
            assertEquals(Json.parseToJsonElement(stored(notificationAccepted).canonicalJson), Json.parseToJsonElement(requireNotNull(storedV2)))
            assertFalse(storedV2.contains(actionHash))
            assertTrue(storedV2.contains(testKeying.token(actionHash)))
            assertEquals("words:2", sql { it.select("SELECT kind FROM token_sightings_v5 WHERE token_hash = ?", testKeying.token(actionHash)) { r -> r.getString(1) } })

            // The route itself refuses a shape outside the list and a v1 item carrying a v2 key.
            val outsideList = shapeOf(screenV2, "\$##.##")
            val v1Root = asV1(screenV2).getValue("root").jsonObject
            val v1WithV2Key = JsonObject(asV1(screenV2) + ("root" to JsonObject(v1Root + ("hasClickAction" to JsonPrimitive(false)))))
            val refused = client.signed(clock, id, secret, HttpMethod.Post, "/v1/skeletons", batch("refused", listOf(outsideList, v1WithV2Key)))
            assertEquals(422, refused.status.value)
            assertEquals(Json.parseToJsonElement("""{"error":"batch_quality","rejected":{"bad_shape":1,"unknown_field":1}}"""),
                Json.parseToJsonElement(refused.bodyAsText()))
            assertEquals(4L, count("cluster_samples"))

            // The ops views serve the old and the new samples side by side.
            runBlocking {
                val ops = OpsStore(db, clock, policy)
                val screen = requireNotNull(ops.cluster(screenFingerprint)).samples.orEmpty().associateBy { it.platformAppVersion }
                val newScreen = requireNotNull(screen.getValue("8.1").skeleton)
                val oldScreen = requireNotNull(screen.getValue("8.0").skeleton)
                assertEquals(listOf("shape:\$#.##"), newScreen.children[0].kinds)
                assertEquals(listOf("mixed"), oldScreen.children[0].kinds)
                assertTrue(newScreen.clickable && oldScreen.clickable)
                assertTrue(newScreen.children[1].clickAction); assertFalse(oldScreen.children[1].clickAction)
                assertTrue(newScreen.children[2].hidden); assertFalse(oldScreen.children[2].hidden)

                val notification = requireNotNull(ops.cluster(notificationKey))
                val samples = notification.samples.orEmpty().associateBy { it.platformAppVersion }
                val newNotification = requireNotNull(samples.getValue("8.1").notification)
                val oldNotification = requireNotNull(samples.getValue("8.0").notification)
                assertEquals(listOf("words:2", "withheld"), newNotification.actions)
                assertEquals("shape:\$#.##", newNotification.slots.getValue(NotifTextField.SUB_TEXT))
                assertEquals(emptyList<String>(), oldNotification.actions)
                assertEquals("mixed", oldNotification.slots.getValue(NotifTextField.SUB_TEXT))

                val screenPage = ClusterDetailHtml.render("test", policy.k, day.toString(), ops.cluster(screenFingerprint))
                for (chip in listOf("shape: \$#.##", "clickable", "click action", "hidden")) assertTrue(screenPage.contains(">$chip</span>"), chip)
                val notificationPage = ClusterDetailHtml.render("test", policy.k, day.toString(), notification)
                assertTrue(notificationPage.contains("action 1: ")); assertTrue(notificationPage.contains(">shape: \$#.##</span>"))
                val hashes = listOf("0123456789abcdef", actionHash).flatMap { listOf(it, testKeying.token(it)) }
                for (page in listOf(screenPage, notificationPage)) for (hash in hashes) assertFalse(page.contains(hash))
            }
        } }
    }

    @Test fun `action labels count toward k and the vocabulary, expire with retention and leave with withdrawal`() = runBlocking {
        Database.connect(config()).use { db ->
            val accepted = SkeletonValidator.validate(
                notificationV2Fixture(day, actions = listOf(TextSlot(actionHash, "words:2"))), policy, day,
            ) as ItemVerdict.Accepted
            val ids = List(policy.k) { install() }
            for (id in ids) observe(db, id, listOf(accepted))
            val ops = OpsStore(db, clock, policy)
            val queued = ops.vocabularyQueue().map { it.jsonObject }.single { it.getValue("tokenHash").jsonPrimitive.content == testKeying.token(actionHash) }
            assertEquals(policy.k, queued.getValue("distinctInstalls").jsonPrimitive.int)
            assertEquals("words:2", queued.getValue("kind").jsonPrimitive.content)

            // Withdrawal removes the install's action-label sightings with the rest; the item drops below k.
            InstallStore(db, clock).withdraw(ids.first(), key)
            assertNull(ops.vocabularyQueue().map { it.jsonObject }.firstOrNull { it.getValue("tokenHash").jsonPrimitive.content == testKeying.token(actionHash) })

            // When the action label's sightings age out, the stored sample's action slot expires like any slot.
            sql { it.update("UPDATE token_sightings_v5 SET last_day = ?", day.minusDays(30)) }
            val result = LifecycleStore(db, clock, policy).runOnce()
            assertEquals(1, result.getValue("samples_notification").rewritten)
            val sample = requireNotNull(sql { it.select("SELECT skeleton::text FROM cluster_samples") { r -> r.getString(1) } })
            val body = Json.parseToJsonElement(sample).jsonObject
            assertEquals(JsonArray(listOf(JsonObject(mapOf("kind" to JsonPrimitive("expired"))))), body.getValue("actions"))
            assertFalse(sample.contains("\"h\""))
            // The shape slot carried no hash and is untouched.
            assertEquals(JsonPrimitive("\$#.##"), body.getValue("slots").jsonObject.getValue("subText").jsonObject.getValue("shape"))
            val rendered = requireNotNull(ops.cluster(stored(accepted).item.fingerprint)?.samples?.single()?.notification)
            assertEquals(listOf("expired"), rendered.actions)
        }
    }
}
