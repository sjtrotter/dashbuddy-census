package cloud.trotter.census.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** #86: the epoch grid, tag separation and key wrapping, without a database. */
class EpochPseudonymsTest {
    @Test fun `epochs are fixed 30-day UTC blocks from 1970-01-01`() {
        assertEquals(0, Epochs.of(LocalDate.of(1970, 1, 1)))
        assertEquals(0, Epochs.of(LocalDate.of(1970, 1, 30)))
        assertEquals(1, Epochs.of(LocalDate.of(1970, 1, 31)))
        assertEquals(-1, Epochs.of(LocalDate.of(1969, 12, 31)))
        val day = LocalDate.of(2026, 11, 13)
        val epoch = Epochs.of(day)
        assertEquals(epoch, Epochs.of(Epochs.firstDay(epoch)))
        assertEquals(epoch - 1, Epochs.of(Epochs.firstDay(epoch).minusDays(1)))
        assertEquals((epoch - 1)..(epoch + 1), Epochs.live(day))
    }

    @Test fun `a tag separates installs, purposes, objects and epochs`() {
        val (key, _) = EpochKey.generate(testKeying, 7)
        val (next, _) = EpochKey.generate(testKeying, 8)
        val install = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val other = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val tag = key.tag(TagPurpose.TOKEN, install, "1:1:0123456789abcdef")
        assertEquals(EpochKey.TAG_HEX_LENGTH, tag.length)
        assertEquals(tag, key.tag(TagPurpose.TOKEN, install, "1:1:0123456789abcdef"))
        assertEquals(5, setOf(
            tag,
            key.tag(TagPurpose.TOKEN, other, "1:1:0123456789abcdef"),
            key.tag(TagPurpose.CLUSTER, install, "1:1:0123456789abcdef"),
            key.tag(TagPurpose.TOKEN, install, "1:2:0123456789abcdef"),
            next.tag(TagPurpose.TOKEN, install, "1:1:0123456789abcdef"),
        ).size)
        assertFalse(tag.contains(install.toString().take(8)))
        assertFalse(key.toString().contains(tag))
    }

    @Test fun `a wrapped key opens only for its epoch under its pepper, and keys are random`() {
        val (key, wrapped) = EpochKey.generate(testKeying, 42)
        val install = UUID.randomUUID()
        assertEquals(key.tag(TagPurpose.HEALTH, install, ""), EpochKey.unwrap(testKeying, 42, wrapped).tag(TagPurpose.HEALTH, install, ""))
        assertThrows(EpochKeyUnwrapException::class.java) { EpochKey.unwrap(testKeying, 43, wrapped) }
        assertThrows(EpochKeyUnwrapException::class.java) { EpochKey.unwrap(HashKeying.fromHex("ab".repeat(32)), 42, wrapped) }
        assertThrows(EpochKeyUnwrapException::class.java) { EpochKey.unwrap(testKeying, 42, wrapped.copyOf(wrapped.size - 1)) }
        // Not derived from the pepper: a second key for the same epoch is unrelated, so nothing re-derives a deleted one.
        val (again, _) = EpochKey.generate(testKeying, 42)
        assertNotEquals(key.tag(TagPurpose.HEALTH, install, ""), again.tag(TagPurpose.HEALTH, install, ""))
    }
}
