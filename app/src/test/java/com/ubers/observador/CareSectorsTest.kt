package com.ubers.observador

import com.ubers.observador.domain.lifecycle.OfferCards
import com.ubers.observador.domain.lifecycle.OfferPlacesReader
import com.ubers.observador.domain.sectors.CareSector
import com.ubers.observador.domain.sectors.OfferPlaceKind
import com.ubers.observador.domain.sectors.SectorLevel
import com.ubers.observador.domain.sectors.SectorMatcher
import com.ubers.observador.domain.sectors.SectorWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.34.0: the pickup address of real cards (11/11/2026, «A 6 min (0.9 km) de distancia» in one text)
 * and the care sectors found in the 20 complete offers of those screenshots.
 */
class CareSectorsTest {

    private data class Offer(val price: String, val pickup: String, val dropoff: String, val expected: Set<String>)

    private fun card(o: Offer, exclusive: Boolean = false, surcharge: String? = null): List<String> =
        listOfNotNull(
            "Moto", if (exclusive) "Exclusivo" else null, o.price, "DOP17.72/km est.",
            "Identidad digital verificada o Tarjeta de …", "4.91 (1873)", surcharge,
            "A 6 min (0.9 km) de distancia", o.pickup, "Viaje: 22 min (10.2 km)", o.dropoff,
            if (exclusive) "Aceptar" else "Me interesa"
        )

    private val mine = listOf(
        "Engombe", "Los Alcarrizos", "Cristo Rey", "Villa Duarte", "Hato Nuevo", "Santa Cruz",
        "Santo Domingo Norte", "Santo Domingo Este"
    ).map { CareSector(it) }

    private val offers = listOf(
        Offer("DOP224.10", "Av. Buenaventura Freites, Los Jardínes", "C. Bernabe Boza 2, Santa Cruz, Santo Domingo Norte", setOf("Santa Cruz", "Santo Domingo Norte")),
        Offer("DOP200.67", "Zona Industrial de Herrera, Santo Domingo Oeste", "Calle Antena, Hato Nuevo", setOf("Hato Nuevo")),
        Offer("DOP150.45", "C. Primera, Alameda, Santo Domingo Oeste", "Cta. del Sol 14, Hato Nuevo", setOf("Hato Nuevo")),
        Offer("DOP150.87", "Calle Luis Amiama Tió, Viejo Arroyo Hondo", "Av. Hermanas Mirabal 28, Guaricano, Santo Domingo Norte", setOf("Santo Domingo Norte")),
        Offer("DOP167.17", "Av. José Núñez de Cáceres, Downtown Center", "C. Ramón Cáceres 285, Cristo Rey", setOf("Cristo Rey")),
        Offer("DOP260.44", "Av. George Washington, Ciudad Universitaria", "Calle Dr. Octavio Mejia Ricart 377, Alma Rosa, Santo Domingo Este", setOf("Santo Domingo Este")),
        Offer("DOP211.64", "P.º del Yaque, Los Ríos", "C. Circunvalación, Guaricano, Santo Domingo Norte", setOf("Santo Domingo Norte")),
        Offer("DOP294.67", "Av Simón Bolívar, La Esperilla", "C. Engombe 39, Engombe, Santo Domingo Oeste", setOf("Engombe")),
        Offer("DOP278.60", "C. Luis Padilla, Los Prados", "Paraiso 16, Los Alcarrizos", setOf("Los Alcarrizos")),
        Offer("DOP259.35", "Santiago, Gazcue", "Av. Malecón 6, Villa Duarte, Santo Domingo Este", setOf("Villa Duarte", "Santo Domingo Este")),
        Offer("DOP140.81", "C. Crisantemos, INTEC Instituto Tecnológico de Santo Domingo", "C. Pedro Abreu 59, Bayona, Santo Domingo Oeste", emptySet()),
        Offer("DOP100.56", "C. Crisantemos, INTEC Instituto Tecnológico de Santo Domingo", "Calle B, Alameda, Santo Domingo Oeste", emptySet()),
        Offer("DOP42.63", "C. Central, Los Jardínes", "Avenida John F. Kennedy, Estación Pedro Francisco Bonó, L2", emptySet()),
        Offer("DOP170.13", "UNPHU Universidad Nacional Pedro Henríquez Ureña", "C. Esteban Suazo 84, Cacique", emptySet()),
        Offer("DOP170.76", "Av. Gregorio Luperón, Los Restauradores", "C. Rafael Abreu 48, San Miguel, Santo Domingo Oeste", emptySet()),
        Offer("DOP92.93", "C. de los Reyes, Altos de Arroyo Hondo", "Alameda, Santo Domingo Oeste", emptySet()),
        Offer("DOP124.75", "Carr. la Isabela, Nuevo Arroyo Hondo", "Calle San Luis 6, Altos de Arroyo Hondo", emptySet()),
        Offer("DOP171.48", "Av. Antonio Guzmán Fernández, Mirador Sur", "Buenos Aires (Independencia)", emptySet()),
        Offer("DOP141.94", "Avenida Rep. de Colombia, Los Ríos", "C. Emma Balaguer 12, Palma Real", emptySet()),
        Offer("DOP56.53", "Av. Abraham Lincoln, UNICDA Universidad Domínico Americano", "Av Simón Bolívar 902, Universidad Católica Santo Domingo", emptySet())
    )

