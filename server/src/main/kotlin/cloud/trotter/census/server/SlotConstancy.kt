package cloud.trotter.census.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a text slot is, judged across installs (#80, ADR-0011 §14). [CONSTANT] is candidate chrome, never proof;
 * [REGIONAL] and [VARYING] are values and are never confirmable; [SHAPE] is a value format, never vocabulary.
 */
@Serializable
enum class SlotClass(val wire: String) {
    @SerialName("constant") CONSTANT("constant"),
    @SerialName("regional") REGIONAL("regional"),
    @SerialName("varying") VARYING("varying"),
    @SerialName("withheld") WITHHELD("withheld"),
    @SerialName("shape") SHAPE("shape"),
    @SerialName("unknown") UNKNOWN("unknown"),
}

/**
 * One slot's evidence within ONE epoch (#80, #86): distinct installs per stored value, never summed across epochs. A
 * value is a keyed word hash, or one of the markers [SlotValues.WITHHELD], [SlotValues.SHAPE], [SlotValues.UNHASHED].
 *
 * @property installs installs that saw the slot at all (its presence rows).
 * @property topHashInstalls installs behind the most common hash.
 * @property hashes distinct hashes seen in the slot.
 * @property sharedHashes hashes seen on at least [SlotConstancyPolicy.regionalMinInstalls] installs each.
 * @property sharedHashInstalls a LOWER BOUND on the distinct installs behind [sharedHashes] ([of]).
 */
data class SlotEvidence(
    val installs: Int,
    val topHashInstalls: Int = 0,
    val hashes: Int = 0,
    val sharedHashes: Int = 0,
    val sharedHashInstalls: Int = 0,
    val withheldInstalls: Int = 0,
    val shapeInstalls: Int = 0,
) {
    companion object {
        /**
         * Builds the evidence from one epoch's counts: [present] installs saw the slot, and [counts] maps each stored
         * value (hash or marker; a presence entry is ignored) to the distinct installs that saw it.
         *
         * Sol S5: one install's rows for two values carry unrelated tags (#86), so the distinct installs behind several
         * shared hashes cannot be counted. They are bounded instead. Every install holds at least one value, so the
         * value rows beyond one per install number `E = Σ values − present`; an install behind `s` shared hashes adds
         * `s − 1` of them, so the distinct installs behind the shared hashes are at least `Σ shared − E`. The bound is
         * exact when no install saw two values, and it errs toward `varying`, never toward `regional`.
         */
        fun of(present: Int, counts: Map<String, Int>, policy: SlotConstancyPolicy): SlotEvidence {
            val values = counts - SlotValues.PRESENT
            val hashes = values.filterKeys { it !in SlotValues.MARKERS }
            val shared = hashes.values.filter { it >= policy.regionalMinInstalls }
            val excess = (values.values.sum() - present).coerceAtLeast(0)
            return SlotEvidence(
                installs = present,
                topHashInstalls = hashes.values.maxOrNull() ?: 0,
                hashes = hashes.size,
                sharedHashes = shared.size,
                sharedHashInstalls = (shared.sum() - excess).coerceAtLeast(0),
                withheldInstalls = values[SlotValues.WITHHELD] ?: 0,
                shapeInstalls = values[SlotValues.SHAPE] ?: 0,
            )
        }
    }
}

/** The value markers a slot sighting stores in place of a hash (#80); none of them is 16 lowercase hex. */
object SlotValues {
    /** The presence row: the install saw the slot, whatever it held. */
    const val PRESENT: String = "present"
    const val WITHHELD: String = "withheld"
    const val SHAPE: String = "shape"

    /** A value the filter never hashes: `digits`, `mixed` or `words:8+`. */
    const val UNHASHED: String = "unhashed"

    val MARKERS: Set<String> = setOf(PRESENT, WITHHELD, SHAPE, UNHASHED)
}

/** The one classifier (#80). Pure; thresholds come from [Policy.k] and [SlotConstancyPolicy]. */
object SlotConstancy {
    /**
     * In order: below k installs `unknown`; a backstop on [SlotConstancyPolicy.withheldPercent] `withheld` (before
     * constant, so a slot that sometimes holds protected text is never offered as chrome); most installs seeing a
     * value format `shape`; one hash on [SlotConstancyPolicy.constantPercent] of the installs and on k of them
     * `constant` (the same share below k stays `unknown`, never `varying`); several shared hashes covering
     * [SlotConstancyPolicy.regionalPercent] `regional`; anything else `varying`.
     */
    fun classify(evidence: SlotEvidence, k: Int, policy: SlotConstancyPolicy): SlotClass {
        val n = evidence.installs
        fun atLeast(count: Int, percent: Int): Boolean = count.toLong() * 100 >= percent.toLong() * n
        return when {
            n < k || n == 0 -> SlotClass.UNKNOWN
            atLeast(evidence.withheldInstalls, policy.withheldPercent) -> SlotClass.WITHHELD
            atLeast(evidence.shapeInstalls, policy.shapePercent) -> SlotClass.SHAPE
            atLeast(evidence.topHashInstalls, policy.constantPercent) ->
                if (evidence.topHashInstalls >= k) SlotClass.CONSTANT else SlotClass.UNKNOWN
            evidence.sharedHashes >= policy.regionalMinValues && atLeast(evidence.sharedHashInstalls, policy.regionalPercent) ->
                SlotClass.REGIONAL
            else -> SlotClass.VARYING
        }
    }
}
