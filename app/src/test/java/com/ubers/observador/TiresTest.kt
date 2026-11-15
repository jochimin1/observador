package com.ubers.observador

import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseType
import com.ubers.observador.domain.expenses.OdometerPoint
import com.ubers.observador.domain.vehicle.PressureUnit
import com.ubers.observador.domain.vehicle.Tire
import com.ubers.observador.domain.vehicle.TireData
import com.ubers.observador.domain.vehicle.TireEvent
import com.ubers.observador.domain.vehicle.TireEventKind
import com.ubers.observador.domain.vehicle.TirePressure
import com.ubers.observador.domain.vehicle.TireRules
import com.ubers.observador.domain.vehicle.TireTemperature
import com.ubers.observador.domain.vehicle.WheelLevel
import com.ubers.observador.domain.vehicle.WheelPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 1.53.0 «Mi moto y neumáticos». */
class TiresTest {
    private val zone = ZoneId.of("America/Santo_Domingo")
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L
    private val v = "vehicle-initial"

    private fun psi(text: String) = TirePressure.parse(text, PressureUnit.PSI).getOrThrow()
    private fun tire(id: String = "TEST-front", at: Long = t0, km: Long? = 10_000, target: String? = "29", pos: WheelPosition = WheelPosition.FRONT) =
        Tire(id, v, pos, "Pirelli", "Diablo", "90/90-18", target?.let(::psi), at, km, createdAt = at, updatedAt = at)
    private fun check(tireId: String, at: Long, p: String, temp: TireTemperature = TireTemperature.COLD) =
        TireEvent("TEST-c$at", tireId, TireEventKind.CHECK, at, psi(p), temp, createdAt = at, updatedAt = at)

    @Test fun pressureKeepsWhatWasWrittenAndConvertsOnlyForDisplay() {
        val p = psi("32.50")
        assertEquals("32.5", p.entered)
        assertEquals(224_080, p.pascals)
        assertEquals("32.5 psi", p.shown())
        assertEquals("2.24 bar", p.shown(PressureUnit.BAR))
        assertEquals("224 kPa", p.shown(PressureUnit.KPA))
        val bar = TirePressure.parse("2,2", PressureUnit.BAR).getOrThrow()
        assertEquals(220_000, bar.pascals)
        assertEquals("2.2 bar", bar.shown())
        assertEquals("31.9 psi", bar.shown(PressureUnit.PSI))
        assertTrue(TirePressure.parse("", PressureUnit.PSI).isFailure)
        assertTrue(TirePressure.parse("abc", PressureUnit.PSI).isFailure)
        assertTrue(TirePressure.parse("-3", PressureUnit.PSI).isFailure)
        assertTrue(TirePressure.parse("3", PressureUnit.PSI).isFailure) // below 50 kPa
        assertTrue(TirePressure.parse("150", PressureUnit.PSI).isFailure)
        assertEquals(PressureUnit.PSI, PressureUnit.fromStorage(null))
    }

    @Test fun aMissingCheckIsNotTheSameAsAProblem() {
        val data = TireData(listOf(tire()))
        val s = TireRules.status(data, v, WheelPosition.FRONT, emptyList(), t0 + day, zone, PressureUnit.PSI)
        assertEquals(WheelLevel.NEEDS_CHECK, s.level)
        assertFalse(s.level.problem)
        val none = TireRules.status(data, v, WheelPosition.REAR, emptyList(), t0, zone, PressureUnit.PSI)
        assertEquals(WheelLevel.NOT_REGISTERED, none.level)
        assertTrue(TireRules.summary(listOf(s, none)).startsWith("Pendiente"))
    }

    @Test fun checksAreComparedWithTheTargetAndExpire() {
        val t = tire()
        fun level(p: String, at: Long = t0 + day, temp: TireTemperature = TireTemperature.COLD, now: Long = t0 + 2 * day) =
            TireRules.status(TireData(listOf(t), listOf(check(t.id, at, p, temp))), v, WheelPosition.FRONT, emptyList(), now, zone, PressureUnit.PSI)
        assertEquals(WheelLevel.OK, level("29").level)
        assertEquals(WheelLevel.OK, level("31.5").level)
        assertEquals(WheelLevel.LOW, level("25").level)
        assertTrue(level("25").level.problem)
        assertEquals(WheelLevel.HIGH, level("34").level)
        // Hot readings run high: not a problem, a request to check cold
        assertEquals(WheelLevel.NEEDS_CHECK, level("34", temp = TireTemperature.HOT).level)
        assertEquals(WheelLevel.LOW, level("25", temp = TireTemperature.HOT).level)
        // A check never renews itself
        assertEquals(WheelLevel.NEEDS_CHECK, level("29", now = t0 + 20 * day).level)
        assertTrue(level("29", now = t0 + 20 * day).detail.contains("19 días"))
        val noTarget = tire(target = null)
        assertEquals(WheelLevel.NO_TARGET, TireRules.status(TireData(listOf(noTarget), listOf(check(noTarget.id, t0, "29"))), v,
            WheelPosition.FRONT, emptyList(), t0, zone, PressureUnit.PSI).level)
        val problem = level("25")
        assertTrue(TireRules.summary(listOf(problem)).startsWith("Problema detectado"))
    }

