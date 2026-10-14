package com.ubers.observador

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.DayBar
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReports
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.finance.EarningsPeriods
import com.ubers.observador.domain.finance.FinancialAnomaly
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.reports.Csv
import com.ubers.observador.domain.reports.DailyExport
import com.ubers.observador.domain.reports.DayExportRow
import com.ubers.observador.domain.reports.Decimals
import com.ubers.observador.domain.reports.ExpenseExport
import com.ubers.observador.domain.reports.ExportNames
import com.ubers.observador.domain.reports.Json
import com.ubers.observador.domain.reports.PeriodSummary
import com.ubers.observador.domain.reports.PeriodSummaryText
import com.ubers.observador.domain.reports.TripExport
import com.ubers.observador.domain.reports.WeeklyReports
import com.ubers.observador.domain.reports.WeeklySchedule
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Fase 8D: files with exact decimals and no personal data; the weekly summary uses the 8C figures. */
class ReportsTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val clock = WorkdayClock(4, zone)
    private val minute = 60_000L
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun dop(minor: Long) = MoneyAmount(minor, Currencies.DOP)

    private fun trip(id: String, completedAt: Long, status: TripStatus = TripStatus.COMPLETED) = Trip(
        id = id, lifecycleSessionId = "s-$id", relatedOfferHash = null, status = status, recovered = false,
        startedFromKnownOffer = true, acceptedAt = completedAt - 20 * minute,
        completedAt = completedAt.takeIf { status == TripStatus.COMPLETED }, cancelledAt = completedAt.takeIf { status == TripStatus.CANCELLED },
        createdAt = completedAt - 25 * minute, updatedAt = completedAt,
        offer = TripOfferSnapshot(150.0, 1.0, 3, 5.0, 15, 70, completedAt - 25 * minute)
    )

    private fun confirmed(id: String, minor: Long, tip: Long = 0) = TripFinancialSummary(
        tripId = id, currencyCode = Currencies.DOP, offeredAmount = dop(15_000), latestAbsoluteTotal = dop(minor),
        effectiveEarnings = dop(minor), tip = tip, fare = minor - tip, completeness = FinancialCompleteness.CONFIRMED
    )

    private fun unknown(id: String) = TripFinancialSummary(id, null, null, null, null, completeness = FinancialCompleteness.UNKNOWN)

    // ---- Exact decimals ----

    @Test fun decimalsComeExactlyFromMinorUnits() {
        assertEquals("1978.38", Decimals.of(197_838, Currencies.DOP))
        assertEquals("-20.00", Decimals.of(-2_000, Currencies.DOP))
        assertEquals("0.00", Decimals.of(0, Currencies.DOP))
        assertEquals("0.01", Decimals.of(1, Currencies.DOP))
        assertEquals("", Decimals.of(null))
        assertEquals("0.300", Decimals.gallons(100 + 200))
        // A long sum of centavos stays exact
        assertEquals("1000000.01", Decimals.of((1..100_000).sumOf { 1_000L } + 1, Currencies.DOP))
    }

    @Test fun csvHasBomCrlfAndEscapes() {
        val csv = Csv.build(listOf("a", "b"), listOf(listOf("x,y", "di \"hola\"")))
        assertTrue(csv.startsWith("﻿"))
        assertEquals("﻿a,b\r\n\"x,y\",\"di \"\"hola\"\"\"\r\n", csv)
    }

    @Test fun jsonEscapesAndIsValid() {
        val text = Json.obj("t" to Json.str("a\"b\\c\nd"), "n" to Json.num("12.50"), "e" to Json.num(""), "l" to Json.arr(listOf("1", "2")))
        assertTrue(JsonCheck.valid(text))
        assertTrue(text.contains("\"a\\\"b\\\\c\\nd\""))
        assertTrue(text.contains("\"e\":null"))
        assertFalse(JsonCheck.valid("{\"a\":}"))
    }

    // ---- Trips file ----

    @Test fun tripsFileUsesRealMoneyOnly() {
        val trips = listOf(trip("t1", at(2026, 10, 6, 10)), trip("t2", at(2026, 10, 6, 11)), trip("t3", at(2026, 10, 6, 12), TripStatus.CANCELLED))
        val sums = mapOf(
            "t1" to confirmed("t1", 19_150, tip = 5_000),
            "t2" to confirmed("t2", 9_000).copy(completeness = FinancialCompleteness.PARTIAL, effectiveEarnings = null, latestAbsoluteTotal = null, anomalies = setOf(FinancialAnomaly.LOW_CONFIDENCE))
        )
        val rows = TripExport.rows(trips, sums, clock)
        assertEquals(listOf("t1", "t2", "t3"), rows.map { it.tripId })
        val csv = TripExport.csv(rows, zone)
        val lines = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(TripExport.HEADER.joinToString(","), lines[0])
        assertEquals(4, lines.size)
        assertEquals(20, TripExport.HEADER.size)
        val t1 = lines[1].split(",")
        assertEquals("150.00", t1[6]) // offered: a reference column
        assertEquals("191.50", t1[7]) // real
        assertEquals("50.00", t1[9]) // tip
        assertEquals("CONFIRMED", t1[14])
        assertEquals("DOP", t1[16])
        val t2 = lines[2].split(",")
        assertEquals("", t2[7]) // no real money: empty, never the offer
        assertEquals("LOW_CONFIDENCE", t2[15])
        assertEquals("CANCELLED", lines[3].split(",")[5])
        val json = TripExport.json(rows, zone, at(2026, 10, 7, 9), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6))
        assertTrue(JsonCheck.valid(json))
        assertTrue(json.contains("\"real\":191.50"))
        assertTrue(json.contains("\"real\":null"))
        assertTrue(json.contains(PeriodSummaryText.NOTE))
    }

    @Test fun filesNeverCarryCoordinatesButKeepWhatUberShowed() {
        val t = trip("t1", at(2026, 10, 6, 10)).let {
            it.copy(riderName = "Stefany", offer = it.offer!!.copy(pickupAddress = "Calle 5, Los Alcarrizos", dropoffAddress = "Aeropuerto Las Américas"))
        }
        val rows = TripExport.rows(listOf(t), mapOf("t1" to confirmed("t1", 100)), clock)
        val json = TripExport.json(rows, zone, 0, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6))
        val all = ((TripExport.HEADER + ExpenseExport.HEADER + DailyExport.HEADER).joinToString(",") + json).lowercase()
        listOf("lat", "lon", "latitude", "longitude", "coord").forEach { assertFalse("found $it", Regex("\\b$it").containsMatchIn(all)) }
        assertTrue(JsonCheck.valid(json))
        assertTrue(json.contains("\"riderName\":\"Stefany\""))
        val csv = TripExport.csv(rows, zone).removePrefix("\uFEFF").split("\r\n")
        // The address has a comma: the CSV quotes it
        assertTrue(csv[1].endsWith(",Stefany,\"Calle 5, Los Alcarrizos\",Aeropuerto Las Américas"))
    }

    // ---- Expenses file ----

    @Test fun expensesFileFiltersByWorkdayWithExactGallons() {
        val fuel = listOf(
            FuelEntry("f1", at(2026, 10, 6, 3), 1_250, 37_500, odometerKm = 12_000, fullTank = true, createdAt = 0, updatedAt = 0), // Monday 3:00 = Sunday
            FuelEntry("f2", at(2026, 10, 7, 9), 1_000, 30_000, fullTank = false, createdAt = 0, updatedAt = 0)
        )
        val expenses = listOf(ExpenseEntry("e1", at(2026, 10, 7, 10), ExpenseType.OIL, 45_000, odometerKm = 12_100, createdAt = 0, updatedAt = 0))
        val rows = ExpenseExport.rows(fuel, expenses, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 12), clock)
        assertEquals(listOf("f2", "e1"), rows.map { it.id })
        val csv = ExpenseExport.csv(rows, zone).removePrefix("﻿").split("\r\n")
        assertEquals("f2,2026-10-07,2026-10-07,09:00:00,FUEL,FUEL,300.00,DOP,1.000,no,", csv[1])
        assertEquals("e1,2026-10-07,2026-10-07,10:00:00,MAINTENANCE,OIL,450.00,DOP,,,12100", csv[2])
        assertTrue(JsonCheck.valid(ExpenseExport.json(rows, zone, 0, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 12))))
    }

    // ---- Daily file and the report's totals equal Ganancias ----

    @Test fun dailyRowsAddUpToThePeriod() {
        val now = at(2026, 10, 9, 20)
        val w = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock)
        val trips = listOf(trip("t1", at(2026, 10, 5, 9)), trip("t2", at(2026, 10, 6, 10)), trip("t3", at(2026, 10, 6, 11)))
        val report = EarningsReports.build(w, clock, trips, mapOf("t1" to confirmed("t1", 20_000), "t2" to confirmed("t2", 15_000)), emptyList())
        val rows = report.bars.map { DayExportRow.of(it, null, if (it.day == LocalDate.of(2026, 10, 6)) 5_000 else 0, Currencies.DOP) }
        assertEquals(report.summary.totalObservedEarnings, rows.sumOf { it.incomeMinor })
        val tuesday = rows.first { it.day == LocalDate.of(2026, 10, 6) }
        assertEquals(50, tuesday.coveragePct)
        assertEquals(10_000, tuesday.netMinor)
        val csv = DailyExport.csv(rows).removePrefix("﻿").split("\r\n")
        assertEquals("2026-10-06,150.00,2,1,50,,50.00,100.00,DOP", csv[2])
        assertEquals("2026-10-07,0.00,0,0,,,0.00,0.00,DOP", csv[3])
    }

    // ---- Weekly summary ----

    private fun income(total: Long, confirmed: Int, completed: Int) =
        EarningsPeriods.summarize(Currencies.DOP, (1..completed).map { i ->
            if (i <= confirmed) confirmed("w$i", if (i == 1) total else 0) else unknown("w$i")
        }, emptyList())

    @Test fun comparisonOnlyWithEightyPercentCoverage() {
        val (c, why) = WeeklyReports.compare(income(110_000, 8, 10), income(100_000, 9, 10))
        assertNotNull(c)
        assertNull(why)
        assertEquals(10_000, c!!.differenceMinor)
        assertEquals(BigDecimal("10.0"), c.percent)
        val (none, reason) = WeeklyReports.compare(income(110_000, 7, 10), income(100_000, 10, 10))
        assertNull(none)
        assertEquals(WeeklyReports.NOT_COMPARABLE_MONEY, reason)
        assertEquals(WeeklyReports.NOT_COMPARABLE_EMPTY, WeeklyReports.compare(income(1, 1, 1), income(0, 0, 0)).second)
    }

    @Test fun weeklyReportUsesTheGanancias() {
        val now = at(2026, 10, 12, 10)
        val clockWeek = EarningsWindows.of(EarningsRange.WEEK, -1, now, clock)
        val trips = listOf(trip("t1", at(2026, 10, 6, 9)), trip("t2", at(2026, 10, 7, 9)))
        val report = EarningsReports.build(clockWeek, clock, trips, mapOf("t1" to confirmed("t1", 60_000), "t2" to confirmed("t2", 40_000)), emptyList())
        val w = WeeklyReports.build(report, null, 5 * 3_600_000L, 60, 20_000, 30_000, goalMinor = 90_000)
        assertEquals(100_000, w.income.totalObservedEarnings)
        assertEquals(70_000L, w.netMinor)
        assertEquals(true, w.goalReached)
        assertEquals(LocalDate.of(2026, 10, 6), w.bestDay?.day)
        assertEquals(WeeklyReports.NOT_COMPARABLE_EMPTY, w.notComparable)
        assertEquals("RD$1,000.00 · 2 de 2 viajes con dinero confirmado", WeeklyReports.notificationText(w))
        assertNull(WeeklyReports.build(report, null, null, null, null, null, 0).goalReached)
    }

    @Test fun weeklyAlarmFiresAfterTheWeekStarts() {
        // Wednesday: next Monday 4:15
        assertEquals(at(2026, 10, 12, 4, 15), WeeklySchedule.nextTrigger(at(2026, 10, 7, 12), clock, DayOfWeek.MONDAY))
        // Monday 4:05, before the delay: today 4:15
        assertEquals(at(2026, 10, 12, 4, 15), WeeklySchedule.nextTrigger(at(2026, 10, 12, 4, 5), clock, DayOfWeek.MONDAY))
        // Monday 4:15 exactly: already fired, the next week
        assertEquals(at(2026, 10, 19, 4, 15), WeeklySchedule.nextTrigger(at(2026, 10, 12, 4, 15), clock, DayOfWeek.MONDAY))
        // Monday 3:00 is still Sunday's working day: today 4:15
        assertEquals(at(2026, 10, 12, 4, 15), WeeklySchedule.nextTrigger(at(2026, 10, 12, 3), clock, DayOfWeek.MONDAY))
        assertEquals(at(2026, 10, 11, 4, 15), WeeklySchedule.nextTrigger(at(2026, 10, 7, 12), clock, DayOfWeek.SUNDAY))
    }

    // ---- PDF lines ----

    @Test fun pdfLinesHaveTheNoteAndNeverTheOffer() {
        val w = EarningsWindows.of(EarningsRange.WEEK, -1, at(2026, 10, 12, 10), clock)
        val s = PeriodSummary(
            w, income(100_000, 1, 2), 5 * 3_600_000L, 60, 20_000, 120, 30_000, 40_000, 75,
            DayBar(LocalDate.of(2026, 10, 6), 100_000, 2, 1), 0
        )
        val lines = PeriodSummaryText.lines(s)
        val map = lines.toMap()
        assertEquals("al menos RD$1,000.00", map["Ingreso bruto real"])
        assertEquals("1 de 2 viajes con dinero confirmado", map["Cobertura"])
        assertEquals("RD$700.00 (parcial)", map["Ganancia neta"])
        assertEquals("RD$300.00 (75 % de RD$400.00)", map["Gastos de trabajo"])
        assertTrue(PeriodSummaryText.NOTE.contains("No es un documento oficial de Uber"))
        assertFalse(lines.any { it.first.contains("ofert", ignoreCase = true) })
        // A day has no net (decision 4)
        val day = s.copy(window = EarningsWindows.of(EarningsRange.DAY, 0, at(2026, 10, 12, 10), clock))
        assertNull(PeriodSummaryText.lines(day).toMap()["Ganancia neta"])
    }

    @Test fun fileNames() {
        assertEquals("observador_viajes_2026-10-05_2026-10-11.csv", ExportNames.of("viajes", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), "csv"))
        assertEquals("observador_resumen_2026-10-05.pdf", ExportNames.of("resumen", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), "pdf"))
    }
}

