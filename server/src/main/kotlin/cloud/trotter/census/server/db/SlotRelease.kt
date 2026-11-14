package cloud.trotter.census.server.db

import cloud.trotter.census.server.EpochKey
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.SlotValues
import cloud.trotter.census.server.TagPurpose
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * The slot classes a contributor's phone may read (#81, ADR-0011 §14 and §15), built so that no single install can
 * move an answer it can read (the oracle #80's review found, Sol S1 and N1 to N4).
 *
 * - **The day's release.** Once per UTC day the lifecycle sweep copies the slot rows as they stood at the end of the
 *   previous day, counted on this day ([countedSlotRows], live epochs only), into `slot_release`, in ONE statement
 *   inside a transaction that holds the table's EXCLUSIVE lock, and marks the day built in the same transaction. A row
 *   first received today is left out and a row written again today counts with its earlier last day (V10, Sol S6), so
 *   an upload reaches another install's answer the next day at the earliest, whenever the build runs. In that state an
 *   install that saw a slot holds a value row for it (a presence row's last day is the newest of its values', and
 *   trust, quarantine and the variant are shared), so the regional lower bound of
 *   [cloud.trotter.census.server.SlotEvidence.of] holds on released data (Sol N3).
 * - **Served on its day only.** A read accepts the release only when it is today's ([releaseDay]); yesterday's is
 *   refused from midnight even if today's build failed, and the sweep deletes it (Sol N2). A release counts only rows
 *   inside retention on its day, so it never outlives its evidence.
 * - **The reader's own rows never decide its answer.** Each read recomputes the reader's epoch tags for every row
 *   it is answered from and leaves the matching rows out ([leaveOneOut]) before classifying. The marker check, the
 *   release and the reader's own rows are read in one REPEATABLE READ snapshot, and erasure deletes an install's
 *   released rows through the same tags, so a concurrent deletion can never leave its rows counted but unsubtracted
 *   (Sol N1). A row in an epoch without a live key cannot be attributed, so its whole epoch group is left out:
 *   failing closed means less evidence, never the reader's own.
 */
internal object SlotRelease {
    /** Whether today's release has been built. */
    fun Connection.releaseDay(today: LocalDate): Boolean =
        select("SELECT 1 FROM slot_release_days WHERE release_day = ?", today) { true } ?: false

    /**
     * Builds today's release when it does not exist, in one transaction (the caller's). Returns the rows copied, or
     * null when today's release already existed.
     */
    fun Connection.buildRelease(policy: Policy, today: LocalDate): Int? {
        createStatement().use { it.execute("LOCK TABLE slot_release IN EXCLUSIVE MODE") }
        if (releaseDay(today)) return null
        update("DELETE FROM slot_release_days WHERE release_day <> ?", today)
        update("INSERT INTO slot_release_days (release_day) VALUES (?)", today)
        val live = Epochs.live(today)
        // Sol S6: the rows as they stood at the end of yesterday (V10): none first received today, and a row written
        // again today counted with the last day it had before.
        return update(
            """INSERT INTO slot_release (release_day, fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot,
                    value, epoch, install_tag)
                SELECT ?, fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value, epoch, install_tag
                FROM slot_sightings WHERE first_received < ? AND ${policy.countedSlotRows(BEFORE_TODAY_LAST_DAY)}
                AND epoch BETWEEN ? AND ?""",
            today, today, today, today, today, live.first, live.last,
        )
    }

    /** A slot row's last day as it stood before the receipt day bound to the first parameter (V10, Sol S6). */
    private const val BEFORE_TODAY_LAST_DAY = "(CASE WHEN received_day >= ? THEN prev_last_day ELSE last_day END)"

    /** One released or live row a contributor read is answered from. */
    class Row(val slot: String, val epoch: Int, val value: String, val tag: String)

    /** Today's released rows of [slots] of one cluster variant. */
    fun Connection.releasedRows(today: LocalDate, scope: SlotScope, slots: Collection<String>): List<Row> {
        val array = createArrayOf("text", slots.toTypedArray())
        try {
            return rows(
                "SELECT slot, epoch, value, install_tag FROM slot_release WHERE release_day = ? AND ${scope.where()} AND slot = ANY(?)",
                today, *scope.args(), array,
            )
        } finally {
            array.free()
        }
    }

    /** The live counted rows of one slot of one cluster variant: what the operator's classifier reads. */
    fun Connection.liveRows(policy: Policy, today: LocalDate, scope: SlotScope, slot: String): List<Row> {
        val live = Epochs.live(today)
        return rows(
            """SELECT slot, epoch, value, install_tag FROM slot_sightings WHERE ${scope.where()} AND slot = ?
                AND ${policy.countedSlotRows()} AND epoch BETWEEN ? AND ?""",
            *scope.args(), slot, today, today, live.first, live.last,
        )
    }

    private fun Connection.rows(sql: String, vararg args: Any?): List<Row> = prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(Row(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4))) }
        }
    }

    /**
     * Per slot, the evidence of the epoch it is judged on ([bestEpoch]) with [reader]'s own rows left out: a row whose
     * tag equals the reader's tag for that row's object under that epoch's key is the reader's. An epoch without a key
     * in [keys] is left out whole.
     */
    fun leaveOneOut(rows: List<Row>, scope: SlotScope, reader: UUID, keys: Map<Int, EpochKey>): Map<String, SlotCounts> {
        val own = HashMap<Triple<String, String, Int>, String>()
        val present = HashMap<Pair<String, Int>, Int>()
        val values = HashMap<Pair<String, Int>, MutableMap<String, Int>>()
        for (row in rows) {
            val key = keys[row.epoch] ?: continue
            val readerTag = own.getOrPut(Triple(row.slot, row.value, row.epoch)) {
                key.tag(TagPurpose.SLOT, reader, SlotObject.of(scope.fingerprint, scope.variant, row.slot, row.value))
            }
            if (readerTag == row.tag) continue
            val group = row.slot to row.epoch
            if (row.value == SlotValues.PRESENT) present.merge(group, 1, Int::plus)
            else values.getOrPut(group) { HashMap() }.merge(row.value, 1, Int::plus)
        }
        return present.keys.groupBy { it.first }.mapValues { (_, groups) ->
            bestEpoch(groups.map { SlotCounts(it.second, present.getValue(it), values[it].orEmpty()) })
        }
    }

    /** The class of [counts] and, when it is constant, the value it is constant on. */
    fun Policy.classWithValue(counts: SlotCounts?): Pair<SlotClass, String?> {
        if (counts == null) return SlotClass.UNKNOWN to null
        val slotClass = classify(counts)
        val top = if (slotClass == SlotClass.CONSTANT) {
            counts.values.filterKeys { it !in SlotValues.MARKERS }.maxByOrNull { it.value }?.key
        } else null
        return slotClass to top
    }
}

/** One cluster variant as a contributor names it (#81): the stored cluster key and the #80 variant. */
data class SlotScope(val fingerprint: String, val variant: SlotVariant) {
    internal fun where(): String = "fingerprint = ? AND platform_app_version = ? AND hash_domain = ? AND filter_rev = ? AND layout = ?"
    internal fun args(): Array<Any> = arrayOf(fingerprint, variant.platformAppVersion, variant.hashDomain, variant.filterRev, variant.layout)
}
