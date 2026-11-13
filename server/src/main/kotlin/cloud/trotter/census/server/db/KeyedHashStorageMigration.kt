package cloud.trotter.census.server.db

import cloud.trotter.census.server.HashKeying
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.migration.Context
import org.flywaydb.core.api.migration.JavaMigration
import org.slf4j.LoggerFactory
import java.sql.Connection

/** Startup refuses a pepper other than the one the database was keyed under; deliberately carries no message. */
class HashPepperMismatchException : IllegalStateException()

/**
 * V7, keyed storage (#85): rewrites every stored word hash, and every stored value derived from one, under the
 * server pepper, then records the pepper's [HashKeying.keyCheck] in `hash_keying`.
 *
 * A Flyway Java migration, not SQL, because the pepper never enters SQL text or the database. Flyway runs it in one
 * PostgreSQL transaction together with its schema-history row, so it is all-or-nothing: an interruption rolls every
 * rewrite back and the next startup runs it again from the start (resumable by rerun, never half-keyed), and once it
 * commits Flyway never runs it again (idempotent). It runs in version order, before any later SQL migration, before
 * the lifecycle bootstrap and before HTTP. A database restored from a pre-V7 dump is keyed by the same path.
 *
 * What it rewrites, against the fixed V6 schema:
 * - `token_hash` in `token_sightings_v5`, `vocabulary_v5` and the retired `token_sightings` / `vocabulary`;
 * - every slot `h` in `cluster_samples.skeleton`;
 * - notification cluster fingerprints, which fold the title and text hashes into a public digest: the `clusters` key,
 *   every foreign key that references it (found from the catalog, so none is missed), and the `fingerprint` field
 *   inside the stored notification samples. Screen fingerprints cover class and id only, no text, and stay public.
 *
 * Fail closed: a stored value that is not a well-formed census hash or fingerprint aborts the migration (and so
 * startup) instead of staying unkeyed.
 */
class KeyedHashStorageMigration(private val keying: HashKeying) : JavaMigration {
    override fun getVersion(): MigrationVersion = MigrationVersion.fromVersion("7")
    override fun getDescription(): String = "keyed hash storage"
    override fun getChecksum(): Int? = null
    override fun canExecuteInTransaction(): Boolean = true

    override fun migrate(context: Context) {
        val connection = context.connection
        connection.execute(
            """CREATE TABLE hash_keying (
                singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
                key_check char(64) NOT NULL
            )""",
        )
        val tokens = keyTokens(connection)
        val clusters = keyNotificationClusters(connection)
        val samples = keySamples(connection)
        connection.update("INSERT INTO hash_keying (singleton, key_check) VALUES (true, ?)", keying.keyCheck)
        log.info("keyed storage migrated tokens={} notification_clusters={} samples={}", tokens, clusters, samples)
    }

    private fun keyTokens(connection: Connection): Int {
        val old = buildSet {
            for (table in TOKEN_TABLES) addAll(connection.strings("SELECT DISTINCT token_hash FROM $table"))
        }
        connection.execute("CREATE TEMP TABLE keyed_tokens (old char(16) PRIMARY KEY, new char(16) NOT NULL) ON COMMIT DROP")
        connection.insertPairs("INSERT INTO keyed_tokens (old, new) VALUES (?, ?)", old.map { it to keying.token(it) })
        for (table in TOKEN_TABLES) {
            connection.update("UPDATE $table t SET token_hash = k.new FROM keyed_tokens k WHERE t.token_hash = k.old")
        }
        return old.size
    }

    private fun keyNotificationClusters(connection: Connection): Int {
        val old = connection.strings("SELECT fingerprint FROM clusters WHERE kind = 'notification'")
        if (old.isEmpty()) return 0
        connection.execute("CREATE TEMP TABLE keyed_clusters (old char(64) PRIMARY KEY, new char(64) NOT NULL) ON COMMIT DROP")
        connection.insertPairs("INSERT INTO keyed_clusters (old, new) VALUES (?, ?)", old.map { it to keying.fingerprint(it) })
        // A non-deferrable foreign key cannot follow a key change, so: copy each row under its new key, move every
        // reference, then drop the old row.
        val columns = connection.strings(
            """SELECT quote_ident(column_name) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'clusters' AND column_name <> 'fingerprint'
                ORDER BY ordinal_position""",
        )
        connection.update(
            "INSERT INTO clusters (fingerprint, ${columns.joinToString()}) SELECT k.new, ${columns.joinToString { "c.$it" }} " +
                "FROM clusters c JOIN keyed_clusters k ON c.fingerprint = k.old",
        )
        val references = connection.rows(
            """SELECT c.conrelid::regclass::text, quote_ident(a.attname) FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f' AND c.confrelid = 'clusters'::regclass""",
        )
        for ((table, column) in references) {
            connection.update("UPDATE $table x SET $column = k.new FROM keyed_clusters k WHERE x.$column = k.old")
        }
        connection.update("DELETE FROM clusters c USING keyed_clusters k WHERE c.fingerprint = k.old")
        return old.size
    }