/** A strict little JSON checker for the tests (no Android classes on the JVM). */
object JsonCheck {
    fun valid(text: String): Boolean = try {
        val p = P(text); p.ws(); p.value(); p.ws(); p.i == text.length
    } catch (e: Exception) { false }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { if (i >= s.length || s[i] != c) throw IllegalStateException("expected $c at $i"); i++ }
        fun value() {
            ws()
            when {
                s.startsWith("null", i) -> i += 4
                s.startsWith("true", i) -> i += 4
                s.startsWith("false", i) -> i += 5
                s[i] == '"' -> string()
                s[i] == '{' -> obj()
                s[i] == '[' -> arr()
                else -> number()
            }
        }
        fun string() {
            expect('"')
            while (s[i] != '"') {
                if (s[i] < ' ') throw IllegalStateException("control char")
                if (s[i] == '\\') { i++; if (s[i] == 'u') i += 4 else if (s[i] !in "\"\\/bfnrt") throw IllegalStateException("bad escape") }
                i++
            }
            i++
        }
        fun number() {
            val m = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").find(s, i) ?: throw IllegalStateException("number")
            if (m.range.first != i) throw IllegalStateException("number at $i")
            i = m.range.last + 1
        }
        fun obj() {
            expect('{'); ws()
            if (s[i] == '}') { i++; return }
            while (true) { ws(); string(); ws(); expect(':'); value(); ws(); if (s[i] == ',') { i++; continue }; expect('}'); return }
        }
        fun arr() {
            expect('['); ws()
            if (s[i] == ']') { i++; return }
            while (true) { value(); ws(); if (s[i] == ',') { i++; continue }; expect(']'); return }
        }
    }
}
