package com.ubers.observador

import com.ubers.observador.domain.lifecycle.OfferCards
import com.ubers.observador.domain.lifecycle.OfferPlacesReader
import com.ubers.observador.domain.sectors.CareSector
import com.ubers.observador.domain.sectors.CloseOutcome
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

/** 1.36.0: sectors applied to stored offers (list chip, Análisis card, CSV column). */
class SectorStatsTest {

    private val sectors = listOf(CareSector("Hato Nuevo"), CareSector("Santo Domingo Norte"), CareSector("Engombe"))

    private fun offer(id: Long, from: String?, to: String?, at: Long = 1_000L * id) = com.ubers.observador.data.db.OfferEntity(
        id = id, offerHash = "TEST-$id", detectedAt = at, price = 150.0, pickupKm = 1.0, pickupMin = 5, tripKm = 6.0, tripMin = 12,
        totalKm = 7.0, totalMin = 17, rdpPerKm = 21.4, rdpPerHour = 529.0, fuelCost = 20.0, netProfit = 130.0, score = 60,
        pickupAddress = from, dropoffAddress = to
    )

    private val offers = listOf(
        offer(1, "Zona Industrial de Herrera, Santo Domingo Oeste", "Calle Antena, Hato Nuevo"),
        offer(2, "C. Primera, Alameda, Santo Domingo Oeste", "Cta. del Sol 14, Hato Nuevo"),
        offer(3, "Los Ríos", "C. Circunvalación, Guaricano, Santo Domingo Norte"),
        offer(4, "C. Engombe 39, Engombe", "Gazcue"),
        offer(5, "C. Central, Los Jardínes", "Avenida John F. Kennedy, Estación Pedro Francisco Bonó, L2"),
        offer(6, null, null)
    )

    @Test fun countsPerSectorAndPlace() {
        val c = com.ubers.observador.domain.sectors.SectorStats.counts(offers, sectors)
        assertEquals(listOf("Hato Nuevo", "Engombe", "Santo Domingo Norte"), c.map { it.name })
        assertEquals(2, c[0].dropoff)
        assertEquals(1, c[1].pickup)
        assertEquals(0, c[1].dropoff)
        assertTrue(com.ubers.observador.domain.sectors.SectorStats.counts(offers, emptyList()).isEmpty())
    }

    @Test fun keepFlaggedDropsEmptyDays() {
        val day1 = com.ubers.observador.domain.DayGroup(java.time.LocalDate.of(2026, 11, 10), 2, 20.0, 60.0, listOf(offers[0], offers[4]))
        val day2 = com.ubers.observador.domain.DayGroup(java.time.LocalDate.of(2026, 11, 11), 1, 20.0, 60.0, listOf(offers[4]))
        val kept = com.ubers.observador.domain.sectors.SectorStats.keepFlagged(listOf(day1, day2), sectors)
        assertEquals(1, kept.size)
        assertEquals(listOf(1L), kept.single().offers.map { it.id })
        assertEquals(2, kept.single().count) // the header still describes the whole day
        assertEquals(1, com.ubers.observador.domain.sectors.SectorStats.flaggedCount(listOf(day1, day2), sectors))
        assertEquals(0, com.ubers.observador.domain.sectors.SectorStats.flaggedCount(listOf(day1, day2), emptyList()))
    }

    @Test fun csvValueHasNoCommas() {
        val v = com.ubers.observador.domain.sectors.SectorStats.csvValue(
            offer(9, "Los Ríos", "C. Bernabe Boza 2, Santa Cruz, Santo Domingo Norte"),
            sectors + CareSector("Santa Cruz")
        )
        assertEquals("Destino: Santo Domingo Norte / Santa Cruz", v)
        assertFalse(v.contains(","))
        assertEquals("", com.ubers.observador.domain.sectors.SectorStats.csvValue(offers[4], sectors))
    }
}

/** 1.37.0: Uber's X found by the card's price, the closing rules and the log. */
class SectorCloseTest {

