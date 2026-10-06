package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.StoredSampleExpiry
import cloud.trotter.census.server.jobs.SweepResult
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.time.LocalDate

/** Each batch commits independently. No sweep retains a lock across suspension or another sweep. */
class LifecycleStore(private val db: Database, private val clock: Clock, private val policy: Policy) {
    internal suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        ensureActive()
        transaction(db.exposed) { maxAttempts = 1; (connection.connection as Connection).block() }
    }

    private suspend fun sweep(count: suspend () -> Long, batch: suspend () -> Pair<Int, Int>): SweepResult {
        var deleted = 0L
        var rewritten = 0L
        var batches = 0
        return try {
            do {
                currentCoroutineContext().ensureActive()
                val changed = batch()
                deleted += changed.first; rewritten += changed.second; batches++
                if (changed.first + changed.second == 0) break
            } while (batches < 50)
            val due = count()
            SweepResult(deleted, rewritten, batches, batches == 50 && due > 0, remainingDue = due)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            SweepResult(deleted, rewritten, batches, failed = true)
        }
    }

    private suspend fun delete(table: String, predicate: String, vararg args: Any?): SweepResult = sweep(
        { query { select("SELECT count(*) FROM $table WHERE $predicate", *args) { it.getLong(1) } ?: 0 } },
        { query { update("DELETE FROM $table WHERE ($predicate) AND ctid IN (SELECT ctid FROM $table WHERE $predicate LIMIT 1000)", *args, *args) to 0 } },
    )

    suspend fun runOnce(): Map<String, SweepResult> {
        val today = clock.today()
        val result = linkedMapOf<String, SweepResult>()
        val installs = InstallStore(db, clock)
        result["inactive_installs"] = sweep(
            { query { select("SELECT count(*) FROM installs WHERE last_seen_day + 365 <= ?", today) { it.getLong(1) } ?: 0 } },
            { installs.purgeInactive(today) to 0 },
        )
        result["tokens"] = delete("token_sightings_v5", "last_day + 30 <= ?", today)
        result["sightings"] = delete("cluster_sightings_v5", "day + 90 <= ?", today)
        for (kind in listOf("screen", "notification")) result["samples_$kind"] = samples(kind, today)
        result["vocabulary"] = vocabulary(today)
        result["catalogue"] = catalogue()
        return result
    }

    private fun sampleDue(): String = """(s.purge_after IS NULL OR s.purge_after <= ? OR EXISTS (
        SELECT 1 FROM jsonb_path_query(s.skeleton, '$.**.h') h
        WHERE jsonb_typeof(h) = 'string' AND NOT EXISTS (SELECT 1 FROM token_sightings_v5 t
            WHERE t.hash_domain=s.hash_domain AND t.filter_rev=s.filter_rev AND t.token_hash=h #>> '{}'
            AND t.last_day + 30 > ?)))"""

    private suspend fun samples(kind: String, today: LocalDate): SweepResult = sweep(
        { query { select("SELECT count(*) FROM cluster_samples s JOIN clusters c USING(fingerprint) WHERE c.kind=? AND ${sampleDue()}", kind, today, today) { it.getLong(1) } ?: 0 } },
        { query {
            val fingerprints = select("""SELECT c.fingerprint FROM clusters c WHERE c.kind=? AND EXISTS
                (SELECT 1 FROM cluster_samples s WHERE s.fingerprint=c.fingerprint AND ${sampleDue()})
                ORDER BY c.fingerprint LIMIT 100 FOR UPDATE OF c""", kind, today, today) { rows ->
                buildList { do { add(rows.getString(1)) } while (rows.next()) }
            } ?: emptyList()
            var deleted = 0; var rewritten = 0
            for (fp in fingerprints) {
                if (deleted + rewritten >= 1000) break
                // A fresh statement after obtaining the ingest lock rechecks age and reads current JSON.
                val due = select("SELECT s.* FROM cluster_samples s WHERE fingerprint=? AND ${sampleDue()} LIMIT ?", fp, today, today, 1000 - deleted - rewritten) { rows ->
                    buildList { do { add(Sample(rows.getString("platform_app_version"), rows.getObject("received_day", LocalDate::class.java),
                        rows.getString("skeleton"), rows.getInt("hash_domain"), rows.getInt("filter_rev"), rows.getObject("purge_after", LocalDate::class.java))) } while (rows.next()) }
                } ?: emptyList()
                for (sample in due) {
                    if (sample.deadline == null || sample.deadline <= today) {
                        deleted += update("DELETE FROM cluster_samples WHERE fingerprint=? AND platform_app_version=? AND received_day=?", fp, sample.version, sample.received)
                        update("UPDATE clusters SET notes=NULL WHERE fingerprint=?", fp)
                    } else {
                        val live = liveHashes(sample.domain, sample.revision, today, sample.body)
                        rewritten += update("UPDATE cluster_samples SET skeleton=?::jsonb WHERE fingerprint=? AND platform_app_version=? AND received_day=?",
                            StoredSampleExpiry.rewrite(sample.body, live), fp, sample.version, sample.received)
                    }
                }
            }
            deleted to rewritten
        } },
    )

    private data class Sample(val version: String, val received: LocalDate, val body: String, val domain: Int, val revision: Int, val deadline: LocalDate?)

    private fun vocabularyPredicate(): String = """NOT EXISTS (SELECT 1 FROM token_sightings_v5 t WHERE t.token_hash=vocabulary_v5.token_hash
        AND t.hash_domain=vocabulary_v5.hash_domain AND t.filter_rev=vocabulary_v5.filter_rev AND t.last_day+30 > ?)
        OR (status IN ('queued','unblinded') AND NOT EXISTS (SELECT 1 FROM (${VocabularyEligibility.evidence(policy)}) e
        WHERE e.token_hash=vocabulary_v5.token_hash AND e.hash_domain=vocabulary_v5.hash_domain
        AND e.filter_rev=vocabulary_v5.filter_rev AND e.installs >= ${policy.k}))"""

    private suspend fun vocabulary(today: LocalDate): SweepResult = delete("vocabulary_v5", vocabularyPredicate(),
        today, *VocabularyEligibility.args(clock.now(), today))

    private val cataloguePredicate = """NOT EXISTS (SELECT 1 FROM cluster_samples s WHERE s.fingerprint=clusters.fingerprint)
        AND NOT EXISTS (SELECT 1 FROM cluster_sightings_v5 s WHERE s.fingerprint=clusters.fingerprint)
        AND NOT EXISTS (SELECT 1 FROM trusted_envelopes e WHERE e.fingerprint=clusters.fingerprint)"""

    /** Fresh counts for ops even when the sweeper is paused or a deadline just elapsed. */
    suspend fun remainingDue(): Map<String, Long> {
        val today=clock.today()
        return query {
            buildMap {
                put("inactive_installs", select("SELECT count(*) FROM installs WHERE last_seen_day+365 <= ?",today) { it.getLong(1) }!!)
                put("tokens", select("SELECT count(*) FROM token_sightings_v5 WHERE last_day+30 <= ?",today) { it.getLong(1) }!!)
                put("sightings", select("SELECT count(*) FROM cluster_sightings_v5 WHERE day+90 <= ?",today) { it.getLong(1) }!!)
                for (kind in listOf("screen","notification")) put("samples_$kind",
                    select("SELECT count(*) FROM cluster_samples s JOIN clusters c USING(fingerprint) WHERE c.kind=? AND ${sampleDue()}",kind,today,today) { it.getLong(1) }!!)
                put("vocabulary", select("SELECT count(*) FROM vocabulary_v5 WHERE ${vocabularyPredicate()}",today,*VocabularyEligibility.args(clock.now(),today)) { it.getLong(1) }!!)
                put("catalogue", select("SELECT count(*) FROM clusters WHERE $cataloguePredicate") { it.getLong(1) }!!)
            }
        } + InstallStore(db,clock).remainingDue(today,clock.now())
    }

    private suspend fun catalogue(): SweepResult {
        val predicate = cataloguePredicate
        return sweep(
            { query { select("SELECT count(*) FROM clusters WHERE $predicate") { it.getLong(1) } ?: 0 } },
            { query {
                val ids = select("SELECT fingerprint FROM clusters WHERE $predicate ORDER BY fingerprint LIMIT 1000 FOR UPDATE") { rows ->
                    buildList { do { add(rows.getString(1)) } while (rows.next()) }
                } ?: emptyList()
                ids.sumOf { update("DELETE FROM clusters WHERE fingerprint=? AND $predicate", it) } to 0
            } },
        )
    }
}

internal fun Connection.liveHashes(domain: Int, revision: Int, today: LocalDate, body: String): Set<String> = select(
    "SELECT DISTINCT token_hash FROM token_sightings_v5 WHERE hash_domain=? AND filter_rev=? AND last_day + 30 > ? AND token_hash IN (SELECT h #>> '{}' FROM jsonb_path_query(?::jsonb, '$.**.h') h)", domain, revision, today, body,
) { rows -> buildSet { do { add(rows.getString(1)) } while (rows.next()) } } ?: emptySet()