    private fun keySamples(connection: Connection): Int {
        var rewritten = 0
        connection.prepareStatement(
            """UPDATE cluster_samples SET skeleton = ?::jsonb
                WHERE fingerprint = ? AND platform_app_version = ? AND received_day = ?""",
        ).use { update ->
            connection.prepareStatement(
                """SELECT s.fingerprint, s.platform_app_version, s.received_day, s.skeleton::text, c.kind
                    FROM cluster_samples s JOIN clusters c USING (fingerprint)""",
            ).use { select ->
                select.fetchSize = 200
                select.executeQuery().use { rows ->
                    while (rows.next()) {
                        val fingerprint = rows.getString(1)
                        val body = rows.getString(4)
                        val keyed = keySample(body, keying, fingerprint.takeIf { rows.getString(5) == "notification" })
                        if (keyed == body) continue
                        update.setString(1, keyed)
                        update.setString(2, fingerprint)
                        update.setString(3, rows.getString(2))
                        update.setObject(4, rows.getObject(3))
                        rewritten += update.executeUpdate()
                    }
                }
            }
        }
        return rewritten
    }

    companion object {
        private val log = LoggerFactory.getLogger("Migration")
        private val TOKEN_TABLES = listOf("token_sightings_v5", "vocabulary_v5", "token_sightings", "vocabulary")

        /** Startup check after migrating: the configured pepper is the one the stored values are keyed under. */
        fun verify(connection: Connection, keying: HashKeying) {
            val stored = connection.select("SELECT key_check FROM hash_keying WHERE singleton") { it.getString(1) }
            if (stored == null || !keying.same(stored, keying.keyCheck)) throw HashPepperMismatchException()
        }

        /**
         * One stored sample with every slot hash keyed (the slot predicate is [cloud.trotter.census.server.ingest.StoredSampleExpiry]'s),
         * and, for a notification, its `fingerprint` field set to the cluster's keyed key.
         */
        internal fun keySample(body: String, keying: HashKeying, notificationFingerprint: String?): String {
            fun visit(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> {
                    val hash = (value["h"] as? JsonPrimitive)?.takeIf { it.isString }
                    if (hash != null && "kind" in value) {
                        JsonObject(value + ("h" to JsonPrimitive(keying.token(hash.content))))
                    } else {
                        JsonObject(value.mapValues { visit(it.value) })
                    }
                }
                is JsonArray -> JsonArray(value.map { visit(it) })
                else -> value
            }
            val parsed = Json.parseToJsonElement(body)
            val keyed = visit(parsed)
            val result = if (notificationFingerprint != null && keyed is JsonObject && "fingerprint" in keyed) {
                JsonObject(keyed + ("fingerprint" to JsonPrimitive(notificationFingerprint)))
            } else {
                keyed
            }
            return if (result == parsed) body else result.toString()
        }
    }
}

private fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}

private fun Connection.strings(sql: String): List<String> = rows(sql).map { it.first }

private fun Connection.rows(sql: String): List<Pair<String, String>> = createStatement().use { statement ->
    statement.executeQuery(sql).use { rows ->
        val width = rows.metaData.columnCount
        buildList { while (rows.next()) add(rows.getString(1) to if (width > 1) rows.getString(2) else "") }
    }
}

private fun Connection.insertPairs(sql: String, pairs: List<Pair<String, String>>) {
    prepareStatement(sql).use { statement ->
        for ((index, pair) in pairs.withIndex()) {
            statement.setString(1, pair.first)
            statement.setString(2, pair.second)
            statement.addBatch()
            if (index % 500 == 499) statement.executeBatch()
        }
        statement.executeBatch()
    }
}
