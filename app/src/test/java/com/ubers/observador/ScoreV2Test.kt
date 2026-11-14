package com.ubers.observador

import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.ModelVersionSource
import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.intelligence.ModelVersionRules
import com.ubers.observador.domain.intelligence.ShadowAnalyzer
import com.ubers.observador.domain.intelligence.ShadowGateReason
import com.ubers.observador.domain.intelligence.ShadowScoring
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.operating.FixedCostBasis
import com.ubers.observador.domain.operating.OperatingCostModel
import com.ubers.observador.domain.scoring.ProfitEngineV2
import com.ubers.observador.domain.scoring.ScoreCoverage
import com.ubers.observador.domain.scoring.ScoreFactor
import com.ubers.observador.domain.scoring.ScoreHistory
import com.ubers.observador.domain.scoring.ScoreV2Inputs
import com.ubers.observador.domain.scoring.ScoringAlgorithms
import com.ubers.observador.domain.scoring.WaitEstimate
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.44.0: V2 is deterministic, reflects costs, keeps V1 and history intact, and runs only in shadow. */
class ScoreV2Test {

    private val min = 60_000L
    private val settings = ProfitSettingsSnapshot.of(EngineSettings(), QualityThresholds())

    /** RD$150, recogida 2 km / 6 min, viaje 8 km / 18 min: 10 km and 24 min in all. */
    private fun offer(priceMinor: Long = 15_000, pickupKm: Double = 2.0, pickupMin: Int = 6, at: Long = 1_000 * min) =
        OfferDecisionFeatures(MoneyAmount(priceMinor, Currencies.DOP), pickupKm, pickupMin, 8.0, 18, at)

    /** RD$300/gal at 100 km/gal (RD$3/km) + RD$1/km maintenance + RD$0.50/km depreciation. */
    private fun model(revision: Int = 1, effectiveAt: Long = 0, fuel: Long = 30_000, maintenance: Long = 100, fixed: Long = 50_000) =
        OperatingCostModel(
            "m$revision", revision, "2026-11-01", effectiveAt, effectiveAt, "America/Santo_Domingo", 4,
            fuelPriceMinor = fuel, kmPerGallonMilli = 100_000, maintenancePerKmMinor = maintenance,
            depreciationPerKmMinor = 50, fixedMinor = fixed, fixedBasis = FixedCostBasis.WORKDAY
        )

    private val inputs = ScoreV2Inputs(listOf(model()))

    @Test fun knownInputsGiveTheSameExactResult() {
        val a = ProfitEngineV2.evaluate(offer(), settings, inputs)
        val b = ProfitEngineV2.evaluate(offer(), settings, inputs)
        assertEquals(a, b)
        assertEquals(ProfitEngineV2.ALGORITHM_VERSION, a.algorithm)
        // Cost: RD$30 fuel + RD$10 maintenance + RD$5 depreciation; fixed costs stay out
        assertEquals(4_500L, a.cost!!.total)
        assertEquals(0L, a.cost!!.fixed)
        assertEquals(10_500L, a.netMinor)
        assertEquals(10.5, a.netPerKm!!, 1e-9)
        assertEquals(262.5, a.netPerHour!!, 1e-9)
        // (87.5·35 + 43.75·30 + 66.67·20 + 50·15) / 100 = 64.6
        assertEquals(65, a.score)
        assertEquals(a.score, a.low)
        assertEquals(a.score, a.high)
        assertEquals(listOf(ScoreCoverage.NO_WAIT_HISTORY), a.coverage)
        // V1 on the same offer is unchanged and higher: it ignores the cost
        assertEquals(75, ProfitEngine().evaluate(offer(), settings).score)
    }

