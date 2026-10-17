package com.ubers.observador.domain.lifecycle

/**
 * Fase 8F (decision of 14/10: the driver chose to keep the pickup and drop-off addresses and the
 * rider's name with each trip). Pure readers, tested on the JVM. The address rule is a candidate
 * until a real offer card confirms it: Uber's card is read as price, product, rating, the pickup
 * time and distance, the pickup address, the trip time and distance, the drop-off address and
 * "Aceptar". Coordinates are never read or stored.
 */

/** Where reading an offer card stopped, to explain on the debug panel why no card was shown. */
enum class OfferParseStep(val label: String) {
    NO_ACCEPT("Sin botón «Aceptar» ni «Me interesa»"),
    NO_PRICE("No se encontró el precio"),
    NO_PICKUP("No se encontró la recogida (min y km)"),
    NO_TRIP("No se encontró el viaje (min y km)"),
    OK("Leída")
}

object OfferParseCheck {

    /** The same checks, in the same order, as the offer parser. */
    fun check(texts: List<String>): OfferParseStep {
        if (!hasAccept(texts)) return OfferParseStep.NO_ACCEPT
        val full = texts.joinToString(" ")
        if (OfferPatterns.PRICE.find(full) == null) return OfferParseStep.NO_PRICE
        val pickup = OfferPatterns.PICKUP.find(full) ?: return OfferParseStep.NO_PICKUP
        val pickupKey = pickup.groupValues[1] to pickup.groupValues[2]
        val trip = OfferPatterns.TRIP_TIME_KM.findAll(full).any { (it.groupValues[1] to it.groupValues[2]) != pickupKey } ||
            OfferPatterns.TRIP_KM_TIME.find(full) != null
        return if (trip) OfferParseStep.OK else OfferParseStep.NO_TRIP
    }

    fun hasAccept(texts: List<String>): Boolean =
        texts.any { t -> OfferPatterns.ACCEPT_KEYWORDS.any { t.lowercase().contains(it) } }
}

/**
 * Fase 8J: the button on the offer card. "Me interesa" is the Radar's: pressing it only says the
 * driver wants the trip, and Uber may give it to someone else, so the offer stays an offer until
 * "Llegar por …" shows it was given. "Aceptar" is the card that takes the trip at once (it also
 * appears on the "Exclusivo" cards). Stored by [storageKey]; null on rows older than DB 12.
 */
enum class OfferCardAction(val storageKey: String, val label: String) {
    INTEREST("INTEREST", "Me interesa"),
    ACCEPT("ACCEPT", "Aceptar");

    companion object {
        fun fromStorage(key: String?): OfferCardAction? = entries.firstOrNull { it.storageKey == key }

        /** The button of one card: "Me interesa" wins if both appear (a Radar card never says Aceptar). */
        fun of(card: List<String>): OfferCardAction? {
            val norm = card.map(TextNormalizer::normalize)
            return when {
                norm.any { it.contains("me interesa") } -> INTEREST
                norm.any { t -> listOf("aceptar", "accept", "aceitar").any { t.contains(it) } } -> ACCEPT
                else -> null
            }
        }
    }
}

/**
 * Uber's "Radar de solicitud de viaje" lists several offer cards on one screen, each with its own
 * price and "Me interesa" button. The texts are cut at every price: each piece is one card, read
 * on its own. Text before the first price (a half-scrolled card) has no price and is left out.
 */
object OfferCards {

    /**
     * One card: [texts] from its price to the next card's price, and [header], the labels Uber
     * writes above the price ("Moto", "Exclusivo"). The header of a card sits after the previous
     * card's button (or at the top of the screen for the first one).
     */
    data class Card(val texts: List<String>, val header: List<String>)

    fun segments(texts: List<String>): List<List<String>> = cards(texts).map { it.texts }

    fun cards(texts: List<String>): List<Card> {
        val starts = texts.indices.filter { OfferPatterns.PRICE.containsMatchIn(texts[it]) }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { n, from ->
            val until = starts.getOrNull(n + 1) ?: texts.size
            val headerFrom = if (n == 0) 0 else {
                val prev = starts[n - 1]
                val button = (prev until from).lastOrNull { i -> isButton(texts[i]) }
                (button ?: prev) + 1
            }
            Card(texts.subList(from, until), texts.subList(headerFrom, from))
        }.filter { OfferParseCheck.check(it.texts) == OfferParseStep.OK }
    }

