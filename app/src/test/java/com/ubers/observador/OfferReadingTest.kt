package com.ubers.observador

import com.ubers.observador.domain.lifecycle.OfferParseCheck
import com.ubers.observador.domain.lifecycle.OfferParseStep
import com.ubers.observador.domain.lifecycle.OfferPlacesReader
import com.ubers.observador.domain.lifecycle.RiderNameReader
import com.ubers.observador.domain.lifecycle.ScreenDump
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.models.UberOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 8F: addresses of the offer card, the rider's name, and why a card was not read. */
class OfferReadingTest {

    private val card = listOf(
        "UberMoto", "RD$136.06", "4.92", "A 7 min (2.3 km)", "Av. Charles de Gaulle, Santo Domingo Este",
        "Viaje: 18 min (8.5 km)", "Aeropuerto Internacional Las Américas", "Aceptar"
    )

    @Test fun addressesFollowTheirTimeAndDistance() {
        val p = OfferPlacesReader.read(card)
        assertEquals("Av. Charles de Gaulle, Santo Domingo Este", p.pickup)
        assertEquals("Aeropuerto Internacional Las Américas", p.dropoff)
        // The addresses never change the offer hash (the same offer stays one offer)
        val offer = UberOffer(136.06, 2.3, 7, 8.5, 18)
        assertEquals(offer.offerHash(), offer.copy(pickupAddress = p.pickup, dropoffAddress = p.dropoff).offerHash())
    }

    @Test fun addressInTheSameNodeAsItsTime() {
        val p = OfferPlacesReader.read(listOf("RD$90.00", "A 3 min (0.8 km) · Calle El Sol 12", "10 min (4.0 km) · Plaza Lama", "Aceptar"))
        assertEquals("Calle El Sol 12", p.pickup)
        assertEquals("Plaza Lama", p.dropoff)
    }

    @Test fun labelsRatingsAndNumbersAreNotPlaces() {
        listOf("4.92", "4,95 ★", "Aceptar", "UberMoto", "Efectivo", "12", "RD$5.00", "x").forEach { assertFalse(it, OfferPlacesReader.isPlace(it)) }
        assertTrue(OfferPlacesReader.isPlace("Los Alcarrizos"))
        val p = OfferPlacesReader.read(listOf("RD$90.00", "A 3 min (0.8 km)", "4.9", "Efectivo", "Villa Mella", "10 min (4.0 km)", "Aceptar"))
        assertEquals("Villa Mella", p.pickup)
        assertNull(p.dropoff) // nothing after the trip line but the button
    }

    @Test fun noPickupLineNoPlaces() {
        assertTrue(OfferPlacesReader.read(listOf("Calle 1", "Aceptar")).isEmpty)
    }

    @Test fun longAddressesAreCut() {
        val long = "Calle " + "Muy Larga ".repeat(30)
        val p = OfferPlacesReader.read(listOf("RD$90.00", "A 3 min (0.8 km)", long, "10 min (4.0 km)", "Aceptar"))
        assertEquals(OfferPlacesReader.MAX_LENGTH, p.pickup!!.length)
    }

    @Test fun riderNameFromThePickupScreen() {
        assertEquals("stefany", RiderNameReader.read(listOf("Mapa", "Llegar por stefany")))
        assertEquals("José Miguel", RiderNameReader.read(listOf("Recoger a José Miguel.")))
        assertEquals("Ana", RiderNameReader.read(listOf("LLEGAR  POR Ana")))
        assertNull(RiderNameReader.read(listOf("Llegar por 3 min")))
        assertNull(RiderNameReader.read(listOf("Llegar por")))
        assertNull(RiderNameReader.read(listOf("Llegaste al punto de recogida")))
        assertTrue("llegar por " in RiderNameReader.VALIDATED)
    }

    @Test fun parseCheckSaysWhereItStopped() {
        assertEquals(OfferParseStep.OK, OfferParseCheck.check(card))
        assertEquals(OfferParseStep.NO_ACCEPT, OfferParseCheck.check(card.dropLast(1)))
        assertEquals(OfferParseStep.NO_PRICE, OfferParseCheck.check(listOf("136.06", "A 7 min (2.3 km)", "18 min (8.5 km)", "Aceptar")))
        assertEquals(OfferParseStep.NO_PICKUP, OfferParseCheck.check(listOf("RD$136.06", "7 min (2.3 km) de distancia", "Aceptar")))
        assertEquals(OfferParseStep.NO_TRIP, OfferParseCheck.check(listOf("RD$136.06", "A 7 min (2.3 km)", "Aceptar")))
    }

    @Test fun fullDumpListsTextsWithIds() {
        val snap = ScreenSnapshot(
            0, "com.ubercab.driver",
            listOf(ScreenNode(text = "Llegar por stefany", viewId = "com.ubercab.driver:id/title"), ScreenNode(), ScreenNode(contentDescription = "Menú", clickable = true))
        )
        val dump = ScreenDump.full(snap, "prueba")
        assertTrue(dump.contains("#0 [title]: Llegar por stefany"))
        assertTrue(dump.contains("#2 (toca): | Menú") || dump.contains("#2 (toca): Menú") || dump.contains("#2 (toca):  | Menú"))
        assertFalse(dump.contains("#1"))
    }
}
