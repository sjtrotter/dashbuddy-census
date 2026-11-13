package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.AnonymousWrappers
import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.SlotValues
import cloud.trotter.census.server.auth.sha256Hex
import java.io.ByteArrayOutputStream

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
 * cluster's fingerprint splices anonymous wrappers away, so two raw trees of one cluster can put different nodes at
 * one path (Sol S4). Slot evidence is therefore partitioned by [layout], the raw tree's own shape: one path means one
 * node only within one layout. No id contains `:`, which the epoch-tag object uses as a separator.
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

    /**
     * The raw layout id (#80, Sol S4): lowercase hex sha256 over the skeleton's tree exactly as sent, wrappers
     * included, so two trees share it only when every node sits at the same path. A screen writes `L1`, then per node
     * in pre-order: the length-prefixed class (empty when absent), `N` for no id or `I` and the length-prefixed id, the
     * child count in ASCII decimal and a 0 byte, then for an anonymous wrapper (no id, a wrapper class) `W` and its
     * text keys, sorted, as a count and length-prefixed keys, else `-`. A wrapper that carries text therefore never
     * shares a layout with a bare one. Length-prefixed is the UTF-8 byte count in ASCII decimal, a 0 byte, the bytes.
     * A notification's layout is the same digest over `N1` and its action count; its fields have fixed ids.
     */
    fun layout(item: CensusSkeletonDto): String {
        val out = ByteArrayOutputStream()
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(bytes.size.toString().toByteArray(Charsets.US_ASCII)); out.write(0); out.write(bytes)
        }
        fun number(value: Int) { out.write(value.toString().toByteArray(Charsets.US_ASCII)); out.write(0) }
        when (item) {
            is UiSkeletonDto -> {
                out.write("L1".toByteArray(Charsets.US_ASCII))
                val pending = ArrayDeque<UiSkeletonNodeDto>().apply { addLast(item.root) }
                while (pending.isNotEmpty()) {
                    val node = pending.removeLast()
                    text(node.className ?: "")
                    val id = node.id
                    if (id == null) out.write('N'.code) else { out.write('I'.code); text(id) }
                    number(node.children.size)
                    if (AnonymousWrappers.isAnonymousWrapper(node.className, node.id)) {
                        out.write('W'.code)
                        val keys = node.text.keys.sorted()
                        number(keys.size)
                        keys.forEach { text(it) }
                    } else {
                        out.write('-'.code)
                    }
                    node.children.asReversed().forEach { pending.addLast(it) }
                }
            }
            is NotificationSkeletonDto -> {
                out.write("N1".toByteArray(Charsets.US_ASCII))
                number(item.actions.size)
            }
        }
        return sha256Hex(out.toByteArray())
    }

    private fun valueOf(slot: TextSlot): String = slot.h ?: when (slot.kind) {
        KindClassifier.WITHHELD -> SlotValues.WITHHELD
        KindClassifier.SHAPE -> SlotValues.SHAPE
        else -> SlotValues.UNHASHED
    }
}
