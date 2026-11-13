package com.ubers.observador.domain.lifecycle

/** Versions identify the rules, not the installed build. No screen text is retained. */
object ReadingVersions {
    const val PARSER = "OFFER_PARSER_1_38_0"
    const val CLASSIFIER = "SCREEN_CLASSIFIER_1_38_0"
}

data class OfferReadTrace(
    val parserVersion: String = ReadingVersions.PARSER,
    val classifierVersion: String = ReadingVersions.CLASSIFIER,
    val uberVersion: String? = null,
    val readingId: String? = null,
    val confidence: Double = 0.90,
    val reasons: String = ""
)

enum class ReadingProblem(val label: String) {
    NO_ACCEPT("Sin botón de oferta"), NO_PRICE("Falta el precio total"),
    MULTIPLE_PRICES("Hay varios precios totales posibles"),
    NO_PICKUP("Faltan los minutos o kilómetros de recogida"),
    MULTIPLE_PICKUPS("La recogida es ambigua"),
    NO_TRIP("Faltan los minutos o kilómetros del viaje"),
    MULTIPLE_TRIPS("El recorrido del viaje es ambiguo"),
    INVALID_VALUES("Las cifras del recorrido no son válidas")
}

/** A malformed amount is never silently shortened into a plausible fare. */
object ReadingNumbers {
    fun decimal(raw: String): Double? {
        val text = raw.trim().replace('\u00a0', ' ')
        if (!Regex("[0-9]+(?:[., ][0-9]+)*").matches(text)) return null
        val last = text.indexOfLast { it == '.' || it == ',' }
        val fraction = last >= 0 && text.length - last - 1 in 1..2
        val integer = if (fraction) text.substring(0, last) else text
        val groups = integer.split('.', ',', ' ')
        if (groups.size > 1 && (groups.first().length !in 1..3 || groups.drop(1).any { it.length != 3 })) return null
        val separators = integer.filter { it == '.' || it == ',' || it == ' ' }.toSet()
        if (separators.size > 1 || (fraction && text[last] in separators)) return null
        val normalized = groups.joinToString("") + if (fraction) "." + text.substring(last + 1) else ""
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it <= 10_000_000.0 }
    }
}
