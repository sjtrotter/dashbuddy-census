package cloud.trotter.census.server

import kotlinx.serialization.Serializable

/** Public policy defaults; POLICY_* environment overrides are a later slice (#1157 S1). */
@Serializable
data class Policy(
    val serverVersion: String = "dev",
    val imageDigest: String? = null,
    val k: Int = 10,
    val quarantineDays: Int = 7,
    val activeWindowDays: Int = 30,
    val dailySkeletonBudget: Int = 300,
    val maxSkeletonBytes: Int = 65_536,
    val maxBatchItems: Int = 100,
    val maxBatchBytes: Int = 1_048_576,
    val acceptedSchemaIds: List<String> = listOf("uinode.skeleton.v1"),
    val acceptedPlatforms: List<String> = listOf("doordash", "uber", "_unknown"),
    val acceptedTextKeys: List<String> = listOf("text", "desc", "state", "pane", "role", "hint", "tooltip", "error", "clickLabel", "uid"),
    val acceptedHashDomains: List<Int> = listOf(1),
    val retention: Retention = Retention(),
    val hashDomain: String = ContractCompatibility.HASH_DOMAIN,
)

/** Retention targets in days; scheduled pruning is future ingest work (#1157 S1). */
@Serializable
data class Retention(
    val tokenSightingsDays: Int = 30,
    val clusterSightingsDays: Int = 90,
    val trustedEnvelopesDays: Int = 30,
    val healthDailyDays: Int = 180,
    val ingestLedgerDays: Int = 7,
    val backupsDays: Int = 14,
)
