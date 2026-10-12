package com.ubers.observador.data.debug

import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.ScoreWeights
import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.FinancialIds
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.finance.TripFinancialAggregator
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.intelligence.ShadowEvaluation
import com.ubers.observador.domain.intelligence.ShadowScoring
import com.ubers.observador.domain.lifecycle.RideLifecycleEventType
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.InMemoryTripLedger
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripDecisionSnapshot
import com.ubers.observador.domain.trips.TripEvent
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripOfferSnapshot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Debug-only synthetic history for Fase 7D, with KNOWN patterns so the analysis can be checked:
 *
 * - GOOD_MODEL: real time is close to the estimate, so a higher score brings a better RD$/h.
 * - BAD_MODEL: the highest scores take far longer than promised, so they end with the worst RD$/h.
 * - PICKUP_MATTERS: pickups take three times their estimate; a model that weighs pickup time more
 *   ranks the trips better, in old and new data alike (calibration should find it).
 * - OVERFIT: old trips follow PICKUP_MATTERS; in the recent part the offers promising the most per
 *   hour run longest. A model tuned on the old part fails on the new part and must be rejected.
 *
 * Every trip also includes a few realistic gaps: ~5% without money, ~5% recovered mid-trip (no
 * offer, no acceptance time) and two outliers with a big tip. All ids start with "TEST-": the
 * existing "Borrar prueba" buttons remove them, real data is never touched. Deterministic per seed.
 */
object IntelligenceTestSeeder {

    enum class Scenario { GOOD_MODEL, BAD_MODEL, PICKUP_MATTERS, OVERFIT }

    private const val P = TripTestSeeder.PREFIX + "int-"
    private const val MINUTE = 60_000L
    private const val DAY = 86_400_000L

    /** The rules the synthetic offers were judged with: the app's defaults. */
    val DEFAULT_SETTINGS = ProfitSettingsSnapshot(6.0, 300.0, 6.0, 12, ScoreWeights.DEFAULT, 60, 80)

    data class Seed(
        val trips: List<Trip>,
        val events: List<TripEvent>,
        val observations: List<FinancialObservation>,
        val features: Map<String, OfferDecisionFeatures>
    ) {
        /** Each trip's money as the 7C rules compute it (what the database will hold). */
        fun summaries(): Map<String, TripFinancialSummary> = observations.groupBy { it.tripId!! }.mapValues { (id, obs) ->
            TripFinancialAggregator.aggregate(id, null, obs)
        }
    }

