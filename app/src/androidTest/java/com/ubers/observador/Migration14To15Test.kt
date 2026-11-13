package com.ubers.observador

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.ReadingAuditEntity
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
 * 1.38.0 on a real database: 14 -> 15 adds the reading columns (old offers explicitly have no
 * trace and stay included), creates the bounded reading audit, and marks a doubtful reading
 * without deleting it. Validated against schemas/15.json.
 */
@RunWith(AndroidJUnit4::class)
class Migration14To15Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun oldOffersKeepTheirNumbersAndHaveNoTrace() {
        helper.createDatabase(DB, 14).apply {
            execSQL(INSERT_OFFER)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 15, true, AppDatabase.MIGRATION_14_15)
        db.query("SELECT price, parser_version, reading_confidence, reading_reasons, excluded_from_analysis FROM detected_offers").use { c ->
            c.moveToFirst()
            assertEquals(136.06, c.getDouble(0), 0.0)
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
            assertEquals("", c.getString(3))
            assertEquals(0, c.getInt(4))
        }
        db.query("SELECT COUNT(*) FROM reading_audits").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()
    }

    @Test
    fun theChainFrom4Opens() = runBlocking {
        helper.createDatabase(CHAIN_DB, 4).close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, CHAIN_DB)
            .addMigrations(
                AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15
            )
            .build()
        assertNull(room.readingAuditDao().observeLatest().first())
        room.close()
    }

    @Test
    fun aDoubtfulReadingIsMarkedNotDeleted() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        room.openHelper.writableDatabase.execSQL(INSERT_OFFER)
        val id = room.offerDao().getSince(0).single().id
        val repo = OfferRepository(room)
        assertTrue(repo.setExcluded(id, true))
        assertEquals(listOf(id), room.offerDao().observeExcludedIds().first())
        assertEquals(1, room.offerDao().getSince(0).size)
        assertTrue(room.offerDao().getSince(0).single().excludedFromAnalysis)
        assertTrue(repo.setExcluded(id, false))
        assertFalse(room.offerDao().getSince(0).single().excludedFromAnalysis)
        assertFalse(repo.setExcluded(id + 999, true))
        room.close()
    }

    @Test
    fun theAuditKeepsResultsAndAttachesLaterStages() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dao = room.readingAuditDao()
        dao.record(ReadingAuditEntity("r1", 1_000, "P", "C", "4.500 (1)", "OFFER", 0.9, false, "", 1, 0))
        dao.record(ReadingAuditEntity("r2", 2_000, "P", "C", null, "UNKNOWN", 0.0, false, "NO_PRICE", 0, 1))
        dao.lifecycle("r1", "OFFER_VISIBLE", "s1", "OFFER_DETECTED")
        dao.financial("r2", "TRIP_EARNINGS", 0.72)
        assertEquals("r2", dao.observeLatest().first()!!.id)
        assertEquals("OFFER_DETECTED", dao.get("r1")!!.eventCodes)
        assertEquals("TRIP_EARNINGS", dao.get("r2")!!.financialType)
        dao.trim(1)
        assertEquals(listOf("r2"), dao.latest(10).map { it.id })
        room.close()
    }

    private companion object {
        const val DB = "migration-14-15-test.db"
        const val CHAIN_DB = "migration-4-15-test.db"
        const val INSERT_OFFER =
            "INSERT INTO detected_offers (offer_hash, detectedAt, price, pickupKm, pickupMin, tripKm, tripMin, " +
                "totalKm, totalMin, rdpPerKm, rdpPerHour, fuelCost, netProfit, score) " +
                "VALUES ('136.06-4.1-7-7.2-11', 1000, 136.06, 4.1, 7, 7.2, 11, 11.3, 18, 12.0, 453.0, 17.0, 119.0, 76)"
    }
}
