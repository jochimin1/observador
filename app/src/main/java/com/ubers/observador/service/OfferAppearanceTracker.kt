package com.ubers.observador.service

import com.ubers.observador.domain.models.UberOffer
import java.util.UUID

/**
 * One identity per appearance, independent of price, list position and wall-clock changes.
 * Reordering Radar cards preserves their identities; a confirmed absence starts a new one.
 * Identical simultaneous cards get separate ids, but are never used to infer which was accepted.
 * Called on the reading thread with elapsed realtime. Plain Kotlin for sequence tests.
 */
class OfferAppearanceTracker(
    private val absentConfirmMs: Long = OfferSessionTracker.ABSENT_CONFIRM_MS,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private data class Seen(val offer: UberOffer, var missingSince: Long? = null)
    private val entries = LinkedHashMap<String, MutableList<Seen>>()
    private var window: Int? = null

    fun observe(offers: List<UberOffer>, now: Long, windowId: Int?): List<UberOffer> {
        if (offers.isNotEmpty() && window != windowId) {
            entries.clear()
            window = windowId
        }
        val used = HashSet<String>()
        val result = offers.map { offer ->
            val key = offer.contentFingerprint()
            val previous = entries.getOrPut(key) { mutableListOf() }
            val seen = previous.firstOrNull {
                it.offer.offerHash() !in used && (it.missingSince == null || now - it.missingSince!! < absentConfirmMs)
            } ?: Seen(offer.copy(appearanceId = UberOffer.APPEARANCE_PREFIX + newId())).also { previous += it }
            seen.missingSince = null
            used += seen.offer.offerHash()
            seen.offer
        }
        entries.values.forEach { list ->
            list.forEach { if (it.offer.offerHash() !in used && it.missingSince == null) it.missingSince = now }
            list.removeAll { it.missingSince?.let { since -> now - since >= absentConfirmMs } == true }
        }
        entries.entries.removeAll { it.value.isEmpty() }
        return result
    }

    fun reset() {
        entries.clear()
        window = null
    }

    /** Multiple cards do not reveal which one the driver accepted. Never guess the first one. */
    fun lifecycleOfferId(offers: List<UberOffer>, cardCount: Int = offers.size): String? =
        offers.singleOrNull()?.takeIf { cardCount == 1 }?.offerHash()
}
