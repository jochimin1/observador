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

    /** Real advertisements of the driver's rear sensor, recorded on 2026-11-15 (Observador 1.54.0 recorder). */
    private val real = listOf(
        "0201050303b0fb13ff000181eaca23a434e19a04000f0d000064000d0954504d53325f323341343334000000000000000000000000000000000000000000",
        "0201050303b0fb13ff000181eaca23a43452960400fc0c000064000d0954504d53325f323341343334000000000000000000000000000000000000000000",
        "0201050303b0fb13ff000181eaca23a43432920400db0c000064000d0954504d53325f323341343334000000000000000000000000000000000000000000")
    private fun rec(hex: String, name: String? = "TPMS2_23A434") = BleFrame(t0, "81:EA:CA:23:A4:34", name, -60, Hex.bytes(hex)!!)

    @Test fun theRearSensorsRealAdvertisementsAreRead() {
        val r = real.map { (TpmsParser.parse(rec(it)) as TpmsParse.Ok).reading }
        assertTrue(r.all { it.sensorId == "23a434" && it.batteryPct == 100 && it.leak == false && it.parserVersion == "fbb0-v1" })
        assertEquals(listOf(301_793, 300_626, 299_570), r.map { it.pressurePa })
        assertEquals(listOf(3_343, 3_324, 3_291), r.map { it.temperatureCenti })
        // 301.8 kPa absolute is 29.1 psi on a gauge; gauge or absolute is still to be confirmed
        assertEquals(200_468, TpmsParser.gaugeIfAbsolute(301_793))
        assertFalse(r.first().validated)
        assertEquals(2 to "23a434", TpmsParser.nameParts("TPMS2_23A434"))
        assertTrue(Advertisement.mentions(rec(real[0]), "23A434"))
        // Without the name from Android the name AD inside the advertisement is used
        assertTrue(TpmsParser.parse(rec(real[0], name = null)) is TpmsParse.Ok)
    }

    @Test fun badFramesAreRefusedNotGuessed() {
        // A name that names another sensor
        assertTrue(TpmsParser.parse(rec(real[0], name = "TPMS1_13AE32")) is TpmsParse.Rejected)
        // Impossible pressure (0xFFFFFFFF) and temperature
        assertTrue(TpmsParser.parse(rec(real[0].replace("e19a0400", "ffffffff"))) is TpmsParse.Rejected)
        assertTrue(TpmsParser.parse(rec(real[0].replace("0f0d0000", "ffff0000"))) is TpmsParse.Rejected)
        // Negative temperature (-5.5 °C) and a flat tyre are real values
        val cold = (TpmsParser.parse(rec(real[0].replace("e19a0400", "00000000").replace("0f0d0000", "dafdffff"))) as TpmsParse.Ok).reading
        assertEquals(-550, cold.temperatureCenti); assertEquals(0, cold.pressurePa)
        // Unknown status stays unknown, never a leak; impossible battery is dropped
        assertNull((TpmsParser.parse(rec(real[0].replace("0f0d00006400", "0f0d00006401"))) as TpmsParse.Ok).reading.leak)
        assertNull((TpmsParser.parse(rec(real[0].replace("0f0d00006400", "0f0d0000c800"))) as TpmsParse.Ok).reading.batteryPct)
        // Other devices, short or damaged records
        assertTrue(TpmsParser.parse(BleFrame(t0, "x", "43\" Crystal UHD", -85, Hex.bytes("0201181bff75004204010167746dfae86c85766dfae86c84245c190a3a11321008343322204372797374616c20554844")!!)) is TpmsParse.Rejected)
        assertTrue(TpmsParser.parse(BleFrame(t0, "x", null, -50, Hex.bytes("020106")!!)) is TpmsParse.Rejected)
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
        assertEquals(SensorState.RECEIVING, SensorFreshness.state(b, t0 + 10_000, true, t0 + 3 * 60_000))
        assertEquals(SensorState.STALE, SensorFreshness.state(b, t0 + 10_000, true, t0 + 10 * 60_000))
        // A frame from before the association does not count for it
        assertEquals(SensorState.WAITING, SensorFreshness.state(b, t0 - 1, true, t0 + 1))
        assertEquals("hace 3 min", SensorFreshness.ago(t0 + 200_000, t0))
    }

    @Test fun recordingKeepsChangesAndDropsImmediateRepeats() {
        val d = FrameDeduper()
        val f = rec(real[0])
        assertTrue(d.accept(f))
        assertFalse(d.accept(f.copy(receivedAt = t0 + 500)))
        assertTrue(d.accept(f.copy(receivedAt = t0 + 2_500)))
        assertTrue(d.accept(rec(real[1]).copy(receivedAt = t0 + 2_600)))
        assertTrue(d.accept(f.copy(address = "other", receivedAt = t0 + 2_600)))
    }
}
