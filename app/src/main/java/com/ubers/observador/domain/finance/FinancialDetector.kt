package com.ubers.observador.domain.finance

import com.ubers.observador.domain.lifecycle.ClassifierPolicy
import com.ubers.observador.domain.lifecycle.RideLifecycleContext
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.TextNormalizer
import com.ubers.observador.domain.lifecycle.UberScreenType
import com.ubers.observador.domain.money.MoneyParseResult
import com.ubers.observador.domain.money.MoneyParser
import java.time.Instant
import java.time.ZoneId

/** UBER_TODAY_PILL (Fase 8A) is the "DOP … · HOY" figure above the map; see UberTodayPillDetector. */
enum class FinancialScreenType { TRIP_EARNINGS, TIP_NOTICE, DAILY_EARNINGS_SUMMARY, WEEKLY_EARNINGS_SUMMARY, UBER_TODAY_PILL, UNKNOWN }

/** One label rule: what an amount next to these words is. */
data class MoneyLabel(val phrases: List<String>, val type: FinancialObservationType, val semantics: AmountSemantics)

/**
 * Spanish phrases for money screens. NONE has been seen on a real Uber screen yet: every one is
 * a candidate, PENDING VALIDATION WITH REAL CAPTURES, and the evidence of every observation says
 * so (PROVISIONAL_SIGNATURE). Longer phrases are listed first so "tarifa de cancelación" wins
 * over "tarifa".
 */
object FinancialSignatures {

    val TRIP_EARNINGS = listOf("ganaste", "ganancia del viaje", "ganancias del viaje", "detalles del viaje")
    val TIP_NOTICE = listOf("recibiste una propina", "te dejaron una propina")
    val DAILY = listOf("ganancias de hoy", "hoy ganaste", "resumen de hoy")
    val WEEKLY = listOf("ganancias de la semana", "esta semana")

    /** On a trip earnings screen. On a tip notice, a tip is a DELTA instead (see [labelsFor]). */
    private val TRIP_LABELS = listOf(
        MoneyLabel(listOf("tarifa de cancelacion", "cargo por cancelacion"), FinancialObservationType.CANCELLATION_FEE, AmountSemantics.COMPONENT),
        MoneyLabel(listOf("ganaste", "total del viaje", "ganancia del viaje", "total"), FinancialObservationType.TRIP_TOTAL, AmountSemantics.ABSOLUTE_TOTAL),
        MoneyLabel(listOf("tarifa"), FinancialObservationType.FARE, AmountSemantics.COMPONENT),
        MoneyLabel(listOf("propina"), FinancialObservationType.TIP, AmountSemantics.COMPONENT),
        MoneyLabel(listOf("promocion", "bonificacion", "incentivo"), FinancialObservationType.PROMOTION, AmountSemantics.COMPONENT),
        MoneyLabel(listOf("ajuste"), FinancialObservationType.ADJUSTMENT, AmountSemantics.COMPONENT),
        MoneyLabel(listOf("peaje"), FinancialObservationType.TOLL_REIMBURSEMENT, AmountSemantics.COMPONENT)
    )
    private val TIP_LABELS = listOf(MoneyLabel(listOf("propina"), FinancialObservationType.TIP, AmountSemantics.DELTA))
    private val SUMMARY_LABELS = DAILY + WEEKLY + listOf("ganancias", "total")

    fun labelsFor(screen: FinancialScreenType): List<MoneyLabel> = when (screen) {
        FinancialScreenType.TRIP_EARNINGS -> TRIP_LABELS
        FinancialScreenType.TIP_NOTICE -> TIP_LABELS
        else -> emptyList()
    }

    fun summaryLabels(): List<String> = SUMMARY_LABELS.map(TextNormalizer::normalize)
}

/** Evidence codes for money: never screen text. */
object FinancialEvidence {
    const val LABEL_IN_SAME_NODE = "LABEL_IN_SAME_NODE"
    const val LABEL_IN_PREVIOUS_NODE = "LABEL_IN_PREVIOUS_NODE"
    const val AFTER_TRIP_SUMMARY = "AFTER_TRIP_SUMMARY"
    const val PROVISIONAL_SIGNATURE = "PROVISIONAL_SIGNATURE"
    const val SCREEN_PREFIX = "SCREEN_"

    /** A label right beside its amount, and a label one node before it. */
    const val SAME_NODE_CONFIDENCE = 0.95
    const val PREVIOUS_NODE_CONFIDENCE = 0.90
}

data class FinancialScreenClassification(val type: FinancialScreenType, val confidence: Double, val evidence: List<String>)

