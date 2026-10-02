package cloud.trotter.census.server.db

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class InstallTest {
    @Test
    fun `string representation redacts the credential hash and full install ID`() {
        val id = UUID.fromString("12345678-1234-4234-8234-123456789abc")
        val day = LocalDate.of(2026, 10, 2)
        val hash = "PRIVATE_KEY_HASH_SENTINEL"
        val rendered = Install(id, day, day, false, hash, null).toString()
        assertTrue(rendered.startsWith("Install(id=12345678…, keyHash=[redacted], "))
        assertTrue(rendered.contains("lastSeenDay=2026-10-02"))
        assertFalse(rendered.contains(hash))
        assertFalse(rendered.contains(id.toString()))
    }
}
