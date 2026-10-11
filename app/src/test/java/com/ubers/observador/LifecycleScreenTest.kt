package com.ubers.observador

import com.ubers.observador.LifecycleFixtures.button
import com.ubers.observador.LifecycleFixtures.screen
import com.ubers.observador.LifecycleFixtures.text
import com.ubers.observador.domain.lifecycle.ClassifierPolicy
import com.ubers.observador.domain.lifecycle.ConfidenceLevel
import com.ubers.observador.domain.lifecycle.EvidenceCode
import com.ubers.observador.domain.lifecycle.ScreenDetection
import com.ubers.observador.domain.lifecycle.ScreenDetector
import com.ubers.observador.domain.lifecycle.ScreenFingerprint
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSignatures
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.SnapshotSanitizer
import com.ubers.observador.domain.lifecycle.TextNormalizer
import com.ubers.observador.domain.lifecycle.UberScreenClassifier
import com.ubers.observador.domain.lifecycle.UberScreenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Normalization, fingerprint, sanitizer and the screen classifier. */
class LifecycleScreenTest {

    private val classifier = LifecycleFixtures.classifier

    // ---- Normalization ----

    @Test
    fun `normalize trims`() = assertEquals("iniciar viaje", TextNormalizer.normalize("  iniciar viaje "))

    @Test
    fun `normalize collapses spaces, tabs, line breaks and no-break spaces`() =
        assertEquals("iniciar viaje", TextNormalizer.normalize("Iniciar \t\n  viaje "))

    @Test
    fun `normalize ignores case`() =
        assertEquals(TextNormalizer.normalize("INICIAR VIAJE"), TextNormalizer.normalize("iniciar viaje"))

    @Test
    fun `normalize folds accents, ligatures, full-width letters and invisible characters`() {
        assertEquals("llegue", TextNormalizer.normalize("Llegué"))
        assertEquals("finalizar", TextNormalizer.normalize("ﬁnalizar"))
        assertEquals("viaje", TextNormalizer.normalize("ｖｉａｊｅ"))
        assertEquals("iniciar viaje", TextNormalizer.normalize("Ini​ciar viaje"))
    }

    @Test
    fun `masking keeps the shape of a value, not the value`() =
        assertEquals("# min (#.# km)", TextNormalizer.maskDigits("3 min (1.2 km)"))

    @Test
    fun `texts keep the order the offer parser has always used`() {
        val snap = ScreenSnapshot(
            0L, LifecycleFixtures.UBER,
            listOf(
                ScreenNode(text = " DOP136.06 ", contentDescription = "precio"),
                ScreenNode(text = "   "),
                ScreenNode(contentDescription = "Aceptar")
            )
        )
        assertEquals(listOf("DOP136.06", "precio", "Aceptar"), snap.texts)
    }

    // ---- Fingerprint ----

    @Test
    fun `fingerprint is deterministic`() =
        assertEquals(
            ScreenFingerprint.of(LifecycleFixtures.inTrip(), null),
            ScreenFingerprint.of(LifecycleFixtures.inTrip(), null)
        )

    @Test
    fun `a countdown does not make a new screen`() =
        assertEquals(
            ScreenFingerprint.of(LifecycleFixtures.pickup("3 min"), null),
            ScreenFingerprint.of(LifecycleFixtures.pickup("2 min"), null)
        )

    @Test
    fun `different offers or labels make different fingerprints`() {
        val offer = LifecycleFixtures.offer()
        assertFalse(
            ScreenFingerprint.of(offer, LifecycleFixtures.OFFER_HASH) ==
                ScreenFingerprint.of(offer, LifecycleFixtures.OTHER_OFFER_HASH)
        )
        assertFalse(ScreenFingerprint.of(LifecycleFixtures.arrived(), null) == ScreenFingerprint.of(LifecycleFixtures.inTrip(), null))
    }

