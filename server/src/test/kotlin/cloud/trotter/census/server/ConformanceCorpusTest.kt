package cloud.trotter.census.server

import cloud.trotter.census.server.auth.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class ConformanceCorpusTest {
    @Test
    fun `reader counts built schemas and refusals while retaining only built records for replay`() {
        val bytes = """
            {"file":"synthetic/screen.json","skeleton":{"schemaId":"uinode.skeleton.v1","kind":"screen"},"fingerprint":"screen","hashes":[]}
            {"file":"synthetic/refused.json","refused":"SENSITIVE_NOTIFICATION"}
            {"file":"synthetic/notification.json","skeleton":{"schemaId":"notification.skeleton.v1","kind":"notification"},"fingerprint":"notification","hashes":[]}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val manifest = Json.parseToJsonElement("""
            {
                "totalRecords": 3,
                "sha256": "${sha256Hex(bytes)}",
                "builtBySchema": {"uinode.skeleton.v1": 1, "notification.skeleton.v1": 1},
                "refusedByReason": {"SENSITIVE_NOTIFICATION": 1}
            }
        """).jsonObject

        val corpus = readConformanceCorpus(bytes, manifest)

        assertEquals(mapOf("uinode.skeleton.v1" to 1, "notification.skeleton.v1" to 1), corpus.builtBySchema)
        assertEquals(mapOf("SENSITIVE_NOTIFICATION" to 1), corpus.refusedByReason)
        assertEquals(listOf("synthetic/screen.json", "synthetic/notification.json"),
            corpus.builtRecords.map { it.getValue("file").jsonPrimitive.content })
        for (key in listOf("builtBySchema", "refusedByReason")) {
            val counts = manifest.getValue(key).jsonObject
            val incorrectCounts = JsonObject(counts + (counts.keys.first() to JsonPrimitive(2)))
            assertThrows(AssertionFailedError::class.java) {
                readConformanceCorpus(bytes, JsonObject(manifest + (key to incorrectCounts)))
            }
        }
    }
}