    @Test fun pickupIsTheAddressNotDeDistancia() {
        offers.forEach { o ->
            val places = OfferPlacesReader.read(card(o))
            assertEquals(o.price, o.pickup, places.pickup)
            assertEquals(o.price, o.dropoff, places.dropoff)
        }
    }

    @Test fun exclusiveCardWithSurchargeReadsTheSame() {
        val o = offers[7]
        val places = OfferPlacesReader.read(card(o, exclusive = true, surcharge = "+DOP50.00 incluido"))
        assertEquals(o.pickup, places.pickup)
        assertEquals(o.dropoff, places.dropoff)
    }

    @Test fun radarWithTwoCardsKeepsEachAddress() {
        val texts = listOf("Radar de solicitud de viaje") + card(offers[12]) + card(offers[2])
        val cards = OfferCards.segments(texts)
        assertEquals(2, cards.size)
        assertEquals(offers[12].pickup, OfferPlacesReader.read(cards[0]).pickup)
        assertEquals(offers[2].dropoff, OfferPlacesReader.read(cards[1]).dropoff)
    }

    @Test fun deDistanciaAloneIsNeverAPlace() {
        assertFalse(OfferPlacesReader.isPlace("de distancia"))
        assertFalse(OfferPlacesReader.isPlace("De distancia"))
    }

    @Test fun theTwentyRealOffersMatchExactlyTheExpectedSectors() {
        offers.forEach { o ->
            val m = SectorMatcher.match(o.pickup, o.dropoff, mine)
            assertEquals(o.price, o.expected, m.hits.map { it.sector.name }.toSet())
            // All of them come from the destination on these cards
            assertTrue(o.price, m.hits.all { it.place == OfferPlaceKind.DROPOFF })
        }
        assertEquals(10, offers.count { SectorMatcher.match(it.pickup, it.dropoff, mine).hits.isNotEmpty() })
    }

    @Test fun esteIsNotOesteAndSantoDomingoAloneIsNoMunicipality() {
        assertFalse(SectorMatcher.matches("C. Pedro Abreu 59, Bayona, Santo Domingo Oeste", "Santo Domingo Este"))
        assertTrue(SectorMatcher.matches("Alma Rosa, Santo Domingo Este", "Santo Domingo Este"))
        assertFalse(SectorMatcher.matches("Av Simón Bolívar 902, Universidad Católica Santo Domingo", "Santo Domingo Norte"))
    }

    @Test fun accentsCaseAndSpacesDoNotMatter() {
        assertTrue(SectorMatcher.matches("C. Central, Los Jardínes", "los jardines"))
        assertTrue(SectorMatcher.matches("Paraiso 16, Los Alcarrizos", "  LOS   alcarrizos "))
        assertTrue(SectorMatcher.matches("Paraiso 16, Los Alcarrizos", "Alcarrizos"))
    }

    @Test fun bracketsAreOneMorePart() {
        assertTrue(SectorMatcher.matches("Buenos Aires (Independencia)", "Independencia"))
        assertEquals(listOf("Buenos Aires", "Independencia"), SectorMatcher.displayParts("Buenos Aires (Independencia)"))
    }

    @Test fun theStreetIsSkippedButASectorFirstIsNot() {
        assertFalse(SectorMatcher.matches("Av. Abraham Lincoln, UNICDA Universidad Domínico Americano", "Abraham Lincoln"))
        assertTrue(SectorMatcher.matches("Alameda, Santo Domingo Oeste", "Alameda"))
        assertEquals(listOf("Engombe", "Santo Domingo Oeste"), SectorMatcher.displayParts("C. Engombe 39, Engombe, Santo Domingo Oeste"))
        // Known limit: a street without prefix or number is read as a sector
        assertTrue(SectorMatcher.matches("Santiago, Gazcue", "Santiago"))
    }

