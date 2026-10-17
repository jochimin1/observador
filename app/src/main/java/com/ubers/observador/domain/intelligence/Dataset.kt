package com.ubers.observador.domain.intelligence

import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.ScoreWeights
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOutcome
import com.ubers.observador.domain.trips.TripStatus

/**
 * One trip done, joined with its offer, the settings that judged it and its real money. Since
 * 1.25.0 also the trips closed by «Buscando» («terminó sin confirmar», [endConfirmed] false), and
 * the real kilometres of the GPS (8E) when the whole trip was measured.
 * Outcome fields (earnings, real minutes) are labels for evaluation only: no decision model
 * ever receives this class, only [features].
 *
 * Missing data stays null: a recovered trip without acceptance time has no real hourly figure,
 * and the offered amount is never used in place of real earnings.
 */
data class IntelligenceSample(
    val tripId: String,
    val offerHash: String?,
    val detectedAtMs: Long?,
    val acceptedAtMs: Long?,
    val tripStartedAtMs: Long?,
    val completedAtMs: Long?,
    /** The offer card as seen; null when the trip was picked up mid-way (no offer known). */
    val features: OfferDecisionFeatures?,
    /** Rules in force when it was judged; null for trips stored without them. */
    val settingsAtDetection: ProfitSettingsSnapshot?,
    val scoreAtDetection: Int?,
    val categoryAtDetection: OfferQuality?,
    /** What the offer promised: offered / estimated minutes * 60 (ProfitEngine's own figure). */
    val predictedRdpPerHour: Double?,
    /** Offered / ESTIMATED km. Not a real RD$/km: no real distance is measured yet. */
    val earningsPerEstimatedKm: Double?,
    val estimatedEngagedMinutes: Int?,
    val actualEarnings: MoneyAmount?,
    val financialCompleteness: FinancialCompleteness,
    /** completedAt - acceptedAt: pickup + wait + ride. */
    val actualEngagedMinutes: Double?,
    /** completedAt - tripStartedAt: the ride alone. */
    val actualInTripMinutes: Double?,
    /** Real earnings / engaged minutes * 60, only with confirmed money and both times. */
    val realizedGrossPerEngagedHour: Double?,
    val recovered: Boolean,
    val timelineValid: Boolean,
    val modelVersionId: String? = null,
    /** False for a trip closed by «Buscando»: its end is the route's last point or 45 s before the close. */
    val endConfirmed: Boolean = true,
    /** The offer's km: pickup + trip, as Uber estimated them. */
    val estimatedKm: Double? = null,
    /** GPS km of the whole trip (8E), only when it was measured from pickup to end with no gap. */
    val realDistanceMm: Long? = null
) {
    /** Real km - estimated km. */
    val distanceErrorKm: Double?
        get() {
            val real = realDistanceMm ?: return null
            val estimated = estimatedKm ?: return null
            return real / 1_000_000.0 - estimated
        }

    val distanceErrorPercent: Double?
        get() {
            val estimated = estimatedKm ?: return null
            if (estimated <= 0.0) return null
            return distanceErrorKm?.div(estimated)
        }

    /** Real earnings / real GPS km: RD$ per real km, only with confirmed money and a full measure. */
    val realizedPerRealKm: Double?
        get() {
            val earnings = actualEarnings ?: return null
            val mm = realDistanceMm ?: return null
            if (mm < 100_000) return null // under 100 m: no meaningful ratio
            return earnings.toDecimal().toDouble() / (mm / 1_000_000.0)
        }

    /** Orders samples in time for validation: when the offer appeared, else when it was taken. */
    val orderingTimeMs: Long get() = detectedAtMs ?: acceptedAtMs ?: completedAtMs ?: 0L

    val timeErrorMinutes: Double?
        get() {
            val actual = actualEngagedMinutes ?: return null
            val estimated = estimatedEngagedMinutes ?: return null
            return actual - estimated
        }

    val timeErrorPercent: Double?
        get() {
            val estimated = estimatedEngagedMinutes ?: return null
            if (estimated <= 0) return null
            return timeErrorMinutes?.div(estimated)
        }

    val hourlyError: Double?
        get() {
            val realized = realizedGrossPerEngagedHour ?: return null
            return realized - (predictedRdpPerHour ?: return null)
        }

    val hourlyErrorPercent: Double?
        get() {
            val predicted = predictedRdpPerHour ?: return null
            if (predicted <= 0.0) return null
            return hourlyError?.div(predicted)
        }

    /** Real - offered in minor units, only with confirmed money in the offer's currency. */
    val moneyDifferenceMinor: Long?
        get() {
            val actual = actualEarnings ?: return null
            val offered = features?.offeredAmount ?: return null
            if (actual.currencyCode != offered.currencyCode) return null
            return actual.minorUnits - offered.minorUnits
        }

    val moneyDifferencePercent: Double?
        get() {
            val offered = features?.offeredAmount ?: return null
            if (offered.minorUnits <= 0) return null
            return moneyDifferenceMinor?.let { it.toDouble() / offered.minorUnits }
        }
}

