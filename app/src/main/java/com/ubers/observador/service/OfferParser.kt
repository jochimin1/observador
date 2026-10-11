package com.ubers.observador.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ubers.observador.domain.models.UberOffer

object OfferParser {

    private const val TAG = "OfferParser"

    // "DOP136.06" or "DOP 136.06" or "RD$136.06"
    private val PRICE_REGEX = Regex("""(?:DOP|RD\$)\s*([\d,]+\.?\d*)""")

    // Pickup: "A 7 min (4.1 km)" | "En 7 min (4.1 km)" | "A 7 min · 4.1 km"
    private val PICKUP_REGEX = Regex(
        """(?:A|En)\s+(\d+)\s*min\s*[\(·•]\s*([\d.]+)\s*km"""
    )

    // Trip: "11 min (7.2 km)" | "Viaje: 11 min (7.2 km)"
    private val TRIP_TIME_KM_REGEX = Regex(
        """(?:Viaje:?\s+)?(\d+)\s*min\s*[\(·•]\s*([\d.]+)\s*km"""
    )
    private val TRIP_KM_TIME_REGEX = Regex(
        """([\d.]+)\s*km\s*[·•]\s*(\d+)\s*min"""
    )

    // Partial match — covers "ACEPTAR", "Aceptar viaje", "Accept trip", etc.
    private val ACCEPT_KEYWORDS = listOf("aceptar", "accept", "aceitar")

    fun parse(rootNode: AccessibilityNodeInfo): UberOffer? {
        val texts = mutableListOf<String>()
        collectTexts(rootNode, texts)

        val fullText = texts.joinToString(" ")
        Log.d(TAG, "Collected texts: $fullText")

        val hasAcceptButton = texts.any { text ->
            ACCEPT_KEYWORDS.any { keyword -> text.lowercase().contains(keyword) }
        }
        if (!hasAcceptButton) {
            Log.d(TAG, "No accept button found")
            return null
        }

        val price = extractPrice(fullText)
        if (price == null) {
            Log.d(TAG, "Price not found in: $fullText")
            return null
        }

        val pickupMatch = extractPickup(fullText)
        if (pickupMatch == null) {
            Log.d(TAG, "Pickup not found in: $fullText")
            return null
        }

        val tripMatch = extractTrip(fullText, pickupMatch)
        if (tripMatch == null) {
            Log.d(TAG, "Trip not found in: $fullText")
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

    private fun extractPrice(text: String): Double? {
        val match = PRICE_REGEX.find(text) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    private fun extractPickup(text: String): Pair<Int, Double>? {
        val match = PICKUP_REGEX.find(text) ?: return null
        val min = match.groupValues[1].toIntOrNull() ?: return null
        val km = match.groupValues[2].toDoubleOrNull() ?: return null
        return Pair(min, km)
    }

    private fun extractTrip(text: String, pickup: Pair<Int, Double>): Pair<Int, Double>? {
        val allMatches = TRIP_TIME_KM_REGEX.findAll(text).toList()
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

        val kmTimeMatch = TRIP_KM_TIME_REGEX.find(text) ?: return null
        val km = kmTimeMatch.groupValues[1].toDoubleOrNull() ?: return null
        val min = kmTimeMatch.groupValues[2].toIntOrNull() ?: return null
        return Pair(min, km)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, result) }
        }
    }
}
