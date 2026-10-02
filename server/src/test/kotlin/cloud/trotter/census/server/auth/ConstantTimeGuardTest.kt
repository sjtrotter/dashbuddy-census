package cloud.trotter.census.server.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ConstantTimeGuardTest {
    @Test
    fun `credential comparisons stay constant time`() {
        val root = Path.of(requireNotNull(System.getProperty("census.mainSource")), "kotlin/cloud/trotter/census/server")
        val sources = listOf("auth/Credentials.kt", "auth/InstallAuth.kt", "db/InstallStore.kt").associateWith {
            Files.readString(root.resolve(it))
        }
        val failures = sources.flatMap { (file, source) ->
            source.lines().mapIndexedNotNull { index, line ->
                if (unsafeComparison(file, line)) "$file:${index + 1}: non-constant-time credential comparison" else null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        val verify = sources.getValue("auth/Credentials.kt").substringAfter("object RequestSigner")
            .substringAfter("fun verify(").substringBefore("fun timestampInWindow(")
        assertTrue(verify.contains("MessageDigest.isEqual("), "RequestSigner.verify must use MessageDigest.isEqual")
        val auth = sources.getValue("auth/InstallAuth.kt")
        assertTrue(auth.lines().any { it.contains("MessageDigest.isEqual(") && it.contains("row.keyHash") })
        val store = sources.getValue("db/InstallStore.kt")
        assertTrue(store.substringAfter("private fun sameHash(").contains("MessageDigest.isEqual("))
    }

    @Test
    fun `guard detects each unsafe spelling and narrowly scopes the exception`() {
        listOf("hash == other", "other != secret", "signature.equals(other)", "hash.contentEquals(other)").forEach {
            assertTrue(unsafeComparison("auth/InstallAuth.kt", it))
        }
        val marker = " // constant-time: not credential material"
        assertTrue(unsafeComparison("auth/Credentials.kt", "hash == secret$marker"))
        val encoding = "return bytes.size >= 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == secret$marker"
        assertFalse(unsafeComparison("auth/Credentials.kt", encoding))
        assertTrue(unsafeComparison("db/InstallStore.kt", encoding))
        assertFalse(unsafeComparison("auth/InstallAuth.kt", "MessageDigest.isEqual(hash, row.keyHash)"))
    }

    private fun unsafeComparison(file: String, line: String): Boolean {
        val code = line.substringBefore("//").trim()
        if (file == "auth/Credentials.kt" && line.contains("// constant-time: not credential material") &&
            code == "return bytes.size >= 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == secret"
        ) return false
        if (!Regex("(?i)\\b\\w*(?:hash|signature|secret)\\w*\\b").containsMatchIn(code)) return false
        return Regex("==|!=|\\.\\s*equals\\s*\\(|\\bcontentEquals\\s*\\(").containsMatchIn(code)
    }
}
