package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.domain.ScoreWeights
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 7D migration on a real database: a 7C database (offers, trips, events, money) comes
 * through 6 -> 7 intact, validated against schemas/7.json; old trips read back with the original
 * weights; and the whole chain 4 -> 7 opens with Room.
 */
@RunWith(AndroidJUnit4::class)
class Migration6To7Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everythingFrom7CSurvivesAndTheNewTablesMatchTheSchema() {
        helper.createDatabase(DB, 6).apply {
            execSQL(INSERT_OFFER)
            execSQL(INSERT_TRIP)
            execSQL(INSERT_EVENT)
            execSQL(INSERT_OBSERVATION)
            execSQL(INSERT_SUMMARY)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 7, true, AppDatabase.MIGRATION_6_7)
        assertEquals(1, count(db, "detected_offers"))
        assertEquals(1, count(db, "trips"))
        assertEquals(1, count(db, "ride_events"))
        assertEquals(1, count(db, "financial_observations"))
        assertEquals(1, count(db, "trip_financial_summaries"))
        assertEquals(0, count(db, "profit_model_versions"))
        assertEquals(0, count(db, "shadow_evaluations"))
        assertEquals(0, count(db, "calibration_runs"))
        db.query("SELECT score_at_detection, setting_min_rdp_per_hour, setting_weight_km_pct FROM trips").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(76, c.getInt(0))
            assertEquals(300.0, c.getDouble(1), 1e-9)
            assertTrue(c.isNull(2))
        }
        db.query("SELECT amount_minor FROM financial_observations").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(14_150L, c.getLong(0))
        }
        val unique = db.query("PRAGMA index_list(`profit_model_versions`)").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(c.getColumnIndexOrThrow("name")), c.getInt(c.getColumnIndexOrThrow("unique")) == 1) }
        }
        assertEquals(true, unique["index_profit_model_versions_number"])
        db.close()
    }

    @Test
    fun oldTripsReadBackWithTheOriginalWeightsAndTheChainFrom4Opens() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).apply {
            execSQL(INSERT_OFFER)
            close()
        }
        helper.runMigrationsAndValidate(CHAIN_DB, 7, true, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7).apply {
            execSQL(INSERT_TRIP)
            close()
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .build()
        assertEquals(1, room.offerDao().getSince(0).size)
        val trip = room.tripDao().getAllTrips().single().toDomain()
        assertEquals(ScoreWeights.DEFAULT, trip.decision!!.weights)
        assertTrue(room.intelligenceDao().getVersions().isEmpty())
        room.close()
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val DB = "migration-6-7-test.db"
        const val CHAIN_DB = "migration-4-7-test.db"
        const val INSERT_OFFER =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score) " +
                "VALUES ('136.06-4.1-7-7.2-11', 1000, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76)"
        const val INSERT_TRIP =
            "INSERT INTO trips (id, lifecycle_session_id, related_offer_hash, status, recovered, started_from_known_offer, " +
                "accepted_at, completed_at, created_at, updated_at, has_anomalies, offer_seen_at, offered_amount, " +
                "estimated_pickup_km, estimated_pickup_minutes, estimated_trip_km, estimated_trip_minutes, score_at_detection, " +
                "setting_min_rdp_per_km, setting_min_rdp_per_hour, setting_max_pickup_km, setting_max_pickup_minutes, " +
                "setting_good_from, setting_excellent_from) " +
                "VALUES ('trip-1', 'session-1', '136.06-4.1-7-7.2-11', 'COMPLETED', 0, 1, 1000, 5000, 1000, 5000, 0, 900, 136.06, " +
                "4.1, 7, 7.2, 11, 76, 6.0, 300.0, 6.0, 12, 60, 80)"
        const val INSERT_EVENT =
            "INSERT INTO ride_events (event_id, trip_id, lifecycle_session_id, event_type, timestamp, sequence, recovered, evidence_codes) " +
                "VALUES ('event-1', 'trip-1', 'session-1', 'TRIP_COMPLETED', 5000, 1, 0, '')"
        const val INSERT_OBSERVATION =
            "INSERT INTO financial_observations (observation_id, trip_id, lifecycle_session_id, observed_at, type, semantics, " +
                "amount_minor, currency_code, scope, confidence, source, screen_type, evidence_codes, link_confidence, created_at) " +
                "VALUES ('obs-1', 'trip-1', NULL, 6000, 'TRIP_TOTAL', 'ABSOLUTE_TOTAL', 14150, 'DOP', 'TRIP', 0.95, 'TEST', NULL, '', 'HIGH', 6000)"
        const val INSERT_SUMMARY =
            "INSERT INTO trip_financial_summaries (trip_id, currency_code, effective_earnings_minor, fare_minor, tip_minor, promotion_minor, " +
                "adjustment_minor, cancellation_fee_minor, other_minor, completeness, anomaly_codes, observation_count, " +
                "pending_review_count, updated_at) VALUES ('trip-1', 'DOP', 14150, 0, 0, 0, 0, 0, 0, 'CONFIRMED', '', 1, 0, 6000)"
    }
}
