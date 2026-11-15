package com.ubers.observador.domain

import com.ubers.observador.domain.models.OfferAnalysis
import java.util.Locale

/**
 * 1.50.0: the figures of the card over Uber. The score badge (number, colour and its label) is
 * always shown: it is the decision at a glance, and colour never goes without its label. The first
 * field is the big one; the rest go below it.
 */
enum class OverlayField(val label: String) {
    PRICE("Precio"),
    DISTANCE_TIME("Km y minutos"),
    RDP_KM("RD$ por km"),
    RDP_HOUR("RD$ por hora"),
    NET("Ganancia estimada"),
    PICKUP("Recogida"),
    ADDRESSES("Recogida y destino (direcciones)");

    /** What the card writes for this field; null when the offer does not have it. */
    fun text(a: OfferAnalysis): List<String> = when (this) {
        PRICE -> listOf(String.format(Locale.US, "RD$%.2f", a.offer.price))
        DISTANCE_TIME -> listOf(String.format(Locale.US, "%.1f km · %d min", a.totalKm, a.totalMin))
        RDP_KM -> listOf(String.format(Locale.US, "RD$%.2f/km", a.rdpPerKm))
        RDP_HOUR -> listOf(String.format(Locale.US, "RD$%.0f/h", a.rdpPerHour))
        // Price minus fuel: an estimate on the offer, never money earned
        NET -> listOf(String.format(Locale.US, "≈ RD$%.2f tras combustible", a.netProfit))
        PICKUP -> listOf(String.format(Locale.US, "Recogida %.1f km · %d min", a.offer.pickupKm, a.offer.pickupMin))
        ADDRESSES -> listOfNotNull(a.offer.pickupAddress?.let { "▲ $it" }, a.offer.dropoffAddress?.let { "▼ $it" })
    }
}

enum class OverlayPosition(val label: String) { TOP("Arriba"), CENTER("Centro"), BOTTOM("Abajo") }

enum class OverlayTextSize(val label: String, val scale: Float) { NORMAL("Normal", 1f), LARGE("Grande", 1.2f), XLARGE("Muy grande", 1.4f) }

data class OverlayLayout(
    val fields: List<OverlayField> = DEFAULT_FIELDS,
    val position: OverlayPosition = OverlayPosition.TOP,
    val textSize: OverlayTextSize = OverlayTextSize.NORMAL
) {
    /** Adds [f] at the end, or removes it; never under [MIN_FIELDS] nor over [MAX_FIELDS]. */
    fun toggle(f: OverlayField): OverlayLayout = when {
        f in fields -> if (fields.size > MIN_FIELDS) copy(fields = fields - f) else this
        fields.size < MAX_FIELDS -> copy(fields = fields + f)
        else -> this
    }

    /** Moves [f] one place up (-1) or down (+1). */
    fun move(f: OverlayField, by: Int): OverlayLayout {
        val i = fields.indexOf(f)
        val j = i + by
        if (i < 0 || j !in fields.indices) return this
        val m = fields.toMutableList()
        m[i] = m[j].also { m[j] = m[i] }
        return copy(fields = m)
    }

    companion object {
        const val MIN_FIELDS = 2
        const val MAX_FIELDS = 4

        /** Exactly what the card showed before 1.50.0. */
        val DEFAULT_FIELDS = listOf(OverlayField.PRICE, OverlayField.DISTANCE_TIME, OverlayField.RDP_KM, OverlayField.ADDRESSES)
    }
}

/**
 * DataStore keys `overlay_fields` («PRICE,RDP_HOUR»), `overlay_position` and `overlay_text_size`.
 * Strict like every restored setting: a value out of shape is rejected (null) and goes back to its
 * default.
 */
object OverlayLayoutCodec {

    fun encodeFields(fields: List<OverlayField>): String = fields.joinToString(",") { it.name }

    fun decodeFields(text: String?): List<OverlayField>? {
        if (text == null) return OverlayLayout.DEFAULT_FIELDS
        val parts = text.split(',')
        val fields = parts.map { p -> OverlayField.entries.firstOrNull { it.name == p.trim() } ?: return null }
        if (fields.size !in OverlayLayout.MIN_FIELDS..OverlayLayout.MAX_FIELDS || fields.toSet().size != fields.size) return null
        return fields
    }

    fun decodePosition(text: String?): OverlayPosition? =
        if (text == null) OverlayPosition.TOP else OverlayPosition.entries.firstOrNull { it.name == text }

    fun decodeTextSize(text: String?): OverlayTextSize? =
        if (text == null) OverlayTextSize.NORMAL else OverlayTextSize.entries.firstOrNull { it.name == text }

    fun layout(fields: String?, position: String?, textSize: String?) = OverlayLayout(
        decodeFields(fields) ?: OverlayLayout.DEFAULT_FIELDS,
        decodePosition(position) ?: OverlayPosition.TOP,
        decodeTextSize(textSize) ?: OverlayTextSize.NORMAL
    )
}
