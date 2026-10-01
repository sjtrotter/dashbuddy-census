package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Source guards for disposable processes, explicit clocks, and the app's #909 regex rule (#1157 S1). */
class TwelveFactorGuardTest {
    @Test
    fun `production sources obey deployment rules`() {
        val root = Path.of(requireNotNull(System.getProperty("census.mainSource")))
        assertTrue(Files.isDirectory(root), "Missing server/src/main source root")
        val failures = mutableListOf<String>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { path ->
                failures += violations(path.fileName.toString(), Files.readString(path)).map { "$path: $it" }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `guards reject violations and permit explicit exceptions`() {
        assertFalse(violations("Example.kt", "File(\"state\").writeText(\"data\")").isEmpty())
        assertTrue(violations("Example.kt", "File(System.getProperty(\"java.io.tmpdir\"), \"x\").writeText(\"data\")").isEmpty())
        assertFalse(violations("Example.kt", "System.currentTimeMillis()").isEmpty())
        assertTrue(violations("SystemClock.kt", "System.currentTimeMillis()").isEmpty())
        listOf("Instant", "LocalDate", "LocalDateTime").forEach { type ->
            assertFalse(violations("Example.kt", "$type.now()").isEmpty())
            assertTrue(violations("SystemClock.kt", "$type.now()").isEmpty())
        }
        assertFalse(violations("Example.kt", "println(\"message\")").isEmpty())
        listOf("System.err", "System.out").forEach { stream ->
            assertFalse(violations("Example.kt", "$stream.print(\"message\")").isEmpty())
        }
        listOf("write", "writeString", "newOutputStream").forEach { method ->
            assertFalse(violations("Example.kt", "Files.$method(path, data)").isEmpty())
            assertTrue(
                violations("Example.kt", "Files.$method(Path.of(System.getProperty(\"java.io.tmpdir\"), \"x\"), data)")
                    .isEmpty(),
            )
        }
        listOf("Path.of", "Paths.get").forEach { constructor ->
            assertFalse(violations("Example.kt", "$constructor(\"state\").writeText(\"data\")").isEmpty())
            assertFalse(violations("Example.kt", "val path = $constructor(\"state\"); Files.write(path, data)").isEmpty())
            assertTrue(violations("Example.kt", "$constructor(\"state\").readText()").isEmpty())
            assertTrue(
                violations("Example.kt", "$constructor(System.getProperty(\"java.io.tmpdir\"), \"x\").writeText(\"data\")")
                    .isEmpty(),
            )
        }
        assertFalse(violations("Example.kt", "Regex(\"abc}\")").isEmpty())
        assertTrue(violations("Example.kt", "Regex(\"abc\\\\}\")").isEmpty())
        assertEquals(emptyList<String>(), violations("Example.kt", "Regex(\"a{1,3}\")"))
    }

    private fun violations(name: String, source: String): List<String> = buildList {
        if (Regex("\\bprintln\\s*\\(").containsMatchIn(source)) add("Use SLF4J, not println")
        if (Regex("\\bSystem\\s*\\.\\s*(err|out)\\b").containsMatchIn(source)) add("Use SLF4J, not standard streams")
        val wallClock = Regex("\\b(System\\s*\\.\\s*currentTimeMillis|(?:Instant|LocalDate|LocalDateTime)\\s*\\.\\s*now)\\s*\\(")
        if (!name.contains("Clock") && wallClock.containsMatchIn(source)) {
            add("Wall-clock reads belong in a Clock-named file")
        }
        // Conservative guard: every File/Path constructor in a file that writes must
        // explicitly root itself at tmpdir. NIO writes need a directly rooted path.
        val writes = Regex("\\b(write|writeString|writeText|writeBytes|appendText|appendBytes|writer|bufferedWriter|outputStream|newOutputStream|newBufferedWriter|createNewFile|mkdir|mkdirs)\\s*\\(")
        if (writes.containsMatchIn(source)) {
            val constructors = Regex("\\b(File|Path\\s*\\.\\s*of|Paths\\s*\\.\\s*get)\\s*\\(\\s*").findAll(source)
            constructors.forEach {
                val argument = source.substring(it.range.last + 1)
                if (!argument.startsWith("System.getProperty(\"java.io.tmpdir\")")) {
                    add("Filesystem writes must be rooted at java.io.tmpdir")
                }
            }
        }
        val nioWrites = Regex("\\bFiles\\s*\\.\\s*(write|writeString|newOutputStream|newBufferedWriter)\\s*\\(\\s*")
        val temporaryPath = Regex("(?:Path\\s*\\.\\s*of|Paths\\s*\\.\\s*get)\\s*\\(\\s*System\\.getProperty\\(\"java\\.io\\.tmpdir\"\\)")
        nioWrites.findAll(source).forEach {
            if (temporaryPath.find(source.substring(it.range.last + 1))?.range?.first != 0) {
                add("NIO writes must be directly rooted at java.io.tmpdir")
            }
        }
        val literals = Regex("Regex\\s*\\(\\s*(\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\")")
        literals.findAll(source).forEach { match ->
            val literal = match.groupValues[1]
            val pattern = if (literal.startsWith("\"\"\"")) {
                literal.removeSurrounding("\"\"\"")
            } else {
                literal.removeSurrounding("\"").replace("\\\\", "\\")
            }
            if (hasBareClosingBrace(pattern)) add("Escape literal closing braces in Regex (#909)")
        }
    }

    private fun hasBareClosingBrace(pattern: String): Boolean {
        var index = 0
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index++
                '{' -> {
                    val quantifier = Regex("\\{[0-9]+(?:,[0-9]*)?\\}").find(pattern, index)
                    if (quantifier?.range?.first == index) index = quantifier.range.last
                }
                '}' -> return true
            }
            index++
        }
        return false
    }
}
