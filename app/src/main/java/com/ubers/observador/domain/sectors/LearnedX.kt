package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.lifecycle.OfferPatterns
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.TextNormalizer
import kotlin.math.abs

/**
 * 1.59.0: Uber's X, learned from the driver's own hand. Nobody outside the phone knows how Uber
 * draws the X of an offer card (an icon with no words, an id, a description…), so Observador does
 * not guess it: every time the driver closes a card with Uber's X, the touched button is written
 * down (its class, id, description, text, size and where it sits against the card's «Aceptar»),
 * but only when the card really went away and Uber went back to searching (never a trip taken).
 * After [REQUIRED] closes that agree, with the Uber version installed now, that button is the X
 * and the «Automático» mode touches it for real; until then it only simulates. When Uber updates,
 * it is learned again. Every observation is kept for good (cierres/x-aprendida.txt, in backups and
 * in the evidence export); «Olvidar» adds a line, it erases nothing.
 */
object LearnedX {

    const val REQUIRED = 3
    const val FILE = "x-aprendida.txt"
    private const val TAG = "x1"
    private const val FORGET = "olvidar"
    private const val MIN_SIDE = 16
    private const val MAX_SIDE = 400

    data class Key(val className: String, val viewId: String?, val description: String?, val text: String?) {
        fun same(other: Key): Boolean =
            className == other.className && viewId == other.viewId &&
                norm(description) == norm(other.description) && norm(text) == norm(other.text)
    }

    data class Observation(
        val atMs: Long,
        val uberVersion: String,
        val key: Key,
        val width: Int,
        val height: Int,
        /** From the X's right edge to the right edge of the card's «Aceptar», px. */
        val fromRight: Int,
        /** From the X's bottom to the top of «Aceptar», px: positive when the X is above it. */
        val above: Int,
        /** The price of the card that was closed, as read. */
        val price: Double
    )

    data class Signature(val key: Key, val width: Int, val height: Int, val fromRight: Int, val above: Boolean, val uberVersion: String, val seen: Int)

    sealed interface State {
        data class Learned(val signature: Signature) : State
        /** [agreeing] closes point to the same button, of [total] with this Uber version. */
        data class Learning(val agreeing: Int, val total: Int, val olderVersion: Boolean) : State
    }

    private fun norm(s: String?): String? = s?.let { TextNormalizer.normalize(it) }?.takeIf { it.isNotBlank() }
    private fun blank(s: String?): String? = s?.takeIf { it.isNotBlank() }

    /** The card regions of a screen: from after the previous card's button to this card's button. */
    private fun regions(nodes: List<ScreenNode>): List<IntRange> {
        val actions = CloseTarget.actionIndices(nodes)
        return actions.mapIndexed { k, end -> (if (k == 0) 0 else actions[k - 1] + 1)..end }
    }

    private fun priceOf(nodes: List<ScreenNode>, region: IntRange): Double? =
        region.firstNotNullOfOrNull { i -> nodes[i].text?.let { OfferPatterns.fareValue(it) } }

    private fun keyOf(n: ScreenNode) = Key(n.className?.substringAfterLast('.') ?: "?", blank(n.viewId), blank(n.contentDescription), blank(n.text))

    /**
     * What the driver touched, taken from the last screen read before the touch: the node with the
     * same place and class, inside one offer card, that is not the card's own button nor a word that
     * accepts. Null when the touch was anything else (then nothing is learned from it).
     */
    fun observe(before: ScreenSnapshot, touched: ScreenNode, atMs: Long, uberVersion: String?): Observation? {
        if (touched.right <= touched.left || touched.bottom <= touched.top) return null
        val nodes = before.nodes
        val cls = touched.className?.substringAfterLast('.')
        val same = nodes.indices.filter { i ->
            val n = nodes[i]
            n.left == touched.left && n.top == touched.top && n.right == touched.right && n.bottom == touched.bottom &&
                n.className?.substringAfterLast('.') == cls
        }
        val index = same.singleOrNull() ?: return null
        val node = nodes[index]
        val region = regions(nodes).singleOrNull { index in it } ?: return null
        if (index == region.last) return null
        if (CloseTarget.forbidden(node) || CloseTarget.forbidden(touched)) return null
        val w = node.right - node.left; val h = node.bottom - node.top
        if (w !in MIN_SIDE..MAX_SIDE || h !in MIN_SIDE..MAX_SIDE) return null
        val price = priceOf(nodes, region) ?: return null
        val action = nodes[region.last]
        return Observation(atMs, uberVersion ?: "?", keyOf(node), w, h, action.right - node.right, action.top - node.bottom, price)
    }

    // ---- The file: one line per observation, «olvidar» lines start again ----

    private fun esc(s: String?): String = s?.replace("%", "%25")?.replace(";", "%3B")?.replace("\n", "%0A")?.replace("\r", "%0D") ?: ""
    private fun unesc(s: String): String? = s.takeIf { it.isNotEmpty() }
        ?.replace("%0D", "\r")?.replace("%0A", "\n")?.replace("%3B", ";")?.replace("%25", "%")

