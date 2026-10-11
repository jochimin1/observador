package com.ubers.observador.domain

/**
 * The three verdicts shown for an offer. Label and colors live here so the overlay, the
 * history list, the notification and the settings preview can never disagree.
 *
 * Colors are ARGB ints (no Android dependency). Text on each background keeps at least
 * 4.5:1 contrast. The label is always shown next to the color: red and green are hard to
 * tell apart for color-blind drivers, and glare washes colors out.
 */
enum class OfferQuality(
    val label: String,
    val backgroundArgb: Int,
    val foregroundArgb: Int
) {
    BAD("MALO", 0xFFC62828.toInt(), 0xFFFFFFFF.toInt()),
    GOOD("BUENO", 0xFFF9A825.toInt(), 0xFF1B1B1B.toInt()),
    EXCELLENT("EXCELENTE", 0xFF2E7D32.toInt(), 0xFFFFFFFF.toInt())
}

/** Score cut-offs: below [goodFrom] is BAD, from [excellentFrom] up is EXCELLENT. */
data class QualityThresholds(
    val goodFrom: Int = 60,
    val excellentFrom: Int = 80
) {
    fun classify(score: Int): OfferQuality = when {
        score >= excellentFrom -> OfferQuality.EXCELLENT
        score >= goodFrom -> OfferQuality.GOOD
        else -> OfferQuality.BAD
    }

    companion object {
        /** EXCELLENT must sit above GOOD, so values that cross are pushed apart. */
        fun of(goodFrom: Int, excellentFrom: Int) =
            QualityThresholds(goodFrom, maxOf(excellentFrom, goodFrom + 1))
    }
}
