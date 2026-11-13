package cloud.trotter.census.server.db

import cloud.trotter.census.server.*
import cloud.trotter.census.server.ingest.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker required for lifecycle acceptance")
abstract class LifecycleTestDatabase {
    protected var instant = Instant.parse("2026-10-06T12:00:00Z")
    protected val clock = object : Clock { override fun now() = instant }
    protected val day: LocalDate get() = clock.today()
    protected val key = "a".repeat(64)
    protected val policy = notificationPolicy
    protected fun config() = Config.fromEnv(testEnvironment() + mapOf("DATABASE_URL" to postgres.jdbcUrl,
        "DATABASE_USER" to postgres.username, "DATABASE_PASSWORD" to postgres.password))
    protected fun <T> sql(block: (Connection) -> T): T = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)
    protected fun count(table: String): Long = sql { it.select("SELECT count(*) FROM $table") { r -> r.getLong(1) }!! }
    protected fun install(age: Long = 8, trusted: Boolean = false): UUID = UUID.randomUUID().also { id -> sql {
        it.update("""INSERT INTO installs(install_id,key_hash,created_day,last_seen_day,trusted)
            VALUES(?,?,?,?,?)""", id, key, day.minusDays(age), day, trusted)
    } }
    protected fun item(revision: Int = 1, observation: LocalDate = day, channel: String = "CHANNEL_SENTINEL"): ItemVerdict.Accepted {
        val raw = JsonObject(notificationFixture(observation, channel) + mapOf("filterRev" to JsonPrimitive(revision)))
        return SkeletonValidator.validate(raw, policy, day) as ItemVerdict.Accepted
    }
    /**
     * A second hash domain cannot be ingested: the contract's envelope gate pins `hashDomain` to
     * `CensusHash.HASH_DOMAIN`, so the validator maps any other value to `bad_item`. The partition
     * key still carries the domain, so its separation is exercised by seeding the sighting row directly.
     */
    protected fun sightDomain2(id: UUID, revision: Int = 1) = sql {
        val (token, kind) = it.select("SELECT token_hash, kind FROM token_sightings_v5 WHERE hash_domain=1 LIMIT 1") { r -> r.getString(1) to r.getString(2) }!!
        val trusted = it.select("SELECT trusted FROM installs WHERE install_id=?", id) { r -> r.getBoolean(1) }!!
        it.update("""INSERT INTO token_sightings_v5(token_hash,epoch,install_tag,trusted,first_day,last_day,kind,hash_domain,filter_rev)
            VALUES (?, ?, ?, ?, ?, ?, ?, 2, ?)
            ON CONFLICT (hash_domain, token_hash, filter_rev, epoch, install_tag) DO UPDATE SET last_day=EXCLUDED.last_day""",
            token, Epochs.of(day), tokenTag(id, token, 2, revision), trusted, day, day, kind, revision)
    }

    /** #86: [id]'s epoch tag for [objectKey] under [on]'s epoch, as ingest computes it (the key is created if missing). */
    protected fun tag(purpose: TagPurpose, id: UUID, objectKey: String, on: LocalDate = day): String = sql {
        with(EpochKeyStore) { it.keyForTagging(testKeying, Epochs.of(on), day) }.tag(purpose, id, objectKey)
    }

    /** [id]'s tag on a token sighting (the fixtures' word hash, keyed, by default). */
    protected fun tokenTag(id: UUID, token: String = testKeying.token("0123456789abcdef"), domain: Int = 1, revision: Int = 1, on: LocalDate = day): String =
        tag(TagPurpose.TOKEN, id, TokenObject.of(domain, revision, token), on)
    protected fun screenItem(): ItemVerdict.Accepted {
        val slot=cloud.trotter.census.contract.TextSlot("0123456789abcdef","words:1")
        val child=cloud.trotter.census.contract.UiSkeletonNodeDto(className="android.widget.TextView",text=mapOf("desc" to slot,"pane" to slot))
        val root=cloud.trotter.census.contract.UiSkeletonNodeDto(className="android.widget.Button",text=mapOf("text" to slot),children=listOf(child))
        val fields=notificationFixture(day)-setOf("kind","channelId","slots")
        val raw=JsonObject(fields+mapOf("schemaId" to JsonPrimitive("uinode.skeleton.v1"),
            "fingerprint" to JsonPrimitive(cloud.trotter.census.contract.CensusFingerprint.of(root)),
            "windowTitle" to cloud.trotter.census.contract.SkeletonSchema.json.encodeToJsonElement(cloud.trotter.census.contract.TextSlot.serializer(),slot),
            "root" to cloud.trotter.census.contract.SkeletonSchema.json.encodeToJsonElement(cloud.trotter.census.contract.UiSkeletonNodeDto.serializer(),root)))
        return SkeletonValidator.validate(raw,policy,day) as ItemVerdict.Accepted
    }
    /** #85: an accepted item as SkeletonStore stores it (keyed word hashes, keyed notification fingerprint). */
    protected fun stored(accepted: ItemVerdict.Accepted): ItemVerdict.Accepted = testKeying.atRest(accepted)
    protected suspend fun observe(db: Database, id: UUID, accepted: List<ItemVerdict.Accepted> = listOf(item())) =
        SkeletonStore(db, clock).ingest(id, key, day, accepted, 0, emptyMap(), UUID.randomUUID().toString(), 1, BudgetPolicy())
    @BeforeEach fun reset() {
        instant = Instant.parse("2026-10-06T12:00:00Z")
        Database.migrate(config())
        sql { it.update("TRUNCATE installs, clusters, withdrawals, vocabulary_v5, filter_floor, health_fleet_daily, token_sightings_v5, health_daily, epoch_keys CASCADE") }
    }
    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer("postgres:17-alpine")
        @JvmStatic fun dockerAvailable(): Boolean = System.getenv("CI") == "true" || runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }
}
