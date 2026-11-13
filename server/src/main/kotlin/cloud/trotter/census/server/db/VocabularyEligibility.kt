package cloud.trotter.census.server.db

import cloud.trotter.census.server.Policy
import java.time.LocalDate

/** SQL parameters are supplied by the caller's clock, never the database session time zone. */
object VocabularyEligibility {
    fun evidence(policy: Policy): String = """SELECT t.hash_domain, t.filter_rev, t.token_hash,
        min(t.kind) AS kind, count(DISTINCT t.install_id) AS installs,
        min(t.first_day) AS first_day, max(t.last_day) AS last_day
        FROM token_sightings_v5 t JOIN installs i ON i.install_id = t.install_id
        WHERE NOT i.trusted AND i.revoked_at IS NULL AND i.created_day <= ?
        AND t.last_day + 30 > ? AND t.hash_domain IN (${policy.acceptedHashDomains.joinToString(",")})
        AND t.filter_rev >= ${policy.minimumFilterRev}
        GROUP BY t.hash_domain, t.filter_rev, t.token_hash"""

    fun queue(policy: Policy): String = """SELECT q.* FROM (${evidence(policy)}) q
        WHERE q.installs >= ${policy.k} AND NOT EXISTS (SELECT 1 FROM vocabulary_v5 v
        WHERE v.hash_domain=q.hash_domain AND v.filter_rev=q.filter_rev AND v.token_hash=q.token_hash)"""

    /** Quarantine in whole UTC days since the enrolment day (#79: no enrolment instant is stored). */
    fun args(today: LocalDate): Array<Any> = arrayOf(today.minusDays(7), today)
}
