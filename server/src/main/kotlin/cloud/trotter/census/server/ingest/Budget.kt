package cloud.trotter.census.server.ingest

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.Policy
import cloud.trotter.census.server.db.LedgerRow
import cloud.trotter.census.server.secondsToUtcMidnight

/** Per-install, per-UTC-day admission limits; defaults come from [Policy] (#79: health reports have their own). */
data class BudgetPolicy(
    val dailySkeletonBudget: Int = Policy().dailySkeletonBudget,
    val dailyBytes: Long = Policy().dailyBytesBudget,
    val dailyBatches: Int = Policy().dailyBatchBudget,
    val dailyHealthReports: Int = Policy().dailyHealthReportBudget,
) {
    init {
        require(dailySkeletonBudget >= 0 && dailyBytes >= 0 && dailyBatches >= 0 && dailyHealthReports >= 0)
    }
}

sealed interface BudgetVerdict {
    data object Ok : BudgetVerdict
    data class BudgetExhausted(val retryAfterSeconds: Long) : BudgetVerdict
}

sealed interface ConsumeOutcome {
    data class Consumed(val bytesRemaining: Long, val skeletonsRemaining: Int, val batchesRemaining: Int) : ConsumeOutcome
    data class BudgetExhausted(val retryAfterSeconds: Long) : ConsumeOutcome
}

class Budget(private val clock: Clock, val policy: BudgetPolicy = BudgetPolicy()) {
    fun remainingSkeletons(row: LedgerRow?): Int = (policy.dailySkeletonBudget.toLong() - (row?.accepted ?: 0))
        .coerceIn(0, policy.dailySkeletonBudget.toLong()).toInt()

    fun remainingBytes(row: LedgerRow?): Long = (policy.dailyBytes - (row?.bytes ?: 0)).coerceAtLeast(0)

    fun exceeded(row: LedgerRow?, incomingBytes: Long, isNewBatch: Boolean): BudgetVerdict {
        require(incomingBytes >= 0)
        return if (remainingSkeletons(row) == 0 || incomingBytes > remainingBytes(row) ||
            (isNewBatch && (row?.batchIds?.size ?: 0) >= policy.dailyBatches)
        ) {
            BudgetVerdict.BudgetExhausted(clock.secondsToUtcMidnight())
        } else {
            BudgetVerdict.Ok
        }
    }
}
