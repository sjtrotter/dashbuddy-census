package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.server.AnchorCheckPolicy
import cloud.trotter.census.server.ChromeShape
import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Database
import cloud.trotter.census.server.HashKeying
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.SlotClass
import cloud.trotter.census.server.db.SlotRelease.classWithValue
import cloud.trotter.census.server.db.SlotRelease.liveRows
import cloud.trotter.census.server.secondsToUtcMidnight
import cloud.trotter.census.server.today
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.LocalDate

/**
 * Who asks an anchor check (#82): a fixed caller name, recorded on every audit row, and the caller's own daily
 * candidate budget. The operator is [operator]; the agent token (#84) builds its own caller (an `agent:` name and its
 * budget) and lands in the same budget table and audit log.
 */
data class AnchorCaller(val id: String, val dailyCandidates: Int) {
    init {
        require(PATTERN.matches(id) && dailyCandidates > 0)
    }

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9:_-]{0,63}")

        fun operator(policy: AnchorCheckPolicy): AnchorCaller = AnchorCaller("operator", policy.operatorDailyCandidates)
    }
}

/** The answer class one audit row records (#82). */
enum class AnchorResult(val wire: String) {
    MATCH("match"),
    NO_ANSWER("no_answer"),
    REFUSED("refused"),
    BUDGET_EXHAUSTED("budget_exhausted"),
}

/** How an anchor check ends (#82). */
sealed interface AnchorOutcome {
    /** Per candidate, in request order: the case variant the slot holds, or null for "no answer". */
    data class Answered(val matches: List<String?>) : AnchorOutcome

    /** The indexes of the candidates the chrome filter refused. Nothing was hashed and no slot was read. */
    data class Refused(val indexes: List<Int>) : AnchorOutcome

    /** The caller's candidates for today are spent. Nothing was hashed and no slot was read. */
    data class BudgetExhausted(val retryAfterSeconds: Long) : AnchorOutcome
}

/** One audit row as the operator reads it (#82). [words] is absent for a refused or over-budget query. */
@Serializable
data class AnchorAuditRow(
    val caller: String, val day: String, val fingerprint: String, val variant: SlotVariant, val slot: String,
    val candidates: Int, val result: String, val words: List<String>? = null,
)

/**
 * The anchor check (#82, ADR-0011 §16): whether a candidate word is what a constant slot holds. It hashes the
 * candidate, keys it with the pepper and compares, which is a dictionary lookup, so it answers only inside these
 * bounds, in this order, in one transaction:
 *
 * 1. the chrome filter ([ChromeShape.anchorCandidate]) runs on every candidate before anything else, in memory;
 * 2. the caller's daily budget is spent by the query's candidate count, refused ones included; over budget, the query
 *    stops there;
 * 3. a query with any refused candidate stops there, before any hash or slot read;
 * 4. the slot's live rows are read and classified (operator side: every counted row, nobody left out). Only a
 *    `constant` slot, which by [cloud.trotter.census.server.SlotConstancy.classify] means one hash on k installs or
 *    more, yields a value; `unknown`, `varying`, `regional`, `withheld` and `shape` yield none;
 * 5. every case variant of every candidate is hashed (public census hash), keyed and compared in constant time, against
 *    the constant value or, when there is none, a placeholder whose result is discarded ([anchorMatches]);
 * 6. one audit row records the query.
 *
 * So below-k, regional, varying, withheld and shape slots answer exactly as a constant slot that holds none of the
 * candidates does ("no answer"), after the same statements and the same hashing. The audit row and the log carry the
 * answer class, never which class the slot had.
 */
class AnchorCheckStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        transaction(db.exposed) {
            maxAttempts = 1
            val raw = connection.connection as Connection
            (statementSeam?.let { raw.listening(it) } ?: raw).block()
        }
    }

    /** One query: [candidates] (1 to [AnchorCheckPolicy.maxCandidates]) against [slot] of [scope], for [caller]. */
    suspend fun check(caller: AnchorCaller, scope: SlotScope, slot: String, candidates: List<String>): AnchorOutcome {
        require(candidates.size in 1..policy.anchorCheck.maxCandidates)
        val admitted = candidates.map(ChromeShape::anchorCandidate)
        val today = clock.today()
        return query {
            if (!spend(caller, today, candidates.size)) {
                audit(caller, today, scope, slot, candidates.size, AnchorResult.BUDGET_EXHAUSTED, null)
                return@query AnchorOutcome.BudgetExhausted(secondsToUtcMidnight(clock.now()))
            }
            val refused = admitted.indices.filter { admitted[it] == null }
            if (refused.isNotEmpty()) {
                audit(caller, today, scope, slot, candidates.size, AnchorResult.REFUSED, null)
                return@query AnchorOutcome.Refused(refused)
            }
            val words = admitted.requireNoNulls()
            val matches = anchorMatches(db.keying, constantValue(today, scope, slot), words)
            audit(caller, today, scope, slot, candidates.size, if (matches.any { it != null }) AnchorResult.MATCH else AnchorResult.NO_ANSWER, words)
            AnchorOutcome.Answered(matches)
        }
    }

    /** The newest audit rows first (#82). */
    suspend fun auditLog(limit: Int): List<AnchorAuditRow> = query {
        prepareStatement(
            """SELECT caller, day, fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, candidates, result, words
                FROM anchor_checks ORDER BY id DESC LIMIT ?""",
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(AnchorAuditRow(
                        rows.getString(1), rows.getString(2), rows.getString(3),
                        SlotVariant(rows.getString(4), rows.getInt(5), rows.getInt(6), rows.getString(7)), rows.getString(8),
                        rows.getInt(9), rows.getString(10),
                        rows.getArray(11)?.let { array -> try { (array.array as Array<*>).map { it as String } } finally { array.free() } },
                    ))
                }
            }
        }
    }

    /** Spends [amount] of [caller]'s budget for [today]; false, spending nothing, when the day's limit would pass. */
    private fun Connection.spend(caller: AnchorCaller, today: LocalDate, amount: Int): Boolean {
        update("INSERT INTO anchor_check_budget (caller, day) VALUES (?, ?) ON CONFLICT DO NOTHING", caller.id, today)
        return update(
            "UPDATE anchor_check_budget SET candidates = candidates + ? WHERE caller = ? AND day = ? AND candidates + ? <= ?",
            amount, caller.id, today, amount, caller.dailyCandidates,
        ) == 1
    }

    /**
     * The keyed value [slot] is constant on, judged on its live counted rows ([liveRows], the operator's classifier),
     * or null for any other class. Every class runs this same statement.
     */
    private fun Connection.constantValue(today: LocalDate, scope: SlotScope, slot: String): String? {
        val (slotClass, value) = policy.classWithValue(SlotRelease.counts(liveRows(policy, today, scope, slot))[slot])
        return value.takeIf { slotClass == SlotClass.CONSTANT }
    }

    private fun Connection.audit(
        caller: AnchorCaller, today: LocalDate, scope: SlotScope, slot: String, candidates: Int, result: AnchorResult, words: List<String>?,
    ) {
        val array = words?.let { createArrayOf("text", it.toTypedArray()) }
        try {
            update(
                """INSERT INTO anchor_checks (caller, day, fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot,
                        candidates, result, words) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                caller.id, today, *scope.args(), slot, candidates, result.wire, array,
            )
        } finally {
            array?.free()
        }
    }

    /** Test seam: receives the SQL of every statement this store prepares, so a test can compare query sequences. */
    internal var statementSeam: ((String) -> Unit)? = null
}

/** Compared against when a slot has no constant value; its result is discarded ([anchorMatches]). */
private const val NO_VALUE: String = "0000000000000000"

/**
 * For each word (an admitted canonical candidate), the case variant whose keyed census hash equals [expected], or null
 * (#82). Each variant is hashed the phone's way ([CensusHash.of]), keyed with the pepper ([HashKeying.token]) and
 * compared in constant time ([HashKeying.same]). The same work runs when [expected] is null (the slot does not answer):
 * the variants are compared against [NO_VALUE] and nothing matches.
 */
internal fun anchorMatches(keying: HashKeying, expected: String?, words: List<String>): List<String?> {
    val target = expected ?: NO_VALUE
    return words.map { word ->
        var matched: String? = null
        for (variant in ChromeShape.caseVariants(word)) {
            val keyed = CensusHash.of(variant)?.let(keying::token)
            val equal = keying.same(keyed ?: NO_VALUE, target)
            if (equal && keyed != null && expected != null && matched == null) matched = variant
        }
        matched
    }
}