    @Test fun whereLimitsTheAddressChecked() {
        val onlyPickup = listOf(CareSector("Hato Nuevo", SectorWhere.PICKUP))
        assertTrue(SectorMatcher.match("Los Ríos", "Calle Antena, Hato Nuevo", onlyPickup).isEmpty)
        val onlyDrop = listOf(CareSector("Hato Nuevo", SectorWhere.DROPOFF))
        assertEquals(OfferPlaceKind.DROPOFF, SectorMatcher.match("Los Ríos", "Calle Antena, Hato Nuevo", onlyDrop).hits.single().place)
        assertTrue(SectorMatcher.match("Hato Nuevo", "Los Ríos", onlyDrop).isEmpty)
    }

    @Test fun loudestLevelWins() {
        val list = listOf(CareSector("Villa Duarte", level = SectorLevel.WARN), CareSector("Santo Domingo Este", level = SectorLevel.AVOID))
        val m = SectorMatcher.match(null, "Av. Malecón 6, Villa Duarte, Santo Domingo Este", list)
        assertEquals(SectorLevel.AVOID, m.level)
        assertNull(SectorMatcher.match(null, null, list).level)
        assertTrue(SectorMatcher.match("x", "y", emptyList()).isEmpty)
    }

    @Test fun suggestionsComeFromPastAddressesMostFrequentFirst() {
        val past = offers.flatMap { listOf(it.pickup, it.dropoff) }
        val s = SectorMatcher.suggestions(past, listOf(CareSector("Hato Nuevo")))
        assertEquals("Santo Domingo Oeste", s.first())
        assertFalse(s.any { SectorMatcher.words(it) == SectorMatcher.words("Hato Nuevo") })
        assertFalse(s.any { it.startsWith("C. ") || it.startsWith("Av") })
        assertTrue(s.size <= 12)
    }
}

/** 1.34.0: Uber's «DOP20.19/km est.» line under the fare (cards of 11/11) is not the price. */
class OfferRateLineTest {

    private val card = listOf(
        "Moto", "DOP224.10", "DOP20.19/km est.", "Identidad digital verificada o Tarjeta de …", "4.91 (1873)",
        "A 6 min (0.9 km) de distancia", "Av. Buenaventura Freites, Los Jardínes", "Viaje: 22 min (10.2 km)",
        "C. Bernabe Boza 2, Santa Cruz, Santo Domingo Norte", "Me interesa"
    )

    @Test fun theRatePerKmIsNotAFare() {
        assertNull(com.ubers.observador.domain.lifecycle.OfferPatterns.fare("DOP20.19/km est."))
        assertNull(com.ubers.observador.domain.lifecycle.OfferPatterns.fare("DOP 20.19 / km est."))
        assertEquals(224.10, com.ubers.observador.domain.lifecycle.OfferPatterns.fareValue(card.joinToString(" "))!!, 0.001)
    }

    @Test fun aCardWithTheRateLineIsOneCardWithItsRealPrice() {
        val cards = OfferCards.cards(card)
        assertEquals(1, cards.size)
        assertEquals("DOP224.10", cards.single().texts.first())
        assertEquals(listOf("Moto"), cards.single().header)
    }

    @Test fun theRadarWithRateLinesStillGivesTwoCards() {
        val second = listOf(
            "Moto", "DOP150.45", "DOP17.70/km est.", "4.86 (590)", "A 6 min (1.6 km) de distancia",
            "C. Primera, Alameda, Santo Domingo Oeste", "Viaje: 11 min (6.9 km)", "Cta. del Sol 14, Hato Nuevo", "Me interesa"
        )
        val cards = OfferCards.cards(listOf("Radar de solicitud de viaje") + card + second)
        assertEquals(listOf("DOP224.10", "DOP150.45"), cards.map { it.texts.first() })
        assertEquals("C. Primera, Alameda, Santo Domingo Oeste", OfferPlacesReader.read(cards[1].texts).pickup)
    }

    @Test fun surchargeAndRateTogether() {
        val texts = listOf(
            "Moto", "Exclusivo", "DOP140.81", "DOP19.83/km est.", "4.83 (859)", "+DOP7.25 incluido",
            "A 4 min (0.9 km) de distancia", "C. Crisantemos, INTEC Instituto Tecnológico de Santo Domingo",
            "Viaje: 12 min (6.2 km)", "C. Pedro Abreu 59, Bayona, Santo Domingo Oeste", "Aceptar"
        )
        val cards = OfferCards.cards(texts)
        assertEquals(1, cards.size)
        assertEquals(140.81, com.ubers.observador.domain.lifecycle.OfferPatterns.fareValue(cards.single().texts.joinToString(" "))!!, 0.001)
    }
}

