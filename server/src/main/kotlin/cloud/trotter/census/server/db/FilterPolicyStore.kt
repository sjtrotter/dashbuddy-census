package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.StoredSampleExpiry
import cloud.trotter.census.server.jobs.SweepResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Reader
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.sql.Connection
import java.time.LocalDate

/** A policy-only recovery journal. Parse the entire document before applying any change. */
data class FilterFloorJournal(val minimumFilterRev: Int, val changedDay: LocalDate) {
    init { require(minimumFilterRev > 0) { "Invalid filter floor journal" } }
    fun encode(): String = "min_filter_rev,changed_day\n$minimumFilterRev,$changedDay\n"

    fun export(path: Path) {
        val target = path.toAbsolutePath()
        FileChannel.open(target.resolveSibling(".${target.fileName}.lock"), CREATE, WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val previous = if (Files.exists(target)) Files.newBufferedReader(target).use(::read) else null
                val merged = if (previous != null && previous.minimumFilterRev >= minimumFilterRev) previous else this
                val temporary = Files.createTempFile(target.parent, ".filter-floor-", ".tmp")
                try {
                    FileChannel.open(temporary, WRITE).use { channel ->
                        val bytes = java.nio.ByteBuffer.wrap(merged.encode().toByteArray(Charsets.UTF_8))
                        while (bytes.hasRemaining()) channel.write(bytes)
                        channel.force(true)
                    }
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    FileChannel.open(target.parent, READ).use { it.force(true) }
                } finally { Files.deleteIfExists(temporary) }
            }
        }
    }

    companion object {
        fun read(reader: Reader): FilterFloorJournal {
            val buffer = CharArray(129)
            var length = 0
            while (length < buffer.size) {
                val n = reader.read(buffer, length, buffer.size - length)
                if (n < 0) break
                length += n
            }
            val text = String(buffer, 0, length)
            require(text.length <= 128) { "Invalid filter floor journal" }
            val match = Regex("min_filter_rev,changed_day\\n([1-9][0-9]*),([0-9]{4}-[0-9]{2}-[0-9]{2})\\n?").matchEntire(text)
                ?: throw IllegalArgumentException("Invalid filter floor journal")
            return try {
                FilterFloorJournal(match.groupValues[1].toInt(), LocalDate.parse(match.groupValues[2]))
            } catch (_: Exception) { throw IllegalArgumentException("Invalid filter floor journal") }
        }
    }
}

/** Called with HTTP stopped. The durable floor commits BEFORE deletion, so interrupted purges replay. */
class FilterPolicyStore(private val db: Database, private val clock: Clock) {
    private suspend fun <T> query(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        ensureActive()
        transaction(db.exposed) { maxAttempts = 1; (connection.connection as Connection).block() }
    }
    var results: Map<String, SweepResult> = emptyMap()
        private set

    suspend fun current(): FilterFloorJournal? = query {
        select("SELECT min_filter_rev, changed_day FROM filter_floor WHERE singleton") {
            FilterFloorJournal(it.getInt(1), it.getObject(2, LocalDate::class.java))
        }
    }

