package cloud.trotter.census.server

import cloud.trotter.census.server.db.FilterFloorJournal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.time.LocalDate

class FilterFloorJournalTest {
    @TempDir lateinit var directory: Path
    @Test fun `strict policy-only parser and atomic replacement`() {
        val floor = FilterFloorJournal(4, LocalDate.of(2026, 10, 6))
        val target = directory.resolve("floor.csv")
        floor.export(target)
        assertEquals(floor, Files.newBufferedReader(target).use(FilterFloorJournal::read))
        floor.copy(minimumFilterRev = 5).export(target)
        assertEquals(5, Files.newBufferedReader(target).use(FilterFloorJournal::read).minimumFilterRev)
        floor.export(target)
        assertEquals(5,Files.newBufferedReader(target).use(FilterFloorJournal::read).minimumFilterRev)
        for (bad in listOf("", "min_filter_rev,changed_day\n0,2026-10-06\n", floor.encode() + floor.encode(),
            floor.encode().replace("4,", "2147483648,"), floor.encode().replace("10-06", "02-30"), "PRIVATE_FAILURE".repeat(1000))) {
            val failure = assertThrows(IllegalArgumentException::class.java) { FilterFloorJournal.read(bad.reader()) }
            assertEquals("Invalid filter floor journal", failure.message)
        }
    }
}
