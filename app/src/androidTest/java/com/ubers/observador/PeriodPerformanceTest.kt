package com.ubers.observador

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.*
import com.ubers.observador.domain.earnings.*
import com.ubers.observador.domain.operating.*
import com.ubers.observador.domain.trips.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.io.File

/** Reproducible 10,000-row comparison on the QA emulator, never the driver's database. */
class PeriodPerformanceTest {
    @Test fun boundedQueriesPreserveResultsAndReduceMaterializedRows() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val clock = WorkdayClock(4, ZoneId.of("UTC"))
            val day = LocalDate.of(2026, 11, 14)
            val start = clock.startOf(day)
            val end = start + 86_400_000
            db.withTransaction {
                repeat(10_000) { i ->
                    val at = start + (i - 9_980) * 3_600_000L
                    db.distanceDao().upsertSegment(DistanceSegment("segment-$i", clock.workdayOf(at), at, at + 3_600_000,
                        at + 3_600_000, SegmentOrigin.AUTO_ONLINE, measuredMm = 1_000_000, emptyMm = 1_000_000, fixesUsed = 2, createdAt = at).toEntity())
                    db.expenseDao().upsertExpense(ExpenseEntryEntity("expense-$i", at, "OTHER", 100, "DOP", null, at, at))
                }
                // A segment crossing midnight and an open segment updated in the selected interval.
                db.distanceDao().upsertSegment(DistanceSegment("cross", day.minusDays(1), start - 10, start + 10, start + 10,
                    SegmentOrigin.AUTO_ONLINE, emptyMm = 20, createdAt = start - 10).toEntity())
                db.distanceDao().upsertSegment(DistanceSegment("open", day, start + 1, null, start + 10,
                    SegmentOrigin.AUTO_ONLINE, emptyMm = 20, createdAt = start + 1).toEntity())
            }
            val all = db.distanceDao().getAllSegments()
            val bounded = db.distanceDao().getSegmentsTouching(start, end)
            assertEquals(all.filter { it.startedAt < end && (it.endedAt ?: it.updatedAt) > start }.map { it.id }.toSet(), bounded.map { it.id }.toSet())
            val expenses = db.expenseDao().getExpenses()
            val paid = db.expenseDao().getExpensesBetween(start, end)
            assertEquals(expenses.filter { it.at >= start && it.at < end }, paid)
            assertTrue(bounded.size < 30)
            assertTrue(paid.size < 30)
            val window = EarningsWindow(EarningsRange.DAY, 0, day, day, start, end)
            val model = OperatingCostModel("m", 1, day.toString(), start, start, "UTC", 4, 30000, 100000, 100, 0, 0, FixedCostBasis.WORKDAY)
            val income = EarningsReports.build(window, clock, emptyList(), emptyMap(), emptyList()).summary
            fun result(segments: List<DistanceSegmentEntity>, costs: List<ExpenseEntryEntity>) = OperatingCosts.report(window, clock, end, listOf(model),
                emptyList(), emptyMap(), emptyMap(), segments.map { it.toDomain() }, emptyList(), costs.map { it.toDomain() }, income)
            assertEquals(result(all, expenses), result(bounded, paid))
            suspend fun sample(full: Boolean): Long {
                val t = System.nanoTime()
                if (full) { db.distanceDao().getAllSegments(); db.expenseDao().getExpenses() }
                else { db.distanceDao().getSegmentsTouching(start, end); db.expenseDao().getExpensesBetween(start, end) }
                return System.nanoTime() - t
            }
            sample(true); sample(false)
            val old = mutableListOf<Long>(); val new = mutableListOf<Long>()
            repeat(7) { old += sample(true); new += sample(false) }
            val report = "rows_old=${all.size + expenses.size}; rows_new=${bounded.size + paid.size}; samples=7; old_median_ms=${old.sorted()[3] / 1_000_000.0}; new_median_ms=${new.sorted()[3] / 1_000_000.0}; same_operating_report=true"
            Log.i("PeriodBenchmark", report)
            File(context.filesDir, "performance-1.51.0.txt").writeText(report)
            // Timings are evidence, not a flaky pass/fail threshold on shared hardware.
        } finally { db.close() }
    }

    @Test fun closureCandidatesKeepStaleOpenAndExactBoundarySemantics() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val old = Trip("old-open", "old-open", null, TripStatus.UNKNOWN, true, false, createdAt = 1, updatedAt = 2)
            val end = old.copy(id = "end", lifecycleSessionId = "end", status = TripStatus.COMPLETED, completedAt = 100, updatedAt = 100)
            val before = end.copy(id = "before", lifecycleSessionId = "before", completedAt = 99)
            val future = old.copy(id = "future", lifecycleSessionId = "future", createdAt = 200)
            listOf(old, end, before, future).forEach { db.tripDao().insertTrip(it.toEntity()) }
            assertEquals(setOf("old-open", "end"), db.tripDao().getClosureCandidates(100, 200).map { it.id }.toSet())
        } finally { db.close() }
    }
}
