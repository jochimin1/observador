package com.ubers.observador.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OfferEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun offerDao(): OfferDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE detected_offers ADD COLUMN offer_hash TEXT NOT NULL DEFAULT ''"
                )
                // Populate hash using same format as UberOffer.offerHash()
                database.execSQL(
                    "UPDATE detected_offers SET offer_hash = " +
                    "printf('%.2f-%.1f-%d-%.1f-%d', price, pickupKm, pickupMin, tripKm, tripMin)"
                )
                // Remove duplicates keeping the earliest record per hash
                database.execSQL(
                    """DELETE FROM detected_offers
                       WHERE id NOT IN (
                           SELECT MIN(id) FROM detected_offers GROUP BY offer_hash
                       )"""
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_detected_offers_offer_hash " +
                    "ON detected_offers (offer_hash)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Hash is no longer globally unique: the same offer can repeat on another day.
                // Deduplication is now time-window based (see OfferDao.insertIfNotRecent)
                database.execSQL("DROP INDEX IF EXISTS index_detected_offers_offer_hash")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_detected_offers_offer_hash " +
                    "ON detected_offers (offer_hash)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_detected_offers_detectedAt " +
                    "ON detected_offers (detectedAt)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // One-time cleanup of duplicates saved before time-window dedup existed.
                // Keeps the earliest row per offer_hash within each 10-minute bucket, so an
                // identical offer on another day or hours later is never touched.
                database.execSQL(
                    """DELETE FROM detected_offers
                       WHERE id NOT IN (
                           SELECT MIN(id) FROM detected_offers
                           GROUP BY offer_hash, detectedAt / 600000
                       )"""
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "observador.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { INSTANCE = it }
            }
    }
}
