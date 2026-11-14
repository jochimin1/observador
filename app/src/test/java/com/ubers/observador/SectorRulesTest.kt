package com.ubers.observador

import com.ubers.observador.domain.backup.DecodedSettings
import com.ubers.observador.domain.backup.RestoreRules
import com.ubers.observador.domain.sectors.CareSector
import com.ubers.observador.domain.sectors.CareSectorCodec
import com.ubers.observador.domain.sectors.OfferPlaceKind
import com.ubers.observador.domain.sectors.SectorContext
import com.ubers.observador.domain.sectors.SectorLevel
import com.ubers.observador.domain.sectors.SectorMatcher
import com.ubers.observador.domain.sectors.SectorSchedule
import com.ubers.observador.domain.sectors.SectorWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 1.47.0: other names, exceptions, schedule, modality, on/off and the rule tester. */
class SectorRulesTest {

    private val zone = ZoneId.of("America/Santo_Domingo")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, product: String? = "Moto") =
        SectorContext(LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli(), zone, product)

    // 2026-11-13 is a Friday, 2026-11-14 a Saturday
    private val fridayNight = at(2026, 11, 13, 22)
    private val saturdayDawn = at(2026, 11, 14, 2)
    private val saturdayNoon = at(2026, 11, 14, 12)

    @Test fun anAliasMatchesLikeTheName() {
        val s = CareSector("Los Alcarrizos", aliases = listOf("Alcarrizos"))
        assertEquals(1, SectorMatcher.match(null, "Calle 5, Alcarrizos", listOf(s)).hits.size)
        assertEquals(1, SectorMatcher.match(null, "Paraíso 16, Los Alcarrizos", listOf(s)).hits.size)
        assertTrue(SectorMatcher.match(null, "Calle 5, Los Ríos", listOf(s)).isEmpty)
    }

    @Test fun anExceptionAnywhereInTheAddressCancelsTheSector() {
        val s = CareSector("Santo Domingo Oeste", exceptions = listOf("Herrera"))
        assertTrue(SectorMatcher.match(null, "C. Duarte 4, Herrera, Santo Domingo Oeste", listOf(s)).isEmpty)
        assertEquals(1, SectorMatcher.match(null, "C. Duarte 4, Engombe, Santo Domingo Oeste", listOf(s)).hits.size)
        // A street called like the exception does not cancel: streets are skipped
        assertEquals(1, SectorMatcher.match(null, "Av. Herrera 10, Engombe, Santo Domingo Oeste", listOf(s)).hits.size)
    }

    @Test fun sameNameInTwoMunicipalitiesStaysApartAndStreetsNeverMatch() {
        val este = CareSector("Santo Domingo Este")
        assertTrue(SectorMatcher.match(null, "Los Mina, Santo Domingo Oeste", listOf(este)).isEmpty)
        val lincoln = CareSector("Lincoln")
        assertTrue(SectorMatcher.match(null, "Av. Abraham Lincoln 50, Piantini", listOf(lincoln)).isEmpty)
        // Incomplete destination: one part only, read whole
        assertEquals(1, SectorMatcher.match(null, "Los Mina", listOf(CareSector("Los Mina"))).hits.size)
        assertTrue(SectorMatcher.match(null, null, listOf(CareSector("Los Mina"))).isEmpty)
    }

    @Test fun aNightScheduleCrossesMidnightAndBelongsToTheDayItBegan() {
        val friday = SectorSchedule(days = setOf(5), fromMin = 20 * 60, toMin = 5 * 60)
        val s = CareSector("Engombe", schedule = friday)
        val list = listOf(s)
        assertEquals(1, SectorMatcher.match(null, "Engombe", list, fridayNight).hits.size)
        assertEquals(1, SectorMatcher.match(null, "Engombe", list, saturdayDawn).hits.size)
        assertTrue(SectorMatcher.match(null, "Engombe", list, saturdayNoon).isEmpty)
        // Saturday 22:00 is not Friday's night
        assertTrue(SectorMatcher.match(null, "Engombe", list, at(2026, 11, 14, 22)).isEmpty)
        // Without a context (searches) the schedule is not applied
        assertEquals(1, SectorMatcher.match(null, "Engombe", list).hits.size)
    }

    @Test fun scheduleEdgesAndWholeDay() {
        val morning = SectorSchedule(emptySet(), 6 * 60, 10 * 60)
        assertTrue(morning.isActive(LocalDateTime.of(2026, 11, 14, 6, 0).atZone(zone)))
        assertFalse(morning.isActive(LocalDateTime.of(2026, 11, 14, 10, 0).atZone(zone)))
        val sundays = SectorSchedule(setOf(7), 0, 0)
        assertTrue(sundays.isActive(LocalDateTime.of(2026, 11, 15, 23, 59).atZone(zone)))
        assertFalse(sundays.isActive(LocalDateTime.of(2026, 11, 14, 12, 0).atZone(zone)))
        assertEquals("V · 20:00–05:00", SectorSchedule(setOf(5), 1200, 300).label)
        assertEquals(20 * 60 + 30, SectorSchedule.parseTime("20:30"))
        assertEquals(8 * 60, SectorSchedule.parseTime("8"))
        assertNull(SectorSchedule.parseTime("25:00"))
        assertNull(SectorSchedule.parseTime("8:7"))
    }

    @Test fun modalityAndDisabledSectors() {
        val onlyFlash = CareSector("Engombe", product = "Moto Flash")
        assertTrue(SectorMatcher.match(null, "Engombe", listOf(onlyFlash), saturdayNoon).isEmpty)
        assertEquals(1, SectorMatcher.match(null, "Engombe", listOf(onlyFlash), at(2026, 11, 14, 12, product = "moto flash")).hits.size)
        assertTrue(SectorMatcher.match(null, "Engombe", listOf(onlyFlash), at(2026, 11, 14, 12, product = null)).isEmpty)
        val off = CareSector("Engombe", enabled = false)
        assertTrue(SectorMatcher.match(null, "Engombe", listOf(off)).isEmpty)
    }

    @Test fun theTesterSaysWhy() {
        val sectors = listOf(
            CareSector("Santo Domingo Oeste", exceptions = listOf("Herrera")),
            CareSector("Herrera", where = SectorWhere.PICKUP),
            CareSector("Engombe", schedule = SectorSchedule(setOf(5), 1200, 300)),
            CareSector("Los Mina", level = SectorLevel.AVOID)
        )
        val e = SectorMatcher.explain("C. 1, Herrera, Santo Domingo Oeste", OfferPlaceKind.DROPOFF, sectors, saturdayNoon)
        val byName = e.associateBy { it.sector.name }
        assertTrue(byName.getValue("Santo Domingo Oeste").verdict is SectorMatcher.Verdict.Excepted)
        assertEquals(SectorMatcher.Verdict.OtherPlace, byName.getValue("Herrera").verdict)
        assertEquals(SectorMatcher.Verdict.NotFound, byName.getValue("Los Mina").verdict)
        // The ones that say something come first
        assertEquals(SectorMatcher.Verdict.NotFound, e.last().verdict)

        val night = SectorMatcher.explain("Engombe, Santo Domingo Oeste", OfferPlaceKind.DROPOFF, sectors, saturdayNoon)
        assertTrue(night.first { it.sector.name == "Engombe" }.verdict is SectorMatcher.Verdict.OutOfSchedule)
        val matched = SectorMatcher.explain("Engombe, Santo Domingo Oeste", OfferPlaceKind.DROPOFF, sectors, fridayNight)
            .first { it.sector.name == "Engombe" }.verdict as SectorMatcher.Verdict.Matched
        assertEquals("Engombe", matched.part)
    }

    @Test fun theTesterAgreesWithTheCard() {
        val sectors = listOf(
            CareSector("Los Alcarrizos", aliases = listOf("Alcarrizos"), exceptions = listOf("Pueblo Nuevo")),
            CareSector("Engombe", product = "Moto", schedule = SectorSchedule(emptySet(), 600, 1300))
        )
        val addresses = listOf("Calle 5, Alcarrizos", "Pueblo Nuevo, Los Alcarrizos", "Engombe, Santo Domingo Oeste", "Gazcue")
        listOf(fridayNight, saturdayNoon, at(2026, 11, 14, 12, product = "Moto Flash")).forEach { ctx ->
            addresses.forEach { a ->
                val card = SectorMatcher.match(null, a, sectors, ctx).hits.map { it.sector.name }.toSet()
                val tester = SectorMatcher.explain(a, OfferPlaceKind.DROPOFF, sectors, ctx).filter { it.warns }.map { it.sector.name }.toSet()
                assertEquals("$a at $ctx", card, tester)
            }
        }
    }

    @Test fun theCodecReadsTheOldFormatAndRoundTripsTheNewOne() {
        val old = """[{"n":"Engombe","w":"BOTH","l":"AVOID"}]"""
        assertEquals(listOf(CareSector("Engombe", SectorWhere.BOTH, SectorLevel.AVOID)), CareSectorCodec.decode(old))
        // A list without the new fields is written exactly as before
        assertEquals(old, CareSectorCodec.encode(CareSectorCodec.decode(old)!!))
        val full = listOf(
            CareSector(
                "Los Alcarrizos", SectorWhere.DROPOFF, SectorLevel.CLOSE, listOf("Alcarrizos"), listOf("Pueblo Nuevo"),
                SectorSchedule(setOf(5, 6), 1200, 300), "Moto", enabled = false
            ),
            CareSector("Gazcue", schedule = SectorSchedule(emptySet(), 0, 0))
        )
        assertEquals(full, CareSectorCodec.decode(CareSectorCodec.encode(full)))
    }

    @Test fun theCodecRejectsDamagedNewFields() {
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","a":"B"}]"""))
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","a":[""]}]"""))
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","x":["1","2","3","4","5","6"]}]"""))
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","d":[8],"f":0,"t":0}]"""))
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","d":[],"f":1440,"t":0}]"""))
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","d":[1]}]"""))
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","p":""}]"""))
        assertNull(CareSectorCodec.decode("""[{"n":"A","w":"BOTH","l":"WARN","e":"no"}]"""))
        val bad = RestoreRules.validateSettings(DecodedSettings(mapOf("care_sectors" to """[{"n":"A","w":"BOTH","l":"WARN","d":[9],"f":0,"t":0}]"""), emptyList()))
        assertEquals(listOf("care_sectors"), bad.defaulted)
    }

    @Test fun splitNamesTidiesWhatWasTyped() {
        // Empty ones dropped, repeated ones (accents and case ignored) kept once, as first written
        assertEquals(listOf("Alcarrizos", "los alcarrizos"), CareSectorCodec.splitNames(" Alcarrizos ;; los alcarrizos; Los Alcarrizos ;", 5))
        assertEquals(2, CareSectorCodec.splitNames("A; B; C", 2).size)
    }

    @Test fun suggestionsSkipAliasesAlreadyListed() {
        val s = SectorMatcher.suggestions(listOf("Calle 5, Alcarrizos", "Gazcue"), listOf(CareSector("Los Alcarrizos", aliases = listOf("Alcarrizos"))))
        assertEquals(listOf("Gazcue"), s)
    }
}
