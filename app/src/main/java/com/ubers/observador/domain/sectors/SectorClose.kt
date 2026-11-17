package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.lifecycle.OfferPatterns
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.TextNormalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * 1.37.0: Uber's X on one offer card. Found by the card's price (never by position, so a card the
 * parser dropped cannot shift which X is touched), between the previous card's button and this
 * card's «Aceptar» / «Me interesa». Exactly one candidate or nothing: an ambiguous screen is refused.
 */
object CloseTarget {

    private val CLOSE_WORDS = listOf("cerrar", "descartar", "rechazar", "ignorar", "omitir", "close", "dismiss", "decline")
    private val ACTION_LABELS = listOf("aceptar", "me interesa", "accept")
    private val NEVER_WORDS = listOf("aceptar", "interesa", "accept", "confirmar", "llegue", "iniciar", "finalizar", "llamar")

    sealed interface Result {
        data class Found(val index: Int, val label: String) : Result
        data class Refused(val reason: String) : Result
    }

    private fun label(n: ScreenNode): String =
        listOfNotNull(n.text, n.contentDescription, n.viewId?.substringAfter(":id/")?.replace('_', ' '))
            .joinToString(" ").let { TextNormalizer.normalize(it) }

    private fun words(n: ScreenNode): Set<String> = label(n).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    private fun isAction(n: ScreenNode): Boolean {
        val t = TextNormalizer.normalize(n.text ?: n.contentDescription ?: return false)
        return ACTION_LABELS.any { t == it || t.startsWith("$it ") }
    }

    private fun isFare(n: ScreenNode): Boolean = n.text?.let { OfferPatterns.fare(it) } != null

    /** The «Aceptar» / «Me interesa» of each card, one per card even when Uber repeats it in a child node. */
    fun actionIndices(nodes: List<ScreenNode>): List<Int> {
        val out = ArrayList<Int>()
        nodes.indices.filter { isAction(nodes[it]) }.forEach { i ->
            val prev = out.lastOrNull()
            if (prev == null || (prev + 1 until i).any { isFare(nodes[it]) }) out += i
        }
        return out
    }

    /** 1.59.0: a node whose words say accept, confirm, call… is never touched, learned or not. */
    fun forbidden(n: ScreenNode): Boolean = words(n).any { word -> NEVER_WORDS.any { word == it } }

    private fun isCloseCandidate(n: ScreenNode): Boolean {
        if (!n.clickable || !n.enabled || !n.visibleToUser) return false
        val w = words(n)
        if (w.any { word -> NEVER_WORDS.any { word == it } }) return false
        return CLOSE_WORDS.any { it in w }
    }

    fun find(snapshot: ScreenSnapshot, price: Double): Result {
        val nodes = snapshot.nodes
        val actions = actionIndices(nodes)
        if (actions.isEmpty()) return Result.Refused("No se ve el botón de la tarjeta")
        val regions = actions.mapIndexed { k, end -> (if (k == 0) 0 else actions[k - 1] + 1) to end }
        val mine = regions.filter { (from, to) ->
            (from until to).any { i -> nodes[i].text?.let { OfferPatterns.fareValue(it) }?.let { abs(it - price) < 0.005 } == true }
        }
        if (mine.isEmpty()) return Result.Refused("La oferta ya no está en pantalla")
        if (mine.size > 1) return Result.Refused("Hay dos tarjetas con el mismo precio")
        val (from, to) = mine.single()
        val candidates = (from until to).filter { isCloseCandidate(nodes[it]) }
        return when (candidates.size) {
            1 -> Result.Found(candidates.single(), label(nodes[candidates.single()]))
            0 -> Result.Refused("No se reconoce la X de la tarjeta")
            else -> Result.Refused("Hay ${candidates.size} botones que podrían ser la X")
        }
    }

    /**
     * Full content and window identity, in addition to the conservative price/unique-X check.
     * 1.59.0: with [learned], only the X learned from the driver's own closes is accepted.
     */
    fun find(
        snapshot: ScreenSnapshot,
        expected: com.ubers.observador.domain.models.UberOffer,
        windowId: Int,
        learned: LearnedX.Signature? = null
    ): Result {
        if (snapshot.packageName != "com.ubercab.driver" || snapshot.windowId != windowId) {
            return Result.Refused("La ventana cambió")
        }
        // 1.63.0: the learned X first, then the X of the captures (place and shape), then words
        val found = when {
            learned != null -> LearnedX.find(snapshot, expected.price, learned)
            else -> UberCardX.find(snapshot, expected.price).let { rule ->
                if (rule is Result.Found) rule else find(snapshot, expected.price).takeIf { it is Result.Found } ?: rule
            }
        }
        if (found !is Result.Found) return found
        // The card that holds that price must still be exactly the offer that was scheduled
        val card = UberCards.withPrice(UberCards.of(snapshot), expected.price) ?: return Result.Refused("La tarjeta cambió")
        val texts = snapshot.copy(nodes = snapshot.nodes.subList(card.region.first, card.region.last + 1)).texts
        val actual = com.ubers.observador.domain.lifecycle.OfferCardParser.parseAll(texts).singleOrNull()
        if (actual == null || actual.contentFingerprint() != expected.contentFingerprint()) {
            return Result.Refused("La tarjeta cambió")
        }
        return found
    }
}

enum class CloseOutcome { NONE, SIMULATE, CLOSE }

/** 1.37.0: when a card is closed, and what the floating card says meanwhile. */
object CloseRules {

    const val COUNTDOWN_MS = 3_000L
    const val DAILY_CAP = 30

    fun decide(automatic: Boolean, level: SectorLevel?, validated: Boolean, closedToday: Int): CloseOutcome = when {
        !automatic || level != SectorLevel.CLOSE -> CloseOutcome.NONE
        closedToday >= DAILY_CAP -> CloseOutcome.NONE
        !validated -> CloseOutcome.SIMULATE
        else -> CloseOutcome.CLOSE
    }

    /** The red band while the 3 seconds run. */
    fun banner(match: SectorMatch, card: Int?, outcome: CloseOutcome): SectorBanner? {
        if (outcome == CloseOutcome.NONE) return SectorBanner.of(match, card)
        val places = SectorBanner.places(match)
        val who = if (card != null && card > 1) "Tarjeta $card · " else ""
        val text = if (outcome == CloseOutcome.CLOSE) "⛔ Se cierra en 3 s · $who$places · toca para no cerrar"
        else "⛔ Simulación: se cerraría en 3 s · $who$places"
        return SectorBanner(text, avoid = true)
    }
}

/** 1.37.0: one line per closing decision, in a daily file (filesDir/cierres), shown in Ajustes. */
object CloseLog {

    const val CLOSED = "CERRADA"
    const val SIMULATED = "SIMULADA"
    const val NOT_CLOSED = "NO CERRADA"
    const val REQUESTED = "TOQUE SOLICITADO"

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun fileName(atMs: Long, zone: ZoneId): String = "cierres-" + DAY.format(Instant.ofEpochMilli(atMs).atZone(zone)) + ".txt"

    /** «14:03:12 · CERRADA · Destino: Engombe · RD$294.67» or «… · NO CERRADA (Cancelado por ti) · …». */
    fun line(atMs: Long, zone: ZoneId, result: String, places: String, price: Double): String =
        TIME.format(Instant.ofEpochMilli(atMs).atZone(zone)) + " · " + result + " · " + places + " · RD$" +
            String.format(java.util.Locale.US, "%.2f", price)

    fun closedCount(text: String): Int = text.lineSequence().count { it.contains(" · $CLOSED · ") }
}
