package com.ubers.observador.domain.trips

import com.ubers.observador.domain.lifecycle.RideLifecycleEvent
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState

/**
 * Stored status of a trip. Each value has a fixed [storageKey] written to the database, so
 * renaming or reordering the enum never changes what old rows mean (ordinals are never stored).
 */
enum class TripStatus(val storageKey: String, val stage: Int) {
    TO_PICKUP("TO_PICKUP", 1),
    ARRIVED_OR_WAITING("ARRIVED_OR_WAITING", 2),
    IN_TRIP("IN_TRIP", 3),
    COMPLETED("COMPLETED", 4),
    CANCELLED("CANCELLED", 4),
    /** Ended without evidence of how (Uber back to searching, or a second trip began). */
    INTERRUPTED("INTERRUPTED", 4),
    /** Observation lost (restart, reconnection): still open, the next ride screen picks it up. */
    UNKNOWN("UNKNOWN", 0);

    /** Open trips can still change; at most one should exist. */
    val isOpen: Boolean get() = this == TO_PICKUP || this == ARRIVED_OR_WAITING || this == IN_TRIP || this == UNKNOWN
    val isTerminal: Boolean get() = !isOpen

    companion object {
        /** Unknown keys (a newer app version, a damaged row) read as UNKNOWN instead of failing. */
        fun fromStorage(key: String?): TripStatus = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN

        /** The trip status a lifecycle state means; null for states that are not a trip (IDLE, offer). */
        fun of(state: RideLifecycleState): TripStatus? = when (state) {
            RideLifecycleState.TO_PICKUP -> TO_PICKUP
            RideLifecycleState.ARRIVED_OR_WAITING -> ARRIVED_OR_WAITING
            RideLifecycleState.IN_TRIP -> IN_TRIP
            RideLifecycleState.COMPLETED -> COMPLETED
            RideLifecycleState.CANCELLED -> CANCELLED
            RideLifecycleState.UNKNOWN -> UNKNOWN
            RideLifecycleState.IDLE, RideLifecycleState.OFFER_VISIBLE -> null
        }
    }
}

/** Kinds of stored trip events, with fixed storage keys. */
enum class TripEventType(val storageKey: String) {
    ACCEPTED("ACCEPTED"),
    PICKUP_NAVIGATION("PICKUP_NAVIGATION"),
    ARRIVED("ARRIVED"),
    TRIP_STARTED("TRIP_STARTED"),
    TRIP_COMPLETED("TRIP_COMPLETED"),
    TRIP_CANCELLED("TRIP_CANCELLED"),
    /** Observador joined a trip already under way, or picked it up again after losing it. */
    LIFECYCLE_RECOVERED("LIFECYCLE_RECOVERED"),
    /** Observation lost: restart, reconnection, or a ride that went quiet. */
    LIFECYCLE_LOST("LIFECYCLE_LOST"),
    /** Closed without evidence of completion or cancellation. */
    INTERRUPTED("INTERRUPTED");

    companion object {
        fun fromStorage(key: String?): TripEventType? = entries.firstOrNull { it.storageKey == key }
    }
}

/** Typed anomaly codes: never free text, so they can be counted and checked. */
enum class TripAnomalyCode(val storageKey: String) {
    INVALID_TRANSITION("INVALID_TRANSITION"),
    EVENT_OUT_OF_ORDER("EVENT_OUT_OF_ORDER"),
    MULTIPLE_ACTIVE_TRIPS("MULTIPLE_ACTIVE_TRIPS"),
    TERMINAL_STATE_CONFLICT("TERMINAL_STATE_CONFLICT"),
    ORPHAN_EVENT("ORPHAN_EVENT");

    companion object {
        fun fromStorage(key: String?): TripAnomalyCode? = entries.firstOrNull { it.storageKey == key }
    }
}

/** The offer as Observador saw it when the trip began, kept even if the offer row is deleted later. */
data class TripOfferSnapshot(
    val offeredAmount: Double,
    val pickupKm: Double,
    val pickupMinutes: Int,
    val tripKm: Double,
    val tripMinutes: Int,
    /** Score stored when the offer was detected: the judgement at decision time, never recalculated. */
    val scoreAtDetection: Int,
    val offerSeenAt: Long,
    /** Fase 8F: as Uber wrote them on the card. */
    val pickupAddress: String? = null,
    val dropoffAddress: String? = null,
    /** Fase 8J: modality, "Exclusivo" and the card's button (OfferCardAction.storageKey). */
    val product: String? = null,
    val exclusive: Boolean? = null,
    val cardAction: String? = null
)

