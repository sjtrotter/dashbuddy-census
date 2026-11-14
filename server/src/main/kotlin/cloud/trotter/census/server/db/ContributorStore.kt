package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.SlotRole
import cloud.trotter.census.server.SlotValues
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.db.ContributorGate.activeInstall
import cloud.trotter.census.server.db.ContributorGate.consume
import cloud.trotter.census.server.db.ContributorGate.gateOpen
import cloud.trotter.census.server.db.ContributorGate.liveGrant
import cloud.trotter.census.server.db.ContributorGate.liveKeysByEpoch
import cloud.trotter.census.server.db.ContributorGate.ownRow
import cloud.trotter.census.server.db.ContributorGate.sightedAny
import cloud.trotter.census.server.db.SlotRelease.leaveOneOut
import cloud.trotter.census.server.db.SlotRelease.releaseDay
import cloud.trotter.census.server.db.SlotRelease.releasedRows
import cloud.trotter.census.server.secondsToUtcMidnight
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** How a contributor request ends (#81); the route maps each to one status and error code. */
sealed interface ContributorOutcome<out T> {
    data class Ok<T>(val value: T) : ContributorOutcome<T>

    /** Not this credential generation, or revoked: `401 unauthorized`. */
    data object Stale : ContributorOutcome<Nothing>

    /** No live contributor grant: `403 grant_required`. */
    data object NoGrant : ContributorOutcome<Nothing>

    /** The day's contributor quota is spent: `429 budget_exhausted`. */
    data class BudgetExhausted(val retryAfterSeconds: Long) : ContributorOutcome<Nothing>

    /** Today's slot release is not built yet: `503 release_unavailable`. */
    data object ReleaseUnavailable : ContributorOutcome<Nothing>
}

/** A refusal carried over to an outcome of another type; null for [ContributorOutcome.Ok]. */
internal fun ContributorOutcome<*>.refusal(): ContributorOutcome<Nothing>? = when (this) {
    is ContributorOutcome.Ok -> null
    ContributorOutcome.Stale -> ContributorOutcome.Stale
    ContributorOutcome.NoGrant -> ContributorOutcome.NoGrant
    is ContributorOutcome.BudgetExhausted -> this
    ContributorOutcome.ReleaseUnavailable -> ContributorOutcome.ReleaseUnavailable
}

/** A grant as the phone sees it (#81): its state and days, never another install's. */
data class GrantView(val status: String, val grantedDay: LocalDate? = null, val expiresDay: LocalDate? = null)

/** The answer to a grant request (#81). */
sealed interface GrantOutcome {
    data class Granted(val view: GrantView) : GrantOutcome
    data object NotEligible : GrantOutcome
    data object TooNew : GrantOutcome
    data object Revoked : GrantOutcome
    data object Stale : GrantOutcome
}

/** What one review stored (#81). [skipped] counts items naming a slot or value the install has no live sighting of. */
data class ReviewResult(val confirmations: Int, val roles: Int, val skipped: Int)

/** One word a contributor sends once its gate is open: the slot, the public hash it confirmed, the word. */
data class WordItem(val slot: String, val publicHash: String, val word: String)

/**
 * The contributor tier (#81, ADR-0011 §15): grants, reviews (confirmations and role tags), the word gate and the slot
 * classes a contributor's phone may read. Contributor status is separate from `installs.trusted` and never permits an
 * envelope upload. Every write checks the install and its grant in the transaction that writes; nothing a contributor
 * sends names the install (epoch tags, #86).
 */
class ContributorStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    private val limits get() = policy.contributor

    private suspend fun <T> query(isolation: Int? = null, readOnly: Boolean = false, block: Connection.() -> T): T =
        withContext(Dispatchers.IO) {
            var attempt = 0
            var result: Result<T>
            do {
                result = runCatching {
                    transaction(db.exposed, isolation, readOnly) {
                        maxAttempts = 1
                        (connection.connection as Connection).block()
                    }
                }
                // A REPEATABLE READ transaction that meets a concurrent commit is refused (40001); run it again on a
                // fresh snapshot. Nothing it wrote survived the refusal.
                val retry = result.exceptionOrNull()?.isSerializationFailure() == true && ++attempt < SERIALIZATION_ATTEMPTS
            } while (retry)
            result.getOrThrow()
        }

    /** One consistent snapshot for every read a contributor answer is computed from (Sol N1). */
    private suspend fun <T> snapshot(readOnly: Boolean = true, block: Connection.() -> T): T =
        query(Connection.TRANSACTION_REPEATABLE_READ, readOnly, block)

    /**
     * A grant request (#81): the phone asserts eligibility as a yes or no and sends no count. Granted only to an
     * install past the server's quarantine (so a fresh install cannot be the reading half of a pair), never while a
     * revoked grant stands. Idempotent while a grant is live; after expiry a new one starts.
     */
    suspend fun requestGrant(installId: UUID, keyHash: String, eligible: Boolean, today: LocalDate): GrantOutcome = query {
        val createdDay = select(
            "SELECT created_day FROM installs WHERE install_id = ? AND key_hash = ? AND revoked_at IS NULL FOR UPDATE",
            installId, keyHash,
        ) { it.getObject(1, LocalDate::class.java) } ?: return@query GrantOutcome.Stale
        if (!eligible) return@query GrantOutcome.NotEligible
        if (revokedGrant(installId)) return@query GrantOutcome.Revoked
        liveGrantView(installId, today)?.let { return@query GrantOutcome.Granted(it) }
        if (VocabularyEligibility.countsFrom(createdDay, today) != null) return@query GrantOutcome.TooNew
        val expires = today.plusDays(limits.grantDays.toLong())
        update(
            "INSERT INTO contributor_grants (grant_id, install_id, granted_day, expires_day) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(), installId, today, expires,
        )
        GrantOutcome.Granted(GrantView(GRANTED, today, expires))
    }

    /** The install's grant state on [today]; null for a stale credential. */
    suspend fun grantStatus(installId: UUID, keyHash: String, today: LocalDate): GrantView? = query {
        activeInstall(installId, keyHash, lock = false) ?: return@query null
        when {
            revokedGrant(installId) -> GrantView(REVOKED)
            else -> liveGrantView(installId, today)
                ?: select("SELECT max(expires_day) FROM contributor_grants WHERE install_id = ?", installId) {
                    it.getObject(1, LocalDate::class.java)
                }?.let { GrantView(EXPIRED, expiresDay = it) }
                ?: GrantView(NONE)
        }
    }

    private fun Connection.revokedGrant(installId: UUID): Boolean =
        select("SELECT 1 FROM contributor_grants WHERE install_id = ? AND revoked_day IS NOT NULL LIMIT 1", installId) { true } ?: false

    private fun Connection.liveGrantView(installId: UUID, today: LocalDate): GrantView? = liveGrant(installId, today, lock = false)?.let { id ->
        select("SELECT granted_day, expires_day FROM contributor_grants WHERE grant_id = ?", id) {
            GrantView(GRANTED, it.getObject(1, LocalDate::class.java), it.getObject(2, LocalDate::class.java))
        }
    }

    /**
     * One screen's review (#81): confirmations (a slot and the public hash of its word, keyed here before storage, #85)
     * and role tags. One transaction: the install and its grant are share-locked, the day's review quota spent, and
     * each item stored under the install's epoch tag for its own row (#86). An item for a slot or value the install
     * has no live sighting of is skipped: a contributor confirms what it uploaded, so send the skeleton first.
     */
    suspend fun review(
        installId: UUID, keyHash: String, today: LocalDate, scope: SlotScope,
        confirmations: List<Pair<String, String>>, roles: List<Pair<String, SlotRole>>,
    ): ContributorOutcome<ReviewResult> = query {
        val trusted = activeInstall(installId, keyHash, lock = true) ?: return@query ContributorOutcome.Stale
        liveGrant(installId, today, lock = true) ?: return@query ContributorOutcome.NoGrant
        if (!consume(installId, today, "contributor_reviews", 1, limits.dailyReviews)) return@query exhausted()
        val keys = liveKeysByEpoch(db.keying, today).values
        val tagger = Tagger(this, db.keying, today)
        val sightingDays = policy.retention.clusterSightingsDays
        var skipped = 0
        var stored = 0
        for ((slot, publicHash) in confirmations.distinct()) {
            val value = db.keying.token(publicHash)
            if (!ownRow(TaggedTable.SLOTS, keys, installId, scope, slot, value, today, sightingDays)) { skipped++; continue }
            stored += insertTagged("slot_confirmations", "value", tagger.tag(TagPurpose.CONFIRMATION, installId,
                SlotObject.of(scope.fingerprint, scope.variant, slot, value), today), scope, slot, value, trusted, today)
        }
        var tagged = 0
        for ((slot, role) in roles.distinct()) {
            if (!ownRow(TaggedTable.SLOTS, keys, installId, scope, slot, SlotValues.PRESENT, today, sightingDays)) { skipped++; continue }
            tagged += insertTagged("slot_roles", "role", tagger.tag(TagPurpose.ROLE, installId,
                SlotObject.of(scope.fingerprint, scope.variant, slot, role.wire), today), scope, slot, role.wire, trusted, today)
        }
        ContributorOutcome.Ok(ReviewResult(stored, tagged, skipped))
    }

    private fun Connection.insertTagged(
        table: String, column: String, tag: EpochTag, scope: SlotScope, slot: String, value: String, trusted: Boolean, today: LocalDate,
    ): Int = update(
        """INSERT INTO $table (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, $column, epoch, install_tag,
                trusted, last_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, epoch, $column, install_tag)
            DO UPDATE SET last_day = GREATEST($table.last_day, EXCLUDED.last_day)""",
        *scope.args(), slot, value, tag.epoch, tag.tag, trusted, today,
    ).coerceAtMost(1)

    /**
     * Spends the read quota and writes the audit row for one read, in its own transaction, before the read's snapshot
     * (a quota update inside a REPEATABLE READ snapshot would be refused whenever the same ledger row moved).
     */
    private suspend fun admitRead(
        installId: UUID, keyHash: String, today: LocalDate, kind: String, scope: SlotScope, items: Int, column: String, limit: Int,
    ): ContributorOutcome<Unit> = query {
        activeInstall(installId, keyHash, lock = true) ?: return@query ContributorOutcome.Stale
        val grant = liveGrant(installId, today, lock = true) ?: return@query ContributorOutcome.NoGrant
        if (!consume(installId, today, column, items, limit)) return@query exhausted()
        update(
            "INSERT INTO contributor_reads (grant_id, day, kind, fingerprint, layout, items) VALUES (?, ?, ?, ?, ?, ?)",
            grant, today, kind, scope.fingerprint, scope.variant.layout, items,
        )
        ContributorOutcome.Ok(Unit)
    }

    /**
     * The slot classes of one cluster variant a contributor may read (#81): from today's release only, with the
     * reader's own rows left out ([SlotRelease]); classes only, never a hash or a count, and nothing below k (an
     * `unknown` slot is omitted). Sorted by slot id.
     */
    suspend fun classes(installId: UUID, keyHash: String, today: LocalDate, scope: SlotScope): ContributorOutcome<List<Pair<String, SlotClass>>> {
        val admitted = admitRead(installId, keyHash, today, "classes", scope, 1, "contributor_reads", limits.dailyReads)
        admitted.refusal()?.let { return it }
        return snapshot {
            activeInstall(installId, keyHash, lock = false) ?: return@snapshot ContributorOutcome.Stale
            liveGrant(installId, today, lock = false) ?: return@snapshot ContributorOutcome.NoGrant
            if (!releaseDay(today)) return@snapshot ContributorOutcome.ReleaseUnavailable
            readSeam?.invoke(this)
            val keys = liveKeysByEpoch(db.keying, today)
            val rows = releasedRows(today, scope)
            // Sol S3: a reader is answered only about a variant it has itself uploaded (a live sighting of one of its
            // slots); otherwise it reads nothing, as if the variant had no evidence.
            if (!sightedAny(keys.values, installId, scope, rows.map { it.slot }.toSet(), today, policy.retention.clusterSightingsDays)) {
                return@snapshot ContributorOutcome.Ok(emptyList())
            }
            val evidence = leaveOneOut(rows, scope, installId, keys)
            ContributorOutcome.Ok(evidence.mapNotNull { (slot, counts) ->
                policy.classify(counts).takeIf { it != SlotClass.UNKNOWN }?.let { slot to it }
            }.sortedBy { it.first })
        }
    }

    /** For each (slot, public hash), whether the gate is open for this reader ([ContributorGate.gateOpen]). */
    suspend fun gates(
        installId: UUID, keyHash: String, today: LocalDate, scope: SlotScope, items: List<Pair<String, String>>,
    ): ContributorOutcome<List<Boolean>> {
        val admitted = admitRead(installId, keyHash, today, "gates", scope, items.size, "contributor_reads", limits.dailyReads)
        admitted.refusal()?.let { return it }
        return snapshot {
            activeInstall(installId, keyHash, lock = false) ?: return@snapshot ContributorOutcome.Stale
            liveGrant(installId, today, lock = false) ?: return@snapshot ContributorOutcome.NoGrant
            readSeam?.invoke(this)
            val keys = liveKeysByEpoch(db.keying, today)
            ContributorOutcome.Ok(items.map { (slot, publicHash) ->
                gateOpen(policy, keys, installId, today, scope, slot, db.keying.token(publicHash))
            })
        }
    }

    /**
     * Plaintext words (#81): each must look like app wording, hash (public census hash, then keyed) to the hash it is
     * sent with, and find its gate open for this reader; then it waits in `contributor_words` for the operator, who
     * approves it into the working vocabulary through the vocabulary resolve route. Never auto-approved, never shipped
     * from here. A refused word is neither stored nor logged.
     */
    suspend fun words(installId: UUID, keyHash: String, today: LocalDate, scope: SlotScope, items: List<WordItem>): ContributorOutcome<List<WordStatus>> {
        val admitted = admitRead(installId, keyHash, today, "words", scope, items.size, "contributor_words", limits.dailyWords)
        admitted.refusal()?.let { return it }
        val checked = items.map { ContributorGate.checkWord(it.word, it.publicHash) }
        return snapshot(readOnly = false) {
            // As in the quarantine (Sol S4): install first, then the grant, both share-locked in the writing snapshot.
            activeInstall(installId, keyHash, lock = true) ?: return@snapshot ContributorOutcome.Stale
            liveGrant(installId, today, lock = true) ?: return@snapshot ContributorOutcome.NoGrant
            val keys = liveKeysByEpoch(db.keying, today)
            ContributorOutcome.Ok(items.zip(checked).map { (item, check) ->
                val (canonical, refused) = check
                if (refused != null) return@map refused
                val value = db.keying.token(item.publicHash)
                if (!gateOpen(policy, keys, installId, today, scope, item.slot, value)) return@map WordStatus.GATE_CLOSED
                update(
                    """INSERT INTO contributor_words (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value,
                            clear_text, received_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                    *scope.args(), item.slot, value, canonical, today,
                )
                WordStatus.ACCEPTED
            })
        }
    }

    /** Test seam: runs inside a read's snapshot after its install and grant checks, before its evidence is read. */
    internal var readSeam: ((Connection) -> Unit)? = null

    private fun exhausted() = ContributorOutcome.BudgetExhausted(secondsToUtcMidnight(clock.now()))

    /** The operator's list of grants (#81): ids and days, and today's read items from the audit log. */
    suspend fun grants(limit: Int = 50): JsonArray = query {
        val today = clock.today()
        prepareStatement(
            """SELECT g.grant_id, g.granted_day, g.expires_day, g.revoked_day,
                    (SELECT COALESCE(sum(r.items), 0) FROM contributor_reads r WHERE r.grant_id = g.grant_id AND r.day = ?) AS reads
                FROM contributor_grants g ORDER BY g.granted_day DESC, g.grant_id LIMIT ?""",
        ).use { statement ->
            statement.setObject(1, today)
            statement.setInt(2, limit)
            statement.executeQuery().use { rows ->
                JsonArray(buildList {
                    while (rows.next()) add(buildJsonObject {
                        put("grantId", rows.getString(1)); put("grantedDay", rows.getString(2)); put("expiresDay", rows.getString(3))
                        rows.getString(4)?.let { put("revokedDay", it) }
                        put("live", rows.getString(4) == null && rows.getObject(3, LocalDate::class.java) > today)
                        put("readsToday", rows.getLong(5))
                    })
                })
            }
        }
    }

    /**
     * Revokes or clears a grant (#81). Revoking marks the grant and every live grant of its install revoked (a revoked
     * grant refuses a new request) and deletes the install's confirmations and role tags, found through its epoch
     * tags; the install row is locked first, the order withdrawal uses. Clearing a revoked grant deletes the install's
     * revoked grants, so it may request again. False when no such grant exists (or, clearing, it is not revoked).
     */
    suspend fun setRevoked(grantId: UUID, revoked: Boolean): Boolean = query {
        val installId = select("SELECT install_id FROM contributor_grants WHERE grant_id = ?", grantId) {
            it.getObject(1, UUID::class.java)
        } ?: return@query false
        select("SELECT 1 FROM installs WHERE install_id = ? FOR UPDATE", installId) { true }
        if (!revoked) {
            if (select("SELECT 1 FROM contributor_grants WHERE grant_id = ? AND revoked_day IS NOT NULL", grantId) { true } == null) return@query false
            return@query update("DELETE FROM contributor_grants WHERE install_id = ? AND revoked_day IS NOT NULL", installId) > 0
        }
        val today = clock.today()
        update(
            "UPDATE contributor_grants SET revoked_day = ? WHERE revoked_day IS NULL AND (grant_id = ? OR (install_id = ? AND expires_day > ?))",
            today, grantId, installId, today,
        )
        val keys = with(EpochKeyStore) { storedKeys(db.keying) }
        TaggedTable.CONFIRMATIONS.erase(this, keys, installId)
        TaggedTable.ROLES.erase(this, keys, installId)
        true
    }

    /** Words waiting for the operator (#81), oldest first. */
    suspend fun pendingWords(limit: Int = 50): List<PendingWord> = query {
        prepareStatement(
            """SELECT fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value, clear_text, received_day
                FROM contributor_words ORDER BY received_day, fingerprint, slot, value LIMIT ?""",
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(PendingWord(rows.getString(1), SlotVariant(rows.getString(2), rows.getInt(3), rows.getInt(4),
                        rows.getString(5)), rows.getString(6), rows.getString(7), rows.getString(8), rows.getObject(9, LocalDate::class.java)))
                }
            }
        }
    }

    companion object {
        const val GRANTED: String = "granted"
        const val REVOKED: String = "revoked"
        const val EXPIRED: String = "expired"
        const val NONE: String = "none"
        private const val SERIALIZATION_ATTEMPTS = 3
    }
}

/** A gated word waiting for the operator (#81). [value] is the keyed hash the vocabulary resolve route takes. */
data class PendingWord(
    val fingerprint: String, val variant: SlotVariant, val slot: String, val value: String, val clearText: String, val receivedDay: LocalDate,
)

/** A REPEATABLE READ refusal anywhere in the cause chain (SQLSTATE 40001). */
internal fun Throwable.isSerializationFailure(): Boolean =
    generateSequence(this) { it.cause }.any { (it as? SQLException)?.sqlState == "40001" }
