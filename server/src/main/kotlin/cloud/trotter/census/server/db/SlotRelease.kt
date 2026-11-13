package cloud.trotter.census.server.db

import cloud.trotter.census.contract.SkeletonKind
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.EpochKey
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.SlotValues
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.ingest.storedKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Replaces the released slot evidence (#80, Sol S1), at most once per UTC day: the per-value install counts of every
 * slot that k installs saw in its best-supported epoch, counting only rows received before [today]. Null when today's
 * release already exists; otherwise the rows written. Run by the lifecycle sweep after retention, so a released row
 * still exists until the next release.
 */
internal fun Connection.releaseSlotEvidence(policy: Policy, today: LocalDate, now: Instant): Int? {
    val released = select("SELECT released_day FROM slot_release WHERE singleton FOR UPDATE") { it.getObject(1, LocalDate::class.java) }
    if (released != null && released >= today) return null
    update("DELETE FROM slot_evidence_released")
    val columns = "fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot"
    val written = update(
        """INSERT INTO slot_evidence_released ($columns, epoch, value, installs)
            WITH evidence AS (
                SELECT $columns, epoch, value, count(*) AS installs FROM slot_sightings
                WHERE ${policy.countedSlotRows()} AND first_received < ?
                GROUP BY $columns, epoch, value
            ), best AS (
                SELECT DISTINCT ON ($columns) $columns, epoch FROM evidence
                WHERE value = '${SlotValues.PRESENT}' AND installs >= ${policy.k}
                ORDER BY $columns, installs DESC, epoch DESC
            )
            SELECT e.fingerprint, e.platform_app_version, e.hash_domain, e.filter_rev, e.layout, e.slot, e.epoch, e.value, e.installs
            FROM evidence e JOIN best b USING ($columns, epoch)""",
        today, today, today,
    )
    update(
        """INSERT INTO slot_release (singleton, released_day, released_at) VALUES (true, ?, ?)
            ON CONFLICT (singleton) DO UPDATE SET released_day = EXCLUDED.released_day, released_at = EXCLUDED.released_at""",
        today, now.atOffset(ZoneOffset.UTC),
    )
    return written
}

/**
 * The app's read of slot classes (#80, ADR-0011 §14, app #1626), answered from the daily release, never from live rows
 * (Sol S1), and never decided by the asking install's own uploads:
 *
 * - rows count only once received before the release day, and the release changes once a day, so an upload cannot move
 *   a class its uploader can read until the next release;
 * - before classifying, the asking install's own counted rows are found (its epoch tags, recomputed over the slot's
 *   released values) and subtracted, so its own rows never decide its own answer;
 * - an install that erased its data after the release (its counted rows are gone and cannot be subtracted) gets no
 *   classes until the next release.
 *
 * Classes only; `unknown` slots are left out, so a cluster fewer than k installs saw answers exactly as one nobody saw.
 * The residual (a ring of colluding installs) is in docs/SECURITY.md.
 */
class SlotStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }

    suspend fun appClasses(installId: UUID, kind: SkeletonKind, publicFingerprint: String, variant: SlotVariant): Map<String, SlotClass> = query {
        val release = select("SELECT released_day, released_at FROM slot_release WHERE singleton") {
            it.getObject(1, LocalDate::class.java) to it.getObject(2, OffsetDateTime::class.java).toInstant()
        } ?: return@query emptyMap()
        val (releasedDay, releasedAt) = release
        val erased = select("SELECT erased_through FROM installs WHERE install_id = ?", installId) {
            it.getObject(1, OffsetDateTime::class.java)?.toInstant()
        }
        if (erased != null && erased > releasedAt) return@query emptyMap()
        val fingerprint = db.keying.storedKey(kind.wire, publicFingerprint)
        val slots = released(fingerprint, variant)
        if (slots.isEmpty()) return@query emptyMap()
        val keys = with(EpochKeyStore) { storedKeys(db.keying) }.associateBy { it.epoch }
        val own = ownCountedRows(installId, fingerprint, variant, slots, keys, releasedDay)
        slots.mapNotNull { (slot, counts) ->
            val mine = own[slot].orEmpty()
            val values = counts.values.mapValues { (value, installs) -> installs - (if (value in mine) 1 else 0) }.filterValues { it > 0 }
            val present = counts.present - (if (SlotValues.PRESENT in mine) 1 else 0)
            policy.classify(SlotCounts(counts.epoch, present, values)).takeIf { it != SlotClass.UNKNOWN }?.let { slot to it }
        }.toMap()
    }

    private fun Connection.released(fingerprint: String, variant: SlotVariant): Map<String, SlotCounts> {
        data class Row(val slot: String, val epoch: Int, val value: String, val installs: Int)
        val rows = prepareStatement(
            """SELECT slot, epoch, value, installs FROM slot_evidence_released WHERE fingerprint = ? AND platform_app_version = ?
                AND hash_domain = ? AND filter_rev = ? AND layout = ?""",
        ).use { statement ->
            listOf(fingerprint, variant.platformAppVersion, variant.hashDomain, variant.filterRev, variant.layout)
                .forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(Row(result.getString(1), result.getInt(2), result.getString(3), result.getInt(4))) }
            }
        }
        return rows.groupBy { it.slot }.mapValues { (_, perSlot) ->
            SlotCounts(perSlot.first().epoch, perSlot.firstOrNull { it.value == SlotValues.PRESENT }?.installs ?: 0,
                perSlot.filter { it.value != SlotValues.PRESENT }.associate { it.value to it.installs })
        }
    }

    /**
     * The asking install's rows the release counted, per slot, as the set of their values (presence included): its tag
     * for every released value of the slot, looked up under the predicate the release used on its day.
     */
    private fun Connection.ownCountedRows(
        installId: UUID, fingerprint: String, variant: SlotVariant, slots: Map<String, SlotCounts>,
        keys: Map<Int, EpochKey>, releasedDay: LocalDate,
    ): Map<String, Set<String>> {
        val owners = HashMap<String, Pair<String, String>>()
        for ((slot, counts) in slots) {
            val key = keys[counts.epoch] ?: continue // A destroyed key: nobody, the asker included, can own those rows.
            for (value in counts.values.keys + SlotValues.PRESENT) {
                owners[key.tag(TagPurpose.SLOT, installId, SlotObject.of(fingerprint, variant, slot, value))] = slot to value
            }
        }
        val found = HashMap<String, MutableSet<String>>()
        for (chunk in owners.keys.chunked(TaggedTable.CHUNK)) {
            val tags = createArrayOf("text", chunk.toTypedArray())
            try {
                prepareStatement(
                    """SELECT install_tag FROM slot_sightings WHERE fingerprint = ? AND platform_app_version = ? AND hash_domain = ?
                        AND filter_rev = ? AND layout = ? AND install_tag = ANY(?::bpchar[]) AND first_received < ?
                        AND ${policy.countedSlotRows()}""",
                ).use { statement ->
                    listOf(fingerprint, variant.platformAppVersion, variant.hashDomain, variant.filterRev, variant.layout, tags,
                        releasedDay, releasedDay, releasedDay).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            val (slot, value) = owners.getValue(rows.getString(1))
                            found.getOrPut(slot) { HashSet() } += value
                        }
                    }
                }
            } finally {
                tags.free()
            }
        }
        return found
    }
}
