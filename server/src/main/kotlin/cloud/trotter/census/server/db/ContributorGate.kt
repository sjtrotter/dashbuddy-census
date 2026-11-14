package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.census.server.EpochKey
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.HashKeying
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.SlotValues
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.db.SlotRelease.classWithValue
import cloud.trotter.census.server.db.SlotRelease.leaveOneOut
import cloud.trotter.census.server.db.SlotRelease.liveRows
import cloud.trotter.census.server.db.SlotRelease.releaseDay
import cloud.trotter.census.server.db.SlotRelease.releasedRows
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** Why a submitted word was refused before the gate was asked (#81). */
enum class WordStatus(val wire: String) {
    ACCEPTED("accepted"),
    /** Not the shape of app wording: empty, too long, no canonical form, not 1 to 8 words, or a sensitive marker. */
    NOT_CHROME("not_chrome"),
    /** The word does not hash to the hash it was sent with. */
    HASH_MISMATCH("hash_mismatch"),
    /** The slot's gate is not open for this value. */
    GATE_CLOSED("gate_closed"),
}

/**
 * The word gate and the grant checks every contributor request shares (#81, ADR-0011 §15). Each function runs inside
 * its caller's transaction; the read paths call them inside one REPEATABLE READ snapshot.
 */
internal object ContributorGate {
    /** The install row, bound to the current credential: whether it is trusted, or null (fail closed). */
    fun Connection.activeInstall(installId: UUID, keyHash: String, lock: Boolean): Boolean? = select(
        "SELECT trusted FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL" + if (lock) " FOR SHARE" else "",
        installId, keyHash,
    ) { it.getBoolean(1) }

    /**
     * The install's live grant on [today], or null: issued on or before today, expiring after it, not revoked, and no
     * revoked grant of the install standing. [lock] share-locks it, so a revocation or withdrawal waits for the caller.
     */
    fun Connection.liveGrant(installId: UUID, today: LocalDate, lock: Boolean): UUID? = select(
        """SELECT grant_id FROM contributor_grants g WHERE install_id = ? AND revoked_day IS NULL AND granted_day <= ?
            AND expires_day > ? AND NOT EXISTS (SELECT 1 FROM contributor_grants r WHERE r.install_id = g.install_id
            AND r.revoked_day IS NOT NULL) ORDER BY expires_day DESC LIMIT 1""" + if (lock) " FOR SHARE" else "",
        installId, today, today,
    ) { it.getObject(1, UUID::class.java) }

    /**
     * Spends [amount] of the install's [column] quota for [today] on its ledger row (#81), which a data deletion keeps.
     * False when the day's [limit] would be passed; nothing is spent then.
     */
    fun Connection.consume(installId: UUID, today: LocalDate, column: String, amount: Int, limit: Int): Boolean {
        require(column in QUOTA_COLUMNS && amount >= 0)
        update("INSERT INTO ingest_ledger (install_id, day) VALUES (?, ?) ON CONFLICT DO NOTHING", installId, today)
        return update(
            "UPDATE ingest_ledger SET $column = $column + ? WHERE install_id = ? AND day = ? AND $column + ? <= ?",
            amount, installId, today, amount, limit,
        ) == 1
    }

    private val QUOTA_COLUMNS = setOf("contributor_reads", "contributor_reviews", "contributor_words", "contributor_quarantine")

    /** The live keys on [today] by epoch: the only keys an install's recent rows can be found under. */
    fun Connection.liveKeysByEpoch(keying: HashKeying, today: LocalDate): Map<Int, EpochKey> =
        with(EpochKeyStore) { liveKeys(keying, today) }.associateBy { it.epoch }

    /**
     * Whether [installId] holds a live row of [table] for [value] in [slot] of [scope]: found through its tag under
     * each live key, never by naming the install. [days] is the table's retention.
     */
    fun Connection.ownRow(
        table: TaggedTable, keys: Collection<EpochKey>, installId: UUID, scope: SlotScope, slot: String, value: String,
        today: LocalDate, days: Int,
    ): Boolean = keys.any { key ->
        // Sol S3: found by the tag alone (its object names the cluster, the variant, the slot and the value), so the
        // lookup reads the install's own rows only and costs the same however many peers share the cluster.
        val tag = key.tag(table.purpose, installId, SlotObject.of(scope.fingerprint, scope.variant, slot, value))
        select(
            "SELECT 1 FROM ${table.table} WHERE install_tag = ? AND epoch = ? AND last_day + ? > ? LIMIT 1",
            tag, key.epoch, days, today,
        ) { true } ?: false
    }

    /**
     * Whether [reader] holds a live sighting of any of [slots] in [scope] (Sol S3): one query over its presence tags
     * under each live key. A reader is answered only about a variant (or slot) it has itself uploaded. The slots come
     * from the caller, never from peer evidence, and the lookup is by tag alone (a tag's object names the cluster and
     * the variant), so its work depends on the reader's own rows only: nothing about a cluster the reader never sent
     * is touched before this answers.
     */
    fun Connection.sightedAny(keys: Collection<EpochKey>, reader: UUID, scope: SlotScope, slots: Collection<String>, today: LocalDate, days: Int): Boolean {
        if (slots.isEmpty() || keys.isEmpty()) return false
        val tags = keys.flatMap { key ->
            slots.map { key.tag(TagPurpose.SLOT, reader, SlotObject.of(scope.fingerprint, scope.variant, it, SlotValues.PRESENT)) }
        }
        val array = createArrayOf("text", tags.toTypedArray())
        try {
            return select(
                "SELECT 1 FROM slot_sightings WHERE install_tag = ANY(?::bpchar[]) AND last_day + ? > ? LIMIT 1",
                array, days, today,
            ) { true } ?: false
        } finally {
            array.free()
        }
    }

    /**
     * The gate (#81, ADR-0011 §15): open for [value] in [slot] only when, in the caller's snapshot,
     * 0. [reader] holds a live sighting of the slot (Sol S3),
     * 1. [reader] itself confirmed that value (so the gate answers no one about values they have not confirmed),
     * 2. the slot is constant on that very value, at k installs or more, both in today's release and in the live rows,
     *    each judged with [reader]'s own rows left out ([SlotRelease.leaveOneOut]): the reader's uploads never decide
     *    its own gate, and a value uploaded today reaches the release no sooner than tomorrow, and
     * 3. at least [cloud.trotter.census.server.ContributorPolicy.gateConfirmations] independent, non-trusted installs
     *    confirmed it within ONE epoch (one install's confirmations are one row per epoch, so it counts once).
     *
     * Sol S8: 0 and 1 read the reader's own rows only. Both conditions of 2 are always evaluated, and the confirmations
     * of 3 are read only once both hold, so every refusal below k runs the same statements whether or not other installs
     * confirmed the value.
     */
    fun Connection.gateOpen(
        policy: Policy, keys: Map<Int, EpochKey>, reader: UUID, today: LocalDate, scope: SlotScope, slot: String, value: String,
    ): Boolean {
        val days = policy.contributor.retentionDays
        if (!sightedAny(keys.values, reader, scope, listOf(slot), today, policy.retention.clusterSightingsDays)) return false
        if (!ownRow(TaggedTable.CONFIRMATIONS, keys.values, reader, scope, slot, value, today, days)) return false
        val constant = SlotClass.CONSTANT to value
        val released = releaseDay(today) &&
            policy.classWithValue(leaveOneOut(releasedRows(today, scope, listOf(slot)), scope, reader, keys)[slot]) == constant
        val live = policy.classWithValue(leaveOneOut(liveRows(policy, today, scope, slot), scope, reader, keys)[slot]) == constant
        if (!released || !live) return false
        return confirmations(policy, today, scope, slot, value) >= policy.contributor.gateConfirmations
    }

    /** Distinct non-trusted installs that confirmed [value] in [slot], in the live epoch where most did. */
    fun Connection.confirmations(policy: Policy, today: LocalDate, scope: SlotScope, slot: String, value: String): Int {
        val live = Epochs.live(today)
        return select(
            """SELECT COALESCE(max(n), 0) FROM (SELECT count(DISTINCT install_tag) AS n FROM slot_confirmations
                WHERE ${scope.where()} AND slot = ? AND value = ? AND NOT trusted AND last_day + ? > ? AND epoch BETWEEN ? AND ?
                GROUP BY epoch) per_epoch""",
            *scope.args(), slot, value, policy.contributor.retentionDays, today, live.first, live.last,
        ) { it.getInt(1) } ?: 0
    }

    /**
     * The checks a submitted word passes before any gate is asked: the shape of app wording (1 to 8 words, a
     * canonical form, no sensitive marker) and a public census hash equal to [publicHash]. Returns the canonical form
     * to store, or the refusal. Pure: nothing about a refused word is stored or logged.
     */
    fun checkWord(word: String, publicHash: String): Pair<String?, WordStatus?> {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return null to WordStatus.NOT_CHROME
        val canonical = CensusHash.canonical(word) ?: return null to WordStatus.NOT_CHROME
        val shape = KindClassifier.shapeKind(canonical)
        if (canonical.isEmpty() || shape !is KindClassifier.ShapeKind.Words || !shape.hashable) return null to WordStatus.NOT_CHROME
        if (SensitiveMarkerScan.findMarker(canonical) != null) return null to WordStatus.NOT_CHROME
        val hash = CensusHash.ofCanonical(canonical) ?: return null to WordStatus.NOT_CHROME
        if (hash != publicHash) return null to WordStatus.HASH_MISMATCH
        return canonical to null
    }

    const val MAX_WORD_LENGTH: Int = 128
}
