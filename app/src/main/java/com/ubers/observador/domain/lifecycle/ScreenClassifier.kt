package com.ubers.observador.domain.lifecycle

/** What Uber Driver appears to show. A screen is not a ride state: see RideLifecycleState. */
enum class UberScreenType {
    SEARCHING,
    OFFER,
    PICKUP_NAVIGATION,
    ARRIVED_OR_WAITING,
    IN_TRIP,
    TRIP_SUMMARY,
    CANCELLED,
    /** Two detectors agreed too closely to pick one: the candidates are kept, nothing is forced. */
    AMBIGUOUS,
    UNKNOWN
}

/** Every confidence cut-off and score step lives here. */
object ClassifierPolicy {
    /** One matching signal on its own stays LOW: a single generic word never decides a screen. */
    const val ONE_SIGNAL = 0.60
    const val PER_EXTRA_SIGNAL = 0.15
    const val MAX_CONFIDENCE = 0.95

    const val MEDIUM_FROM = 0.65
    const val HIGH_FROM = 0.85

    /** Below this gap between the two best detections the screen is AMBIGUOUS. */
    const val AMBIGUITY_MARGIN = 0.10

    /** 1 signal 0.60 (LOW) · 2 signals 0.75 (MEDIUM) · 3 signals 0.90 (HIGH) · capped at 0.95. */
    fun confidenceFor(signals: Int): Double =
        if (signals <= 0) 0.0 else minOf(MAX_CONFIDENCE, ONE_SIGNAL + PER_EXTRA_SIGNAL * (signals - 1))
}

enum class ConfidenceLevel {
    LOW, MEDIUM, HIGH;

    companion object {
        fun of(confidence: Double): ConfidenceLevel = when {
            confidence >= ClassifierPolicy.HIGH_FROM -> HIGH
            confidence >= ClassifierPolicy.MEDIUM_FROM -> MEDIUM
            else -> LOW
        }
    }
}

/** One reason for a classification: the code, the signature phrase that matched, and whether it is proven. */
data class ScreenEvidence(val code: EvidenceCode, val matchedSignature: String, val validated: Boolean)

data class ScreenDetection(val type: UberScreenType, val confidence: Double, val evidence: List<ScreenEvidence>)

data class ScreenClassification(
    val type: UberScreenType,
    val confidence: Double,
    val evidence: List<ScreenEvidence>,
    /** For AMBIGUOUS: the detections that were too close to call. */
    val candidates: List<ScreenDetection> = emptyList(),
    /** Detectors that threw on this snapshot; the others still ran. */
    val failedDetectors: List<UberScreenType> = emptyList()
) {
    val level: ConfidenceLevel get() = ConfidenceLevel.of(confidence)

    /** True while any evidence comes from a phrase not yet confirmed on a real Uber screen. */
    val provisional: Boolean get() = evidence.any { !it.validated }

    val isRecognized: Boolean get() = type != UberScreenType.UNKNOWN && type != UberScreenType.AMBIGUOUS

    companion object {
        val UNKNOWN = ScreenClassification(UberScreenType.UNKNOWN, 0.0, emptyList())
    }
}

/** One small rule per screen. The classifier combines them; none of them reads Android. */
interface ScreenDetector {
    val type: UberScreenType
    fun evaluate(snapshot: ScreenSnapshot): ScreenDetection?
}

/**
 * A screen shows every [required] code; [supporting] codes add confidence; any [excluded] code
 * rules it out (an "Iniciar viaje" button means the trip has not started, so not IN_TRIP).
 */
class SignatureDetector(
    override val type: UberScreenType,
    private val signatures: ScreenSignatures,
    private val required: List<EvidenceCode>,
    private val supporting: List<EvidenceCode> = emptyList(),
    private val excluded: List<EvidenceCode> = emptyList()
) : ScreenDetector {

    override fun evaluate(snapshot: ScreenSnapshot): ScreenDetection? {
        val texts = snapshot.normalizedTexts
        if (excluded.any { signatures.match(it, texts) != null }) return null
        val found = ArrayList<ScreenEvidence>()
        for (code in required) {
            val phrase = signatures.match(code, texts) ?: return null
            found += ScreenEvidence(code, phrase, signatures.isValidated(code))
        }
        for (code in supporting) {
            signatures.match(code, texts)?.let { found += ScreenEvidence(code, it, signatures.isValidated(code)) }
        }
        return ScreenDetection(type, ClassifierPolicy.confidenceFor(found.size), found)
    }
}

/** The offer card, read exactly as OfferParser reads it: accept action, price and pickup. */
class OfferScreenDetector(private val signatures: ScreenSignatures) : ScreenDetector {

    override val type = UberScreenType.OFFER

