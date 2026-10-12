package com.ubers.observador

import com.ubers.observador.data.debug.IntelligenceTestSeeder
import com.ubers.observador.data.debug.IntelligenceTestSeeder.Scenario
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.ModelVersionSource
import com.ubers.observador.domain.OfferDecisionFeatures
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.ProfitSettingsSnapshot
import com.ubers.observador.domain.ScoreWeights
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.intelligence.CalibrationEngine
import com.ubers.observador.domain.intelligence.Coverage
import com.ubers.observador.domain.intelligence.DriftAnalyzer
import com.ubers.observador.domain.intelligence.DriftStatus
import com.ubers.observador.domain.intelligence.InsightType
import com.ubers.observador.domain.intelligence.IntelligenceAnomaly
import com.ubers.observador.domain.intelligence.IntelligenceDataset
import com.ubers.observador.domain.intelligence.IntelligenceDatasetBuilder
import com.ubers.observador.domain.intelligence.IntelligenceEngine
import com.ubers.observador.domain.intelligence.IntelligenceInput
import com.ubers.observador.domain.intelligence.IntelligenceSample
import com.ubers.observador.domain.intelligence.ModelHealth
import com.ubers.observador.domain.intelligence.ModelVersionRules
import com.ubers.observador.domain.intelligence.Monotonicity
import com.ubers.observador.domain.intelligence.OfferDecisionAnalyzer
import com.ubers.observador.domain.intelligence.OfferDecisionInput
import com.ubers.observador.domain.intelligence.OfferDecisionOutcome
import com.ubers.observador.domain.intelligence.OutcomeLabel
import com.ubers.observador.domain.intelligence.PredictionErrorAnalyzer
import com.ubers.observador.domain.intelligence.ProfitModelBacktester
import com.ubers.observador.domain.intelligence.RealBestHoursAnalyzer
import com.ubers.observador.domain.intelligence.RecommendationStatus
import com.ubers.observador.domain.intelligence.SampleConfidence
import com.ubers.observador.domain.intelligence.ScoreComponent
import com.ubers.observador.domain.intelligence.ScorePerformanceAnalyzer
import com.ubers.observador.domain.intelligence.ShadowAnalyzer
import com.ubers.observador.domain.intelligence.ShadowGateReason
import com.ubers.observador.domain.intelligence.ShadowReport
import com.ubers.observador.domain.intelligence.ShadowScoring
import com.ubers.observador.domain.intelligence.Spearman
import com.ubers.observador.domain.intelligence.Stats
import com.ubers.observador.domain.intelligence.TemporalSplit
import com.ubers.observador.domain.intelligence.ThresholdAnalyzer
import com.ubers.observador.domain.intelligence.WeightGrid
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripDecisionSnapshot
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Fase 7D: measure, validate, compare, explain. Never decide alone, never invent outcomes. */
class IntelligenceTest {