/** Everything one screen produced. Unlabeled or ambiguous amounts are counted, never stored as money. */
data class FinancialDetection(
    val classification: FinancialScreenClassification,
    val observations: List<FinancialObservation> = emptyList(),
    val snapshots: List<PlatformEarningsSnapshot> = emptyList(),
    val unlabeledAmounts: Int = 0,
    val ambiguousAmounts: Int = 0,
    val parseFailures: Int = 0
)

/**
 * Reads money from the snapshot Fase 7A already built (the tree is never walked again).
 * Classification decides what kind of screen it is; extraction then pairs each amount with the
 * label written beside it. An amount without a label is never used: the largest number is not
 * assumed to be the total. Offer cards are ignored: an offer is not money earned.
 */
class FinancialDetector(private val parser: MoneyParser = MoneyParser()) {

    fun classify(snapshot: ScreenSnapshot, lifecycleScreen: UberScreenType): FinancialScreenClassification {
        if (lifecycleScreen == UberScreenType.OFFER) return unknown()
        val texts = snapshot.normalizedTexts
        fun has(phrases: List<String>) = phrases.map(TextNormalizer::normalize).firstOrNull { p -> texts.any { it.contains(p) } }
        val hasMoney = snapshot.texts.any { t -> parser.parseAll(t).any { it is MoneyParseResult.Success } }
        if (!hasMoney) return unknown()
        val signals = ArrayList<String>()
        val type = when {
            has(FinancialSignatures.TIP_NOTICE) != null -> FinancialScreenType.TIP_NOTICE
            has(FinancialSignatures.DAILY) != null -> FinancialScreenType.DAILY_EARNINGS_SUMMARY
            has(FinancialSignatures.WEEKLY) != null -> FinancialScreenType.WEEKLY_EARNINGS_SUMMARY
            has(FinancialSignatures.TRIP_EARNINGS) != null || lifecycleScreen == UberScreenType.TRIP_SUMMARY ->
                FinancialScreenType.TRIP_EARNINGS
            else -> return unknown()
        }
        signals += FinancialEvidence.SCREEN_PREFIX + type.name
        if (lifecycleScreen == UberScreenType.TRIP_SUMMARY) signals += FinancialEvidence.AFTER_TRIP_SUMMARY
        signals += FinancialEvidence.PROVISIONAL_SIGNATURE
        // A money screen phrase plus amounts on screen: two signals (MEDIUM); after a trip summary, three
        val count = 2 + if (lifecycleScreen == UberScreenType.TRIP_SUMMARY) 1 else 0
        return FinancialScreenClassification(type, ClassifierPolicy.confidenceFor(count), signals)
    }

    fun detect(
        snapshot: ScreenSnapshot,
        lifecycleScreen: UberScreenType,
        context: RideLifecycleContext,
        nowMs: Long,
        zone: ZoneId
    ): FinancialDetection {
        val classification = classify(snapshot, lifecycleScreen)
        if (classification.type == FinancialScreenType.UNKNOWN || classification.confidence < ClassifierPolicy.MEDIUM_FROM) {
            return FinancialDetection(classification)
        }
        return when (classification.type) {
            FinancialScreenType.DAILY_EARNINGS_SUMMARY, FinancialScreenType.WEEKLY_EARNINGS_SUMMARY ->
                platformSnapshot(snapshot, classification, nowMs, zone)
            else -> amounts(snapshot, classification, context, nowMs)
        }
    }

    private data class Labeled(val label: MoneyLabel, val amount: com.ubers.observador.domain.money.MoneyAmount, val sameNode: Boolean)

