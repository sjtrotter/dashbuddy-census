package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.SlotConstancy
import cloud.trotter.census.server.SlotEvidence
import cloud.trotter.census.server.SlotValues
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.ingest.SlotWalk
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * The epoch-tag object of a slot sighting (#80): the row's whole identity, value included, so one install's rows for
 * two values (or two slots) carry unrelated tags, as its token rows do (#86). [SQL] rebuilds it from the columns for
 * erasure. Unambiguous: no field but the last (the app version, which has no `:` either) contains `:`.
 */
internal object SlotObject {
    fun of(fingerprint: String, variant: SlotVariant, slot: String, value: String): String =
        "$fingerprint:${variant.hashDomain}:${variant.filterRev}:${variant.layout}:$slot:$value:${variant.platformAppVersion}"

    const val SQL: String = "fingerprint::text || ':' || hash_domain::text || ':' || filter_rev::text || ':' || layout::text" +
        " || ':' || slot || ':' || value || ':' || platform_app_version"

    /** [SQL] over a table whose value column is [column] (#81: a role tag's `role`). */
    fun sql(column: String): String = SQL.replace("|| value ||", "|| $column ||")
}

/**
 * The cluster variant support is counted in (#80): one app version under one hash domain and filter revision, and one
 * raw layout ([SlotWalk.layout], Sol S4), so a slot path names the same node in every row counted together.
 */
@Serializable
data class SlotVariant(val platformAppVersion: String, val hashDomain: Int, val filterRev: Int, val layout: String)

/**
 * One slot's class (#80) with the installs and distinct hashes of the epoch it was judged on, and (#81) the role tags
 * contributors gave it, as non-trusted installs per role in the epoch where most tagged it: operator views only.
 */
@Serializable
data class SlotReading(val slot: String, val slotClass: SlotClass, val installs: Int, val hashes: Int, val roles: Map<String, Int> = emptyMap())

/** One cluster variant's slots (#80), sorted by slot id. */
@Serializable
data class SlotVariantReading(val variant: SlotVariant, val slots: List<SlotReading>)

/**
 * One skeleton's slots for the ingest transaction (#80): what one install saw in one cluster variant on one
 * observation day. [item] is already keyed ([cloud.trotter.census.server.ingest.atRest]).
 */
internal class SlotSighting(val item: CensusSkeletonDto, val variant: SlotVariant, val observed: LocalDate)

/**
 * Writes slot sightings inside the ingest transaction (#80). Per (variant, epoch): one presence row per slot the
 * install saw and one row per distinct value, each tagged under its observation day's epoch. A row's `last_day` is
 * the newest observation of that very slot and value (Sol S3), never of the batch, so an old value expires on its own
 * day. Presence only: a repeat moves `last_day` and merges the
 * quarantine mark exactly as token sightings do.
 */
internal fun Connection.recordSlotSightings(
    tagger: Tagger, installId: UUID, trusted: Boolean, countsFrom: LocalDate?, fingerprint: String,
    sightings: List<SlotSighting>, slotLimit: Int, received: LocalDate,
) {
    data class Row(val variant: SlotVariant, val epoch: Int, val slot: String, val value: String)
    val lastDays = LinkedHashMap<Row, LocalDate>()
    for (sighting in sightings) {
        val epoch = Epochs.of(sighting.observed)
        for (slot in SlotWalk.slots(sighting.item, slotLimit)) {
            for (value in listOf(SlotValues.PRESENT, slot.value)) {
                lastDays.merge(Row(sighting.variant, epoch, slot.slot, value), sighting.observed) { a, b -> maxOf(a, b) }
            }
        }
    }
    var written = 0
    prepareStatement(
        """INSERT INTO slot_sightings (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value, epoch,
                install_tag, trusted, counts_from, last_day, first_received, received_day)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, value, install_tag) DO UPDATE SET
                -- #81 (Sol S6): the first write on a new receipt day keeps the row's state before that day.
                prev_last_day = CASE WHEN slot_sightings.received_day < EXCLUDED.received_day
                    THEN slot_sightings.last_day ELSE slot_sightings.prev_last_day END,
                received_day = GREATEST(slot_sightings.received_day, EXCLUDED.received_day),
                last_day = GREATEST(slot_sightings.last_day, EXCLUDED.last_day),
                counts_from = CASE WHEN slot_sightings.counts_from IS NULL OR EXCLUDED.counts_from IS NULL
                    THEN NULL ELSE LEAST(slot_sightings.counts_from, EXCLUDED.counts_from) END""",
    ).use { statement ->
        for ((row, day) in lastDays) {
            val variant = row.variant
            val tag = tagger.tag(TagPurpose.SLOT, installId, SlotObject.of(fingerprint, variant, row.slot, row.value), day)
            check(tag.epoch == row.epoch)
            listOf(fingerprint, variant.platformAppVersion, variant.hashDomain, variant.filterRev, variant.layout, row.slot,
                row.value, tag.epoch, tag.tag, trusted, countsFrom, day, received, received)
                .forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
            statement.addBatch()
            if (++written % 500 == 0) statement.executeBatch()
        }
        statement.executeBatch()
    }
}

/** One slot of one variant within one epoch: who saw it ([present]) and the installs per value. */
internal class SlotCounts(val epoch: Int, val present: Int, val values: Map<String, Int>)

/** The epoch a slot is judged on (#80): the one where most installs saw it, the newer one on a tie. Never a sum. */
internal fun bestEpoch(perEpoch: Collection<SlotCounts>): SlotCounts =
    perEpoch.maxWith(compareBy<SlotCounts> { it.present }.thenBy { it.epoch })

internal fun Policy.classify(counts: SlotCounts): SlotClass =
    SlotConstancy.classify(SlotEvidence.of(counts.present, counts.values, slotConstancy), k, slotConstancy)

/**
 * The operator's live read (#80): every variant's slot readings for the stored cluster keys in [fingerprints], from the
 * rows as they stand now. Support is counted per variant and slot within ONE epoch, as vocabulary k is (#86): distinct
 * epoch tags of non-trusted, out-of-quarantine rows. Operator views only: a contributor's phone reads the day's release,
 * with its own rows left out ([SlotRelease], #81), never these live readings.
 */
internal fun Connection.slotReadings(policy: Policy, today: LocalDate, fingerprints: List<String>): Map<String, List<SlotVariantReading>> {
    if (fingerprints.isEmpty()) return emptyMap()
    data class Key(val fingerprint: String, val variant: SlotVariant, val slot: String, val epoch: Int)
    val present = HashMap<Key, Int>()
    val values = HashMap<Key, MutableMap<String, Int>>()
    val keys = createArrayOf("text", fingerprints.toTypedArray())
    try {
        prepareStatement(
            """SELECT fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, value, count(*) AS installs
                FROM slot_sightings
                WHERE fingerprint = ANY(?::bpchar[]) AND ${policy.countedSlotRows()}
                GROUP BY fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, value""",
        ).use { statement ->
            statement.setArray(1, keys)
            statement.setObject(2, today); statement.setObject(3, today)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val key = Key(rows.getString(1), SlotVariant(rows.getString(2), rows.getInt(3), rows.getInt(4), rows.getString(5)),
                        rows.getString(6), rows.getInt(7))
                    val value = rows.getString(8)
                    if (value == SlotValues.PRESENT) present[key] = rows.getInt(9)
                    else values.getOrPut(key) { HashMap() }[value] = rows.getInt(9)
                }
            }
        }
    } finally {
        keys.free()
    }
    return present.keys.groupBy { it.fingerprint }.mapValues { (_, perCluster) ->
        perCluster.groupBy { it.variant }.map { (variant, perVariant) ->
            SlotVariantReading(variant, perVariant.groupBy { it.slot }.map { (slot, epochs) ->
                val chosen = bestEpoch(epochs.map { SlotCounts(it.epoch, present.getValue(it), values[it].orEmpty()) })
                SlotReading(slot, policy.classify(chosen), chosen.present, chosen.values.keys.count { it !in SlotValues.MARKERS })
            }.sortedBy { it.slot })
        }.sortedWith(compareBy({ it.variant.platformAppVersion }, { it.variant.hashDomain }, { it.variant.filterRev }, { it.variant.layout }))
    }
}

