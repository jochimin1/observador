package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 4 -> 5 migration on a real database: offers come through unchanged, the two new tables,
 * their indices and nullability are what Room expects (validated against schemas/5.json).
 */
@RunWith(AndroidJUnit4::class)
class Migration4To5Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createVersion4WithOffers() {
        helper.createDatabase(DB, 4).apply {
            execSQL(INSERT_OFFER.format("136.06-4.1-7-7.2-11", 1_000L, 136.06, 76))
            execSQL(INSERT_OFFER.format("98.50-2.0-5-3.4-9", 2_000L, 98.50, 35))
            close()
        }
    }

    @Test
    fun offersSurviveUnchangedAndTheNewTablesMatchTheSchema() {
        createVersion4WithOffers()
        val db = helper.runMigrationsAndValidate(DB, 5, true, AppDatabase.MIGRATION_4_5)

        db.query("SELECT offer_hash, detectedAt, price, score FROM detected_offers ORDER BY detectedAt").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("136.06-4.1-7-7.2-11", c.getString(0))
            assertEquals(1_000L, c.getLong(1))
            assertEquals(136.06, c.getDouble(2), 1e-9)
            assertEquals(76, c.getInt(3))
        }
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertTrue(tables.containsAll(listOf("detected_offers", "trips", "ride_events")))

        val uniqueIndices = db.query("PRAGMA index_list(`trips`)").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("unique")) == 1) }
        }
        assertEquals(true, uniqueIndices["index_trips_lifecycle_session_id"])
        assertEquals(false, uniqueIndices["index_trips_status"])

        val notNull = db.query("PRAGMA table_info(`trips`)").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("notnull")) == 1) }
        }
        assertEquals(true, notNull["status"])
        assertEquals(false, notNull["accepted_at"])
        assertEquals(false, notNull["trip_started_at"])
        assertEquals(false, notNull["related_offer_hash"])
        db.close()
    }

    @Test
    fun theMigratedDatabaseOpensWithRoomAndKeepsEveryOffer() = runBlocking {
        createVersion4WithOffers()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, DB)
            // Room opens at the latest version, so every later step is needed too
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10)
            .build()
        assertEquals(2, room.offerDao().getSince(0).size)
        assertTrue(room.tripDao().getAllTrips().isEmpty())
        room.close()
    }

    private companion object {
        const val DB = "migration-4-5-test.db"
        const val INSERT_OFFER =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score) " +
                "VALUES ('%s', %d, %s, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, %d)"
    }
}
