package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8E migration on a real database: an 8A database comes through 8 -> 9 intact, validated
 * against schemas/9.json; the new tables have no column that could hold a position; and the
 * chain 4 -> 9 opens with Room.
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everythingFrom8ASurvivesAndTheNewTablesMatchTheSchema() {
        helper.createDatabase(DB, 8).apply {
            execSQL(INSERT_OFFER)
            execSQL(INSERT_TRIP)
            execSQL(INSERT_EVENT)
            execSQL(INSERT_OBSERVATION)
            execSQL(INSERT_SNAPSHOT)
            execSQL(INSERT_VERSION)
            execSQL(INSERT_SESSION)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 9, true, AppDatabase.MIGRATION_8_9)
        assertEquals(1, count(db, "detected_offers"))
        assertEquals(1, count(db, "trips"))
        assertEquals(1, count(db, "online_sessions"))
        assertEquals(0, count(db, "distance_segments"))
        assertEquals(0, count(db, "trip_distances"))
        listOf("distance_segments", "trip_distances").forEach { table ->
            db.query("PRAGMA table_info(`$table`)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name")).lowercase()
                    assertFalse("$table.$name", listOf("lat", "lon", "lng", "coord", "address", "route").any { name.contains(it) })
                }
            }
        }
        // A trip's kilometres go with the trip
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO trip_distances (trip_id, pickup_mm, trip_mm, gap_mm, coverage, updated_at) VALUES ('trip-1', 1, 2, 0, 'FULL', 1)")
        db.execSQL("DELETE FROM trips WHERE id = 'trip-1'")
        assertEquals(0, count(db, "trip_distances"))
        db.close()
    }

    @Test
    fun theChainFrom4Opens() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).apply {
            execSQL(INSERT_OFFER)
            close()
        }
        helper.runMigrationsAndValidate(
            CHAIN_DB, 9, true,
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9
        ).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9)
            .build()
        assertEquals(1, room.offerDao().getSince(0).size)
        assertTrue(room.distanceDao().getOpenSegments().isEmpty())
        room.close()
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val DB = "migration-8-9-test.db"
        const val CHAIN_DB = "migration-4-9-test.db"
        const val INSERT_OFFER =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score) " +
                "VALUES ('136.06-4.1-7-7.2-11', 1000, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76)"
        const val INSERT_TRIP =
            "INSERT INTO trips (id, lifecycle_session_id, related_offer_hash, status, recovered, started_from_known_offer, " +
                "accepted_at, completed_at, created_at, updated_at, has_anomalies, offer_seen_at, offered_amount, " +
                "estimated_pickup_km, estimated_pickup_minutes, estimated_trip_km, estimated_trip_minutes, score_at_detection, " +
                "setting_min_rdp_per_km, setting_min_rdp_per_hour, setting_max_pickup_km, setting_max_pickup_minutes, " +
                "setting_good_from, setting_excellent_from, setting_weight_km_pct, setting_weight_hour_pct, " +
                "setting_weight_pickup_km_pct, setting_weight_pickup_min_pct) " +
                "VALUES ('trip-1', 'session-1', '136.06-4.1-7-7.2-11', 'COMPLETED', 0, 1, 1000, 5000, 1000, 5000, 0, 900, 136.06, " +
                "4.1, 7, 7.2, 11, 76, 6.0, 300.0, 6.0, 12, 60, 80, 40, 30, 15, 15)"
        const val INSERT_EVENT =
            "INSERT INTO ride_events (event_id, trip_id, lifecycle_session_id, event_type, timestamp, sequence, recovered, evidence_codes) " +
                "VALUES ('event-1', 'trip-1', 'session-1', 'TRIP_COMPLETED', 5000, 1, 0, '')"
        const val INSERT_OBSERVATION =
            "INSERT INTO financial_observations (observation_id, trip_id, lifecycle_session_id, observed_at, type, semantics, " +
                "amount_minor, currency_code, scope, confidence, source, screen_type, evidence_codes, link_confidence, created_at) " +
                "VALUES ('obs-1', 'trip-1', NULL, 6000, 'TRIP_TOTAL', 'ABSOLUTE_TOTAL', 14150, 'DOP', 'TRIP', 0.95, 'TEST', NULL, '', 'HIGH', 6000)"
        const val INSERT_SNAPSHOT =
            "INSERT INTO platform_earnings_snapshots (snapshot_id, captured_at, period_type, period_start, period_end, amount_minor, " +
                "currency_code, confidence, screen_type, evidence_codes) " +
                "VALUES ('snap-1', 7000, 'DAY', NULL, NULL, 206995, 'DOP', 0.9, 'UBER_TODAY_PILL', 'PROVISIONAL_SIGNATURE')"
        const val INSERT_VERSION =
            "INSERT INTO profit_model_versions (id, number, created_at, source, algorithm_version, min_rdp_per_km, min_rdp_per_hour, " +
                "max_pickup_km, max_pickup_minutes, weight_km_pct, weight_hour_pct, weight_pickup_km_pct, weight_pickup_min_pct, " +
                "good_from, excellent_from, note) " +
                "VALUES ('v1', 1, 1000, 'DEFAULT', 'PROFIT_ENGINE_V1', 6.0, 300.0, 6.0, 12, 35, 30, 20, 15, 60, 80, NULL)"
        const val INSERT_SESSION =
            "INSERT INTO online_sessions (id, started_at, start_kind, last_online_seen_at, ended_at, possible_end_at, end_reason, " +
                "unobserved_ms, gaps, anomaly_codes, created_at, updated_at) " +
                "VALUES ('s1', 1000, 'EXACT', 5000, 6000, 6000, 'WENT_OFFLINE', 0, '', '', 1000, 6000)"
    }
}
