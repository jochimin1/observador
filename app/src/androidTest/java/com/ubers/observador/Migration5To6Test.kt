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
 * Fase 7C migrations on a real database: 5 -> 6 keeps offers, trips and events intact and adds
 * the money tables exactly as Room expects (validated against schemas/6.json); and the whole
 * chain 4 -> 5 -> 6 works from a 1.6.0 install.
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun offersTripsAndEventsSurviveAndMoneyTablesMatchTheSchema() {
        helper.createDatabase(DB, 5).apply {
            execSQL(INSERT_OFFER)
            execSQL(INSERT_TRIP)
            execSQL(INSERT_EVENT)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 6, true, AppDatabase.MIGRATION_5_6)

        assertEquals(1, count(db, "detected_offers"))
        db.query("SELECT status, offered_amount, completed_at FROM trips WHERE id = 'trip-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("COMPLETED", c.getString(0))
            assertEquals(136.06, c.getDouble(1), 1e-9)
            assertEquals(5_000L, c.getLong(2))
        }
        assertEquals(1, count(db, "ride_events"))
        assertEquals(0, count(db, "financial_observations"))
        assertEquals(0, count(db, "trip_financial_summaries"))
        assertEquals(0, count(db, "platform_earnings_snapshots"))

        val unique = db.query("PRAGMA index_list(`financial_observations`)").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("unique")) == 1) }
        }
        assertEquals(true, unique["index_financial_observations_observation_id"])
        assertEquals(false, unique["index_financial_observations_trip_id"])

        // Money columns are integers (minor units), never REAL
        val types = db.query("PRAGMA table_info(`financial_observations`)").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("type"))) }
        }
        assertEquals("INTEGER", types["amount_minor"])

        // Deleting a trip keeps its money as unlinked and removes its summary
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO financial_observations (observation_id, trip_id, lifecycle_session_id, observed_at, type, semantics, " +
                "amount_minor, currency_code, scope, confidence, source, screen_type, evidence_codes, link_confidence, created_at) " +
                "VALUES ('obs-1', 'trip-1', NULL, 6000, 'TRIP_TOTAL', 'ABSOLUTE_TOTAL', 14150, 'DOP', 'TRIP', 0.95, 'TEST', NULL, '', 'HIGH', 6000)"
        )
        db.execSQL(
            "INSERT INTO trip_financial_summaries (trip_id, fare_minor, tip_minor, promotion_minor, adjustment_minor, " +
                "cancellation_fee_minor, other_minor, completeness, anomaly_codes, observation_count, pending_review_count, updated_at) " +
                "VALUES ('trip-1', 0, 0, 0, 0, 0, 0, 'CONFIRMED', '', 1, 0, 6000)"
        )
        db.execSQL("DELETE FROM trips WHERE id = 'trip-1'")
        db.query("SELECT trip_id FROM financial_observations WHERE observation_id = 'obs-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
        assertEquals(0, count(db, "trip_financial_summaries"))
        db.close()
    }

    @Test
    fun fullChainFrom4OpensWithRoom() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).apply {
            execSQL(INSERT_OFFER)
            close()
        }
        helper.runMigrationsAndValidate(CHAIN_DB, 6, true, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11)
            .build()
        assertEquals(1, room.offerDao().getSince(0).size)
        assertTrue(room.tripDao().getAllTrips().isEmpty())
        assertTrue(room.financialDao().getTripIdsWithObservations().isEmpty())
        room.close()
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val DB = "migration-5-6-test.db"
        const val CHAIN_DB = "migration-4-6-test.db"
        const val INSERT_OFFER =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score) " +
                "VALUES ('136.06-4.1-7-7.2-11', 1000, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76)"
        const val INSERT_TRIP =
            "INSERT INTO trips (id, lifecycle_session_id, related_offer_hash, status, recovered, started_from_known_offer, " +
                "accepted_at, completed_at, created_at, updated_at, has_anomalies, offered_amount) " +
                "VALUES ('trip-1', 'session-1', '136.06-4.1-7-7.2-11', 'COMPLETED', 0, 1, 1000, 5000, 1000, 5000, 0, 136.06)"
        const val INSERT_EVENT =
            "INSERT INTO ride_events (event_id, trip_id, lifecycle_session_id, event_type, timestamp, sequence, recovered, evidence_codes) " +
                "VALUES ('event-1', 'trip-1', 'session-1', 'TRIP_COMPLETED', 5000, 1, 0, '')"
    }
}
