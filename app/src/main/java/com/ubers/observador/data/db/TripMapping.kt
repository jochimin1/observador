package com.ubers.observador.data.db

import com.ubers.observador.domain.ScoreWeights
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripAnomalyCode
import com.ubers.observador.domain.trips.TripDecisionSnapshot
import com.ubers.observador.domain.trips.TripEvent
import com.ubers.observador.domain.trips.TripEventType
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripStatus

/** The only place trip rows and domain objects are converted. */

private const val CODE_SEPARATOR = ","

fun Trip.toEntity() = TripEntity(
    id = id,
    lifecycleSessionId = lifecycleSessionId,
    relatedOfferHash = relatedOfferHash,
    status = status.storageKey,
    recovered = recovered,
    startedFromKnownOffer = startedFromKnownOffer,
    acceptedAt = acceptedAt,
    pickupNavigationAt = pickupNavigationAt,
    arrivedAt = arrivedAt,
    tripStartedAt = tripStartedAt,
    completedAt = completedAt,
    cancelledAt = cancelledAt,
    interruptedAt = interruptedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastEventId = lastEventId,
    hasAnomalies = hasAnomalies,
    offerSeenAt = offer?.offerSeenAt,
    offeredAmount = offer?.offeredAmount,
    estimatedPickupKm = offer?.pickupKm,
    estimatedPickupMinutes = offer?.pickupMinutes,
    estimatedTripKm = offer?.tripKm,
    estimatedTripMinutes = offer?.tripMinutes,
    scoreAtDetection = offer?.scoreAtDetection,
    settingMinRdpPerKm = decision?.minRdpPerKm,
    settingMinRdpPerHour = decision?.minRdpPerHour,
    settingMaxPickupKm = decision?.maxPickupKm,
    settingMaxPickupMinutes = decision?.maxPickupMinutes,
    settingGoodFrom = decision?.goodFrom,
    settingExcellentFrom = decision?.excellentFrom,
    settingWeightKmPct = decision?.weights?.kmPct,
    settingWeightHourPct = decision?.weights?.hourPct,
    settingWeightPickupKmPct = decision?.weights?.pickupKmPct,
    settingWeightPickupMinPct = decision?.weights?.pickupMinPct,
    offerPickupAddress = offer?.pickupAddress,
    offerDropoffAddress = offer?.dropoffAddress,
    riderName = riderName
)

fun TripEntity.toDomain() = Trip(
    id = id,
    lifecycleSessionId = lifecycleSessionId,
    relatedOfferHash = relatedOfferHash,
    status = TripStatus.fromStorage(status),
    recovered = recovered,
    startedFromKnownOffer = startedFromKnownOffer,
    acceptedAt = acceptedAt,
    pickupNavigationAt = pickupNavigationAt,
    arrivedAt = arrivedAt,
    tripStartedAt = tripStartedAt,
    completedAt = completedAt,
    cancelledAt = cancelledAt,
    interruptedAt = interruptedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastEventId = lastEventId,
    hasAnomalies = hasAnomalies,
    offer = offerSnapshot(),
    decision = decisionSnapshot(),
    riderName = riderName
)

private fun TripEntity.offerSnapshot(): TripOfferSnapshot? {
    val amount = offeredAmount ?: return null
    return TripOfferSnapshot(
        offeredAmount = amount,
        pickupKm = estimatedPickupKm ?: return null,
        pickupMinutes = estimatedPickupMinutes ?: return null,
        tripKm = estimatedTripKm ?: return null,
        tripMinutes = estimatedTripMinutes ?: return null,
        scoreAtDetection = scoreAtDetection ?: return null,
        offerSeenAt = offerSeenAt ?: return null,
        pickupAddress = offerPickupAddress,
        dropoffAddress = offerDropoffAddress
    )
}

private fun TripEntity.decisionSnapshot(): TripDecisionSnapshot? {
    val perKm = settingMinRdpPerKm ?: return null
    return TripDecisionSnapshot(
        minRdpPerKm = perKm,
        minRdpPerHour = settingMinRdpPerHour ?: return null,
        maxPickupKm = settingMaxPickupKm ?: return null,
        maxPickupMinutes = settingMaxPickupMinutes ?: return null,
        goodFrom = settingGoodFrom ?: return null,
        excellentFrom = settingExcellentFrom ?: return null,
        weights = ScoreWeights.of(settingWeightKmPct, settingWeightHourPct, settingWeightPickupKmPct, settingWeightPickupMinPct)
    )
}

fun TripEvent.toEntity() = RideEventEntity(
    eventId = eventId,
    tripId = tripId,
    lifecycleSessionId = lifecycleSessionId,
    eventType = type.storageKey,
    timestamp = timestampMs,
    sequence = sequence,
    screenType = screenType,
    confidence = confidence,
    recovered = recovered,
    previousStatus = previousStatus?.storageKey,
    resultingStatus = resultingStatus?.storageKey,
    evidenceCodes = evidenceCodes.joinToString(CODE_SEPARATOR),
    anomalyCode = anomaly?.storageKey
)

/** Null only for an event type this version does not know (written by a newer version). */
fun RideEventEntity.toDomain(): TripEvent? {
    val type = TripEventType.fromStorage(eventType) ?: return null
    return TripEvent(
        eventId = eventId,
        tripId = tripId,
        lifecycleSessionId = lifecycleSessionId,
        type = type,
        timestampMs = timestamp,
        sequence = sequence,
        screenType = screenType,
        confidence = confidence,
        recovered = recovered,
        previousStatus = previousStatus?.let(TripStatus::fromStorage),
        resultingStatus = resultingStatus?.let(TripStatus::fromStorage),
        evidenceCodes = evidenceCodes.split(CODE_SEPARATOR).filter { it.isNotEmpty() },
        anomaly = TripAnomalyCode.fromStorage(anomalyCode)
    )
}

/** What a trip copies from its offer row when it is created. */
fun OfferEntity.toTripOfferSnapshot() = TripOfferSnapshot(
    offeredAmount = price,
    pickupKm = pickupKm,
    pickupMinutes = pickupMin,
    tripKm = tripKm,
    tripMinutes = tripMin,
    scoreAtDetection = score,
    offerSeenAt = detectedAt,
    pickupAddress = pickupAddress,
    dropoffAddress = dropoffAddress
)
