package cloud.trotter.census.server.db

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.server.HashKeying
import cloud.trotter.census.server.ingest.StoredSampleExpiry
import cloud.trotter.census.server.ingest.storedKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.time.LocalDate

/**
 * The V5 cutover of null-provenance (legacy) samples, one batch at a time: a sample whose public body proves the key
 * it is stored under gets its embedded provenance and loses every legacy hash; an invalid, resolved or expired one
 * is deleted; the cluster's derived notes and draft are cleared either way.
 *
 * #85: V7 runs this before it keys anything, while the bodies and fingerprints are still public; the startup
 * bootstrap runs it again afterwards and finds nothing. A body proves its key when the stored key of its own
 * `fingerprint` field and of its recomputed fingerprint ([storedKey]) both equal the row's stored key. A keyed body
 * cannot prove anything, so a null-provenance sample with keyed slots is deleted (fail closed).
 */
internal object LegacySampleCutover {
    /** Returns (deleted, scrubbed) for at most [limit] legacy samples; zero of both means none are left. */
    fun Connection.cutOver(keying: HashKeying, today: LocalDate, limit: Int, rowKeyIsPublic: Boolean): Pair<Int, Int> {
        val rows = select("""SELECT s.*, c.status, c.last_seen_day, c.kind FROM cluster_samples s JOIN clusters c USING(fingerprint)
            WHERE s.hash_domain IS NULL OR s.filter_rev IS NULL OR s.purge_after IS NULL LIMIT ?""", limit) { rows ->
            buildList { do { add(Sample(rows.getString("fingerprint"), rows.getString("platform_app_version"),
                rows.getObject("received_day", LocalDate::class.java), rows.getString("skeleton"),
                rows.getString("status"), rows.getObject("last_seen_day", LocalDate::class.java), rows.getString("kind"))) } while (rows.next()) }
        } ?: emptyList()
        var deleted = 0; var scrubbed = 0
        for (sample in rows) {
            update("UPDATE clusters SET notes=NULL, draft=NULL, draft_day=NULL WHERE fingerprint=?", sample.fp)
            // Before V7 the row's key is public; it is stored under the key V7 is about to give it.
            val rowKey = if (rowKeyIsPublic) keying.storedKey(sample.kind, sample.fp) else sample.fp
            val body = runCatching { Json.parseToJsonElement(sample.body).jsonObject }.getOrNull()
            val domain = (body?.get("hashDomain") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val rev = (body?.get("filterRev") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            val declared = (body?.get("fingerprint") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val valid = body != null && domain != null && domain > 0 && rev != null && rev > 0 && declared != null &&
                runCatching {
                    val dto = CensusSkeletonSchema.deserialize(sample.body)
                    dto.kind.wire == sample.kind && keying.storedKey(sample.kind, declared) == rowKey && keying.storedKey(dto) == rowKey
                }.getOrDefault(false)
            if (!valid || sample.status == "resolved" || sample.last.plusDays(90) <= today) {
                deleted += update("DELETE FROM cluster_samples WHERE fingerprint=? AND platform_app_version=? AND received_day=?", sample.fp, sample.version, sample.received)
            } else {
                // The body carries its row's stored key, never a public fingerprint, and no legacy hash survives.
                val scrubbedBody = Json.parseToJsonElement(StoredSampleExpiry.rewrite(sample.body, emptySet())).jsonObject
                scrubbed += update("""UPDATE cluster_samples SET hash_domain=?, filter_rev=?, purge_after=?, skeleton=?::jsonb
                    WHERE fingerprint=? AND platform_app_version=? AND received_day=?""", domain, rev, sample.last.plusDays(90),
                    JsonObject(scrubbedBody + ("fingerprint" to JsonPrimitive(rowKey))).toString(), sample.fp, sample.version, sample.received)
            }
        }
        return deleted to scrubbed
    }

    private data class Sample(val fp: String, val version: String, val received: LocalDate, val body: String, val status: String, val last: LocalDate, val kind: String)
}
