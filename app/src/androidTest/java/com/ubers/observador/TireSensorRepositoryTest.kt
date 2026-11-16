package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.TireSensorRepository
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.expenses.INITIAL_VEHICLE
import com.ubers.observador.domain.vehicle.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** 1.54.0: migration 22→23, sensor association rules and recorded frames kept with their reading. */
class TireSensorRepositoryTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repo = TireSensorRepository(db)
    private val v = INITIAL_VEHICLE
    @After fun close() = db.close()

    @Test fun migrationAddsTheSensorTablesAndKeepsTyres() {
        val name = "sensors-${UUID.randomUUID()}"
        helper.createDatabase(name, 22).apply {
            execSQL("INSERT INTO tire_components (id,vehicle_id,position,brand,model,size,target_pa,target_entered,target_unit,installed_at,installed_odometer_km,removed_at,removed_odometer_km,created_at,updated_at) VALUES ('TEST-t','vehicle-initial','FRONT','Pirelli',NULL,NULL,199948,'29','PSI',1000,NULL,NULL,NULL,1000,1000)")
            close()
        }
        helper.runMigrationsAndValidate(name, 23, true, AppDatabase.MIGRATION_22_23).use { sql ->
            sql.query("SELECT brand FROM tire_components WHERE id='TEST-t'").use { assertTrue(it.moveToFirst()); assertEquals("Pirelli", it.getString(0)) }
            sql.query("SELECT COUNT(*) FROM tire_sensors").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM tire_sensor_frames").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
        context.deleteDatabase(name)
    }

    @Test fun pairCardBindsBothWheelsAndOneSensorNeverGoesOnBoth() = runBlocking {
        assertNull(repo.bindPair(v, "13ae32", "23a434", 1_000))
        var b = repo.observeBindings().first()
        assertEquals("13ae32", SensorBindings.active(b, v, WheelPosition.FRONT)!!.sensorId)
        assertEquals("23a434", SensorBindings.active(b, v, WheelPosition.REAR)!!.sensorId)
        assertNotNull(repo.bind(v, WheelPosition.FRONT, "23A434", 2_000))
        // Swapping with the card is allowed and keeps the history
        assertNull(repo.bindPair(v, "23a434", "13ae32", 3_000))
        b = repo.observeBindings().first()
        assertEquals("23a434", SensorBindings.active(b, v, WheelPosition.FRONT)!!.sensorId)
        assertEquals(4, b.size)
        repo.unbind(v, WheelPosition.REAR, 4_000)
        assertNull(SensorBindings.active(repo.observeBindings().first(), v, WheelPosition.REAR))
    }

    @Test fun framesAreKeptRawWithTheirProvisionalReading() = runBlocking {
        // A real advertisement of the rear sensor (2026-11-15)
        val hex = "0201050303b0fb13ff000181eaca23a434e19a04000f0d000064000d0954504d53325f323341343334"
        val frame = BleFrame(5_000, "AA:BB", "TPMS2_23A434", -55, Hex.bytes(hex)!!)
        val other = BleFrame(5_100, "CC:DD", null, -80, Hex.bytes("0201060303aafe")!!)
        repo.saveFrames("TEST-session", listOf(frame, other), setOf("23a434"))
        val all = repo.frames()
        assertEquals(2, all.size)
        val mine = all.single { it.address == "AA:BB" }
        assertEquals("23a434", mine.sensorId)
        assertEquals(hex, mine.rawHex)
        assertEquals(301_793, mine.pressurePa)
        assertEquals(3_343, mine.temperatureCenti)
        assertEquals(TpmsParser.VERSION, mine.parserVersion)
        val stranger = all.single { it.address == "CC:DD" }
        assertNull(stranger.sensorId)
        assertNotNull(stranger.problem)
        assertEquals("23a434", repo.observeLatest().first().single().sensorId)
    }
}