    suspend fun bootstrap(configured: Int, imported: FilterFloorJournal? = null): Int {
        require(configured > 0)
        require(imported == null || imported.changedDay <= clock.today()) { "Invalid filter floor journal" }
        query {
            val migration = requireNotNull(FilterPolicyStore::class.java.getResourceAsStream("/db/migration/V5__skeleton_lifecycle.sql"))
                .bufferedReader().use { it.readText() }
            createStatement().use { it.execute(migration.substring(migration.indexOf("DO \$\$"))) }
        }
        val first = current() == null
        val counts = linkedMapOf<String, SweepResult>()
        var legacyCaptures=0L
        do {
            currentCoroutineContext().ensureActive()
            val n=query { with(InstallStore(db,clock)) {
                deleteEnvelopesAndClearDrafts("""DELETE FROM trusted_envelopes WHERE id IN
                    (SELECT id FROM trusted_envelopes WHERE fingerprint IS NOT NULL AND (? OR fingerprint IN
                    (SELECT fingerprint FROM cluster_samples WHERE hash_domain IS NULL OR filter_rev IS NULL OR purge_after IS NULL)) LIMIT 1000) RETURNING id""", first)
            } }
            legacyCaptures+=n
        } while(n==1000)
        counts["legacy_captures"]=SweepResult(deleted=legacyCaptures,remainingDue=0)
        for (table in listOf("token_sightings", "cluster_sightings", "vocabulary")) {
            if (!first) check(query { select("SELECT count(*) FROM $table") { it.getLong(1) } == 0L }) { "Unexpected legacy writes" }
            var deleted = 0L; var batches = 0
            if (first) do {
                currentCoroutineContext().ensureActive()
                val changed = query { update("DELETE FROM $table WHERE ctid IN (SELECT ctid FROM $table LIMIT 1000)") }
                deleted += changed; batches++
            } while (changed == 1000)
            counts["legacy_$table"] = SweepResult(deleted, batches = batches, remainingDue = 0)
        }
        // Null provenance marks legacy copies. Canonical metadata is the only valid source for attribution.
        var removed = 0L; var rewritten = 0L; var batches = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val changed = query {
                val rows = select("""SELECT s.*, c.status, c.last_seen_day, c.kind FROM cluster_samples s JOIN clusters c USING(fingerprint)
                    WHERE s.hash_domain IS NULL OR s.filter_rev IS NULL OR s.purge_after IS NULL LIMIT 1000""") { rows ->
                    buildList { do { add(LegacySample(rows.getString("fingerprint"), rows.getString("platform_app_version"),
                        rows.getObject("received_day", LocalDate::class.java), rows.getString("skeleton"),
                        rows.getString("status"), rows.getObject("last_seen_day", LocalDate::class.java), rows.getString("kind"))) } while (rows.next()) }
                } ?: emptyList()
                var deleted = 0; var scrubbed = 0
                for (sample in rows) {
                    update("UPDATE clusters SET notes=NULL, draft=NULL, draft_day=NULL WHERE fingerprint=?", sample.fp)
                    val body = runCatching { Json.parseToJsonElement(sample.body).jsonObject }.getOrNull()
                    val domain = (body?.get("hashDomain") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    val rev = (body?.get("filterRev") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    val valid = domain != null && domain > 0 && rev != null && rev > 0 &&
                        (body["fingerprint"] as? JsonPrimitive)?.takeIf { it.isString }?.content == sample.fp &&
                        runCatching {
                            val dto=cloud.trotter.census.contract.CensusSkeletonSchema.deserialize(sample.body)
                            dto.kind.wire == sample.kind && cloud.trotter.census.contract.CensusFingerprint.of(dto) == sample.fp
                        }.getOrDefault(false)
                    if (!valid || sample.status == "resolved" || sample.last.plusDays(90) <= clock.today()) {
                        deleted += update("DELETE FROM cluster_samples WHERE fingerprint=? AND platform_app_version=? AND received_day=?", sample.fp, sample.version, sample.received)
                    } else {
                        scrubbed += update("""UPDATE cluster_samples SET hash_domain=?, filter_rev=?, purge_after=?, skeleton=?::jsonb
                            WHERE fingerprint=? AND platform_app_version=? AND received_day=?""", domain, rev, sample.last.plusDays(90),
                            StoredSampleExpiry.rewrite(sample.body, emptySet()), sample.fp, sample.version, sample.received)
                    }
                }
                deleted to scrubbed
            }
            removed += changed.first; rewritten += changed.second; batches++
            if (changed.first + changed.second == 0) break
        }
        counts["legacy_samples"] = SweepResult(removed, rewritten, batches, remainingDue = 0)
        if (first) {
            do {
                currentCoroutineContext().ensureActive()
                val n=query { update("""UPDATE clusters SET resolved_day=? WHERE fingerprint IN
                    (SELECT fingerprint FROM clusters WHERE status='resolved' AND resolved_day IS NULL LIMIT 1000)""",clock.today().minusDays(30)) }
            } while(n==1000)
            do {
                currentCoroutineContext().ensureActive()
                val n=query { update("""UPDATE clusters SET notes=NULL,draft=NULL,draft_day=NULL WHERE fingerprint IN
                    (SELECT fingerprint FROM clusters WHERE notes IS NOT NULL OR draft IS NOT NULL OR draft_day IS NOT NULL LIMIT 1000)""") }
            } while(n==1000)
        }

        query {
            update("""INSERT INTO filter_floor(singleton,min_filter_rev,changed_day) VALUES(true,?,?)
                ON CONFLICT(singleton) DO UPDATE SET min_filter_rev=GREATEST(filter_floor.min_filter_rev,EXCLUDED.min_filter_rev),
                changed_day=CASE WHEN EXCLUDED.min_filter_rev > filter_floor.min_filter_rev THEN EXCLUDED.changed_day ELSE filter_floor.changed_day END""",
                maxOf(configured, imported?.minimumFilterRev ?: 1), clock.today())
        }
        val floor = current()!!.minimumFilterRev
        var revisionCaptures=0L
        do {
            currentCoroutineContext().ensureActive()
            val n=query { with(InstallStore(db,clock)) {
                deleteEnvelopesAndClearDrafts("""DELETE FROM trusted_envelopes WHERE id IN (SELECT id FROM trusted_envelopes
                    WHERE fingerprint IN (SELECT fingerprint FROM cluster_sightings_v5 WHERE filter_rev < ?
                    UNION SELECT fingerprint FROM cluster_samples WHERE filter_rev < $floor) LIMIT 1000) RETURNING id""",floor)
            } }
            revisionCaptures+=n
        } while(n==1000)
        // A capture carries no filter revision of its own; its provenance is the cluster's sightings/samples.
        // Once the floor is above the first revision, a capture whose cluster shows NO evidence at or above the
        // floor (expired provenance, or never paired) cannot be proven safe and is removed conservatively.
        if (floor > 1) do {
            currentCoroutineContext().ensureActive()
            val n=query { with(InstallStore(db,clock)) {
                deleteEnvelopesAndClearDrafts("""DELETE FROM trusted_envelopes WHERE id IN (SELECT e.id FROM trusted_envelopes e
                    WHERE e.fingerprint IS NULL OR NOT EXISTS (SELECT 1 FROM cluster_sightings_v5 s WHERE s.fingerprint=e.fingerprint AND s.filter_rev >= ?)
                    AND NOT EXISTS (SELECT 1 FROM cluster_samples s WHERE s.fingerprint=e.fingerprint AND s.filter_rev >= $floor) LIMIT 1000) RETURNING id""",floor)
            } }
            revisionCaptures+=n
        } while(n==1000)
        counts["revision_captures"]=SweepResult(deleted=revisionCaptures,remainingDue=0)
        // Clear cluster-derived material BEFORE deleting the evidence that identifies affected clusters.
        var affected = 0L
        var revisionSamples=0L; var revisionSightings=0L; var sampleCaps=0L; var revisionBatches=0
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = query {
                val fps = select("""SELECT fingerprint FROM cluster_sightings_v5 WHERE filter_rev < ?
                    UNION SELECT fingerprint FROM cluster_samples WHERE filter_rev < ? LIMIT 1""", floor, floor) { rows ->
                    buildList { do { add(rows.getString(1)) } while (rows.next()) }
                } ?: emptyList()
                var removedSamples=0; var removedSightings=0; var cappedSamples=0
                for (fp in fps) {
                    update("UPDATE clusters SET notes=NULL, draft=NULL, draft_day=NULL WHERE fingerprint=?", fp)
                    val capped=update("""WITH due AS (
                        SELECT s.ctid, LEAST(s.purge_after, COALESCE((SELECT max(day)+90 FROM cluster_sightings_v5
                            WHERE fingerprint=? AND filter_rev>=?), ?), c.resolved_day+30) deadline
                        FROM cluster_samples s JOIN clusters c USING(fingerprint) WHERE s.fingerprint=? AND s.filter_rev>=?
                    ) UPDATE cluster_samples s SET purge_after=d.deadline FROM
                        (SELECT * FROM due WHERE deadline < (SELECT purge_after FROM cluster_samples WHERE ctid=due.ctid) LIMIT 1000) d
                        WHERE s.ctid=d.ctid""",fp,floor,clock.today(),fp,floor)
                    cappedSamples+=capped
                    if(capped==1000) continue // Retain unsafe provenance until every derived copy is re-capped.
                    removedSamples+=update("DELETE FROM cluster_samples WHERE ctid IN (SELECT ctid FROM cluster_samples WHERE fingerprint=? AND filter_rev < ? LIMIT 1000)", fp, floor)
                    removedSightings+=update("DELETE FROM cluster_sightings_v5 WHERE ctid IN (SELECT ctid FROM cluster_sightings_v5 WHERE fingerprint=? AND filter_rev < ? LIMIT 1000)", fp, floor)
                    update("""UPDATE clusters SET first_seen_day=x.first_day, last_seen_day=x.last_day FROM
                        (SELECT min(day) first_day,max(day) last_day FROM cluster_sightings_v5 WHERE fingerprint=?) x
                        WHERE fingerprint=? AND x.first_day IS NOT NULL""", fp, fp)

                }
                listOf(fps.size,removedSamples,removedSightings,cappedSamples)
            }
            affected += n[0]; revisionSamples+=n[1]; revisionSightings+=n[2]; sampleCaps+=n[3]; revisionBatches++
            if (n[0] == 0) break
        }
        counts["revision_clusters"] = SweepResult(rewritten=affected,batches=revisionBatches,remainingDue=0)
        counts["revision_samples"] = SweepResult(deleted=revisionSamples,rewritten=sampleCaps,batches=revisionBatches,remainingDue=0)
        counts["revision_sightings"] = SweepResult(deleted=revisionSightings,batches=revisionBatches,remainingDue=0)
        for (table in listOf("token_sightings_v5", "vocabulary_v5")) {
            var deleted = 0L; var passes = 0
            do {
                currentCoroutineContext().ensureActive()
                val n = query { update("DELETE FROM $table WHERE ctid IN (SELECT ctid FROM $table WHERE filter_rev < ? LIMIT 1000)", floor) }
                deleted += n; passes++
            } while (n == 1000)
            counts["revision_$table"] = SweepResult(deleted, batches = passes, remainingDue = 0)
        }
        results = counts
        return floor
    }

    private data class LegacySample(val fp: String, val version: String, val received: LocalDate, val body: String, val status: String, val last: LocalDate, val kind: String)
}
