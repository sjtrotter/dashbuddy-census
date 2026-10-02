package cloud.trotter.census.server.ops

import cloud.trotter.census.server.auth.hashSecret
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkeletonRenderTest {
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

    companion object {
        val sample = """{"windowTitle":{"h":"fedcba9876543210","kind":"words:2"},"root":{
            "class":"android.widget.FrameLayout","text":{"text":{"h":"0123456789abcdef","kind":"words:2"}},
            "children":[{"class":"android.widget.Button","id":"com.example:id/action","text":{"desc":{"kind":"digits"}}}]}}
        """.trimIndent()
    }
}
