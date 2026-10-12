package com.ubers.observador.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 7 (Fase 7D) adds `profit_model_versions`, `shadow_evaluations` and `calibration_runs`,
 * and four nullable weight columns to `trips` (null = the original 35/30/20/15).
 * Version 6 (Fase 7C) adds the money tables: `financial_observations`, `trip_financial_summaries`
 * and `platform_earnings_snapshots`. Version 5 (Fase 7B) added `trips` and `ride_events`;
 * `detected_offers` is untouched. Schemas are
 * exported to app/schemas from version 5 on (4.json was rebuilt from the generated v4 code) so
 * MigrationTestHelper can check every step. Never fallbackToDestructiveMigration: a failed
 * migration rolls back and keeps the data.
 */
@Database(
    entities = [
        OfferEntity::class, TripEntity::class, RideEventEntity::class,
        FinancialObservationEntity::class, TripFinancialSummaryEntity::class, PlatformEarningsSnapshotEntity::class,
        ProfitModelVersionEntity::class, ShadowEvaluationEntity::class, CalibrationRunEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun offerDao(): OfferDao

    abstract fun tripDao(): TripDao

    abstract fun financialDao(): FinancialDao

    abstract fun intelligenceDao(): IntelligenceDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE detected_offers ADD COLUMN offer_hash TEXT NOT NULL DEFAULT ''"
                )
                // Populate hash using same format as UberOffer.offerHash()
                db.execSQL(
                    "UPDATE detected_offers SET offer_hash = " +
                    "printf('%.2f-%.1f-%d-%.1f-%d', price, pickupKm, pickupMin, tripKm, tripMin)"
                )
                // Remove duplicates keeping the earliest record per hash
                db.execSQL(
                    """DELETE FROM detected_offers
                       WHERE id NOT IN (
                           SELECT MIN(id) FROM detected_offers GROUP BY offer_hash
                       )"""
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_detected_offers_offer_hash " +
                    "ON detected_offers (offer_hash)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Hash is no longer globally unique: the same offer can repeat on another day.
                // Deduplication is now time-window based (see OfferDao.insertIfNotRecent)
                db.execSQL("DROP INDEX IF EXISTS index_detected_offers_offer_hash")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_detected_offers_offer_hash " +
                    "ON detected_offers (offer_hash)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_detected_offers_detectedAt " +
                    "ON detected_offers (detectedAt)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // One-time cleanup of duplicates saved before time-window dedup existed.
                // Keeps the earliest row per offer_hash within each 10-minute bucket, so an
                // identical offer on another day or hours later is never touched.
                db.execSQL(
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
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_trips_lifecycle_session_id` ON `trips` (`lifecycle_session_id`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_status` ON `trips` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_created_at` ON `trips` (`created_at`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ride_events` (`database_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`event_id` TEXT NOT NULL, `trip_id` TEXT NOT NULL, `lifecycle_session_id` TEXT NOT NULL, " +
                        "`event_type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, " +
                        "`screen_type` TEXT, `confidence` REAL, `recovered` INTEGER NOT NULL, " +
                        "`previous_status` TEXT, `resulting_status` TEXT, `evidence_codes` TEXT NOT NULL, " +
                        "`anomaly_code` TEXT, FOREIGN KEY(`trip_id`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ride_events_event_id` ON `ride_events` (`event_id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ride_events_trip_id_sequence` " +
                        "ON `ride_events` (`trip_id`, `sequence`)"
                )
            }
        }

        /** Money tables only (Fase 7C); offers, trips and their events are untouched. Exact Room SQL. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `financial_observations` (`database_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`observation_id` TEXT NOT NULL, `trip_id` TEXT, `lifecycle_session_id` TEXT, `observed_at` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, `semantics` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL, `currency_code` TEXT NOT NULL, " +
                        "`scope` TEXT NOT NULL, `confidence` REAL NOT NULL, `source` TEXT NOT NULL, `screen_type` TEXT, " +
                        "`evidence_codes` TEXT NOT NULL, `link_confidence` TEXT NOT NULL, `breakdown_group_id` TEXT, " +
                        "`voids_observation_id` TEXT, `created_at` INTEGER NOT NULL, FOREIGN KEY(`trip_id`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_financial_observations_observation_id` ON `financial_observations` (`observation_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_financial_observations_trip_id` ON `financial_observations` (`trip_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_financial_observations_observed_at` ON `financial_observations` (`observed_at`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trip_financial_summaries` (`trip_id` TEXT NOT NULL, `currency_code` TEXT, " +
                        "`offered_amount_minor` INTEGER, `latest_absolute_minor` INTEGER, `effective_earnings_minor` INTEGER, " +
                        "`fare_minor` INTEGER NOT NULL, `tip_minor` INTEGER NOT NULL, `promotion_minor` INTEGER NOT NULL, " +
                        "`adjustment_minor` INTEGER NOT NULL, `cancellation_fee_minor` INTEGER NOT NULL, `other_minor` INTEGER NOT NULL, " +
                        "`completeness` TEXT NOT NULL, `anomaly_codes` TEXT NOT NULL, `observation_count` INTEGER NOT NULL, " +
                        "`pending_review_count` INTEGER NOT NULL, `first_observed_at` INTEGER, `last_observed_at` INTEGER, " +
                        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`trip_id`), FOREIGN KEY(`trip_id`) REFERENCES `trips`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `platform_earnings_snapshots` (`database_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`snapshot_id` TEXT NOT NULL, `captured_at` INTEGER NOT NULL, `period_type` TEXT NOT NULL, " +
                        "`period_start` INTEGER, `period_end` INTEGER, `amount_minor` INTEGER NOT NULL, `currency_code` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, `screen_type` TEXT, `evidence_codes` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_platform_earnings_snapshots_snapshot_id` ON `platform_earnings_snapshots` (`snapshot_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_platform_earnings_snapshots_captured_at` ON `platform_earnings_snapshots` (`captured_at`)"
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `setting_weight_km_pct` INTEGER")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `setting_weight_hour_pct` INTEGER")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `setting_weight_pickup_km_pct` INTEGER")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `setting_weight_pickup_min_pct` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profit_model_versions` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `algorithm_version` TEXT NOT NULL, " +
                        "`min_rdp_per_km` REAL NOT NULL, `min_rdp_per_hour` REAL NOT NULL, `max_pickup_km` REAL NOT NULL, " +
                        "`max_pickup_minutes` INTEGER NOT NULL, `weight_km_pct` INTEGER NOT NULL, `weight_hour_pct` INTEGER NOT NULL, " +
                        "`weight_pickup_km_pct` INTEGER NOT NULL, `weight_pickup_min_pct` INTEGER NOT NULL, `good_from` INTEGER NOT NULL, " +
                        "`excellent_from` INTEGER NOT NULL, `note` TEXT, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_profit_model_versions_number` ON `profit_model_versions` (`number`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shadow_evaluations` (`evaluation_id` TEXT NOT NULL, `offer_hash` TEXT NOT NULL, " +
                        "`evaluated_at` INTEGER NOT NULL, `active_version_id` TEXT NOT NULL, `active_score` INTEGER NOT NULL, " +
                        "`active_category` TEXT NOT NULL, `candidate_version_id` TEXT NOT NULL, `candidate_score` INTEGER NOT NULL, " +
                        "`candidate_category` TEXT NOT NULL, PRIMARY KEY(`evaluation_id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_shadow_evaluations_offer_hash` ON `shadow_evaluations` (`offer_hash`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_shadow_evaluations_candidate_version_id` ON `shadow_evaluations` (`candidate_version_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `calibration_runs` (`run_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`period_start` INTEGER, `period_end` INTEGER NOT NULL, `total_trips` INTEGER NOT NULL, " +
                        "`valid_samples` INTEGER NOT NULL, `training_n` INTEGER NOT NULL, `validation_n` INTEGER NOT NULL, " +
                        "`base_version_id` TEXT, `candidate_version_id` TEXT, `current_training_rho` REAL, " +
                        "`candidate_training_rho` REAL, `current_validation_rho` REAL, `candidate_validation_rho` REAL, " +
                        "`status` TEXT NOT NULL, `flags` TEXT NOT NULL, `analysis_version` TEXT NOT NULL, " +
                        "`algorithm_version` TEXT NOT NULL, PRIMARY KEY(`run_id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_calibration_runs_created_at` ON `calibration_runs` (`created_at`)"
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build().also { INSTANCE = it }
            }
    }
}