/**
 * The rows k counting may use (#80, the vocabulary's rule, #86): non-trusted, past quarantine on the day bound to the
 * first parameter, inside retention on the day bound to the second, in an accepted domain at or above the floor.
 */
internal fun Policy.countedSlotRows(lastDay: String = "last_day"): String = """NOT trusted AND (counts_from IS NULL OR counts_from <= ?)
    AND $lastDay + ${retention.clusterSightingsDays} > ?
    AND hash_domain IN (${acceptedHashDomains.joinToString(",")}) AND filter_rev >= $minimumFilterRev"""

/**
 * #81: the role tags of one cluster, per variant and slot: for each role, the distinct non-trusted installs that gave it
 * within one live epoch (the epoch where most did; never a sum across epochs), from rows inside retention.
 */
internal fun Connection.slotRoles(policy: Policy, today: LocalDate, fingerprint: String): Map<Pair<SlotVariant, String>, Map<String, Int>> {
    val live = Epochs.live(today)
    val counts = HashMap<Pair<SlotVariant, String>, MutableMap<String, Int>>()
    prepareStatement(
        """SELECT platform_app_version, hash_domain, filter_rev, layout, slot, role, max(installs) FROM (
                SELECT platform_app_version, hash_domain, filter_rev, layout, slot, role, epoch, count(DISTINCT install_tag) AS installs
                FROM slot_roles WHERE fingerprint = ? AND NOT trusted AND last_day + ? > ? AND epoch BETWEEN ? AND ?
                GROUP BY platform_app_version, hash_domain, filter_rev, layout, slot, role, epoch
            ) per_epoch GROUP BY platform_app_version, hash_domain, filter_rev, layout, slot, role""",
    ).use { statement ->
        listOf(fingerprint, policy.contributor.retentionDays, today, live.first, live.last).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows ->
            while (rows.next()) {
                val variant = SlotVariant(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getString(4))
                counts.getOrPut(variant to rows.getString(5)) { sortedMapOf() }[rows.getString(6)] = rows.getInt(7)
            }
        }
    }
    return counts
}
