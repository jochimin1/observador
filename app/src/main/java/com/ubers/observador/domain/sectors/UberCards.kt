package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.lifecycle.OfferPatterns
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import kotlin.math.abs

/** A rectangle on screen, in pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height
    val empty: Boolean get() = width <= 0 || height <= 0
    fun contains(o: Box): Boolean = !o.empty && o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    companion object {
        fun of(n: ScreenNode) = Box(n.left, n.top, n.right, n.bottom)
    }
}

/**
 * 1.63.0: the offer cards on Uber's screen as places, not only as runs of text. A card is its
 * text from after the previous card's button to its own «Me interesa» / «Aceptar», plus its box:
 * the smallest container of that run that holds both its price and its button. Uber's X is NOT in
 * that run: in the real captures of 2026-11-16 (Uber 4.605.10002) it is a separate node written
 * after the button, so it is found by place (inside the card's box), never by order.
 */
data class UberCard(val region: IntRange, val action: Int, val fare: Int, val price: Double, val box: Box)

object UberCards {

    fun of(snapshot: ScreenSnapshot): List<UberCard> {
        val nodes = snapshot.nodes
        val actions = CloseTarget.actionIndices(nodes)
        val screen = nodes.firstOrNull()?.let(Box::of)
        return actions.mapIndexedNotNull { k, end ->
            val region = (if (k == 0) 0 else actions[k - 1] + 1)..end
            val fare = region.firstOrNull { i -> nodes[i].text?.let { OfferPatterns.fareValue(it) } != null } ?: return@mapIndexedNotNull null
            val price = OfferPatterns.fareValue(nodes[fare].text!!) ?: return@mapIndexedNotNull null
            val fareBox = Box.of(nodes[fare]); val actionBox = Box.of(nodes[end])
            val container = region.map { Box.of(nodes[it]) }
                .filter { b -> b.contains(fareBox) && b.contains(actionBox) && b != screen }
                .minByOrNull { it.area }
            UberCard(region, end, fare, price, container ?: union(region.map { Box.of(nodes[it]) }.filter { !it.empty }) ?: Box(0, 0, 0, 0))
        }
    }

    private fun union(boxes: List<Box>): Box? =
        if (boxes.isEmpty()) null else Box(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })

    /** The card showing [price]: exactly one, or null. */
    fun withPrice(cards: List<UberCard>, price: Double): UberCard? =
        cards.filter { abs(it.price - price) < 0.005 }.singleOrNull()

    /** The card whose box holds [node] (the smallest, when boxes overlap as in the Radar). */
    fun holding(cards: List<UberCard>, node: Box): UberCard? = cards.filter { it.box.contains(node) }.minByOrNull { it.box.area }
}

/**
 * 1.63.0: Uber's X as the captures show it. In both full screens recorded on 2026-11-16 (a single
 * card and the Radar) the X is a touchable Button with no text, no description and no id, 132×132 px,
 * 44 px inside the card's top-right corner, written after the card's «Me interesa». The rule asks
 * for exactly that shape and place, and exactly one such node: anything else is refused.
 *
 * Real touches with this rule are allowed only for the Uber versions those captures came from
 * ([CONFIRMED]); for any other version the rule still shows where the X would be (simulation) until
 * the driver's own closes confirm it (LearnedX) or a new capture adds that version here.
 */
object UberCardX {
    /** Uber versions whose X was confirmed with real captures (docs/releases/1.63.0.md). */
    val CONFIRMED = listOf("4.605.")
    const val MIN_SIDE = 88
    const val MAX_SIDE = 200

    fun confirmedFor(uberVersion: String?): Boolean = uberVersion != null && CONFIRMED.any { uberVersion.startsWith(it) }

    private fun blank(s: String?) = s.isNullOrBlank()

    /** Shape and place of the X inside [card]: an unlabeled button in the card's top-right corner. */
    fun looksLikeX(n: ScreenNode, card: UberCard): Boolean {
        val b = Box.of(n)
        if (!n.clickable || !n.enabled || !n.visibleToUser) return false
        if (!blank(n.text) || !blank(n.contentDescription) || CloseTarget.forbidden(n)) return false
        val cls = n.className?.substringAfterLast('.') ?: return false
        if (cls != "Button" && cls != "ImageButton" && cls != "ImageView") return false
        if (b.width !in MIN_SIDE..MAX_SIDE || b.height !in MIN_SIDE..MAX_SIDE || abs(b.width - b.height) > maxOf(b.width, b.height) / 4) return false
        val box = card.box
        if (!box.contains(b)) return false
        val centerX = (b.left + b.right) / 2
        return centerX > box.left + box.width * 3 / 4 && b.top - box.top <= box.height * 15 / 100
    }

    fun candidates(snapshot: ScreenSnapshot, card: UberCard): List<Int> =
        snapshot.nodes.indices.filter { it != card.action && looksLikeX(snapshot.nodes[it], card) }

    fun find(snapshot: ScreenSnapshot, price: Double): CloseTarget.Result {
        val cards = UberCards.of(snapshot)
        if (cards.isEmpty()) return CloseTarget.Result.Refused("No se ve el botón de la tarjeta")
        val mine = cards.filter { abs(it.price - price) < 0.005 }
        if (mine.isEmpty()) return CloseTarget.Result.Refused("La oferta ya no está en pantalla")
        if (mine.size > 1) return CloseTarget.Result.Refused("Hay dos tarjetas con el mismo precio")
        val found = candidates(snapshot, mine.single())
        return when (found.size) {
            1 -> CloseTarget.Result.Found(found.single(), LABEL)
            0 -> CloseTarget.Result.Refused("No se reconoce la X de la tarjeta")
            else -> CloseTarget.Result.Refused("Hay ${found.size} botones que podrían ser la X")
        }
    }

    const val LABEL = "X de la tarjeta (botón sin texto, arriba a la derecha)"
}
