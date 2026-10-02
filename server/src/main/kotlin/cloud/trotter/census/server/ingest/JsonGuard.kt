package cloud.trotter.census.server.ingest

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Bound container nesting before handing untrusted input to the recursive JSON parser. */
fun parseBounded(bytes: ByteArray, maxDepth: Int = 96): JsonElement? {
    var depth = 0
    var inString = false
    var index = 0
    while (index < bytes.size) {
        val byte = bytes[index].toInt()
        if (inString) {
            when (byte) {
                0x5c -> index++
                0x22 -> inString = false
                else -> Unit
            }
        } else {
            when (byte) {
                0x22 -> inString = true
                0x7b, 0x5b -> {
                    depth++
                    if (depth > maxDepth) return null
                }
                0x7d, 0x5d -> depth--
                else -> Unit
            }
        }
        index++
    }
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return null
    }
    return try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }
}
