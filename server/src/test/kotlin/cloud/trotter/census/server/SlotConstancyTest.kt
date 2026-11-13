package cloud.trotter.census.server

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.ingest.SlotValue
import cloud.trotter.census.server.ingest.SlotWalk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** #80: the pure classifier and the slot ids the phone and the server share. */
class SlotConstancyTest {
    private val policy = SlotConstancyPolicy()
    private fun classify(evidence: SlotEvidence, k: Int = 10) = SlotConstancy.classify(evidence, k, policy)

    @Test fun `below k is unknown whatever the evidence`() {
        assertEquals(SlotClass.UNKNOWN, classify(SlotEvidence(installs = 9, topHashInstalls = 9, hashes = 1)))
        assertEquals(SlotClass.UNKNOWN, classify(SlotEvidence(installs = 9, withheldInstalls = 9)))
        assertEquals(SlotClass.UNKNOWN, classify(SlotEvidence(installs = 0), k = 0))
    }

    @Test fun `constant needs nine in ten installs and k of them on one hash`() {
        assertEquals(SlotClass.CONSTANT, classify(SlotEvidence(installs = 10, topHashInstalls = 10, hashes = 1)))
        assertEquals(SlotClass.CONSTANT, classify(SlotEvidence(installs = 20, topHashInstalls = 18, hashes = 3)))
        assertEquals(SlotClass.VARYING, classify(SlotEvidence(installs = 20, topHashInstalls = 17, hashes = 4)))
        // Nine in ten but nine installs short of k: not enough support, and not a value either.
        assertEquals(SlotClass.UNKNOWN, classify(SlotEvidence(installs = 10, topHashInstalls = 9, hashes = 2), k = 10))
    }

    @Test fun `withheld wins over constant, shape needs most installs`() {
        assertEquals(SlotClass.WITHHELD, classify(SlotEvidence(installs = 10, topHashInstalls = 9, hashes = 1, withheldInstalls = 1)))
        assertEquals(SlotClass.CONSTANT, classify(SlotEvidence(installs = 11, topHashInstalls = 10, hashes = 1, withheldInstalls = 1)))
        assertEquals(SlotClass.SHAPE, classify(SlotEvidence(installs = 10, shapeInstalls = 6)))
        assertEquals(SlotClass.VARYING, classify(SlotEvidence(installs = 10, shapeInstalls = 5)))
        // Seen by k installs and never hashed (digits, mixed, long text): a value.
        assertEquals(SlotClass.VARYING, classify(SlotEvidence(installs = 10)))
    }

    @Test fun `regional needs several shared values covering half the installs`() {
        assertEquals(SlotClass.REGIONAL, classify(SlotEvidence(installs = 12, topHashInstalls = 3, hashes = 4, sharedHashes = 4, sharedHashInstalls = 12)))
        // One shared value beside noise is not regional.
        assertEquals(SlotClass.VARYING, classify(SlotEvidence(installs = 12, topHashInstalls = 6, hashes = 7, sharedHashes = 1, sharedHashInstalls = 6)))
        // Shared values on too few of the installs.
        assertEquals(SlotClass.VARYING, classify(SlotEvidence(installs = 20, topHashInstalls = 3, hashes = 18, sharedHashes = 2, sharedHashInstalls = 6)))
    }

    @Test fun `slot ids follow the tree as sent, sorted keys, the window title and notification fields`() {
        val leaf = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf(
            "text" to TextSlot("0123456789abcdef", "words:1"), "desc" to TextSlot.WITHHELD))
        val wrapper = UiSkeletonNodeDto(className = "android.view.View", children = listOf(
            UiSkeletonNodeDto(className = "android.widget.TextView"), leaf))
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to TextSlot(kind = "digits")),
            children = listOf(wrapper, UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("text" to TextSlot.ofShape(ShapeTemplate.MONEY)))))
        val screen = UiSkeletonDto(SkeletonSchema.SCHEMA_ID_V2, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            engineVersion = 1, day = "2026-10-06", windowTitle = TextSlot("fedcba9876543210", "words:2"), root = root)
        assertEquals(listOf(
            SlotValue("windowTitle", "fedcba9876543210"),
            SlotValue("root/text", SlotValues.UNHASHED),
            SlotValue("root/0/1/desc", SlotValues.WITHHELD),
            SlotValue("root/0/1/text", "0123456789abcdef"),
            SlotValue("root/1/text", SlotValues.SHAPE),
        ), SlotWalk.slots(screen, 100))
        assertEquals(3, SlotWalk.slots(screen, 3).size)

        val slots = NotifTextField.entries.associateWith { TextSlot("0123456789abcdef", "words:1") }
        val notification = NotificationSkeletonDto(SkeletonKind.NOTIFICATION, NotificationSkeletonSchema.SCHEMA_ID_V2, 1, 1,
            requireNotNull(CensusFingerprint.of("doordash", "CHANNEL", slots)), "doordash", engineVersion = 1, day = "2026-10-06",
            channelId = "CHANNEL", slots = slots, actions = listOf(TextSlot.WITHHELD, TextSlot("fedcba9876543210", "words:2")))
        assertEquals(listOf("title", "text", "bigText", "tickerText", "subText", "action/0", "action/1"),
            SlotWalk.slots(notification, 100).map { it.slot })
        assertEquals(SlotValues.WITHHELD, SlotWalk.slots(notification, 100)[5].value)
    }
}