    private fun n(text: String? = null, desc: String? = null, clickable: Boolean = false, id: String? = null) =
        com.ubers.observador.domain.lifecycle.ScreenNode(text = text, contentDescription = desc, clickable = clickable, viewId = id)

    private fun card(price: String, x: com.ubers.observador.domain.lifecycle.ScreenNode?, action: String = "Me interesa") = listOfNotNull(
        n("Moto"), x, n(price), n("DOP20.19/km est."), n("4.91 (1873)"), n("A 6 min (0.9 km) de distancia"),
        n("Av. Buenaventura Freites, Los Jardínes"), n("Viaje: 22 min (10.2 km)"), n("C. Bernabe Boza 2, Santa Cruz"),
        n(desc = action, clickable = true), n(action)
    )

    private fun snap(nodes: List<com.ubers.observador.domain.lifecycle.ScreenNode>) =
        com.ubers.observador.domain.lifecycle.ScreenSnapshot(0L, "com.ubercab.driver", nodes)

    private val x = n(desc = "Cerrar", clickable = true)
    private val target = com.ubers.observador.domain.sectors.CloseTarget

    @Test fun findsTheXOfTheOnlyCard() {
        val nodes = card("DOP224.10", x)
        val r = target.find(snap(nodes), 224.10)
        assertTrue(r is com.ubers.observador.domain.sectors.CloseTarget.Result.Found)
        assertEquals(1, (r as com.ubers.observador.domain.sectors.CloseTarget.Result.Found).index)
    }

    @Test fun inTheRadarItPicksTheXOfTheCardWithThatPrice() {
        val first = card("DOP42.63", n(desc = "Cerrar", clickable = true))
        val second = card("DOP150.45", n(desc = "Cerrar", clickable = true))
        val nodes = listOf(n("Radar de solicitud de viaje")) + first + second
        val r = target.find(snap(nodes), 150.45) as com.ubers.observador.domain.sectors.CloseTarget.Result.Found
        assertEquals(1 + first.size + 1, r.index)
        val r1 = target.find(snap(nodes), 42.63) as com.ubers.observador.domain.sectors.CloseTarget.Result.Found
        assertEquals(2, r1.index)
    }

    @Test fun theRepeatedButtonTextCountsOnce() {
        val nodes = card("DOP224.10", x)
        assertEquals(1, target.actionIndices(nodes).size)
    }

    @Test fun refusesWhatIsUnclear() {
        fun refused(nodes: List<com.ubers.observador.domain.lifecycle.ScreenNode>, price: Double) =
            target.find(snap(nodes), price) as? com.ubers.observador.domain.sectors.CloseTarget.Result.Refused
        assertEquals("No se reconoce la X de la tarjeta", refused(card("DOP224.10", null), 224.10)!!.reason)
        assertEquals("No se reconoce la X de la tarjeta", refused(card("DOP224.10", n(desc = "Cerrar", clickable = false)), 224.10)!!.reason)
        val twoX = card("DOP224.10", x).toMutableList().also { it.add(2, n(desc = "Descartar", clickable = true)) }
        assertTrue(refused(twoX, 224.10)!!.reason.startsWith("Hay 2"))
        assertEquals("La oferta ya no está en pantalla", refused(card("DOP224.10", x), 100.0)!!.reason)
        val samePrice = card("DOP150.45", x) + card("DOP150.45", n(desc = "Cerrar", clickable = true))
        assertEquals("Hay dos tarjetas con el mismo precio", refused(samePrice, 150.45)!!.reason)
        assertEquals("No se ve el botón de la tarjeta", refused(listOf(n("DOP150.45"), x), 150.45)!!.reason)
    }

    @Test fun neverTouchesAcceptEvenIfItMentionsClosing() {
        val tricky = n(text = "Aceptar", desc = "Cerrar y aceptar", clickable = true)
        val nodes = card("DOP224.10", tricky, action = "Aceptar")
        assertTrue(target.find(snap(nodes), 224.10) is com.ubers.observador.domain.sectors.CloseTarget.Result.Refused)
        val ok = card("DOP224.10", n(id = "com.ubercab.driver:id/dismiss_button", clickable = true), action = "Aceptar")
        assertTrue(target.find(snap(ok), 224.10) is com.ubers.observador.domain.sectors.CloseTarget.Result.Found)
    }

