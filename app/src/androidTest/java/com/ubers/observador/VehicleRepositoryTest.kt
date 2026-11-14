package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.*
import com.ubers.observador.data.backup.BackupArchive
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.expenses.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class VehicleRepositoryTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun migrationKeepsAmountsOdometersCostVersionsAndGpsWithoutInventingEvidence() {
        val name = "vehicle-migrate-${UUID.randomUUID()}"
        helper.createDatabase(name,17).apply {
            execSQL("INSERT INTO fuel_entries VALUES ('fuel',1000,2000,60000,'DOP',12000,1,1000,1000)")
            execSQL("INSERT INTO expense_entries VALUES ('oil',500,'OIL',40000,'DOP',11000,500,500)")
            execSQL("INSERT INTO odometer_readings VALUES ('odo',2000,12100,0,2000,2000)")
            execSQL("INSERT INTO operating_cost_models VALUES ('cost',1,'2026-11-13',0,0,'UTC',4,30000,100000,100,0,0,'WORKDAY',100,'OPERATING_COST_V1')")
            close()
        }
        val sql = helper.runMigrationsAndValidate(name,18,true,AppDatabase.MIGRATION_17_18)
        try {
            sql.query("SELECT amount_minor,odometer_km,vehicle_id FROM fuel_entries WHERE id='fuel'").use {
                assertTrue(it.moveToFirst()); assertEquals(60000L,it.getLong(0)); assertEquals(12000L,it.getLong(1)); assertEquals(INITIAL_VEHICLE,it.getString(2))
            }
            sql.query("SELECT expected_life_km,expected_life_days FROM expense_entries").use { it.moveToFirst(); assertTrue(it.isNull(0)); assertTrue(it.isNull(1)) }
            sql.query("SELECT name FROM vehicle_profiles").use { it.moveToFirst(); assertEquals("Mi moto",it.getString(0)) }
            sql.query("SELECT COUNT(*) FROM operating_cost_models").use { it.moveToFirst(); assertEquals(1,it.getInt(0)) }
            sql.query("PRAGMA quick_check").use { it.moveToFirst(); assertEquals("ok",it.getString(0)) }
        } finally { sql.close(); context.deleteDatabase(name) }
    }

    @Test fun replacementKeepsHistoryAndRejectsDatesOutsideTheNewVehicle() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val vehicles = VehicleRepository(db)
            val expenses = ExpenseRepository(db)
            val old = FuelEntry("old",100,1000,30000,odometerKm=1000,fullTank=true,createdAt=100,updatedAt=100)
            expenses.saveFuel(old)
            assertNull(vehicles.save("Honda",80,false,200))
            assertNull(vehicles.savePlan(ComponentPlan(INITIAL_VEHICLE,ExpenseType.OIL,2000,90)))
            assertNull(vehicles.save("Yamaha",60,true,1000))
            val care = vehicles.get()
            assertEquals(2,care.vehicles.size)
            assertEquals(1000L,care.vehicles.first().retiredAt)
            assertEquals("Yamaha",care.active.name)
            assertTrue(care.plans.none { it.vehicleId == care.active.id })
            assertTrue(runCatching { expenses.saveFuel(old.copy(id="invalid",vehicleId=care.active.id)) }.isFailure)
            expenses.saveFuel(old.copy(id="new",atMs=1100,vehicleId=care.active.id,odometerKm=10))
            assertEquals(2,expenses.getAll().fuel.size)
            assertEquals(listOf("new"),VehicleCare.scope(expenses.getAll(),care.active).fuel.map { it.id })
            assertEquals(1000L,expenses.getAll().fuel.first().odometerKm)
        } finally { db.close() }
    }

    @Test fun resetAndMaintenanceLifetimePersistTogetherAndUndoRestoresMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val repo = ExpenseRepository(db)
            val e = ExpenseEntry("oil",1000,ExpenseType.OIL,40000,odometerKm=10,createdAt=1000,updatedAt=1000,expectedLifeKm=2000,expectedLifeDays=90)
            repo.saveExpense(e,10)
            val snapshot = repo.getAll()
            assertEquals(e,snapshot.expenses.single())
            assertTrue(snapshot.readings.single().isReset)
            assertEquals(e.vehicleId,snapshot.readings.single().vehicleId)
            repo.delete(DeletedExpense.Expense(e))
            repo.restore(DeletedExpense.Expense(e))
            assertEquals(e,repo.getAll().expenses.single())
        } finally { db.close() }
    }

    @Test fun invalidOrArchivedPlansAndInvalidNamesAreRejected() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val repo = VehicleRepository(db)
            assertNotNull(repo.save("",100,false,100))
            assertNotNull(repo.save("Moto",101,false,100))
            assertNotNull(repo.savePlan(ComponentPlan(INITIAL_VEHICLE,ExpenseType.OIL,-1,90)))
            assertNotNull(repo.savePlan(ComponentPlan(INITIAL_VEHICLE,ExpenseType.OTHER,1000,90)))
            assertNull(repo.save("Moto",50,true,1000))
            assertNotNull(repo.savePlan(ComponentPlan(INITIAL_VEHICLE,ExpenseType.OIL,1000,90)))
            assertTrue(repo.get().plans.isEmpty())
        } finally { db.close() }
    }

    @Test fun activeTripPreventsVehicleReplacement() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            db.openHelper.writableDatabase.execSQL("INSERT INTO trips (id,lifecycle_session_id,status,recovered,started_from_known_offer,created_at,updated_at,has_anomalies) VALUES ('trip','s','ARRIVED_OR_WAITING',0,0,100,100,0)")
            assertNotNull(VehicleRepository(db).save("Nueva",100,true,1000))
            assertEquals(INITIAL_VEHICLE,VehicleRepository(db).get().active.id)
        } finally { db.close() }
    }

    @Test fun futureDatedEntriesMustBeCorrectedBeforeRetiringTheirVehicle() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            ExpenseRepository(db).saveOdometer(OdometerReading("future",2000,1000,createdAt=100,updatedAt=100))
            assertNotNull(VehicleRepository(db).save("Nueva",100,true,1000))
            assertEquals(INITIAL_VEHICLE,VehicleRepository(db).get().active.id)
        } finally { db.close() }
    }

    @Test fun vehiclePlansAndLifetimesSurviveBackupArchiveAndReopening() = runBlocking {
        val name="vehicle-backup-${UUID.randomUUID()}"
        val restored="vehicle-restored-${UUID.randomUUID()}"
        val zip=File(context.cacheDir,"vehicle-${UUID.randomUUID()}.zip")
        try {
            val db=AppDatabase.openFile(context,name)
            val original=try {
                val repo=VehicleRepository(db)
                assertNull(repo.save("Honda CB",80,false,1000))
                assertNull(repo.savePlan(ComponentPlan(INITIAL_VEHICLE,ExpenseType.OIL,2000,90)))
                ExpenseRepository(db).saveExpense(ExpenseEntry("oil",1000,ExpenseType.OIL,40000,odometerKm=1000,createdAt=1000,updatedAt=1000,expectedLifeKm=2000))
                val result=repo.get()
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                result
            } finally { db.close() }
            val manifest=BackupArchive.create(context.getDatabasePath(name),emptyMap(),zip)
            assertEquals(19,manifest.dbVersion)
            assertEquals(1,manifest.rows["vehicle_profiles"])
            val target=context.getDatabasePath(restored)
            ZipFile(zip).use { z -> z.getInputStream(z.getEntry(BackupFormat.ENTRY_DB)).use { s -> target.outputStream().use { s.copyTo(it) } } }
            val reopened=AppDatabase.openFile(context,restored)
            try {
                assertEquals(original,VehicleRepository(reopened).get())
                assertEquals(2000L,ExpenseRepository(reopened).getAll().expenses.single().expectedLifeKm)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name); context.deleteDatabase(restored); zip.delete() }
    }
}