    private fun isButton(text: String): Boolean = text.lowercase().let { t -> OfferPatterns.ACCEPT_KEYWORDS.any { t.contains(it) } }
}

/** Fase 8J: the modality Uber prints above the price ("Moto") and whether the card is "Exclusivo". */
data class OfferProduct(val product: String?, val exclusive: Boolean)

/**
 * Reads [OfferProduct] from a card's header. Known modalities are compared normalized and kept
 * as Uber wrote them; an unknown short label right above the price is kept too, as it is, so a
 * new modality is never lost. Confirmed on real cards (17/10): "Moto" and "Exclusivo".
 */
object OfferProductReader {

    /** Normalized names of Uber's modalities; the first ones are confirmed, the rest candidates. */
    val KNOWN = listOf(
        "moto", "uber moto", "moto flash", "flash", "uberx", "uber x", "comfort", "xl", "black", "envios", "flash moto", "taxi"
    )
    const val EXCLUSIVE = "exclusivo"

    /** Header texts that are never a modality. */
    private val NOT_PRODUCT = setOf(EXCLUSIVE, "radar de solicitud de viaje", "cerrar", "x", "nuevo", "rechazar", "descartar", "menu")

    fun read(header: List<String>): OfferProduct {
        val norm = header.map { TextNormalizer.normalize(it).trim() }
        val exclusive = norm.any { it == EXCLUSIVE }
        val known = header.indices.firstOrNull { norm[it] in KNOWN }?.let { header[it].trim() }
        val fallback = header.indices.reversed().firstOrNull { i ->
            val n = norm[i]
            n.isNotEmpty() && n.length <= 20 && n !in NOT_PRODUCT && n.none { it.isDigit() } && n.any { it.isLetter() }
        }?.let { header[it].trim() }
        return OfferProduct(known ?: fallback, exclusive)
    }
}

/** The two addresses of an offer card, as Uber wrote them. */
data class OfferPlaces(val pickup: String?, val dropoff: String?) {
    val isEmpty: Boolean get() = pickup == null && dropoff == null
}

object OfferPlacesReader {

    const val MAX_LENGTH = 120

    private val RATING = Regex("""^\d[.,]\d{1,2}\s*★?$""")
    private val NUMBERISH = Regex("""^[\d\s.,:()+\-·•★%$]+$""")

    /** Short labels of the card that are never an address (compared normalized). */
    private val NOT_PLACES = setOf(
        "aceptar", "accept", "rechazar", "moto", "uber moto", "ubermoto", "flash", "uberx", "uber x", "exclusivo",
        "nuevo", "viaje", "recogida", "destino", "verificado", "prima", "tarifa", "incluye", "pago en efectivo",
        "efectivo", "tarjeta", "pago con tarjeta", "x", "cerrar"
    )

    fun read(texts: List<String>): OfferPlaces {
        val pickupAt = texts.indexOfFirst { OfferPatterns.PICKUP.containsMatchIn(it) }
        if (pickupAt < 0) return OfferPlaces(null, null)
        val pickupKey = OfferPatterns.PICKUP.find(texts[pickupAt])!!.let { it.groupValues[1] to it.groupValues[2] }
        val tripAt = (pickupAt + 1 until texts.size).firstOrNull { i ->
            OfferPatterns.TRIP_TIME_KM.findAll(texts[i]).any { (it.groupValues[1] to it.groupValues[2]) != pickupKey } ||
                OfferPatterns.TRIP_KM_TIME.containsMatchIn(texts[i])
        }
        val acceptAt = texts.indexOfFirst { t -> OfferPatterns.ACCEPT_KEYWORDS.any { t.lowercase().contains(it) } }
            .takeIf { it > pickupAt } ?: texts.size
        // An address may share the node with its time ("A 7 min (2.3 km) · Calle X")
        val pickup = tail(texts[pickupAt], OfferPatterns.PICKUP)
            ?: firstPlace(texts, pickupAt + 1, tripAt ?: acceptAt)
        val dropoff = tripAt?.let { i ->
            tail(texts[i], OfferPatterns.TRIP_TIME_KM) ?: tail(texts[i], OfferPatterns.TRIP_KM_TIME) ?: firstPlace(texts, i + 1, acceptAt)
        }
        return OfferPlaces(pickup, dropoff?.takeIf { it != pickup })
    }

