package cloud.trotter.census.server.db

import cloud.trotter.census.server.EpochKey
import cloud.trotter.census.server.TagPurpose
import cloud.trotter.census.server.testKeying
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * #86: how many rows of [table] belong to [installId]: by `install_id` in the tables that keep it, and in the tagged
 * content tables through the install's epoch tags under the live keys on [today] (the lookup erasure uses), or under
 * every stored key when [today] is null.
 */
internal fun Connection.installRows(table: String, installId: UUID, today: LocalDate?): Long {
    val tagged = TaggedTable.entries.singleOrNull { it.table == table }
        ?: return select("SELECT count(*) FROM $table WHERE install_id = ?", installId) { it.getLong(1) }!!
    return keys(today).sumOf { key ->
        val tags = createArrayOf("text", tagged.tags(this, key, installId).toTypedArray())
        try {
            select("SELECT count(*) FROM $table WHERE epoch = ? AND install_tag = ANY(?::bpchar[])", key.epoch, tags) { it.getLong(1) }!!
        } finally {
            tags.free()
        }
    }
}

/** Deletes [installId]'s rows of [table] the way [installRows] finds them. */
internal fun Connection.deleteInstallRows(table: String, installId: UUID, today: LocalDate?): Int {
    val tagged = TaggedTable.entries.singleOrNull { it.table == table }
        ?: return update("DELETE FROM $table WHERE install_id = ?", installId)
    return forInstallTags(keys(today), tagged, installId, "DELETE FROM $table WHERE epoch = ? AND install_tag = ANY(?::bpchar[])")
}

/** [installId]'s tag for [purpose] and [objectKey] under [day]'s epoch, as ingest on [today] computes it. */
internal fun Connection.testTag(purpose: TagPurpose, installId: UUID, objectKey: String, today: LocalDate, day: LocalDate = today): EpochTag =
    Tagger(this, testKeying, today).tag(purpose, installId, objectKey, day)

private fun Connection.keys(today: LocalDate?): List<EpochKey> = if (today != null) {
    with(EpochKeyStore) { liveKeys(testKeying, today) }
} else {
    prepareStatement("SELECT epoch, wrapped FROM epoch_keys").use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(EpochKey.unwrap(testKeying, rows.getInt(1), rows.getBytes(2))) } }
    }
}

/** Whether [tag], stored under [epoch], is [installId]'s tag for [purpose] and [objectKey] (#86). */
internal fun Connection.isTagOf(purpose: TagPurpose, installId: UUID, objectKey: String, epoch: Int, tag: String, today: LocalDate): Boolean =
    testTag(purpose, installId, objectKey, today, cloud.trotter.census.server.Epochs.firstDay(epoch)).tag == tag
