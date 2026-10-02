package cloud.trotter.census.server.ingest

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JsonGuardTest {
    @Test
    fun `deep batch is refused before recursive parsing`() {
        val body = "{\"batchId\":\"deep\",\"items\":[" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "]}"
        assertNull(parseBounded(body.toByteArray()))
    }

    @Test
    fun `ninety levels parse and the nesting boundary is inclusive`() {
        for (depth in listOf(90, 96)) {
            assertNotNull(parseBounded(("[".repeat(depth) + "0" + "]".repeat(depth)).toByteArray()))
        }
        assertNull(parseBounded(("[".repeat(97) + "0" + "]".repeat(97)).toByteArray()))
    }

    @Test
    fun `brackets and escaped quotes inside strings do not count`() {
        val body = """{"text":"\"[[[[\\\"[[[["}"""
        assertEquals(Json.parseToJsonElement(body), parseBounded(body.toByteArray(), maxDepth = 1))
    }

    @Test
    fun `invalid UTF-8 and malformed JSON are refused`() {
        assertNull(parseBounded(byteArrayOf(0x22, 0xc3.toByte(), 0x28, 0x22)))
        assertNull(parseBounded("{\"a\":}".toByteArray()))
    }
}
