package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.ClosureRepository
import com.ubers.observador.data.db.*
import com.ubers.observador.data.export.ClosurePdf
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.*
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.*
import com.ubers.observador.domain.distance.*
import com.ubers.observador.domain.online.*
import com.ubers.observador.domain.operating.*
import com.ubers.observador.data.OperatingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Run only on the dedicated QA emulator. */
class ClosureRepositoryTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repo = ClosureRepository(db)
    private val clock = WorkdayClock(4, ZoneId.of("America/Santo_Domingo"))
    private val day = LocalDate.of(2026, 11, 12)
    private val start = clock.startOf(day)
    private val end = clock.startOf(day.plusDays(1))
    private val window = EarningsWindow(EarningsRange.DAY, 0, day, day, start, end)
    @After fun close() = db.close()

    @Test fun migrationPreservesExistingData() {
        val name = "closure-${UUID.randomUUID()}"
        helper.createDatabase(name, 20).apply {
            execSQL("INSERT INTO odometer_readings VALUES ('odo',2000,12100,0,2000,2000,'vehicle-initial')")
            close()
        }
        helper.runMigrationsAndValidate(name, 21, true, AppDatabase.MIGRATION_20_21).use { sql ->
            sql.query("SELECT km FROM odometer_readings WHERE id='odo'").use { assertTrue(it.moveToFirst()); assertEquals(12100, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM report_closures").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    private suspend fun trip(id: String, completed: Long, amount: Long) {
        val t = Trip(id, id, null, TripStatus.COMPLETED, false, false,
            acceptedAt = start - 3_600_000, completedAt = completed, createdAt = start - 3_600_000, updatedAt = completed, riderName = "Ana Pérez")
        db.tripDao().insertTrip(t.toEntity())
        db.financialDao().upsertSummary(TripFinancialSummary(id, "DOP", null, MoneyAmount(amount, "DOP"), MoneyAmount(amount, "DOP"), completeness = FinancialCompleteness.CONFIRMED).toEntity(end + 1))
    }

    @Test fun midnightBoundaryTestPolicyAndLateRevision() = runBlocking {
        trip("inside", start + 1_000, 12345)
        trip("boundary", end, 99999)
        trip("TEST-trip", start + 2_000, 99999)
        val first = repo.close(window, clock, false, null, null, now = end + 1)
        val original = first.payload
        val lines = ClosureRepository.lines(first)
        assertEquals("12345", lines.single { it.label == "Ingresos confirmados" }.value)
        assertTrue(lines.any { it.value == "Ana Pérez" })
        assertNull(lines.single { it.label == "Beneficio operativo" }.value)
        db.financialDao().upsertSummary(TripFinancialSummary("inside", "DOP", null, MoneyAmount(15000, "DOP"), MoneyAmount(15000, "DOP"), completeness = FinancialCompleteness.CONFIRMED).toEntity(end + 1))
        val second = repo.close(window, clock, true, 100L, 100L, first, end + 2)
        assertEquals(2, second.revision)
        assertEquals(first.scope, second.scope)
        assertEquals(first.endMs, second.endMs)
        assertFalse(second.includeTest)
        assertNull(second.profitGoal)
        assertEquals("15000", ClosureRepository.lines(second).single { it.label == "Ingresos confirmados" }.value)
        assertEquals(original, repo.observe().first().single { it.id == first.id }.payload)
        assertTrue(ClosurePdf.render(lines).take(4).toByteArray().toString(Charsets.US_ASCII) == "%PDF")
    }

    @Test fun newRevisionKeepsCutoffEvenAfterNewActivity() = runBlocking {
        val cutoff = start + 10_000
        val first = repo.close(window, clock, false, null, null, now = cutoff)
        trip("later", cutoff + 1, 999)
        val second = repo.close(window, clock, false, null, null, first, end + 1)
        assertEquals(cutoff, second.endMs)
        assertEquals("0", ClosureRepository.lines(second).single { it.label == "Ingresos confirmados" }.value)
    }

    @Test fun crossingTripBlocksOtherwiseCalculableProfitAtHistoricalCutoff() = runBlocking {
        val hour = 3_600_000L
        val operating = OperatingRepository(db)
        assertNull(operating.save(OperatingCostInput("300", "100", "1.5", "0", "40", FixedCostBasis.WORKDAY, "100", day.toString()), clock, start))
        val t = Trip("done", "done", null, TripStatus.COMPLETED, false, false,
            acceptedAt = start + hour, completedAt = start + 2 * hour, createdAt = start + hour, updatedAt = start + 2 * hour)
        db.tripDao().insertTrip(t.toEntity())
        db.financialDao().upsertSummary(TripFinancialSummary(t.id, "DOP", null, MoneyAmount(20000, "DOP"), MoneyAmount(20000, "DOP"), completeness = FinancialCompleteness.CONFIRMED).toEntity(end))
        db.distanceDao().upsertTripDistance(TripDistance(t.id, 2_000_000, 8_000_000, 0, TripDistanceCoverage.FULL, start + 2 * hour).toEntity(t.id))
        db.distanceDao().upsertSegment(DistanceSegment("d", day, start, start + 4 * hour, start + 4 * hour, SegmentOrigin.AUTO_ONLINE,
            measuredMm = 15_000_000, passengerMm = 8_000_000, pickupMm = 2_000_000, emptyMm = 5_000_000, fixesUsed = 100, createdAt = start).toEntity())
        db.onlineDao().upsert(OnlineSession("o", start, OnlineStartKind.EXACT, start + 4 * hour, endedAt = start + 4 * hour, createdAt = start, updatedAt = start + 4 * hour).toEntity())
        val cutoff = start + 5 * hour
        val good = repo.close(window, clock, false, 9000, null, now = cutoff)
        assertEquals("9250", ClosureRepository.lines(good).single { it.label == "Beneficio operativo" }.value)
        db.tripDao().insertTrip(t.copy(id = "crossing", lifecycleSessionId = "crossing", acceptedAt = start + 3 * hour,
            createdAt = start + 3 * hour, completedAt = start + 6 * hour, updatedAt = start + 6 * hour).toEntity())
        val revision = repo.close(window, clock, false, null, null, good, end + 1)
        val lines = ClosureRepository.lines(revision)
        assertNull(lines.single { it.label == "Beneficio operativo" }.value)
        assertEquals("1", lines.single { it.label == "Viajes sin cierre al corte" }.value)
        assertEquals("No evaluable: faltan datos", lines.single { it.label == "Estado de beneficio" }.value)
        assertEquals(good.payload, repo.observe().first().single { it.id == good.id }.payload)
    }
}
