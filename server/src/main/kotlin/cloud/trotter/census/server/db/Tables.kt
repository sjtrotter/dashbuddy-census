package cloud.trotter.census.server.db

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.postgresql.util.PGobject

/** Mirrors V1; Flyway alone owns DDL. */
object Installs : Table("installs") {
    val installId = javaUUID("install_id")
    val keyHash = char("key_hash", 64)
    val createdDay = date("created_day")
    val lastSeenDay = date("last_seen_day")
    val trusted = bool("trusted").default(false)
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val attestedVerdict = text("attested_verdict").nullable()
    val lastAppVersion = text("last_app_version").nullable()
    val metroCell = text("metro_cell").nullable()
    override val primaryKey = PrimaryKey(installId)
}

object Nonces : Table("nonces") {
    val nonce = char("nonce", 32)
    val installId = javaUUID("install_id").references(Installs.installId).nullable()
    val issuedAt = timestampWithTimeZone("issued_at").index("nonces_issued_at_idx")
    val used = bool("used").default(false)
    override val primaryKey = PrimaryKey(nonce)
}

object IngestLedger : Table("ingest_ledger") {
    val installId = javaUUID("install_id").references(Installs.installId)
    val day = date("day")
    val bytes = long("bytes").default(0)
    val accepted = integer("accepted").default(0)
    val duplicate = integer("duplicate").default(0)
    val rejected = registerColumn("rejected", RejectedCountsColumnType()).default(emptyMap())
    val batchIds = array("batch_ids", TextColumnType()).default(emptyList())
    override val primaryKey = PrimaryKey(installId, day)
}

/** PostgreSQL jsonb without a second JSON library; values are reason codes and counts only. */
private class RejectedCountsColumnType : ColumnType<Map<String, Int>>() {
    override fun sqlType(): String = "JSONB"
    override fun valueFromDB(value: Any): Map<String, Int> = Json.decodeFromString(value.toString())
    override fun notNullValueToDB(value: Map<String, Int>): Any = PGobject().apply {
        type = "jsonb"
        this.value = Json.encodeToString(value)
    }
    override fun nonNullValueToString(value: Map<String, Int>): String =
        "'${Json.encodeToString(value).replace("'", "''")}'::jsonb"
}
