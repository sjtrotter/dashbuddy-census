package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.SlotValues

/** One text slot of a skeleton: where it sits ([SlotWalk] ids) and what it held, as stored (#80). */
data class SlotValue(val slot: String, val value: String)

/**
 * The slot positions of a skeleton (#80), the ids the app's review screen and the server agree on:
 *
 * - a screen node's text field: `root`, then the child index of every step down the tree as the skeleton sends it,
 *   then the text key: `root/text`, `root/0/2/desc`;
 * - the window title: `windowTitle`;
 * - a notification field: its wire name (`title`, `text`, `bigText`, `tickerText`, `subText`); an action label:
 *   `action/<index>`.
 *
 * The path is the tree as sent, anonymous wrappers included, so the phone computes it from the skeleton it holds. A
 * layout whose wrappers come and go splits one visual slot across paths, each counted on its own: that errs toward
 * `unknown`, never toward a false `constant`. No id contains `:`, which the epoch-tag object uses as a separator.
 *
 * A slot's value is its keyed word hash when it carries one (the item must already be keyed, [atRest]), else a
 * [SlotValues] marker. Pre-order, at most [limit] slots: deterministic, so every install drops the same ones.
 */
object SlotWalk {
    const val WINDOW_TITLE: String = "windowTitle"
    private const val ROOT = "root"
    private const val ACTION = "action"

    fun slots(item: CensusSkeletonDto, limit: Int): List<SlotValue> {
        val out = ArrayList<SlotValue>()
        fun add(slot: String, value: TextSlot) { if (out.size < limit) out += SlotValue(slot, valueOf(value)) }
        when (item) {
            is UiSkeletonDto -> {
                item.windowTitle?.let { add(WINDOW_TITLE, it) }
                // Iterative pre-order: the contract caps depth, but a recursion here would still be the deepest stack.
                val pending = ArrayDeque<Pair<UiSkeletonNodeDto, String>>()
                pending.addLast(item.root to ROOT)
                while (pending.isNotEmpty() && out.size < limit) {
                    val (node, path) = pending.removeLast()
                    for ((key, value) in node.text.toSortedMap()) add("$path/$key", value)
                    node.children.indices.reversed().forEach { pending.addLast(node.children[it] to "$path/$it") }
                }
            }
            is NotificationSkeletonDto -> {
                NotifTextField.entries.forEach { field -> item.slots[field]?.let { add(field.wire, it) } }
                item.actions.forEachIndexed { index, value -> add("$ACTION/$index", value) }
            }
        }
        return out
    }

    private fun valueOf(slot: TextSlot): String = slot.h ?: when (slot.kind) {
        KindClassifier.WITHHELD -> SlotValues.WITHHELD
        KindClassifier.SHAPE -> SlotValues.SHAPE
        else -> SlotValues.UNHASHED
    }
}