    private fun amounts(
        snapshot: ScreenSnapshot,
        classification: FinancialScreenClassification,
        context: RideLifecycleContext,
        nowMs: Long
    ): FinancialDetection {
        val labels = FinancialSignatures.labelsFor(classification.type)
        val found = ArrayList<Labeled>()
        var unlabeled = 0
        var failures = 0
        var ambiguous = 0
        snapshot.texts.forEachIndexed { i, raw ->
            parser.parseAll(raw).forEach { result ->
                when (result) {
                    is MoneyParseResult.Success -> {
                        val here = labelIn(snapshot.normalizedTexts[i], labels)
                        val before = if (here == null && i > 0) labelIn(snapshot.normalizedTexts[i - 1], labels) else null
                        val label = here ?: before
                        if (label == null) unlabeled++ else found += Labeled(label, result.amount, here != null)
                    }
                    is MoneyParseResult.Ambiguous -> ambiguous++
                    is MoneyParseResult.Invalid -> failures++
                }
            }
        }
        // Two different totals on one screen cannot both be the total: neither is kept
        val totals = found.filter { it.label.semantics == AmountSemantics.ABSOLUTE_TOTAL }.map { it.amount }.distinct()
        val usable = if (totals.size > 1) {
            ambiguous += totals.size
            found.filter { it.label.semantics != AmountSemantics.ABSOLUTE_TOTAL }
        } else {
            found.distinctBy { Triple(it.label.type, it.label.semantics, it.amount) }
        }
        val (session, link) = FinancialCorrelator.link(classification.type, context, nowMs)
        val scope = if (link == LinkConfidence.HIGH) FinancialScope.TRIP else FinancialScope.UNKNOWN
        val groupKey = usable.filter { it.label.semantics == AmountSemantics.COMPONENT }
            .joinToString { "${it.label.type.storageKey}:${it.amount.minorUnits}" }
        val observations = usable.map { item ->
            val id = FinancialIds.observationId(session, item.label.type, item.label.semantics, item.amount, classification.type.name, nowMs)
            FinancialObservation(
                observationId = id,
                observedAtMs = nowMs,
                lifecycleSessionId = session,
                tripId = null, // resolved from the session by the repository, inside the write transaction
                type = item.label.type,
                semantics = item.label.semantics,
                amount = item.amount,
                scope = scope,
                confidence = if (item.sameNode) FinancialEvidence.SAME_NODE_CONFIDENCE else FinancialEvidence.PREVIOUS_NODE_CONFIDENCE,
                evidence = classification.evidence +
                    (if (item.sameNode) FinancialEvidence.LABEL_IN_SAME_NODE else FinancialEvidence.LABEL_IN_PREVIOUS_NODE),
                source = com.ubers.observador.domain.finance.FinancialSource.UBER_SCREEN,
                screenType = classification.type.name,
                linkConfidence = link,
                breakdownGroupId = if (item.label.semantics == AmountSemantics.COMPONENT) {
                    FinancialIds.groupId(session, groupKey, nowMs)
                } else {
                    null
                }
            )
        }
        return FinancialDetection(classification, observations, emptyList(), unlabeled, ambiguous, failures)
    }

    private fun platformSnapshot(
        snapshot: ScreenSnapshot,
        classification: FinancialScreenClassification,
        nowMs: Long,
        zone: ZoneId
    ): FinancialDetection {
        val labels = FinancialSignatures.summaryLabels()
        val amounts = snapshot.texts.flatMapIndexed { i, raw ->
            parser.parseAll(raw).filterIsInstance<MoneyParseResult.Success>().filter { _ ->
                labels.any { snapshot.normalizedTexts[i].contains(it) } ||
                    (i > 0 && labels.any { snapshot.normalizedTexts[i - 1].contains(it) })
            }.map { it.amount }
        }.distinct()
        // One period total expected; several labeled amounts cannot be told apart
        if (amounts.size != 1) return FinancialDetection(classification, ambiguousAmounts = amounts.size)
        val period = if (classification.type == FinancialScreenType.WEEKLY_EARNINGS_SUMMARY) PlatformPeriodType.WEEK else PlatformPeriodType.DAY
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
        val amount = amounts.single()
        return FinancialDetection(
            classification,
            snapshots = listOf(
                PlatformEarningsSnapshot(
                    snapshotId = FinancialIds.snapshotId(period, amount, day),
                    capturedAtMs = nowMs,
                    periodType = period,
                    periodStartMs = null, // Uber's limits are not on screen: never assumed
                    periodEndMs = null,
                    amount = amount,
                    confidence = classification.confidence,
                    screenType = classification.type.name,
                    evidence = classification.evidence
                )
            )
        )
    }

    private fun labelIn(normalized: String, labels: List<MoneyLabel>): MoneyLabel? =
        labels.firstOrNull { label -> label.phrases.any { normalized.contains(TextNormalizer.normalize(it)) } }

    private fun unknown() = FinancialScreenClassification(FinancialScreenType.UNKNOWN, 0.0, emptyList())
}

/**
 * Which trip an amount belongs to. Only one case is strong enough to link automatically: a trip
 * earnings screen while that same lifecycle has just ended (completed or cancelled) within
 * [FinancePolicy.LINK_WINDOW_MS]. Anything else (a tip notice hours later, an earnings screen
 * with no ride just ended) is UNASSIGNED: never linked by price, address or name.
 */
object FinancialCorrelator {

    fun link(screen: FinancialScreenType, context: RideLifecycleContext, nowMs: Long): Pair<String?, LinkConfidence> {
        val session = context.sessionId
        val endedAt = context.lastTransitionAt
        val justEnded = context.state == RideLifecycleState.COMPLETED || context.state == RideLifecycleState.CANCELLED
        return if (screen == FinancialScreenType.TRIP_EARNINGS && justEnded && session != null &&
            endedAt != null && nowMs - endedAt in 0..FinancePolicy.LINK_WINDOW_MS
        ) {
            session to LinkConfidence.HIGH
        } else {
            null to LinkConfidence.LOW
        }
    }
}
