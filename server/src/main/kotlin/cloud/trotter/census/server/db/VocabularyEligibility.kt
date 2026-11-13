package cloud.trotter.census.server.db

import cloud.trotter.census.server.Policy
import java.time.LocalDate

/**
 * SQL parameters are supplied by the caller's clock, never the database session time zone.
 *
 * #86: k is counted within one epoch, never across two. Each (partition, token) is counted per epoch as the distinct
 * epoch tags of non-trusted, out-of-quarantine sightings, and its evidence is the largest of those per-epoch counts:
 * an install seen in two epochs carries two unrelated tags, so adding epochs together could count it twice.
 */
object VocabularyEligibility {
    fun evidence(policy: Policy): String = """SELECT hash_domain, filter_rev, token_hash, min(kind) AS kind,
        max(installs) AS installs, min(first_day) AS first_day, max(last_day) AS last_day FROM (
            SELECT t.hash_domain, t.filter_rev, t.token_hash, t.epoch, min(t.kind) AS kind,
            count(DISTINCT t.install_tag) AS installs, min(t.first_day) AS first_day, max(t.last_day) AS last_day
            FROM token_sightings_v5 t
            WHERE NOT t.trusted AND (t.counts_from IS NULL OR t.counts_from <= ?)
            AND t.last_day + 30 > ? AND t.hash_domain IN (${policy.acceptedHashDomains.joinToString(",")})
            AND t.filter_rev >= ${policy.minimumFilterRev}
            GROUP BY t.hash_domain, t.filter_rev, t.token_hash, t.epoch
        ) per_epoch GROUP BY hash_domain, filter_rev, token_hash"""

    fun queue(policy: Policy): String = """SELECT q.* FROM (${evidence(policy)}) q
        WHERE q.installs >= ${policy.k} AND NOT EXISTS (SELECT 1 FROM vocabulary_v5 v
        WHERE v.hash_domain=q.hash_domain AND v.filter_rev=q.filter_rev AND v.token_hash=q.token_hash)"""

    fun args(today: LocalDate): Array<Any> = arrayOf(today, today)

    /**
     * Quarantine in whole UTC days (#79: no enrolment instant is stored). The enrolment day itself does not count
     * (Sol S6): an install enrolled at 23:59 on day D qualifies on D+8, so at least seven full days always elapse.
     *
     * #86: content rows no longer join the installs table, so ingest stamps a sighting with the day its install's
     * evidence starts to count, only while that day is still ahead of [today] (the receipt day); null means it counts.
     * The lifecycle sweep clears passed marks, so a mark (shared by one install's rows) lives at most 8 days.
     */
    fun countsFrom(createdDay: LocalDate, today: LocalDate): LocalDate? =
        createdDay.plusDays(QUARANTINE_DAYS).takeIf { it > today }

    private const val QUARANTINE_DAYS = 8L
}
