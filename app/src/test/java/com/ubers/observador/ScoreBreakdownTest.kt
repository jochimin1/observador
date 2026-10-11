package com.ubers.observador

import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.ScoreBreakdown
import com.ubers.observador.domain.models.UberOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreBreakdownTest {

    private val engine = ProfitEngine()
    private val defaults = EngineSettings()
    private val example = UberOffer(price = 75.50, pickupKm = 1.8, pickupMin = 4, tripKm = 3.5, tripMin = 9)

    @Test
    fun `example offer scores 76 with default thresholds`() {
        val analysis = engine.analyze(example, defaults)
        val parts = engine.breakdown(analysis, defaults)
        assertEquals(100.0, parts.km, 0.001)
        assertEquals(58.0, parts.hour, 0.5)
        assertEquals(70.0, parts.pickupKm, 0.001)
        assertEquals(66.7, parts.pickupMin, 0.1)
        assertEquals(76, analysis.score)
        assertEquals(analysis.score, parts.total)
    }

    @Test
    fun `stricter thresholds lower the same offer to 36`() {
        val strict = EngineSettings(minRdpPerKm = 12.0, minRdpPerHour = 400.0, maxPickupKm = 2.0, maxPickupMin = 4)
        assertEquals(36, engine.analyze(example, strict).score)
    }

    @Test
    fun `weights add up to 100`() {
        val sum = ScoreBreakdown.WEIGHT_KM_PCT + ScoreBreakdown.WEIGHT_HOUR_PCT +
            ScoreBreakdown.WEIGHT_PICKUP_KM_PCT + ScoreBreakdown.WEIGHT_PICKUP_MIN_PCT
        assertEquals(100, sum)
    }

    @Test
    fun `a threshold saved as zero by an older version does not break the score`() {
        val broken = EngineSettings(minRdpPerKm = 0.0, minRdpPerHour = 0.0, maxPickupKm = 0.0, maxPickupMin = 0)
        val score = engine.analyze(example, broken).score
        assertTrue("score out of range: $score", score in 0..100)
    }
}
