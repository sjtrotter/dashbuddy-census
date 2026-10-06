package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.time.Instant

class WithdrawalJournalTest {
    private val hash = "a".repeat(64)

    @Test
    fun `reader accepts optional header ISO instants and blank lines`() {
        val row = "$hash,2026-10-03T12:01:02.123456Z"
        val expected = listOf(hash to Instant.parse("2026-10-03T12:01:02.123456Z"))
        for (csv in listOf(row, "\ninstall_id_hash,withdrawn_at\r\n\r\n$row\n \n")) {
            assertEquals(expected, readWithdrawalJournal(StringReader(csv)))
        }
        assertEquals(emptyList<Pair<String, Instant>>(), readWithdrawalJournal(StringReader("\n")))
    }

    @Test
    fun `reader accepts actual Postgres COPY timestamps and CSV quoting`() {
        val csv = "\"install_id_hash\",\"withdrawn_at\"\n\"$hash\",\"2026-10-03 12:01:02.123456+00\"\n$hash,2026-10-03 14:01:02.123456+02\n$hash,2026-10-03 17:31:02.123456+05:30"
        assertEquals(List(3) { hash to Instant.parse("2026-10-03T12:01:02.123456Z") }, readWithdrawalJournal(StringReader(csv)))
    }

    @Test
    fun `reader rejects malformed CSV and timestamps`() {
        for (csv in listOf("$hash", "$hash,2026-10-03T12:00:00Z,extra", "$hash,not-a-date", "$hash,\"2026-10-03T12:00:00Z")) {
            assertThrows(RuntimeException::class.java) { readWithdrawalJournal(StringReader(csv)) }
        }
    }
}
