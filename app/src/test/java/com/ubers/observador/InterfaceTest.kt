package com.ubers.observador

import com.ubers.observador.domain.OverlayField
import com.ubers.observador.domain.OverlayLayout
import com.ubers.observador.domain.OverlayLayoutCodec
import com.ubers.observador.domain.OverlayPosition
import com.ubers.observador.domain.OverlayTextSize
import com.ubers.observador.domain.SettingsGroup
import com.ubers.observador.domain.SettingsIndex
import com.ubers.observador.domain.backup.DecodedSettings
import com.ubers.observador.domain.backup.RestoreRules
import com.ubers.observador.domain.models.OfferAnalysis
import com.ubers.observador.domain.models.UberOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.50.0: Ajustes in groups with search, and the configurable card over Uber. */
class InterfaceTest {

    @Test fun everyGroupHasSettingsAndNoTitleRepeats() {
        SettingsGroup.entries.forEach { g -> assertTrue(g.label, SettingsIndex.ENTRIES.any { it.group == g }) }
        val titles = SettingsIndex.ENTRIES.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test fun searchIgnoresAccentsAndCaseAndFindsByOtherWords() {
        assertEquals("Precio del combustible", SettingsIndex.search("gasolina").first().title)
        assertEquals(SettingsGroup.PROFIT, SettingsIndex.search("COMBUSTÍBLE").first().group)
        assertEquals("Inicio de la jornada", SettingsIndex.search("jornada inicio").first().title)
        assertTrue(SettingsIndex.search("tarjeta").all { it.group == SettingsGroup.READING })
        // Titles first, then keyword matches
        val meta = SettingsIndex.search("meta")
        assertTrue(meta.first().title.startsWith("Meta"))
        assertTrue(SettingsIndex.search("").isEmpty())
        assertTrue(SettingsIndex.search("zzz").isEmpty())
        // Word starts, not anywhere inside a word
        assertTrue(SettingsIndex.search("ina").none { it.title == "Precio del combustible" })
    }

    @Test fun theDefaultCardIsTheOneBefore150() {
        assertEquals(
            listOf(OverlayField.PRICE, OverlayField.DISTANCE_TIME, OverlayField.RDP_KM, OverlayField.ADDRESSES),
            OverlayLayout().fields
        )
        assertEquals(OverlayPosition.TOP, OverlayLayout().position)
        assertEquals(OverlayTextSize.NORMAL, OverlayLayout().textSize)
        // Nothing stored yet: the same card
        assertEquals(OverlayLayout(), OverlayLayoutCodec.layout(null, null, null))
    }

    @Test fun twoToFourFiguresInTheChosenOrder() {
        var l = OverlayLayout()
        assertEquals(l, l.toggle(OverlayField.RDP_HOUR))
        l = l.toggle(OverlayField.ADDRESSES).toggle(OverlayField.RDP_HOUR)
        assertEquals(OverlayField.RDP_HOUR, l.fields.last())
        l = l.toggle(OverlayField.RDP_KM).toggle(OverlayField.DISTANCE_TIME)
        assertEquals(listOf(OverlayField.PRICE, OverlayField.RDP_HOUR), l.fields)
        assertEquals(l, l.toggle(OverlayField.PRICE))
        l = l.move(OverlayField.RDP_HOUR, -1)
        assertEquals(listOf(OverlayField.RDP_HOUR, OverlayField.PRICE), l.fields)
        assertEquals(l, l.move(OverlayField.RDP_HOUR, -1))
        assertEquals(l, l.move(OverlayField.PRICE, 1))
    }

    @Test fun storedLayoutIsStrict() {
        val fields = listOf(OverlayField.NET, OverlayField.PICKUP, OverlayField.PRICE)
        assertEquals(fields, OverlayLayoutCodec.decodeFields(OverlayLayoutCodec.encodeFields(fields)))
        assertNull(OverlayLayoutCodec.decodeFields("PRICE"))
        assertNull(OverlayLayoutCodec.decodeFields("PRICE,PRICE"))
        assertNull(OverlayLayoutCodec.decodeFields("PRICE,NADA"))
        assertNull(OverlayLayoutCodec.decodeFields("PRICE,NET,PICKUP,RDP_KM,RDP_HOUR"))
        assertNull(OverlayLayoutCodec.decodePosition("LEFT"))
        // A damaged value falls back to the default card, never to an empty one
        assertEquals(OverlayLayout.DEFAULT_FIELDS, OverlayLayoutCodec.layout("PRICE", "LEFT", "HUGE").fields)
        val v = RestoreRules.validateSettings(
            DecodedSettings(mapOf("overlay_fields" to "PRICE,NET", "overlay_position" to "BOTTOM", "overlay_text_size" to "HUGE"), emptyList())
        )
        assertEquals("PRICE,NET", v.values["overlay_fields"])
        assertEquals("BOTTOM", v.values["overlay_position"])
        assertEquals(listOf("overlay_text_size"), v.defaulted)
    }

    @Test fun figuresSayWhatTheyAreAndNetIsAnEstimate() {
        val offer = UberOffer(price = 150.0, pickupKm = 1.2, pickupMin = 4, tripKm = 5.0, tripMin = 14, pickupAddress = "Gazcue")
        val a = OfferAnalysis(offer, 6.2, 18, 24.19, 500.0, 30.0, 120.0, 70)
        assertEquals(listOf("RD$150.00"), OverlayField.PRICE.text(a))
        assertEquals(listOf("RD$500/h"), OverlayField.RDP_HOUR.text(a))
        assertTrue(OverlayField.NET.text(a).single().startsWith("≈"))
        assertEquals(listOf("Recogida 1.2 km · 4 min"), OverlayField.PICKUP.text(a))
        // Without a drop-off address only the pickup line
        assertEquals(listOf("▲ Gazcue"), OverlayField.ADDRESSES.text(a))
    }
}
