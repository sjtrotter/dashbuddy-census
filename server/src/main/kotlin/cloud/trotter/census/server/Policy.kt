package cloud.trotter.census.server

import cloud.trotter.census.contract.SkeletonSchema
import kotlinx.serialization.Serializable

/** One effective policy is constructed after durable floor replay, before HTTP starts. */
@Serializable
data class Policy(
    val serverVersion: String = "dev",
    val imageDigest: String? = null,
    val minimumFilterRev: Int = 1,
    val k: Int = 10,
    val quarantineDays: Int = 7,
    val activeWindowDays: Int = 30,
    val dailySkeletonBudget: Int = 300,
    val maxSkeletonBytes: Int = 65_536,
    val maxBatchItems: Int = 100,
    val maxBatchBytes: Int = 1_048_576,
    // Notification wire support is opt-in until the separate ADR lifecycle prerequisite is repaired.
    val acceptedSchemaIds: List<String> = listOf(SkeletonSchema.SCHEMA_ID),
    val acceptedPlatforms: List<String> = listOf("doordash", "uber", "_unknown"),
    val acceptedTextKeys: List<String> = listOf("text", "desc", "state", "pane", "role", "hint", "tooltip", "error", "clickLabel", "uid"),
    val acceptedHashDomains: List<Int> = listOf(1),
    val retention: Retention = Retention(),
    val hashDomain: String = ContractCompatibility.HASH_DOMAIN,
)

/** Half-open UTC retention deadlines, enforced on reads and by lifecycle sweeps. */
@Serializable
data class Retention(
    val tokenSightingsDays: Int = 30,
    val clusterSightingsDays: Int = 90,
    val trustedEnvelopesDays: Int = 30,
    val healthDailyDays: Int = 180,
    val ingestLedgerDays: Int = 7,
    val backupsDays: Int = 14,
    // Must outlive every restorable backup: backupsDays (14) + S3 noncurrent-version expiry (14) + margin.
    val withdrawalsDays: Int = 60,
)

fun Config.policy(effectiveFloor: Int = minimumFilterRev): Policy = Policy(
    serverVersion = serverVersion, imageDigest = imageDigest,
    minimumFilterRev = maxOf(minimumFilterRev, effectiveFloor),
    acceptedSchemaIds = if (notificationsEnabled) listOf(
        SkeletonSchema.SCHEMA_ID, cloud.trotter.census.contract.NotificationSkeletonSchema.SCHEMA_ID,
    ) else listOf(SkeletonSchema.SCHEMA_ID),
)
