package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.asV1
import cloud.trotter.census.server.notificationPolicy
import cloud.trotter.census.server.notificationV2Fixture
import cloud.trotter.census.server.screenV2Fixture
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** #78: v2 skeletons are admitted beside v1; shapes come only from the closed list; v1 stays strict. */
class SkeletonV2ValidatorTest {
    private val day = LocalDate.of(2026, 10, 2)
    private val policy = notificationPolicy
    private val screen = screenV2Fixture(day)
    private val notification = notificationV2Fixture(day)

    private fun verdict(item: JsonObject, policy: Policy = this.policy) = SkeletonValidator.validate(item, policy, day)
    private fun rejected(reason: String, item: JsonObject, policy: Policy = this.policy) =
        assertEquals(ItemVerdict.Rejected(reason), verdict(item, policy))

    /** The screen fixture's first child holds the shape slot; [change] rewrites that slot. */
    private fun shapeSlot(change: (JsonObject) -> JsonElement): JsonObject {
        val root = screen.getValue("root").jsonObject
        val children = root.getValue("children").jsonArray
        val pay = children[0].jsonObject
        val text = pay.getValue("text").jsonObject
        val newPay = JsonObject(pay + ("text" to JsonObject(text + ("text" to change(text.getValue("text").jsonObject)))))
        return JsonObject(screen + ("root" to JsonObject(root + ("children" to JsonArray(listOf(newPay) + children.drop(1))))))
    }

    private fun rootKey(key: String, value: JsonElement): JsonObject =
        JsonObject(screen + ("root" to JsonObject(screen.getValue("root").jsonObject + (key to value))))

    @Test fun `v2 screens and notifications are accepted with their flags shapes and action hashes`() {
        val acceptedScreen = verdict(screen) as ItemVerdict.Accepted
        val node = (acceptedScreen.item as UiSkeletonDto).root
        assertTrue(node.isClickable)
        assertTrue(node.children[1].hasClickAction)
        assertEquals(false, node.children[2].isVisibleToUser)
        assertEquals(ShapeTemplate.MONEY.wire, node.children[0].text.getValue("text").shape)
        // A shape slot carries no hash, so it is no token; the hashed button label is the only one.
        assertEquals(listOf("0123456789abcdef"), acceptedScreen.tokens.map { it.hash })

        val acceptedNotification = verdict(notification) as ItemVerdict.Accepted
        val item = acceptedNotification.item as NotificationSkeletonDto
        assertEquals(listOf(TextSlot("fedcba9876543210", "words:2"), TextSlot.WITHHELD), item.actions)
        // Four hashed text slots and the hashed action label count as tokens, like every other slot.
        assertEquals(setOf("0123456789abcdef", "fedcba9876543210"), acceptedNotification.tokens.map { it.hash }.toSet())
        assertEquals(5, acceptedNotification.tokens.size)
        assertEquals("words:2", acceptedNotification.tokens.single { it.hash == "fedcba9876543210" }.kind)
    }

    @Test fun `every template on the closed list is accepted and nothing else is`() {
        for (template in ShapeTemplate.entries) {
            assertTrue(verdict(screenV2Fixture(day, shape = template)) is ItemVerdict.Accepted, template.wire)
        }
        for (shape in listOf("\$##.##", "$7.50", "Ready now", "", " \$#.##", "#:## PM", "mixed")) {
            rejected("bad_shape", shapeSlot { JsonObject(it + ("shape" to JsonPrimitive(shape))) })
        }
        rejected("bad_shape", shapeSlot { JsonObject(it - "shape") })
        rejected("bad_shape", shapeSlot { JsonObject(it + ("shape" to JsonNull)) })
        rejected("bad_shape", shapeSlot { JsonObject(mapOf("kind" to JsonPrimitive("mixed"), "shape" to JsonPrimitive(ShapeTemplate.MONEY.wire))) })
        rejected("hash_on_withheld_kind", shapeSlot { JsonObject(it + ("h" to JsonPrimitive("0123456789abcdef"))) })
        rejected("bad_item", shapeSlot { JsonObject(it + ("shape" to JsonPrimitive(7))) })
        rejected("unknown_field", shapeSlot { JsonObject(it + ("value" to JsonPrimitive(1))) })
        // A shape on a notification text slot or an action is held to the same list.
        val slots = notification.getValue("slots").jsonObject
        rejected("bad_shape", JsonObject(notification + ("slots" to JsonObject(slots + ("subText" to JsonObject(mapOf(
            "kind" to JsonPrimitive("shape"), "shape" to JsonPrimitive("Jordan"),
        )))))))
        rejected("bad_shape", JsonObject(notification + ("actions" to JsonArray(listOf(JsonObject(mapOf(
            "kind" to JsonPrimitive("shape"), "shape" to JsonPrimitive("Accept"),
        )))))))
    }

