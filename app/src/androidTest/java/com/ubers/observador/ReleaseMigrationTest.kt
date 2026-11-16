package com.ubers.observador

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.ubers.observador.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Validate the complete exported schema at every supported starting version, with a retained row. */
class ReleaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun everyExportedSchemaMigratesTo24WithoutLosingTheOffer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (start in 4..23) {
            val name = "release-migration-$start-${UUID.randomUUID()}"
            try {
                helper.createDatabase(name, start).use { db ->
                    db.execSQL("INSERT INTO detected_offers (offer_hash,detectedAt,price,pickupKm,pickupMin,tripKm,tripMin,totalKm,totalMin,rdpPerKm,rdpPerHour,fuelCost,netProfit,score) VALUES ('release-retained',1000,125.5,1.0,2,4.0,8,5.0,10,25.1,753.0,10.5,115.0,90)")
                }
                helper.runMigrationsAndValidate(name, 24, true, *releaseTestMigrations).use { db ->
                    db.query("SELECT price,offer_hash FROM detected_offers").use { row ->
                        assertTrue("retained offer from schema $start", row.moveToFirst())
                        assertEquals(125.5, row.getDouble(0), 0.0)
                        assertEquals("release-retained", row.getString(1))
                        assertEquals(1, row.count)
                    }
                    db.query("PRAGMA quick_check").use { row -> row.moveToFirst(); assertEquals("ok", row.getString(0)) }
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