    @Test fun higherCostsLowerTheScore() {
        val cheap = ProfitEngineV2.evaluate(offer(), settings, ScoreV2Inputs(listOf(model(fuel = 15_000, maintenance = 0))))
        val normal = ProfitEngineV2.evaluate(offer(), settings, inputs)
        val dear = ProfitEngineV2.evaluate(offer(), settings, ScoreV2Inputs(listOf(model(fuel = 90_000, maintenance = 300))))
        assertTrue("${cheap.score} > ${normal.score}", cheap.score > normal.score)
        assertTrue("${normal.score} > ${dear.score}", normal.score > dear.score)
        assertTrue(ScoreFactor.HIGH_COST in dear.factors)
        assertFalse(ScoreFactor.HIGH_COST in cheap.factors)
        // Fixed costs do not depend on taking the offer: changing them changes nothing
        assertEquals(normal, ProfitEngineV2.evaluate(offer(), settings, ScoreV2Inputs(listOf(model(fixed = 900_000)))))
    }

    @Test fun anOfferThatDoesNotCoverItsCostSaysSo() {
        val r = ProfitEngineV2.evaluate(offer(priceMinor = 4_000, pickupKm = 7.0, pickupMin = 15), settings, inputs)
        assertTrue(r.netMinor!! < 0)
        assertEquals(ScoreFactor.LOSS, r.factors.first())
        assertTrue(ScoreFactor.LONG_PICKUP in r.factors)
        assertFalse(ScoreFactor.BELOW_HOUR_TARGET in r.factors)
        // Both pay parts are zero, and so are the pickup parts beyond the limits
        assertEquals(0, r.score)
    }

    @Test fun withoutACostModelV1IsUsedAndSaidSo() {
        val r = ProfitEngineV2.evaluate(offer(), settings, ScoreV2Inputs.NONE)
        assertTrue(r.usedV1)
        assertEquals(ProfitEngine().evaluate(offer(), settings).score, r.score)
        assertEquals(listOf(ScoreCoverage.NO_COST_MODEL), r.coverage)
        assertNull(r.cost)
        // A shadow test never stores that V1 score under V2's name
        assertNull(ScoringAlgorithms.score(ProfitEngineV2.ALGORITHM_VERSION, offer(), settings, ScoreV2Inputs.NONE))
        assertNull(ScoringAlgorithms.score("PROFIT_ENGINE_V9", offer(), settings, inputs))
        // A model that only starts after the offer does not apply to it
        assertTrue(ProfitEngineV2.evaluate(offer(at = 10), settings, ScoreV2Inputs(listOf(model(effectiveAt = 20)))).usedV1)
    }

    @Test fun anOldOfferKeepsTheCostModelOfItsDay() {
        val models = listOf(model(1, effectiveAt = 0), model(2, effectiveAt = 2_000 * min, fuel = 60_000))
        val before = ProfitEngineV2.evaluate(offer(at = 1_000 * min), settings, ScoreV2Inputs(models))
        val after = ProfitEngineV2.evaluate(offer(at = 3_000 * min), settings, ScoreV2Inputs(models))
        assertEquals(1, before.costRevision)
        assertEquals(2, after.costRevision)
        assertEquals(ProfitEngineV2.evaluate(offer(at = 1_000 * min), settings, ScoreV2Inputs(models.take(1))), before)
        assertTrue(after.score < before.score)
    }

    @Test fun pickupWaitsGiveARangeOnlyWithEnoughHistory() {
        assertNull(WaitEstimate.of(List(9) { 3 * min }))
        // Outliers (over 30 min) and negative waits are not waits
        assertNull(WaitEstimate.of(List(9) { 3 * min } + listOf(45 * min, -1)))
        val w = WaitEstimate.of((1..10).map { it * min })!!
        assertEquals(10, w.samples)
        assertEquals(3 * min, w.lowMs)
        assertEquals(5 * min, w.typicalMs)
        assertEquals(8 * min, w.highMs)
        val noWait = ProfitEngineV2.evaluate(offer(), settings, inputs)
        val r = ProfitEngineV2.evaluate(offer(), settings, inputs.copy(pickupWait = w))
        assertTrue(r.isRange)
        assertTrue(r.low <= r.score && r.score <= r.high)
        assertTrue(r.score < noWait.score)
        assertTrue(ScoreFactor.LONG_WAIT in r.factors)
        assertTrue(r.coverage.isEmpty())
        assertEquals(5 * min, r.waitTypicalMs)
    }

