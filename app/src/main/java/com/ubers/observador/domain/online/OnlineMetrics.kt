package com.ubers.observador.domain.online

import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus

/** Real money per online hour, never shown without how much of it is confirmed. */
data class OnlineEarnings(
    /** Confirmed money of the trips completed inside the sessions. */
    val total: MoneyAmount,
    /** Per confirmed online hour; null with too little time or no confirmed money. */
    val perHour: MoneyAmount?,
    val completedTrips: Int,
    val confirmedTrips: Int
) {
    /** Some completed trip has no confirmed money yet: the figure is a minimum. */
    val partial: Boolean get() = confirmedTrips < completedTrips
}

/**
 * Time online in a window. Confirmed and possible are kept apart; busy, waiting and the rates
 * use confirmed time only. Shares and rates are null when there is too little to compute them.
 */
data class OnlineSummary(
    val sessionCount: Int = 0,
    val recoveredSessionCount: Int = 0,
    val openSession: Boolean = false,
    val confirmedMs: Long = 0,
    val possibleMs: Long = 0,
    /** Time with a trip, from acceptance to its end, inside confirmed time. */
    val busyMs: Long = 0,
    /** A trip joined without its acceptance counts from where it was first seen: busy time is a minimum. */
    val busyIsMinimum: Boolean = false,
    val waitingMs: Long = 0,
    /** busy / confirmed, 0-100, rounded half up. */
    val utilizationPercent: Int? = null,
    val offersSeen: Int = 0,
    /** Offers per online hour, in tenths (34 = 3.4 per hour). */
    val offersPerHourTenths: Long? = null,
    /** Average from the start to the first offer, sessions with an exact start only. */
    val firstOfferAvgMs: Long? = null,
    val firstOfferSamples: Int = 0,
    /** Average from the end of a trip to accepting the next, in the same session. */
    val waitBetweenTripsAvgMs: Long? = null,
    val waitBetweenTripsSamples: Int = 0,
    val earnings: OnlineEarnings? = null
) {
    val hasData: Boolean get() = sessionCount > 0
    /** Possible time beyond confirmed: shown as a range ("1 h 40 min, hasta 2 h 10 min"). */
    val hasRange: Boolean get() = possibleMs > confirmedMs
}

/** A trip as the time metrics need it. */
data class TripTimeSpan(val tripId: String, val span: TimeSpan, val startIsMinimum: Boolean, val acceptedAt: Long?, val completedAt: Long?)

/**
 * Pure metrics of Fase 8A. Everything is clipped to the window, so a session crossing the start
 * of a working day (4:00) is split between both days when computed; it is never split in the
 * database.
 */
object OnlineMetrics {

    private const val HOUR_MS = 3_600_000L

    /** From acceptance (or, when not seen, the first stage seen) to its end, or now while it goes on. */
    fun tripSpan(trip: Trip, nowMs: Long): TripTimeSpan? {
        val start = trip.acceptedAt
            ?: listOfNotNull(trip.pickupNavigationAt, trip.arrivedAt, trip.tripStartedAt).minOrNull()
            ?: trip.createdAt
        val end = trip.completedAt ?: trip.cancelledAt ?: trip.interruptedAt
            ?: if (trip.status.isOpen && trip.status != TripStatus.UNKNOWN) nowMs else trip.updatedAt
        if (end < start) return null
        return TripTimeSpan(trip.id, TimeSpan(start, end), trip.acceptedAt == null, trip.acceptedAt, trip.completedAt)
    }

    fun summarize(
        window: TimeSpan,
        sessions: List<OnlineSession>,
        trips: List<Trip>,
        offerTimes: List<Long>,
        money: Map<String, TripFinancialSummary>,
        nowMs: Long,
        policy: OnlinePolicy = OnlinePolicy(),
        currencyCode: String = Currencies.DOP
    ): OnlineSummary {
        val inWindow = sessions.filter { s ->
            s.startedAt < window.endMs && s.possibleEnd(nowMs, policy) > window.startMs
        }
        if (inWindow.isEmpty()) return OnlineSummary()
        val confirmed = union(inWindow.flatMap { it.confirmedSpans(nowMs, policy) }.mapNotNull { clip(it, window) })
        val possible = union(inWindow.mapNotNull { clip(TimeSpan(it.startedAt, it.possibleEnd(nowMs, policy)), window) })
        val confirmedMs = length(confirmed)
        val possibleMs = maxOf(length(possible), confirmedMs)

        val tripSpans = trips.mapNotNull { tripSpan(it, nowMs) }
        val touching = tripSpans.filter { t -> confirmed.any { overlaps(it, t.span) } }
        val busy = intersect(union(touching.map { it.span }), confirmed)
        val busyMs = length(busy)

        val offers = offerTimes.filter { t -> confirmed.any { t >= it.startMs && t < it.endMs } }
        val enough = confirmedMs >= policy.minRateMs

        // First offer: only where the start is exact (a recovered start would make it look quicker)
        val firstOffers = inWindow.filter { it.startKind == OnlineStartKind.EXACT && it.startedAt >= window.startMs }
            .mapNotNull { s ->
                val end = s.possibleEnd(nowMs, policy)
                offerTimes.filter { it >= s.startedAt && it < end }.minOrNull()?.let { it - s.startedAt }
            }

        val waits = inWindow.flatMap { s -> waitsBetweenTrips(s, tripSpans, trips, nowMs, policy) }
            .filter { (endOfPrevious, _) -> endOfPrevious >= window.startMs && endOfPrevious < window.endMs }
            .map { it.second }

        val completed = trips.filter { t ->
            val at = t.completedAt
            t.status == TripStatus.COMPLETED && at != null && at >= window.startMs && at < window.endMs &&
                inWindow.any { at >= it.startedAt && at <= it.possibleEnd(nowMs, policy) }
        }
        val earnings = if (completed.isEmpty()) null else earningsOf(completed, money, confirmedMs, enough, currencyCode)

        return OnlineSummary(
            sessionCount = inWindow.size,
            recoveredSessionCount = inWindow.count { it.startKind == OnlineStartKind.RECOVERED },
            openSession = inWindow.any { it.isOpen },
            confirmedMs = confirmedMs,
            possibleMs = possibleMs,
            busyMs = busyMs,
            busyIsMinimum = touching.any { it.startIsMinimum },
            waitingMs = confirmedMs - busyMs,
            utilizationPercent = if (confirmedMs > 0 && enough) roundHalfUp(busyMs * 100, confirmedMs).toInt() else null,
            offersSeen = offers.size,
            offersPerHourTenths = if (enough) roundHalfUp(offers.size.toLong() * 10 * HOUR_MS, confirmedMs) else null,
            firstOfferAvgMs = firstOffers.takeIf { it.isNotEmpty() }?.let { roundHalfUp(it.sum(), it.size.toLong()) },
            firstOfferSamples = firstOffers.size,
            waitBetweenTripsAvgMs = waits.takeIf { it.isNotEmpty() }?.let { roundHalfUp(it.sum(), it.size.toLong()) },
            waitBetweenTripsSamples = waits.size,
            earnings = earnings
        )
    }

