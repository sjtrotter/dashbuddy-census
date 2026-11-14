package cloud.trotter.census.server

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.server.db.anchorMatches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #82: the anchor check's candidate filter, its case variants, and the keyed compare. */
class ChromeShapeTest {
    @Test fun `app wording passes the filter in its canonical form`() {
        for (text in listOf("Heads up", "Order details", "Got it!", "Take a photo", "Can I help", "Return to dash", "Deliver to")) {
            assertEquals(text, ChromeShape.anchorCandidate(text), text)
        }
        assertEquals("Heads up", ChromeShape.anchorCandidate("  Heads  up "))
    }

    @Test fun `name, address, contact, code and sensitive shapes are refused`() {
        val refused = listOf(
            // A first name and an initial, in any case: the title-case variant of a lower-case candidate is judged too.
            "Brandon C", "Jane S.", "brandon c", "JANE S", "Jane S. is here", "Plan B",
            // A lead-in with a tail, and street lines.
            "Deliver to Sam", "deliver to sam", "Pickup for Riley", "Main St", "oak avenue", "Elm Street.",
            // Digits anywhere: phone numbers, codes, PINs, house numbers, prices.
            "555-123-4567", "1234", "Gate code 4821", "123 Main", "Apt 4B", "\$7.50",
            // Contact and mask shapes.
            "jane@example.com", "[redacted]",
            // A dasher-sensitive marker, an over-long value, body text, nothing.
            "Bank Account", "DasherDirect balance", "a".repeat(41), "one two three four five six seven eight nine", "", " ", "​",
        )
        for (text in refused) assertNull(ChromeShape.anchorCandidate(text), text)
        assertNull(ChromeShape.anchorCandidate("x".repeat(ChromeShape.MAX_TEXT_LENGTH + 1)))
    }

    @Test fun `the case variants are the ones hasText accepts, without duplicates`() {
        assertEquals(listOf("Heads up", "heads up", "HEADS UP", "Heads Up"), ChromeShape.caseVariants("Heads up"))
        assertEquals(listOf("DoorDash", "doordash", "DOORDASH", "Doordash"), ChromeShape.caseVariants("DoorDash"))
        for (word in listOf("Heads up", "straße", "İstanbul", "¡hola amigo", "o'clock rush")) {
            val variants = ChromeShape.caseVariants(word)
            assertEquals(variants.distinct(), variants)
            assertTrue(variants.all { it.equals(word, ignoreCase = true) && it.length == word.length }, variants.toString())
        }
    }

    @Test fun `the compare keys the candidate with the pepper and finds the variant the slot holds`() {
        val keying = testKeying
        val stored = keying.token(requireNotNull(CensusHash.of("Heads Up")))
        assertEquals(listOf("Heads Up", null), anchorMatches(keying, stored, listOf("heads up", "Got it")))
        // A public (unkeyed) hash never matches: the candidate is hashed, keyed, then compared.
        assertEquals(listOf<String?>(null), anchorMatches(keying, requireNotNull(CensusHash.of("Heads Up")), listOf("Heads Up")))
        // Under another pepper the same word does not match either.
        val other = HashKeying.fromHex("f".repeat(HashKeying.PEPPER_HEX_LENGTH))
        assertEquals(listOf<String?>(null), anchorMatches(other, stored, listOf("Heads Up")))
        // No constant value: nothing matches, whatever the candidate.
        assertEquals(listOf<String?>(null, null), anchorMatches(keying, null, listOf("Heads Up", "Got it")))
    }
}
