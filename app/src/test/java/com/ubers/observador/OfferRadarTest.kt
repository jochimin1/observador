package com.ubers.observador

import com.ubers.observador.LifecycleFixtures.button
import com.ubers.observador.LifecycleFixtures.screen
import com.ubers.observador.LifecycleFixtures.text
import com.ubers.observador.data.db.action
import com.ubers.observador.domain.OfferAlerts
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.lifecycle.OfferCardAction
import com.ubers.observador.domain.lifecycle.OfferCards
import com.ubers.observador.domain.lifecycle.OfferParseCheck
import com.ubers.observador.domain.lifecycle.OfferParseStep
import com.ubers.observador.domain.lifecycle.OfferPatterns
import com.ubers.observador.domain.lifecycle.OfferPlacesReader
import com.ubers.observador.domain.lifecycle.OfferProductReader
import com.ubers.observador.domain.lifecycle.UberScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 8I: the offer cards of 17/10. Uber's "Radar de solicitud de viaje" lists several cards,
 * each with "Me interesa" instead of "Aceptar". Texts copied from those screenshots.
 */
class OfferRadarTest {

    private fun card(price: String, pickup: String, from: String, trip: String, to: String, action: String = "Me interesa") = listOf(
        "Moto", price, "Identidad digital verificada o Tarjeta de cré…", "4.94 (1074)", pickup, from, trip, to, action
    )

    private val single = card("DOP50.01", "A 5 min (1.9 km)", "Los Ríos", "Viaje: 6 min (2.2 km)", "Avenida John F. Kennedy, Los Prados")

    @Test
    fun `a card with Me interesa is read`() {
        assertEquals(OfferParseStep.OK, OfferParseCheck.check(single))
        val cards = OfferCards.segments(single)
        assertEquals(1, cards.size)
        val places = OfferPlacesReader.read(cards.single())
        assertEquals("Los Ríos", places.pickup)
        assertEquals("Avenida John F. Kennedy, Los Prados", places.dropoff)
    }

    @Test
    fun `the Me interesa card is an offer screen for the ride lifecycle`() {
        val s = screen(*single.map { if (it == "Me interesa") button(it) else text(it) }.toTypedArray())
        assertEquals(UberScreenType.OFFER, LifecycleFixtures.classifier.classify(s).type)
    }

    @Test
    fun `the Radar with two cards gives two offers in order`() {
        val texts = listOf("Radar de solicitud de viaje") +
            card("DOP74.17", "A 2 min (0.5 km)", "Los Ríos", "Viaje: 10 min (3.9 km)", "Madre Teresa de Calcuta, Arroyo Manzano") +
            card("DOP70.35", "A 2 min (0.8 km)", "Av. de los Próceres, INTEC Instituto Tecnológico de Santo Domingo", "Viaje: 9 min (3.9 km)", "Avenida Rep. de Colombia, Altos de Arroyo Hondo")
        val cards = OfferCards.segments(texts)
        assertEquals(2, cards.size)
        assertEquals(listOf("74.17", "70.35"), cards.map { OfferPatterns.PRICE.find(it.joinToString(" "))!!.groupValues[1] })
        assertEquals("0.8", OfferPatterns.PICKUP.find(cards[1].joinToString(" "))!!.groupValues[2])
        val second = OfferPlacesReader.read(cards[1])
        assertEquals("Av. de los Próceres, INTEC Instituto Tecnológico de Santo Domingo", second.pickup)
        assertEquals("Avenida Rep. de Colombia, Altos de Arroyo Hondo", second.dropoff)
        // The first card's addresses do not leak into the second, nor the reverse
        assertEquals("Madre Teresa de Calcuta, Arroyo Manzano", OfferPlacesReader.read(cards[0]).dropoff)
    }

    @Test
    fun `a card cut at the top of the Radar is left out`() {
        val texts = listOf("Radar de solicitud de viaje", "A 2 min (0.2 km)", "C. Guayubin, Los Ríos", "Viaje: 14 min (8.0 km)",
            "C. San Antonio 60, Los Alcarrizos", "Me interesa") +
            card("DOP119.41", "A 8 min (4.5 km)", "Av. Monumental, Los Peralejos", "Viaje: 14 min (8.3 km)", "C. Miguel Ángel Monclús 358, Renacimiento")
        val cards = OfferCards.segments(texts)
        assertEquals(1, cards.size)
        assertTrue(cards.single().contains("DOP119.41"))
    }

