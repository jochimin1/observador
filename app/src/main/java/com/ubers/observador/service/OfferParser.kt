package com.ubers.observador.service

import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.domain.lifecycle.OfferPatterns
import com.ubers.observador.domain.models.UberOffer

/**
 * Reads an offer card from the screen's texts (ScreenSnapshot.texts: each node's text, then its
 * description, depth-first). Same rules as always; the patterns now live in [OfferPatterns] so the
 * ride lifecycle recognises offers exactly the same way.
 */
object OfferParser {

    private const val TAG = "OfferParser"

    fun parse(texts: List<String>): UberOffer? {
        val fullText = texts.joinToString(" ")
        // Screen text can hold names and addresses: only debug builds may print it
        debug { "Collected texts: $fullText" }

        val hasAcceptButton = texts.any { text ->
            OfferPatterns.ACCEPT_KEYWORDS.any { keyword -> text.lowercase().contains(keyword) }
        }
        if (!hasAcceptButton) {
            debug { "No accept button found" }
            return null
        }

        val price = extractPrice(fullText)
        if (price == null) {
            debug { "Price not found in: $fullText" }
            return null
        }

        val pickupMatch = extractPickup(fullText)
        if (pickupMatch == null) {
            debug { "Pickup not found in: $fullText" }
            return null
        }

        val tripMatch = extractTrip(fullText, pickupMatch)
        if (tripMatch == null) {
            debug { "Trip not found in: $fullText" }
            return null
        }

        Log.d(TAG, "Parsed offer: price=$price pickup=$pickupMatch trip=$tripMatch")
        return UberOffer(
            price = price,
            pickupKm = pickupMatch.second,
            pickupMin = pickupMatch.first,
            tripKm = tripMatch.second,
            tripMin = tripMatch.first
        )
    }

    private inline fun debug(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    private fun extractPrice(text: String): Double? {
        val match = OfferPatterns.PRICE.find(text) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }

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
