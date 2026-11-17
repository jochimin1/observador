package com.ubers.observador

import com.ubers.observador.domain.lifecycle.OfferCardParser
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.sectors.CloseEvidence
import com.ubers.observador.domain.sectors.CloseTarget
import com.ubers.observador.domain.sectors.LearnedX
import com.ubers.observador.domain.sectors.UberCardX
import com.ubers.observador.domain.sectors.UberCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * 1.63.0: the two full Uber screens of the driver's evidence export (2026-11-16, Uber 4.605.10002),
 * with the passengers' places replaced by TEST text: a single card and the Radar. Uber's X is the
 * unlabeled Button after «Me interesa» (nodes 79 and 87).
 */
class UberCardXTest {

    private val screens: List<ScreenSnapshot> by lazy {
        val text = javaClass.getResource("/cierres/evidencia-uber-4.605.txt")!!.readText()
        text.split("=== Cierre ").filter { it.isNotBlank() }.map { block ->
            val nodes = block.lines().filter { it.contains(" | @") }.map { line ->
                val p = line.split(" | ")
                val flags = p[5]
                val b = p[6].removePrefix("@").split(',').map { it.trim().toInt() }
                ScreenNode(
                    text = p[2].removePrefix("t=").ifEmpty { null },
                    contentDescription = p[3].removePrefix("d=").ifEmpty { null },
                    viewId = p[4].removePrefix("id=").ifEmpty { null }?.let { "com.ubercab.driver:id/$it" },
                    className = p[1],
                    clickable = flags.startsWith("toca"),
                    enabled = !flags.contains("inactivo"),
                    left = b[0], top = b[1], right = b[2], bottom = b[3],
                    visibleToUser = !flags.contains("oculto")
                )
            }
            ScreenSnapshot(1_763_323_315_000, "com.ubercab.driver", nodes, 7)
        }
    }

    private fun offerOf(s: ScreenSnapshot, price: Double) =
        UberCards.withPrice(UberCards.of(s), price)!!.let { c -> OfferCardParser.parseAll(s.copy(nodes = s.nodes.subList(c.region.first, c.region.last + 1)).texts).single() }

    @Test fun theXOfTheRealCapturesIsFoundByPlaceAndShape() {
        val (single, radar) = screens
        assertEquals(82, single.nodes.size)
        // The words never found it: it has none
        assertTrue(CloseTarget.find(single, 90.51) is CloseTarget.Result.Refused)
        assertEquals(CloseTarget.Result.Found(79, UberCardX.LABEL), UberCardX.find(single, 90.51))
        assertEquals(CloseTarget.Result.Found(87, UberCardX.LABEL), UberCardX.find(radar, 240.64))
        // The whole check before a touch: same window, same card content, then the X
        assertEquals(CloseTarget.Result.Found(79, UberCardX.LABEL), CloseTarget.find(single, offerOf(single, 90.51), 7))
        assertEquals(CloseTarget.Result.Found(87, UberCardX.LABEL), CloseTarget.find(radar, offerOf(radar, 240.64), 7))
        assertTrue(CloseTarget.find(single, offerOf(single, 90.51), 8) is CloseTarget.Result.Refused)
        // The card's box is the CardView, not the screen
        assertEquals(com.ubers.observador.domain.sectors.Box(44, 1047, 1036, 2166), UberCards.of(single).single().box)
    }

    @Test fun anythingThatIsNotExactlyThatButtonIsRefused() {
        val single = screens[0]
        fun with(change: (MutableList<ScreenNode>) -> Unit) = single.copy(nodes = single.nodes.toMutableList().also(change))
        val named = with { it[79] = it[79].copy(text = "Aceptar") }
        assertTrue(UberCardX.find(named, 90.51) is CloseTarget.Result.Refused)
        val twice = with { it.add(80, it[79].copy()) }
        assertTrue(UberCardX.find(twice, 90.51) is CloseTarget.Result.Refused)
        val bottomLeft = with { it[79] = it[79].copy(left = 88, top = 1800, right = 220, bottom = 1932) }
        assertTrue(UberCardX.find(bottomLeft, 90.51) is CloseTarget.Result.Refused)
        val hidden = with { it[79] = it[79].copy(visibleToUser = false) }
        assertTrue(UberCardX.find(hidden, 90.51) is CloseTarget.Result.Refused)
        val huge = with { it[79] = it[79].copy(left = 700, top = 1060, right = 1000, bottom = 1360) }
        assertTrue(UberCardX.find(huge, 90.51) is CloseTarget.Result.Refused)
        // The «Me interesa» button is never a candidate
        assertFalse(UberCardX.candidates(single, UberCards.of(single).single()).contains(77))
    }

    @Test fun onlyTheCapturedUberVersionTouchesWithoutLearning() {
        assertTrue(UberCardX.confirmedFor("4.605.10002"))
        assertTrue(UberCardX.confirmedFor("4.605.10100"))
        assertFalse(UberCardX.confirmedFor("4.606.10000"))
        assertFalse(UberCardX.confirmedFor(null))
    }

    @Test fun aHandCloseOnTheRealXTeachesAndFindsIt() {
        val (single, radar) = screens
        // Before 1.63.0 this touch taught nothing: the X is outside the card's run of text
        val a = LearnedX.observe(single, single.nodes[79], 1, "4.605.10002")
        val b = LearnedX.observe(radar, radar.nodes[87], 2, "4.605.10002")
        assertNotNull(a); assertNotNull(b)
        val text = listOf(a!!, b!!, a.copy(atMs = 3)).joinToString("\n") { LearnedX.encode(it) }
        val learned = LearnedX.state(text, "4.605.10002") as LearnedX.State.Learned
        assertEquals(CloseTarget.Result.Found(79, "X aprendida"), LearnedX.find(single, 90.51, learned.signature))
        assertEquals(CloseTarget.Result.Found(87, "X aprendida"), LearnedX.find(radar, 240.64, learned.signature))
    }

    @Test fun theEvidenceNowSaysWhatTheRuleFinds() {
        val text = CloseEvidence.format(screens[0], 90.51, "Destino: Alameda", "prueba", "4.605.10002", ZoneOffset.UTC)
        assertTrue(text, text.contains("x_por_regla=79\n"))
    }
}
