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
    var rows = 0L
    for (key in keys(today)) tagged.forEachTagChunk(this, key, installId) { chunk ->
        val tags = createArrayOf("text", chunk.toTypedArray())
        try {
            rows += select("SELECT count(*) FROM $table WHERE epoch = ? AND install_tag = ANY(?::bpchar[])", key.epoch, tags) { it.getLong(1) }!!
        } finally {
            tags.free()
        }
    }
    return rows
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

/**
 * #81: one row of each contributor-tier table for [installId], as the contributor routes write them: a confirmation,
 * a role tag, a released slot row (under the slot sighting's tag), a quarantine item and a grant. [fingerprint] must be
 * a stored cluster.
 */
internal fun Connection.seedContributorRows(installId: UUID, fingerprint: String, day: LocalDate) {
    val variant = SlotVariant("1.0", 1, 1, "a".repeat(64))
    val value = "1234567890abcdef"
    val confirmation = testTag(TagPurpose.CONFIRMATION, installId, SlotObject.of(fingerprint, variant, "root/text", value), day)
    update("""INSERT INTO slot_confirmations (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value, epoch,
            install_tag, trusted, last_day) VALUES (?, '1.0', 1, 1, repeat('a', 64), 'root/text', ?, ?, ?, false, ?)""",
        fingerprint, value, confirmation.epoch, confirmation.tag, day)
    val role = testTag(TagPurpose.ROLE, installId, SlotObject.of(fingerprint, variant, "root/text", "tip"), day)
    update("""INSERT INTO slot_roles (fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, role, epoch,
            install_tag, trusted, last_day) VALUES (?, '1.0', 1, 1, repeat('a', 64), 'root/text', 'tip', ?, ?, false, ?)""",
        fingerprint, role.epoch, role.tag, day)
    val released = testTag(TagPurpose.SLOT, installId, SlotObject.of(fingerprint, variant, "root/text", "present"), day)
    update("INSERT INTO slot_release_days (release_day) VALUES (?) ON CONFLICT DO NOTHING", day)
    update("""INSERT INTO slot_release (release_day, fingerprint, platform_app_version, hash_domain, filter_rev, layout, slot, value,
            epoch, install_tag) VALUES (?, ?, '1.0', 1, 1, repeat('a', 64), 'root/text', 'present', ?, ?)""",
        day, fingerprint, released.epoch, released.tag)
    val item = UUID.randomUUID()
    val quarantined = testTag(TagPurpose.QUARANTINE, installId, item.toString(), day)
    update("INSERT INTO quarantine_items (id, epoch, install_tag, expires_day, artifact) VALUES (?, ?, ?, ?, ?)",
        item, quarantined.epoch, quarantined.tag, day.plusDays(3), byteArrayOf(1))
    update("INSERT INTO contributor_grants (grant_id, install_id, granted_day, expires_day) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(), installId, day, day.plusDays(30))
}
