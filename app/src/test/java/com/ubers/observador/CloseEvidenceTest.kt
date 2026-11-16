package com.ubers.observador

import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.sectors.CloseEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 1.56.0: the evidence written for every «Cerrar» countdown, to validate Uber's X. */
class CloseEvidenceTest {
    private fun n(text: String? = null, desc: String? = null, click: Boolean = false, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        ScreenNode(text = text, contentDescription = desc, className = "android.view.View", clickable = click, left = l, top = t, right = r, bottom = b)

    private val snap = ScreenSnapshot(1_800_000_000_000L, "com.ubercab.driver", listOf(
        n(click = true, l = 960, t = 300, r = 1040, b = 380),           // 0: icon without words, square → possible X
        n("UberX"), n("DOP 245.50"), n("A 4 min (1.2 km)"), n("Engombe, Santo Domingo Oeste"),
        n(click = true, l = 0, t = 400, r = 1080, b = 420),             // 5: wide strip, not an X
        n("Aceptar", click = true, l = 40, t = 900, r = 1040, b = 1000), // 6: action of card 1
        n(click = true, l = 960, t = 1100, r = 1040, b = 1180),         // 7: X of another card
        n("DOP 120.00"), n("Aceptar", click = true)))

    @Test fun theCardAndIconShapedButtonsAreMarked() {
        assertEquals(0..6, CloseEvidence.cardRegion(snap, 245.5))
        assertEquals(listOf(0), CloseEvidence.shapeCandidates(snap, CloseEvidence.cardRegion(snap, 245.5)).map { it.index })
        assertEquals(7..9, CloseEvidence.cardRegion(snap, 120.0))
        val text = CloseEvidence.format(snap, 245.5, "Engombe", "SIMULADA (No se reconoce la X de la tarjeta)", "4.512.10001", ZoneOffset.UTC)
        assertTrue(text, text.startsWith("=== Cierre 2027-01-15T08:00Z\nuber=4.512.10001 precio=245.5 lugares=Engombe"))
        assertTrue(text.contains("tarjeta=0..6\nposibles_x_por_forma=0\n"))
        assertTrue(text.contains("TX? 0 | View | t= | d= | id= | toca | @960,300,1040,380"))
        assertTrue(text.contains("T   2 | View | t=DOP 245.50"))
        assertEquals(2, CloseEvidence.count(text + text))
        assertEquals("evidencia-2027-01-15.txt", CloseEvidence.fileName(snap.timestampMs, ZoneOffset.UTC))
    }
}
