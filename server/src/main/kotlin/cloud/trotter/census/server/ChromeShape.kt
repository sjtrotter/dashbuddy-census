package cloud.trotter.census.server

import cloud.trotter.census.contract.CaseFold
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.SensitiveMarkerScan

/**
 * The shape of app wording, judged on the server before a plaintext word is used (#81 contributor words, #82 anchor
 * candidates). Built from the contract's own predicates: the census canonical form, the `words:1..8` grammar and the
 * sensitive-marker scan. The phone's full chrome filter (ADR-0011 §2) uses the app's PII shapes and customer markers,
 * which live outside the Apache-2.0 contract, so the server cannot run it; [anchorCandidate] adds a short
 * server-authored list of name and address shapes instead, and errs toward refusing.
 */
object ChromeShape {
    /** The longest plaintext a request may carry, before canonicalization. */
    const val MAX_TEXT_LENGTH: Int = 128

    /**
     * The phone withholds a canonical value longer than this before anything is hashed (ADR-0011 §2 step 2), so no
     * longer value is ever a slot hash.
     */
    const val MAX_CANONICAL_LENGTH: Int = 40

    /**
     * The canonical form of [text] when it has the shape of app wording: 1 to [MAX_TEXT_LENGTH] characters, a canonical
     * form, `words:1..8` (so no digit anywhere: no phone number, code, PIN, house number or ZIP) and no sensitive
     * marker; else null. Pure.
     */
    fun wordsCanonical(text: String): String? {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return null
        val canonical = CensusHash.canonical(text)?.takeIf { it.isNotEmpty() } ?: return null
        val shape = KindClassifier.shapeKind(canonical)
        if (shape !is KindClassifier.ShapeKind.Words || !shape.hashable) return null
        if (SensitiveMarkerScan.findMarker(canonical) != null) return null
        return canonical
    }

    /**
     * #82: the canonical form of an anchor candidate, or null when it is refused. On top of [wordsCanonical]: at most
     * [MAX_CANONICAL_LENGTH] characters, and none of [caseVariants] may carry a name, address or contact shape
     * ([refusedShape]). Every variant is judged because the check compares every variant: a candidate typed in lower
     * case must not reach a name shape through its title-case form. Pure: nothing is hashed here.
     */
    fun anchorCandidate(text: String): String? {
        val canonical = wordsCanonical(text)?.takeIf { it.length <= MAX_CANONICAL_LENGTH } ?: return null
        return canonical.takeIf { caseVariants(it).none(::refusedShape) }
    }

    /**
     * The case variants of [canonical] the anchor check compares (#82), in this order and without duplicates: as sent,
     * all lower case, all upper case, sentence case (the first letter upper, the rest lower) and title case (each
     * word's first letter upper, the rest lower). Each maps one character to one character (`lowercaseChar`,
     * `uppercaseChar`) and is kept only if `equals(other, ignoreCase = true)` accepts it against [canonical], the
     * comparison the app's `hasText` makes, so a rule written with the candidate matches a slot that holds any variant.
     * Mixed case inside a word (`DoorDash`) is compared as sent only.
     */
    fun caseVariants(canonical: String): List<String> {
        val lower = canonical.map { it.lowercaseChar() }.joinToString("")
        val upper = canonical.map { it.uppercaseChar() }.joinToString("")
        val sentence = capitalizeFirst(lower, everyWord = false)
        val title = capitalizeFirst(lower, everyWord = true)
        return listOf(canonical, lower, upper, sentence, title).distinct().filter { it.equals(canonical, ignoreCase = true) }
    }

    private fun capitalizeFirst(lower: String, everyWord: Boolean): String {
        val out = StringBuilder(lower.length)
        var pending = true
        for (ch in lower) {
            if (ch == ' ') {
                pending = pending || everyWord
                out.append(ch)
            } else if (pending && ch.isLetter()) {
                out.append(ch.uppercaseChar())
                pending = false
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * A name, address or contact shape in one canonical variant (words separated by one ASCII space):
     * - an initial: a one-letter capital word, optionally with a period, after a word that starts with a capital, when
     *   it ends the value, carries the period, or is not `A` or `I` (`Brandon C`, `Jane S. is here`; `Plan B` is refused
     *   too, which is the accepted cost);
     * - a customer lead-in with anything after it (`Deliver to Sam`; `Deliver to` alone is chrome);
     * - a street suffix ending a value of two or more words (`Main St`, `Oak Avenue`);
     * - an `@` (an e-mail address) or a square bracket (a redaction mask).
     */
    internal fun refusedShape(value: String): Boolean {
        if (value.any { it == '@' || it == '[' || it == ']' }) return true
        val words = value.split(' ')
        val folded = CaseFold.fold(value)
        if (LEAD_INS.any { folded.startsWith("$it ") }) return true
        if (words.size >= 2 && CaseFold.fold(words.last().trimEnd('.', ',')) in STREET_SUFFIXES) return true
        return words.indices.any { index ->
            val core = words[index].removeSuffix(".")
            index > 0 && core.length == 1 && core[0].isUpperCase() && words[index - 1].firstOrNull()?.isUpperCase() == true &&
                (index == words.lastIndex || words[index].endsWith('.') || core[0] !in setOf('A', 'I'))
        }
    }

    /** Lead-ins whose tail is a customer or a place on the delivery apps' screens; case-folded. */
    private val LEAD_INS = listOf(
        "deliver to", "delivering to", "drop off for", "dropoff for", "pick up for", "pickup for", "order for", "orders for",
        "message from",
    )

    /** Street-type words that end an address line; case-folded. */
    private val STREET_SUFFIXES = setOf(
        "street", "st", "avenue", "ave", "road", "rd", "boulevard", "blvd", "drive", "dr", "lane", "ln", "court", "ct",
        "parkway", "pkwy", "highway", "hwy",
    )
}