    @Test fun changingATyreKeepsTheOldOne() {
        val old = tire()
        val data = TireData(listOf(old))
        val new = tire(id = "TEST-front-2", at = t0 + 100 * day, km = 18_000)
        val written = TireRules.install(data, new)
        assertEquals(2, written.size)
        val closed = written.first { it.id == old.id }
        assertEquals(t0 + 100 * day, closed.removedAt)
        assertEquals(18_000L, closed.removedOdometerKm)
        val after = TireData(written)
        assertEquals("TEST-front-2", TireRules.active(after, v, WheelPosition.FRONT)!!.id)
        val s = TireRules.status(after, v, WheelPosition.FRONT, emptyList(), t0 + 101 * day, zone, PressureUnit.PSI)
        assertEquals(listOf(old.id), s.previous.map { it.id })
        // The old tyre's life comes from its own two odometers
        assertEquals(8_000L, TireRules.lifeKm(closed, emptyList()).first)
        // Installing before the current one is refused
        assertTrue(runCatching { TireRules.install(data, tire(id = "x", at = t0 - day)) }.isFailure)
        // The other wheel is independent
        assertEquals(1, TireRules.install(data, tire(id = "TEST-rear", pos = WheelPosition.REAR)).size)
    }

    @Test fun anOdometerResetNeverGivesANegativeLife() {
        val t = tire(km = 10_000)
        val normal = listOf(OdometerPoint(t0 + day, 12_500, false, 0, "a"))
        assertEquals(2_500L to null, TireRules.lifeKm(t, normal))
        val reset = listOf(OdometerPoint(t0 + day, 50, true, 1, "r"), OdometerPoint(t0 + 2 * day, 800, false, 1, "b"))
        val (km, why) = TireRules.lifeKm(t, reset)
        assertNull(km)
        assertNotNull(why)
        assertNull(TireRules.lifeKm(t, listOf(OdometerPoint(t0 + day, 9_000, false, 0, "c"))).first)
        assertNull(TireRules.lifeKm(tire(km = null), normal).first)
        assertNull(TireRules.lifeKm(t, emptyList()).first)
    }

    @Test fun moneyIsLinkedOnceAndOnlyToTheRightKind() {
        fun exp(id: String, type: ExpenseType, at: Long) = ExpenseEntry(id, at, type, 250_000, odometerKm = null, createdAt = at, updatedAt = at, vehicleId = v)
        val expenses = listOf(exp("e1", ExpenseType.TIRES, t0), exp("e2", ExpenseType.TIRES, t0 + 2 * day),
            exp("e3", ExpenseType.MAINTENANCE_OTHER, t0), exp("e4", ExpenseType.TIRES, t0 + 30 * day))
        val linked = TireData(listOf(tire()), listOf(TireEvent("ev1", "TEST-front", TireEventKind.INSTALL, t0, expenseId = "e1", createdAt = t0, updatedAt = t0)))
        assertEquals(listOf("e2"), TireRules.linkable(expenses, linked, v, TireEventKind.INSTALL, t0).map { it.id })
        // Editing the event that holds it: still offered
        assertEquals(listOf("e1", "e2"), TireRules.linkable(expenses, linked, v, TireEventKind.INSTALL, t0, editingEventId = "ev1").map { it.id })
        assertEquals(listOf("e3"), TireRules.linkable(expenses, linked, v, TireEventKind.REPAIR, t0).map { it.id })
        assertTrue(TireRules.linkable(expenses, linked, v, TireEventKind.CHECK, t0).isEmpty())
        assertTrue(TireRules.alreadyLinked(linked, "e1", "other"))
        assertFalse(TireRules.alreadyLinked(linked, "e1", "ev1"))
        // A repair does not reset the tyre change reminder
        assertEquals(ExpenseType.MAINTENANCE_OTHER, TireEventKind.REPAIR.expenseType)
        assertNull(TireEventKind.CHECK.expenseType)
    }

    @Test fun eventsMustFitTheTyreLife() {
        val t = tire().copy(removedAt = t0 + 10 * day)
        fun ev(kind: TireEventKind, at: Long, p: String? = "29") = TireEvent("e", t.id, kind, at, p?.let(::psi), createdAt = at, updatedAt = at)
        assertNull(TireRules.eventProblem(t, ev(TireEventKind.CHECK, t0 + day), t0 + 20 * day))
        assertNotNull(TireRules.eventProblem(t, ev(TireEventKind.CHECK, t0 - day), t0 + 20 * day))
        assertNotNull(TireRules.eventProblem(t, ev(TireEventKind.CHECK, t0 + 11 * day), t0 + 20 * day))
        assertNotNull(TireRules.eventProblem(t, ev(TireEventKind.CHECK, t0 + day, p = null), t0 + 20 * day))
        assertNull(TireRules.eventProblem(t, ev(TireEventKind.REPAIR, t0 + day, p = null), t0 + 20 * day))
        assertNotNull(TireRules.eventProblem(t, ev(TireEventKind.REPAIR, t0 + 30 * day, p = null), t0 + 20 * day))
    }

    @Test fun textsAreCleaned() {
        assertEquals("Pirelli Diablo", TireRules.cleanText("  Pirelli   Diablo "))
        assertNull(TireRules.cleanText("   "))
        assertTrue(runCatching { TireRules.cleanText("x".repeat(41)) }.isFailure)
        assertTrue(runCatching { TireRules.cleanText("a\u0000b") }.isFailure)
    }
}