    /** (end of the previous trip, wait) for consecutive trips inside one session. */
    private fun waitsBetweenTrips(
        session: OnlineSession,
        spans: List<TripTimeSpan>,
        trips: List<Trip>,
        nowMs: Long,
        policy: OnlinePolicy
    ): List<Pair<Long, Long>> {
        val end = session.possibleEnd(nowMs, policy)
        val ended = trips.associateBy { it.id }
        val inside = spans.filter { it.span.startMs >= session.startedAt && it.span.startMs < end }.sortedBy { it.span.startMs }
        return inside.zipWithNext().mapNotNull { (previous, next) ->
            val prevTrip = ended[previous.tripId] ?: return@mapNotNull null
            // Only a trip seen ending (completed or cancelled) and a next one seen being accepted
            if (prevTrip.completedAt == null && prevTrip.cancelledAt == null) return@mapNotNull null
            val accepted = next.acceptedAt ?: return@mapNotNull null
            val wait = accepted - previous.span.endMs
            if (wait < 0) null else previous.span.endMs to wait
        }
    }

    private fun earningsOf(
        completed: List<Trip>,
        money: Map<String, TripFinancialSummary>,
        confirmedMs: Long,
        enough: Boolean,
        currencyCode: String
    ): OnlineEarnings {
        val confirmedMoney = completed.mapNotNull { t ->
            money[t.id]?.takeIf { it.completeness == FinancialCompleteness.CONFIRMED && it.currencyCode == currencyCode }
                ?.effectiveEarnings?.takeIf { it.currencyCode == currencyCode }
        }
        val total = confirmedMoney.fold(0L) { acc, m -> Math.addExact(acc, m.minorUnits) }
        val perHour = if (confirmedMoney.isNotEmpty() && enough && confirmedMs > 0) {
            MoneyAmount(roundHalfUp(Math.multiplyExact(total, HOUR_MS), confirmedMs), currencyCode)
        } else {
            null
        }
        return OnlineEarnings(MoneyAmount(total, currencyCode), perHour, completed.size, confirmedMoney.size)
    }

    // --- interval arithmetic on sorted, non-overlapping lists ---

    fun clip(span: TimeSpan, window: TimeSpan): TimeSpan? {
        val start = maxOf(span.startMs, window.startMs)
        val end = minOf(span.endMs, window.endMs)
        return if (end > start) TimeSpan(start, end) else null
    }

    fun union(spans: List<TimeSpan>): List<TimeSpan> {
        val sorted = spans.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<TimeSpan>()
        sorted.forEach { s ->
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) {
                out[out.lastIndex] = TimeSpan(last.startMs, maxOf(last.endMs, s.endMs))
            } else {
                out += s
            }
        }
        return out
    }

    fun intersect(a: List<TimeSpan>, b: List<TimeSpan>): List<TimeSpan> {
        val out = ArrayList<TimeSpan>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val start = maxOf(a[i].startMs, b[j].startMs)
            val end = minOf(a[i].endMs, b[j].endMs)
            if (end > start) out += TimeSpan(start, end)
            if (a[i].endMs < b[j].endMs) i++ else j++
        }
        return out
    }

    fun length(spans: List<TimeSpan>): Long = spans.sumOf { it.durationMs }

    private fun overlaps(a: TimeSpan, b: TimeSpan) = a.startMs < b.endMs && b.startMs < a.endMs

    private fun roundHalfUp(numerator: Long, denominator: Long): Long = (numerator * 2 + denominator) / (denominator * 2)
}