/** What the data can and cannot support. Shown before any conclusion. */
data class DataQualityReport(
    /** Trips done in the period: confirmed by Uber's summary plus, when included, closed by «Buscando». */
    val completedTrips: Int,
    val withConfirmedMoney: Int,
    val withPartialMoney: Int,
    val withoutMoney: Int,
    val withFullTimes: Int,
    val usableForHourly: Int,
    val recovered: Int,
    val missingAcceptedAt: Int,
    val invalidTimeline: Int,
    val currencyMismatch: Int,
    val withoutOffer: Int,
    val withoutSettingsSnapshot: Int,
    /** Of [completedTrips]: closed by «Buscando», with no summary seen. */
    val unconfirmedEnds: Int = 0,
    /** With the GPS km of the whole trip. */
    val withRealDistance: Int = 0
) {
    val confirmedEnds: Int get() = completedTrips - unconfirmedEnds
    val distance: Coverage get() = Coverage(completedTrips, withRealDistance)
    val financial: Coverage get() = Coverage(completedTrips, withConfirmedMoney)
    val time: Coverage get() = Coverage(completedTrips, withFullTimes)
    val hourly: Coverage get() = Coverage(completedTrips, usableForHourly)
}

data class IntelligenceDataset(val samples: List<IntelligenceSample>, val quality: DataQualityReport)

/**
 * Trip + offer snapshot + settings snapshot + financial summary = samples. Pure and
 * deterministic. Nothing is dropped globally: each analysis picks the samples valid for it.
 * Inconsistent trips are kept, flagged and counted, never deleted.
 */
object IntelligenceDatasetBuilder {

    private val engine = ProfitEngine()

    /** Neutral rules, used only to read the predicted ratios (they do not depend on settings). */
    private val NEUTRAL = ProfitSettingsSnapshot(6.0, 300.0, 6.0, 12, ScoreWeights.DEFAULT, 60, 80)

    /**
     * [includeUnconfirmed]: also the trips closed by «Buscando» that count as done (TripOutcome).
     * [routeEnds]: trip id -> last point of its route (8K), their best end time.
     * [realDistanceMm]: trip id -> GPS km of the whole trip (8E), only for full measures.
     */
    fun build(
        trips: List<Trip>,
        summaries: Map<String, TripFinancialSummary>,
        currencyCode: String,
        includeUnconfirmed: Boolean = true,
        routeEnds: Map<String, Long> = emptyMap(),
        realDistanceMm: Map<String, Long> = emptyMap()
    ): IntelligenceDataset {
        var currencyMismatch = 0
        val samples = trips
            .filter { it.status == TripStatus.COMPLETED || (includeUnconfirmed && it.status == TripStatus.INTERRUPTED && TripOutcome.endedAt(it) != null) }
            .map { it to TripOutcome.realEndAt(it, routeEnds[it.id]) }
            .sortedWith(compareBy<Pair<Trip, Long?>> { it.second ?: 0L }.thenBy { it.first.id })
            .map { (trip, end) ->
                val summary = summaries[trip.id]
                val confirmed = summary?.takeIf { it.completeness == FinancialCompleteness.CONFIRMED }?.effectiveEarnings
                val earnings = confirmed?.takeIf { it.currencyCode == currencyCode }
                if (confirmed != null && earnings == null) currencyMismatch++
                sample(trip, summary, earnings, currencyCode, end, realDistanceMm[trip.id])
            }
        val quality = DataQualityReport(
            completedTrips = samples.size,
            withConfirmedMoney = samples.count { it.actualEarnings != null },
            withPartialMoney = samples.count { it.financialCompleteness == FinancialCompleteness.PARTIAL },
            withoutMoney = samples.count { it.financialCompleteness == FinancialCompleteness.UNKNOWN },
            withFullTimes = samples.count { it.actualEngagedMinutes != null },
            usableForHourly = samples.count { it.realizedGrossPerEngagedHour != null },
            recovered = samples.count { it.recovered },
            missingAcceptedAt = samples.count { it.acceptedAtMs == null },
            invalidTimeline = samples.count { !it.timelineValid },
            currencyMismatch = currencyMismatch,
            withoutOffer = samples.count { it.features == null },
            withoutSettingsSnapshot = samples.count { it.settingsAtDetection == null },
            unconfirmedEnds = samples.count { !it.endConfirmed },
            withRealDistance = samples.count { it.realDistanceMm != null }
        )
        return IntelligenceDataset(samples, quality)
    }

