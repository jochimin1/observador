package com.ubers.observador.domain.lifecycle

import com.ubers.observador.domain.models.UberOffer

data class CardReading(val offer: UberOffer?, val problems: List<ReadingProblem>) {
    val accepted: Boolean get() = offer != null && problems.isEmpty()
    val step: OfferParseStep get() = when (problems.firstOrNull()) {
        ReadingProblem.NO_ACCEPT -> OfferParseStep.NO_ACCEPT
        ReadingProblem.NO_PRICE -> OfferParseStep.NO_PRICE
        ReadingProblem.NO_PICKUP -> OfferParseStep.NO_PICKUP
        ReadingProblem.NO_TRIP -> OfferParseStep.NO_TRIP
        ReadingProblem.INVALID_VALUES -> OfferParseStep.INVALID_VALUES
        null -> OfferParseStep.OK
        else -> OfferParseStep.AMBIGUOUS
    }
}

data class ScreenOfferReading(val cards: List<CardReading>) {
    val offers: List<UberOffer> get() = cards.mapNotNull { it.offer }
    val rejected: List<CardReading> get() = cards.filterNot { it.accepted }
    val problems: List<ReadingProblem> get() = rejected.flatMap { it.problems }.distinct()
}

/** Observation, lifecycle and close verification share the same conservative parsing rules. */
object OfferCardParser {
    fun parseAll(texts: List<String>): List<UberOffer> = diagnoseAll(texts).offers
    fun parse(texts: List<String>): UberOffer? = diagnose(texts).offer

    fun diagnoseAll(texts: List<String>): ScreenOfferReading = ScreenOfferReading(
        OfferCards.rawCards(texts).map { card ->
            val result = diagnose(card.texts)
            if (!result.accepted) result else {
                val places = OfferPlacesReader.read(card.texts)
                val product = OfferProductReader.read(card.header)
                result.copy(offer = result.offer!!.copy(
                    pickupAddress = places.pickup, dropoffAddress = places.dropoff,
                    cardAction = OfferCardAction.of(card.texts), product = product.product,
                    exclusive = product.exclusive
                ))
            }
        }
    )

    fun diagnose(texts: List<String>): CardReading {
        fun fail(problem: ReadingProblem) = CardReading(null, listOf(problem))
        if (!OfferParseCheck.hasAccept(texts)) return fail(ReadingProblem.NO_ACCEPT)
        // '\n' between nodes: \s still spans them (\u00abDOP\u00bb / \u00ab136.06\u00bb), the thousands space does not
        val full = texts.joinToString("\n").replace(' ', ' ')
        val candidates = OfferPatterns.fares(full)
        if (candidates.any { ReadingNumbers.decimal(it.groupValues[1]) == null }) return fail(ReadingProblem.INVALID_VALUES)
        val fares = candidates.mapNotNull { ReadingNumbers.decimal(it.groupValues[1]) }.distinct()
        if (fares.isEmpty()) return fail(ReadingProblem.NO_PRICE)
        if (fares.size != 1) return fail(ReadingProblem.MULTIPLE_PRICES)
        val pickupMatches = OfferPatterns.PICKUP.findAll(full).toList()
        val pickups = pickupMatches.mapNotNull { measures(it, false) }.distinct()
        if (pickups.isEmpty()) return fail(ReadingProblem.NO_PICKUP)
        if (pickups.size != 1) return fail(ReadingProblem.MULTIPLE_PICKUPS)
        // Positions distinguish pickup from trip even when their numerical values are equal.
        val trips = (OfferPatterns.TRIP_TIME_KM.findAll(full).filter { trip ->
            pickupMatches.none { trip.range.first >= it.range.first && trip.range.last <= it.range.last }
        }.mapNotNull { measures(it, false) } +
            OfferPatterns.TRIP_KM_TIME.findAll(full).mapNotNull { measures(it, true) }).distinct().toList()
        if (trips.isEmpty()) return fail(ReadingProblem.NO_TRIP)
        if (trips.size != 1) return fail(ReadingProblem.MULTIPLE_TRIPS)
        val pickup = pickups.single(); val trip = trips.single(); val price = fares.single()
        if (price <= 0 || pickup.first < 0 || trip.first <= 0 || pickup.second < 0 || trip.second <= 0 ||
            pickup.first > 1_440 || trip.first > 1_440 || pickup.second > 2_000 || trip.second > 2_000) {
            return fail(ReadingProblem.INVALID_VALUES)
        }
        return CardReading(UberOffer(
            price, pickup.second, pickup.first, trip.second, trip.first,
            readingTrace = OfferReadTrace()
        ), emptyList())
    }

    private fun measures(match: MatchResult, reversed: Boolean): Pair<Int, Double>? {
        val minutes = match.groupValues[if (reversed) 2 else 1].toIntOrNull() ?: return null
        val km = ReadingNumbers.decimal(match.groupValues[if (reversed) 1 else 2]) ?: return null
        return minutes to km
    }
}
