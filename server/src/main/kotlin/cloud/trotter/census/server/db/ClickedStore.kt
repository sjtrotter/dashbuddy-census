package cloud.trotter.census.server.db

import cloud.trotter.census.contract.TapRef
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.server.Retention
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.LifecycleDeadline
import cloud.trotter.census.server.ingest.SlotWalk
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** The tap a clicked item names (#87); null for every other item. */
internal val ItemVerdict.Accepted.clickedTap: TapRef? get() = (item as? UiSkeletonDto)?.tap

/**
 * What a clicked skeleton counts toward (#87, gig.log#1634): one button of one screen, the node that took the tap
 * ([ownerPath]) in one raw layout ([SlotWalk.layout]) of one fingerprint. The fingerprint splices anonymous wrappers
 * away, so one owner path names one node only within one layout; two owners of one screen are two targets, and so is
 * one owner path in two layouts of one fingerprint.
 */
internal data class ClickedTarget(val fingerprint: String, val layout: String, val ownerPath: String) {
    /**
     * The epoch tag's object ([TagPurpose.CLICKED]): `fingerprint:layout:ownerPath`. Two hex digests and a node path
     * (`root/1/0`), none of which holds a `:`, so the object names one target.
     */
    val objectKey: String get() = "$fingerprint:$layout:$ownerPath"

    companion object {
        fun of(accepted: ItemVerdict.Accepted): ClickedTarget = ClickedTarget(
            accepted.item.fingerprint, SlotWalk.layout(accepted.item), requireNotNull(accepted.clickedTap) { "not a clicked item" }.ownerPath,
        )

        val ORDER: Comparator<ClickedTarget> = compareBy<ClickedTarget> { it.fingerprint }.thenBy { it.layout }.thenBy { it.ownerPath }
    }
}

/**
 * Clicked skeletons (#87) inside the ingest transaction: the tapped window's screen, with the tapped button as a path
 * into it. Like matched skeletons they never touch the cluster tables, so a tap never enters the UNKNOWN catalogue,
 * never moves a cluster's `last_seen_day` and never feeds token or slot sightings, vocabulary, the anchor check or the
 * contributor release. Two tables, written here only:
 *
 * - `clicked_samples`: one anonymous sample per (target, app version, observation day), the first that arrives. Names
 *   no install. It keeps its keyed slot hashes (#85) and lives until its observation day (clamped to the receipt day)
 *   + the cluster-sighting retention, as a matched sample does.
 * - `clicked_sightings`: presence per epoch tag ([TagPurpose.CLICKED], object [ClickedTarget.objectKey]) and
 *   observation day: the operator view's support.
 *
 * Rows are written in key order (samples, then sightings), so two concurrent batches that share a sample key wait on
 * each other in one order and never deadlock. No arrival order is stored or relied on.
 */
internal fun Connection.recordClicked(
    tagger: Tagger, installId: UUID, trusted: Boolean, received: LocalDate,
    accepted: List<ItemVerdict.Accepted>, retention: Retention,
): Int {
    data class Row(val target: ClickedTarget, val version: String, val observed: LocalDate, val accepted: ItemVerdict.Accepted)
    val rows = accepted.map { item ->
        Row(ClickedTarget.of(item), item.item.platformAppVersion ?: "unknown",
            LifecycleDeadline.observation(LocalDate.parse(item.item.day), received), item)
    }.sortedWith(compareBy<Row, ClickedTarget>(ClickedTarget.ORDER) { it.target }.thenBy { it.version }.thenBy { it.observed })
    var samplesStored = 0
    for ((target, version, observed, item) in rows) {
        samplesStored += update(
            """INSERT INTO clicked_samples (platform, fingerprint, layout, owner_path, platform_app_version, day, received_day,
                    skeleton, hash_domain, filter_rev, purge_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) ON CONFLICT DO NOTHING""",
            item.item.platform, target.fingerprint, target.layout, target.ownerPath, version, observed, received,
            item.canonicalJson, item.item.hashDomain, item.item.filterRev, observed.plusDays(retention.clusterSightingsDays.toLong()),
        )
    }
    for ((target, version, observed, item) in rows) {
        val sighting = tagger.tag(TagPurpose.CLICKED, installId, target.objectKey, observed)
        update(
            """INSERT INTO clicked_sightings (fingerprint, layout, owner_path, epoch, install_tag, trusted, day, platform_app_version,
                    hash_domain, filter_rev)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
            target.fingerprint, target.layout, target.ownerPath, sighting.epoch, sighting.tag, trusted, observed, version,
            item.item.hashDomain, item.item.filterRev,
        )
    }
    return samplesStored
}
