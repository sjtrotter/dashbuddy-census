package cloud.trotter.census.server

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.db.KeyedHashStorageMigration
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.SkeletonValidator
import cloud.trotter.census.server.ingest.atRest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** #85: the keyed-storage primitive and the ingest transform, without a database. */
class HashKeyingTest {
    private val hash = requireNotNull(CensusHash.of("Ready now"))
    private val other = HashKeying.fromHex("ab".repeat(32))

    @Test fun `keyed values are deterministic, census-width, and depend on the pepper`() {
        val keyed = testKeying.token(hash)
        assertEquals(keyed, testKeying.token(hash))
        assertTrue(CensusHash.isWellFormed(keyed))
        assertNotEquals(hash, keyed)
        assertNotEquals(keyed, other.token(hash))
        assertEquals(keyed, testKeying.candidate("  Ready\u00A0now "))
        val fingerprint = "c".repeat(64)
        assertTrue(CensusFingerprint.isWellFormed(testKeying.fingerprint(fingerprint)))
        assertNotEquals(testKeying.fingerprint(fingerprint), other.fingerprint(fingerprint))
        // Labels separate the kinds: a keyed token is not a prefix of the keyed fingerprint of related input.
        assertFalse(testKeying.fingerprint(hash.repeat(4)).startsWith(testKeying.token(hash)))
        assertNotEquals(testKeying.keyCheck, other.keyCheck)
        assertTrue(testKeying.same(keyed, testKeying.token(hash)))
        assertFalse(testKeying.same(keyed, hash))
        assertThrows(IllegalArgumentException::class.java) { testKeying.token("ABCDEF0123456789") }
        assertThrows(IllegalArgumentException::class.java) { testKeying.fingerprint("c".repeat(63)) }
    }

    @Test fun `the pepper is exactly 64 hex digits and never echoed`() {
        for (invalid in listOf("", "CHANGE-ME", "a".repeat(63), "a".repeat(65), "g".repeat(64), "PRIVATE_PEPPER_SENTINEL")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { HashKeying.fromHex(invalid) }
            assertEquals("Invalid variable: CENSUS_HASH_PEPPER", failure.message)
        }
        assertEquals("HashKeying([redacted])", testKeying.toString())
        assertFalse(testKeying.toString().contains(TEST_HASH_PEPPER))
        assertEquals(testKeying.keyCheck, HashKeying.fromHex(TEST_HASH_PEPPER.uppercase()).keyCheck)
    }

    @Test fun `at-rest items key every slot, every token and a notification fingerprint, keeping equality and size`() {
        val day = LocalDate.of(2026, 10, 2)
        val notification = notificationAccepted(day)
        val stored = testKeying.atRest(notification)
        val publicSlot = (notification.item as NotificationSkeletonDto).slots.values.first().h!!
        assertEquals(testKeying.fingerprint(notification.item.fingerprint), stored.item.fingerprint)
        assertTrue((stored.item as NotificationSkeletonDto).slots.values.all { it.h == testKeying.token(publicSlot) })
        assertEquals(notification.tokens.map { testKeying.token(it.hash) }, stored.tokens.map { it.hash })
        assertEquals(notification.bytes, stored.bytes)
        assertFalse(stored.canonicalJson.contains(publicSlot)); assertFalse(stored.canonicalJson.contains(notification.item.fingerprint))
        assertEquals(stored, testKeying.atRest(notificationAccepted(day)))
        assertNotEquals(stored.item.fingerprint, testKeying.atRest(notificationAccepted(day, channel = "OTHER")).item.fingerprint)

        val slot = TextSlot(hash, "words:2")
        val leaf = UiSkeletonNodeDto(className = "android.widget.TextView", text = mapOf("desc" to slot))
        val root = UiSkeletonNodeDto(className = "android.widget.Button", text = mapOf("text" to slot), children = listOf(leaf))
        val screen = UiSkeletonDto(SkeletonSchema.SCHEMA_ID, 1, 1, requireNotNull(CensusFingerprint.of(root)), "doordash",
            platformAppVersion = "8.0", engineVersion = 1, day = day.toString(), windowTitle = slot, root = root)
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(CensusSkeletonSchema.measure(screen).json)
        val accepted = SkeletonValidator.validate(raw, notificationPolicy, day) as ItemVerdict.Accepted
        val keyedScreen = testKeying.atRest(accepted)
        assertEquals(screen.fingerprint, keyedScreen.item.fingerprint)
        assertFalse(keyedScreen.canonicalJson.contains(hash))
        assertEquals(3, Regex(testKeying.token(hash)).findAll(keyedScreen.canonicalJson).count())
        assertEquals(accepted.bytes, keyedScreen.bytes)
    }

    @Test fun `the migration's sample rewrite agrees with ingest`() {
        val day = LocalDate.of(2026, 10, 2)
        val notification = notificationAccepted(day)
        val stored = testKeying.atRest(notification)
        val migrated = KeyedHashStorageMigration.keySample(notification.canonicalJson, testKeying, stored.item.fingerprint)
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(stored.canonicalJson), kotlinx.serialization.json.Json.parseToJsonElement(migrated))
        val expired = """{"root":{"text":{"text":{"kind":"expired"}}}}"""
        assertEquals(expired, KeyedHashStorageMigration.keySample(expired, testKeying, null))
        assertThrows(IllegalArgumentException::class.java) {
            KeyedHashStorageMigration.keySample("""{"windowTitle":{"h":"not-a-hash","kind":"words:1"}}""", testKeying, null)
        }
    }
}
