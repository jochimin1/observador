package com.ubers.observador

import com.ubers.observador.domain.reports.WeekReview
import com.ubers.observador.domain.reports.WeekReviewFigures
import com.ubers.observador.domain.sectors.CloseSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class WeekReviewTest {
    private val zone = ZoneId.of("America/Santo_Domingo")

    @Test fun dueOnceOnTheChosenDayFromSevenPm() {
        val sunday = ZonedDateTime.of(2026, 11, 15, 19, 5, 0, 0, zone)
        assertTrue(WeekReview.due(sunday, DayOfWeek.SUNDAY, null, "2026-11-09"))
        assertFalse(WeekReview.due(sunday, DayOfWeek.SUNDAY, "2026-11-09", "2026-11-09"))
        assertFalse(WeekReview.due(sunday.withHour(18), DayOfWeek.SUNDAY, null, "2026-11-09"))
        assertFalse(WeekReview.due(sunday, DayOfWeek.MONDAY, null, "2026-11-09"))
        assertEquals(-1, WeekReview.offset(0, 3_600_000))
        assertEquals(0, WeekReview.offset(0, 2 * 86_400_000L))
    }

    @Test fun theSummarySaysTheMoneyAndWhatIsLeft() {
        val f = WeekReviewFigures(1_250_000, "DOP", 980_000, 1_850, 25 * 3_600_000L, 40, 37, 2,
            CloseSummary(closed = 5, stoppedByDriver = 1), listOf("Delantera: falta revisar"), listOf("13AE32"), true)
        assertEquals("Tu semana: neto RD$9,800", WeekReview.title(f))
        assertEquals(listOf(
            "Ingreso RD$12,500 · neto RD$9,800 · neto RD$19 por km",
            "25 h en línea · 40 viajes",
            "Sectores «Cerrar»: 5 cerradas, 1 impedidas por ti, 0 sin cerrar.",
            "Gomas: Delantera: falta revisar",
            "Por revisar: 3 viajes sin dinero confirmado, 2 importes por revisar, confirmar con Uber 3 viajes en Ganancias, sensor 13AE32 sin señal."
        ), WeekReview.lines(f))
        val clean = f.copy(confirmedTrips = 40, pendingReview = 0, moneyToConfirm = false, silentSensors = emptyList(), tires = emptyList(), closes = CloseSummary())
        assertEquals("Nada pendiente por revisar.", WeekReview.lines(clean).last())
    }
}
