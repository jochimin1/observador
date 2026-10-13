package com.ubers.observador.domain.online

import com.ubers.observador.domain.trips.Trip
import java.time.Instant
import java.time.ZoneId

/** One wait: from the end of a trip to accepting the next one. */
data class TripWait(
    val previousTripId: String,
    val nextTripId: String,
    /** When the previous trip ended (completed or cancelled): the wait began here. */
    val startedAt: Long,
    val waitMs: Long,
    /** Both ends inside one online session (8A); otherwise counted only because the gap was short. */
    val inSession: Boolean
)

enum class WaitBucket(val label: String, val maxMs: Long) {
    SHORT("Menos de 5 min", 5 * 60_000L),
    MEDIUM("5 a 15 min", 15 * 60_000L),
    LONG("15 a 30 min", 30 * 60_000L),
    VERY_LONG("Más de 30 min", Long.MAX_VALUE)
}

/** Waits that began in one two-hour slot of the clock (0-2, 2-4 ... 22-24). */
data class WaitSlot(val startHour: Int, val count: Int, val medianMs: Long)

data class WaitSummary(
    val count: Int = 0,
    val averageMs: Long? = null,
    val medianMs: Long? = null,
    val longestMs: Long? = null,
    val buckets: Map<WaitBucket, Int> = emptyMap(),
    /** Slots with enough waits to compare, fastest first. */
    val slots: List<WaitSlot> = emptyList(),
    /** Waits counted without an online session around them (short gaps only). */
    val outsideSession: Int = 0
) {
    val fastestSlot: WaitSlot? get() = slots.firstOrNull()
    val slowestSlot: WaitSlot? get() = slots.lastOrNull()?.takeIf { slots.size > 1 }
}

/**
 * Time between trips: from a trip seen ending (completed or cancelled) to accepting the next
 * one. A wait counts when both ends are inside the same online session (8A), or, for trips with
 * no session around them (before 8A, or Uber not seen), when the gap is at most the online
 * tolerance (30 min): a longer gap with nothing seen may be a break, not a wait, so it is left
 * out rather than guessed. A trip joined without its acceptance never starts or ends a wait.
 */
object TripWaits {

    const val MIN_SLOT_SAMPLES = 3

    fun of(trips: List<Trip>, sessions: List<OnlineSession>, nowMs: Long, policy: OnlinePolicy = OnlinePolicy()): List<TripWait> {
        val ordered = trips.filter { it.acceptedAt != null }.sortedBy { it.acceptedAt }
        return ordered.zipWithNext().mapNotNull { (previous, next) ->
            val ended = previous.completedAt ?: previous.cancelledAt ?: return@mapNotNull null
            val accepted = next.acceptedAt ?: return@mapNotNull null
            val wait = accepted - ended
            if (wait < 0) return@mapNotNull null
            val session = sessions.any { s -> ended >= s.startedAt && accepted <= s.possibleEnd(nowMs, policy) }
            when {
                session -> TripWait(previous.id, next.id, ended, wait, inSession = true)
                wait <= policy.toleranceMs -> TripWait(previous.id, next.id, ended, wait, inSession = false)
                else -> null
            }
        }
    }

    fun summarize(waits: List<TripWait>, zone: ZoneId): WaitSummary {
        if (waits.isEmpty()) return WaitSummary()
        val sorted = waits.map { it.waitMs }.sorted()
        val slots = waits.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour / 2 * 2 }
            .filterValues { it.size >= MIN_SLOT_SAMPLES }
            .map { (hour, list) -> WaitSlot(hour, list.size, median(list.map { it.waitMs }.sorted())) }
            .sortedWith(compareBy<WaitSlot> { it.medianMs }.thenBy { it.startHour })
        return WaitSummary(
            count = waits.size,
            averageMs = (sorted.sum() * 2 + sorted.size) / (sorted.size * 2L),
            medianMs = median(sorted),
            longestMs = sorted.last(),
            buckets = WaitBucket.entries.associateWith { b -> waits.count { bucketOf(it.waitMs) == b } },
            slots = slots,
            outsideSession = waits.count { !it.inSession }
        )
    }

    fun bucketOf(waitMs: Long): WaitBucket = WaitBucket.entries.first { waitMs < it.maxMs }

    /** Middle value; for an even count, the mean of the two middle ones (rounded half up). */
    private fun median(sorted: List<Long>): Long {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2] + 1) / 2
    }
}
