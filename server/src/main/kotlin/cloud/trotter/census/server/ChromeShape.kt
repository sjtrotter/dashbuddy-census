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
     * [MAX_CANONICAL_LENGTH] characters; a phrase whose as-sent form has a capitalized word anywhere after its first
     * ([laterCapital]: `Accept Order`, `Riley Smith`, `Maria de Souza`, `Can I help`) is refused, because
     * capitalization cannot tell app wording from a person's name (Sol rounds 2 and 3, S1); and none of [judgedForms]
     * may carry a name, address, contact or code shape ([refusedShape]). The judged forms are wider than the compared
     * ones on purpose: `brandon c,` is refused for its upper-case form. Pure: nothing is hashed here.
     */
    fun anchorCandidate(text: String): String? {
        val canonical = wordsCanonical(text)?.takeIf { it.length <= MAX_CANONICAL_LENGTH } ?: return null
        if (laterCapital(canonical)) return null
        return canonical.takeIf { judgedForms(it).none(::refusedShape) }
    }

    /**
     * The case variants of [canonical] the anchor check compares (#82), in this order and without duplicates. A single
     * word: as sent, all lower case, all upper case, and capitalized (first letter upper, the rest lower). Several
     * words: all lower case and sentence case (the first letter upper, the rest lower) only, never as sent, so no
     * compared phrase has a capital after its first letter. Each maps one character to one character (`lowercaseChar`,
     * `uppercaseChar`) and is kept only if `equals(other, ignoreCase = true)` accepts it against [canonical], the
     * comparison the app's `hasText` makes. A slot holding a phrase with a later capital is not found here; see
     * OPERATOR.md.
     */
    fun caseVariants(canonical: String): List<String> {
        val lower = canonical.map { it.lowercaseChar() }.joinToString("")
        val sentence = capitalizeFirst(lower, everyWord = false)
        val forms = if (words(canonical).size <= 1) listOf(canonical, lower, canonical.map { it.uppercaseChar() }.joinToString(""), sentence)
            else listOf(lower, sentence)
        return forms.distinct().filter { it.equals(canonical, ignoreCase = true) }
    }

    /**
     * The forms [refusedShape] judges (#82): as sent, all lower case, all upper case, sentence case and title case,
     * whether or not they are compared, so a shape the casing of the candidate hides is still seen.
     */
    internal fun judgedForms(canonical: String): List<String> {
        val lower = canonical.map { it.lowercaseChar() }.joinToString("")
        return listOf(canonical, lower, canonical.map { it.uppercaseChar() }.joinToString(""), capitalizeFirst(lower, everyWord = false),
            capitalizeFirst(lower, everyWord = true)).distinct()
    }

    /** A word ([words]) after the first that starts with a capital letter (`Accept Order`, `Juan de la Cruz`, `Can I`). */
    internal fun laterCapital(value: String): Boolean = words(value).drop(1).any { it.first[0].isUpperCase() }

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
     * A name, address, contact or code shape in one case variant. Punctuation never hides one (Sol round 1, S1): every
     * predicate reads words, not the raw string, where a **word** is a run of letters that may carry an inner
     * apostrophe or hyphen (`Let's`, `O'Brien`, `Mary-Jo`) and every other character (space, `,` `:` `-` `!` `(`…)
     * separates words, and a **letter run** splits at every non-letter. Refused:
     * - an initial: a one-letter capital word after a word that starts with a capital, when it ends the value, is
     *   followed by a period, or is not one of [SHORT_WORDS] (`Brandon C`, `brandon c,` through its title case,
     *   `Brandon, C`, `Jane S. is here`; `Plan B` is refused too, the accepted cost);
     * - a lead-in from [LEAD_INS] followed by any further letter run, whatever separates them (`Deliver to Sam`,
     *   `Deliver to: Sam`, `Pick-up for Riley`, `Gate code: KQZX`); the lead-in alone (`Deliver to`) is chrome;
     * - a street suffix as the last letter run of two or more (`Main St!`, `Oak Avenue.`), or an abbreviated one between
     *   two runs (`Main St Springfield`);
     * - an e-mail or web address (an `@`, or a dot between a letter and a common top-level domain) and a square bracket
     *   (a redaction mask).
     * Digits never reach here ([wordsCanonical] refuses any), so phone numbers, PINs and codes with digits are refused
     * there, punctuation or not.
     */
    internal fun refusedShape(value: String): Boolean {
        if (value.any { it == '@' || it == '[' || it == ']' } || DOMAIN.containsMatchIn(value)) return true
        val runs = letterRuns(CaseFold.fold(value))
        if (LEAD_INS.any { lead -> runs.size > lead.size && runs.subList(0, lead.size) == lead }) return true
        if (runs.size >= 2 && runs.last() in STREET_SUFFIXES) return true
        if ((1 until runs.lastIndex).any { runs[it] in STREET_ABBREVIATIONS }) return true
        val words = words(value)
        return words.indices.any { index ->
            val (word, end) = words[index]
            index > 0 && word.length == 1 && word[0].isUpperCase() && words[index - 1].first[0].isUpperCase() &&
                (index == words.lastIndex || value.getOrNull(end) == '.' || word[0] !in SHORT_WORDS)
        }
    }

    /** The words of [value] with the index just past each ([refusedShape]). */
    private fun words(value: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            var from = start
            var to = end
            while (from < to && value[from] in JOINERS) from++
            while (to > from && value[to - 1] in JOINERS) to--
            if (to > from) out += value.substring(from, to) to to
            start = -1
        }
        for (i in value.indices) {
            if (value[i].isLetter() || value[i] in JOINERS) { if (start < 0) start = i } else close(i)
        }
        close(value.length)
        return out
    }

    private fun letterRuns(value: String): List<String> = value.split(NON_LETTERS).filter { it.isNotEmpty() }

    private val NON_LETTERS = Regex("[^\\pL]+")

    /** Characters that join letters into one word ([words]): apostrophes and the hyphen. */
    private const val JOINERS = "'\u2019-"

    /** One-letter words that are words, not initials, when another word follows: English, Spanish and French. */
    private val SHORT_WORDS = setOf('A', 'I', 'Y', 'O', 'E', 'U', '\u00C0')

    /** An e-mail or web address without its `@`: a letter, a dot and a common top-level domain. */
    private val DOMAIN = Regex("\\pL\\.(?:com|net|org|edu|gov|io|co|us|ca|mx|info)\\b", RegexOption.IGNORE_CASE)

    /**
     * Lead-ins whose tail is a customer, a place or a code on the delivery apps' screens, as case-folded letter runs.
     */
    private val LEAD_INS: List<List<String>> = listOf(
        "deliver to", "delivering to", "drop off for", "dropoff for", "pick up for", "pickup for", "order for", "orders for",
        "message from", "gate code", "door code", "access code", "entry code", "passcode", "pin", "buzzer", "buzz",
        "apt", "apartment", "suite", "unit",
    ).map { it.split(' ') }

    /** Street-type words that end an address line; case-folded. */
    private val STREET_SUFFIXES = setOf(
        "street", "st", "avenue", "ave", "road", "rd", "boulevard", "blvd", "drive", "dr", "lane", "ln", "court", "ct",
        "parkway", "pkwy", "highway", "hwy",
    )

    /** The abbreviated suffixes, which an address line carries between a street name and a city. */
    private val STREET_ABBREVIATIONS = setOf("st", "ave", "rd", "blvd", "dr", "ln", "ct", "pkwy", "hwy")
}