/** 1.35.0: the stored list, the band text and the restore check. */
class CareSectorSettingsTest {

    private val list = listOf(
        CareSector("Engombe", SectorWhere.BOTH, SectorLevel.AVOID),
        CareSector("Hato Nuevo", SectorWhere.DROPOFF, SectorLevel.WARN)
    )

    @Test fun codecRoundTrip() {
        val text = com.ubers.observador.domain.sectors.CareSectorCodec.encode(list)
        assertEquals(list, com.ubers.observador.domain.sectors.CareSectorCodec.decode(text))
        assertEquals(emptyList<CareSector>(), com.ubers.observador.domain.sectors.CareSectorCodec.decode(null))
        assertEquals(emptyList<CareSector>(), com.ubers.observador.domain.sectors.CareSectorCodec.decode(""))
    }

    @Test fun damagedListsAreRejectedWhole() {
        val codec = com.ubers.observador.domain.sectors.CareSectorCodec
        assertNull(codec.decode("no es json"))
        assertNull(codec.decode("""[{"n":"Engombe","w":"LEJOS","l":"AVOID"}]"""))
        assertNull(codec.decode("""[{"n":"","w":"BOTH","l":"WARN"}]"""))
        assertNull(codec.decode("""[{"n":"${"x".repeat(41)}","w":"BOTH","l":"WARN"}]"""))
        assertNull(codec.decode("[" + (1..51).joinToString(",") { """{"n":"S$it","w":"BOTH","l":"WARN"}""" } + "]"))
    }

    @Test fun upsertReplacesTheSameSectorIgnoringAccentsAndCase() {
        val codec = com.ubers.observador.domain.sectors.CareSectorCodec
        val after = codec.upsert(list, CareSector("HATO  nuevo", SectorWhere.BOTH, SectorLevel.AVOID))
        assertEquals(2, after.size)
        assertEquals(SectorLevel.AVOID, after[1].level)
        assertEquals(3, codec.upsert(list, CareSector("Cristo Rey")).size)
        assertEquals("Buenos Aires Independencia", codec.cleanName(" Buenos Aires, (Independencia) "))
    }

    @Test fun bandTexts() {
        val warn = com.ubers.observador.domain.sectors.SectorBanner.of(
            SectorMatcher.match("Los Ríos", "Calle Antena, Hato Nuevo", list)
        )!!
        assertEquals("⚠ Destino: Hato Nuevo", warn.text)
        assertFalse(warn.avoid)
        val avoid = com.ubers.observador.domain.sectors.SectorBanner.of(
            SectorMatcher.match("C. Engombe 39, Engombe", "Calle Antena, Hato Nuevo", list), card = 2
        )!!
        assertEquals("⛔ Evitar · Tarjeta 2 · Recogida: Engombe · Destino: Hato Nuevo", avoid.text)
        assertTrue(avoid.avoid)
        assertNull(com.ubers.observador.domain.sectors.SectorBanner.of(SectorMatcher.match("Los Ríos", "Gazcue", list)))
    }

    @Test fun closeLevelIsTreatedAsAvoidInTheBand() {
        val close = listOf(CareSector("Engombe", level = SectorLevel.CLOSE))
        val b = com.ubers.observador.domain.sectors.SectorBanner.of(SectorMatcher.match(null, "Engombe, Santo Domingo Oeste", close))!!
        assertTrue(b.avoid)
        assertTrue(b.text.startsWith("⛔ Evitar"))
    }

    @Test fun restoreKeepsAValidListAndDropsADamagedOne() {
        val good = com.ubers.observador.domain.backup.RestoreRules.validateSettings(
            com.ubers.observador.domain.backup.DecodedSettings(
                mapOf("care_sectors" to com.ubers.observador.domain.sectors.CareSectorCodec.encode(list), "care_sectors_enabled" to true),
                emptyList()
            )
        )
        assertTrue(good.values.containsKey("care_sectors"))
        assertEquals(true, good.values["care_sectors_enabled"])
        val bad = com.ubers.observador.domain.backup.RestoreRules.validateSettings(
            com.ubers.observador.domain.backup.DecodedSettings(mapOf("care_sectors" to "[{]"), emptyList())
        )
        assertEquals(listOf("care_sectors"), bad.defaulted)
        assertTrue(com.ubers.observador.domain.backup.RestoreRules.defaultedText(bad.defaulted)!!.contains("sectores de cuidado"))
    }
}
