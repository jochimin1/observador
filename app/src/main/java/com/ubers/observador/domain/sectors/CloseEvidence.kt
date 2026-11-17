package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import java.time.Instant
import java.time.ZoneId

/**
 * 1.56.0: what is needed to turn the «Automático» close from simulation into a real touch. Every
 * time a «Cerrar» offer reaches its countdown, the whole Uber screen is written down — every node
 * with its class, text, description, id, whether it can be touched and where it is — together with
 * what Observador decided (the X it found, or why it found none) and which nodes could be the X
 * by shape (touchable, no words, small and square, inside the card). The driver exports it from
 * Ajustes › Sectores. 1.59.0: the X itself is learned from the driver's own closes ([LearnedX]).
 */
object CloseEvidence {

    const val FILE_PREFIX = "evidencia-"

    data class Shape(val index: Int, val node: ScreenNode)

    /** The region of the card that holds [price]: (first, last) node index, or null. */
    fun cardRegion(snapshot: ScreenSnapshot, price: Double): IntRange? {
        val nodes = snapshot.nodes
        val actions = CloseTarget.actionIndices(nodes)
        val regions = actions.mapIndexed { k, end -> (if (k == 0) 0 else actions[k - 1] + 1)..end }
        return regions.singleOrNull { r -> r.any { i -> nodes[i].text?.let { com.ubers.observador.domain.lifecycle.OfferPatterns.fareValue(it) }
            ?.let { kotlin.math.abs(it - price) < 0.005 } == true } }
    }

    /** Touchable nodes with no words that look like an icon button (square-ish, 24–200 px). */
    fun shapeCandidates(snapshot: ScreenSnapshot, region: IntRange?): List<Shape> =
        (region ?: snapshot.nodes.indices).mapNotNull { i ->
            val n = snapshot.nodes[i]
            val w = n.right - n.left; val h = n.bottom - n.top
            val words = listOfNotNull(n.text, n.contentDescription).any { it.isNotBlank() }
            if (n.clickable && n.enabled && n.visibleToUser && !words && w in 24..200 && h in 24..200 && kotlin.math.abs(w - h) <= maxOf(w, h) / 3)
                Shape(i, n) else null
        }

    private fun clean(s: String?) = s?.replace('\n', ' ')?.replace('|', '/')

    fun format(snapshot: ScreenSnapshot, price: Double, places: String, decision: String, uberVersion: String?, zone: ZoneId): String {
        val region = cardRegion(snapshot, price)
        val shapes = shapeCandidates(snapshot, region)
        val sb = StringBuilder()
        sb.append("=== Cierre ").append(Instant.ofEpochMilli(snapshot.timestampMs).atZone(zone).toOffsetDateTime()).append('\n')
        sb.append("uber=").append(uberVersion ?: "?").append(" precio=").append(price).append(" lugares=").append(clean(places)).append('\n')
        sb.append("decision=").append(clean(decision)).append('\n')
        sb.append("tarjeta=").append(region?.let { "${it.first}..${it.last}" } ?: "no encontrada").append('\n')
        sb.append("posibles_x_por_forma=").append(if (shapes.isEmpty()) "ninguna" else shapes.joinToString(",") { it.index.toString() }).append('\n')
        // 1.63.0: what the rule of the captures finds (place and shape inside the card's box)
        sb.append("x_por_regla=").append(when (val r = UberCardX.find(snapshot, price)) {
            is CloseTarget.Result.Found -> r.index.toString()
            is CloseTarget.Result.Refused -> "ninguna (" + clean(r.reason) + ")"
        }).append('\n')
        snapshot.nodes.forEachIndexed { i, n ->
            val mark = when {
                region != null && i in region -> "T"
                else -> " "
            } + if (shapes.any { it.index == i }) "X?" else "  "
            sb.append(mark).append(' ').append(i).append(" | ").append(n.className?.substringAfterLast('.') ?: "?")
                .append(" | t=").append(clean(n.text) ?: "").append(" | d=").append(clean(n.contentDescription) ?: "")
                .append(" | id=").append(n.viewId?.substringAfter(":id/") ?: "")
                .append(" | ").append(if (n.clickable) "toca" else "-").append(if (n.enabled) "" else ",inactivo").append(if (n.visibleToUser) "" else ",oculto")
                .append(" | @").append(n.left).append(',').append(n.top).append(',').append(n.right).append(',').append(n.bottom).append('\n')
        }
        return sb.append('\n').toString()
    }

    fun fileName(atMs: Long, zone: ZoneId) = FILE_PREFIX + Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate() + ".txt"

    fun count(text: String): Int = text.lineSequence().count { it.startsWith("=== Cierre ") }
}
