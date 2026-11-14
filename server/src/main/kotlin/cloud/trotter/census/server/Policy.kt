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
    // #80: operator-side thresholds; nothing on the phone reads them, so they stay out of the published policy.
    @kotlinx.serialization.Transient val slotConstancy: SlotConstancyPolicy = SlotConstancyPolicy(),
    // #81: contributor-tier limits; server-side only, like the slot thresholds, and documented in API.md.
    @kotlinx.serialization.Transient val contributor: ContributorPolicy = ContributorPolicy(),
    // #82: the anchor check's limits; operator-side, documented in API.md.
    @kotlinx.serialization.Transient val anchorCheck: AnchorCheckPolicy = AnchorCheckPolicy(),
)

/**
 * The anchor check's limits (#82, ADR-0011 §16). A budget counts candidates, refused ones included, per caller and UTC
 * day; each caller brings its own daily limit ([cloud.trotter.census.server.db.AnchorCaller]).
 */
data class AnchorCheckPolicy(
    /** Candidates the operator may check per UTC day. */
    val operatorDailyCandidates: Int = 200,
    /** Candidates in one query. */
    val maxCandidates: Int = 8,
    /** Days an audit row is kept after its day. */
    val auditDays: Int = 30,
) {
    init {
        require(operatorDailyCandidates > 0 && maxCandidates in 1..64 && auditDays in 1..30)
    }
}

/**
 * The contributor tier's limits (#81, ADR-0011 §15). Daily counts are per install and UTC day, kept on its ledger row,
 * so they are per grant (an install holds one live grant) and a data deletion does not reset them.
 */
data class ContributorPolicy(
    /** A grant lasts this many UTC days from the day it is issued. */
    val grantDays: Int = 30,
    /** Slot-class and gate items an install may read per day. */
    val dailyReads: Int = 200,
    /** Reviews (one screen's confirmations and role tags) per day; the app sends at most 3. */
    val dailyReviews: Int = 10,
    /** Plaintext word submissions per day. */
    val dailyWords: Int = 30,
    /** "Ask the developer" items per day. */
    val dailyQuarantine: Int = 3,
    /** A quarantine item expires at the start of the UTC day this many days after receipt: at most 72 hours. */
    val quarantineDays: Int = 3,
    /** Confirmations and role tags in one review, gate items in one query, words in one submission. */
    val maxReviewItems: Int = 256,
    val maxGateItems: Int = 64,
    val maxWordItems: Int = 16,
    /** The largest artifact (decoded bytes) a quarantine item may carry. */
    val maxArtifactBytes: Int = 131_072,
    /** Independent installs that must confirm one value before its gate can open. */
    val gateConfirmations: Int = 2,
    /** Days a confirmation, role tag, audit row or pending word is kept after its last day. */
    val retentionDays: Int = 30,
) {
    init {
        require(grantDays > 0 && quarantineDays in 1..3 && gateConfirmations >= 2 && retentionDays in 1..30)
        require(listOf(dailyReads, dailyReviews, dailyWords, dailyQuarantine, maxReviewItems, maxGateItems, maxWordItems, maxArtifactBytes).all { it > 0 })
    }
}

/**
 * The per-slot constancy thresholds (#80, ADR-0011 §14), in whole percent of the installs that saw the slot within one
 * epoch. A slot below [Policy.k] installs is `unknown` whatever these say. The order the classes are tested in lives
 * in [SlotConstancy.classify].
 */
data class SlotConstancyPolicy(
    // A backstop firing on one install in ten means the slot sometimes holds protected text: never offered as chrome.
    val withheldPercent: Int = 10,
    // Most installs see a value format (money, a time) here: a parse field, never vocabulary.
    val shapePercent: Int = 51,
    // One hash on nine installs in ten, and on at least k of them, is candidate chrome; a single stray value is allowed.
    val constantPercent: Int = 90,
    // A hash on three installs is shared (a store or a zone), not one dasher's customer, which two dashers rarely share.
    val regionalMinInstalls: Int = 3,
    // Several shared values make a slot regional; one shared value beside noise is not enough.
    val regionalMinValues: Int = 2,
    // The shared values must cover half the slot's installs, or the slot is mostly one-off values.
    val regionalPercent: Int = 50,
    // Ingest records at most this many slots of one skeleton, in tree order; a real screen has a few hundred at most.
    val maxSlotsPerSkeleton: Int = 1024,
) {
    init {
        require(listOf(withheldPercent, shapePercent, constantPercent, regionalPercent).all { it in 1..100 })
        require(regionalMinInstalls >= 2 && regionalMinValues >= 2 && maxSlotsPerSkeleton > 0)
    }
}

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
