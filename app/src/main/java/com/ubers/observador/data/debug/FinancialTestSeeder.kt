package com.ubers.observador.data.debug

import com.ubers.observador.domain.finance.AmountSemantics
import com.ubers.observador.domain.finance.EarningsPeriods
import com.ubers.observador.domain.finance.FinancialIds
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialObservationType
import com.ubers.observador.domain.finance.FinancialScope
import com.ubers.observador.domain.finance.FinancialSource
import com.ubers.observador.domain.finance.InMemoryFinancialLedger
import com.ubers.observador.domain.finance.LinkConfidence
import com.ubers.observador.domain.finance.PlatformEarningsSnapshot
import com.ubers.observador.domain.finance.PlatformPeriodType
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripStatus

/**
 * Debug-only TEST money (Fase 7C) on top of the [TripTestSeeder] trips. Every id starts with
 * [TripTestSeeder.PREFIX], so "Borrar" removes only these. Deterministic for a given time.
 *
 * - trip-1 (A): total RD$141.50 with its breakdown (fare 121.50 + tip 20.00).
 * - trip-2 (B): completed, no money seen: earnings stay unknown (offer is never income).
 * - trip-5 (E): total 150, late tip +20 (= 170), Uber then shows total 170 (absorbs the tip,
 *   never 190), then an adjustment of -10: 160.
 * - Unlinked: a period promotion +80 (counts, shown apart) and a tip +30 waiting to be linked.
 * - Uber "today" snapshots for the given working day: one equal to Observador's figure, and a
 *   later one RD$30 higher (the unlinked tip), so the reconciliation card has both cases.
 */
object FinancialTestSeeder {

    private const val P = TripTestSeeder.PREFIX
    private const val MINUTE = 60_000L
    private const val DOP = Currencies.DOP

    data class Seed(val observations: List<FinancialObservation>, val snapshots: List<PlatformEarningsSnapshot>)

    fun generate(trips: List<Trip>, now: Long, periodStartMs: Long, periodEndMs: Long): Seed {
        val byId = trips.associateBy { it.id }
        val obs = ArrayList<FinancialObservation>()

        byId["${P}trip-1"]?.completedAt?.let { done ->
            val group = P + FinancialIds.groupId("${P}trip-1", "FARE:12150,TIP:2000", done)
            obs += linked("${P}trip-1", FinancialObservationType.FARE, AmountSemantics.COMPONENT, 12_150, done + MINUTE, group)
            obs += linked("${P}trip-1", FinancialObservationType.TIP, AmountSemantics.COMPONENT, 2_000, done + MINUTE, group)
            obs += linked("${P}trip-1", FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 14_150, done + MINUTE)
        }
        byId["${P}trip-5"]?.completedAt?.let { done ->
            obs += linked("${P}trip-5", FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 15_000, done + 2 * MINUTE)
            obs += linked("${P}trip-5", FinancialObservationType.TIP, AmountSemantics.DELTA, 2_000, done + 20 * MINUTE)
            obs += linked("${P}trip-5", FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL, 17_000, done + 30 * MINUTE)
            obs += linked("${P}trip-5", FinancialObservationType.ADJUSTMENT, AmountSemantics.DELTA, -1_000, done + 35 * MINUTE)
        }
        obs += unlinked(FinancialObservationType.PROMOTION, FinancialScope.PERIOD, 8_000, now - 30 * MINUTE)
        obs += unlinked(FinancialObservationType.TIP, FinancialScope.UNKNOWN, 3_000, now - 20 * MINUTE)

        // What Observador will show for the period, computed with the same rules, so "equal" is equal
        val ledger = InMemoryFinancialLedger(tripIdForSession = { null }, offeredFor = { id -> offered(byId[id]) })
        obs.forEach { ledger.persist(it) }
        val completed = trips.filter { t ->
            t.status == TripStatus.COMPLETED && t.completedAt != null && t.completedAt in periodStartMs until periodEndMs
        }
        val summaries = completed.map { t ->
            ledger.summary(t.id) ?: com.ubers.observador.domain.finance.TripFinancialAggregator.aggregate(t.id, offered(t), emptyList())
        }
        val local = EarningsPeriods.summarize(DOP, summaries, ledger.unassigned().filter { it.observedAtMs in periodStartMs until periodEndMs })
        val snapshots = listOf(
            snapshot(local.totalObservedEarnings, now - 15 * MINUTE, periodStartMs, periodEndMs, "equal"),
            snapshot(local.totalObservedEarnings + 3_000, now - 5 * MINUTE, periodStartMs, periodEndMs, "later")
        )
        return Seed(obs, snapshots)
    }

    /** Expected trip summaries, for checks: what the database must hold after loading. */
    fun expectedSummaries(trips: List<Trip>, seed: Seed): Map<String, TripFinancialSummary> {
        val byId = trips.associateBy { it.id }
        val ledger = InMemoryFinancialLedger(tripIdForSession = { null }, offeredFor = { id -> offered(byId[id]) })
        seed.observations.forEach { ledger.persist(it) }
        return seed.observations.mapNotNull { it.tripId }.distinct().associateWith { requireNotNull(ledger.summary(it)) }
    }

    private fun offered(trip: Trip?): MoneyAmount? = trip?.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, DOP) }

    private fun linked(
        tripId: String,
        type: FinancialObservationType,
        semantics: AmountSemantics,
        minor: Long,
        at: Long,
        group: String? = null
    ): FinancialObservation {
        val amount = MoneyAmount(minor, DOP)
        return FinancialObservation(
            observationId = P + FinancialIds.observationId(tripId, type, semantics, amount, "TEST", at),
            observedAtMs = at,
            lifecycleSessionId = null,
            tripId = tripId,
            type = type,
            semantics = semantics,
            amount = amount,
            scope = FinancialScope.TRIP,
            confidence = 0.95,
            evidence = listOf("TEST_DATA"),
            source = FinancialSource.TEST,
            screenType = "TEST",
            linkConfidence = LinkConfidence.HIGH,
            breakdownGroupId = group
        )
    }

    private fun unlinked(type: FinancialObservationType, scope: FinancialScope, minor: Long, at: Long): FinancialObservation {
        val amount = MoneyAmount(minor, DOP)
        return FinancialObservation(
            observationId = P + FinancialIds.observationId(null, type, AmountSemantics.DELTA, amount, "TEST", at),
            observedAtMs = at,
            lifecycleSessionId = null,
            tripId = null,
            type = type,
            semantics = AmountSemantics.DELTA,
            amount = amount,
            scope = scope,
            confidence = 0.95,
            evidence = listOf("TEST_DATA"),
            source = FinancialSource.TEST,
            screenType = "TEST",
            linkConfidence = LinkConfidence.LOW
        )
    }

    private fun snapshot(minor: Long, at: Long, start: Long, end: Long, tag: String): PlatformEarningsSnapshot {
        val amount = MoneyAmount(minor, DOP)
        return PlatformEarningsSnapshot(
            snapshotId = P + FinancialIds.snapshotId(PlatformPeriodType.DAY, amount, "test-$tag-$start"),
            capturedAtMs = at,
            periodType = PlatformPeriodType.DAY,
            periodStartMs = start,
            periodEndMs = end,
            amount = amount,
            confidence = 0.95,
            screenType = "TEST",
            evidence = listOf("TEST_DATA")
        )
    }
}
