package com.ubers.observador

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.OfferDetails
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.QualityThresholds
import org.junit.Assert.assertEquals
import org.junit.Test

class OfferDetailsTest {

    private val cuts = QualityThresholds(goodFrom = 60, excellentFrom = 80)

    // The example offer from the plan, stored with stale derived values on purpose
    private val stored = OfferEntity(
        offerHash = "x", detectedAt = 1_800_000_000_000L, price = 75.50,
        pickupKm = 1.8, pickupMin = 4, tripKm = 3.5, tripMin = 9,
        totalKm = 0.0, totalMin = 0, rdpPerKm = 0.0, rdpPerHour = 0.0,
        fuelCost = 0.0, netProfit = 0.0, score = 0
    )

    @Test
    fun `example offer matches the expected values of the plan`() {
        val d = OfferDetails.of(stored, EngineSettings(), cuts)
        assertEquals(5.3, d.offer.totalKm, 1e-9)
        assertEquals(13, d.offer.totalMin)
        assertEquals("14.25", "%.2f".format(java.util.Locale.US, d.offer.rdpPerKm))
        assertEquals("348", "%.0f".format(java.util.Locale.US, d.offer.rdpPerHour))
        assertEquals("7.95", "%.2f".format(java.util.Locale.US, d.offer.fuelCost))
        assertEquals("67.55", "%.2f".format(java.util.Locale.US, d.offer.netProfit))
        assertEquals(76, d.offer.score)
        assertEquals(OfferQuality.GOOD, d.quality)
    }

    @Test
    fun `sub-scores are 100, 58, 70 and 67`() {
        val b = OfferDetails.of(stored, EngineSettings(), cuts).breakdown
        assertEquals(100, Math.round(b.km).toInt())
        assertEquals(58, Math.round(b.hour).toInt())
        assertEquals(70, Math.round(b.pickupKm).toInt())
        assertEquals(67, Math.round(b.pickupMin).toInt())
        assertEquals(76, b.total)
    }

    @Test
    fun `stricter settings change the panel the same way they change the list`() {
        val strict = EngineSettings(minRdpPerKm = 12.0, minRdpPerHour = 400.0, maxPickupKm = 2.0, maxPickupMin = 4)
        val d = OfferDetails.of(stored, strict, cuts)
        assertEquals(36, d.offer.score)
        assertEquals(OfferQuality.BAD, d.quality)
    }

    @Test
    fun `identity fields are kept`() {
        val d = OfferDetails.of(stored.copy(id = 42), EngineSettings(), cuts)
        assertEquals(42L, d.offer.id)
        assertEquals(stored.detectedAt, d.offer.detectedAt)
    }
}
