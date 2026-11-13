package com.ubers.observador

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.ReadingEligibility
import com.ubers.observador.domain.lifecycle.CaptureLog
import com.ubers.observador.domain.lifecycle.OfferCardParser
import com.ubers.observador.domain.lifecycle.OfferParseCheck
import com.ubers.observador.domain.lifecycle.OfferParseStep
import com.ubers.observador.domain.lifecycle.ReadingNumbers
import com.ubers.observador.domain.lifecycle.ReadingProblem
import com.ubers.observador.domain.lifecycle.ReadingVersions
import com.ubers.observador.domain.models.UberOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 1.38.0: conservative numbers, explicit «no pude leer», reading versions and doubtful readings. */
class ReadingTraceTest {

    @Test fun decimalsAcceptOnlyConsistentFormats() {
        assertEquals(136.06, ReadingNumbers.decimal("136.06")!!, 0.0)
        assertEquals(136.06, ReadingNumbers.decimal("136,06")!!, 0.0)
        assertEquals(1250.0, ReadingNumbers.decimal("1,250.00")!!, 0.0)
        assertEquals(1250.0, ReadingNumbers.decimal("1.250,00")!!, 0.0)
        assertEquals(1250.0, ReadingNumbers.decimal("1 250,00")!!, 0.0)
        assertEquals(1250.0, ReadingNumbers.decimal("1250")!!, 0.0)
        assertEquals(4.1, ReadingNumbers.decimal("4,1")!!, 0.0)
        listOf("1,25,0", "12,34.5,6", "1.250.5", "1,2345", "", "abc", "1..2", "1,250,00.0").forEach {
            assertNull(it, ReadingNumbers.decimal(it))
        }
        assertNull(ReadingNumbers.decimal("99999999"))
    }

    @Test fun onlyWholeButtonLabelsAreActions() {
        listOf("Aceptar", "ACEPTAR", "Aceptar viaje", "Accept", "Me interesa", " me  interesa ").forEach {
            assertTrue(it, OfferParseCheck.isAction(it))
        }
        listOf("Cerrar y aceptar", "Aceptaste el viaje", "Aceptación", "Interesa").forEach {
            assertFalse(it, OfferParseCheck.isAction(it))
        }
    }

    @Test fun unreadableCardsExplainWhyAndCarryNoNumbers() {
        val reading = OfferCardParser.diagnoseAll(listOf("DOP136.06", "DOP150.00", "A 7 min (4.1 km)", "11 min (7.2 km)", "Aceptar"))
        assertTrue(reading.offers.isEmpty())
        assertEquals(listOf(ReadingProblem.MULTIPLE_PRICES), reading.problems)
        assertEquals(OfferParseStep.AMBIGUOUS, OfferParseCheck.check(listOf("DOP136.06", "DOP150.00", "A 7 min (4.1 km)", "11 min (7.2 km)", "Aceptar")))
        assertTrue(ReadingProblem.entries.all { it.label.isNotBlank() })
    }

    @Test fun radarCardsAreSplitByTheirButtons() {
        val reading = OfferCardParser.diagnoseAll(listOf(
            "Moto", "DOP98.50", "A 5 min (2.0 km)", "9 min (3.4 km)", "Me interesa",
            "Moto", "Exclusivo", "DOP136.06", "A 7 min (4.1 km)", "11 min (7.2 km)", "Me interesa"
        ))
        assertEquals(listOf(98.50, 136.06), reading.offers.map { it.price })
        assertEquals(listOf(false, true), reading.offers.map { it.exclusive == true })
        assertEquals(listOf("Moto", "Moto"), reading.offers.map { it.product })
        assertTrue(reading.rejected.isEmpty())
    }

    @Test fun aBrokenRadarCardDoesNotHideTheGoodOne() {
        val reading = OfferCardParser.diagnoseAll(listOf(
            "DOP98.50", "A 5 min (2.0 km)", "Me interesa",
            "DOP136.06", "A 7 min (4.1 km)", "11 min (7.2 km)", "Me interesa"
        ))
        assertEquals(listOf(136.06), reading.offers.map { it.price })
        assertEquals(listOf(ReadingProblem.NO_TRIP), reading.problems)
    }

    @Test fun parsedOffersCarryTheRuleVersions() {
        val offer = OfferCardParser.parse(listOf("DOP136.06", "A 7 min (4.1 km)", "11 min (7.2 km)", "Aceptar"))!!
        assertEquals(ReadingVersions.PARSER, offer.readingTrace!!.parserVersion)
        assertEquals(ReadingVersions.CLASSIFIER, offer.readingTrace!!.classifierVersion)
        // The trace never changes the identity of the offer
        assertEquals(UberOffer(136.06, 4.1, 7, 7.2, 11).offerHash(), offer.offerHash())
    }

    @Test fun captureEntriesNameTheVersions() {
        val entry = CaptureLog.entry(0, ZoneId.of("UTC"), null, "IDLE", "", "4.500.10000 (123)")
        assertTrue(ReadingVersions.PARSER in entry)
        assertTrue(ReadingVersions.CLASSIFIER in entry)
        assertTrue("uber=4.500.10000 (123)" in entry)
        assertTrue("uber=no disponible" in CaptureLog.entry(0, ZoneId.of("UTC"), null, "IDLE", ""))
    }

    private fun row(id: Long, score: Int, excluded: Boolean = false) = OfferEntity(
        id = id, detectedAt = 1_000L * id, offerHash = "h$id", price = 100.0, pickupKm = 1.0, pickupMin = 3,
        tripKm = 5.0, tripMin = 10, totalKm = 6.0, totalMin = 13, rdpPerKm = 16.67, rdpPerHour = 461.5,
        fuelCost = 20.0, netProfit = 80.0, score = score, excludedFromAnalysis = excluded
    )

    @Test fun doubtfulReadingsStayStoredButLeaveTheStatistics() {
        val rows = listOf(row(1, 80), row(2, 20, excluded = true), row(3, 60))
        assertEquals(listOf(1L, 3L), ReadingEligibility.offers(rows).map { it.id })
        val summary = DashboardStats.summarize(rows, QualityThresholds())
        assertEquals(2, summary.count)
        assertEquals(70.0, summary.avgScore, 0.0)
        assertEquals(0, DashboardStats.summarize(listOf(row(2, 20, excluded = true)), QualityThresholds()).count)
    }
}