    @Test fun closingOnlyInAutomaticWithACloseSectorAndUnderTheCap() {
        val r = com.ubers.observador.domain.sectors.CloseRules
        assertEquals(CloseOutcome.NONE, r.decide(false, SectorLevel.CLOSE, true, 0))
        assertEquals(CloseOutcome.NONE, r.decide(true, SectorLevel.AVOID, true, 0))
        assertEquals(CloseOutcome.NONE, r.decide(true, null, true, 0))
        assertEquals(CloseOutcome.NONE, r.decide(true, SectorLevel.CLOSE, true, 30))
        assertEquals(CloseOutcome.SIMULATE, r.decide(true, SectorLevel.CLOSE, false, 0))
        assertEquals(CloseOutcome.CLOSE, r.decide(true, SectorLevel.CLOSE, true, 29))
        // Uber's X is not confirmed with a real capture yet: nothing is touched
        assertFalse(com.ubers.observador.domain.sectors.CloseTarget.VALIDATED)
    }

    @Test fun bandWhileTheSecondsRun() {
        val m = SectorMatcher.match(null, "Paraiso 16, Los Alcarrizos", listOf(CareSector("Los Alcarrizos", level = SectorLevel.CLOSE)))
        val r = com.ubers.observador.domain.sectors.CloseRules
        assertEquals("⛔ Se cierra en 3 s · Destino: Los Alcarrizos · toca para no cerrar", r.banner(m, null, CloseOutcome.CLOSE)!!.text)
        assertEquals("⛔ Simulación: se cerraría en 3 s · Tarjeta 2 · Destino: Los Alcarrizos", r.banner(m, 2, CloseOutcome.SIMULATE)!!.text)
        assertEquals("⛔ Evitar · Destino: Los Alcarrizos", r.banner(m, null, CloseOutcome.NONE)!!.text)
    }

    @Test fun logLines() {
        val zone = java.time.ZoneId.of("America/Santo_Domingo")
        val at = java.time.ZonedDateTime.of(2026, 11, 12, 14, 3, 12, 0, zone).toInstant().toEpochMilli()
        val l = com.ubers.observador.domain.sectors.CloseLog
        assertEquals("cierres-2026-11-12.txt", l.fileName(at, zone))
        val closed = l.line(at, zone, l.CLOSED, "Destino: Engombe", 294.67)
        assertEquals("14:03:12 · CERRADA · Destino: Engombe · RD$294.67", closed)
        val text = listOf(closed, l.line(at, zone, l.SIMULATED + " (botón «cerrar»)", "Destino: Engombe", 1.0),
            l.line(at, zone, l.NOT_CLOSED + " (Cancelado por ti)", "Destino: Engombe", 1.0)).joinToString("\n")
        assertEquals(1, l.closedCount(text))
    }

    @Test fun theRecorderKeepsWhereEachNodeIs() {
        val node = com.ubers.observador.domain.lifecycle.ScreenNode(contentDescription = "Cerrar", clickable = true, left = 10, top = 20, right = 60, bottom = 70)
        val s = com.ubers.observador.domain.lifecycle.ScreenSnapshot(0L, "com.ubercab.driver", listOf(node))
        val recorded = com.ubers.observador.domain.lifecycle.SnapshotSanitizer.describe(s, com.ubers.observador.domain.lifecycle.ScreenSignatures.SPANISH, keepMeasureDigits = true)
        assertTrue(recorded.contains("@10,20,60,70"))
        val chat = com.ubers.observador.domain.lifecycle.SnapshotSanitizer.describe(s, com.ubers.observador.domain.lifecycle.ScreenSignatures.SPANISH)
        assertFalse(chat.contains("@"))
    }
}
