package cloud.trotter.census.server.db

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Epochs
import cloud.trotter.census.server.HashKeying
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.today
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.migration.Context
import org.flywaydb.core.api.migration.JavaMigration
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * V8, epoch pseudonyms at rest (#86, ADR-0011 §21): the content tables stop naming the install. Token sightings,
 * cluster sightings and per-install health are re-keyed from `install_id` to an epoch tag ([cloud.trotter.census.server.EpochKey]),
 * and the install-id columns are dropped. Afterwards only `installs`, `nonces`, `ingest_ledger` (authentication and
 * quotas) and `trusted_envelopes` (the operator's own devices) carry an install id.
 *
 * A Flyway Java migration like V7, because the epoch keys are generated here and wrapped under the pepper, which never
 * enters SQL. One transaction with its schema-history row: all or nothing, rerun from the start after an interruption.
 *
 * - `epoch_keys` holds one random key per epoch, wrapped; keys are created for the previous and current epoch.
 * - Each existing row is tagged under the epoch of the day it describes (a token row: its last day; older rows,
 *   already due for retention, under the previous epoch), exactly as ingest tags new rows, so a late report joins
 *   the migrated row instead of adding a second one.
 * - `trusted` and the quarantine mark `counts_from` (the day the install's evidence starts to count, kept only while
 *   that day is in the future) move onto the sighting rows, since k counting can no longer join the installs table.
 * - Revoked installs' sightings are deleted: k counting excluded them through that join, and revocation now deletes
 *   an install's evidence ([InstallStore.revoke]).
 * - The pre-V5 tables (`token_sightings`, `cluster_sightings`, `vocabulary`), empty since the V5 cutover, are dropped.
 * - The resulting shape is checked by `db/lifecycle-shape.sql`, which startup re-runs.
 */
class EpochPseudonymMigration(private val keying: HashKeying, private val clock: Clock) : JavaMigration {
    override fun getVersion(): MigrationVersion = MigrationVersion.fromVersion("8")
    override fun getDescription(): String = "epoch pseudonyms"
    override fun getChecksum(): Int? = null
    override fun canExecuteInTransaction(): Boolean = true

    override fun migrate(context: Context) {
        val connection = context.connection
        val today = clock.today()
        connection.execute(
            """CREATE TABLE epoch_keys (
                epoch integer PRIMARY KEY,
                wrapped bytea NOT NULL CHECK (octet_length(wrapped) = 60)
            )""",
        )
        val revoked = connection.update(
            "DELETE FROM token_sightings_v5 t USING installs i WHERE i.install_id = t.install_id AND i.revoked_at IS NOT NULL",
        ) + connection.update(
            "DELETE FROM cluster_sightings_v5 s USING installs i WHERE i.install_id = s.install_id AND i.revoked_at IS NOT NULL",
        )
        connection.execute(
            """ALTER TABLE token_sightings_v5 ADD COLUMN epoch integer, ADD COLUMN install_tag char(64),
                ADD COLUMN trusted boolean NOT NULL DEFAULT false, ADD COLUMN counts_from date NULL""",
        )
        connection.execute(
            """ALTER TABLE cluster_sightings_v5 ADD COLUMN epoch integer, ADD COLUMN install_tag char(64),
                ADD COLUMN trusted boolean NOT NULL DEFAULT false""",
        )
        connection.execute("ALTER TABLE health_daily ADD COLUMN epoch integer, ADD COLUMN install_tag char(64)")

        val tagger = Tagger(connection, keying, today)
        val live = Epochs.live(today)
        fun epochDay(day: LocalDate): LocalDate = day.coerceIn(Epochs.firstDay(live.first), Epochs.firstDay(live.last + 1).minusDays(1))
        val tokens = connection.retag(
            """SELECT t.hash_domain, t.filter_rev, t.token_hash, t.install_id, t.last_day, i.trusted, i.created_day
                FROM token_sightings_v5 t JOIN installs i USING (install_id)""",
            """UPDATE token_sightings_v5 SET epoch = ?, install_tag = ?, trusted = ?, counts_from = ?
                WHERE hash_domain = ? AND token_hash = ? AND filter_rev = ? AND install_id = ?""",
        ) { row ->
            val domain = row.getInt(1); val revision = row.getInt(2); val token = row.getString(3)
            val id = row.getObject(4, UUID::class.java)
            val tag = tagger.tag(TagPurpose.TOKEN, id, TokenObject.of(domain, revision, token), epochDay(row.getObject(5, LocalDate::class.java)))
            listOf(tag.epoch, tag.tag, row.getBoolean(6), VocabularyEligibility.countsFrom(row.getObject(7, LocalDate::class.java), today),
                domain, token, revision, id)
        }
        val clusters = connection.retag(
            """SELECT s.fingerprint, s.install_id, s.day, s.platform_app_version, s.hash_domain, s.filter_rev, i.trusted
                FROM cluster_sightings_v5 s JOIN installs i USING (install_id)""",
            """UPDATE cluster_sightings_v5 SET epoch = ?, install_tag = ?, trusted = ?
                WHERE fingerprint = ? AND install_id = ? AND day = ? AND platform_app_version = ? AND hash_domain = ? AND filter_rev = ?""",
        ) { row ->
            val fingerprint = row.getString(1); val id = row.getObject(2, UUID::class.java)
            val day = row.getObject(3, LocalDate::class.java)
            val tag = tagger.tag(TagPurpose.CLUSTER, id, fingerprint, epochDay(day))
            listOf(tag.epoch, tag.tag, row.getBoolean(7), fingerprint, id, day, row.getString(4), row.getInt(5), row.getInt(6))
        }
        val health = connection.retag(
            "SELECT install_id, day, platform, platform_app_version FROM health_daily",
            """UPDATE health_daily SET epoch = ?, install_tag = ?
                WHERE install_id = ? AND day = ? AND platform = ? AND platform_app_version = ?""",
        ) { row ->
            val id = row.getObject(1, UUID::class.java); val day = row.getObject(2, LocalDate::class.java)
            val tag = tagger.tag(TagPurpose.HEALTH, id, "", epochDay(day))
            listOf(tag.epoch, tag.tag, id, day, row.getString(3), row.getString(4))
        }

        // Dropping install_id drops the old primary keys and the foreign keys to installs with it.
        connection.execute(
            """ALTER TABLE token_sightings_v5 DROP COLUMN install_id, ALTER COLUMN epoch SET NOT NULL,
                ALTER COLUMN install_tag SET NOT NULL, ALTER COLUMN trusted DROP DEFAULT,
                ADD PRIMARY KEY (hash_domain, token_hash, filter_rev, epoch, install_tag)""",
        )
        connection.execute(
            """ALTER TABLE cluster_sightings_v5 DROP COLUMN install_id, ALTER COLUMN epoch SET NOT NULL,
                ALTER COLUMN install_tag SET NOT NULL, ALTER COLUMN trusted DROP DEFAULT,
                ADD PRIMARY KEY (fingerprint, install_tag, day, platform_app_version, hash_domain, filter_rev)""",
        )
        connection.execute(
            """ALTER TABLE health_daily DROP COLUMN install_id, ALTER COLUMN epoch SET NOT NULL,
                ALTER COLUMN install_tag SET NOT NULL, ADD PRIMARY KEY (install_tag, day, platform, platform_app_version)""",
        )
        connection.execute("CREATE INDEX token_sightings_v5_tag_idx ON token_sightings_v5 (install_tag)")
        connection.execute("CREATE INDEX cluster_sightings_v5_tag_idx ON cluster_sightings_v5 (install_tag)")
        connection.execute("DROP TABLE token_sightings, cluster_sightings, vocabulary")
        connection.execute(shapeCheck())
        log.info("epoch pseudonyms migrated tokens={} cluster_sightings={} health_rows={} revoked_rows={}", tokens, clusters, health, revoked)
    }

    companion object {
        private val log = LoggerFactory.getLogger("Migration")

        /** The post-V8 shape check (`db/lifecycle-shape.sql`), also run by the filter bootstrap at every startup. */
        fun shapeCheck(): String = requireNotNull(EpochPseudonymMigration::class.java.getResourceAsStream("/db/lifecycle-shape.sql"))
            .bufferedReader().use { it.readText() }
    }
}

/** A token sighting's tag object: the partition and the keyed token, so one install's tokens carry unrelated tags (#86). */
internal object TokenObject {
    fun of(hashDomain: Int, filterRev: Int, keyedToken: String): String = "$hashDomain:$filterRev:$keyedToken"
}

/** Reads every row of [select], then writes [update] with the parameters [bind] computes, in batches. */
private fun Connection.retag(select: String, update: String, bind: (ResultSet) -> List<Any?>): Int {
    val updates = createStatement().use { statement ->
        statement.executeQuery(select).use { rows -> buildList { while (rows.next()) add(bind(rows)) } }
    }
    prepareStatement(update).use { statement ->
        for ((index, parameters) in updates.withIndex()) {
            parameters.forEachIndexed { position, value -> statement.setObject(position + 1, value) }
            statement.addBatch()
            if (index % 500 == 499) statement.executeBatch()
        }
        statement.executeBatch()
    }
    return updates.size
}

private fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}