    private val minute = 60_000L
    private val utc = ZoneOffset.UTC
    private val defaults = IntelligenceTestSeeder.DEFAULT_SETTINGS
    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)
    private fun at(hour: Int, min: Int = 0) = LocalDateTime.of(2026, 10, 5, hour, min).toEpochSecond(utc) * 1000

    private fun trip(
        id: String = "t1",
        accepted: Long? = at(10),
        started: Long? = at(10, 8),
        completed: Long? = at(10, 30),
        offer: TripOfferSnapshot? = TripOfferSnapshot(150.0, 2.0, 6, 8.0, 18, 82, at(10) - minute),
        decision: TripDecisionSnapshot? = TripDecisionSnapshot(6.0, 300.0, 6.0, 12, 60, 80),
        status: TripStatus = TripStatus.COMPLETED,
        recovered: Boolean = false
    ) = Trip(
        id = id, lifecycleSessionId = "s-$id", relatedOfferHash = offer?.let { "h-$id" }, status = status,
        recovered = recovered, startedFromKnownOffer = offer != null, acceptedAt = accepted, tripStartedAt = started,
        completedAt = completed, createdAt = accepted ?: started ?: 0, updatedAt = completed ?: 0, offer = offer, decision = decision
    )

    private fun money(id: String, minor: Long?, completeness: FinancialCompleteness = FinancialCompleteness.CONFIRMED) =
        TripFinancialSummary(id, Currencies.DOP, null, minor?.let(::dop), minor?.let(::dop), completeness = completeness)

    private fun build(trips: List<Trip>, summaries: List<TripFinancialSummary>) =
        IntelligenceDatasetBuilder.build(trips, summaries.associateBy { it.tripId }, Currencies.DOP)

    /** A hand-made sample: score, realized RD$/h and optional extras. */
    private fun s(
        id: String,
        score: Int?,
        hourly: Double?,
        time: Long = 0,
        features: OfferDecisionFeatures? = null,
        category: OfferQuality? = score?.let { defaults.thresholds.classify(it) },
        accepted: Long? = time
    ) = IntelligenceSample(
        tripId = id, offerHash = "h-$id", detectedAtMs = time, acceptedAtMs = accepted, tripStartedAtMs = null, completedAtMs = time + 20 * minute,
        features = features, settingsAtDetection = defaults, scoreAtDetection = score, categoryAtDetection = category,
        predictedRdpPerHour = null, earningsPerEstimatedKm = null, estimatedEngagedMinutes = null,
        actualEarnings = hourly?.let { dop(10_000) }, financialCompleteness = FinancialCompleteness.CONFIRMED,
        actualEngagedMinutes = 20.0, actualInTripMinutes = null, realizedGrossPerEngagedHour = hourly,
        recovered = false, timelineValid = true
    )

    // ---- Dataset, gaps, coverage ----

    @Test fun completeTripBecomesACompleteSample() {
        val d = build(listOf(trip()), listOf(money("t1", 14_000)))
        val x = d.samples.single()
        assertEquals(dop(14_000), x.actualEarnings)
        assertEquals(30.0, x.actualEngagedMinutes!!, 1e-9)
        assertEquals(22.0, x.actualInTripMinutes!!, 1e-9)
        assertEquals(280.0, x.realizedGrossPerEngagedHour!!, 1e-9) // 140 / 30 min * 60
        assertEquals(375.0, x.predictedRdpPerHour!!, 1e-9) // 150 / 24 min * 60
        assertEquals(OfferQuality.EXCELLENT, x.categoryAtDetection)
        assertEquals(1, d.quality.usableForHourly)
    }

    @Test fun tripWithoutMoneyKeepsTimesButNoHourlyAndNeverTheOffer() {
        val d = build(listOf(trip()), listOf(money("t1", null, FinancialCompleteness.UNKNOWN)))
        val x = d.samples.single()
        assertNull(x.actualEarnings)
        assertNull(x.realizedGrossPerEngagedHour)
        assertEquals(30.0, x.actualEngagedMinutes!!, 1e-9)
        assertEquals(1, d.quality.withoutMoney)
        assertEquals(1, d.quality.withFullTimes)
    }

    @Test fun tripWithoutAcceptedAtHasEarningsButNoHourly() {
        val d = build(listOf(trip(accepted = null)), listOf(money("t1", 14_000)))
        val x = d.samples.single()
        assertEquals(dop(14_000), x.actualEarnings)
        assertNull(x.actualEngagedMinutes)
        assertNull(x.realizedGrossPerEngagedHour)
        assertEquals(22.0, x.actualInTripMinutes!!, 1e-9)
        assertEquals(1, d.quality.missingAcceptedAt)
    }

    @Test fun recoveredTripIsUsedOnlyWhereItsDataAllows() {
        val d = build(listOf(trip(accepted = null, offer = null, recovered = true)), listOf(money("t1", 14_000)))
        val x = d.samples.single()
        assertTrue(x.recovered)
        assertNull(x.features)
        assertNull(x.scoreAtDetection)
        assertEquals(dop(14_000), x.actualEarnings)
        assertNull(x.realizedGrossPerEngagedHour)
        assertEquals(1, d.quality.withoutOffer)
    }

    @Test fun coverageIsCountedPerMetric() {
        val trips = listOf(trip("a"), trip("b"), trip("c", accepted = null), trip("d"), trip("e", status = TripStatus.CANCELLED))
        val d = build(trips, listOf(money("a", 14_000), money("b", 15_000), money("c", 12_000)))
        assertEquals(4, d.quality.completedTrips)
        assertEquals(Coverage(4, 3), d.quality.financial)
        assertEquals(Coverage(4, 3), d.quality.time)
        assertEquals(Coverage(4, 2), d.quality.hourly)
        assertEquals(0.5, d.quality.hourly.ratio!!, 1e-9)
    }

    @Test fun inconsistentTimelineIsFlaggedNotDeleted() {
        val d = build(listOf(trip(completed = at(9))), listOf(money("t1", 14_000)))
        assertEquals(1, d.samples.size)
        assertFalse(d.samples.single().timelineValid)
        assertNull(d.samples.single().realizedGrossPerEngagedHour)
        assertEquals(1, d.quality.invalidTimeline)
    }

    @Test fun otherCurrencyIsNotMixed() {
        val usd = TripFinancialSummary(
            "t1", Currencies.USD, null, MoneyAmount(500, Currencies.USD), MoneyAmount(500, Currencies.USD),
            completeness = FinancialCompleteness.CONFIRMED
        )
        val d = build(listOf(trip()), listOf(usd))
        assertNull(d.samples.single().actualEarnings)
        assertEquals(1, d.quality.currencyMismatch)
    }

    // ---- Predicted vs realized, errors ----

    @Test fun predictedHourlyIsProfitEnginesOwnFigure() {
        val engine = ProfitEngine().analyze(UberOffer(150.0, 2.0, 6, 8.0, 18), EngineSettings())
        val x = build(listOf(trip()), listOf(money("t1", 14_000))).samples.single()
        assertEquals(engine.rdpPerHour, x.predictedRdpPerHour!!, 1e-12)
        assertEquals(engine.rdpPerKm, x.earningsPerEstimatedKm!!, 1e-12)
    }

    @Test fun errorsTimeHourlyAndMoney() {
        val x = build(listOf(trip()), listOf(money("t1", 14_000))).samples.single()
        assertEquals(6.0, x.timeErrorMinutes!!, 1e-9) // 30 real - 24 estimated
        assertEquals(0.25, x.timeErrorPercent!!, 1e-9)
        assertEquals(-95.0, x.hourlyError!!, 1e-9) // 280 - 375
        assertEquals(-95.0 / 375.0, x.hourlyErrorPercent!!, 1e-12)
        assertEquals(-1_000L, x.moneyDifferenceMinor) // 140.00 - 150.00
        assertEquals(-1_000.0 / 15_000.0, x.moneyDifferencePercent!!, 1e-12)
    }

    @Test fun endToEndOneTrip() {
        // Spec 247: offer -> accepted -> completed -> total; predicted RD$450/h, actual about RD$370/h
        val t = trip(offer = TripOfferSnapshot(150.0, 2.0, 6, 8.0, 14, 82, at(10) - minute), completed = at(10, 20))
        val x = build(listOf(t), listOf(money("t1", 12_333))).samples.single()
        assertEquals(450.0, x.predictedRdpPerHour!!, 1e-9)
        assertEquals(369.99, x.realizedGrossPerEngagedHour!!, 1e-9)
        assertEquals(-80.01, x.hourlyError!!, 1e-9)
        assertEquals(-0.1778, x.hourlyErrorPercent!!, 1e-4)
        assertEquals(82, x.scoreAtDetection)
        val report = IntelligenceEngine.analyze(input(build(listOf(t), listOf(money("t1", 12_333)))), ::newId)
        assertEquals(450.0, report.evaluation.predictedHourlyMedian!!, 1e-9)
        assertEquals(369.99, report.evaluation.realizedHourlyMedian!!, 1e-9)
    }

    @Test fun medianSignedAbsoluteAndPercentErrors() {
        val trips = listOf(10, 20, 30, 40).mapIndexed { i, est ->
            trip(
                "t$i",
                offer = TripOfferSnapshot(100.0, 1.0, est / 2, 5.0, est / 2, 50, 0),
                completed = at(10) + (est + (if (i % 2 == 0) 5 else -5)) * minute
            )
        }
        val r = PredictionErrorAnalyzer.analyze(build(trips, trips.map { money(it.id, 10_000) }).samples)
        val time = r.time!!
        assertEquals(0.0, time.signed.median, 1e-9) // +5, -5, +5, -5
        assertEquals(5.0, time.medianAbsolute, 1e-9)
        assertEquals(-5.0, time.signed.p25, 1e-9)
        assertEquals(4, time.coverage.valid)
        assertTrue(time.medianAbsolutePercent!! > 0)
        assertEquals(0L, r.money!!.medianMinor)
    }

    @Test fun percentilesAndMoneyMedianStayExact() {
        assertEquals(2.5, Stats.median(listOf(1.0, 2.0, 3.0, 4.0))!!, 1e-12)
        assertEquals(1.75, Stats.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.25)!!, 1e-12)
        assertNull(Stats.median(emptyList()))
        assertEquals(150L, Stats.medianMinor(listOf(100L, 200L)))
        assertEquals(151L, Stats.medianMinor(listOf(101L, 200L))) // 150.5 rounds half up
    }

    // ---- Buckets, categories, Spearman ----

    @Test fun scoreBucketsPutEachTripInItsRange() {
        val samples = listOf(s("a", 10, 200.0), s("b", 39, 250.0), s("c", 80, 450.0), s("d", 100, 480.0), s("e", 79, 380.0))
        val p = ScorePerformanceAnalyzer.analyze(samples, 300.0)
        assertEquals(listOf(1, 1, 0, 1, 2), p.buckets.map { it.tripCount })
        assertEquals(465.0, p.buckets[4].medianHourly!!, 1e-9)
    }

    @Test fun categoryPerformanceUsesTheRulesOfThatMoment() {
        val samples = (1..6).map { s("g$it", 65, 300.0 + it) } + (1..6).map { s("e$it", 85, 450.0 + it) } + (1..6).map { s("b$it", 30, 200.0 + it) }
        val p = ScorePerformanceAnalyzer.analyze(samples, 300.0)
        assertEquals(6, p.categories.getValue(OfferQuality.EXCELLENT).hourlyN)
        assertEquals(Monotonicity.INCREASING, p.categoryMonotonicity)
        assertEquals(6, p.matrix!!.count(OfferQuality.EXCELLENT, OutcomeLabel.MEETS_TARGET))
        assertEquals(6, p.matrix!!.count(OfferQuality.BAD, OutcomeLabel.BELOW_TARGET))
        assertEquals(0, p.matrix!!.favourableBelowTarget)
    }

    @Test fun spearmanAscendingDescendingTiesAndConstant() {
        val x = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, Spearman.rho(x, listOf(10.0, 20.0, 30.0, 40.0, 50.0))!!, 1e-12)
        assertEquals(-1.0, Spearman.rho(x, listOf(50.0, 40.0, 30.0, 20.0, 10.0))!!, 1e-12)
        assertEquals(listOf(1.0, 2.5, 2.5, 4.0), Spearman.ranks(listOf(10.0, 20.0, 20.0, 30.0)))
        // Ties: Pearson of the average ranks
        assertEquals(0.9486832980505138, Spearman.rho(listOf(1.0, 2.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0, 4.0))!!, 1e-12)
        assertNull(Spearman.rho(listOf(5.0, 5.0, 5.0, 5.0), listOf(1.0, 2.0, 3.0, 4.0)))
        assertNull(Spearman.rho(listOf(1.0, 2.0), listOf(1.0, 2.0)))
    }

    @Test fun nonMonotonicBucketsAreDetected() {
        val samples = (1..5).map { s("a$it", 50, 300.0) } + (1..5).map { s("b$it", 70, 420.0) } + (1..5).map { s("c$it", 90, 350.0) }
        assertEquals(Monotonicity.NON_MONOTONIC, ScorePerformanceAnalyzer.analyze(samples, 300.0).bucketMonotonicity)
        assertEquals(Monotonicity.INSUFFICIENT_DATA, ScorePerformanceAnalyzer.analyze(samples.take(5), 300.0).bucketMonotonicity)
    }

    @Test fun topBottomQuartileSpread() {
        val samples = (0 until 12).map { s("t$it", it * 8, 200.0 + it * 20) }
        val p = ScorePerformanceAnalyzer.analyze(samples, 300.0)
        assertEquals(400.0, p.topQuartileMedian!!, 1e-9) // 380, 400, 420
        assertEquals(220.0, p.bottomQuartileMedian!!, 1e-9) // 200, 220, 240
        assertEquals(180.0, p.topBottomSpread!!, 1e-9)
        assertNull(ScorePerformanceAnalyzer.analyze(samples.take(5), 300.0).topBottomSpread)
    }

    @Test fun categoriesThatBehaveTheSameAreReported() {
        val samples = (1..5).map { s("g$it", 65, 390.0) } + (1..5).map { s("e$it", 85, 392.0) }
        assertEquals(listOf(OfferQuality.GOOD to OfferQuality.EXCELLENT), ScorePerformanceAnalyzer.analyze(samples, 300.0).collapsedCategories)
    }

    // ---- Confidence, selection bias, unknown decisions ----

    @Test fun sampleConfidenceLevels() {
        assertEquals(SampleConfidence.INSUFFICIENT, SampleConfidence.of(9))
        assertEquals(SampleConfidence.EXPLORATORY, SampleConfidence.of(10))
        assertEquals(SampleConfidence.MODERATE, SampleConfidence.of(30))
        assertEquals(SampleConfidence.STRONG, SampleConfidence.of(100))
    }

    @Test fun acceptanceRateExcludesUnknownAndIsUnavailableWithoutDeclineDetection() {
        val offers = listOf(
            OfferDecisionInput("a", 85, OfferQuality.EXCELLENT, OfferDecisionOutcome.ACCEPTED),
            OfferDecisionInput("b", 90, OfferQuality.EXCELLENT, OfferDecisionOutcome.EXPIRED),
            OfferDecisionInput("c", 88, OfferQuality.EXCELLENT, OfferDecisionOutcome.UNKNOWN),
            OfferDecisionInput("d", 40, OfferQuality.BAD, OfferDecisionOutcome.UNKNOWN)
        )
        val known = OfferDecisionAnalyzer.summarize(offers, declineDetectionAvailable = true)
        assertEquals(0.5, known.acceptanceRate!!, 1e-12) // 1 accepted of 2 known; the 2 unknown never count
        assertEquals(1, known.highScoreNotAccepted)
        assertEquals(1, known.highScoreWithoutKnownDecision)
        assertNull(OfferDecisionAnalyzer.summarize(offers, OfferDecisionAnalyzer.DECLINE_DETECTION_AVAILABLE).acceptanceRate)
    }

    @Test fun backtestUsesOnlyAcceptedTripsWithAResult() {
        val f = OfferDecisionFeatures(dop(15_000), 2.0, 6, 8.0, 18, 0)
        val samples = listOf(s("a", 80, 300.0, features = f), s("b", 80, null, features = f), s("c", 80, 300.0, features = null))
        assertEquals(listOf("a"), ProfitModelBacktester.usable(samples).map { it.tripId })
    }

    // ---- Real best hours ----

    @Test fun realBestHoursUseAcceptedAtAndTwoHourSlots() {
        val samples = listOf(
            s("a", 80, 400.0, time = at(19, 55)), // accepted 19:55, ends after 20:00: still 18-20
            s("b", 80, 300.0, time = at(20, 0)),
            s("c", 80, 350.0, time = at(21, 59)),
            s("d", 80, 500.0, time = at(0, 0), accepted = null)
        )
        val r = RealBestHoursAnalyzer.analyze(samples, utc, 300.0)
        assertEquals(1, r.slots[9].performance.tripCount)
        assertEquals(2, r.slots[10].performance.tripCount)
        assertEquals(0, r.slots[0].performance.tripCount) // no acceptance time: not placed anywhere
        assertEquals(3, r.samplesWithAcceptance)
        assertTrue(r.slots[10].lowData)
        assertNull(r.byDayType)
    }

    @Test fun marketAndRealHoursStayApart() {
        val samples = (1..6).map { s("a$it", 80, 380.0, time = at(18, it)) }
        val r = RealBestHoursAnalyzer.analyze(samples, utc, 300.0, observedMarketHourlyBySlot = mapOf(9 to 450.0))
        val slot = r.slots[9]
        assertEquals(380.0, slot.performance.medianHourly!!, 1e-9)
        assertEquals(450.0, slot.observedMarketMedianHourly!!, 1e-9)
        assertEquals(380.0 / 450.0, slot.marketRealizationRatio!!, 1e-12)
    }

    // ---- Backtests, temporal split, leakage, overfit, recommendation, no auto apply ----

    private val now = at(23)

    private fun dataset(scenario: Scenario, count: Int = 100) = IntelligenceTestSeeder.generate(scenario, now, count).let { seed ->
        IntelligenceDatasetBuilder.build(seed.trips, seed.summaries(), Currencies.DOP)
    }

    private var ids = 0
    private fun newId() = "id-${++ids}"

    @Test fun currentModelHistoricalScoresAreReproduced() {
        val checks = dataset(Scenario.GOOD_MODEL).samples.mapNotNull { ProfitModelBacktester.reproduces(it) }
        assertTrue(checks.size > 80)
        assertTrue(checks.all { it })
    }

    @Test fun goodModelRanksTripsAndIsHealthy() {
        val d = dataset(Scenario.GOOD_MODEL)
        val p = ScorePerformanceAnalyzer.analyze(d.samples, 300.0)
        assertTrue("rho ${p.spearman}", p.spearman!! > 0.5)
        assertTrue(p.topBottomSpread!! > 0)
        assertEquals(SampleConfidence.MODERATE, d.quality.hourly.confidence)
    }

    @Test fun badModelIsFlagged() {
        val report = IntelligenceEngine.analyze(input(dataset(Scenario.BAD_MODEL)), ::newId)
        assertTrue("rho ${report.score.spearman}", report.score.spearman!! < 0)
        assertTrue(IntelligenceAnomaly.WEAK_SCORE_CORRELATION in report.anomalies)
        assertTrue(report.evaluation.health == ModelHealth.DEGRADED || report.evaluation.health == ModelHealth.CALIBRATION_RECOMMENDED)
    }

    @Test fun temporalSplitIsOldThenNewNeverRandom() {
        val samples = (1..100).map { s("t$it", 50, 300.0, time = it * 1000L) }.shuffled(java.util.Random(1))
        val split = TemporalSplit.split(samples)
        assertEquals(70, split.training.size)
        assertEquals(30, split.validation.size)
        assertTrue(split.training.maxOf { it.orderingTimeMs } < split.validation.minOf { it.orderingTimeMs })
    }

    @Test fun decisionModelCannotReceiveOutcomes() {
        // The only scoring entry point takes offer features + settings
        val m = ProfitEngine::class.java.methods.single { it.name == "evaluate" }
        assertEquals(listOf(OfferDecisionFeatures::class.java, ProfitSettingsSnapshot::class.java), m.parameterTypes.toList())
        val fields = OfferDecisionFeatures::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.toSet()
        assertEquals(setOf("offeredAmount", "pickupKm", "pickupMinutes", "tripKm", "tripMinutes", "detectedAtMs"), fields)
    }

    @Test fun calibrationFindsABetterModelAndRecommendsShadowOnly() {
        val r = CalibrationEngine.calibrate(dataset(Scenario.PICKUP_MATTERS).samples, defaults, "v1", 300.0, now, ::newId)
        assertEquals("flags ${r.flags} gain ${r.validationImprovement}", RecommendationStatus.SHADOW_RECOMMENDED, r.status)
        assertFalse(r.candidate!!.settings.weights == defaults.weights)
        assertEquals(defaults.copy(weights = r.candidate!!.settings.weights), r.candidate!!.settings) // only weights are searched
        assertTrue(r.validationImprovement!! >= 0.05)
    }

    @Test fun overfitCandidateIsRejected() {
        val r = CalibrationEngine.calibrate(dataset(Scenario.OVERFIT).samples, defaults, "v1", 300.0, now, ::newId)
        assertEquals(
            "train ${r.candidateTraining.spearman} vs ${r.currentTraining.spearman}; valid ${r.candidateValidation.spearman} vs ${r.currentValidation.spearman}",
            RecommendationStatus.REJECTED_OVERFIT, r.status
        )
        assertNull(r.candidate)
    }

    @Test fun fewTripsNeverGiveARecommendation() {
        val r = CalibrationEngine.calibrate(dataset(Scenario.PICKUP_MATTERS, count = 25).samples, defaults, "v1", 300.0, now, ::newId)
        assertEquals(RecommendationStatus.NOT_ENOUGH_DATA, r.status)
        assertNull(r.candidate)
    }

    @Test fun calibrationNeverChangesTheActiveModel() {
        val versions = listOf(ProfitModelVersion("v1", 1, 0, ModelVersionSource.INITIAL, defaults))
        val r = CalibrationEngine.calibrate(dataset(Scenario.PICKUP_MATTERS).samples, defaults, "v1", 300.0, now, ::newId)
        val after = versions + ModelVersionRules.recordCandidate(versions, r.candidate!!, "run-1")
        assertEquals("v1", ModelVersionRules.active(after)!!.id)
        assertEquals(defaults, ModelVersionRules.active(after)!!.settings)
    }

    @Test fun weightGridIsSmallAndAlwaysSumsTo100() {
        val grid = WeightGrid.all()
        assertEquals(84, grid.size)
        assertTrue(grid.all { it.isValid && it.kmPct >= 10 && it.pickupMinPct >= 10 })
    }

    // ---- Shadow, promotion, rollback, immutability ----

    private fun shadowSetup(): Triple<List<ProfitModelVersion>, ProfitModelVersion, ShadowReport> {
        val seed = IntelligenceTestSeeder.generate(Scenario.PICKUP_MATTERS, now)
        val d = IntelligenceDatasetBuilder.build(seed.trips, seed.summaries(), Currencies.DOP)
        val active = ProfitModelVersion("v1", 1, 0, ModelVersionSource.INITIAL, defaults)
        val r = CalibrationEngine.calibrate(d.samples, defaults, "v1", 300.0, now, ::newId)
        val candidate = ModelVersionRules.recordCandidate(listOf(active), r.candidate!!, "run-1")
        val evaluations = IntelligenceTestSeeder.shadow(seed, active, candidate)
        return Triple(listOf(active, candidate), candidate, ShadowAnalyzer.analyze(candidate.id, evaluations, d.samples, 300.0))
    }

    @Test fun shadowScoresTheSameOfferWithBothModels() {
        val f = OfferDecisionFeatures(dop(15_000), 2.0, 6, 8.0, 18, 0)
        val active = ProfitModelVersion("v1", 1, 0, ModelVersionSource.INITIAL, defaults)
        val candidate = ProfitModelVersion("c1", 2, 0, ModelVersionSource.CALIBRATION_RECOMMENDATION, defaults.copy(weights = ScoreWeights(20, 30, 20, 30)))
        val e = ShadowScoring.evaluate("h1", f, active, candidate, 5)
        assertEquals(ProfitEngine().analyze(f.toOffer(), defaults.toEngineSettings()).score, e.activeScore)
        assertEquals(ProfitModelBacktester.score(f, candidate.settings), e.candidateScore)
        assertEquals(ShadowScoring.evaluationId("h1", "c1"), e.evaluationId)
    }

    @Test fun shadowLinksResultsAndOpensTheGateOnlyWithEnoughEvidence() {
        val (_, candidate, report) = shadowSetup()
        assertTrue(report.withOutcome >= 30)
        assertTrue("active ${report.activeSpearman} candidate ${report.candidateSpearman}", report.candidateSpearman!! > report.activeSpearman!!)
        assertTrue(report.blockers.toString(), report.readyForReview)
        assertEquals(candidate.id, report.candidateVersionId)
        assertTrue(report.disagreementRate!! >= 0.0)
    }

    @Test fun shadowWithFewResultsIsNotReady() {
        val (_, candidate, _) = shadowSetup()
        val report = ShadowAnalyzer.analyze(candidate.id, emptyList(), emptyList(), 300.0)
        assertFalse(report.readyForReview)
        assertTrue(ShadowGateReason.TOO_FEW_RESULTS in report.blockers)
    }

    @Test fun promotionNeedsExplicitConfirmation() {
        val (versions, candidate, report) = shadowSetup()
        assertEquals(
            ModelVersionRules.PromotionResult.NotConfirmed,
            ModelVersionRules.promote(versions, candidate, report, userConfirmed = false, nowMs = 1, newId = ::newId)
        )
        val promoted = ModelVersionRules.promote(versions, candidate, report, userConfirmed = true, nowMs = 1, newId = ::newId)
            as ModelVersionRules.PromotionResult.Promoted
        assertEquals(ModelVersionSource.SHADOW_PROMOTED, promoted.version.source)
        assertEquals(candidate.settings, promoted.version.settings)
        assertEquals(promoted.version, ModelVersionRules.active(versions + promoted.version))
        val notReady = report.copy(readyForReview = false, blockers = setOf(ShadowGateReason.TOO_FEW_RESULTS))
        assertTrue(ModelVersionRules.promote(versions, candidate, notReady, true, 1, ::newId) is ModelVersionRules.PromotionResult.Refused)
    }

    @Test fun rollbackRestoresExactPreviousValues() {
        val v1 = ProfitModelVersion("v1", 1, 0, ModelVersionSource.INITIAL, defaults)
        val v2 = ProfitModelVersion("v2", 2, 10, ModelVersionSource.SHADOW_PROMOTED, defaults.copy(weights = ScoreWeights(20, 30, 20, 30), maxPickupKm = 4.0))
        val back = ModelVersionRules.rollback(listOf(v1, v2), 20, ::newId)!!
        assertEquals(ModelVersionSource.ROLLBACK, back.source)
        assertEquals(v1.settings, back.settings)
        assertEquals(3, back.number)
        assertNull(ModelVersionRules.rollback(listOf(v1), 20, ::newId))
    }

    @Test fun versionsAreAppendOnly() {
        val v1 = ProfitModelVersion("v1", 1, 0, ModelVersionSource.INITIAL, defaults)
        assertNull(ModelVersionRules.ensureCurrent(listOf(v1), defaults, 5, ::newId))
        val edited = ModelVersionRules.ensureCurrent(listOf(v1), defaults.copy(minRdpPerHour = 350.0), 5, ::newId)!!
        assertEquals(ModelVersionSource.USER_EDIT, edited.source)
        assertEquals(2, edited.number)
        assertEquals(300.0, v1.settings.minRdpPerHour, 0.0) // the old version is untouched
        assertEquals(ModelVersionSource.INITIAL, ModelVersionRules.ensureCurrent(emptyList(), defaults, 5, ::newId)!!.source)
    }

    @Test fun defaultWeightsKeepTodaysScoresIdentical() {
        val offer = UberOffer(136.06, 4.1, 7, 7.2, 11)
        val parts = ProfitEngine().breakdown(ProfitEngine().analyze(offer, EngineSettings()), EngineSettings())
        // The formula before Fase 7D, written out with the old constants
        val old = Math.round((parts.km * 35 + parts.hour * 30 + parts.pickupKm * 20 + parts.pickupMin * 15) / 100.0).toInt().coerceIn(0, 100)
        assertEquals(old, ProfitEngine().analyze(offer, EngineSettings()).score)
        assertEquals(ScoreWeights.DEFAULT, ScoreWeights.of(50, 50, 50, 50)) // invalid stored weights fall back
        assertEquals(ScoreWeights.DEFAULT, ScoreWeights.of(null, 30, 20, 15))
    }

    // ---- Pickup, thresholds, low data, drift, insights ----

    @Test fun pickupAnalysesAndThresholdBacktest() {
        val d = dataset(Scenario.PICKUP_MATTERS)
        val t = ThresholdAnalyzer.analyze(d.samples, 300.0)
        assertEquals(5, t.pickupKm.size)
        assertEquals(4, t.pickupMinutes.size)
        val four = t.pickupKmLimits.single { it.limit == 4.0 }
        assertTrue(four.within.medianHourly!! > four.beyond.medianHourly!!)
        assertEquals(four.within.tripCount + four.beyond.tripCount, d.samples.count { it.features != null })
        assertEquals(20, t.scoreThresholds.size) // 5 good x 4 excellent cut-offs
    }

    @Test fun lowSampleGivesNoRecommendationAndInsufficientData() {
        val report = IntelligenceEngine.analyze(input(dataset(Scenario.GOOD_MODEL, count = 8)), ::newId)
        assertEquals(RecommendationStatus.NOT_ENOUGH_DATA, report.calibration.status)
        assertEquals(ModelHealth.NEEDS_MORE_DATA, report.evaluation.health)
        assertEquals(IntelligenceAnomaly.INSUFFICIENT_DATA, report.anomalies.first())
        assertNull(report.topInsight)
    }

    @Test fun lowCoverageIsWarned() {
        val trips = (1..10).map { trip("t$it") }
        val report = IntelligenceEngine.analyze(input(build(trips, trips.take(5).map { money(it.id, 14_000) })), ::newId)
        assertTrue(IntelligenceAnomaly.LOW_FINANCIAL_COVERAGE in report.anomalies)
    }

    @Test fun driftNeedsEnoughDataOnBothSides() {
        val day = 86_400_000L
        val few = (1..5).map { s("r$it", 50, 300.0, time = now - it * day) } + (1..4).map { s("p$it", 50, 400.0, time = now - (15 + it) * day) }
        assertEquals(DriftStatus.INSUFFICIENT_DATA, DriftAnalyzer.analyze(few, now).status)
        val many = (1..25).map { s("r$it", 50, 300.0, time = now - it * day / 2) } +
            (1..25).map { s("p$it", 50, 400.0, time = now - 15 * day - it * day / 2) }
        assertEquals(DriftStatus.CHANGED, DriftAnalyzer.analyze(many, now).status)
        val drop = (1..5).map { s("r$it", 50, 300.0, time = now - it * day) } +
            (1..25).map { s("p$it", 50, 400.0, time = now - 15 * day - it * day / 2) }
        assertEquals(DriftStatus.CAPTURE_DROP, DriftAnalyzer.analyze(drop, now).status)
    }

    @Test fun insightNamesTheTimeBias() {
        val report = IntelligenceEngine.analyze(input(dataset(Scenario.PICKUP_MATTERS)), ::newId)
        assertEquals(InsightType.TIME_UNDERESTIMATED, report.topInsight!!.type)
        assertTrue(report.topInsight!!.sampleCount >= 10)
    }

    @Test fun componentCorrelationsExist() {
        val p = ScorePerformanceAnalyzer.analyze(dataset(Scenario.PICKUP_MATTERS).samples, 300.0)
        val pickup = p.components.single { it.component == ScoreComponent.PICKUP_MINUTES }
        assertTrue("rho ${pickup.rho}", pickup.rho!! < 0)
        assertEquals(4, p.components.size)
    }

    // ---- End to end ----

    @Test fun endToEndHundredTrips() {
        val d = dataset(Scenario.GOOD_MODEL)
        assertEquals(100, d.quality.completedTrips)
        assertEquals(5, d.quality.recovered)
        assertEquals(5, d.quality.withoutMoney)
        assertTrue(d.quality.usableForHourly.toString(), d.quality.usableForHourly in 85..95)
        val report = IntelligenceEngine.analyze(input(d), ::newId)
        assertEquals(report.score.buckets.sumOf { it.tripCount }, d.samples.count { it.scoreAtDetection != null })
        assertEquals(1.0, report.scoreReproduction.ratio!!, 0.0)
        assertTrue(report.score.spearman!! > 0.5)
        assertTrue(report.anomalies.none { it == IntelligenceAnomaly.INSUFFICIENT_DATA })
    }

    private fun input(d: IntelligenceDataset) = IntelligenceInput(
        dataset = d, activeSettings = defaults, activeVersion = null, targetHourly = 300.0, offers = emptyList(),
        shadowCandidate = null, shadowEvaluations = emptyList(), observedMarketHourlyBySlot = emptyMap(),
        periodStartMs = null, nowMs = now, zone = ZoneId.of("UTC")
    )
}
