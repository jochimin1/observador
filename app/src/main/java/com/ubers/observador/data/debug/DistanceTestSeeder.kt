package com.ubers.observador.data.debug

import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.SegmentEndReason
import com.ubers.observador.domain.distance.SegmentOrigin

/**
 * Debug-only TEST stretches (Fase 8E), at the same hours as the TEST online sessions, so "Km de
 * hoy" has something to show. Ids start with [PREFIX]; all are closed. Totals only, like real ones.
 *
 * 1. connected, 23.4 km; 2. connected, 31.8 km + 1.2 km without signal; 3. manual, offline, 2.1 km;
 * 4. yesterday, 40 km (today must not count it).
 */
object DistanceTestSeeder {

    const val PREFIX = "TEST-km-"
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val KM = 1_000_000L

    fun generate(now: Long, clock: WorkdayClock): List<DistanceSegment> {
        fun seg(
            n: Int, start: Long, end: Long, origin: SegmentOrigin, reason: SegmentEndReason,
            passenger: Long = 0, pickup: Long = 0, empty: Long = 0, offline: Long = 0, gap: Long = 0
        ) = DistanceSegment(
            id = "$PREFIX$n",
            workday = clock.workdayOf(start),
            startedAt = start,
            endedAt = end,
            updatedAt = end,
            origin = origin,
            endReason = reason,
            measuredMm = passenger + pickup + empty + offline,
            gapMm = gap,
            passengerMm = passenger,
            pickupMm = pickup,
            emptyMm = empty,
            offlineMm = offline,
            fixesUsed = ((end - start) / 5_000).toInt(),
            secondsWithoutSignal = if (gap > 0) 90 else 0,
            createdAt = start
        )
        return listOf(
            seg(1, now - 5 * HOUR - 10 * MIN, now - 3 * HOUR - 20 * MIN, SegmentOrigin.AUTO_ONLINE, SegmentEndReason.WENT_OFFLINE,
                passenger = 12_300_000, pickup = 3_900_000, empty = 7_200_000),
            seg(2, now - 3 * HOUR - 5 * MIN, now - 30 * MIN, SegmentOrigin.AUTO_ONLINE, SegmentEndReason.WENT_OFFLINE,
                passenger = 17_600_000, pickup = 5_100_000, empty = 9_100_000, gap = 1_200_000),
            seg(3, now - 25 * MIN, now - 10 * MIN, SegmentOrigin.MANUAL, SegmentEndReason.STOPPED, offline = 2_100_000),
            seg(4, now - 30 * HOUR, now - 28 * HOUR, SegmentOrigin.AUTO_ONLINE, SegmentEndReason.WENT_OFFLINE,
                passenger = 25 * KM, empty = 15 * KM)
        )
    }
}
