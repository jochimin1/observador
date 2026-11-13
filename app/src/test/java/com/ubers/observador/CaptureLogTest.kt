package com.ubers.observador

import com.ubers.observador.domain.lifecycle.CaptureDeduper
import com.ubers.observador.domain.lifecycle.CaptureLog
import com.ubers.observador.domain.lifecycle.EvidenceCode
import com.ubers.observador.domain.lifecycle.ScreenClassification
import com.ubers.observador.domain.lifecycle.ScreenEvidence
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSignatures
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.SnapshotSanitizer
import com.ubers.observador.domain.lifecycle.UberScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 1.33.2: the capture recorder's rules (file per day, entry header, dedupe, what is hidden). */
class CaptureLogTest {

    private val zone = ZoneId.of("America/Santo_Domingo")
    private val at = ZonedDateTime.of(2026, 10, 24, 14, 3, 12, 0, zone).toInstant().toEpochMilli()

    @Test fun oneFilePerLocalDay() {
        assertEquals("capturas-2026-10-24.txt", CaptureLog.fileName(at, zone))
        val lateNight = ZonedDateTime.of(2026, 10, 24, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("capturas-2026-10-24.txt", CaptureLog.fileName(lateNight, zone))
    }

    @Test fun entryHeaderSaysWhatWasUnderstood() {
        val c = ScreenClassification(
            UberScreenType.ARRIVED_OR_WAITING, 0.72,
            listOf(ScreenEvidence(EvidenceCode.ARRIVED_ACTION, "llegue", validated = false))
        )
        val e = CaptureLog.entry(at, zone, c, "TO_PICKUP", "paquete: x · nodos: 3")
        assertTrue(e.startsWith("=== 2026-10-24 14:03:12 · pantalla=ARRIVED_OR_WAITING 0.72 provisional · estado=TO_PICKUP"))
        assertTrue(e.contains("señales=ARRIVED_ACTION(\"llegue\")"))
        assertTrue(e.endsWith("paquete: x · nodos: 3\n\n"))
        assertEquals(2, CaptureLog.countEntries(e + e))
    }

    @Test fun unknownScreenStillRecorded() {
        val e = CaptureLog.entry(at, zone, null, "IDLE", "cuerpo")
        assertTrue(e.contains("pantalla=— · estado=IDLE · parser="))
        // 1.38.0: the versions sit between the state and the signals
        assertTrue(e.contains(" · uber=no disponible · señales=—"))
    }

    @Test fun sameScreenIsWrittenOnce() {
        val d = CaptureDeduper()
        assertTrue(d.isNew("a"))
        assertFalse(d.isNew("a"))
        assertTrue(d.isNew("b"))
        assertTrue(d.isNew("a"))
        d.reset()
        assertTrue(d.isNew("a"))
    }

    @Test fun summaryText() {
        assertEquals("Sin capturas guardadas.", CaptureLog.summary(0, 0, 0))
        assertEquals("1 día · 1 pantalla · 512 B", CaptureLog.summary(1, 1, 512))
        assertEquals("3 días · 412 pantallas · 1.2 MB", CaptureLog.summary(3, 412, 1_258_291))
    }

    @Test fun recorderKeepsAmountsButHidesNamesAndAddresses() {
        val snap = ScreenSnapshot(
            at, "com.ubercab.driver",
            listOf(
                ScreenNode(text = "DOP 136.06"),
                ScreenNode(text = "7 min (4.1 km)"),
                ScreenNode(text = "Llegar por TEST-Stefany"),
                ScreenNode(text = "Calle TEST-El Conde 105, Ciudad Colonial"),
                ScreenNode(text = "Llegué", clickable = true)
            )
        )
        val recorded = SnapshotSanitizer.describe(snap, ScreenSignatures.SPANISH, keepMeasureDigits = true)
        assertTrue(recorded.contains("\"dop 136.06\""))
        assertTrue(recorded.contains("\"7 min (4.1 km)\""))
        assertFalse(recorded.contains("Stefany"))
        assertFalse(recorded.contains("Conde"))
        assertTrue(recorded.contains("\"llegue\""))
        // The chat copy keeps masking digits as before
        val chat = SnapshotSanitizer.describe(snap, ScreenSignatures.SPANISH)
        assertTrue(chat.contains("\"dop ###.##\""))
    }
}
