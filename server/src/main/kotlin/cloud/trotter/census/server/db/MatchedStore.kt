package cloud.trotter.census.server.db

import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.server.Retention
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.ingest.ItemVerdict
import cloud.trotter.census.server.ingest.LifecycleDeadline
import cloud.trotter.census.server.ingest.SlotWalk
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** The rule a matched item names (#69); null for every other item. */
internal val ItemVerdict.Accepted.matchedRuleId: String? get() = (item as? UiSkeletonDto)?.ruleId

/**
 * Matched skeletons (#69, gig.log#1573) inside the ingest transaction: screens a recognition rule matched while it was
 * failing to read them, each tagged with the rule's id. They never touch the cluster tables, so a known screen never
 * enters the UNKNOWN catalogue, never moves a cluster's `last_seen_day` and never feeds vocabulary, the anchor check
 * or the contributor release. Three tables, written here only:
 *
 * - `matched_samples`: at most 5 per (rule, fingerprint, app version), one per receipt day, the `cluster_samples`
 *   rule. Names no install. A sample keeps its keyed slot hashes (#85) and lives until its observation day + the
 *   cluster-sighting retention, so a late upload never outlives its sightings and no hash-expiry rewrite is needed.
 * - `matched_sightings`: presence per epoch tag ([TagPurpose.MATCHED], object `<ruleId>:<fingerprint>`) and day.
 * - `matched_slot_sightings`: the slot evidence, written like `slot_sightings` ([SlotTable.matched]).
 *
 * Items are handled in (rule, fingerprint) order; a transaction-scoped advisory lock per pair serializes the sample
 * cap between concurrent installs, always taken after the cluster locks and in that order, so ingests never deadlock.
 */
internal fun Connection.recordMatched(
    tagger: Tagger, installId: UUID, trusted: Boolean, countsFrom: LocalDate?, received: LocalDate,
    accepted: List<ItemVerdict.Accepted>, slotLimit: Int, retention: Retention,
): Int {
    var samplesStored = 0
    val groups = accepted.groupBy { requireNotNull(it.matchedRuleId) to it.item.fingerprint }
    for ((key, group) in groups.toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })) {
        val (ruleId, fingerprint) = key
        select("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "matched:$ruleId:$fingerprint") { true }
        for ((version, samples) in group.groupBy { it.item.platformAppVersion ?: "unknown" }) {
            val selected = samples.first()
            val observed = LifecycleDeadline.observation(LocalDate.parse(selected.item.day), received)
            samplesStored += update(
                """INSERT INTO matched_samples (rule_id, platform, fingerprint, platform_app_version, received_day, skeleton,
                        hash_domain, filter_rev, purge_after)
                    SELECT ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ? WHERE (SELECT count(*) FROM matched_samples
                    WHERE rule_id = ? AND fingerprint = ? AND platform_app_version = ?) < 5 ON CONFLICT DO NOTHING""",
                ruleId, selected.item.platform, fingerprint, version, received, selected.canonicalJson,
                selected.item.hashDomain, selected.item.filterRev, observed.plusDays(retention.clusterSightingsDays.toLong()),
                ruleId, fingerprint, version,
            )
        }
        val slotSightings = ArrayList<SlotSighting>(group.size)
        for (acceptedItem in group) {
            val item = acceptedItem.item
            val observed = LifecycleDeadline.observation(LocalDate.parse(item.day), received)
            val version = item.platformAppVersion ?: "unknown"
            slotSightings += SlotSighting(item, SlotVariant(version, item.hashDomain, item.filterRev, SlotWalk.layout(item)), observed)
            val sighting = tagger.tag(TagPurpose.MATCHED, installId, "$ruleId:$fingerprint", observed)
            update(
                """INSERT INTO matched_sightings (rule_id, fingerprint, epoch, install_tag, trusted, day, platform_app_version, hash_domain, filter_rev)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                ruleId, fingerprint, sighting.epoch, sighting.tag, trusted, observed, version, item.hashDomain, item.filterRev,
            )
        }
        recordSlotSightings(tagger, installId, trusted, countsFrom, fingerprint, slotSightings, slotLimit, received, SlotTable.matched(ruleId))
    }
    return samplesStored
}
