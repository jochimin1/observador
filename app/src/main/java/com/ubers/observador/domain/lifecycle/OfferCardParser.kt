package com.ubers.observador.domain.lifecycle

import com.ubers.observador.domain.models.UberOffer

/**
 * Reads an offer card from the screen's texts (ScreenSnapshot.texts: each node's text, then its
 * description, depth-first). Same rules as always; the patterns now live in [OfferPatterns] so the
 * ride lifecycle recognises offers exactly the same way.
 */
object OfferCardParser {
    /**
     * Every offer card on the screen, top first, each with its addresses (Fase 8I: the Radar shows
     * several). [parse] reads the numerical fields; this also reads the places and header.
     */
    fun parseAll(texts: List<String>): List<UberOffer> =
        OfferCards.cards(texts).mapNotNull { c ->
            val card = c.texts
            parse(card)?.let { o ->
                val places = OfferPlacesReader.read(card)
                val product = OfferProductReader.read(c.header)
                o.copy(
                    pickupAddress = places.pickup,
                    dropoffAddress = places.dropoff,
                    cardAction = OfferCardAction.of(card),
                    product = product.product,
                    exclusive = product.exclusive
                )
            }
        }

    fun parse(texts: List<String>): UberOffer? {
        val fullText = texts.joinToString(" ")
        val hasAcceptButton = texts.any { text ->
            OfferPatterns.ACCEPT_KEYWORDS.any { keyword -> text.lowercase().contains(keyword) }
        }
        if (!hasAcceptButton) {
            return null
        }

        val price = extractPrice(fullText)
        if (price == null) {
            return null
        }

        val pickupMatch = extractPickup(fullText)
        if (pickupMatch == null) {
            return null
        }

        val tripMatch = extractTrip(fullText, pickupMatch)
        if (tripMatch == null) {
            return null
        }

        return UberOffer(
            price = price,
            pickupKm = pickupMatch.second,
            pickupMin = pickupMatch.first,
            tripKm = tripMatch.second,
            tripMin = tripMatch.first
        )
    }


    private fun extractPrice(text: String): Double? = OfferPatterns.fareValue(text)

    private fun extractPickup(text: String): Pair<Int, Double>? {
        val match = OfferPatterns.PICKUP.find(text) ?: return null
        val min = match.groupValues[1].toIntOrNull() ?: return null
        val km = match.groupValues[2].toDoubleOrNull() ?: return null
        return Pair(min, km)
    }

    private fun extractTrip(text: String, pickup: Pair<Int, Double>): Pair<Int, Double>? {
        val allMatches = OfferPatterns.TRIP_TIME_KM.findAll(text).toList()
        val tripMatch = allMatches.firstOrNull { match ->
            val min = match.groupValues[1].toIntOrNull() ?: return@firstOrNull false
            val km = match.groupValues[2].toDoubleOrNull() ?: return@firstOrNull false
            !(min == pickup.first && km == pickup.second)
        }
        if (tripMatch != null) {
            val min = tripMatch.groupValues[1].toIntOrNull() ?: return null
            val km = tripMatch.groupValues[2].toDoubleOrNull() ?: return null
            return Pair(min, km)
        }

        val kmTimeMatch = OfferPatterns.TRIP_KM_TIME.find(text) ?: return null
        val km = kmTimeMatch.groupValues[1].toDoubleOrNull() ?: return null
        val min = kmTimeMatch.groupValues[2].toIntOrNull() ?: return null
        return Pair(min, km)
    }
}
