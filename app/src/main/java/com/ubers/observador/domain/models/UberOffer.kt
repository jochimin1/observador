package com.ubers.observador.domain.models

import com.ubers.observador.domain.lifecycle.TextNormalizer
import java.security.MessageDigest
import java.util.Locale

data class UberOffer(
    val price: Double,
    val pickupKm: Double,
    val pickupMin: Int,
    val tripKm: Double,
    val tripMin: Int,
    val detectedAt: Long = System.currentTimeMillis(),
    /** As Uber wrote it on the card (Fase 8F); not part of the hash. */
    val pickupAddress: String? = null,
    val dropoffAddress: String? = null,
    /** Fase 8J: «Me interesa» or «Aceptar», as on the card; not part of the hash. */
    val cardAction: com.ubers.observador.domain.lifecycle.OfferCardAction? = null,
    /** Fase 8J: the modality above the price ("Moto") and the "Exclusivo" label; not part of the hash. */
    val product: String? = null,
    val exclusive: Boolean? = null,
    /** 1.37.1: one visible appearance; kept in the existing offer_hash logical-link columns. */
    val appearanceId: String? = null,
    /** Audit metadata never changes content or appearance identity. */
    val readingTrace: com.ubers.observador.domain.lifecycle.OfferReadTrace? = null
) {
    /** Old numerical hashes remain readable. Newly observed cards carry their own appearance id. */
    fun offerHash(): String =
        appearanceId ?: String.format(Locale.US, "%.2f-%.1f-%d-%.1f-%d", price, pickupKm, pickupMin, tripKm, tripMin)

    /** Content identity, never an appearance id. No address text is retained in the key. */
    fun contentFingerprint(): String {
        val fields = listOf(
            java.lang.Double.toHexString(price), java.lang.Double.toHexString(pickupKm), pickupMin.toString(),
            java.lang.Double.toHexString(tripKm), tripMin.toString(), pickupAddress?.let(TextNormalizer::normalize),
            dropoffAddress?.let(TextNormalizer::normalize), cardAction?.storageKey,
            product?.let(TextNormalizer::normalize), exclusive?.toString()
        )
        val encoded = fields.joinToString("") { it?.let { s -> "${s.length}:$s" } ?: "-1:" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    companion object {
        const val APPEARANCE_PREFIX = "OFFER-V2-"
        fun isAppearanceId(value: String) = value.startsWith(APPEARANCE_PREFIX)
    }
}