    @Test
    fun `the exclusive card with Aceptar is still one offer`() {
        val texts = listOf("Moto", "Exclusivo", "DOP72.47", "Identidad digital verificada o Tarjeta de …", "4.91 (2874)",
            "A 4 min (2.0 km)", "C. Arzobispo Portes, Gazcue", "Viaje: 10 min (3.6 km)", "Av. Francisco del Rosario Sanchez 38, Guachupita", "Aceptar")
        val cards = OfferCards.segments(texts)
        assertEquals(1, cards.size)
        assertEquals("C. Arzobispo Portes, Gazcue", OfferPlacesReader.read(cards.single()).pickup)
    }

    @Test
    fun `a price without a card is not an offer`() {
        assertTrue(OfferCards.segments(listOf("DOP 2,069.95", "HOY", "Estás conectado")).isEmpty())
        assertEquals(OfferParseStep.NO_ACCEPT, OfferParseCheck.check(listOf("DOP50.01", "A 5 min (1.9 km)", "Viaje: 6 min (2.2 km)")))
    }

    @Test
    fun `which offers notify`() {
        // Todas: any new offer, even MALO
        assertTrue(OfferAlerts.shouldNotify(true, OfferAlerts.ALL, false, OfferQuality.BAD))
        // Never twice for the same offer
        assertFalse(OfferAlerts.shouldNotify(false, OfferAlerts.ALL, true, OfferQuality.EXCELLENT))
        assertTrue(OfferAlerts.shouldNotify(true, OfferAlerts.GOOD, false, OfferQuality.GOOD))
        assertFalse(OfferAlerts.shouldNotify(true, OfferAlerts.GOOD, false, OfferQuality.BAD))
        assertFalse(OfferAlerts.shouldNotify(true, OfferAlerts.OFF, false, OfferQuality.EXCELLENT))
        // Asistido keeps its promise: BUENO and EXCELENTE always notify
        assertTrue(OfferAlerts.shouldNotify(true, OfferAlerts.OFF, true, OfferQuality.GOOD))
        assertFalse(OfferAlerts.shouldNotify(true, OfferAlerts.OFF, true, OfferQuality.BAD))
        assertEquals(OfferAlerts.ALL, OfferAlerts.fromStorage(null))
        assertEquals(OfferAlerts.GOOD, OfferAlerts.fromStorage("GOOD"))
    }

    // --- Fase 8J: the card's button ---

    @Test
    fun `each card knows its button`() {
        val texts = listOf("Radar de solicitud de viaje") +
            card("DOP74.17", "A 2 min (0.5 km)", "Los Ríos", "Viaje: 10 min (3.9 km)", "Madre Teresa de Calcuta, Arroyo Manzano") +
            card("DOP72.47", "A 4 min (2.0 km)", "C. Arzobispo Portes, Gazcue", "Viaje: 10 min (3.6 km)", "Guachupita", action = "Aceptar")
        val cards = OfferCards.segments(texts)
        assertEquals(listOf(OfferCardAction.INTEREST, OfferCardAction.ACCEPT), cards.map { OfferCardAction.of(it) })
        assertNull(OfferCardAction.of(listOf("DOP50.01", "A 5 min (1.9 km)")))
    }

    @Test
    fun `the button is stored by a fixed key`() {
        assertEquals(OfferCardAction.INTEREST, OfferCardAction.fromStorage("INTEREST"))
        assertEquals(OfferCardAction.ACCEPT, OfferCardAction.fromStorage("ACCEPT"))
        assertNull(OfferCardAction.fromStorage(null))
        assertNull(OfferCardAction.fromStorage("OTRO"))
        assertEquals("Me interesa", OfferCardAction.INTEREST.label)
    }

