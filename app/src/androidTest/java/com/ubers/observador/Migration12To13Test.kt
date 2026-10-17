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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8J migration on a real database: offers and trips from DB 12 come through 12 -> 13 intact,
 * with the modality, "Exclusivo" and the trip's card button empty, validated against
 * schemas/13.json; the chain 4 -> 13 opens.
 */
@RunWith(AndroidJUnit4::class)
class Migration12To13Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun offersAndTripsSurviveWithEmptyModality() {
        helper.createDatabase(DB, 12).apply {
            execSQL(INSERT_OFFER)
            execSQL(INSERT_TRIP)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 13, true, AppDatabase.MIGRATION_12_13)
        db.query("SELECT product, exclusive, card_action, price FROM detected_offers").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(0))
            assertEquals(true, c.isNull(1))
            assertEquals("INTEREST", c.getString(2))
            assertEquals(50.01, c.getDouble(3), 0.0)
        }
        db.query("SELECT offer_product, offer_exclusive, offer_card_action, rider_name FROM trips").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(0))
            assertEquals(true, c.isNull(1))
            assertEquals(true, c.isNull(2))
            assertEquals("stefany", c.getString(3))
        }
        db.close()
    }

    @Test
    fun theChainFrom4Opens() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).apply {
            execSQL(INSERT_OFFER_V4)
            close()
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13
            )
            .build()
        val offer = room.offerDao().getSince(0).single()
        assertNull(offer.product)
        assertNull(offer.exclusive)
        room.close()
    }

    private companion object {
        const val DB = "migration-12-13-test.db"
        const val CHAIN_DB = "migration-4-13-test.db"
        const val INSERT_OFFER =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score, pickup_address, dropoff_address, card_action) " +
                "VALUES ('50.01-1.9-5-2.2-6', 1000, 50.01, 1.9, 5, 2.2, 6, 4.1, 11, 12.2, 272.8, 6.0, 44.0, 55, " +
                "'Los Ríos', 'Avenida John F. Kennedy, Los Prados', 'INTEREST')"
        const val INSERT_OFFER_V4 =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score) " +
                "VALUES ('136.06-4.1-7-7.2-11', 1000, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76)"
        const val INSERT_TRIP =
            "INSERT INTO trips (id, lifecycle_session_id, related_offer_hash, status, recovered, started_from_known_offer, " +
                "accepted_at, completed_at, created_at, updated_at, has_anomalies, offer_seen_at, offered_amount, " +
                "estimated_pickup_km, estimated_pickup_minutes, estimated_trip_km, estimated_trip_minutes, score_at_detection, " +
                "setting_min_rdp_per_km, setting_min_rdp_per_hour, setting_max_pickup_km, setting_max_pickup_minutes, " +
                "setting_good_from, setting_excellent_from, setting_weight_km_pct, setting_weight_hour_pct, " +
                "setting_weight_pickup_km_pct, setting_weight_pickup_min_pct, offer_pickup_address, offer_dropoff_address, rider_name) " +
                "VALUES ('trip-1', 'session-1', '50.01-1.9-5-2.2-6', 'COMPLETED', 0, 1, 1000, 5000, 1000, 5000, 0, 900, 50.01, " +
                "1.9, 5, 2.2, 6, 55, 6.0, 300.0, 6.0, 12, 60, 80, 40, 30, 15, 15, 'Los Ríos', 'Los Prados', 'stefany')"
    }
}
