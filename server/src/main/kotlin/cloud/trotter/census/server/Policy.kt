package cloud.trotter.census.server

import cloud.trotter.census.contract.SkeletonVersion
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
    // #79: the rest of the per-install daily quota, enforced at admission against the UTC-day ledger.
    val dailyBytesBudget: Long = 10L * 1024 * 1024,
    val dailyBatchBudget: Int = 40,
    val dailyHealthReportBudget: Int = 60,
    val maxSkeletonBytes: Int = 65_536,
    val maxBatchItems: Int = 100,
    val maxBatchBytes: Int = 1_048_576,
    // Notification wire support is opt-in until the separate ADR lifecycle prerequisite is repaired.
    val acceptedSchemaIds: List<String> = acceptedSchemaIds(notificationsEnabled = false),
    val acceptedPlatforms: List<String> = listOf("doordash", "uber", "_unknown"),
    val acceptedTextKeys: List<String> = listOf("text", "desc", "state", "pane", "role", "hint", "tooltip", "error", "clickLabel", "uid"),
    val acceptedHashDomains: List<Int> = listOf(1),
    val retention: Retention = Retention(),
    val hashDomain: String = ContractCompatibility.HASH_DOMAIN,
)

/**
 * Half-open UTC retention deadlines, enforced on reads and by lifecycle sweeps. #79 (council round 3): per-install
 * health and cluster sightings keep 30 days; health is folded into the fleet totals before its per-install rows go.
 */
@Serializable
data class Retention(
    val tokenSightingsDays: Int = 30,
    val clusterSightingsDays: Int = 30,
    val trustedEnvelopesDays: Int = 30,
    val healthDailyDays: Int = 30,
    val ingestLedgerDays: Int = 7,
    val backupsDays: Int = 14,
    // Must outlive every restorable backup: backupsDays (14) + S3 noncurrent-version expiry (14) + margin.
    val withdrawalsDays: Int = 60,
)

/** The per-install daily quota this policy advertises (#79): the one place routes build a [BudgetPolicy] from. */
fun Policy.budget(): cloud.trotter.census.server.ingest.BudgetPolicy = cloud.trotter.census.server.ingest.BudgetPolicy(
    dailySkeletonBudget = dailySkeletonBudget, dailyBytes = dailyBytesBudget, dailyBatches = dailyBatchBudget,
    dailyHealthReports = dailyHealthReportBudget,
)

fun Config.policy(effectiveFloor: Int = minimumFilterRev): Policy = Policy(
    serverVersion = serverVersion, imageDigest = imageDigest,
    minimumFilterRev = maxOf(minimumFilterRev, effectiveFloor),
    acceptedSchemaIds = acceptedSchemaIds(notificationsEnabled),
)

/**
 * The skeleton versions this server validates and stores (#78): v1 for apps that predate it, v2 for shape slots,
 * click flags and notification actions. Listed by hand, not read from the contract, because the validator knows each
 * version's keys: a contract bump that adds a version must not start accepting it before the server does.
 */
internal val SERVED_SKELETON_VERSIONS: List<SkeletonVersion> = listOf(SkeletonVersion.V1, SkeletonVersion.V2)

/**
 * The advertised schema ids (#78), oldest version first and screen before notification, the contract's own order
 * (`CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS`). The app sends the newest version listed; notification ids appear
 * only while notifications are enabled.
 */
fun acceptedSchemaIds(notificationsEnabled: Boolean): List<String> = SERVED_SKELETON_VERSIONS.flatMap {
    listOfNotNull(it.screenSchemaId, it.notificationSchemaId.takeIf { notificationsEnabled })
}
