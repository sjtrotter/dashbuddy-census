package cloud.trotter.census.server.ingest

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.server.HashKeying

/**
 * #85: an accepted item as it may be stored. Every slot hash and every token is keyed under the pepper; a
 * notification's fingerprint, which folds its title and text hashes in, is keyed too. A screen fingerprint covers
 * class and id only and stays public. Validation (fingerprint recompute, size) ran on the public item; keying keeps
 * every width, so the stored item has the same shape and size.
 */
fun HashKeying.atRest(accepted: ItemVerdict.Accepted): ItemVerdict.Accepted {
    val item = when (val public = accepted.item) {
        is UiSkeletonDto -> public.copy(windowTitle = public.windowTitle?.let { slot(it) }, root = node(public.root))
        is NotificationSkeletonDto -> public.copy(
            fingerprint = fingerprint(public.fingerprint),
            slots = public.slots.mapValues { slot(it.value) },
        )
    }
    val measured = CensusSkeletonSchema.measure(item)
    val tokens = accepted.tokens.map { it.copy(key = it.key.copy(hash = token(it.key.hash))) }
    return ItemVerdict.Accepted(item, measured.json, measured.bytes, tokens)
}

private fun HashKeying.slot(slot: TextSlot): TextSlot = slot.h?.let { slot.copy(h = token(it)) } ?: slot

private fun HashKeying.node(node: UiSkeletonNodeDto): UiSkeletonNodeDto = node.copy(
    text = node.text.mapValues { slot(it.value) },
    children = node.children.map { node(it) },
)
