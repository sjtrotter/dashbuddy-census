package cloud.trotter.census.server.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class CredentialsTest {
    private val id = "12345678-1234-4123-8123-123456789abc"
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `only canonical lowercase UUID v4 and variant are accepted`() {
        assertNotNull(InstallId.parse(id))
        listOf(id.uppercase(), id.replace("-4123-", "-1123-"), id.replace("-8123-", "-7123-"), "1-1-4-8-1", "$id ")
            .forEach { assertNull(InstallId.parse(it)) }
    }

    @Test
    fun `bearer failures collapse to null and secrets have canonical base64url entropy bounds`() {
        val credential = requireNotNull(parseBearer("Bearer $id.$secret"))
        assertEquals(id, credential.installId.value)
        assertFalse(credential.toString().contains(secret))
        assertNotNull(parseBearer("bearer $id.$secret"))
        listOf(null, "", "Basic $id.$secret", "Bearer $id", "Bearer $id.$secret=", "Bearer $id.$secret.extra", "Bearer $id. $secret")
            .forEach { assertNull(parseBearer(it)) }
        assertFalse(isValidSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31))))
        assertTrue(isValidSecret("A".repeat(128)))
        assertFalse(isValidSecret("A".repeat(129)))
        assertFalse(isValidSecret("A".repeat(42) + "B")) // nonzero padding bits
    }

    @Test
    fun `stored hash is lowercase SHA256 of UTF8 string not decoded bytes`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashSecret("abc"))
        assertEquals(64, hashSecret(secret).length)
    }
}
