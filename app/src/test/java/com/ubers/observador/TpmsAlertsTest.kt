package com.ubers.observador

import com.ubers.observador.domain.vehicle.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.54.0: alert rules (fed only once the sensor format is validated). */
class TpmsAlertsTest {
    private val target = TirePressure.parse("29", PressureUnit.PSI).getOrThrow() // 199,948 Pa
    private val F = WheelPosition.FRONT
    private fun s(t: Long, psi: Double? = 29.0, c: Int? = 3_000, bat: Int? = 90, leak: Boolean? = false) =
        TpmsSample(F, t, psi?.let { (it * 6894.757).toInt() }, c, bat, leak)

    @Test fun oneLowReadingIsNoiseTwoAreAnAlertAndItEndsPastTheMargin() {
        val a = TpmsAlertTracker()
        assertTrue(a.onSample(s(1, 25.0), target).isEmpty())
        val start = a.onSample(s(2, 25.0), target)
        assertEquals(TpmsAlertKind.LOW_PRESSURE, start.single().kind)
        // Back just above the limit: still inside the margin, stays on
        assertTrue(a.onSample(s(3, 26.2), target).isEmpty())
        val end = a.onSample(s(4, 27.5), target).single()
        assertEquals(4L, end.endedAt)
        assertTrue(a.active.isEmpty())
    }

    @Test fun alternatingAtTheLimitDoesNotFlicker() {
        val a = TpmsAlertTracker()
        val events = (1..10L).flatMap { a.onSample(s(it, if (it % 2 == 0L) 25.9 else 26.3), target) }
        assertTrue(events.isEmpty())
    }

    @Test fun highTemperatureBatteryAndReportedLeak() {
        val a = TpmsAlertTracker()
        a.onSample(s(1, c = 8_500, bat = 15), target)
        val e = a.onSample(s(2, c = 8_500, bat = 15, leak = true), target)
        assertEquals(setOf(TpmsAlertKind.HIGH_TEMPERATURE, TpmsAlertKind.LOW_BATTERY, TpmsAlertKind.LEAK_REPORTED), e.map { it.kind }.toSet())
        // Leak is the sensor's own flag: one reading is enough
        val b = TpmsAlertTracker()
        assertEquals(TpmsAlertKind.LEAK_REPORTED, b.onSample(s(1, leak = true), target).single().kind)
        // Missing fields are not alarms
        assertTrue(TpmsAlertTracker().onSample(s(1, psi = null, c = null, bat = null, leak = null), target).isEmpty())
        // Without a target there is no pressure alert
        val c = TpmsAlertTracker(); c.onSample(s(1, 20.0), null)
        assertTrue(c.onSample(s(2, 20.0), null).isEmpty())
    }

    @Test fun signalLossStartsWhileListeningAndEndsWithTheNextFrame() {
        val a = TpmsAlertTracker()
        a.onSample(s(0), target)
        assertTrue(a.onTick(5 * 60_000L, true, listOf(F)).isEmpty())
        assertTrue(a.onTick(11 * 60_000L, false, listOf(F)).isEmpty())
        val lost = a.onTick(11 * 60_000L, true, listOf(F)).single()
        assertEquals(TpmsAlertKind.SIGNAL_LOST, lost.kind)
        assertTrue(a.onTick(12 * 60_000L, true, listOf(F)).isEmpty())
        assertEquals(TpmsAlertKind.SIGNAL_LOST, a.onSample(s(12 * 60_000L + 1), target).single { it.endedAt != null }.kind)
    }

    @Test fun silenceKeepsQuietButStillRecords() {
        val e = TpmsAlertEvent(F, TpmsAlertKind.LOW_PRESSURE, 100, value = 170_000, limit = 179_953)
        assertTrue(TpmsAlertNotice.shouldNotify(e, null))
        assertFalse(TpmsAlertNotice.shouldNotify(e, 200))
        assertTrue(TpmsAlertNotice.shouldNotify(e, 50))
        assertTrue(TpmsAlertNotice.text(e, PressureUnit.PSI).startsWith("Presión baja · rueda delantera: 24.7 psi"))
        assertFalse(TpmsParser.VALIDATED)
    }
}
