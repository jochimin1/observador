package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.finance.AmountSemantics.*
import com.ubers.observador.domain.finance.FinancialObservationType.*
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room ledger: never writes to the driver's application database. */
@RunWith(AndroidJUnit4::class)
class ManualMoneyRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: FinancialRepository
    private val trip = Trip("t", "s", null, TripStatus.COMPLETED, false, false,
        completedAt = 3_000, createdAt = 1_000, updatedAt = 3_000)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build(); repo = FinancialRepository(db) }
    @After fun close() = db.close()
    private fun edit(id: String, type: FinancialObservationType, semantics: AmountSemantics, value: String, replace: String? = null) = ManualMoneyRequest(id, type, semantics, value, replace)
    private suspend fun total() = repo.getStoredSummary("t")?.effectiveEarnings?.minorUnits

    @Test fun retriesCorrectionsAndUndoAreAppendOnlyAndRebuildable() = runBlocking {
        db.tripDao().insertTrip(trip.toEntity())
        val first = edit("first", TRIP_TOTAL, ABSOLUTE_TOTAL, "100")
        assertTrue(repo.recordManualMoney("t", first, 4_000) is MoneyEditResult.Saved)
        assertTrue(repo.recordManualMoney("t", first, 4_000) is MoneyEditResult.Saved)
        assertEquals(1, repo.tripObservations("t").size)
        repo.recordManualMoney("t", edit("tip", TIP, DELTA, "20"), 5_000)
        assertEquals(12_000L, total())
        val original = repo.tripObservations("t").first { it.type == TRIP_TOTAL }
        repo.recordManualMoney("t", edit("correct", TRIP_TOTAL, ABSOLUTE_TOTAL, "110", original.observationId), 6_000)
        assertEquals(11_000L, total())
        assertEquals(4, repo.tripObservations("t").size)
        assertTrue(repo.undoManualMoney("t", "correct", "undo", 7_000))
        assertFalse(repo.undoManualMoney("t", "correct", "again", 8_000))
        assertEquals(12_000L, total())
        assertEquals(6, repo.tripObservations("t").size)
        assertEquals(repo.computeSummary("t").effectiveEarnings, repo.getStoredSummary("t")!!.effectiveEarnings)
        assertEquals(12_000L, repo.periodEarnings(0, 4_000).totalObservedEarnings)
        assertEquals(0L, repo.periodEarnings(4_000, 9_000).totalObservedEarnings)
    }

    @Test fun collectionsAndTheirUndoNeverAddIncome() = runBlocking {
        db.tripDao().insertTrip(trip.toEntity())
        repo.recordManualMoney("t", edit("cash", CASH_COLLECTED, COMPONENT, "100"), 4_000)
        assertNull(total())
        val id = repo.tripObservations("t").single().observationId
        assertTrue(repo.voidManualMoney("t", id, "delete", 5_000))
        assertEquals(0L, MoneyExplanation.collections(repo.tripObservations("t")).cash)
        assertTrue(repo.undoManualMoney("t", "delete", "undo", 6_000))
        assertEquals(10_000L, MoneyExplanation.collections(repo.tripObservations("t")).cash)
        assertNull(total())
    }

    @Test fun staleTargetsAndOpenOrMissingTripsDoNotWriteAnything() = runBlocking {
        db.tripDao().insertTrip(trip.toEntity())
        db.tripDao().insertTrip(trip.copy(id = "open", lifecycleSessionId = "open", status = TripStatus.IN_TRIP, completedAt = null).toEntity())
        val request = edit("one", TRIP_TOTAL, ABSOLUTE_TOTAL, "100")
        assertTrue(repo.recordManualMoney("open", request, 4_000) is MoneyEditResult.Rejected)
        assertTrue(repo.recordManualMoney("absent", request, 4_000) is MoneyEditResult.Rejected)
        assertTrue(repo.recordManualMoney("t", request.copy(replacesId = "missing"), 4_000) is MoneyEditResult.Rejected)
        assertTrue(repo.tripObservations("t").isEmpty())
        repo.recordManualMoney("t", request, 4_000)
        val original = repo.tripObservations("t").single().observationId
        repo.recordManualMoney("t", edit("two", TRIP_TOTAL, ABSOLUTE_TOTAL, "110", original), 5_000)
        assertTrue(repo.recordManualMoney("t", edit("stale", TRIP_TOTAL, ABSOLUTE_TOTAL, "120", original), 6_000) is MoneyEditResult.Rejected)
        assertEquals(11_000L, total())
        assertEquals(3, repo.tripObservations("t").size)
    }

    @Test fun cancellationIncomeUsesCancellationDateAndNotCompletedTripCount() = runBlocking {
        db.tripDao().insertTrip(trip.copy(status = TripStatus.CANCELLED, completedAt = null, cancelledAt = 3_000).toEntity())
        assertEquals(0, repo.periodEarnings(0, 4_000).completedTripCount)
        repo.recordManualMoney("t", edit("fee", CANCELLATION_FEE, COMPONENT, "50"), 7_000)
        assertEquals(0L, repo.periodEarnings(0, 4_000).totalObservedEarnings)
        repo.recordManualMoney("t", edit("total", TRIP_TOTAL, ABSOLUTE_TOTAL, "50"), 8_000)
        val summary = repo.periodEarnings(0, 4_000)
        assertEquals(5_000L, summary.totalObservedEarnings)
        assertEquals(0, summary.completedTripCount)
        assertEquals(1, summary.cancelledIncomeCount)
        assertEquals(0L, repo.periodEarnings(4_000, 9_000).totalObservedEarnings)
    }

    @Test fun otherCurrencyCannotBeSilentlyConvertedByCorrection() = runBlocking {
        db.tripDao().insertTrip(trip.toEntity())
        val usd = FinancialObservation("usd", 4_000, "s", "t", TRIP_TOTAL, ABSOLUTE_TOTAL,
            MoneyAmount(100, "USD"), FinancialScope.TRIP, 1.0, emptyList(), FinancialSource.UBER_SCREEN, null, LinkConfidence.HIGH)
        repo.persistObservation(usd, 4_000)
        assertTrue(repo.recordManualMoney("t", edit("edit", TRIP_TOTAL, ABSOLUTE_TOTAL, "100", "usd"), 5_000) is MoneyEditResult.Rejected)
        assertEquals(1, repo.tripObservations("t").size)
    }
}
