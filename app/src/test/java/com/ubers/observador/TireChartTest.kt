package com.ubers.observador

import com.ubers.observador.domain.vehicle.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.55.0: chart series keep manual and sensor apart, break at gaps and describe themselves. */
class TireChartTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private fun psi(t: String) = TirePressure.parse(t, PressureUnit.PSI).getOrThrow()

    @Test fun manualAndSensorStayApartAndGapsBreakTheLine() {
        val checks = listOf(TireEvent("c", "t", TireEventKind.CHECK, t0, psi("29"), createdAt = t0, updatedAt = t0),
            TireEvent("r", "t", TireEventKind.REPAIR, t0, createdAt = t0, updatedAt = t0))
        val sensor = listOf(t0 + min to 301_793, t0 + 3 * min to 300_626, t0 + 120 * min to 299_570)
        val s = TireChart.pressure(checks, sensor, psi("29"), 10, PressureUnit.PSI, t0, t0 + 200 * min)
        assertEquals(1, s.manual.size)
        assertEquals(29.0, s.manual.single().value, 0.01)
        assertEquals(listOf(2, 1), s.sensorRuns.map { it.size })
        assertEquals(43.77, s.sensorRuns[0][0].value, 0.01) // as sent, still unconfirmed
        assertTrue(s.sensorProvisional)
        assertEquals(26.1, s.low!!, 0.01)
        assertTrue(s.minY < 26.1 && s.maxY > 43.77)
        val d = TireChart.description(s, "Presión trasera")
        assertTrue(d, d.contains("1 revisiones manuales") && d.contains("3 lecturas del sensor por confirmar") && d.contains("1 hueco") && d.contains("objetivo 29 psi"))
        assertEquals(0f, TireChart.x(s, t0)); assertEquals(1f, TireChart.x(s, t0 + 999 * min))
    }

    @Test fun temperatureIsInTheDriversUnitAndEmptyChartsSaySo() {
        val s = TireChart.temperature(listOf(t0 to 3_343), TemperatureUnit.F, t0, t0 + min)
        assertEquals(92.17, s.sensorRuns.single().single().value, 0.01)
        assertEquals("Temperatura: sin datos en este periodo.", TireChart.description(TireChart.temperature(emptyList(), TemperatureUnit.C, t0, t0 + min), "Temperatura"))
        // Many points are thinned to keep drawing light
        val many = (0 until 5_000).map { t0 + it * 1_000L to 3_000 }
        assertTrue(TireChart.temperature(many, TemperatureUnit.C, t0, t0 + 10_000_000).sensorRuns.flatten().size <= TireChart.MAX_SENSOR_POINTS)
    }
}
