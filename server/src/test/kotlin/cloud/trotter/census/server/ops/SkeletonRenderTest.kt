package cloud.trotter.census.server.ops

import cloud.trotter.census.server.auth.hashSecret
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkeletonRenderTest {
    @Test
    fun `notification projection gates channel never emits hashes and tolerates expiry`() {
        val raw = cloud.trotter.census.server.notificationFixture().toString()
        for (visible in listOf(false, true)) {
            for (sample in listOf(raw, raw.replace("words:1", "expired"))) {
                val rendered = SkeletonRender.renderNotification(sample, visible)
                org.junit.jupiter.api.Assertions.assertEquals(visible, rendered.channelId != null)
                org.junit.jupiter.api.Assertions.assertEquals(!visible, rendered.channelWithheld)
                org.junit.jupiter.api.Assertions.assertEquals(5, rendered.slots.size)
                val output = Json.encodeToString(rendered)
                assertFalse(output.contains("0123456789abcdef"))
                assertFalse(output.contains("\"h\""))
                org.junit.jupiter.api.Assertions.assertEquals(visible, output.contains("CHANNEL_SENTINEL"))
            }
        }
        for (sample in listOf(raw.replace("CHANNEL_SENTINEL", "bad channel"), raw.replace("words:1", "PRIVATE_TEXT"))) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { SkeletonRender.renderNotification(sample, true) }
        }
    }

    @Test
    fun `redacts every nested label and never renders text hashes`() {
        for (gate in listOf(false, true)) {
            val output = Json.encodeToString(SkeletonRender.render(sample, gate))
            for (label in listOf("android.widget.FrameLayout", "android.widget.Button", "com.example:id/action")) {
                if (gate) assertTrue(output.contains(label)) else {
                    assertFalse(output.contains(label))
                    assertTrue(output.contains("~" + hashSecret(label).take(8)))
                }
            }
            assertTrue(output.contains("words:2"))
            assertTrue(output.contains("digits"))
            assertFalse(output.contains("0123456789abcdef"))
            assertFalse(output.contains("fedcba9876543210"))
            assertFalse(output.contains("\"h\""))
        }
    }

    @Test
    fun `v2 shapes render as their template, flags and actions show, and a stored template off the list never does (#78)`() {
        val screen = SkeletonRender.render(cloud.trotter.census.server.screenV2Fixture().toString(), false)
        org.junit.jupiter.api.Assertions.assertTrue(screen.clickable)
        org.junit.jupiter.api.Assertions.assertEquals(listOf("shape:\$#.##"), screen.children[0].kinds)
        org.junit.jupiter.api.Assertions.assertTrue(screen.children[1].clickAction)
        org.junit.jupiter.api.Assertions.assertTrue(screen.children[2].hidden)
        val tampered = """{"root":{"text":{"text":{"kind":"shape","shape":"Jordan"}}}}"""
        org.junit.jupiter.api.Assertions.assertEquals(listOf("shape"), SkeletonRender.render(tampered, true).kinds)

        val notification = cloud.trotter.census.server.notificationV2Fixture().toString()
        val rendered = SkeletonRender.renderNotification(notification, false)
        org.junit.jupiter.api.Assertions.assertEquals(listOf("words:2", "withheld"), rendered.actions)
        org.junit.jupiter.api.Assertions.assertEquals("shape:\$#.##", rendered.slots.getValue(cloud.trotter.census.contract.NotifTextField.SUB_TEXT))
        val output = Json.encodeToString(rendered)
        assertFalse(output.contains("fedcba9876543210"))
        assertFalse(output.contains("\"h\""))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            SkeletonRender.renderNotification(notification.replace("words:2", "PRIVATE_TEXT"), true)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            SkeletonRender.renderNotification(notification.replace("\"words:2\"", "\"shape:PRIVATE_TEXT\""), true)
        }
    }

    companion object {
        val sample = """{"windowTitle":{"h":"fedcba9876543210","kind":"words:2"},"root":{
            "class":"android.widget.FrameLayout","text":{"text":{"h":"0123456789abcdef","kind":"words:2"}},
            "children":[{"class":"android.widget.Button","id":"com.example:id/action","text":{"desc":{"kind":"digits"}}}]}}
        """.trimIndent()
    }
}
