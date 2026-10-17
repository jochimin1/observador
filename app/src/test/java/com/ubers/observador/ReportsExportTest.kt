package com.ubers.observador

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.TakenFilter
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.lifecycle.OfferCardAction
import com.ubers.observador.domain.lifecycle.OfferCards
import com.ubers.observador.domain.lifecycle.OfferProductReader
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.reports.ButtonFilter
import com.ubers.observador.domain.reports.Csv
import com.ubers.observador.domain.reports.ExportHistory
import com.ubers.observador.domain.reports.ExportRecord
import com.ubers.observador.domain.reports.OfferOrigin
import com.ubers.observador.domain.reports.OfferReport
import com.ubers.observador.domain.reports.PeriodTotalsBuilder
import com.ubers.observador.domain.reports.PeriodTotalsText
import com.ubers.observador.domain.reports.ReportFilters
import com.ubers.observador.domain.reports.ReportPeriod
import com.ubers.observador.domain.reports.ReportPeriods
import com.ubers.observador.domain.reports.ZipPack
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** Fase 8J: «Reportes y exportación». Modality on the card, filters, detail rows and totals that add up. */
class ReportsExportTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val clock = WorkdayClock(4, zone)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    // ---- modality read from the card ----

    private fun card(product: List<String>, price: String, action: String) =
        product + listOf(price, "Identidad digital verificada o Tarjeta de cré…", "4.90 (2041)", "A 2 min (0.5 km)", "Los Ríos",
            "Viaje: 10 min (3.9 km)", "Madre Teresa de Calcuta, Arroyo Manzano", action)

    @Test
    fun `the modality and Exclusivo are read above each price`() {
        val texts = listOf("Radar de solicitud de viaje") +
            card(listOf("Moto"), "DOP74.17", "Me interesa") +
            card(listOf("Moto", "Exclusivo"), "DOP72.47", "Aceptar")
        val cards = OfferCards.cards(texts)
        assertEquals(2, cards.size)
        val first = OfferProductReader.read(cards[0].header)
        assertEquals("Moto", first.product)
        assertFalse(first.exclusive)
        val second = OfferProductReader.read(cards[1].header)
        assertEquals("Moto", second.product)
        assertTrue(second.exclusive)
        // The header of the second card does not reach into the first card
        assertFalse(cards[1].header.any { it.contains("Madre Teresa") })
    }

    @Test
    fun `an unknown modality is kept as Uber wrote it`() {
        assertEquals("Moto Plus", OfferProductReader.read(listOf("Radar de solicitud de viaje", "Moto Plus")).product)
        assertNull(OfferProductReader.read(listOf("Radar de solicitud de viaje", "4.90 (2041)")).product)
        assertNull(OfferProductReader.read(emptyList()).product)
    }

    // ---- detail rows ----

    private fun offer(id: Long, price: Double, at: Long, product: String? = "Moto", action: OfferCardAction? = OfferCardAction.INTEREST, exclusive: Boolean? = false) =
        OfferEntity(
            id = id, offerHash = "h$id", detectedAt = at, price = price, pickupKm = 1.0, pickupMin = 3, tripKm = 4.0, tripMin = 10,
            totalKm = 5.0, totalMin = 13, rdpPerKm = 0.0, rdpPerHour = 0.0, fuelCost = 0.0, netProfit = 0.0, score = 50,
            pickupAddress = "Los Ríos", dropoffAddress = "Los Prados", cardAction = action?.storageKey, product = product, exclusive = exclusive
        )

    private fun trip(id: String, hash: String, status: TripStatus, accepted: Long, end: Long?) = Trip(
        id = id, lifecycleSessionId = "s-$id", relatedOfferHash = hash, status = status, recovered = false, startedFromKnownOffer = true,
        acceptedAt = accepted,
        completedAt = end.takeIf { status == TripStatus.COMPLETED },
        cancelledAt = end.takeIf { status == TripStatus.CANCELLED },
        interruptedAt = end.takeIf { status == TripStatus.INTERRUPTED },
        createdAt = accepted, updatedAt = end ?: accepted,
        offer = TripOfferSnapshot(100.0, 1.0, 3, 4.0, 10, 50, accepted - 5_000),
        riderName = "stefany"
    )

    private fun confirmed(tripId: String, minor: Long, tip: Long = 0) = TripFinancialSummary(
        tripId = tripId, currencyCode = Currencies.DOP, offeredAmount = MoneyAmount(10_000, Currencies.DOP),
        latestAbsoluteTotal = MoneyAmount(minor, Currencies.DOP), effectiveEarnings = MoneyAmount(minor, Currencies.DOP),
        tip = tip, completeness = FinancialCompleteness.CONFIRMED
    )

    private val engine = EngineSettings()
    private val cuts = QualityThresholds()

    private fun sample(): List<com.ubers.observador.domain.reports.OfferReportRow> {
        val offers = listOf(
            offer(1, 100.0, at(14, 10)),                                         // taken, completed, confirmed money
            offer(2, 74.17, at(14, 11)),                                         // only offered
            offer(3, 72.47, at(14, 12), action = OfferCardAction.ACCEPT, exclusive = true), // taken, closed without confirmation
            offer(4, 50.01, at(15, 9), product = null, action = null, exclusive = null),    // older row, offered
            offer(5, 119.41, at(15, 10), product = "Flash")                       // taken, cancelled
        )
        val trips = listOf(
            trip("t1", "h1", TripStatus.COMPLETED, at(14, 10, 1), at(14, 10, 20)),
            trip("t3", "h3", TripStatus.INTERRUPTED, at(14, 12, 1), at(14, 12, 25)),
            trip("t5", "h5", TripStatus.CANCELLED, at(15, 10, 1), at(15, 10, 4))
        )
        return OfferReport.rows(offers, trips, mapOf("t1" to confirmed("t1", 11_500, tip = 1_500)), engine, cuts, clock)
    }

    @Test
    fun `every offer becomes a row with its origin, status, modality and button`() {
        val rows = sample().associateBy { it.offerId }
        assertEquals(5, rows.size)
        assertEquals(OfferOrigin.TAKEN, rows.getValue(1).origin)
        assertEquals(TripStatus.COMPLETED, rows.getValue(1).tripStatus)
        assertEquals(11_500L, rows.getValue(1).realMinor)
        assertEquals(1_500L, rows.getValue(1).tipMinor)
        assertEquals(1_500L, rows.getValue(1).differenceMinor)
        assertEquals("stefany", rows.getValue(1).riderName)
        assertEquals(OfferOrigin.OFFERED, rows.getValue(2).origin)
        assertNull(rows.getValue(2).tripStatus)
        assertNull(rows.getValue(2).realMinor)
        assertTrue(rows.getValue(3).tripDone)
        assertEquals(true, rows.getValue(3).exclusive)
        assertEquals(OfferCardAction.ACCEPT, rows.getValue(3).button)
        assertEquals(OfferReport.NO_PRODUCT, rows.getValue(4).product)
        assertNull(rows.getValue(4).button)
        assertFalse(rows.getValue(5).tripDone)
        // The offered price is a reference in minor units, never the real money
        assertEquals(7_417L, rows.getValue(2).offeredMinor)
        // Judged with today's settings, like Inicio
        assertEquals(cuts.classify(rows.getValue(1).scoreToday), rows.getValue(1).quality)
    }

    @Test
    fun `filters combine and keep only matching rows`() {
        val rows = sample()
        assertEquals(listOf(1L, 3L, 5L), OfferReport.filter(rows, ReportFilters(origin = TakenFilter.TAKEN)).map { it.offerId }.sorted())
        assertEquals(listOf(2L, 4L), OfferReport.filter(rows, ReportFilters(origin = TakenFilter.OFFERED)).map { it.offerId }.sorted())
        assertEquals(listOf(3L), OfferReport.filter(rows, ReportFilters(button = ButtonFilter.ACCEPT)).map { it.offerId })
        assertEquals(listOf(5L), OfferReport.filter(rows, ReportFilters(product = "Flash")).map { it.offerId })
        assertEquals(listOf(1L), OfferReport.filter(rows, ReportFilters(origin = TakenFilter.TAKEN, button = ButtonFilter.INTEREST, product = "Moto")).map { it.offerId })
        val quality = rows.first().quality
        val byQuality = OfferReport.filter(rows, ReportFilters(quality = CategoryFilter.entries.first { it.quality == quality }))
        assertTrue(byQuality.all { it.quality == quality })
        assertEquals(rows, OfferReport.filter(rows, ReportFilters()))
        assertEquals(listOf("Moto", "Flash", OfferReport.NO_PRODUCT), OfferReport.products(rows))
        assertEquals("Tomados · Me interesa · Moto", ReportFilters(TakenFilter.TAKEN, button = ButtonFilter.INTEREST, product = "Moto").describe())
        assertEquals("Sin filtros", ReportFilters().describe())
    }

    @Test
    fun `totals add up to the detail rows`() {
        val rows = sample()
        val t = PeriodTotalsBuilder.of(rows, expensesWorkMinor = 2_000)
        assertEquals(5, t.count.all)
        assertEquals(2, t.count.offered)
        assertEquals(3, t.count.taken)
        assertEquals(60, t.takenPercent)
        assertEquals(1, t.completed)
        assertEquals(1, t.unconfirmed)
        assertEquals(1, t.cancelled)
        assertEquals(0, t.inProgress)
        assertEquals(rows.sumOf { it.offeredMinor }, t.offeredRefMinor)
        assertEquals(41_606L, t.offeredRefMinor)
        // Only confirmed money is income; the offer never is
        assertEquals(11_500L, t.realMinor)
        assertEquals(1, t.confirmedTrips)
        assertEquals(2, t.doneTrips)
        assertEquals(1_500L, t.tipsMinor)
        assertEquals(9_500L, t.netMinor)
        assertEquals(t.count.all, t.byQuality.values.sumOf { it.all })
        assertEquals(t.count.taken, t.byQuality.values.sumOf { it.taken })
        assertEquals(t.count.all, t.byButton.sumOf { it.all })
        assertEquals(t.count.all, t.byProduct.sumOf { it.count.all })
        assertEquals(t.count.all, t.days.sumOf { it.count.all })
        assertEquals(t.offeredRefMinor, t.days.sumOf { it.offeredRefMinor })
        assertEquals(t.realMinor, t.days.sumOf { it.realMinor })
        assertEquals(listOf(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15)), t.days.map { it.day })
        assertEquals(150L, t.takenKmTenths) // 3 taken × 5.0 km
        val moto = t.byProduct.first { it.product == "Moto" }
        assertEquals(3, moto.count.all)
        assertEquals(8_221L, moto.avgOfferedMinor) // (10000 + 7417 + 7247) / 3, half up
    }

    @Test
    fun `a day has no expenses or net, and an empty period gives empty totals`() {
        val t = PeriodTotalsBuilder.of(emptyList())
        assertEquals(0, t.count.all)
        assertNull(t.takenPercent)
        assertNull(t.netMinor)
        assertTrue(t.days.isEmpty())
    }

    // ---- files ----

    @Test
    fun `the detail CSV has one line per row and every column`() {
        val rows = sample()
        val csv = OfferReport.csv(rows, zone)
        assertTrue(csv.startsWith(Csv.BOM))
        val lines = csv.removePrefix(Csv.BOM).split("\r\n").filter { it.isNotEmpty() }
        assertEquals(rows.size + 1, lines.size)
        assertEquals(OfferReport.HEADER.joinToString(","), lines.first())
        assertTrue(lines.any { it.contains(",tomado,terminado,Moto,no,Me interesa,") })
        assertTrue(lines.any { it.contains(",tomado,terminó sin confirmar,Moto,si,Aceptar,") })
        assertTrue(lines.any { it.contains(",ofertado,,Sin dato,,,") })
        val json = OfferReport.json(rows, zone, at(16, 9), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), ReportFilters())
        assertTrue(json.contains("\"count\":5"))
        assertTrue(json.contains("\"cardAction\":\"INTEREST\""))
        assertFalse(json.contains("lat"))
    }

    @Test
    fun `the totals file says what each number is and carries the note`() {
        val t = PeriodTotalsBuilder.of(sample())
        val csv = PeriodTotalsText.csv(t, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), ReportFilters(origin = TakenFilter.TAKEN))
        assertTrue(csv.contains("Origen,Tomados,3"))
        assertTrue(csv.contains("Periodo,Filtros,Tomados"))
        assertTrue(csv.contains("No es un documento oficial de Uber"))
        assertTrue(csv.contains("1 de 2 viajes con dinero confirmado"))
        assertTrue(PeriodTotalsText.sections(t).any { it.title == "Modalidad: Flash" })
        val json = PeriodTotalsText.json(t, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), ReportFilters())
        assertTrue(json.trim().startsWith("{") && json.trim().endsWith("}"))
    }

    @Test
    fun `several files go together in one zip, in order`() {
        val zip = ZipPack.of(listOf("a.csv" to "uno".toByteArray(), "b.json" to "{}".toByteArray()))
        val names = ArrayList<String>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                if (e.name == "a.csv") assertEquals("uno", z.readBytes().toString(Charsets.UTF_8))
            }
        }
        assertEquals(listOf("a.csv", "b.json"), names)
    }

    @Test
    fun `the export history keeps the last five, newest first`() {
        var text: String? = null
        (1..7).forEach { i -> text = ExportHistory.add(text, ExportRecord(i.toLong(), "Hoy (14 oct)", "Sin filtros", "f$i.csv")) }
        val list = ExportHistory.decode(text)
        assertEquals(5, list.size)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), list.map { it.atMs })
        assertTrue(ExportHistory.decode(null).isEmpty())
    }

    // ---- periods ----

    @Test
    fun `periods follow the working day and the week start`() {
        val now = at(15, 2) // Thursday 2:00 still belongs to Wednesday's working day
        val today = ReportPeriods.windowOf(ReportPeriod.TODAY, null, null, now, clock, DayOfWeek.MONDAY)!!
        assertEquals(LocalDate.of(2026, 10, 14), today.firstDay)
        assertEquals(at(14, 4), today.startMs)
        val week = ReportPeriods.windowOf(ReportPeriod.THIS_WEEK, null, null, now, clock, DayOfWeek.MONDAY)!!
        assertEquals(LocalDate.of(2026, 10, 12), week.firstDay)
        assertEquals(LocalDate.of(2026, 10, 18), week.lastDay)
        val lastWeek = ReportPeriods.windowOf(ReportPeriod.LAST_WEEK, null, null, now, clock, DayOfWeek.SUNDAY)!!
        assertEquals(LocalDate.of(2026, 10, 4), lastWeek.firstDay)
        val lastMonth = ReportPeriods.windowOf(ReportPeriod.LAST_MONTH, null, null, now, clock, DayOfWeek.MONDAY)!!
        assertEquals(LocalDate.of(2026, 9, 1), lastMonth.firstDay)
        assertEquals(LocalDate.of(2026, 9, 30), lastMonth.lastDay)
        val custom = ReportPeriods.windowOf(ReportPeriod.CUSTOM, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 3), now, clock, DayOfWeek.MONDAY)!!
        assertEquals(LocalDate.of(2026, 10, 3), custom.firstDay)
        assertEquals(at(11, 4), custom.endMs)
        assertNull(ReportPeriods.windowOf(ReportPeriod.CUSTOM, null, null, now, clock, DayOfWeek.MONDAY))
    }

    @Test
    fun `other screens open the export with their period`() {
        val now = at(15, 12)
        val week = ReportPeriods.windowOf(ReportPeriod.LAST_WEEK, null, null, now, clock, DayOfWeek.MONDAY)!!
        assertEquals(ReportPeriod.LAST_WEEK, ReportPeriods.forWindow(week).period)
        val day = com.ubers.observador.domain.earnings.EarningsWindows.of(EarningsRange.DAY, -3, now, clock)
        val preset = ReportPeriods.forWindow(day)
        assertEquals(ReportPeriod.CUSTOM, preset.period)
        assertEquals(day.firstDay, preset.from)
        assertEquals(ReportPeriod.TODAY, ReportPeriods.forStats(StatsPeriod.TODAY, LocalDate.of(2026, 10, 15)).period)
        val seven = ReportPeriods.forStats(StatsPeriod.WEEK, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 9), seven.from)
        assertEquals(LocalDate.of(2026, 10, 15), seven.to)
        assertNull(ReportPeriods.forStats(StatsPeriod.ALL, LocalDate.of(2026, 10, 15)).from)
    }
}