    @Test fun `v2 flags and actions are typed and bounded`() {
        rejected("plaintext_field", rootKey("hasClickAction", JsonPrimitive("true")))
        rejected("bad_item", rootKey("isVisibleToUser", JsonPrimitive(1)))
        // An action label in clear text is plaintext, never a slot.
        rejected("plaintext_field", JsonObject(notification + ("actions" to JsonArray(listOf(JsonPrimitive("Accept"))))))
        rejected("bad_item", JsonObject(notification + ("actions" to JsonObject(emptyMap()))))
        rejected("unknown_field", JsonObject(notification + ("actions" to JsonArray(listOf(JsonObject(mapOf(
            "kind" to JsonPrimitive("withheld"), "label" to JsonPrimitive(1),
        )))))))
        val limit = NotificationSkeletonDto.MAX_ACTIONS
        val withheld = JsonObject(mapOf("kind" to JsonPrimitive("withheld")))
        assertTrue(verdict(JsonObject(notification + ("actions" to JsonArray(List(limit) { withheld })))) is ItemVerdict.Accepted)
        rejected("bad_item", JsonObject(notification + ("actions" to JsonArray(List(limit + 1) { withheld }))))
        // The node flags are screen keys; a notification carrying one is refused.
        rejected("unknown_field", JsonObject(notification + ("hasClickAction" to JsonPrimitive(true))))
    }

    @Test fun `a v1 item carrying any v2 key is refused, and so is the contract's own v1 decode of it`() {
        val v1Screen = asV1(screen)
        val v1Notification = asV1(notification)
        assertTrue(verdict(v1Screen) is ItemVerdict.Accepted)
        assertTrue(verdict(v1Notification) is ItemVerdict.Accepted)
        fun v1Root(key: String, value: JsonElement) =
            JsonObject(v1Screen + ("root" to JsonObject(v1Screen.getValue("root").jsonObject + (key to value))))
        val shapedTitle = JsonObject(v1Screen + ("windowTitle" to JsonObject(mapOf(
            "kind" to JsonPrimitive("shape"), "shape" to JsonPrimitive(ShapeTemplate.MONEY.wire),
        ))))
        val mixedWithShapeKey = JsonObject(v1Screen + ("windowTitle" to JsonObject(mapOf(
            "kind" to JsonPrimitive("mixed"), "shape" to JsonNull,
        ))))
        val notificationSlots = v1Notification.getValue("slots").jsonObject
        val carriers = listOf(
            // A v2 key with its default value is still a v2 key.
            v1Root("hasClickAction", JsonPrimitive(false)),
            v1Root("isVisibleToUser", JsonPrimitive(true)),
            v1Root("hasClickAction", JsonNull),
            shapedTitle,
            mixedWithShapeKey,
            JsonObject(v1Notification + ("actions" to JsonArray(emptyList()))),
            JsonObject(v1Notification + ("actions" to JsonArray(listOf(JsonObject(mapOf("kind" to JsonPrimitive("withheld"))))))),
            JsonObject(v1Notification + ("slots" to JsonObject(notificationSlots + ("subText" to JsonObject(mapOf(
                "kind" to JsonPrimitive("mixed"), "shape" to JsonNull,
            )))))),
        )
        for (carrier in carriers) {
            rejected("unknown_field", carrier)
            // Defence in depth: the contract's strict v1 decoder refuses the same item on its own.
            assertThrows(SerializationException::class.java) { CensusSkeletonSchema.deserialize(carrier.toString()) }
        }
        // The v1 grammar never had the shape kind: without the v2 key it is the bad kind it always was.
        rejected("bad_kind", JsonObject(v1Screen + ("windowTitle" to JsonObject(mapOf("kind" to JsonPrimitive("shape"))))))
    }

    @Test fun `v2 is refused while the policy lists only v1, and an unknown version always`() {
        val v1Only = Policy(acceptedSchemaIds = listOf("uinode.skeleton.v1", "notification.skeleton.v1"))
        rejected("unknown_schema", screen, v1Only)
        rejected("unknown_schema", notification, v1Only)
        assertTrue(verdict(asV1(screen), v1Only) is ItemVerdict.Accepted)
        // A policy that names a schema the server cannot validate still refuses it.
        val future = Policy(acceptedSchemaIds = listOf("uinode.skeleton.v3"))
        rejected("unknown_schema", JsonObject(screen + ("schemaId" to JsonPrimitive("uinode.skeleton.v3"))), future)
        // A v2 notification still needs the notification kind.
        rejected("kind_schema_mismatch", JsonObject(notification + ("kind" to JsonPrimitive("screen"))))
    }

    @Test fun `the v1 and v2 forms of one screen or notification share a fingerprint`() {
        for (v2 in listOf(screen, notification)) {
            val a = (verdict(asV1(v2)) as ItemVerdict.Accepted).item
            val b = (verdict(v2) as ItemVerdict.Accepted).item
            assertEquals(a.fingerprint, b.fingerprint)
            assertEquals(CensusFingerprint.of(a), CensusFingerprint.of(b))
        }
        // The v1 projection reads the shape as mixed and drops the actions and flags.
        val projected = asV1(notification)
        assertEquals(JsonPrimitive("mixed"), projected.getValue("slots").jsonObject.getValue("subText").jsonObject.getValue("kind"))
        assertTrue("actions" !in projected)
    }
}