    @Test fun pickupWaitsComeFromRealStartedTripsOnly() {
        fun trip(id: String, arrived: Long?, started: Long?, cancelled: Long? = null) = Trip(
            id = id, lifecycleSessionId = "s-$id", relatedOfferHash = null,
            status = if (cancelled != null) TripStatus.CANCELLED else TripStatus.COMPLETED, recovered = false,
            startedFromKnownOffer = false, arrivedAt = arrived, tripStartedAt = started, cancelledAt = cancelled,
            completedAt = started?.plus(10 * min), createdAt = 0, updatedAt = 0
        )
        val waits = ScoreHistory.pickupWaits(
            listOf(
                trip("a", 0, 4 * min), trip("b", 0, null, cancelled = 6 * min),
                trip("TEST-c", 0, 2 * min), trip("d", null, 5 * min)
            )
        )
        assertEquals(listOf(4 * min), waits)
    }

    @Test fun shadowComparesV2WithV1WithoutChangingTheActiveScore() {
        val active = ProfitModelVersion("v1", 1, 0, ModelVersionSource.INITIAL, settings)
        val v2 = ModelVersionRules.v2Candidate(listOf(active), settings, 5) { "c2" }
        assertEquals(ProfitEngineV2.ALGORITHM_VERSION, v2.algorithmVersion)
        assertEquals(ModelVersionSource.CALIBRATION_RECOMMENDATION, v2.source)
        assertEquals(2, v2.number)
        // Never active by itself, and reused instead of duplicated
        assertEquals(active, ModelVersionRules.active(listOf(active, v2)))
        assertEquals(v2, ModelVersionRules.v2Candidate(listOf(active, v2), settings, 9) { "other" })

        val e = ShadowScoring.evaluate("h1", offer(), active, v2, 7, inputs)!!
        assertEquals(75, e.activeScore)
        assertEquals(65, e.candidateScore)
        assertNull(ShadowScoring.evaluate("h1", offer(), active, v2, 7, ScoreV2Inputs.NONE))
    }

    @Test fun v2CannotBePromotedInThisVersion() {
        val active = ProfitModelVersion("v1", 1, 0, ModelVersionSource.INITIAL, settings)
        val v2 = ModelVersionRules.v2Candidate(listOf(active), settings, 5) { "c2" }
        val report = ShadowAnalyzer.analyze(v2.id, emptyList(), emptyList(), 300.0, v2.algorithmVersion)
        assertTrue(ShadowGateReason.SHADOW_ONLY in report.blockers)
        // Even a report that looked ready, with the driver's confirmation, is refused
        val ready = report.copy(readyForReview = true, blockers = emptySet())
        assertEquals(
            ModelVersionRules.PromotionResult.Refused(setOf(ShadowGateReason.SHADOW_ONLY)),
            ModelVersionRules.promote(listOf(active, v2), v2, ready, userConfirmed = true, nowMs = 9, newId = { "p" })
        )
        assertFalse(ShadowGateReason.SHADOW_ONLY in ShadowAnalyzer.analyze("v1", emptyList(), emptyList(), 300.0).blockers)
    }

    @Test fun aStoredVersionKeepsItsFormula() {
        val v2 = ModelVersionRules.v2Candidate(emptyList(), settings, 5) { "c2" }
        val back = v2.toEntity().toDomain()
        assertEquals(v2, back)
        assertEquals("V2", ScoringAlgorithms.label(back.algorithmVersion))
        assertNotNull(ScoringAlgorithms.score(back.algorithmVersion, offer(), back.settings, inputs))
    }
}
