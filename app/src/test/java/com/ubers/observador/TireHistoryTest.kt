package com.ubers.observador

import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.OdometerPoint
import com.ubers.observador.domain.vehicle.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 1.55.0: history with gaps, cost per km, full export and the driver's own limits. */
class TireHistoryTest {
    private val zone = ZoneId.of("America/Santo_Domingo")
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L
    private val v = "vehicle-initial"
    private fun psi(t: String) = TirePressure.parse(t, PressureUnit.PSI).getOrThrow()
    private val front = Tire("TEST-f", v, WheelPosition.FRONT, "Pirelli", null, "90/90-18", psi("29"), t0, 10_000, createdAt = t0, updatedAt = t0)
    private fun ev(id: String, kind: TireEventKind, at: Long, expense: String? = null) =
        TireEvent(id, front.id, kind, at, if (kind == TireEventKind.CHECK) psi("28") else null, expenseId = expense, createdAt = at, updatedAt = at)
    private fun exp(id: String, minor: Long, type: ExpenseType = ExpenseType.TIRES) = ExpenseEntry(id, t0, type, minor, createdAt = t0, updatedAt = t0, vehicleId = v)

    @Test fun historyShowsEverythingAndLongStretchesWithoutChecksAsGaps() {
        val data = TireData(listOf(front), listOf(ev("i", TireEventKind.INSTALL, t0), ev("c1", TireEventKind.CHECK, t0 + 3 * day), ev("c2", TireEventKind.CHECK, t0 + 40 * day)))
        val b = SensorBinding("b1", v, WheelPosition.FRONT, "13ae32", boundAt = t0 + day, createdAt = t0, updatedAt = t0)
        val days = listOf(SensorDay("13ae32", t0 + 5 * day, 12, t0 + 5 * day, t0 + 5 * day + 3_600_000, 299_000, 301_000, 3_000, 3_300, 100))
        val h = TireHistory.build(data, listOf(b), days, v, null, Long.MIN_VALUE, Long.MAX_VALUE, TireSettings(), now = t0 + 50 * day)
        val gaps = h.filterIsInstance<TireHistoryItem.Gap>()
        assertEquals(listOf(37L), gaps.map { it.days }) // 3 → 40 days; 40 → 50 is within 14
        assertEquals(1, h.filterIsInstance<TireHistoryItem.Sensor>().size)
        assertEquals(1, h.filterIsInstance<TireHistoryItem.SensorOn>().size)
        assertEquals(3, h.filterIsInstance<TireHistoryItem.Event>().size)
        assertTrue(h.zipWithNext().all { (a, b) -> a.atMs >= b.atMs })
        // A shorter check period finds more gaps; the rear has nothing
        assertEquals(2, TireHistory.build(data, emptyList(), emptyList(), v, WheelPosition.FRONT, Long.MIN_VALUE, Long.MAX_VALUE, TireSettings(checkDays = 7), t0 + 50 * day)
            .filterIsInstance<TireHistoryItem.Gap>().size)
        assertTrue(TireHistory.build(data, listOf(b), days, v, WheelPosition.REAR, Long.MIN_VALUE, Long.MAX_VALUE, TireSettings(), t0 + 50 * day).isEmpty())
        // The window filters
        assertEquals(2, TireHistory.build(data, emptyList(), emptyList(), v, null, t0 + 39 * day, t0 + 41 * day, TireSettings(), t0 + 50 * day).size) // the check and the gap it closes
    }

    @Test fun costPerKmNeedsRealMoneyAndEnoughKm() {
        val data = TireData(listOf(front), listOf(ev("i", TireEventKind.INSTALL, t0, "e1"), ev("r", TireEventKind.REPAIR, t0 + day, "e2"), ev("x", TireEventKind.REPAIR, t0 + 2 * day, "gone")))
        val money = listOf(exp("e1", 250_000), exp("e2", 30_000, ExpenseType.MAINTENANCE_OTHER))
        val odo = listOf(OdometerPoint(t0 + 30 * day, 14_000, false, 0, "o"))
        val c = TireCosts.of(front, data, money, odo)
        assertEquals(280_000L, c.totalMinor)
        assertEquals(2, c.expenses) // the deleted expense is not counted
        assertEquals(4_000L, c.km)
        assertEquals(70L, c.perKmMinor) // RD$0.70/km
        assertTrue(TireCosts.line(c).startsWith("Pirelli: RD$0.70/km · RD$2800.00 en 2 gastos · 4000 km"))
        assertNull(TireCosts.of(front, data, money, listOf(OdometerPoint(t0 + day, 10_300, false, 0, "o"))).perKmMinor)
        assertNull(TireCosts.of(front, TireData(listOf(front)), money, odo).perKmMinor)
    }

    @Test fun exportCarriesEveryColumnAndBothUnits() {
        val data = TireData(listOf(front), listOf(ev("c1", TireEventKind.CHECK, t0, null).copy(note = "Antes de salir, \"en frío\"")))
        val ev = TireExport.events(data, emptyList(), zone)
        assertTrue(ev.startsWith("\uFEFFid,tire_id,position,kind,source"))
        val line = ev.lines()[1]
        assertTrue(line, line.contains(",FRONT,CHECK,MANUAL,") && line.contains(",28,PSI,193053,28,193.053,") && line.contains("\"Antes de salir, \"\"en frío\"\"\""))
        assertTrue(TireExport.tires(data, zone).contains("Pirelli"))
        val s = TireExport.sensors(listOf(SensorBinding("b", v, WheelPosition.REAR, "23a434", boundAt = t0, createdAt = t0, updatedAt = t0)), zone)
        assertTrue(s.contains(",REAR,23A434,LEEPEE_MOTORCARE,"))
        assertTrue(TireExport.readme(zone, t0, 1, 1, 1, 3).contains("sin recortar"))
    }

    @Test fun limitsAndTemperatureUnitAreTheDrivers() {
        assertEquals("33.4 °C", TemperatureUnit.C.format(3_343))
        assertEquals("92.2 °F", TemperatureUnit.F.format(3_343))
        assertEquals("-5.5 °C", TemperatureUnit.C.format(-550))
        assertTrue(TireSettings().valid())
        assertTrue(!TireSettings(checkDays = 0).valid() && !TireSettings(tolerancePct = 50).valid())
        // A wider tolerance accepts what the default calls low
        val t = front
        val data = TireData(listOf(t), listOf(TireEvent("c", t.id, TireEventKind.CHECK, t0, psi("25.5"), TireTemperature.COLD, createdAt = t0, updatedAt = t0)))
        assertEquals(WheelLevel.LOW, TireRules.status(data, v, WheelPosition.FRONT, emptyList(), t0, zone, PressureUnit.PSI).level)
        assertEquals(WheelLevel.OK, TireRules.status(data, v, WheelPosition.FRONT, emptyList(), t0, zone, PressureUnit.PSI, tolerancePct = 15).level)
        assertEquals(WheelLevel.NEEDS_CHECK, TireRules.status(data, v, WheelPosition.FRONT, emptyList(), t0 + 8 * day, zone, PressureUnit.PSI, checkDays = 7, tolerancePct = 15).level)
    }
}