    fun generate(scenario: Scenario, now: Long, count: Int = 100, seed: Long = 7L): Seed {
        val random = java.util.Random(seed)
        var tripIds = 0
        var side = 0
        val ledger = InMemoryTripLedger(newTripId = { "${P}trip-${++tripIds}" }, newEventId = { "${P}side-${++side}" })
        val engine = ProfitEngine()
        val observations = ArrayList<FinancialObservation>()
        val features = LinkedHashMap<String, OfferDecisionFeatures>()
        val decision = TripDecisionSnapshot(6.0, 300.0, 6.0, 12, 60, 80)
        val start = now - 60 * DAY

        for (i in 0 until count) {
            // Spread over 60 days, at varied hours, oldest first
            val day = start + (i * 60L * DAY) / count
            val hour = 6 + (i * 7) % 16
            val acceptedAt = day - day % DAY + hour * 60 * MINUTE + (i % 4) * 7 * MINUTE
            val pickupKm = round1(0.3 + random.nextDouble() * 6.5)
            val pickupMin = (2 + pickupKm * 1.6 + random.nextInt(3)).roundToInt()
            val tripKm = round1(1.5 + random.nextDouble() * 12.0)
            val tripMin = (4 + tripKm * 2.0 + random.nextInt(5)).roundToInt()
            val price = round2(55.0 + tripKm * (9.0 + random.nextDouble() * 9.0) + random.nextDouble() * 40.0)
            val offered = MoneyAmount((price * 100).roundToLong(), Currencies.DOP)
            val f = OfferDecisionFeatures(offered, pickupKm, pickupMin, tripKm, tripMin, acceptedAt - MINUTE)
            val evaluation = engine.evaluate(f, DEFAULT_SETTINGS)

            val noise = 1.0 + (random.nextDouble() - 0.5) * 0.10
            val recentHalf = i >= count * 0.7
            val actualMinutes = when {
                scenario == Scenario.GOOD_MODEL -> (pickupMin + tripMin) * 1.08 * noise
                scenario == Scenario.BAD_MODEL -> (pickupMin + tripMin) * (0.4 + (evaluation.score / 30.0).let { it * it }) * noise
                scenario == Scenario.PICKUP_MATTERS || (scenario == Scenario.OVERFIT && !recentHalf) ->
                    (pickupMin * 3.0 + tripMin) * noise
                // OVERFIT, recent part: the market turned: offers promising the most per hour now run longest
                else -> (pickupMin + tripMin) * (0.4 + (evaluation.predictedRdpPerHour / 300.0).let { it * it }) * noise
            }.coerceAtLeast(3.0)
            val recovered = i % 20 == 13
            val noMoney = i % 20 == 7
            val outlierTip = if (i == count / 3 || i == (2 * count) / 3) 30_000L else 0L
            val completedAt = acceptedAt + (actualMinutes * MINUTE).roundToLong()
            val startedAt = acceptedAt + (pickupMin * (if (scenario == Scenario.PICKUP_MATTERS) 3.0 else 1.1) * MINUTE).roundToLong()
                .coerceAtMost(completedAt - MINUTE)

            val hash = "${P}offer-$i"
            val session = "${P}session-$i"
            val snapshot = TripOfferSnapshot(price, pickupKm, pickupMin, tripKm, tripMin, evaluation.score, f.detectedAtMs)
            fun event(n: Int, type: RideLifecycleEventType, from: RideLifecycleState, to: RideLifecycleState, at: Long, recovery: Boolean = false) {
                ledger.persist(
                    TripEventInput("${P}event-$i-$n", session, if (recovered) null else hash, type, from, to, at, null, null, recovery, emptyList()),
                    offer = if (recovered) null else snapshot,
                    decision = decision
                )
            }
            if (recovered) {
                event(1, RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.IDLE, RideLifecycleState.IN_TRIP, startedAt, recovery = true)
            } else {
                event(1, RideLifecycleEventType.ACCEPTANCE_CONFIRMED, RideLifecycleState.OFFER_VISIBLE, RideLifecycleState.TO_PICKUP, acceptedAt)
                event(2, RideLifecycleEventType.TRIP_STARTED, RideLifecycleState.TO_PICKUP, RideLifecycleState.IN_TRIP, startedAt)
                features[hash] = f
            }
            event(3, RideLifecycleEventType.TRIP_COMPLETED, RideLifecycleState.IN_TRIP, RideLifecycleState.COMPLETED, completedAt)

            if (!noMoney) {
                val tripId = ledger.trips.last().id
                val earned = (offered.minorUnits * (1.0 + (random.nextDouble() - 0.4) * 0.08)).roundToLong() + outlierTip
                val amount = MoneyAmount(earned, Currencies.DOP)
                observations += FinancialObservation(
                    observationId = TripTestSeeder.PREFIX + FinancialIds.observationId(tripId, FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, amount, "TEST", completedAt),
                    observedAtMs = completedAt + MINUTE,
                    lifecycleSessionId = null,
                    tripId = tripId,
                    type = FinancialObservationType.TRIP_TOTAL,
                    semantics = AmountSemantics.ABSOLUTE_TOTAL,
                    amount = amount,
                    scope = FinancialScope.TRIP,
                    confidence = 0.95,
                    evidence = listOf("TEST_DATA"),
                    source = FinancialSource.TEST,
                    screenType = "TEST",
                    linkConfidence = LinkConfidence.HIGH
                )
            }
        }
        return Seed(ledger.trips, ledger.events, observations, features)
    }

    /**
     * Shadow evaluations for the TEST offers, as if [candidate] had been running in shadow when
     * they appeared (debug only: real shadow scores only new offers).
     */
    fun shadow(seed: Seed, active: ProfitModelVersion, candidate: ProfitModelVersion): List<ShadowEvaluation> =
        seed.features.map { (hash, f) ->
            val e = ShadowScoring.evaluate(hash, f, active, candidate, f.detectedAtMs)
            e.copy(evaluationId = TripTestSeeder.PREFIX + e.evaluationId)
        }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
}
