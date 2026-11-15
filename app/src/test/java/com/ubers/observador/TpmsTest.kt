package com.ubers.observador

import com.ubers.observador.domain.vehicle.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.54.0: sensor IDs, association, advertisement structure and the provisional parser. */
class TpmsTest {
    private val t0 = 1_800_000_000_000L
    private val v = "vehicle-initial"

    @Test fun kitCardCodesAreRead() {
        assertEquals(SensorIds.Card.Single("13ae32"), SensorIds.parseCard(" 13AE32 ").getOrThrow())
        assertEquals(SensorIds.Card.Pair("13ae32", "23a434"), SensorIds.parseCard("13AE32&23A434").getOrThrow())
        assertTrue(SensorIds.parseCard("13AE32&13ae32").isFailure)
        assertTrue(SensorIds.parseCard("13AE3").isFailure)
        assertTrue(SensorIds.parseCard("13AE3Z").isFailure)
        assertTrue(SensorIds.parseCard("13AE32&23A434&1").isFailure)
        assertEquals("13AE32", SensorIds.shown("13ae32"))
    }

    private fun bind(id: String, pos: WheelPosition, at: Long) = SensorBinding("b-$id-$at", v, pos, id, boundAt = at, createdAt = at, updatedAt = at)

    @Test fun oneSensorPerWheelAndNeverOnBoth() {
        val front = bind("13ae32", WheelPosition.FRONT, t0)
        assertEquals(listOf(front), SensorBindings.bind(emptyList(), front).getOrThrow())
        // Same sensor on the rear: refused
        assertTrue(SensorBindings.bind(listOf(front), bind("13ae32", WheelPosition.REAR, t0 + 1)).isFailure)
        // Same sensor again on the front: nothing to write
        assertTrue(SensorBindings.bind(listOf(front), bind("13ae32", WheelPosition.FRONT, t0 + 1)).getOrThrow().isEmpty())
        // A replacement closes and keeps the old one
        val written = SensorBindings.bind(listOf(front), bind("aaaaaa", WheelPosition.FRONT, t0 + 5)).getOrThrow()
        assertEquals(2, written.size)
        assertEquals(t0 + 5, written.first { it.sensorId == "13ae32" }.unboundAt)
        assertEquals("aaaaaa", SensorBindings.active(written, v, WheelPosition.FRONT)!!.sensorId)
    }

    /** A frame laid out as the study describes: flags AD, then a manufacturer AD carrying the fields. */
    private fun frame(id: String = "13ae32", pressurePa: Long = 225_000, tempCenti: Int = 3_150, battery: Int = 95, leak: String = "00", pattern: String = "0001"): BleFrame {
        fun le(v: Long, bytes: Int) = (0 until bytes).joinToString("") { "%02x".format((v shr (8 * it)) and 0xff) }
        // positions in hex chars: 0..17 header, 18..21 pattern, 22..27 filler, 28..33 id, 34..41 p, 42..49 t, 50..51 bat, 52..53 leak
        val payload = "ffaaaa" + "000000" + pattern + "000000" + id + le(pressurePa, 4) + le(tempCenti.toLong() and 0xffffffffL, 4) + "%02x".format(battery) + leak
        // header: 02 01 06 (flags) then <len> ff ... ; build so the pattern lands at char 18
        val body = "aabbccdd" + payload.substring(12) // manufacturer data after type
        val ad = "%02x".format(1 + body.length / 2) + "ff" + body
        val hex = "020106" + ad
        return BleFrame(t0, "AA:BB:CC:DD:EE:FF", null, -60, Hex.bytes(hex)!!)
    }

    @Test fun theProvisionalParserReadsTheStudiedLayout() {
        val f = frame()
        assertEquals("0001", f.hex.substring(18, 22))
        val r = (TpmsParser.parse(f) as TpmsParse.Ok).reading
        assertEquals("13ae32", r.sensorId)
        assertEquals(225_000, r.pressurePa)
        assertEquals(3_150, r.temperatureCenti)
        assertEquals(95, r.batteryPct)
        assertEquals(false, r.leak)
        assertFalse(r.validated)
        assertTrue(Advertisement.mentions(f, "13AE32"))
        // Negative temperature and a flat tyre (0 Pa) are real values
        val cold = (TpmsParser.parse(frame(tempCenti = -550, pressurePa = 0)) as TpmsParse.Ok).reading
        assertEquals(-550, cold.temperatureCenti)
        assertEquals(0, cold.pressurePa)
        // Unknown leak flag stays unknown; impossible battery is dropped, not invented
        assertNull((TpmsParser.parse(frame(leak = "7f", battery = 200)) as TpmsParse.Ok).reading.leak)
        assertNull((TpmsParser.parse(frame(battery = 200)) as TpmsParse.Ok).reading.batteryPct)
    }

    @Test fun badFramesAreRefusedNotGuessed() {
        assertTrue(TpmsParser.parse(frame(pattern = "0002")) is TpmsParse.Rejected)
        assertTrue(TpmsParser.parse(frame(pressurePa = 5_000_000)) is TpmsParse.Rejected)
        assertTrue(TpmsParser.parse(frame(tempCenti = 30_000)) is TpmsParse.Rejected)
        assertTrue(TpmsParser.parse(BleFrame(t0, "x", null, -50, Hex.bytes("020106")!!)) is TpmsParse.Rejected)
        // A length running past the end
        assertTrue(TpmsParser.parse(BleFrame(t0, "x", null, -50, Hex.bytes("0201060aff0102")!!)) is TpmsParse.Rejected)
        assertNull(Advertisement.structures(Hex.bytes("05ff01")!!))
        assertEquals(2, Advertisement.structures(Hex.bytes("02010603ff0102000000")!!)!!.size)
        assertNull(Hex.bytes("abc"))
        assertNull(Hex.bytes("zz"))
    }

    @Test fun freshnessIsFromReceptionAndOldDataNeverLooksCurrent() {
        val b = bind("13ae32", WheelPosition.FRONT, t0)
        assertEquals(SensorState.NO_SENSOR, SensorFreshness.state(null, null, true, t0))
        assertEquals(SensorState.NOT_LISTENING, SensorFreshness.state(b, null, false, t0))
        assertEquals(SensorState.WAITING, SensorFreshness.state(b, null, true, t0))
        assertEquals(SensorState.RECEIVING, SensorFreshness.state(b, t0 + 10_000, true, t0 + 60_000))
        assertEquals(SensorState.STALE, SensorFreshness.state(b, t0 + 10_000, true, t0 + 10 * 60_000))
        // A frame from before the association does not count for it
        assertEquals(SensorState.WAITING, SensorFreshness.state(b, t0 - 1, true, t0 + 1))
        assertEquals("hace 3 min", SensorFreshness.ago(t0 + 200_000, t0))
    }

    @Test fun recordingKeepsChangesAndDropsImmediateRepeats() {
        val d = FrameDeduper()
        val f = frame()
        assertTrue(d.accept(f))
        assertFalse(d.accept(f.copy(receivedAt = t0 + 500)))
        assertTrue(d.accept(f.copy(receivedAt = t0 + 2_500)))
        assertTrue(d.accept(frame(pressurePa = 230_000).copy(receivedAt = t0 + 2_600)))
        assertTrue(d.accept(f.copy(address = "other", receivedAt = t0 + 2_600)))
    }
}