    private fun sample(
        trip: Trip,
        summary: TripFinancialSummary?,
        earnings: MoneyAmount?,
        currencyCode: String,
        end: Long?,
        realMm: Long?
    ): IntelligenceSample {
        val offer = trip.offer
        val features = offer?.let {
            MoneyAmount.fromLegacyDouble(it.offeredAmount, currencyCode)?.let { amount ->
                OfferDecisionFeatures(amount, it.pickupKm, it.pickupMinutes, it.tripKm, it.tripMinutes, it.offerSeenAt)
            }
        }
        val settings = trip.decision?.toProfitSettings()
        val predicted = features?.let { engine.evaluate(it, settings ?: NEUTRAL) }
        val accepted = trip.acceptedAt
        val started = trip.tripStartedAt
        // The summary's time, or for a trip closed by «Buscando» its best estimate (TripOutcome.realEndAt)
        val completed = end
        val timelineValid = completed != null &&
            (accepted == null || accepted <= completed) &&
            (started == null || started <= completed) &&
            (accepted == null || started == null || accepted <= started)
        val engaged = if (timelineValid && accepted != null && completed != null && completed > accepted) {
            (completed - accepted) / MINUTE
        } else {
            null
        }
        val inTrip = if (timelineValid && started != null && completed != null && completed > started) {
            (completed - started) / MINUTE
        } else {
            null
        }
        val realized = if (earnings != null && engaged != null) earnings.toDecimal().toDouble() / engaged * 60.0 else null
        val score = offer?.scoreAtDetection
        return IntelligenceSample(
            tripId = trip.id,
            offerHash = trip.relatedOfferHash,
            detectedAtMs = offer?.offerSeenAt,
            acceptedAtMs = accepted,
            tripStartedAtMs = started,
            completedAtMs = completed,
            features = features,
            settingsAtDetection = settings,
            scoreAtDetection = score,
            categoryAtDetection = if (score != null && settings != null) settings.thresholds.classify(score) else null,
            predictedRdpPerHour = predicted?.predictedRdpPerHour?.takeIf { (features?.estimatedEngagedMinutes ?: 0) > 0 },
            earningsPerEstimatedKm = predicted?.predictedRdpPerKm?.takeIf { (features?.let { it.pickupKm + it.tripKm } ?: 0.0) > 0.0 },
            estimatedEngagedMinutes = features?.estimatedEngagedMinutes,
            actualEarnings = earnings,
            financialCompleteness = summary?.completeness ?: FinancialCompleteness.UNKNOWN,
            actualEngagedMinutes = engaged,
            actualInTripMinutes = inTrip,
            realizedGrossPerEngagedHour = realized,
            recovered = trip.recovered,
            timelineValid = timelineValid,
            endConfirmed = trip.status == TripStatus.COMPLETED,
            estimatedKm = features?.let { it.pickupKm + it.tripKm }?.takeIf { it > 0.0 },
            realDistanceMm = realMm?.takeIf { it > 0 }
        )
    }

    private const val MINUTE = 60_000.0
}
