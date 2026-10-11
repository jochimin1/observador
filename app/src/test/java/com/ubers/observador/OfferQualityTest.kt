package com.ubers.observador

import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.QualityThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferQualityTest {

    private val t = QualityThresholds(goodFrom = 60, excellentFrom = 80)

    @Test
    fun `scores fall into the three categories at the boundaries`() {
        assertEquals(OfferQuality.BAD, t.classify(0))
        assertEquals(OfferQuality.BAD, t.classify(59))
        assertEquals(OfferQuality.GOOD, t.classify(60))
        assertEquals(OfferQuality.GOOD, t.classify(79))
        assertEquals(OfferQuality.EXCELLENT, t.classify(80))
        assertEquals(OfferQuality.EXCELLENT, t.classify(100))
    }

    @Test
    fun `crossed thresholds are pushed apart so EXCELLENT stays above GOOD`() {
        val crossed = QualityThresholds.of(goodFrom = 90, excellentFrom = 80)
        assertEquals(90, crossed.goodFrom)
        assertEquals(91, crossed.excellentFrom)
        assertEquals(OfferQuality.GOOD, crossed.classify(90))
        assertEquals(OfferQuality.EXCELLENT, crossed.classify(91))
    }

    @Test
    fun `each category has its own label and background`() {
        val all = OfferQuality.entries
        assertEquals(3, all.map { it.label }.toSet().size)
        assertEquals(3, all.map { it.backgroundArgb }.toSet().size)
        assertEquals("MALO", OfferQuality.BAD.label)
        assertEquals("BUENO", OfferQuality.GOOD.label)
        assertEquals("EXCELENTE", OfferQuality.EXCELLENT.label)
    }

    @Test
    fun `the real offers from the screenshots land where expected with default thresholds`() {
        // Scores the engine gives with default settings: RD65.41 -> 84, RD83.76 -> 77, RD136.06 -> 70
        assertEquals(OfferQuality.EXCELLENT, t.classify(84))
        assertEquals(OfferQuality.GOOD, t.classify(77))
        assertEquals(OfferQuality.GOOD, t.classify(70))
        assertTrue(t.classify(45) == OfferQuality.BAD)
    }
}
