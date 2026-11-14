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

/**
 * #81 (Sol S1): whether any object in [text], at any depth, names one key twice. A JSON parser keeps one of the two
 * values, so a body checked after parsing could still carry the other; a request that must mean exactly one thing is
 * refused instead. Keys are compared after their escapes are decoded (`"words"` is `words`). [text] must already
 * be valid JSON ([parseBounded]).
 */
fun hasDuplicateKeys(text: String): Boolean {
    // Per open container: the keys of an object, or null for an array.
    val stack = ArrayDeque<MutableSet<String>?>()
    var expectKey = false
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '{' -> { stack.addLast(HashSet()); expectKey = true }
            '[' -> { stack.addLast(null); expectKey = false }
            '}', ']' -> { stack.removeLast(); expectKey = false }
            ',' -> expectKey = stack.lastOrNull() != null
            '"' -> {
                val start = index
                index++
                while (text[index] != '"') { if (text[index] == '\\') index++; index++ }
                if (expectKey) {
                    val key = Json.decodeFromString(kotlinx.serialization.serializer<String>(), text.substring(start, index + 1))
                    if (!stack.last()!!.add(key)) return true
                    expectKey = false
                }
            }
        }
        index++
    }
    return false
}