    @Test
    fun `an offer with Me interesa stays an offer and is counted by button`() {
        fun row(id: Long, action: OfferCardAction?) = com.ubers.observador.data.db.OfferEntity(
            id = id, offerHash = "h$id", detectedAt = id, price = 50.0, pickupKm = 1.0, pickupMin = 3, tripKm = 2.0, tripMin = 6,
            totalKm = 3.0, totalMin = 9, rdpPerKm = 16.0, rdpPerHour = 300.0, fuelCost = 5.0, netProfit = 45.0, score = 60,
            cardAction = action?.storageKey
        )
        val ledger = com.ubers.observador.domain.OfferLedger.of(
            listOf(row(1, OfferCardAction.INTEREST), row(2, OfferCardAction.INTEREST), row(3, OfferCardAction.ACCEPT), row(4, null)),
            emptyList()
        )
        assertEquals(2, ledger.countOf(OfferCardAction.INTEREST))
        assertEquals(1, ledger.countOf(OfferCardAction.ACCEPT))
        // Without a trip, every one of them is "Ofertados", never "Tomados"
        assertEquals(4, ledger.counts.offered)
        assertEquals(0, ledger.counts.taken)
        assertEquals(OfferCardAction.INTEREST, row(1, OfferCardAction.INTEREST).action)
        assertNull(row(4, null).action)
    }

    // 18/10: Uber's chip "+DOP5.25 incluido" (a surcharge already inside the fare) was read as
    // the price, cut the card in two and gave RD$5.25. Texts copied from those screenshots.
    private val withSurcharge = listOf(
        "Moto", "Exclusivo", "DOP92.60", "Documento de identidad oficial verifica…", "4.85 (423)", "+DOP5.25 incluido",
        "A 2 min (0.4 km)", "Av. Coronel Juan María Lora Fernández, Los Ríos", "Viaje: 4 min (1.4 km)",
        "Av. Jardines de Fontainebleau 209, Los Jardínes", "Aceptar"
    )

    @Test
    fun `the included surcharge chip is not the fare`() {
        val cards = OfferCards.cards(withSurcharge)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(92.60, OfferPatterns.fareValue(card.texts.joinToString(" "))!!, 0.0001)
        val product = OfferProductReader.read(card.header)
        assertEquals("Moto", product.product)
        assertTrue(product.exclusive)
        val places = OfferPlacesReader.read(card.texts)
        assertEquals("Av. Coronel Juan María Lora Fernández, Los Ríos", places.pickup)
        assertEquals("Av. Jardines de Fontainebleau 209, Los Jardínes", places.dropoff)
        assertEquals("0.4", OfferPatterns.PICKUP.find(card.texts.joinToString(" "))!!.groupValues[2])
    }

    @Test
    fun `the Radar with surcharge chips keeps each card's own fare`() {
        val texts = listOf("Radar de solicitud de viaje",
            "Moto", "DOP48.31", "Identidad digital verificada o Tarjeta de …", "4.91 (93)", "+DOP5.25 incluido",
            "A 6 min (2.4 km)", "Av. Isabel Aguiar, Zona Industrial de Herrera, Santo Domingo Oeste", "Viaje: 4 min (1.3 km)",
            "Av. México 200, Buenos Aires de Herrera, Santo Domingo Oeste", "Me interesa",
            "Moto", "Exclusivo", "DOP47.88", "Documento de identidad oficial verifica…", "4.88 (646)", "+DOP5.25 incluido",
            "A 3 min (1.3 km)", "C. Calas, Los Jardínes", "Viaje: 3 min (0.7 km)", "C. 1ra 13, Los Jardínes", "Me interesa")
        val cards = OfferCards.cards(texts)
        assertEquals(listOf(48.31, 47.88), cards.map { OfferPatterns.fareValue(it.texts.joinToString(" ")) })
        assertEquals("C. Calas, Los Jardínes", OfferPlacesReader.read(cards[1].texts).pickup)
        assertTrue(OfferProductReader.read(cards[1].header).exclusive)
        assertFalse(OfferProductReader.read(cards[0].header).exclusive)
    }

    @Test
    fun `only a price written as an extra is skipped`() {
        assertNull(OfferPatterns.fare("+DOP5.25 incluido"))
        assertNull(OfferPatterns.fare("+ DOP5.25"))
        assertNull(OfferPatterns.fare("DOP5.25 incluido"))
        assertEquals(47.88, OfferPatterns.fareValue("+DOP5.25 incluido DOP47.88")!!, 0.0001)
        assertEquals(1978.38, OfferPatterns.fareValue("RD$1,978.38")!!, 0.0001)
        assertEquals(136.06, OfferPatterns.fareValue("DOP 136.06 · 4.9")!!, 0.0001)
        // A card with only the chip and no fare is not an offer: nothing is invented
        assertEquals(OfferParseStep.NO_PRICE, OfferParseCheck.check(listOf("+DOP5.25 incluido", "A 2 min (0.4 km)", "Viaje: 4 min (1.4 km)", "Aceptar")))
    }
}
