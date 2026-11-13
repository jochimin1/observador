package com.ubers.observador.domain.finance

import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.trips.Trip
import java.math.BigDecimal

/** One explicit edit. A component belongs inside a total; a delta is additional to that total. */
data class ManualMoneyRequest(
    val commandId: String,
    val type: FinancialObservationType,
    val semantics: AmountSemantics,
    val amountText: String,
    val replacesId: String? = null
)

sealed interface MoneyEditResult {
    data class Saved(val commandId: String) : MoneyEditResult
    data class Rejected(val reason: String) : MoneyEditResult
}

/** Append-only batches use existing ledger fields. No monetary value is overwritten. */
object ManualMoney {
    const val ENTRY = "MANUAL_MONEY_ENTRY"
    const val ACTION = "MANUAL_MONEY_ACTION"
    const val COMMAND = "MONEY_COMMAND:"
    const val REPLACES = "MONEY_REPLACES:"
    const val UNDO = "MONEY_UNDO:"
    const val RESTORED = "MONEY_RESTORED"
    const val RECORDED_AT = "MONEY_RECORDED_AT:"
    val types = listOf(FinancialObservationType.TRIP_TOTAL, FinancialObservationType.TIP,
        FinancialObservationType.ADJUSTMENT, FinancialObservationType.COMMISSION,
        FinancialObservationType.CANCELLATION_FEE, FinancialObservationType.FARE,
        FinancialObservationType.PROMOTION, FinancialObservationType.CASH_COLLECTED,
        FinancialObservationType.DIGITAL_COLLECTED)

    fun parse(text: String): MoneyAmount? {
        val raw = text.trim()
        if (!Regex("-?[0-9]{1,8}(?:[.,][0-9]{1,2})?").matches(raw)) return null
        return runCatching { MoneyAmount(BigDecimal(raw.replace(',', '.')).movePointRight(2).longValueExact(), Currencies.DOP) }.getOrNull()
    }

    fun validate(trip: Trip, request: ManualMoneyRequest): String? {
        if (trip.status.isOpen) return "Cierra el viaje antes de registrar su dinero."
        if (!Regex("[A-Za-z0-9_-]{1,80}").matches(request.commandId)) return "Identificador de cambio no válido."
        if (request.type !in types) return "Ese tipo de importe no se puede introducir."
        val amount = parse(request.amountText) ?: return "Escribe un importe sin separadores de miles y con hasta dos decimales."
        if (amount.isNegative && request.type != FinancialObservationType.ADJUSTMENT) return "Escribe un importe positivo; las comisiones se descuentan automáticamente."
        if (request.type == FinancialObservationType.TRIP_TOTAL && request.semantics != AmountSemantics.ABSOLUTE_TOTAL) return "El total debe sustituir el total anterior."
        if (request.type != FinancialObservationType.TRIP_TOTAL && request.semantics == AmountSemantics.ABSOLUTE_TOTAL) return "Solo el total del viaje puede confirmar el ingreso."
        if (request.type.isCollection && request.semantics != AmountSemantics.COMPONENT) return "Un cobro no es un ingreso adicional."
        return null
    }

    fun id(tripId: String, commandId: String, suffix: String): String =
        (if (tripId.startsWith("TEST-")) "TEST-" else "") + "money-$commandId-$suffix"

    fun rows(trip: Trip, request: ManualMoneyRequest, original: FinancialObservation?, now: Long): List<FinancialObservation> {
        require(validate(trip, request) == null)
        val raw = requireNotNull(parse(request.amountText))
        val amount = if (request.type == FinancialObservationType.COMMISSION) -raw else raw
        val codes = listOf(ENTRY, COMMAND + request.commandId) + listOfNotNull(original?.let { REPLACES + it.observationId })
        val value = FinancialObservation(id(trip.id, request.commandId, "value"), now, trip.lifecycleSessionId,
            trip.id, request.type, request.semantics, amount, FinancialScope.TRIP, 1.0, codes,
            FinancialSource.MANUAL, null, LinkConfidence.HIGH)
        return listOfNotNull(original?.let { void(it, request.commandId, now, listOf(ACTION, COMMAND + request.commandId, REPLACES + it.observationId)) }, value)
    }

