package com.ubers.observador

import com.ubers.observador.domain.lifecycle.OfferCardParser
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.domain.sectors.*
import org.junit.Assert.*
import org.junit.Test

class PendingSectorClosesTest {
    private val config = CloseConfiguration(true, true, true, true, listOf(CareSector("Engombe", level = SectorLevel.CLOSE)))
    private val offer = UberOffer(224.10, 0.9, 6, 10.2, 22, pickupAddress = "Los Ríos", dropoffAddress = "Engombe",
        appearanceId = "OFFER-V2-a")
    private val registry = PendingSectorCloses().apply { configure(config); observe(listOf(offer), 7) }
    private fun schedule() = registry.add(offer, 7, "Destino: Engombe", registry.revision)!!

    @Test fun changingAnyGateCancelsThePendingTicket() {
        val changes = listOf(config.copy(observing = false), config.copy(automatic = false), config.copy(sectorsEnabled = false),
            config.copy(showOverlay = false), config.copy(sectors = emptyList()), config.copy(sectors = listOf(CareSector("Engombe"))))
        changes.forEach { changed ->
            val r = PendingSectorCloses().apply { configure(config); observe(listOf(offer), 7) }
            val ticket = r.add(offer, 7, "Destino", r.revision)!!
            assertEquals(listOf(ticket), r.configure(changed))
            assertNull(r.take(ticket))
            assertEquals(CloseOutcome.NONE, r.outcome(ticket, 0, true))
        }
    }

    @Test fun aUserTapAlsoBlocksWorkWhichHasNotYetBeenScheduled() {
        val revision = registry.revision
        registry.cancelAll()
        assertNull(registry.add(offer, 7, "Destino", revision))
    }

    @Test fun staleAsyncWorkCannotRearmAfterChangingModeAndBack() {
        val revision = registry.revision
        registry.configure(config.copy(automatic = false))
        registry.configure(config)
        assertNull(registry.add(offer, 7, "Destino", revision))
    }

    @Test fun aLostOrReplacedCardCannotFire() {
        val ticket = schedule()
        assertEquals(listOf(ticket), registry.observe(listOf(offer.copy(appearanceId = "OFFER-V2-b")), 7))
        assertNull(registry.take(ticket))
        assertEquals(CloseOutcome.NONE, registry.outcome(ticket, 0, true))
    }

    @Test fun radarReorderingKeepsTheCorrectPendingTicket() {
        val other = offer.copy(price = 150.0, dropoffAddress = "Gazcue", appearanceId = "OFFER-V2-b")
        registry.observe(listOf(offer, other), 7)
        val ticket = schedule()
        assertTrue(registry.observe(listOf(other, offer), 7).isEmpty())
        assertEquals(ticket, registry.take(ticket))
        assertEquals(CloseOutcome.SIMULATE, registry.outcome(ticket, 0, true))
    }

    @Test fun duplicateCardsAndDifferentWindowsAreRefused() {
        registry.observe(listOf(offer, offer.copy(appearanceId = "OFFER-V2-b")), 7)
        assertNull(registry.add(offer, 7, "Destino", registry.revision))
        registry.observe(listOf(offer), 8)
        assertNull(registry.add(offer, 7, "Destino", registry.revision))
    }

    @Test fun currentBudgetAndVisibleCancelControlAreRequiredAtExecution() {
        val ticket = schedule()
        assertEquals(CloseOutcome.SIMULATE, registry.outcome(ticket, 29, true))
        assertEquals(CloseOutcome.NONE, registry.outcome(ticket, 30, true))
        assertEquals(CloseOutcome.NONE, registry.outcome(ticket, 0, false))
        assertFalse(CloseTarget.VALIDATED)
    }

    @Test fun ticketIsConsumedOnceAndCannotReturnAfterClear() {
        val ticket = schedule()
        assertEquals(ticket, registry.take(ticket))
        assertNull(registry.take(ticket))
        registry.clear()
        assertEquals(CloseOutcome.NONE, registry.outcome(ticket, 0, true))
    }

