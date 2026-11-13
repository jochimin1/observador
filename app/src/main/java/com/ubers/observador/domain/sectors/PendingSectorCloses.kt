package com.ubers.observador.domain.sectors

import com.ubers.observador.domain.models.UberOffer

/** Read from one DataStore snapshot, both while observing and immediately before execution. */
data class CloseConfiguration(
    val observing: Boolean = false,
    val automatic: Boolean = false,
    val sectorsEnabled: Boolean = false,
    val showOverlay: Boolean = false,
    val sectors: List<CareSector> = emptyList()
) {
    fun level(offer: UberOffer): SectorLevel? =
        if (!observing || !automatic || !sectorsEnabled || !showOverlay) null
        else SectorMatcher.match(offer.pickupAddress, offer.dropoffAddress, sectors).level
}

/** Main-thread state behind the Android timer. Tests exercise the same invalidation as production. */
class PendingSectorCloses {
    data class Ticket(val offer: UberOffer, val windowId: Int, val places: String, val revision: Long)
    var configuration = CloseConfiguration()
        private set
    var revision = 0L
        private set
    private val pending = LinkedHashMap<String, Ticket>()
    private var visible = emptyMap<String, Pair<String, Int?>>()
    private val cancelled = HashSet<String>()

    fun configure(next: CloseConfiguration): List<Ticket> {
        if (next == configuration) return emptyList()
        configuration = next
        revision++
        return cancelAll()
    }

    fun observe(offers: List<UberOffer>, windowId: Int?): List<Ticket> {
        // Identical cards cannot be distinguished after a reordering. Refuse both.
        visible = offers.groupBy { it.contentFingerprint() }.values.filter { it.size == 1 }
            .map { it.single() }.associate { it.offerHash() to (it.contentFingerprint() to windowId) }
        val removed = pending.values.filter { !isVisible(it) }
        removed.forEach { pending.remove(it.offer.offerHash()); cancelled += it.offer.offerHash() }
        return removed
    }

    fun add(offer: UberOffer, windowId: Int?, places: String, expectedRevision: Long): Ticket? {
        if (offer.appearanceId == null || windowId == null || expectedRevision != revision ||
            configuration.level(offer) != SectorLevel.CLOSE || offer.offerHash() in cancelled ||
            pending.containsKey(offer.offerHash())) return null
        val ticket = Ticket(offer, windowId, places, revision)
        if (!isVisible(ticket)) return null
        pending[offer.offerHash()] = ticket
        return ticket
    }

    fun take(ticket: Ticket): Ticket? {
        val id = ticket.offer.offerHash()
        if (pending[id] != ticket) return null
        pending.remove(id)
        cancelled += id
        return ticket
    }

    fun outcome(ticket: Ticket, usedToday: Int, overlayVisible: Boolean): CloseOutcome =
        if (ticket.revision != revision || !isVisible(ticket) || !overlayVisible) CloseOutcome.NONE
        else CloseRules.decide(true, configuration.level(ticket.offer), CloseTarget.VALIDATED, usedToday)

    fun cancelAll(): List<Ticket> = pending.values.toList().also {
        cancelled += visible.keys
        pending.clear()
    }

    fun clear(): List<Ticket> = cancelAll().also {
        revision++
        visible = emptyMap()
        cancelled.clear()
    }

    private fun isVisible(ticket: Ticket): Boolean =
        visible[ticket.offer.offerHash()] == (ticket.offer.contentFingerprint() to ticket.windowId)
}
