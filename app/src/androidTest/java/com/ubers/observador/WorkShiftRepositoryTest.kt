package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.VehicleRepository
import com.ubers.observador.data.WorkShiftRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.expenses.ShiftCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 1.46.0: work shifts. Emulator only; never run against the driver's phone. */
@RunWith(AndroidJUnit4::class)
class WorkShiftRepositoryTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repo = WorkShiftRepository(db)
    private val h = 3_600_000L
    private val t0 = 1_800_000_000_000L

    @After fun close() = db.close()

    @Test fun migration19To20KeepsOdometerAndCreatesEmptyShifts() {
        val name = "shifts-migrate-${UUID.randomUUID()}"
        helper.createDatabase(name, 19).apply {
            execSQL("INSERT INTO odometer_readings VALUES ('odo',2000,12100,0,2000,2000,'vehicle-initial')")
            close()
        }
        helper.runMigrationsAndValidate(name, 20, true, AppDatabase.MIGRATION_19_20).use { sql ->
            sql.query("SELECT km FROM odometer_readings WHERE id='odo'").use { assertTrue(it.moveToFirst()); assertEquals(12100L, it.getLong(0)) }
            sql.query("SELECT COUNT(*) FROM work_shifts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("PRAGMA quick_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
        }
    }

    @Test fun startFinishWritesOdometerReadingsAndMeasures() = runBlocking {
        assertNull(repo.start(1000, t0, false, t0))
        assertEquals("Ya hay una jornada abierta. Termínala antes de iniciar otra.", repo.start(1001, t0 + h, false, t0 + h))
        val open = repo.all().single()
        assertTrue(open.isOpen)
        assertEquals(ShiftCheck.Ok, repo.finish(open.id, 1120, t0 + 8 * h, t0 + 8 * h, confirmed = false))
        val closed = repo.all().single()
        assertEquals(120L, closed.uberKm)
        // Both readings are ordinary odometer rows
        assertEquals(listOf(1000L, 1120L), db.expenseDao().getOdometer().map { it.km })
        assertTrue(repo.isShiftReading(closed.start.id))
    }

    @Test fun longShiftNeedsConfirmationAndLowerEndIsRejected() = runBlocking {
        assertNull(repo.start(1000, t0, false, t0))
        val id = repo.all().single().id
        assertTrue(repo.finish(id, 900, t0 + h, t0 + h, false) is ShiftCheck.Rejected)
        assertTrue(repo.finish(id, 1700, t0 + h, t0 + h, false) is ShiftCheck.Confirm)
        assertTrue(repo.all().single().isOpen)
        assertEquals(ShiftCheck.Ok, repo.finish(id, 1700, t0 + h, t0 + h, true))
    }

    @Test fun deleteAndUndoRestoreShiftAndReadings() = runBlocking {
        assertNull(repo.start(1000, t0, false, t0))
        val id = repo.all().single().id
        repo.finish(id, 1100, t0 + h, t0 + h, false)
        val deleted = repo.delete(id)!!
        assertTrue(repo.all().isEmpty())
        assertTrue(db.expenseDao().getOdometer().isEmpty())
        repo.restore(deleted)
        assertEquals(100L, repo.all().single().uberKm)
        assertEquals(2, db.expenseDao().getOdometer().size)
    }

    @Test fun motorbikeCannotChangeWithAnOpenShift() = runBlocking {
        val vehicles = VehicleRepository(db)
        assertNull(vehicles.save("Mi moto", 100, false, t0 - h))
        assertNull(repo.start(1000, t0, false, t0))
        assertEquals("Termina la jornada abierta antes de cambiar de moto.", vehicles.save("Otra", 100, true, t0 + h))
    }

    @Test fun editStartKeepsTheSameReading() = runBlocking {
        assertNull(repo.start(1000, t0, false, t0))
        val s = repo.all().single()
        assertNull(repo.editStart(s.id, 1005, t0, false, t0 + h))
        val edited = repo.all().single()
        assertEquals(s.start.id, edited.start.id)
        assertEquals(1005L, edited.start.km)
        assertEquals(t0 + h, edited.start.updatedAt)
    }
}
