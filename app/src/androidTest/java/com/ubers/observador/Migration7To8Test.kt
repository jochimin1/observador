package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineStartKind
import com.ubers.observador.domain.online.TimeSpan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8A migration on a real database: a 7D database (offers, trips, events, money, versions)
 * comes through 7 -> 8 intact, validated against schemas/8.json; and the whole chain 4 -> 8
 * opens with Room and stores a session.
 */
@RunWith(AndroidJUnit4::class)
class Migration7To8Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everythingFrom7DSurvivesAndTheNewTableMatchesTheSchema() {
        helper.createDatabase(DB, 7).apply {
            execSQL(INSERT_OFFER)
            execSQL(INSERT_TRIP)
            execSQL(INSERT_EVENT)
            execSQL(INSERT_OBSERVATION)
            execSQL(INSERT_SNAPSHOT)
            execSQL(INSERT_VERSION)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 8, true, AppDatabase.MIGRATION_7_8)
        assertEquals(1, count(db, "detected_offers"))
        assertEquals(1, count(db, "trips"))
        assertEquals(1, count(db, "ride_events"))
        assertEquals(1, count(db, "financial_observations"))
        assertEquals(1, count(db, "platform_earnings_snapshots"))
        assertEquals(1, count(db, "profit_model_versions"))
        assertEquals(0, count(db, "online_sessions"))
        val indexes = db.query("PRAGMA index_list(`online_sessions`)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        assertTrue(indexes.containsAll(listOf("index_online_sessions_started_at", "index_online_sessions_ended_at")))
        db.close()
    }

    @Test
    fun theChainFrom4OpensAndStoresASession() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).apply {
            execSQL(INSERT_OFFER)
            close()
        }
        helper.runMigrationsAndValidate(
            CHAIN_DB, 8, true,
            AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8
        ).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .build()
        assertEquals(1, room.offerDao().getSince(0).size)
        val session = OnlineSession(
            "s1", 1_000, OnlineStartKind.EXACT, 5_000, gaps = listOf(TimeSpan(2_000, 3_000)), unobservedMs = 1_000,
            createdAt = 1_000, updatedAt = 5_000
        )
        room.onlineDao().upsert(session.toEntity())
        assertEquals(session, room.onlineDao().getOpen().single().toDomain())
        room.close()
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val DB = "migration-7-8-test.db"
        const val CHAIN_DB = "migration-4-8-test.db"
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
    }
}