/** The settings that judged the offer, so "why was this good that day?" can be answered later. */
data class TripDecisionSnapshot(
    val minRdpPerKm: Double,
    val minRdpPerHour: Double,
    val maxPickupKm: Double,
    val maxPickupMinutes: Int,
    val goodFrom: Int,
    val excellentFrom: Int,
    /** Score weights in force (Fase 7D). Trips stored before 7D all used the defaults. */
    val weights: com.ubers.observador.domain.ScoreWeights = com.ubers.observador.domain.ScoreWeights.DEFAULT
) {
    fun toProfitSettings() = com.ubers.observador.domain.ProfitSettingsSnapshot(
        minRdpPerKm = minRdpPerKm,
        minRdpPerHour = minRdpPerHour,
        maxPickupKm = maxPickupKm,
        maxPickupMinutes = maxPickupMinutes,
        weights = weights,
        goodFrom = goodFrom,
        excellentFrom = excellentFrom
    )
}

/**
 * A trip: the aggregate of its events. Three different identities: [id] (internal, never
 * reused), [lifecycleSessionId] (the 7A session, unique per trip) and [relatedOfferHash]
 * (the offer, a logical link only). Times are epoch ms and null when not observed.
 */
data class Trip(
    val id: String,
    val lifecycleSessionId: String,
    val relatedOfferHash: String?,
    val status: TripStatus,
    val recovered: Boolean,
    val startedFromKnownOffer: Boolean,
    val acceptedAt: Long? = null,
    val pickupNavigationAt: Long? = null,
    val arrivedAt: Long? = null,
    val tripStartedAt: Long? = null,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    val interruptedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastEventId: String? = null,
    val hasAnomalies: Boolean = false,
    val offer: TripOfferSnapshot? = null,
    val decision: TripDecisionSnapshot? = null,
    /** Fase 8F: the rider's name as Uber shows it after accepting ("Llegar por …"). */
    val riderName: String? = null
)

/** One stored, never-edited observation of a trip. No screen text: codes and states only. */
data class TripEvent(
    val eventId: String,
    val tripId: String,
    val lifecycleSessionId: String,
    val type: TripEventType,
    val timestampMs: Long,
    /** 1, 2, 3... per trip, assigned inside the write transaction by the single writer. */
    val sequence: Int,
    val screenType: String?,
    val confidence: Double?,
    val recovered: Boolean,
    val previousStatus: TripStatus?,
    val resultingStatus: TripStatus?,
    val evidenceCodes: List<String>,
    val anomaly: TripAnomalyCode? = null
)

/** A 7A event as the persistence layer needs it. */
data class TripEventInput(
    val eventId: String,
    val lifecycleSessionId: String,
    val relatedOfferHash: String?,
    val lifecycleType: RideLifecycleEventType,
    val from: RideLifecycleState,
    val to: RideLifecycleState,
    val timestampMs: Long,
    val screenType: String?,
    val confidence: Double?,
    /** This event joined a ride already under way (a recovery transition), not a stage seen live. */
    val recoveryTransition: Boolean,
    val evidenceCodes: List<String>
) {
    companion object {
        /**
         * Null for events that are not about a trip: offers (already stored as offers) and
         * events without a session. Evidence is stored by EvidenceCode name: those names are a
         * storage contract and must not be renamed.
         */
        fun from(event: RideLifecycleEvent): TripEventInput? {
            val session = event.sessionId ?: return null
            if (event.type == RideLifecycleEventType.OFFER_DETECTED) return null
            return TripEventInput(
                eventId = event.eventId,
                lifecycleSessionId = session,
                relatedOfferHash = event.relatedOfferHash,
                lifecycleType = event.type,
                from = event.from,
                to = event.to,
                timestampMs = event.timestampMs,
                screenType = event.screenType.name,
                confidence = event.confidence,
                recoveryTransition = event.recoveryTransition,
                evidenceCodes = event.evidence.map { it.name }
            )
        }
    }
}

/**
 * What counts as a trip done (Fase 8H). Uber's summary screen was never confirmed on a real phone,
 * so waiting for it left every trip out. A trip that was taken (accepted or heading to the rider)
 * and then closed by Uber going back to searching, with no anomaly, is counted too, but marked as
 * not confirmed: it may have been cancelled instead, and Observador does not know.
 */
object TripOutcome {

    /** When the trip ended for counting purposes, or null if it does not count as done. */
    fun endedAt(t: Trip): Long? = when {
        t.status == TripStatus.COMPLETED -> t.completedAt
        t.status == TripStatus.INTERRUPTED && !t.hasAnomalies && (t.acceptedAt != null || t.pickupNavigationAt != null) -> t.interruptedAt
        else -> null
    }

    /** True only when the screen that closes a trip was seen. */
    fun isConfirmed(t: Trip): Boolean = t.status == TripStatus.COMPLETED
}