    @Test
    fun `fingerprint keeps no screen text`() {
        val fp = ScreenFingerprint.of(LifecycleFixtures.inTrip(), null)
        assertEquals(16, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ---- Sanitized snapshot ----

    @Test
    fun `sanitized snapshot hides names and addresses but keeps buttons, ids and measures`() {
        val dump = SnapshotSanitizer.describe(LifecycleFixtures.offer(), ScreenSignatures.SPANISH)
        assertFalse(dump.lowercase().contains("ficticia"))
        assertFalse(dump.contains("123"))
        assertTrue(dump.contains("\"aceptar\""))
        assertTrue(dump.contains("id=accept"))
        assertTrue(dump.contains("\"dop###.##\""))
        assertTrue(dump.contains("\"a # min (#.# km)\""))
        val ride = SnapshotSanitizer.describe(LifecycleFixtures.inTrip(), ScreenSignatures.SPANISH)
        assertTrue(ride.contains("\"finalizar viaje\""))
        // A known phrase is shown alone: the name after "dejar a" never appears
        assertTrue(ride.contains("\"dejar a\" +13 car."))
        assertFalse(ride.lowercase().contains("ficticia"))
        assertFalse(ride.lowercase().contains("imaginaria"))
    }

    // ---- Classification ----

    @Test
    fun `each synthetic screen is recognised`() {
        val expected = mapOf(
            LifecycleFixtures.searching() to UberScreenType.SEARCHING,
            LifecycleFixtures.offer() to UberScreenType.OFFER,
            LifecycleFixtures.pickup() to UberScreenType.PICKUP_NAVIGATION,
            LifecycleFixtures.arrived() to UberScreenType.ARRIVED_OR_WAITING,
            LifecycleFixtures.inTrip() to UberScreenType.IN_TRIP,
            LifecycleFixtures.summary() to UberScreenType.TRIP_SUMMARY,
            LifecycleFixtures.cancelled() to UberScreenType.CANCELLED,
            LifecycleFixtures.unknown() to UberScreenType.UNKNOWN
        )
        expected.forEach { (snap, type) -> assertEquals(type, classifier.classify(snap).type) }
    }

    @Test
    fun `the offer card is HIGH and fully proven, the trip screens are provisional`() {
        val offer = classifier.classify(LifecycleFixtures.offer())
        assertEquals(ConfidenceLevel.HIGH, offer.level)
        assertFalse(offer.provisional)
        assertEquals(
            listOf(EvidenceCode.OFFER_ACCEPT_ACTION, EvidenceCode.OFFER_PRICE, EvidenceCode.OFFER_PICKUP_ETA),
            offer.evidence.map { it.code }
        )
        val ride = classifier.classify(LifecycleFixtures.inTrip())
        assertEquals(ConfidenceLevel.MEDIUM, ride.level)
        assertTrue(ride.provisional)
        assertEquals(listOf(EvidenceCode.END_TRIP_ACTION, EvidenceCode.DROPOFF_HEADER), ride.evidence.map { it.code })
    }

    @Test
    fun `classification explains itself with the matched signature`() {
        val c = classifier.classify(LifecycleFixtures.arrived())
        assertEquals("iniciar viaje", c.evidence.first { it.code == EvidenceCode.START_TRIP_ACTION }.matchedSignature)
    }

    @Test
    fun `a single generic word decides nothing`() {
        val c = classifier.classify(screen(text("Viaje")))
        assertEquals(UberScreenType.UNKNOWN, c.type)
        val one = classifier.classify(screen(button("Finalizar viaje")))
        assertEquals(UberScreenType.IN_TRIP, one.type)
        assertEquals(ConfidenceLevel.LOW, one.level)
    }

    @Test
    fun `empty screen is UNKNOWN with no evidence`() {
        val c = classifier.classify(screen())
        assertEquals(UberScreenType.UNKNOWN, c.type)
        assertEquals(0.0, c.confidence, 0.0)
        assertTrue(c.evidence.isEmpty())
    }

    @Test
    fun `contradictory evidence is UNKNOWN, not a guess`() {
        // Start and finish buttons together rule out both ARRIVED and IN_TRIP
        val c = classifier.classify(screen(text("Esperando a Ana"), button("Iniciar viaje"), button("Finalizar viaje")))
        assertEquals(UberScreenType.UNKNOWN, c.type)
    }

    @Test
    fun `detections too close to call are AMBIGUOUS with their candidates`() {
        val a = fixed(UberScreenType.PICKUP_NAVIGATION, 0.82)
        val b = fixed(UberScreenType.ARRIVED_OR_WAITING, 0.79)
        val c = UberScreenClassifier(LifecycleFixtures.UBER, ScreenSignatures.SPANISH, listOf(a, b))
            .classify(LifecycleFixtures.unknown())
        assertEquals(UberScreenType.AMBIGUOUS, c.type)
        assertEquals(listOf(UberScreenType.PICKUP_NAVIGATION, UberScreenType.ARRIVED_OR_WAITING), c.candidates.map { it.type })
        val clear = UberScreenClassifier(LifecycleFixtures.UBER, ScreenSignatures.SPANISH, listOf(a, fixed(UberScreenType.IN_TRIP, 0.60)))
            .classify(LifecycleFixtures.unknown())
        assertEquals(UberScreenType.PICKUP_NAVIGATION, clear.type)
    }

    @Test
    fun `other apps are never classified`() =
        assertEquals(UberScreenType.UNKNOWN, classifier.classify(LifecycleFixtures.otherApp()).type)

    @Test
    fun `a failing detector is reported and the others still run`() {
        val broken = object : ScreenDetector {
            override val type = UberScreenType.CANCELLED
            override fun evaluate(snapshot: ScreenSnapshot): ScreenDetection? = throw IllegalStateException("bug")
        }
        val c = UberScreenClassifier(LifecycleFixtures.UBER, ScreenSignatures.SPANISH, listOf(broken, fixed(UberScreenType.IN_TRIP, 0.9)))
            .classify(LifecycleFixtures.unknown())
        assertEquals(UberScreenType.IN_TRIP, c.type)
        assertEquals(listOf(UberScreenType.CANCELLED), c.failedDetectors)
    }

    @Test
    fun `confidence levels follow the central policy`() {
        assertEquals(0.60, ClassifierPolicy.confidenceFor(1), 1e-9)
        assertEquals(0.75, ClassifierPolicy.confidenceFor(2), 1e-9)
        assertEquals(0.90, ClassifierPolicy.confidenceFor(3), 1e-9)
        assertEquals(0.95, ClassifierPolicy.confidenceFor(9), 1e-9)
        assertEquals(0.0, ClassifierPolicy.confidenceFor(0), 0.0)
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.of(0.64))
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.of(0.65))
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.of(0.85))
    }

    @Test
    fun `an offer shown over a trip screen is read as the offer`() {
        val stacked = screen(
            text("Dejar a Ana Ficticia"), button("Finalizar viaje"),
            text("DOP98.50"), text("A 5 min (2.0 km)"), text("9 min (3.4 km)"), button("Aceptar")
        )
        assertEquals(UberScreenType.OFFER, classifier.classify(stacked).type)
    }

    private fun fixed(type: UberScreenType, confidence: Double) = object : ScreenDetector {
        override val type = type
        override fun evaluate(snapshot: ScreenSnapshot) = ScreenDetection(type, confidence, emptyList())
    }
}
