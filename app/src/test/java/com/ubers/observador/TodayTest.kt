package com.ubers.observador

import com.ubers.observador.domain.earnings.Today
import com.ubers.observador.domain.earnings.TodayFigures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.57.0: the day at a glance (widget, tile, notification). */
class TodayTest {
    private val h = 3_600_000L
    private val day = TodayFigures(incomeMinor = 250_000, currency = "DOP", confirmedTrips = 5, completedTrips = 7, pending = 1,
        onlineMs = 3 * h + 20 * 60_000, profitMinor = 180_000, goalMinor = 300_000, goalMinutes = 8 * 60)

    @Test fun netWhenTheCostModelCanAndIncomeOtherwise() {
        assertEquals("Neto RD$1,800", Today.headline(day))
        assertEquals("Ingreso RD$2,500", Today.headline(day.copy(profitMinor = null)))
        assertEquals("Hoy: RD$1,800 neto · 7 viajes · 3 h 20 min", Today.notificationLine(day))
        assertEquals("RD$2,500 hoy", Today.tileLine(day.copy(profitMinor = null)))
        assertEquals("-RD$300", Today.money(-30_000))
    }

    @Test fun detailsNameWhatIsMissing() {
        assertEquals("3 h 20 min en línea · RD$540/h · 7 viajes (2 sin dinero confirmado) · 1 por revisar", Today.details(day))
        // Under 30 min online there is no rate
        assertNull(day.copy(onlineMs = 20 * 60_000).perHourMinor)
        assertEquals("20 min en línea · 1 viaje", Today.details(day.copy(onlineMs = 20 * 60_000, completedTrips = 1, confirmedTrips = 1, pending = 0)))
    }

    @Test fun goalsInMoneyAndHours() {
        assertEquals("Meta 83 % de RD$3,000 · Horas 3 h 20 min de 8 h", Today.goals(day))
        assertNull(Today.goals(day.copy(goalMinor = 0, goalMinutes = 0)))
        assertFalse(day.hoursReached)
        assertTrue(day.copy(onlineMs = 8 * h).hoursReached)
        assertEquals(1000, day.copy(incomeMinor = 999_999).moneyGoalPermille)
    }
}
