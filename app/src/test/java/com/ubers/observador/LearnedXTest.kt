package com.ubers.observador

import com.ubers.observador.domain.lifecycle.OfferCardParser
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.sectors.CloseTarget
import com.ubers.observador.domain.sectors.LearnedX
import org.junit.Assert.*
import org.junit.Test

class LearnedXTest {

    /** An Uber card whose X is an icon with no words (the case the word search cannot find). */
    private fun card(price: String = "DOP224.10", destination: String = "Engombe", dy: Int = 0, xDesc: String? = null) = listOf(
        ScreenNode(text = "Moto", left = 20, top = 200 + dy, right = 300, bottom = 240 + dy),
        ScreenNode(className = "android.widget.ImageView", contentDescription = xDesc, clickable = true,
            left = 640, top = 190 + dy, right = 700, bottom = 250 + dy),
        ScreenNode(text = price, left = 20, top = 260 + dy, right = 400, bottom = 320 + dy),
        ScreenNode(text = "A 6 min (0.9 km) de distancia", left = 20, top = 340 + dy, right = 600, bottom = 380 + dy),
        ScreenNode(text = "Los Ríos", left = 20, top = 380 + dy, right = 600, bottom = 420 + dy),
        ScreenNode(text = "Viaje: 22 min (10.2 km)", left = 20, top = 440 + dy, right = 600, bottom = 480 + dy),
        ScreenNode(text = destination, left = 20, top = 480 + dy, right = 600, bottom = 520 + dy),
        ScreenNode(className = "android.widget.Button", text = "Aceptar", clickable = true, left = 20, top = 560 + dy, right = 700, bottom = 640 + dy)
    )

    private fun screen(nodes: List<ScreenNode>, window: Int = 7) = ScreenSnapshot(1_000, "com.ubercab.driver", nodes, window)

    private fun learnedFrom(vararg screens: List<ScreenNode>, version: String = "4.500"): String =
        screens.mapIndexed { k, nodes ->
            LearnedX.encode(LearnedX.observe(screen(nodes), nodes[1], 1_000L + k, version)!!)
        }.joinToString("\n")

    @Test fun anIconWithNoWordsIsLearnedFromThreeHandClosesAndThenFoundExactly() {
        val text = learnedFrom(card(), card(dy = 30), card(price = "DOP150.45", dy = -20))
        val state = LearnedX.state(text, "4.500")
        assertTrue(state is LearnedX.State.Learned)
        val sig = (state as LearnedX.State.Learned).signature
        assertEquals(60, sig.width)
        assertTrue(sig.above)
        // The word search refuses this card; the learned X finds the one icon
        assertTrue(CloseTarget.find(screen(card()), 224.10) is CloseTarget.Result.Refused)
        val expected = OfferCardParser.parseAll(screen(card()).texts).single()
        val found = CloseTarget.find(screen(card(dy = 50)), expected, 7, sig)
        assertEquals(CloseTarget.Result.Found(1, "X aprendida"), found)
        // Two cards: the X of the right card, by its price
        val two = card(price = "DOP150.45", destination = "Gazcue") + card(dy = 700)
        assertEquals(9, (CloseTarget.find(screen(two), expected, 7, sig) as CloseTarget.Result.Found).index)
    }

    @Test fun twoClosesOrAnotherUberVersionAreNotEnough() {
        val two = learnedFrom(card(), card(dy = 10))
        assertEquals(LearnedX.State.Learning(2, 2, false), LearnedX.state(two, "4.500"))
        val three = learnedFrom(card(), card(), card())
        assertEquals(LearnedX.State.Learning(0, 0, true), LearnedX.state(three, "4.600"))
        assertTrue(LearnedX.state(three, "4.500") is LearnedX.State.Learned)
    }

    @Test fun forgettingStartsAgainWithoutErasingAndMixedRecordsLearnNothing() {
        val three = learnedFrom(card(), card(), card())
        val forgotten = three + "\n" + LearnedX.forgetLine(9_000)
        assertEquals(LearnedX.State.Learning(0, 0, false), LearnedX.state(forgotten, "4.500"))
        // Three on the icon, two on a different button: 3 of 5 is not three in four
        val other = card().toMutableList().also { it[1] = it[1].copy(className = "android.widget.ImageButton") }
        val mixed = three + "\n" + learnedFrom(other, other)
        assertEquals(LearnedX.State.Learning(3, 5, false), LearnedX.state(mixed, "4.500"))
    }