    /** Text after the time and distance in the same node, when it reads like a place. */
    private fun tail(text: String, pattern: Regex): String? {
        val m = pattern.find(text) ?: return null
        val rest = text.substring(m.range.last + 1).trim().trimStart(')', '·', '•', '-', ',', ':', ' ').trim()
        return rest.takeIf { isPlace(it) }?.let(::clean)
    }

    private fun firstPlace(texts: List<String>, from: Int, until: Int): String? =
        (from until until.coerceAtMost(texts.size)).asSequence().map { texts[it] }.firstOrNull { isPlace(it) }?.let(::clean)

    fun isPlace(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        if (t.none { it.isLetter() }) return false
        if (NUMBERISH.matches(t) || RATING.matches(t)) return false
        if (OfferPatterns.PRICE.containsMatchIn(t) || OfferPatterns.PICKUP.containsMatchIn(t) ||
            OfferPatterns.TRIP_TIME_KM.containsMatchIn(t) || OfferPatterns.TRIP_KM_TIME.containsMatchIn(t)
        ) return false
        val n = TextNormalizer.normalize(t)
        if (n in NOT_PLACES) return false
        if (OfferPatterns.ACCEPT_KEYWORDS.any { n.contains(it) }) return false
        return true
    }

    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH)
}

/**
 * The rider's first name as Uber shows it once the trip is accepted ("Llegar por stefany" is
 * confirmed with a real capture; the other phrases are candidates). Only the name Uber prints.
 */
object RiderNameReader {

    const val MAX_LENGTH = 40

    /** Normalized prefixes; the name is the rest of the original text. */
    val PREFIXES = listOf("llegar por ", "recoger a ", "recogiendo a ", "esperando a ", "dejar a ", "dejando a ", "viaje con ")

    /** Validated with a real Uber capture (14/10/2026). */
    val VALIDATED = setOf("llegar por ")

    fun read(texts: List<String>): String? {
        texts.forEach { raw ->
            val text = raw.trim()
            val n = TextNormalizer.normalize(text)
            val prefix = PREFIXES.firstOrNull { n.startsWith(it) } ?: return@forEach
            // Normalizing only drops accents and extra spaces, so the prefix has the same number of words
            val words = text.split(Regex("\\s+"))
            val prefixWords = prefix.trim().split(' ').size
            if (words.size <= prefixWords) return@forEach
            val name = words.drop(prefixWords).joinToString(" ").trim().trimEnd('.', ',', '!', ':')
            if (isName(name)) return name.take(MAX_LENGTH)
        }
        return null
    }

    private fun isName(name: String): Boolean =
        name.length in 2..MAX_LENGTH && name.none { it.isDigit() } && name.split(' ').size <= 4 && name.any { it.isLetter() }
}

/**
 * The full reading of a screen for the debug panel's copy (decision of 14/10: names and
 * addresses may be shown). One line per node with text: position, view id, text and description.
 */
object ScreenDump {
    fun full(snapshot: ScreenSnapshot, title: String): String {
        val sb = StringBuilder()
        sb.append("Observador · ").append(title).append('\n')
        sb.append("Paquete: ").append(snapshot.packageName).append(" · nodos: ").append(snapshot.nodes.size).append('\n')
        snapshot.nodes.forEachIndexed { i, n ->
            val text = n.text?.trim().orEmpty()
            val desc = n.contentDescription?.trim().orEmpty()
            if (text.isEmpty() && desc.isEmpty()) return@forEachIndexed
            sb.append('#').append(i)
            n.viewId?.substringAfter(":id/")?.let { sb.append(" [").append(it).append(']') }
            if (n.clickable) sb.append(" (toca)")
            sb.append(": ").append(text)
            if (desc.isNotEmpty() && desc != text) sb.append(" | ").append(desc)
            sb.append('\n')
        }
        return sb.toString()
    }
}
