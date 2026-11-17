package com.ubers.observador

import com.ubers.observador.domain.health.ReadingEvent
import com.ubers.observador.domain.health.ReadingMark
import com.ubers.observador.domain.health.UberWatch
import com.ubers.observador.domain.health.VersionHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UberWatchTest {
    private fun m(at: Long, v: String, e: ReadingEvent = ReadingEvent.OFFER_OK) = ReadingMark(at, v, e)

    @Test fun eachVersionIsCountedFromItsFirstMark() {
        val marks = listOf(m(1, "4.500"), m(2, "4.500", ReadingEvent.MONEY_TOTAL), m(3, "4.500", ReadingEvent.OFFER_UNREADABLE),
            m(10, "4.510"), m(11, "4.510"))
        assertEquals(listOf(
            VersionHealth("4.510", 10, null, 2, 0, 0),
            VersionHealth("4.500", 1, 10, 1, 1, 1)
        ), UberWatch.health(marks))
        assertEquals(10L, UberWatch.updatedAt(marks, "4.510"))
        assertNull(UberWatch.updatedAt(marks.take(3), "4.500"))
        assertTrue(UberWatch.health(marks).first().watching)
        assertEquals("Uber 4.500: 1 ofertas leídas, 1 sin leer (50 %), 1 totales de dinero leídos. X: aprendida.",
            UberWatch.describe(UberWatch.health(marks)[1], "aprendida"))
    }

    @Test fun readingGotWorseOnlyWithEnoughUnreadableRecentOffers() {
        val good = (1..17).map { m(it.toLong(), "4.6") }
        assertFalse(UberWatch.degraded(good + listOf(m(20, "4.6", ReadingEvent.OFFER_UNREADABLE), m(21, "4.6", ReadingEvent.OFFER_UNREADABLE)), "4.6"))
        val bad = good.takeLast(5) + (30..33).map { m(it.toLong(), "4.6", ReadingEvent.OFFER_UNREADABLE) }
        assertTrue(UberWatch.degraded(bad, "4.6"))
        // Money marks do not dilute the offers
        assertTrue(UberWatch.degraded(bad + (40..80).map { m(it.toLong(), "4.6", ReadingEvent.MONEY_TOTAL) }, "4.6"))
        assertFalse(UberWatch.degraded(bad, "4.7"))
    }

    @Test fun linesRoundTrip() {
        val x = m(1_763_000_000_000, "4.5;10%", ReadingEvent.MONEY_TOTAL)
        assertEquals(x, UberWatch.decode(UberWatch.encode(x)))
        assertEquals(1, UberWatch.parse(UberWatch.encode(x) + "\nr1;roto\n").size)
    }
}

class TireWarmthTest {
    @Test fun aWarmTyreIsComparedAsIfItWereCold() {
        val p = com.ubers.observador.domain.vehicle.TirePressure.parse("33", com.ubers.observador.domain.vehicle.PressureUnit.PSI).getOrThrow().pascals
        val cold = com.ubers.observador.domain.vehicle.TireWarmth.coldGauge(p, 4_500)
        assertTrue("$cold", kotlin.math.abs(cold - 206_854) < 100) // ≈ 30.0 psi
        assertEquals(p, com.ubers.observador.domain.vehicle.TireWarmth.coldGauge(p, 2_900))
        assertEquals(p, com.ubers.observador.domain.vehicle.TireWarmth.coldGauge(p, null))
        assertFalse(com.ubers.observador.domain.vehicle.TireWarmth.isWarm(2_999))
        assertTrue(com.ubers.observador.domain.vehicle.TireWarmth.isWarm(3_000))
    }
}
