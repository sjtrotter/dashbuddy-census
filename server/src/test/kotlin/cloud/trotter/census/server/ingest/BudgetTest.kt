package cloud.trotter.census.server.ingest

import cloud.trotter.census.server.Clock
import cloud.trotter.census.server.db.LedgerRow
import cloud.trotter.census.server.secondsToUtcMidnight
import cloud.trotter.census.server.today
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class BudgetTest {
    @Test
    fun `shared accepted count exhausts at 150 screen plus 150 notification items`() {
        val shared = Budget(clock, BudgetPolicy())
        val row = LedgerRow(accepted = 150 + 150, duplicate = 298)
        assertEquals(0, shared.remainingSkeletons(row))
        assertEquals(BudgetVerdict.BudgetExhausted(60), shared.exceeded(row, 1, true))
    }

    private val clock = object : Clock { override fun now(): Instant = Instant.parse("2026-10-02T23:59:00.500Z") }
    private val budget = Budget(clock, BudgetPolicy(dailySkeletonBudget = 3, dailyBytes = 10, dailyBatches = 2))

    @Test
    fun `UTC reset rounds up and ignores the host time zone`() {
        assertEquals(LocalDate.parse("2026-10-02"), clock.today())
        assertEquals(60, clock.secondsToUtcMidnight())
        assertEquals(86400, object : Clock { override fun now(): Instant = Instant.parse("2026-10-03T00:00:00Z") }.secondsToUtcMidnight())
    }

    @Test
    fun `empty ledger permits work and accepted skeletons alone consume skeleton quota`() {
        assertEquals(3, budget.remainingSkeletons(null))
        assertEquals(2, budget.remainingSkeletons(LedgerRow(accepted = 1, duplicate = 50, rejectedByReason = mapOf("invalid" to 50))))
        assertEquals(0, budget.remainingSkeletons(LedgerRow(accepted = 100)))
        assertEquals(BudgetVerdict.Ok, budget.exceeded(null, 10, true))
        assertEquals(BudgetVerdict.BudgetExhausted(60), budget.exceeded(LedgerRow(accepted = 3), 0, false))
    }

    @Test
    fun `bytes and new batch limits handle equality duplicates and overflow`() {
        val row = LedgerRow(bytes = 9, batchIds = listOf("a", "b"))
        assertEquals(BudgetVerdict.Ok, budget.exceeded(row, 1, false))
        assertEquals(BudgetVerdict.BudgetExhausted(60), budget.exceeded(row, 1, true))
        assertEquals(BudgetVerdict.BudgetExhausted(60), budget.exceeded(row, 2, false))
        assertEquals(BudgetVerdict.BudgetExhausted(60), budget.exceeded(row, Long.MAX_VALUE, false))
    }
}
