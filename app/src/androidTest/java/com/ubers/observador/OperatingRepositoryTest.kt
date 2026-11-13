package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.*
import com.ubers.observador.data.backup.*
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.distance.*
import com.ubers.observador.domain.earnings.*
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.online.*
import com.ubers.observador.domain.operating.*
import com.ubers.observador.domain.trips.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class OperatingRepositoryTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = LocalDate.of(2026, 11, 13)
    private val clock = WorkdayClock(4, ZoneId.of("UTC"))
    private val start = clock.startOf(day)
    private val hour = 3_600_000L
    private fun input(date: LocalDate = day) = OperatingCostInput("300", "100", "1.5", "0", "40", FixedCostBasis.WORKDAY, "100", date.toString())

    @Test fun migrationAddsOnlyTheModelsAndPreservesExistingRows() {
        val name = "operating-migration-${UUID.randomUUID()}"
        helper.createDatabase(name, 16).apply {
            execSQL("INSERT INTO trips (id,lifecycle_session_id,status,recovered,started_from_known_offer,created_at,updated_at,has_anomalies) VALUES ('t','s','COMPLETED',0,0,1000,2000,0)")
            execSQL("INSERT INTO trip_corrections (id,trip_id,at,field,new_value,origin) VALUES ('c','t',2000,'RIDER_NAME','Ana','DRIVER')")
            close()
        }
        val sql = helper.runMigrationsAndValidate(name, 17, true, AppDatabase.MIGRATION_16_17)
        try {
            sql.query("SELECT status FROM trips WHERE id='t'").use { it.moveToFirst(); assertEquals("COMPLETED", it.getString(0)) }
            sql.query("SELECT new_value FROM trip_corrections WHERE id='c'").use { it.moveToFirst(); assertEquals("Ana", it.getString(0)) }
            sql.query("SELECT COUNT(*) FROM operating_cost_models").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { sql.close(); context.deleteDatabase(name) }
    }

    @Test fun revisionsRejectPastDatesAndNeverOverwriteExistingModels() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = OperatingRepository(db)
            assertNull(repo.save(input(), clock, start+hour))
            assertNotNull(repo.save(input(), clock, start+hour))
            assertNotNull(repo.save(input(day.minusDays(1)), clock, start+hour))
            assertNull(repo.save(input(day.plusDays(1)).copy(fuelPrice = "600"), clock, start+hour))
            val models = db.operatingCostDao().getAll().map { it.toDomain() }
            assertEquals(2, models.size)
            assertEquals(30_000L, OperatingCosts.modelAt(models, start+2*hour)!!.fuelPriceMinor)
            assertEquals(60_000L, OperatingCosts.modelAt(models, clock.startOf(day.plusDays(1)))!!.fuelPriceMinor)
            assertEquals(listOf(1,2), models.map { it.revision })
        } finally { db.close() }
    }

    @Test fun badValuesCannotCreateAProfile() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = OperatingRepository(db)
            listOf(input().copy(kmPerGallon = "0"), input().copy(fuelPrice = "NaN"), input().copy(fixed = "-1"),
                input().copy(looseShare = "4294967297"), input().copy(effectiveDay = "2026-02-30")).forEach {
                assertNotNull(repo.save(it, clock, start+hour))
            }
            assertTrue(db.operatingCostDao().getAll().isEmpty())
        } finally { db.close() }
    }

    @Test fun reportUsesTheLedgerAndFiltersTestRowsConsistently() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = OperatingRepository(db)
            assertNull(repo.save(input(), clock, start))
            val trip = Trip("t", "s", null, TripStatus.COMPLETED, false, false,
                acceptedAt = start+hour, completedAt = start+2*hour, createdAt = start+hour, updatedAt = start+2*hour)
            db.tripDao().insertTrip(trip.toEntity())
            db.tripDao().insertTrip(trip.copy(id = "TEST-t", lifecycleSessionId = "TEST-s").toEntity())
            FinancialRepository(db).recordManualMoney("t", ManualMoneyRequest("income", FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, "200"), start+4*hour)
            db.distanceDao().upsertTripDistance(TripDistance("s", 2_000_000, 8_000_000, 0, TripDistanceCoverage.FULL, start+2*hour).toEntity("t"))
            db.distanceDao().upsertSegment(DistanceSegment("d", day, start, start+4*hour, start+4*hour, SegmentOrigin.AUTO_ONLINE,
                measuredMm = 15_000_000, passengerMm = 8_000_000, pickupMm = 2_000_000, emptyMm = 5_000_000, fixesUsed = 100, createdAt = start).toEntity())
            db.onlineDao().upsert(OnlineSession("o", start, OnlineStartKind.EXACT, start+4*hour, endedAt = start+4*hour, createdAt = start, updatedAt = start+4*hour).toEntity())
            val w = EarningsWindows.of(EarningsRange.DAY, 0, start+5*hour, clock)
            val real = repo.report(w, clock, start+5*hour, false)
            assertTrue(real.issues.toString(), real.issues.isEmpty())
            assertEquals(9_250L, real.profitMinor)
            assertEquals(14_500L, real.trips.single().profitMinor)
            val withTest = repo.report(w, clock, start+5*hour, true)
            assertNull(withTest.profitMinor)
            assertEquals(2, withTest.trips.size)
        } finally { db.close() }
    }

    @Test fun costVersionsSurviveARealBackupArchiveRoundTrip() = runBlocking {
        val name = "operating-backup-${UUID.randomUUID()}.db"
        val restoredName = "operating-restored-${UUID.randomUUID()}.db"
        val zip = File(context.cacheDir, "operating-${UUID.randomUUID()}.zip")
        try {
            val db = AppDatabase.openFile(context, name)
            val original = try {
                assertNull(OperatingRepository(db).save(input(), clock, start))
                val rows = db.operatingCostDao().getAll()
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                rows
            } finally { db.close() }
            val manifest = BackupArchive.create(context.getDatabasePath(name), emptyMap(), zip)
            assertEquals(17, manifest.dbVersion)
            assertEquals(1, manifest.rows["operating_cost_models"])
            val target = context.getDatabasePath(restoredName)
            ZipFile(zip).use { z -> z.getInputStream(z.getEntry(BackupFormat.ENTRY_DB)).use { s -> target.outputStream().use { s.copyTo(it) } } }
            val restored = AppDatabase.openFile(context, restoredName)
            try { assertEquals(original, restored.operatingCostDao().getAll()) } finally { restored.close() }
        } finally { context.deleteDatabase(name); context.deleteDatabase(restoredName); zip.delete() }
    }

    @Test fun expenseReadForReportsKeepsRecurringPayments() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = ExpenseRepository(db)
            val row = RecurringCost("fixed", RecurringType.DATA_PLAN, 1_000, frequency = Frequency.MONTHLY,
                startsOn = day, createdAt = start, updatedAt = start)
            repo.saveRecurring(row)
            assertEquals(listOf(row), repo.getAll().recurring)
        } finally { db.close() }
    }
}
