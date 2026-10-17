package com.ubers.observador

import com.ubers.observador.LifecycleFixtures.button
import com.ubers.observador.LifecycleFixtures.screen
import com.ubers.observador.LifecycleFixtures.text
import com.ubers.observador.domain.OfferAlerts
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.lifecycle.OfferCards
import com.ubers.observador.domain.lifecycle.OfferParseCheck
import com.ubers.observador.domain.lifecycle.OfferParseStep
import com.ubers.observador.domain.lifecycle.OfferPatterns
import com.ubers.observador.domain.lifecycle.OfferPlacesReader
import com.ubers.observador.domain.lifecycle.UberScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