    fun void(target: FinancialObservation, command: String, now: Long, evidence: List<String>) = target.copy(
        observationId = id(requireNotNull(target.tripId), command, "void"), observedAtMs = now,
        type = FinancialObservationType.VOID, semantics = AmountSemantics.DELTA,
        amount = MoneyAmount.zero(target.amount.currencyCode), source = FinancialSource.MANUAL,
        confidence = 1.0, linkConfidence = LinkConfidence.HIGH, evidence = evidence,
        voidsObservationId = target.observationId, breakdownGroupId = null
    )

    fun live(history: List<FinancialObservation>): List<FinancialObservation> {
        val voided = history.filter { it.type == FinancialObservationType.VOID }.mapNotNull { it.voidsObservationId }.toSet()
        return history.filter { it.type != FinancialObservationType.VOID && it.observationId !in voided }
    }

    fun command(row: FinancialObservation): String? = row.evidence.firstOrNull { it.startsWith(COMMAND) }?.removePrefix(COMMAND)

    /** Restore original observation time and source, so undo also restores which deltas its total absorbed. */
    fun undoRows(trip: Trip, commandId: String, history: List<FinancialObservation>, undoId: String, now: Long): List<FinancialObservation>? {
        val batch = history.filter { command(it) == commandId && it.evidence.none { code -> code.startsWith(UNDO) } }
        if (batch.isEmpty() || history.any { UNDO + commandId in it.evidence }) return null
        val value = batch.firstOrNull { it.type != FinancialObservationType.VOID }
        if (value != null && live(history).none { it.observationId == value.observationId }) return null
        val originalId = batch.flatMap { it.evidence }.firstOrNull { it.startsWith(REPLACES) }?.removePrefix(REPLACES)
        val original = originalId?.let { id -> history.firstOrNull { it.observationId == id } } ?: if (originalId != null) return null else null
        val evidence = listOf(ACTION, COMMAND + undoId, UNDO + commandId)
        val restored = original?.copy(observationId = id(trip.id, undoId, "restore"),
            evidence = original.evidence.filterNot { it.startsWith(COMMAND) || it.startsWith(REPLACES) || it.startsWith(UNDO) || it.startsWith(RECORDED_AT) } + RESTORED + (RECORDED_AT + now) + evidence,
            voidsObservationId = null)
        return listOfNotNull(value?.let { void(it, undoId, now, evidence) }, restored)
    }

}

data class TripCollections(val cash: Long, val digital: Long)
object MoneyExplanation {
    fun reason(summary: TripFinancialSummary): String = when (summary.completeness) {
        FinancialCompleteness.CONFIRMED -> "Hay un total de ingreso confirmado. Los componentes incluidos no se suman otra vez."
        FinancialCompleteness.PARTIAL -> "Hay componentes o pagos adicionales, pero falta el total del viaje."
        FinancialCompleteness.UNKNOWN -> "Todavía no hay un total ni componentes de ingreso válidos. El precio ofertado no lo sustituye."
    }

    fun collections(history: List<FinancialObservation>, currency: String = Currencies.DOP): TripCollections {
        val live = ManualMoney.live(history).filter { it.amount.currencyCode == currency && it.confidence >= FinancePolicy.MIN_CONFIDENCE && !it.amount.isNegative }
        return TripCollections(live.filter { it.type == FinancialObservationType.CASH_COLLECTED }.sumOf { it.amount.minorUnits },
            live.filter { it.type == FinancialObservationType.DIGITAL_COLLECTED }.sumOf { it.amount.minorUnits })
    }
}

/** User-declared reference limits, never inferred from the driver's workday. */
object ExplicitMoneyPeriod {
    fun parse(start: String, end: String, zone: java.time.ZoneId): Pair<Long, Long>? = runCatching {
        val format = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        fun instant(text: String): Long {
            val local = java.time.LocalDateTime.parse(text.trim(), format)
            require(zone.rules.getValidOffsets(local).size == 1) // no ambiguous or nonexistent wall time
            return local.atZone(zone).toInstant().toEpochMilli()
        }
        val from = instant(start)
        val to = instant(end)
        require(to > from && to - from <= 366L * 86_400_000)
        from to to
    }.getOrNull()
}
