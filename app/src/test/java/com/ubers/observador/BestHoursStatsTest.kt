package com.ubers.observador

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.domain.BestHours
import com.ubers.observador.domain.BestHoursStats
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.SampleConfidence
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.TimeSlotStats
import com.ubers.observador.domain.WorkdayClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BestHoursStatsTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val defaults = EngineSettings()
    private val cuts = QualityThresholds(goodFrom = 60, excellentFrom = 80)

    // Raw offers with stale derived values on purpose: the calculation must rescore them
    private fun offer(
        at: ZonedDateTime,
        price: Double = 150.0,
        pickupKm: Double = 0.8,
        pickupMin: Int = 2,
        tripKm: Double = 5.0,
        tripMin: Int = 12,
        storedScore: Int = 0
    ) = OfferEntity(
        offerHash = "x", detectedAt = at.toInstant().toEpochMilli(), price = price,
        pickupKm = pickupKm, pickupMin = pickupMin, tripKm = tripKm, tripMin = tripMin,
        totalKm = 0.0, totalMin = 0, rdpPerKm = 0.0, rdpPerHour = 0.0,
        fuelCost = 0.0, netProfit = 0.0, score = storedScore
    )

    // Templates with known scores under factory settings
    private fun excellent(at: ZonedDateTime) = offer(at, 150.0, 0.8, 2, 5.0, 12)      // 95
    private fun good(at: ZonedDateTime) = offer(at, 75.50, 1.8, 4, 3.5, 9)           // 76
    private fun bad(at: ZonedDateTime) = offer(at, 70.0, 4.5, 10, 6.0, 15)           // 35

    private fun at(hour: Int, minute: Int = 0, second: Int = 0, day: Int = 9) =
        ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, zone)

    private fun calc(offers: List<OfferEntity>, settings: EngineSettings = defaults, z: ZoneId = zone) =
        BestHours.calculate(offers, settings, cuts, z)

    private fun BestHoursStats.slot(start: Int) = slots.single { it.startHour == start }

    private fun allNumbers(s: TimeSlotStats) = listOfNotNull(
        s.medianRdpPerHour, s.p25RdpPerHour, s.p75RdpPerHour, s.medianRdpPerKm, s.medianScore,
        s.goodRate, s.excellentRate, s.goodOrBetterRate, s.medianPickupKm, s.medianPickupMinutes,
        s.offersPerHour, s.minutesPerOffer
    )

    // A valid slot built directly, for index and ranking rules
    private fun slot(
        start: Int,
        count: Int,
        hourly: Double,
        perKm: Double,
        goodOrBetter: Double,
        perHour: Double
    ) = TimeSlotStats(
        startHour = start, endHour = start + 2, offerCount = count,
        medianRdpPerHour = hourly, p25RdpPerHour = hourly, p75RdpPerHour = hourly,
        medianRdpPerKm = perKm, medianScore = 70.0,
        badCount = 0, goodCount = count, excellentCount = 0,
        goodRate = goodOrBetter, excellentRate = 0.0, goodOrBetterRate = goodOrBetter,
        medianPickupKm = 1.0, medianPickupMinutes = 3.0, activeDays = 1, offersPerHour = perHour,
        confidence = SampleConfidence.of(count), opportunityIndex = null
    )

    // ---- Slots (1-9) ----

    @Test fun `01 midnight belongs to 00-02`() = assertEquals(0, BestHours.timeSlotFor(at(0).toInstant().toEpochMilli(), zone))
    @Test fun `02 01_59_59 belongs to 00-02`() = assertEquals(0, BestHours.timeSlotFor(at(1, 59, 59).toInstant().toEpochMilli(), zone))
    @Test fun `03 02_00 belongs to 02-04`() = assertEquals(2, BestHours.timeSlotFor(at(2).toInstant().toEpochMilli(), zone))
    @Test fun `04 17_59_59 belongs to 16-18`() = assertEquals(16, BestHours.timeSlotFor(at(17, 59, 59).toInstant().toEpochMilli(), zone))
    @Test fun `05 18_00 belongs to 18-20`() = assertEquals(18, BestHours.timeSlotFor(at(18).toInstant().toEpochMilli(), zone))
    @Test fun `06 23_59_59 belongs to 22-00`() = assertEquals(22, BestHours.timeSlotFor(at(23, 59, 59).toInstant().toEpochMilli(), zone))

    @Test
    fun `07 there are exactly 12 slots`() {
        assertEquals(12, calc(emptyList()).slots.size)
        assertEquals(12, calc(TestOfferSeeder.generate(at(15, day = 10))).slots.size)
    }

    @Test
    fun `08 each offer lands in exactly one slot`() {
        val offers = TestOfferSeeder.generate(at(15, day = 10))
        assertEquals(offers.size, calc(offers).slots.sumOf { it.offerCount })
    }

    @Test
    fun `09 slots cover the day with no gaps`() {
        val slots = calc(emptyList()).slots
        assertEquals((0..22 step 2).toList(), slots.map { it.startHour })
        slots.zipWithNext().forEach { (a, b) -> assertEquals(a.endHour, b.startHour) }
        assertEquals(24, slots.last().endHour)
    }

    // ---- Empty data (10-14) ----

    @Test
    fun `10 no offers gives 12 empty slots`() {
        val stats = calc(emptyList())
        assertTrue(stats.slots.all { it.offerCount == 0 && it.medianRdpPerHour == null && it.opportunityIndex == null })
        assertEquals(0, stats.totalOffers)
    }

    @Test
    fun `11 no division by zero with empty slots`() {
        val s = calc(emptyList()).slot(18)
        assertEquals(0.0, s.goodOrBetterRate, 0.0)
        assertEquals(0.0, s.offersPerHour, 0.0)
        assertNull(s.minutesPerOffer)
    }

    @Test
    fun `12 no NaN anywhere`() {
        listOf(emptyList(), TestOfferSeeder.generate(at(15, day = 10))).forEach { offers ->
            calc(offers).slots.forEach { s -> assertFalse(allNumbers(s).any { it.isNaN() }) }
        }
    }

    @Test
    fun `13 no Infinity anywhere`() {
        listOf(emptyList(), TestOfferSeeder.generate(at(15, day = 10))).forEach { offers ->
            calc(offers).slots.forEach { s -> assertFalse(allNumbers(s).any { it.isInfinite() }) }
        }
    }

    @Test
    fun `14 no best slot without enough data`() {
        assertNull(calc(emptyList()).bestSlot)
        assertNull(calc(List(4) { excellent(at(18, it)) }).bestSlot)
    }

    // ---- Median (15-17) ----

    @Test fun `15 odd median`() = assertEquals(3.0, DashboardStats.median(listOf(5.0, 1.0, 3.0)), 1e-9)
    @Test fun `16 even median`() = assertEquals(2.5, DashboardStats.median(listOf(4.0, 1.0, 3.0, 2.0)), 1e-9)

    @Test
    fun `17 extreme values do not dominate the median`() {
        val values = listOf(300.0, 320.0, 330.0, 340.0, 950.0)
        assertEquals(330.0, DashboardStats.median(values), 1e-9)
        assertEquals(448.0, values.average(), 1e-9)
    }

    // ---- Percentiles (18-22): linear interpolation between closest ranks ----

    @Test
    fun `18 P25`() {
        assertEquals(2.0, DashboardStats.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.25)!!, 1e-9)
        assertEquals(17.5, DashboardStats.percentile(listOf(10.0, 20.0, 30.0, 40.0), 0.25)!!, 1e-9)
    }

    @Test
    fun `19 P50 is the median`() {
        assertEquals(3.0, DashboardStats.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.5)!!, 1e-9)
        val values = listOf(7.0, 1.0, 9.0, 4.0, 4.0, 12.0)
        assertEquals(DashboardStats.median(values), DashboardStats.percentile(values.sorted(), 0.5)!!, 1e-9)
    }

    @Test
    fun `20 P75`() {
        assertEquals(4.0, DashboardStats.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.75)!!, 1e-9)
        assertEquals(32.5, DashboardStats.percentile(listOf(10.0, 20.0, 30.0, 40.0), 0.75)!!, 1e-9)
    }

    @Test
    fun `21 one observation is every percentile`() {
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { p ->
            assertEquals(7.0, DashboardStats.percentile(listOf(7.0), p)!!, 0.0)
        }
        assertNull(DashboardStats.percentile(emptyList(), 0.5))
    }

    @Test
    fun `22 repeated values`() {
        listOf(0.25, 0.5, 0.75).forEach { p ->
            assertEquals(5.0, DashboardStats.percentile(listOf(5.0, 5.0, 5.0, 5.0), p)!!, 0.0)
        }
    }

    // ---- Confidence (23-29) ----

    @Test fun `23 zero offers is insufficient`() = assertEquals(SampleConfidence.INSUFFICIENT, SampleConfidence.of(0))
    @Test fun `24 four offers is insufficient`() = assertEquals(SampleConfidence.INSUFFICIENT, SampleConfidence.of(4))
    @Test fun `25 five offers is low`() = assertEquals(SampleConfidence.LOW, SampleConfidence.of(5))
    @Test fun `26 fourteen offers is low`() = assertEquals(SampleConfidence.LOW, SampleConfidence.of(14))
    @Test fun `27 fifteen offers is medium`() = assertEquals(SampleConfidence.MEDIUM, SampleConfidence.of(15))
    @Test fun `28 twenty-nine offers is medium`() = assertEquals(SampleConfidence.MEDIUM, SampleConfidence.of(29))
    @Test fun `29 thirty offers is high`() = assertEquals(SampleConfidence.HIGH, SampleConfidence.of(30))

    // ---- Quality (30-33) ----

    private val mixed = List(3) { excellent(at(18, it)) } + List(5) { good(at(18, 10 + it)) } + List(2) { bad(at(19, it)) }

    @Test fun `30 good count`() = assertEquals(5, calc(mixed).slot(18).goodCount)
    @Test fun `31 excellent count`() = assertEquals(3, calc(mixed).slot(18).excellentCount)

    @Test
    fun `32 good-or-better rate`() {
        val s = calc(mixed).slot(18)
        assertEquals(0.8, s.goodOrBetterRate, 1e-9)
        assertEquals(0.5, s.goodRate, 1e-9)
        assertEquals(0.3, s.excellentRate, 1e-9)
    }

    @Test
    fun `33 zero offers is 0 percent without error`() {
        val s = calc(mixed).slot(6)
        assertEquals(0.0, s.goodRate, 0.0)
        assertEquals(0.0, s.excellentRate, 0.0)
        assertEquals(0.0, s.goodOrBetterRate, 0.0)
    }

    // ---- Current score (34-36) ----

    private val strict = EngineSettings(minRdpPerKm = 12.0, minRdpPerHour = 400.0, maxPickupKm = 2.0, maxPickupMin = 4)

    @Test
    fun `34 changing the settings re-categorises offers`() {
        val offers = List(5) { good(at(18, it)) }
        assertEquals(5, calc(offers).slot(18).goodCount)
        assertEquals(5, calc(offers, strict).slot(18).badCount)
    }

    @Test
    fun `35 statistics follow the thresholds`() {
        val offers = List(5) { good(at(18, it)) }
        val before = calc(offers).slot(18)
        val after = calc(offers, strict).slot(18)
        assertEquals(76.0, before.medianScore!!, 1e-9)
        assertEquals(36.0, after.medianScore!!, 1e-9)
        assertEquals(1.0, before.goodOrBetterRate, 0.0)
        assertEquals(0.0, after.goodOrBetterRate, 0.0)
    }

    @Test
    fun `36 stored offers are never modified`() {
        val offers = List(5) { good(at(18, it)).copy(score = 12, rdpPerHour = 1.0) }
        val snapshot = offers.map { it.copy() }
        calc(offers, strict)
        assertEquals(snapshot, offers)
        assertTrue(offers.all { it.score == 12 })
    }

    // ---- Frequency (37-39) ----

    @Test
    fun `37 twenty offers in 2 hours is 10 per hour`() {
        val s = calc(List(20) { excellent(at(18).plusMinutes(it * 5L)) }).slot(18)
        assertEquals(10.0, s.offersPerHour, 1e-9)
        assertEquals(6.0, s.minutesPerOffer!!, 1e-9)
    }

    @Test
    fun `38 thirty offers in 2 hours is 15 per hour`() {
        val s = calc(List(30) { excellent(at(18).plusMinutes(it * 3L)) }).slot(18)
        assertEquals(15.0, s.offersPerHour, 1e-9)
        assertEquals(4.0, s.minutesPerOffer!!, 1e-9)
    }

    @Test
    fun `39 no offers gives no minutes per offer`() {
        val s = calc(emptyList()).slot(18)
        assertEquals(0.0, s.offersPerHour, 0.0)
        assertNull(s.minutesPerOffer)
    }

    @Test
    fun `39b the pace is per day with activity, not all days piled into one window`() {
        val offers = List(10) { excellent(at(18, it * 5, day = 8)) } + List(10) { excellent(at(18, it * 5, day = 9)) }
        val s = calc(offers).slot(18)
        assertEquals(2, s.activeDays)
        assertEquals(5.0, s.offersPerHour, 1e-9)
    }

    // ---- Opportunity index (40-44) ----

    @Test
    fun `40 the index is always between 0 and 100`() {
        val sets = listOf(
            TestOfferSeeder.generate(at(15, day = 10)),
            List(5) { offer(at(8, it), price = 5000.0) } + List(5) { offer(at(10, it), price = 1.0) },
            List(5) { excellent(at(18, it)) }
        )
        sets.forEach { offers ->
            calc(offers).slots.mapNotNull { it.opportunityIndex }.forEach { assertTrue(it in 0..100) }
        }
        val extreme = slot(18, 30, hourly = 1e9, perKm = 1e9, goodOrBetter = 1.0, perHour = 1e9)
        assertEquals(100, BestHours.calculateOpportunityIndex(extreme, 1.0, 1.0, 1.0))
        assertEquals(0, BestHours.calculateOpportunityIndex(slot(18, 30, 0.0, 0.0, 0.0, 0.0), 0.0, 0.0, 0.0))
    }

    @Test
    fun `41 an insufficient slot can never be the best`() {
        // 4 superb offers at 20-22 against 5 ordinary ones at 08-10
        val offers = List(4) { offer(at(20, it), price = 900.0) } + List(5) { good(at(8, it)) }
        val stats = calc(offers)
        assertEquals(8, stats.bestSlot!!.startHour)
        assertNull(stats.slot(20).opportunityIndex)
    }

    @Test
    fun `42 the highest pay per hour alone does not win`() {
        val stats = calc(TestOfferSeeder.generate(at(15, day = 10)))
        val evening = stats.slot(18)
        val night = stats.slot(20)
        assertTrue(night.medianRdpPerHour!! > evening.medianRdpPerHour!!)
        assertEquals(18, stats.bestSlot!!.startHour)
    }

    @Test
    fun `43 a balanced slot beats an extreme one`() {
        // A: 5 offers at RD$600/h, 40% good. B: 45 offers at RD$450/h, 80% good, busier
        val extreme = slot(8, 5, hourly = 600.0, perKm = 15.0, goodOrBetter = 0.4, perHour = 2.5)
        val balanced = slot(18, 45, hourly = 450.0, perKm = 15.0, goodOrBetter = 0.8, perHour = 4.5)
        val ranked = BestHours.rankSlots(BestHours.withOpportunityIndex(listOf(extreme, balanced)))
        assertEquals(18, ranked.first().startHour)
        assertTrue(ranked.first().opportunityIndex!! > ranked.last().opportunityIndex!!)
    }

    @Test
    fun `44 ties are broken the same way every time`() {
        val a = slot(10, 20, 400.0, 15.0, 0.7, 3.0)
        val b = slot(14, 20, 400.0, 15.0, 0.7, 3.0)
        val c = slot(6, 20, 400.0, 15.0, 0.7, 3.0)
        val first = BestHours.rankSlots(BestHours.withOpportunityIndex(listOf(a, b, c)))
        val second = BestHours.rankSlots(BestHours.withOpportunityIndex(listOf(b, c, a)))
        assertEquals(listOf(6, 10, 14), first.map { it.startHour })
        assertEquals(first, second)
        // Same index: the higher good-or-better share wins before anything else
        val better = slot(16, 20, 400.0, 15.0, 0.71, 3.0).copy(opportunityIndex = 80)
        val worse = slot(4, 20, 400.0, 15.0, 0.70, 3.0).copy(opportunityIndex = 80)
        assertEquals(16, listOf(worse, better).sortedWith(BestHours.RANKING).first().startHour)
    }

    // ---- Time zone (45-48) ----

    @Test
    fun `45 the zone decides the slot`() {
        val instant = ZonedDateTime.of(2026, 10, 9, 23, 30, 0, 0, ZoneId.of("UTC"))
        val millis = instant.toInstant().toEpochMilli()
        assertEquals(22, BestHours.timeSlotFor(millis, ZoneId.of("UTC")))
        assertEquals(18, BestHours.timeSlotFor(millis, zone))
    }

    @Test
    fun `46 local time is used, not UTC`() {
        // 19:30 in Santo Domingo is 23:30 UTC
        val stats = calc(listOf(excellent(at(19, 30))))
        assertEquals(1, stats.slot(18).offerCount)
        assertEquals(0, stats.slot(22).offerCount)
    }

    @Test
    fun `47 another zone gives another classification`() {
        val offers = listOf(excellent(at(19, 30)))
        assertEquals(1, calc(offers, z = ZoneId.of("Asia/Tokyo")).slot(8).offerCount)
        assertEquals(1, calc(offers, z = ZoneId.of("Europe/Madrid")).slot(0).offerCount)
    }

    @Test
    fun `48 1_30 am stays in 00-02 even though it counts for the previous working day`() {
        val lateOffer = excellent(at(1, 30, day = 10))
        assertEquals(LocalDate.of(2026, 10, 9), WorkdayClock(4, zone).workdayOf(lateOffer.detectedAt))
        assertEquals(1, calc(listOf(lateOffer)).slot(0).offerCount)
    }

    // ---- Ranking (49-52) ----

    private val fourValid = List(5) { excellent(at(18, it)) } + List(5) { good(at(12, it)) } +
        List(5) { bad(at(6, it)) } + List(5) { good(at(16, it)) }

    @Test fun `49 at most 3 ranked slots`() = assertEquals(3, calc(fourValid).rankedSlots.size)

    @Test
    fun `50 ranked best first`() {
        val ranked = calc(fourValid).rankedSlots
        ranked.zipWithNext().forEach { (a, b) -> assertTrue(a.opportunityIndex!! >= b.opportunityIndex!!) }
        assertEquals(18, ranked.first().startHour)
        assertEquals(ranked.first(), calc(fourValid).bestSlot)
    }

    @Test
    fun `51 insufficient slots are left out`() {
        val offers = fourValid + List(4) { offer(at(22, it), price = 2000.0) }
        assertTrue(calc(offers).rankedSlots.none { it.startHour == 22 })
    }

    @Test
    fun `52 one valid slot gives one ranked slot`() {
        val stats = calc(List(5) { good(at(10, it)) } + List(3) { excellent(at(18, it)) })
        assertEquals(listOf(10), stats.rankedSlots.map { it.startHour })
    }

    // ---- Test data (53-55) ----

    private val seeded = calc(TestOfferSeeder.generate(at(15, day = 10)))

    @Test fun `53 with the test offers 18-20 wins`() = assertEquals(18, seeded.bestSlot!!.startHour)

    @Test
    fun `54 20-22 pays more per hour but does not win`() {
        assertTrue(seeded.slot(20).medianRdpPerHour!! > seeded.slot(18).medianRdpPerHour!!)
        assertTrue(seeded.slot(20).opportunityIndex!! < seeded.slot(18).opportunityIndex!!)
    }

    @Test
    fun `55 00-02 shows as Pocos datos`() {
        assertEquals(SampleConfidence.INSUFFICIENT, seeded.slot(0).confidence)
        assertEquals(3, seeded.slot(0).offerCount)
        assertEquals("Pocos datos", seeded.slot(0).confidence.label)
    }

    @Test
    fun `56 18-20 wins whatever time the test offers are loaded`() {
        // "Today" lands in the 40 minutes before loading, in whichever slot that is
        for (minute in 0 until 24 * 60 step 10) {
            val now = ZonedDateTime.of(2026, 10, 10, 0, 0, 0, 0, zone).plusMinutes(minute.toLong())
            val stats = calc(TestOfferSeeder.generate(now))
            assertEquals("loaded at $now", 18, stats.bestSlot!!.startHour)
            if (minute >= 160) {
                assertEquals("loaded at $now", SampleConfidence.INSUFFICIENT, stats.slot(0).confidence)
            }
        }
    }

    @Test
    fun `57 the seeded top 3 is 18-20, 20-22 and 16-18`() {
        assertEquals(listOf(18, 20, 16), seeded.rankedSlots.map { it.startHour })
    }

    // ---- Wording ----

    @Test
    fun `58 labels follow the phone clock format`() {
        assertEquals("18–20", BestHours.slotLabel(18, 20, use24Hour = true))
        assertEquals("22–00", BestHours.slotLabel(22, 24, use24Hour = true))
        assertEquals("00–02", BestHours.slotLabel(0, 2, use24Hour = true))
        assertEquals("6–8 pm", BestHours.slotLabel(18, 20, use24Hour = false))
        assertEquals("10 am–12 pm", BestHours.slotLabel(10, 12, use24Hour = false))
        assertEquals("10 pm–12 am", BestHours.slotLabel(22, 24, use24Hour = false))
        assertEquals("12–2 am", BestHours.slotLabel(0, 2, use24Hour = false))
        assertEquals("6p", BestHours.axisLabel(18, use24Hour = false))
        assertEquals("08", BestHours.axisLabel(8, use24Hour = true))
    }

    @Test
    fun `59 TalkBack reads each bar as a sentence`() {
        assertEquals("De 6 de la tarde a 8 de la noche", BestHours.spokenRange(18, 20))
        assertEquals("De 2 a 4 de la tarde", BestHours.spokenRange(14, 16))
        assertEquals("De 12 de la noche a 2 de la madrugada", BestHours.spokenRange(0, 2))
        val text = BestHours.describe(seeded.slot(18))
        assertTrue(text.startsWith("De 6 de la tarde a 8 de la noche. 40 ofertas. Pago mediano 600 pesos por hora."))
        assertTrue(text.endsWith("Confianza alta."))
        assertEquals("De 6 a 8 de la mañana. Sin ofertas.", BestHours.describe(calc(emptyList()).slot(6)))
    }

    // ---- What the screen needs: periods, chart scale and wording (60-65) ----

    @Test
    fun `60 the chart offers 7 and 30 days and starts on 30`() {
        assertEquals(listOf(StatsPeriod.WEEK, StatsPeriod.MONTH), BestHours.PERIODS)
        assertEquals(StatsPeriod.MONTH, BestHours.DEFAULT_PERIOD)
        assertEquals(30, BestHours.DEFAULT_PERIOD.workdays)
    }

    @Test
    fun `61 the tallest bar keeps a margin below the top`() {
        val scale = BestHours.chartScale(seeded.slots, minRdpPerHour = 0.0)
        assertEquals(BestHours.SLOT_COUNT, scale.barFractions.size)
        assertEquals(1 / BestHours.CHART_HEADROOM, scale.barFractions.filterNotNull().max(), 1e-9)
        assertTrue(scale.barFractions.filterNotNull().all { it in 0.0..1.0 })
        assertNull(scale.minLineFraction)
    }

    @Test
    fun `62 no data gives no bars, no line and no NaN`() {
        val scale = BestHours.chartScale(calc(emptyList()).slots, minRdpPerHour = 300.0)
        assertTrue(scale.barFractions.all { it == null })
        assertNull(scale.minLineFraction)
    }

    @Test
    fun `63 empty slots draw nothing and the minimum line shows only when it fits`() {
        val slots = calc(List(5) { excellent(at(18, it)) }).slots
        val median = slots.single { it.startHour == 18 }.medianRdpPerHour ?: error("18-20 has offers")
        val top = median * BestHours.CHART_HEADROOM
        val scale = BestHours.chartScale(slots, minRdpPerHour = median / 2)
        assertNull(scale.barFractions[3])
        assertEquals(median / top, scale.barFractions[9] ?: error("18-20 has a bar"), 1e-9)
        assertEquals(median / 2 / top, scale.minLineFraction ?: error("the line fits"), 1e-9)
        assertNull(BestHours.chartScale(slots, minRdpPerHour = top * 2).minLineFraction)
        assertNull(BestHours.chartScale(slots, minRdpPerHour = 0.0).minLineFraction)
    }

    @Test
    fun `64 headline labels follow the phone clock format`() {
        assertEquals("18:00–20:00", BestHours.fullSlotLabel(18, 20, use24Hour = true))
        assertEquals("22:00–00:00", BestHours.fullSlotLabel(22, 24, use24Hour = true))
        assertEquals("6:00–8:00 pm", BestHours.fullSlotLabel(18, 20, use24Hour = false))
        assertEquals("10:00 am–12:00 pm", BestHours.fullSlotLabel(10, 12, use24Hour = false))
    }

    @Test
    fun `65 TalkBack names the best slot and reads each Top 3 row`() {
        val best = seeded.bestSlot ?: error("the seeded data has a best slot")
        assertTrue(
            BestHours.describe(best, isBest = true)
                .startsWith("De 6 de la tarde a 8 de la noche. Mejor franja observada. 40 ofertas.")
        )
        assertFalse(BestHours.describe(best).contains("Mejor franja"))
        val row = BestHours.describeRank(1, best)
        assertTrue(row.startsWith("Puesto 1. De 6 de la tarde a 8 de la noche. Índice "))
        assertTrue(row.endsWith("Pago mediano 600 pesos por hora. 40 ofertas."))
    }
}
