package com.ubers.observador

import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.data.db.toTripOfferSnapshot
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.OfferLedger
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus
import com.ubers.observador.service.OfferAppearanceTracker
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class OfferIdentityTest {
    private var ids = 0
    private val tracker = OfferAppearanceTracker(newId = { (++ids).toString() })
    private val offer = UberOffer(120.50, 1.1, 3, 4.2, 12, detectedAt = 1_000,
        pickupAddress = "Los Ríos", dropoffAddress = "Los Prados", product = "Moto", exclusive = false)

    @Test fun localeDoesNotChangeAnyIdentity() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val hash = offer.offerHash()
            val content = offer.contentFingerprint()
            Locale.setDefault(Locale.GERMANY)
            assertEquals(hash, offer.offerHash())
            assertEquals(content, offer.contentFingerprint())
        } finally { Locale.setDefault(original) }
    }

    @Test fun fullContentSeparatesCardsWithTheSameFigures() {
        assertNotEquals(offer.contentFingerprint(), offer.copy(dropoffAddress = "Gazcue").contentFingerprint())
        assertNotEquals(offer.contentFingerprint(), offer.copy(product = "Flash").contentFingerprint())
        assertNotEquals(offer.contentFingerprint(), offer.copy(exclusive = true).contentFingerprint())
        assertNotEquals(offer.contentFingerprint(), offer.copy(pickupKm = 1.14).contentFingerprint())
        assertEquals(offer.contentFingerprint(), offer.copy(pickupAddress = " LOS  RIOS ").contentFingerprint())
    }

    @Test fun repaintsKeepTheAppearanceAndOriginalDetectionTime() {
        val first = tracker.observe(listOf(offer), 0, 7).single()
        val repaint = tracker.observe(listOf(offer.copy(detectedAt = 9_000)), 500, 7).single()
        assertEquals(first, repaint)
        assertTrue(first.offerHash().startsWith(UberOffer.APPEARANCE_PREFIX))
        assertFalse(first.offerHash().contains("Ríos"))
    }

    @Test fun absenceIsMeasuredFromTheFirstMissingReadNotTheLastOfferRead() {
        val first = tracker.observe(listOf(offer), 0, 7).single()
        tracker.observe(emptyList(), 20_000, null)
        assertEquals(first, tracker.observe(listOf(offer), 20_500, 7).single())
        tracker.observe(emptyList(), 21_000, null)
        assertNotEquals(first.offerHash(), tracker.observe(listOf(offer), 22_500, 7).single().offerHash())
    }

    @Test fun changingTheWindowStartsANewAppearance() {
        val first = tracker.observe(listOf(offer), 0, 7).single()
        assertNotEquals(first.offerHash(), tracker.observe(listOf(offer), 100, 8).single().offerHash())
    }

    @Test fun reorderingRadarCardsKeepsTheirIdentitiesAndDoesNotGuessAcceptance() {
        val other = offer.copy(dropoffAddress = "Gazcue")
        val first = tracker.observe(listOf(offer, other), 0, 7)
        val reordered = tracker.observe(listOf(other, offer), 300, 7)
        assertEquals(first.map { it.offerHash() }.reversed(), reordered.map { it.offerHash() })
        assertNull(tracker.lifecycleOfferId(reordered))
        assertEquals(first[0].offerHash(), tracker.lifecycleOfferId(listOf(first[0])))
        assertNull(tracker.lifecycleOfferId(listOf(first[0]), cardCount = 2))
    }

    @Test fun identicalSimultaneousCardsStaySeparate() {
        val cards = tracker.observe(listOf(offer, offer), 0, 7)
        assertEquals(2, cards.map { it.offerHash() }.distinct().size)
        assertNull(tracker.lifecycleOfferId(cards))
    }

    private fun row(o: UberOffer, id: Long): OfferEntity = ProfitEngine().analyze(o, EngineSettings()).toEntity().copy(id = id)
    private fun trip(id: String, row: OfferEntity) = Trip(
        id, "session-$id", row.offerHash, TripStatus.COMPLETED, false, true,
        acceptedAt = row.detectedAt + 1_000, completedAt = 30_000, createdAt = row.detectedAt + 1_000,
        updatedAt = 30_000, offer = row.toTripOfferSnapshot()
    )

    @Test fun oneLegacyTripOnlyMarksItsOwnDetectionAsTaken() {
        val first = row(offer, 1)
        val second = row(offer.copy(detectedAt = 86_401_000), 2)
        assertEquals(first.offerHash, second.offerHash)
        val ledger = OfferLedger.of(listOf(first, second), listOf(trip("a", first)))
        assertEquals(setOf(1L), ledger.tripOf.keys)
        assertEquals(1, ledger.counts.offered)
    }

    @Test fun deletedLegacyOfferDoesNotStealAnotherAppearance() {
        val deleted = row(offer, 1)
        val other = row(offer.copy(detectedAt = 86_401_000), 2)
        val ledger = OfferLedger.of(listOf(other), listOf(trip("a", deleted)))
        assertNull(ledger.tripOf[2L])
        assertEquals(1, ledger.counts.taken)
        assertEquals(1, ledger.counts.offered)
    }

    @Test fun duplicateLegacyRowsAreNotGuessedAndEqualEndTimesKeepBothTrips() {
        val first = row(offer, 1)
        val second = first.copy(id = 2)
        val ledger = OfferLedger.of(listOf(first, second), listOf(trip("a", first), trip("b", first)))
        assertNull(ledger.tripOf[1L])
        assertNull(ledger.tripOf[2L])
        assertEquals(setOf("a", "b"), ledger.tripOf.values.map { it.id }.toSet())
        assertEquals(4, ledger.rows.size)
        assertEquals(4, ledger.rows.map { it.id }.distinct().size)
    }

    @Test fun appearanceIdsSurviveStorageMappingAndLinkExactly() {
        val one = tracker.observe(listOf(offer), 0, 7).single()
        tracker.observe(emptyList(), 10, null)
        val two = tracker.observe(listOf(offer.copy(detectedAt = 3_000)), 2_000, 7).single()
        val a = row(one, 1)
        val b = row(two, 2)
        val ledger = OfferLedger.of(listOf(a, b), listOf(trip("a", a), trip("b", b)))
        assertEquals("a", ledger.tripOf[1L]?.id)
        assertEquals("b", ledger.tripOf[2L]?.id)
        assertEquals(2, ledger.counts.taken)
    }

    @Test fun shadowResultsCannotBorrowAnIdenticalOfferFromAnotherDay() {
        val seed = com.ubers.observador.data.debug.IntelligenceTestSeeder.generate(
            com.ubers.observador.data.debug.IntelligenceTestSeeder.Scenario.PICKUP_MATTERS, 1_800_000_000_000
        )
        val sample = com.ubers.observador.domain.intelligence.IntelligenceDatasetBuilder.build(
            seed.trips, seed.summaries(), "DOP"
        ).samples.first().copy(offerHash = "legacy", detectedAtMs = 1_000, acceptedAtMs = 2_000, realizedGrossPerEngagedHour = 500.0)
        val later = sample.copy(tripId = "later", detectedAtMs = 86_401_000, acceptedAtMs = 86_402_000, realizedGrossPerEngagedHour = 1_500.0)
        val evaluation = com.ubers.observador.domain.intelligence.ShadowEvaluation(
            "e", "legacy", 1_100, "active", 10, com.ubers.observador.domain.OfferQuality.BAD,
            "candidate", 90, com.ubers.observador.domain.OfferQuality.EXCELLENT
        )
        val report = com.ubers.observador.domain.intelligence.ShadowAnalyzer.analyze("candidate", listOf(evaluation), listOf(sample, later), 300.0)
        assertEquals(1, report.withOutcome)
        assertEquals(500.0, report.disagreementOutcomes.single().medianHourly!!, 0.0)
        assertEquals(0, com.ubers.observador.domain.intelligence.ShadowAnalyzer.analyze(
            "candidate", listOf(evaluation), listOf(later), 300.0
        ).withOutcome)
        assertEquals(0, com.ubers.observador.domain.intelligence.ShadowAnalyzer.analyze(
            "candidate", listOf(evaluation), listOf(sample, sample.copy(tripId = "ambiguous")), 300.0
        ).withOutcome)
    }
}
