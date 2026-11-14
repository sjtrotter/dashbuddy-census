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
        for (text in listOf("Heads up", "Order details", "Got it!", "Take a photo", "Return to dash", "Deliver to",
            "Deliver to:", "Pickup for", "Let's go", "Don't show again", "Check-in", "Accept order", "Pick up by", "Test drive now",
            "Entrega y recogida", "DoorDash", "OK", "jane a smith", "riley smith", "RILEY smith", "HEADS up")) {
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
            "Bank Account", "DasherDirect balance", "a".repeat(41), "one two three four five six seven eight nine", "", " ", "\u200B",
            // Sol round 1, S1: punctuation never hides a shape.
            "brandon c,", "Brandon C!", "Brandon, C", "brandon,c", "(Jane S.)", "Jane S, here", "Mary-Jo K.", "O'Brien M", "Riley M:",
            "Deliver to: Sam", "Deliver to - Sam", "DELIVER TO,SAM", "deliver-to sam", "Pick-up for Riley", "Message from: Ana",
            "Main St!", "Main St.", "main st,", "Oak Ave, Springfield", "Main St Springfield", "Elm Dr.",
            "jane at example.com", "Jane (example.org)", "(555) 123-4567", "+1 555", "PIN: 4821", "Pin: KQZX", "Gate code: KQZX",
            "Gate-code KQZX", "Apt B", "Suite: Rear", "Unit, Back",
            // Sol round 2, S1: two capitalized words in a row are never compared, chrome or name.
            "Riley Smith", "Accept Order", "Jane A Smith", "RILEY SMITH", "Can I help", "PICK UP BY", "Pick Up By",
            // Sol round 3, S1: any capitalized word after the first refuses a phrase, so lower-case particles do not help.
            "riley Smith", "Maria de Souza", "Juan de la Cruz", "Ludwig van Beethoven", "Ana da Silva", "Jean-Luc de la Fontaine",
            "maria de Souza", "ana da silva Costa",
        )
        for (text in refused) assertNull(ChromeShape.anchorCandidate(text), text)
        assertNull(ChromeShape.anchorCandidate("x".repeat(ChromeShape.MAX_TEXT_LENGTH + 1)))
    }

    @Test fun `the case variants are the ones hasText accepts, without duplicates`() {
        // Several words: lower and sentence case only, never as sent, all caps or title case.
        assertEquals(listOf("heads up", "Heads up"), ChromeShape.caseVariants("Heads up"))
        assertEquals(listOf("jane a smith", "Jane a smith"), ChromeShape.caseVariants("jane a smith"))
        assertEquals(listOf("heads up", "Heads up"), ChromeShape.caseVariants("HEADS up"))
        // One word: as sent, lower, upper and capitalized.
        assertEquals(listOf("DoorDash", "doordash", "DOORDASH", "Doordash"), ChromeShape.caseVariants("DoorDash"))
        for (word in listOf("Heads up", "straße", "İstanbul", "¡hola amigo", "o'clock rush")) {
            val variants = ChromeShape.caseVariants(word)
            assertEquals(variants.distinct(), variants)
            assertTrue(variants.all { it.equals(word, ignoreCase = true) && it.length == word.length }, variants.toString())
        }
    }

    @Test fun `the compare keys the candidate with the pepper and finds the variant the slot holds`() {
        val keying = testKeying
        val stored = keying.token(requireNotNull(CensusHash.of("Heads up")))
        assertEquals(listOf("Heads up", null), anchorMatches(keying, stored, listOf("heads up", "Got it")))
        // A title-case slot value is never compared, so a lower-case candidate cannot reach it.
        assertEquals(listOf<String?>(null), anchorMatches(keying, keying.token(requireNotNull(CensusHash.of("Riley Smith"))), listOf("riley smith")))
        // A public (unkeyed) hash never matches: the candidate is hashed, keyed, then compared.
        assertEquals(listOf<String?>(null), anchorMatches(keying, requireNotNull(CensusHash.of("Heads up")), listOf("Heads up")))
        // Under another pepper the same word does not match either.
        val other = HashKeying.fromHex("f".repeat(HashKeying.PEPPER_HEX_LENGTH))
        assertEquals(listOf<String?>(null), anchorMatches(other, stored, listOf("Heads up")))
        // No constant value: nothing matches, whatever the candidate.
        assertEquals(listOf<String?>(null, null), anchorMatches(keying, null, listOf("Heads up", "Got it")))
    }
}
