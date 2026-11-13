package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.SlotConstancy
import cloud.trotter.census.server.SlotEvidence
import cloud.trotter.census.server.SlotValues
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.ingest.SlotWalk
import cloud.trotter.census.server.ingest.storedKey
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * The epoch-tag object of a slot sighting (#80): the row's whole identity, value included, so one install's rows for
 * two values (or two slots) carry unrelated tags, as its token rows do (#86). [SQL] rebuilds it from the columns for
 * erasure. Unambiguous: no field but the last (the app version, which has no `:` either) contains `:`.
 */
internal object SlotObject {
    fun of(fingerprint: String, hashDomain: Int, filterRev: Int, slot: String, value: String, version: String): String =
        "$fingerprint:$hashDomain:$filterRev:$slot:$value:$version"

    const val SQL: String =
        "fingerprint::text || ':' || hash_domain::text || ':' || filter_rev::text || ':' || slot || ':' || value || ':' || platform_app_version"
}

/** A cluster variant support is counted in (#80): one app version under one hash domain and filter revision. */
@Serializable
data class SlotVariant(val platformAppVersion: String, val hashDomain: Int, val filterRev: Int)

/** One slot's class (#80) with the installs and distinct hashes of the epoch it was judged on: operator views only. */
@Serializable
data class SlotReading(val slot: String, val slotClass: SlotClass, val installs: Int, val hashes: Int)

/** One cluster variant's slots (#80), sorted by slot id. */
@Serializable
data class SlotVariantReading(val variant: SlotVariant, val slots: List<SlotReading>)

/**
 * One skeleton's slots for the ingest transaction (#80): what one install saw in one cluster variant on one day.
 * [item] is already keyed ([cloud.trotter.census.server.ingest.atRest]).
 */
internal class SlotSighting(val item: CensusSkeletonDto, val variant: SlotVariant, val observed: LocalDate)

/**
 * Writes slot sightings inside the ingest transaction (#80). Per (variant, epoch): one presence row per slot the
 * install saw and one row per distinct value, each tagged under the observation day's epoch. Presence only: a repeat
 * moves `last_day` and merges the quarantine mark exactly as token sightings do.
 */
internal fun Connection.recordSlotSightings(
    tagger: Tagger, installId: UUID, trusted: Boolean, countsFrom: LocalDate?, fingerprint: String,
    sightings: List<SlotSighting>, slotLimit: Int,
) {
    data class Key(val variant: SlotVariant, val epoch: Int)
    val lastDays = HashMap<Key, LocalDate>()
    val values = HashMap<Key, MutableSet<Pair<String, String>>>()
    for (sighting in sightings) {
        val key = Key(sighting.variant, Epochs.of(sighting.observed))
        lastDays.merge(key, sighting.observed) { a, b -> maxOf(a, b) }
        val set = values.getOrPut(key) { LinkedHashSet() }
        for (slot in SlotWalk.slots(sighting.item, slotLimit)) {
            set += slot.slot to SlotValues.PRESENT
            set += slot.slot to slot.value
        }
    }
    var written = 0
    prepareStatement(
        """INSERT INTO slot_sightings (fingerprint, platform_app_version, hash_domain, filter_rev, slot, value, epoch,
                install_tag, trusted, counts_from, last_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (fingerprint, platform_app_version, hash_domain, filter_rev, slot, epoch, value, install_tag) DO UPDATE SET
                last_day = GREATEST(slot_sightings.last_day, EXCLUDED.last_day),
                counts_from = CASE WHEN slot_sightings.counts_from IS NULL OR EXCLUDED.counts_from IS NULL
                    THEN NULL ELSE LEAST(slot_sightings.counts_from, EXCLUDED.counts_from) END""",
    ).use { statement ->
        for ((key, rows) in values) {
            val day = lastDays.getValue(key)
            val variant = key.variant
            for ((slot, value) in rows) {
                val objectKey = SlotObject.of(fingerprint, variant.hashDomain, variant.filterRev, slot, value, variant.platformAppVersion)
                val tag = tagger.tag(TagPurpose.SLOT, installId, objectKey, day)
                listOf(fingerprint, variant.platformAppVersion, variant.hashDomain, variant.filterRev, slot, value, tag.epoch,
                    tag.tag, trusted, countsFrom, day).forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.addBatch()
                if (++written % 500 == 0) statement.executeBatch()
            }
        }
        statement.executeBatch()
    }
}

/**
 * Reads slot classes (#80, ADR-0011 §14). Support is counted per cluster variant and slot within ONE epoch, as
 * vocabulary k is (#86): distinct epoch tags of non-trusted, out-of-quarantine rows. Of the live epochs, a slot is
 * judged on the one where the most installs saw it (the newer one on a tie); epochs are never added together.
 */
class SlotStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    /**
     * The app's read (#80, app #1626): the classes of one cluster variant, keyed by the PUBLIC fingerprint the phone
     * holds. Classes only, and only the slots at or above k: `unknown` slots are left out, so a cluster seen by fewer
     * than k installs answers exactly as one nobody saw.
     */
    suspend fun appClasses(kind: SkeletonKind, publicFingerprint: String, variant: SlotVariant): Map<String, SlotClass> = query {
        slotReadings(policy, clock.today(), listOf(db.keying.storedKey(kind.wire, publicFingerprint)), variant)
            .values.singleOrNull()?.singleOrNull()?.slots.orEmpty()
            .filter { it.slotClass != SlotClass.UNKNOWN }
            .associate { it.slot to it.slotClass }
    }
}

/**
 * Every variant's slot readings for the stored cluster keys in [fingerprints], or only [variant]'s (#80). Shared by
 * the app's read and the operator views, inside the caller's transaction.
 */
internal fun Connection.slotReadings(
    policy: Policy, today: LocalDate, fingerprints: List<String>, variant: SlotVariant? = null,
): Map<String, List<SlotVariantReading>> {
    if (fingerprints.isEmpty()) return emptyMap()
    val constancy = policy.slotConstancy
    val hash = "value ~ '^[0-9a-f]{16}$'"
    val shared = "$hash AND installs >= ${constancy.regionalMinInstalls}"
    val sql = """WITH evidence AS (
            SELECT fingerprint, platform_app_version, hash_domain, filter_rev, slot, epoch, value, count(*) AS installs
            FROM slot_sightings
            WHERE fingerprint = ANY(?::bpchar[]) AND NOT trusted AND (counts_from IS NULL OR counts_from <= ?)
            AND last_day + ${policy.retention.clusterSightingsDays} > ?
            AND hash_domain IN (${policy.acceptedHashDomains.joinToString(",")}) AND filter_rev >= ${policy.minimumFilterRev}
            ${if (variant != null) "AND platform_app_version = ? AND hash_domain = ? AND filter_rev = ?" else ""}
            GROUP BY fingerprint, platform_app_version, hash_domain, filter_rev, slot, epoch, value
        )
        SELECT fingerprint, platform_app_version, hash_domain, filter_rev, slot, epoch,
            COALESCE(max(installs) FILTER (WHERE value = '${SlotValues.PRESENT}'), 0) AS seen,
            COALESCE(max(installs) FILTER (WHERE $hash), 0) AS top,
            count(*) FILTER (WHERE $hash) AS hashes,
            count(*) FILTER (WHERE $shared) AS shared,
            COALESCE(sum(installs) FILTER (WHERE $shared), 0) AS shared_installs,
            COALESCE(max(installs) FILTER (WHERE value = '${SlotValues.WITHHELD}'), 0) AS withheld,
            COALESCE(max(installs) FILTER (WHERE value = '${SlotValues.SHAPE}'), 0) AS shape
        FROM evidence GROUP BY fingerprint, platform_app_version, hash_domain, filter_rev, slot, epoch"""
    data class Row(val fingerprint: String, val variant: SlotVariant, val slot: String, val epoch: Int, val evidence: SlotEvidence)
    val keys = createArrayOf("text", fingerprints.toTypedArray())
    val rows = try {
        val args = listOf<Any?>(keys, today, today) +
            if (variant != null) listOf(variant.platformAppVersion, variant.hashDomain, variant.filterRev) else emptyList()
        select(sql, *args.toTypedArray()) { result -> buildList {
            do {
                add(Row(
                    result.getString("fingerprint"),
                    SlotVariant(result.getString("platform_app_version"), result.getInt("hash_domain"), result.getInt("filter_rev")),
                    result.getString("slot"), result.getInt("epoch"),
                    SlotEvidence(
                        installs = result.getInt("seen"), topHashInstalls = result.getInt("top"), hashes = result.getInt("hashes"),
                        sharedHashes = result.getInt("shared"), sharedHashInstalls = result.getInt("shared_installs"),
                        withheldInstalls = result.getInt("withheld"), shapeInstalls = result.getInt("shape"),
                    ),
                ))
            } while (result.next())
        } } ?: emptyList()
    } finally {
        keys.free()
    }
    return rows.groupBy { it.fingerprint }.mapValues { (_, perCluster) ->
        perCluster.groupBy { it.variant }.map { (slotVariant, perVariant) ->
            SlotVariantReading(slotVariant, perVariant.groupBy { it.slot }.map { (slot, perEpoch) ->
                val chosen = perEpoch.maxWith(compareBy<Row> { it.evidence.installs }.thenBy { it.epoch }).evidence
                SlotReading(slot, SlotConstancy.classify(chosen, policy.k, constancy), chosen.installs, chosen.hashes)
            }.sortedBy { it.slot })
        }.sortedWith(compareBy<SlotVariantReading>({ it.variant.platformAppVersion }, { it.variant.hashDomain }, { it.variant.filterRev }))
    }
}
