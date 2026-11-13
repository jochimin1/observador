package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.trips.CorrectionField
import com.ubers.observador.domain.trips.CorrectionOrigin
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.40.0 on a real database: 15 -> 16 only adds the trip correction log, and every correction
 * (from Uber's screen or by hand) is recorded and reversible. Validated against schemas/16.json.
 */
@RunWith(AndroidJUnit4::class)
class Migration15To16Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theCorrectionLogIsAddedAndNothingElseChanges() {
        helper.createDatabase(DB, 15).apply {
            execSQL(
                "INSERT INTO trips (id, lifecycle_session_id, status, recovered, started_from_known_offer, created_at, updated_at, has_anomalies) " +
                    "VALUES ('t1', 's1', 'COMPLETED', 0, 0, 1000, 2000, 0)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 16, true, AppDatabase.MIGRATION_15_16)
        db.query("SELECT status FROM trips WHERE id = 't1'").use { c -> c.moveToFirst(); assertEquals("COMPLETED", c.getString(0)) }
        db.query("SELECT COUNT(*) FROM trip_corrections").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    @Test
    fun theChainFrom4Opens() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17
            )
            .build()
        assertTrue(TripRepository(room).observeCorrectedTripIds().first().isEmpty())
        room.close()
    }

    @Test
    fun correctionsAreRecordedAndReversible() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = TripRepository(room)
        val trip = Trip(
            id = "t1", lifecycleSessionId = "s1", relatedOfferHash = null, status = TripStatus.INTERRUPTED,
            recovered = false, startedFromKnownOffer = false, acceptedAt = 1_000, interruptedAt = 600_000,
            createdAt = 1_000, updatedAt = 600_000
        )
        room.tripDao().insertTrip(trip.toEntity())

        // Uber's screen: recorded with its origin
        assertTrue(repo.setRiderName("s1", "Ana", 700_000))
        assertFalse(repo.setRiderName("s1", "Ana", 710_000))
        // The driver's name wins over a later reading
        assertTrue(repo.correctRiderName("t1", "Ana María", 720_000))
        assertFalse(repo.setRiderName("s1", "Ana", 730_000))
        assertEquals("Ana María", room.tripDao().getById("t1")!!.riderName)

        // Status: corrected, then undone
        assertTrue(repo.correctStatus("t1", TripStatus.COMPLETED, 800_000))
        assertEquals("COMPLETED", room.tripDao().getById("t1")!!.status)
        assertFalse(repo.correctStatus("t1", TripStatus.COMPLETED, 810_000))
        assertTrue(repo.undoStatusCorrection("t1", 900_000))
        val back = room.tripDao().getById("t1")!!
        assertEquals("INTERRUPTED", back.status)
        assertNull(back.completedAt)
        assertFalse(repo.undoStatusCorrection("t1", 910_000))

        val log = repo.observeCorrections("t1").first()
        assertEquals(
            listOf(CorrectionOrigin.UBER_SCREEN, CorrectionOrigin.DRIVER, CorrectionOrigin.DRIVER, CorrectionOrigin.DRIVER_UNDO),
            log.map { it.origin }
        )
        assertEquals(listOf(CorrectionField.RIDER_NAME, CorrectionField.RIDER_NAME, CorrectionField.STATUS, CorrectionField.STATUS), log.map { it.field })
        assertEquals("Ana", log[1].oldValue)
        assertEquals(setOf("t1"), repo.observeCorrectedTripIds().first())
        room.close()
    }

    private companion object {
        const val DB = "migration-15-16-test.db"
        const val CHAIN_DB = "migration-4-16-test.db"
    }
}
