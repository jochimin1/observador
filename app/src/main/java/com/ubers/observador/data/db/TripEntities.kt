package com.ubers.observador.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One trip (Fase 7B): the current picture built from its events. Times are epoch ms, like the
 * rest of the app, and null when not observed. Enums are stored by their explicit storage keys.
 *
 * Indices, and the query each one serves:
 * - lifecycle_session_id (unique): find the trip of an event; one session can never make two trips.
 * - status: the open trip at start-up and for the "active trip" query.
 * - created_at: recent trips, newest first, and trips by period.
 * related_offer_hash has no index yet: nothing queries trips by offer (Fase 7C may add it).
 */
@Entity(
    tableName = "trips",
    indices = [
        Index(value = ["lifecycle_session_id"], unique = true),
        Index(value = ["status"]),
        Index(value = ["created_at"])
    ]
)
data class TripEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lifecycle_session_id") val lifecycleSessionId: String,
    @ColumnInfo(name = "related_offer_hash") val relatedOfferHash: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "recovered") val recovered: Boolean,
    @ColumnInfo(name = "started_from_known_offer") val startedFromKnownOffer: Boolean,
    @ColumnInfo(name = "accepted_at") val acceptedAt: Long?,
    @ColumnInfo(name = "pickup_navigation_at") val pickupNavigationAt: Long?,
    @ColumnInfo(name = "arrived_at") val arrivedAt: Long?,
    @ColumnInfo(name = "trip_started_at") val tripStartedAt: Long?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "cancelled_at") val cancelledAt: Long?,
    @ColumnInfo(name = "interrupted_at") val interruptedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_event_id") val lastEventId: String?,
    @ColumnInfo(name = "has_anomalies") val hasAnomalies: Boolean,
    // The offer as seen when the trip began (copied: the offer row may be deleted later)
    @ColumnInfo(name = "offer_seen_at") val offerSeenAt: Long?,
    @ColumnInfo(name = "offered_amount") val offeredAmount: Double?,
    @ColumnInfo(name = "estimated_pickup_km") val estimatedPickupKm: Double?,
    @ColumnInfo(name = "estimated_pickup_minutes") val estimatedPickupMinutes: Int?,
    @ColumnInfo(name = "estimated_trip_km") val estimatedTripKm: Double?,
    @ColumnInfo(name = "estimated_trip_minutes") val estimatedTripMinutes: Int?,
    @ColumnInfo(name = "score_at_detection") val scoreAtDetection: Int?,
    // The settings that judged it
    @ColumnInfo(name = "setting_min_rdp_per_km") val settingMinRdpPerKm: Double?,
    @ColumnInfo(name = "setting_min_rdp_per_hour") val settingMinRdpPerHour: Double?,
    @ColumnInfo(name = "setting_max_pickup_km") val settingMaxPickupKm: Double?,
    @ColumnInfo(name = "setting_max_pickup_minutes") val settingMaxPickupMinutes: Int?,
    @ColumnInfo(name = "setting_good_from") val settingGoodFrom: Int?,
    @ColumnInfo(name = "setting_excellent_from") val settingExcellentFrom: Int?,
    // Weights of the score (Fase 7D, DB 7). Null on older rows = the original 35/30/20/15
    @ColumnInfo(name = "setting_weight_km_pct") val settingWeightKmPct: Int? = null,
    @ColumnInfo(name = "setting_weight_hour_pct") val settingWeightHourPct: Int? = null,
    @ColumnInfo(name = "setting_weight_pickup_km_pct") val settingWeightPickupKmPct: Int? = null,
    @ColumnInfo(name = "setting_weight_pickup_min_pct") val settingWeightPickupMinPct: Int? = null,
    // Fase 8F (DB 11): copied from the offer card, and the rider's name read after accepting
    @ColumnInfo(name = "offer_pickup_address") val offerPickupAddress: String? = null,
    @ColumnInfo(name = "offer_dropoff_address") val offerDropoffAddress: String? = null,
    @ColumnInfo(name = "rider_name") val riderName: String? = null,
    // Fase 8J (DB 13): copied from the offer card
    @ColumnInfo(name = "offer_product") val offerProduct: String? = null,
    @ColumnInfo(name = "offer_exclusive") val offerExclusive: Boolean? = null,
    @ColumnInfo(name = "offer_card_action") val offerCardAction: String? = null
)

/**
 * One observed trip event, inserted once and never updated. Deleting a trip deletes its events
 * (CASCADE): a trip is only ever deleted on purpose (today only TEST data), and events without
 * their trip would be meaningless. The offer link is logical, so deleting offers never touches trips.
 *
 * Indices: event_id (unique) makes a repeated event a no-op; (trip_id, sequence) (unique) serves
 * the timeline query, the next-sequence query and the foreign key, and forbids two events with
 * the same number in one trip.
 */
@Entity(
    tableName = "ride_events",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["trip_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["event_id"], unique = true),
        Index(value = ["trip_id", "sequence"], unique = true)
    ]
)
data class RideEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "database_id") val databaseId: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "lifecycle_session_id") val lifecycleSessionId: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "sequence") val sequence: Int,
    @ColumnInfo(name = "screen_type") val screenType: String?,
    @ColumnInfo(name = "confidence") val confidence: Double?,
    @ColumnInfo(name = "recovered") val recovered: Boolean,
    @ColumnInfo(name = "previous_status") val previousStatus: String?,
    @ColumnInfo(name = "resulting_status") val resultingStatus: String?,
    /** Comma-separated EvidenceCode names (a storage contract): codes only, never screen text. */
    @ColumnInfo(name = "evidence_codes") val evidenceCodes: String,
    @ColumnInfo(name = "anomaly_code") val anomalyCode: String?
)

/** Event count per trip, for the trip list without loading every event. */
data class TripEventCount(
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "count") val count: Int
)
