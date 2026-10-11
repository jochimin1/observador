package com.ubers.observador

import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.models.UberOffer
import org.junit.Assert.*
import org.junit.Test

class ProfitEngineTest {

    private val engine = ProfitEngine()
    private val settings = EngineSettings(
        costPerKm = 1.50,
        minRdpPerKm = 6.0,
        minRdpPerHour = 300.0,
        maxPickupKm = 6.0,
        maxPickupMin = 12
    )

    @Test
    fun `offer RD83 76 calculates correct metrics`() {
        val offer = UberOffer(price = 83.76, pickupKm = 2.1, pickupMin = 4, tripKm = 4.0, tripMin = 9)
        val analysis = engine.analyze(offer, settings)

        assertEquals(6.1, analysis.totalKm, 0.01)
        assertEquals(13, analysis.totalMin)
        assertEquals(13.73, analysis.rdpPerKm, 0.01)
        assertEquals(386.6, analysis.rdpPerHour, 1.0)
        assertEquals(9.15, analysis.fuelCost, 0.01)
        assertTrue("Score should be >= 60", analysis.score >= 60)
    }

    @Test
    fun `offer RD136 06 calculates correct metrics`() {
        val offer = UberOffer(price = 136.06, pickupKm = 4.1, pickupMin = 7, tripKm = 7.2, tripMin = 11)
        val analysis = engine.analyze(offer, settings)

        assertEquals(11.3, analysis.totalKm, 0.01)
        assertEquals(18, analysis.totalMin)
        assertEquals(12.04, analysis.rdpPerKm, 0.01)
        assertEquals(453.5, analysis.rdpPerHour, 1.0)
        assertEquals(16.95, analysis.fuelCost, 0.01)
        assertTrue("Score should be >= 60", analysis.score >= 60)
    }

    @Test
    fun `score is capped between 0 and 100`() {
        val great = UberOffer(price = 500.0, pickupKm = 0.1, pickupMin = 1, tripKm = 20.0, tripMin = 30)
        val terrible = UberOffer(price = 10.0, pickupKm = 10.0, pickupMin = 30, tripKm = 1.0, tripMin = 5)
        // Pickup parts are never exactly 100/0 for a real offer, so the extremes land near the ends.
        assertTrue("great offer should be near 100", engine.analyze(great, settings).score in 95..100)
        assertTrue("terrible offer should be near 0", engine.analyze(terrible, settings).score in 0..10)
    }

    @Test
    fun `first offer scores higher per km than second`() {
        val o1 = UberOffer(price = 83.76, pickupKm = 2.1, pickupMin = 4, tripKm = 4.0, tripMin = 9)
        val o2 = UberOffer(price = 136.06, pickupKm = 4.1, pickupMin = 7, tripKm = 7.2, tripMin = 11)
        val a1 = engine.analyze(o1, settings)
        val a2 = engine.analyze(o2, settings)
        assertTrue("RD83 should have higher RD$/km", a1.rdpPerKm > a2.rdpPerKm)
        assertTrue("RD136 should have higher RD$/hour", a2.rdpPerHour > a1.rdpPerHour)
    }
}
