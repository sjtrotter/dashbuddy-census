package cloud.trotter.census.server.ingest

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StoredSampleExpiryTest {
    @Test fun `window title nested text maps and all five notification slots lose expired hashes`() {
        val slot = """{"kind":"words:1","h":"0123456789abcdef"}"""
        val withheld="""{"kind":"digits","h":null}"""
        assertEquals(Json.parseToJsonElement(withheld),Json.parseToJsonElement(StoredSampleExpiry.rewrite(withheld,emptySet())))
        val root = """{"text":{"text":$slot,"desc":$slot},"children":[{"text":{"pane":$slot},"children":[]}]}"""
        val screen = """{"fingerprint":"fixed","windowTitle":$slot,"root":$root}"""
        val notification = """{"fingerprint":"fixed","slots":{"title":$slot,"text":$slot,"bigText":$slot,"subText":$slot,"summaryText":$slot}}"""
        for (body in listOf(screen, notification)) {
            val rewritten = StoredSampleExpiry.rewrite(body, emptySet())
            assertFalse(rewritten.contains("0123456789abcdef"))
            assertFalse(rewritten.contains("\"h\""))
            assertEquals("fixed", Json.parseToJsonElement(rewritten).jsonObject["fingerprint"]!!.jsonPrimitive.content)
            assertEquals(rewritten, StoredSampleExpiry.rewrite(rewritten, emptySet()))
            assertEquals(Json.parseToJsonElement(body), Json.parseToJsonElement(StoredSampleExpiry.rewrite(body, setOf("0123456789abcdef"))))
        }
    }
}
