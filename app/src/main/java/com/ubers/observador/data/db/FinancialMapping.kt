package com.ubers.observador.data.db

import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.FinancialAnomaly
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.finance.PlatformEarningsSnapshot
import com.ubers.observador.domain.finance.PlatformPeriodType
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.MoneyAmount

/** The only place money rows and domain objects are converted. */

private const val SEPARATOR = ","

private fun codes(list: Collection<String>) = list.joinToString(SEPARATOR)
private fun codes(text: String) = text.split(SEPARATOR).filter { it.isNotEmpty() }

fun FinancialObservation.toEntity(createdAt: Long) = FinancialObservationEntity(
    observationId = observationId,
    tripId = tripId,
    lifecycleSessionId = lifecycleSessionId,
    observedAt = observedAtMs,
    type = type.storageKey,
    semantics = semantics.storageKey,
    amountMinor = amount.minorUnits,
    currencyCode = amount.currencyCode,
    scope = scope.storageKey,
    confidence = confidence,
    source = source.storageKey,
    screenType = screenType,
    evidenceCodes = codes(evidence),
    linkConfidence = linkConfidence.storageKey,
    breakdownGroupId = breakdownGroupId,
    voidsObservationId = voidsObservationId,
    createdAt = createdAt
)

fun FinancialObservationEntity.toDomain() = FinancialObservation(
    observationId = observationId,
    observedAtMs = observedAt,
    lifecycleSessionId = lifecycleSessionId,
    tripId = tripId,
    type = FinancialObservationType.fromStorage(type),
    semantics = AmountSemantics.fromStorage(semantics),
    amount = MoneyAmount(amountMinor, currencyCode),
    scope = FinancialScope.fromStorage(scope),
    confidence = confidence,
    evidence = codes(evidenceCodes),
    source = FinancialSource.fromStorage(source),
    screenType = screenType,
    linkConfidence = LinkConfidence.fromStorage(linkConfidence),
    breakdownGroupId = breakdownGroupId,
    voidsObservationId = voidsObservationId
)

fun TripFinancialSummary.toEntity(updatedAt: Long) = TripFinancialSummaryEntity(
    tripId = tripId,
    currencyCode = currencyCode,
    offeredAmountMinor = offeredAmount?.minorUnits,
    latestAbsoluteMinor = latestAbsoluteTotal?.minorUnits,
    effectiveEarningsMinor = effectiveEarnings?.minorUnits,
    fareMinor = fare,
    tipMinor = tip,
    promotionMinor = promotion,
    adjustmentMinor = adjustment,
    cancellationFeeMinor = cancellationFee,
    otherMinor = other,
    completeness = completeness.storageKey,
    anomalyCodes = codes(anomalies.map { it.storageKey }),
    observationCount = observationCount,
    pendingReviewCount = pendingReviewCount,
    firstObservedAt = firstObservedAt,
    lastObservedAt = lastObservedAt,
    updatedAt = updatedAt
)

/** [offeredCurrency] is used for the offer reference when no real money fixed the currency. */
fun TripFinancialSummaryEntity.toDomain(offeredCurrency: String): TripFinancialSummary {
    val currency = currencyCode
    fun money(minor: Long?) = minor?.let { MoneyAmount(it, currency ?: offeredCurrency) }
    return TripFinancialSummary(
        tripId = tripId,
        currencyCode = currency,
        offeredAmount = offeredAmountMinor?.let { MoneyAmount(it, offeredCurrency) },
        latestAbsoluteTotal = money(latestAbsoluteMinor),
        effectiveEarnings = money(effectiveEarningsMinor),
        fare = fareMinor,
        tip = tipMinor,
        promotion = promotionMinor,
        adjustment = adjustmentMinor,
        cancellationFee = cancellationFeeMinor,
        other = otherMinor,
        completeness = FinancialCompleteness.fromStorage(completeness),
        anomalies = codes(anomalyCodes).mapNotNull(FinancialAnomaly::fromStorage).toSet(),
        observationCount = observationCount,
        pendingReviewCount = pendingReviewCount,
        firstObservedAt = firstObservedAt,
        lastObservedAt = lastObservedAt
    )
}

fun PlatformEarningsSnapshot.toEntity() = PlatformEarningsSnapshotEntity(
    snapshotId = snapshotId,
    capturedAt = capturedAtMs,
    periodType = periodType.storageKey,
    periodStart = periodStartMs,
    periodEnd = periodEndMs,
    amountMinor = amount.minorUnits,
    currencyCode = amount.currencyCode,
    confidence = confidence,
    screenType = screenType,
    evidenceCodes = codes(evidence)
)

fun PlatformEarningsSnapshotEntity.toDomain() = PlatformEarningsSnapshot(
    snapshotId = snapshotId,
    capturedAtMs = capturedAt,
    periodType = PlatformPeriodType.fromStorage(periodType),
    periodStartMs = periodStart,
    periodEndMs = periodEnd,
    amount = MoneyAmount(amountMinor, currencyCode),
    confidence = confidence,
    screenType = screenType,
    evidence = codes(evidenceCodes)
)