    private fun card(destination: String = "Engombe", price: String = "DOP224.10", product: String = "Moto") = listOf(
        ScreenNode(text = product), ScreenNode(contentDescription = "Cerrar", clickable = true),
        ScreenNode(text = price), ScreenNode(text = "DOP20.19/km est."),
        ScreenNode(text = "A 6 min (0.9 km) de distancia"), ScreenNode(text = "Los Ríos"),
        ScreenNode(text = "Viaje: 22 min (10.2 km)"), ScreenNode(text = destination),
        ScreenNode(contentDescription = "Me interesa", clickable = true), ScreenNode(text = "Me interesa")
    )
    private fun screen(nodes: List<ScreenNode>, window: Int = 7) = ScreenSnapshot(1_000, "com.ubercab.driver", nodes, window)

    @Test fun productionParserVerifiesTheEntireCardBeforeSelectingAnX() {
        val snapshot = screen(card())
        val expected = OfferCardParser.parseAll(snapshot.texts).single().copy(appearanceId = "OFFER-V2-a")
        assertEquals(224.10, expected.price, 0.0)
        assertTrue(CloseTarget.find(snapshot, expected, 7) is CloseTarget.Result.Found)
        assertTrue(CloseTarget.find(screen(card("Gazcue")), expected, 7) is CloseTarget.Result.Refused)
        assertTrue(CloseTarget.find(screen(card(product = "Flash")), expected, 7) is CloseTarget.Result.Refused)
        assertTrue(CloseTarget.find(screen(card(), window = 8), expected, 7) is CloseTarget.Result.Refused)
    }

    @Test fun fullCardVerificationSurvivesRadarReorderingButRefusesSamePriceCards() {
        val expected = OfferCardParser.parseAll(screen(card()).texts).single()
        val other = card(destination = "Gazcue", price = "DOP150.45")
        assertTrue(CloseTarget.find(screen(other + card()), expected, 7) is CloseTarget.Result.Found)
        assertTrue(CloseTarget.find(screen(card() + other), expected, 7) is CloseTarget.Result.Found)
        assertTrue(CloseTarget.find(screen(card() + card("Gazcue")), expected, 7) is CloseTarget.Result.Refused)
    }

    @Test fun hiddenCloseNodesCannotBeSelected() {
        val expected = OfferCardParser.parseAll(screen(card()).texts).single()
        val hidden = card().toMutableList().also { it[1] = it[1].copy(visibleToUser = false) }
        assertTrue(CloseTarget.find(screen(hidden), expected, 7) is CloseTarget.Result.Refused)
    }

    @Test fun dailyBudgetSurvivesReopeningAndNeverAllowsAttempt31() {
        val dir = java.nio.file.Files.createTempDirectory("close-budget-").toFile()
        try {
            val file = java.io.File(dir, "today.txt")
            repeat(29) { assertTrue(DailyCloseBudget.reserve(file, 0)) }
            assertEquals(29, DailyCloseBudget.used(java.io.File(file.path), 0))
            assertTrue(DailyCloseBudget.reserve(file, 0))
            assertFalse(DailyCloseBudget.reserve(file, 0))
            assertEquals(30, DailyCloseBudget.used(file, 0))
            assertEquals(0, DailyCloseBudget.used(java.io.File(dir, "tomorrow.txt"), 0))
        } finally { dir.deleteRecursively() }
    }

    @Test fun budgetIncludesLegacyClosesAndRefusesUnreadableStorage() {
        val dir = java.nio.file.Files.createTempDirectory("close-budget-").toFile()
        try {
            val file = java.io.File(dir, "today.txt")
            assertTrue(DailyCloseBudget.reserve(file, 12))
            assertEquals(13, DailyCloseBudget.used(file, 12))
            assertFalse(DailyCloseBudget.reserve(file, 30))
            assertEquals(30, DailyCloseBudget.used(dir, 0))
            assertFalse(DailyCloseBudget.reserve(dir, 0))
        } finally { dir.deleteRecursively() }
    }
}
