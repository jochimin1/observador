package com.ubers.observador.domain.lifecycle

/**
 * Why a screen was recognised. Logs, tests and the debug panel use these codes instead of the
 * text on screen, so nothing personal is copied and another language only needs new phrases.
 */
enum class EvidenceCode(val description: String) {
    OFFER_ACCEPT_ACTION("botón de aceptar la oferta"),
    OFFER_PRICE("precio de la oferta"),
    OFFER_PICKUP_ETA("tiempo y distancia de recogida"),
    SEARCHING_STATUS("estado buscando viajes"),
    ONLINE_TOGGLE("control para desconectarse"),
    PICKUP_HEADER("encabezado de ir a recoger"),
    ARRIVED_ACTION("acción de avisar la llegada"),
    WAITING_STATUS("esperando al pasajero"),
    START_TRIP_ACTION("acción de iniciar el viaje"),
    END_TRIP_ACTION("acción de finalizar el viaje"),
    DROPOFF_HEADER("encabezado de dejar al pasajero"),
    TRIP_SUMMARY("resumen del viaje terminado"),
    RATE_RIDER("calificar al pasajero"),
    TRIP_CANCELLED("aviso de viaje cancelado"),
    CANCEL_ACKNOWLEDGE("botón de enterado tras cancelar")
}

/**
 * The regular expressions that read an offer card. They come from OfferParser, which has read
 * real Uber cards since 1.0.0; OfferParser and the offer detector now share this one copy.
 */
object OfferPatterns {
    // "DOP136.06" or "DOP 136.06" or "RD$136.06"
    val PRICE = Regex("""(?:DOP|RD\$)\s*([\d,]+\.?\d*)""")

    // Pickup: "A 7 min (4.1 km)" | "En 7 min (4.1 km)" | "A 7 min · 4.1 km"
    val PICKUP = Regex("""(?:A|En)\s+(\d+)\s*min\s*[\(·•]\s*([\d.]+)\s*km""")

    // Trip: "11 min (7.2 km)" | "Viaje: 11 min (7.2 km)"
    val TRIP_TIME_KM = Regex("""(?:Viaje:?\s+)?(\d+)\s*min\s*[\(·•]\s*([\d.]+)\s*km""")
    val TRIP_KM_TIME = Regex("""([\d.]+)\s*km\s*[·•]\s*(\d+)\s*min""")

    // Partial match: "ACEPTAR", "Aceptar viaje", "Accept trip"... and "Me interesa", the button of
    // the cards in Uber's "Radar de solicitud de viaje" (screenshots of 17/10)
    val ACCEPT_KEYWORDS = listOf("aceptar", "accept", "aceitar", "me interesa")
}

/**
 * Phrases that reveal each [EvidenceCode] in one language, compared normalized and as part of a
 * longer label. [validated] lists the codes checked against real Uber screens; every other
 * phrase is a candidate until a real capture confirms it, and the classification says so.
 */
data class ScreenSignatures(
    val language: String,
    val phrases: Map<EvidenceCode, List<String>>,
    val validated: Set<EvidenceCode>,
    /** Single phrases confirmed on a real screen while the rest of their code is still a guess. */
    val validatedPhrases: Map<EvidenceCode, Set<String>> = emptyMap()
) {
    private val normalized: Map<EvidenceCode, List<String>> =
        phrases.mapValues { (_, list) -> list.map(TextNormalizer::normalize).filter { it.isNotEmpty() } }

    fun isValidated(code: EvidenceCode): Boolean = code in validated

    private val validatedNormalized: Map<EvidenceCode, Set<String>> =
        validatedPhrases.mapValues { (_, set) -> set.map(TextNormalizer::normalize).toSet() }

    /** True when [code] is confirmed as a whole, or the very phrase that matched was seen on a real screen. */
    fun isValidatedMatch(code: EvidenceCode, matchedPhrase: String): Boolean =
        code in validated || matchedPhrase in validatedNormalized[code].orEmpty()

    /** The phrase of [code] found in one of [normalizedTexts], or null. */
    fun match(code: EvidenceCode, normalizedTexts: List<String>): String? =
        normalized[code].orEmpty().firstOrNull { phrase -> normalizedTexts.any { it.contains(phrase) } }

    /** The longest known phrase inside [normalizedText], or null: that part is Uber's, not the rider's. */
    fun knownPhraseIn(normalizedText: String): String? =
        normalized.values.flatten().filter { normalizedText.contains(it) }.maxByOrNull { it.length }

    companion object {
        /**
         * Spanish, as Observador's drivers use Uber Driver. Only the offer codes are confirmed;
         * the rest are PENDING VALIDATION WITH REAL CAPTURES and will be replaced by what the
         * debug panel's sanitized snapshot shows on a real trip.
         */
        val SPANISH = ScreenSignatures(
            language = "es",
            phrases = mapOf(
                EvidenceCode.OFFER_ACCEPT_ACTION to OfferPatterns.ACCEPT_KEYWORDS,
                EvidenceCode.SEARCHING_STATUS to listOf("buscando viajes", "estas conectado", "estas en linea"),
                EvidenceCode.ONLINE_TOGGLE to listOf("desconectarte", "desconectarse", "dejar de recibir"),
                // "Llegar por <nombre>" is the title Uber showed after accepting (screenshot of 14/10)
                EvidenceCode.PICKUP_HEADER to listOf("llegar por", "recoger a", "recogida en", "ir a recoger"),
                EvidenceCode.ARRIVED_ACTION to listOf("llegue", "he llegado", "avisar llegada"),
                EvidenceCode.WAITING_STATUS to listOf("esperando a", "esperando al pasajero", "pasajero fue notificado"),
                EvidenceCode.START_TRIP_ACTION to listOf("iniciar viaje", "comenzar viaje", "empezar viaje"),
                EvidenceCode.END_TRIP_ACTION to listOf("finalizar viaje", "terminar viaje", "completar viaje"),
                EvidenceCode.DROPOFF_HEADER to listOf("dejar a", "llevar a", "rumbo al destino"),
                EvidenceCode.TRIP_SUMMARY to listOf("viaje completado", "viaje finalizado", "resumen del viaje"),
                EvidenceCode.RATE_RIDER to listOf("califica a", "calificar al pasajero", "como estuvo"),
                EvidenceCode.TRIP_CANCELLED to listOf("viaje cancelado", "se cancelo el viaje", "cancelo el viaje"),
                EvidenceCode.CANCEL_ACKNOWLEDGE to listOf("entendido")
            ),
            // Price and pickup are matched by OfferPatterns, also confirmed on real cards
            validated = setOf(
                EvidenceCode.OFFER_ACCEPT_ACTION,
                EvidenceCode.OFFER_PRICE,
                EvidenceCode.OFFER_PICKUP_ETA
            ),
            validatedPhrases = mapOf(EvidenceCode.PICKUP_HEADER to setOf("llegar por"))
        )
    }
}
