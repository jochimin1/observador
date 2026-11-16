package com.ubers.observador

import com.ubers.observador.domain.vehicle.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsCalibrationTest {
    private val v = "TEST-moto"
    private val t0 = 1_763_000_000_000L
    private val min = 60_000L
    private fun psi(t: String) = TirePressure.parse(t, PressureUnit.PSI).getOrThrow()
    private val rear = Tire("TEST-r", v, WheelPosition.REAR, "Pirelli", null, null, psi("33"), t0, createdAt = t0, updatedAt = t0)
    private val front = Tire("TEST-f", v, WheelPosition.FRONT, "Pirelli", null, null, psi("29"), t0, createdAt = t0, updatedAt = t0)
    private val bindings = listOf(
        SensorBinding("b1", v, WheelPosition.REAR, "23a434", boundAt = t0, createdAt = t0, updatedAt = t0),
        SensorBinding("b2", v, WheelPosition.FRONT, "13ae32", boundAt = t0, createdAt = t0, updatedAt = t0)
    )
    private fun check(id: String, tire: Tire, at: Long, gauge: String) =
        TireEvent(id, tire.id, TireEventKind.CHECK, at, psi(gauge), createdAt = at, updatedAt = at)

    @Test fun theRealRearReadingIsAbsoluteWhenTheGaugeSaysAbout29Psi() {
        // 301,793 Pa received on 2026-11-15; 29 psi gauge = 199,948 Pa: the difference is one atmosphere
        assertEquals(PressureReference.ABSOLUTE, TpmsCalibrations.reference(301_793, psi("29").pascals))
        assertEquals(PressureReference.GAUGE, TpmsCalibrations.reference(301_793, psi("43.8").pascals))
        assertNull(TpmsCalibrations.reference(301_793, psi("36").pascals))
        assertNull(TpmsCalibrations.reference(301_793, 0))
    }

    @Test fun twoAgreeingChecksConfirmAndTheOppositeOneBlocks() {
        val samples = listOf(
            SensorPressure("23a434", t0 + 60 * min, 301_793, 3343),
            SensorPressure("13ae32", t0 + 200 * min, 296_000, 3000),
            SensorPressure("23a434", t0 + 400 * min, 300_626, 3324)
        )
        val data = TireData(listOf(rear, front), listOf(
            check("e1", rear, t0 + 63 * min, "29"),
            check("e2", front, t0 + 195 * min, "28.3"),
            // Too far from any frame of its sensor: not a calibration
            check("e3", rear, t0 + 300 * min, "29")
        ))
        val one = TpmsCalibrations.pair(data.copy(events = data.events.take(1)), bindings, samples)
        assertEquals(1, one.size)
        assertEquals(CalibrationState.Pending(1, PressureReference.ABSOLUTE, 0), TpmsCalibrations.state(one))
        val list = TpmsCalibrations.pair(data, bindings, samples)
        assertEquals(listOf("e1", "e2"), list.map { it.eventId })
        assertEquals(t0 + 60 * min, list[0].frameAtMs)
        val state = TpmsCalibrations.state(list)
        assertTrue(state is CalibrationState.Confirmed)
        assertEquals(PressureReference.ABSOLUTE, (state as CalibrationState.Confirmed).reference)
        // One that says gauge: nothing is confirmed until the wrong one is fixed
        val wrong = data.copy(events = data.events + check("e4", rear, t0 + 401 * min, "43.6"))
        assertEquals(CalibrationState.Conflict(2, 1), TpmsCalibrations.state(TpmsCalibrations.pair(wrong, bindings, samples)))
    }

    @Test fun referenceTurnsAReadingIntoWhatTheGaugeSays() {
        assertEquals(301_793 - TpmsParser.ATMOSPHERE_PA, TpmsReference.gauge(301_793, PressureReference.ABSOLUTE))
        assertEquals(200_000, TpmsReference.gauge(200_000, PressureReference.GAUGE))
        assertEquals(200_000, TpmsReference.gauge(200_000, null))
        assertFalse(TpmsParser.VALIDATED)
    }

    @Test fun diagnosisSaysWhyASensorIsOrIsNotReceived() {
        val now = t0 + 1_000 * min
        val b = bindings[1]
        val none = TpmsDiagnosis.report(b, emptyList(), null, now)
        assertTrue(none.verdict.startsWith("No se ha recibido nada del sensor 13AE32"))
        val frames = (0 until 5).map { FrameSeen(now - (5 - it) * 2 * min, -60, "TPMS1_13AE32", "13ae32", "13ae32", 90) }
        val ok = TpmsDiagnosis.report(b, frames, frames.last(), now)
        assertEquals(5, ok.count24h)
        assertEquals(2 * min, ok.medianGapMs)
        assertTrue(ok.verdict, ok.verdict.startsWith("Se recibe bien: señal buena"))
        val weak = TpmsDiagnosis.report(b, listOf(frames.last().copy(rssi = -92)), null, now)
        assertTrue(weak.verdict.startsWith("Llega con señal muy débil"))
        // A TPMS heard with another ID: probably the card's ID written wrong
        val other = FrameSeen(now - min, -70, "TPMS1_13AE23", "13ae23", null, 88)
        val s = TpmsDiagnosis.strangers(setOf("13ae32", "23a434"), frames + other + other.copy(atMs = now - 3 * min))
        assertEquals(listOf(StrangerSensor("13ae23", 1, 2, now - min, -70)), s)
    }
}