    @Test fun theCardButtonAndAcceptingWordsAreNeverLearned() {
        val c = card()
        assertNull(LearnedX.observe(screen(c), c.last(), 1, "4.500"))
        val accept = c.toMutableList().also { it[1] = it[1].copy(contentDescription = "Aceptar viaje") }
        assertNull(LearnedX.observe(screen(accept), accept[1], 1, "4.500"))
        // A touch outside any card (the map, the menu) teaches nothing
        assertNull(LearnedX.observe(screen(c), ScreenNode(className = "android.widget.ImageView", left = 0, top = 0, right = 60, bottom = 60), 1, "4.500"))
    }

    @Test fun learnedButtonThatChangedOrIsDoubledIsRefused() {
        val sig = (LearnedX.state(learnedFrom(card(), card(), card()), "4.500") as LearnedX.State.Learned).signature
        val expected = OfferCardParser.parseAll(screen(card()).texts).single()
        val moved = card().toMutableList().also { it[1] = it[1].copy(left = 20, right = 80) }
        assertTrue(CloseTarget.find(screen(moved), expected, 7, sig) is CloseTarget.Result.Refused)
        val doubled = card().toMutableList().also { it.add(2, it[1].copy()) }
        assertTrue(CloseTarget.find(screen(doubled), expected, 7, sig) is CloseTarget.Result.Refused)
        val hidden = card().toMutableList().also { it[1] = it[1].copy(visibleToUser = false) }
        assertTrue(CloseTarget.find(screen(hidden), expected, 7, sig) is CloseTarget.Result.Refused)
        assertTrue(CloseTarget.find(screen(card(), window = 8), expected, 7, sig) is CloseTarget.Result.Refused)
    }

    @Test fun linesSurviveSeparatorsInLabels() {
        val o = LearnedX.observe(screen(card(xDesc = "Cerrar; 50%\noferta")), card(xDesc = "Cerrar; 50%\noferta")[1], 5, "4.5;0")!!
        assertEquals(o, LearnedX.decode(LearnedX.encode(o)))
        assertNull(LearnedX.decode("x1;roto"))
    }
}

class CloseSummaryTest {
    @Test fun closeLogLinesAreCountedByResult() {
        val lines = listOf(
            "14:03:12 · CERRADA · Destino: Engombe · RD$294.67",
            "14:10:00 · SIMULADA (botón «Cerrar») · Destino: Engombe · RD$150.00",
            "14:12:00 · NO CERRADA (Cancelado por ti) · Recogida: Los Ríos · Destino: Engombe · RD$120.00",
            "14:20:00 · NO CERRADA (La tarjeta cambió) · Destino: Engombe · RD$99.00",
            "14:21:00 · NO CERRADA (La tarjeta cambió) · Destino: Engombe · RD$98.00",
            "14:22:00 · NO CERRADA (Límite diario o registro no disponible) · Destino: Engombe · RD$97.00",
            "14:23:00 · TOQUE SOLICITADO · Destino: Engombe · RD$96.00",
            "14:30:00 · X DE UBER TOCADA A MANO · Aprendiendo: 1 de 3 cierres a mano con la X · Recogida: A · Destino: B · RD$80.00",
            "basura"
        )
        val s = com.ubers.observador.domain.sectors.CloseSummaries.of(lines)
        assertEquals(1, s.closed)
        assertEquals(1, s.simulated)
        assertEquals(1, s.stoppedByDriver)
        assertEquals(1, s.byHand)
        assertEquals(mapOf("La tarjeta cambió" to 2, "Límite diario o registro no disponible" to 1), s.notClosed)
        assertEquals("Hoy: 1 cerradas, 1 en simulación, 1 impedidas por ti, 3 sin cerrar, 1 cerradas a mano.",
            com.ubers.observador.domain.sectors.CloseSummaries.line("Hoy", s))
        assertEquals("Sin cerrar: La tarjeta cambió (2); Límite diario o registro no disponible (1)",
            com.ubers.observador.domain.sectors.CloseSummaries.reasons(s))
        assertTrue(com.ubers.observador.domain.sectors.CloseSummaries.of(emptyList()).isEmpty)
    }
}