    fun encode(o: Observation): String = listOf(
        TAG, o.atMs.toString(), esc(o.uberVersion), esc(o.key.className), esc(o.key.viewId), esc(o.key.description), esc(o.key.text),
        o.width.toString(), o.height.toString(), o.fromRight.toString(), o.above.toString(), o.price.toString()
    ).joinToString(";")

    fun forgetLine(atMs: Long): String = "$FORGET;$atMs"

    fun decode(line: String): Observation? {
        val p = line.trim().split(';')
        if (p.size != 12 || p[0] != TAG) return null
        return try {
            Observation(
                p[1].toLong(), unesc(p[2]) ?: "?", Key(unesc(p[3]) ?: return null, unesc(p[4]), unesc(p[5]), unesc(p[6])),
                p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt(), p[11].toDouble()
            )
        } catch (e: NumberFormatException) { null }
    }

    /** What the lines say for the Uber installed now. */
    fun state(text: String, uberVersion: String?): State {
        val lines = text.lines().filter { it.isNotBlank() }
        val start = lines.indexOfLast { it.startsWith("$FORGET;") } + 1
        val all = lines.drop(start).mapNotNull(::decode)
        val version = uberVersion ?: "?"
        val now = all.filter { it.uberVersion == version }
        if (now.isEmpty()) return State.Learning(0, 0, olderVersion = all.isNotEmpty())
        // The largest group of closes on the same button, then those near its middle place
        val group = now.groupBy { o -> now.first { it.key.same(o.key) }.key }.values.maxBy { it.size }
        val mid = group.map { it.fromRight }.sorted()[group.size / 2]
        val tol = tolerance(group.map { it.width }.sorted()[group.size / 2])
        val aboveMostly = group.count { it.above > 0 } * 2 > group.size
        val agree = group.filter { abs(it.fromRight - mid) <= tol && (it.above > 0) == aboveMostly }
        // Three that agree, and no more than one in four that disagree: a mixed record learns nothing
        return if (agree.size >= REQUIRED && agree.size * 4 >= now.size * 3) {
            State.Learned(
                Signature(
                    agree.first().key, agree.map { it.width }.sorted()[agree.size / 2], agree.map { it.height }.sorted()[agree.size / 2],
                    agree.map { it.fromRight }.sorted()[agree.size / 2], agree.first().above > 0, version, agree.size
                )
            )
        } else State.Learning(agree.size, now.size, olderVersion = all.size > now.size)
    }

    private fun tolerance(width: Int) = maxOf(40, width / 2)

    private fun close(a: Int, b: Int) = abs(a - b) <= maxOf(8, b * 3 / 10)

    /** The learned X on the card that shows [price]: exactly one node or a refusal. */
    fun find(snapshot: ScreenSnapshot, price: Double, s: Signature): CloseTarget.Result {
        val nodes = snapshot.nodes
        val regions = regions(nodes)
        if (regions.isEmpty()) return CloseTarget.Result.Refused("No se ve el botón de la tarjeta")
        val mine = regions.filter { r -> r.any { i -> nodes[i].text?.let { OfferPatterns.fareValue(it) }?.let { abs(it - price) < 0.005 } == true } }
        if (mine.isEmpty()) return CloseTarget.Result.Refused("La oferta ya no está en pantalla")
        if (mine.size > 1) return CloseTarget.Result.Refused("Hay dos tarjetas con el mismo precio")
        val region = mine.single()
        val action = nodes[region.last]
        val candidates = (region.first until region.last).filter { i ->
            val n = nodes[i]
            val w = n.right - n.left; val h = n.bottom - n.top
            n.clickable && n.enabled && n.visibleToUser && !CloseTarget.forbidden(n) && keyOf(n).same(s.key) &&
                close(w, s.width) && close(h, s.height) &&
                abs((action.right - n.right) - s.fromRight) <= tolerance(s.width) &&
                ((action.top - n.bottom) > 0) == s.above
        }
        return when (candidates.size) {
            1 -> CloseTarget.Result.Found(candidates.single(), label(nodes[candidates.single()]))
            0 -> CloseTarget.Result.Refused("No está la X aprendida en la tarjeta")
            else -> CloseTarget.Result.Refused("Hay ${candidates.size} botones iguales a la X aprendida")
        }
    }

    fun label(n: ScreenNode): String =
        blank(n.contentDescription) ?: blank(n.text) ?: blank(n.viewId)?.substringAfter(":id/") ?: "X aprendida"

    /** «2 de 3» and the like, for Ajustes. */
    fun describe(state: State): String = when (state) {
        is State.Learned -> "Aprendida: «${state.signature.key.description ?: state.signature.key.text ?: state.signature.key.viewId?.substringAfter(":id/") ?: state.signature.key.className}» " +
            "(${state.signature.seen} cierres a mano, Uber ${state.signature.uberVersion})"
        is State.Learning -> buildString {
            append("Aprendiendo: ${state.agreeing} de $REQUIRED cierres a mano con la X")
            if (state.total > state.agreeing) append(" (${state.total - state.agreeing} no coinciden)")
            if (state.olderVersion) append(". Uber se actualizó: se aprende otra vez")
        }
    }
}
