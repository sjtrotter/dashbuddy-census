package cloud.trotter.census.server

import cloud.trotter.census.server.auth.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals

internal data class ConformanceCorpus(
    val builtRecords: List<JsonObject>,
    val builtBySchema: Map<String, Int>,
    val refusedByReason: Map<String, Int>,
)

internal fun readConformanceCorpus(bytes: ByteArray, manifest: JsonObject): ConformanceCorpus {
    val records = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }.toList()
    assertEquals(manifest.getValue("totalRecords").jsonPrimitive.int, records.size)
    assertEquals(manifest.getValue("sha256").jsonPrimitive.content, sha256Hex(bytes))
    val (refused, built) = records.partition { "refused" in it }
    require(built.all { "skeleton" in it }) { "Conformance records must contain refused or skeleton" }
    val builtBySchema = built.groupingBy { it.getValue("skeleton").jsonObject.getValue("schemaId").jsonPrimitive.content }.eachCount()
    val refusedByReason = refused.groupingBy { it.getValue("refused").jsonPrimitive.content }.eachCount()
    assertEquals(manifest.getValue("builtBySchema").jsonObject.mapValues { it.value.jsonPrimitive.int }, builtBySchema)
    assertEquals(manifest.getValue("refusedByReason").jsonObject.mapValues { it.value.jsonPrimitive.int }, refusedByReason)
    return ConformanceCorpus(built, builtBySchema, refusedByReason)
}