    override fun evaluate(snapshot: ScreenSnapshot): ScreenDetection? {
        val accept = signatures.match(EvidenceCode.OFFER_ACCEPT_ACTION, snapshot.normalizedTexts) ?: return null
        val raw = snapshot.texts.joinToString(" ")
        if (OfferPatterns.PRICE.find(raw) == null || OfferPatterns.PICKUP.find(raw) == null) return null
        val evidence = listOf(
            ScreenEvidence(EvidenceCode.OFFER_ACCEPT_ACTION, accept, signatures.isValidated(EvidenceCode.OFFER_ACCEPT_ACTION)),
            ScreenEvidence(EvidenceCode.OFFER_PRICE, "precio", signatures.isValidated(EvidenceCode.OFFER_PRICE)),
            ScreenEvidence(EvidenceCode.OFFER_PICKUP_ETA, "recogida", signatures.isValidated(EvidenceCode.OFFER_PICKUP_ETA))
        )
        return ScreenDetection(type, ClassifierPolicy.confidenceFor(evidence.size), evidence)
    }
}

/**
 * Snapshot in, explained classification out. Snapshots from other apps are UNKNOWN. When two
 * detections are closer than [ClassifierPolicy.AMBIGUITY_MARGIN] the answer is AMBIGUOUS rather
 * than a guess. A detector that throws is reported and skipped; it never stops the others.
 */
class UberScreenClassifier(
    val targetPackage: String,
    val signatures: ScreenSignatures,
    private val detectors: List<ScreenDetector>
) {

    fun classify(snapshot: ScreenSnapshot): ScreenClassification {
        if (snapshot.packageName != targetPackage || snapshot.nodes.isEmpty()) return ScreenClassification.UNKNOWN
        val failed = ArrayList<UberScreenType>()
        val found = detectors.mapNotNull { detector ->
            try {
                detector.evaluate(snapshot)
            } catch (e: RuntimeException) {
                // A broken rule must not take the service down; it is reported in the result
                failed += detector.type
                null
            }
        }.sortedByDescending { it.confidence }

        val best = found.firstOrNull() ?: return ScreenClassification.UNKNOWN.copy(failedDetectors = failed)
        val second = found.getOrNull(1)
        if (second != null && best.confidence - second.confidence < ClassifierPolicy.AMBIGUITY_MARGIN) {
            return ScreenClassification(
                type = UberScreenType.AMBIGUOUS,
                confidence = second.confidence,
                evidence = emptyList(),
                candidates = found.takeWhile { best.confidence - it.confidence < ClassifierPolicy.AMBIGUITY_MARGIN },
                failedDetectors = failed
            )
        }
        return ScreenClassification(best.type, best.confidence, best.evidence, failedDetectors = failed)
    }

    companion object {
        /** The Spanish rule set. Each rule needs at least two signals to reach MEDIUM confidence. */
        fun spanish(targetPackage: String, signatures: ScreenSignatures = ScreenSignatures.SPANISH) =
            UberScreenClassifier(
                targetPackage = targetPackage,
                signatures = signatures,
                detectors = listOf(
                    OfferScreenDetector(signatures),
                    SignatureDetector(
                        UberScreenType.SEARCHING, signatures,
                        required = listOf(EvidenceCode.SEARCHING_STATUS),
                        supporting = listOf(EvidenceCode.ONLINE_TOGGLE),
                        excluded = listOf(EvidenceCode.START_TRIP_ACTION, EvidenceCode.END_TRIP_ACTION)
                    ),
                    SignatureDetector(
                        UberScreenType.PICKUP_NAVIGATION, signatures,
                        required = listOf(EvidenceCode.PICKUP_HEADER),
                        supporting = listOf(EvidenceCode.ARRIVED_ACTION),
                        excluded = listOf(EvidenceCode.START_TRIP_ACTION, EvidenceCode.END_TRIP_ACTION)
                    ),
                    SignatureDetector(
                        UberScreenType.ARRIVED_OR_WAITING, signatures,
                        required = listOf(EvidenceCode.START_TRIP_ACTION),
                        supporting = listOf(EvidenceCode.WAITING_STATUS, EvidenceCode.PICKUP_HEADER),
                        excluded = listOf(EvidenceCode.END_TRIP_ACTION)
                    ),
                    SignatureDetector(
                        UberScreenType.IN_TRIP, signatures,
                        required = listOf(EvidenceCode.END_TRIP_ACTION),
                        supporting = listOf(EvidenceCode.DROPOFF_HEADER),
                        excluded = listOf(EvidenceCode.START_TRIP_ACTION, EvidenceCode.TRIP_SUMMARY)
                    ),
                    SignatureDetector(
                        UberScreenType.TRIP_SUMMARY, signatures,
                        required = listOf(EvidenceCode.TRIP_SUMMARY),
                        supporting = listOf(EvidenceCode.RATE_RIDER)
                    ),
                    SignatureDetector(
                        UberScreenType.CANCELLED, signatures,
                        required = listOf(EvidenceCode.TRIP_CANCELLED),
                        supporting = listOf(EvidenceCode.CANCEL_ACKNOWLEDGE)
                    )
                )
            )
    }
}
