package com.ubers.observador.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 5 (Fase 7B) adds `trips` and `ride_events`; `detected_offers` is untouched. Schemas are
 * exported to app/schemas from version 5 on (4.json was rebuilt from the generated v4 code) so
 * MigrationTestHelper can check every step. Never fallbackToDestructiveMigration: a failed
 * migration rolls back and keeps the data.
 */
@Database(
    entities = [OfferEntity::class, TripEntity::class, RideEventEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun offerDao(): OfferDao

    abstract fun tripDao(): TripDao

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

        /**
         * New tables only. The SQL is exactly what Room generates for TripEntity and
         * RideEventEntity, so Room's schema check after the migration passes.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trips` (`id` TEXT NOT NULL, `lifecycle_session_id` TEXT NOT NULL, " +
                        "`related_offer_hash` TEXT, `status` TEXT NOT NULL, `recovered` INTEGER NOT NULL, " +
                        "`started_from_known_offer` INTEGER NOT NULL, `accepted_at` INTEGER, " +
                        "`pickup_navigation_at` INTEGER, `arrived_at` INTEGER, `trip_started_at` INTEGER, " +
                        "`completed_at` INTEGER, `cancelled_at` INTEGER, `interrupted_at` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_event_id` TEXT, " +
                        "`has_anomalies` INTEGER NOT NULL, `offer_seen_at` INTEGER, `offered_amount` REAL, " +
                        "`estimated_pickup_km` REAL, `estimated_pickup_minutes` INTEGER, `estimated_trip_km` REAL, " +
                        "`estimated_trip_minutes` INTEGER, `score_at_detection` INTEGER, " +
                        "`setting_min_rdp_per_km` REAL, `setting_min_rdp_per_hour` REAL, " +
                        "`setting_max_pickup_km` REAL, `setting_max_pickup_minutes` INTEGER, " +
                        "`setting_good_from` INTEGER, `setting_excellent_from` INTEGER, PRIMARY KEY(`id`))"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_trips_lifecycle_session_id` ON `trips` (`lifecycle_session_id`)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_status` ON `trips` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_created_at` ON `trips` (`created_at`)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ride_events` (`database_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`event_id` TEXT NOT NULL, `trip_id` TEXT NOT NULL, `lifecycle_session_id` TEXT NOT NULL, " +
                        "`event_type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, " +
                        "`screen_type` TEXT, `confidence` REAL, `recovered` INTEGER NOT NULL, " +
                        "`previous_status` TEXT, `resulting_status` TEXT, `evidence_codes` TEXT NOT NULL, " +
                        "`anomaly_code` TEXT, FOREIGN KEY(`trip_id`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ride_events_event_id` ON `ride_events` (`event_id`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ride_events_trip_id_sequence` " +
                        "ON `ride_events` (`trip_id`, `sequence`)"
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { INSTANCE = it }
            }
    }
}
