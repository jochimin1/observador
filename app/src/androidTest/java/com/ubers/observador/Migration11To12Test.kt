package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.action
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8J migration on a real database: offers from DB 11 come through 11 -> 12 intact with the
 * new card button empty, validated against schemas/12.json; the chain 4 -> 12 opens.
 */
@RunWith(AndroidJUnit4::class)
class Migration11To12Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun offersSurviveWithAnEmptyButton() {
        helper.createDatabase(DB, 11).apply {
            execSQL(INSERT_OFFER)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 12, true, AppDatabase.MIGRATION_11_12)
        db.query("SELECT card_action, pickup_address, price FROM detected_offers").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(0))
            assertEquals("Los Ríos", c.getString(1))
            assertEquals(50.01, c.getDouble(2), 0.0)
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
                AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13
            )
            .build()
        val offers = room.offerDao().getSince(0)
        assertEquals(1, offers.size)
        assertNull(offers.single().action)
        room.close()
    }

    private companion object {
        const val DB = "migration-11-12-test.db"
        const val CHAIN_DB = "migration-4-12-test.db"
        const val INSERT_OFFER =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score, pickup_address, dropoff_address) " +
                "VALUES ('50.01-1.9-5-2.2-6', 1000, 50.01, 1.9, 5, 2.2, 6, 4.1, 11, 12.2, 272.8, 6.0, 44.0, 55, " +
                "'Los Ríos', 'Avenida John F. Kennedy, Los Prados')"
        const val INSERT_OFFER_V4 =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score) " +
                "VALUES ('136.06-4.1-7-7.2-11', 1000, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76)"
    }
}
