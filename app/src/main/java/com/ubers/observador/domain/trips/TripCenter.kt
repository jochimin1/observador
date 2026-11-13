package com.ubers.observador.domain.trips

import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.finance.FinancialCompleteness
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.domain.money.MoneyAmount
import com.ubers.observador.domain.sectors.SectorMatcher

/**
 * 1.40.0, Centro de Viajes: every trip in one list, whatever happened to it, and its detail on
 * demand. Pure: the screen loads trips and money summaries, this decides rows and filters.
 */
enum class TripStatusFilter(val label: String) {
    ALL("Todos"),
    IN_PROGRESS("En curso"),
    COMPLETED("Completados"),
    CANCELLED("Cancelados"),
    INTERRUPTED("Interrumpidos"),
    RECOVERED("Recuperados"),
    NO_OFFER("Sin oferta");

    fun accepts(t: Trip): Boolean = when (this) {
        ALL -> true
        IN_PROGRESS -> t.status.isOpen
        COMPLETED -> t.status == TripStatus.COMPLETED
        CANCELLED -> t.status == TripStatus.CANCELLED
        INTERRUPTED -> t.status == TripStatus.INTERRUPTED
        RECOVERED -> t.recovered
        NO_OFFER -> t.offer == null
    }
}

/** One line of the list: only what the list shows. The full detail is loaded when opened. */
data class TripRow(
    val trip: Trip,
    /** When the trip began for Observador: acceptance, or the first moment it was seen. */
    val startAt: Long,
    /** The end Ganancias uses, or the cancellation; null while under way. */
    val endAt: Long?,
    val offered: MoneyAmount?,
    /** Same rule as Ganancias: only a confirmed total is real money. */
    val real: MoneyAmount?,
    val completeness: FinancialCompleteness,
    val cancellationFeeMinor: Long,
    /** Counts as a done trip in Ganancias, but its money is not confirmed. */
    val moneyPending: Boolean,
    val hasAnomalies: Boolean,
    val corrected: Boolean
) {
    val id: String get() = trip.id
}

data class TripQuery(
    val status: TripStatusFilter = TripStatusFilter.ALL,
    val product: String? = null,
    val sector: String? = null,
    val moneyPendingOnly: Boolean = false,
    val text: String = "",
    /** Privacy: names are neither shown nor searched. */
    val hideNames: Boolean = false
)

object TripCenter {

    /** The money summary Ganancias would use for [t] (the same fallback when none is stored). */
    fun summaryOf(t: Trip, summaries: Map<String, TripFinancialSummary>, currencyCode: String): TripFinancialSummary =
        summaries[t.id] ?: TripFinancialSummary(
            tripId = t.id, currencyCode = null,
            offeredAmount = t.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, currencyCode) },
            latestAbsoluteTotal = null, effectiveEarnings = null, completeness = FinancialCompleteness.UNKNOWN
        )

    fun rows(
        trips: List<Trip>,
        summaries: Map<String, TripFinancialSummary>,
        correctedIds: Set<String>,
        currencyCode: String
    ): List<TripRow> = trips.map { t ->
        val s = summaryOf(t, summaries, currencyCode)
        val counted = TripOutcome.endedAt(t)
        TripRow(
            trip = t,
            startAt = t.acceptedAt ?: t.pickupNavigationAt ?: t.arrivedAt ?: t.tripStartedAt ?: t.createdAt,
            endAt = counted ?: t.cancelledAt ?: t.interruptedAt,
            offered = s.offeredAmount ?: t.offer?.offeredAmount?.let { MoneyAmount.fromLegacyDouble(it, currencyCode) },
            real = if (s.completeness == FinancialCompleteness.CONFIRMED) s.effectiveEarnings else null,
            completeness = s.completeness,
            cancellationFeeMinor = s.cancellationFee,
            moneyPending = counted != null && s.completeness != FinancialCompleteness.CONFIRMED,
            hasAnomalies = t.hasAnomalies || s.hasAnomalies,
            corrected = t.id in correctedIds
        )
    }.sortedByDescending { it.startAt }

    fun filter(rows: List<TripRow>, q: TripQuery): List<TripRow> {
        val words = SectorMatcher.words(q.text)
        return rows.filter { r ->
            val t = r.trip
            q.status.accepts(t) &&
                (q.product == null || t.offer?.product.equals(q.product, ignoreCase = true)) &&
                (q.sector == null || SectorMatcher.matches(t.offer?.pickupAddress, q.sector) || SectorMatcher.matches(t.offer?.dropoffAddress, q.sector)) &&
                (!q.moneyPendingOnly || r.moneyPending) &&
                (words.isEmpty() || searchable(t, q.hideNames).let { hay -> words.all { w -> hay.any { it.startsWith(w) } } })
        }
    }

    /** The words a trip can be found by: addresses and modality, and the name unless hidden. */
    private fun searchable(t: Trip, hideNames: Boolean): List<String> =
        listOfNotNull(t.offer?.pickupAddress, t.offer?.dropoffAddress, t.offer?.product, t.riderName.takeUnless { hideNames })
            .flatMap { SectorMatcher.words(it) }

    /** Modalities present, for the filter chips. */
    fun products(rows: List<TripRow>): List<String> =
        rows.mapNotNull { it.trip.offer?.product?.trim()?.takeIf(String::isNotEmpty) }.distinctBy { it.lowercase() }.sorted()

    fun statusLabel(t: Trip): String = when (t.status) {
        TripStatus.TO_PICKUP -> "Rumbo a recoger"
        TripStatus.ARRIVED_OR_WAITING -> "Esperando al pasajero"
        TripStatus.IN_TRIP -> "En viaje"
        TripStatus.UNKNOWN -> "En curso (observación perdida)"
        TripStatus.COMPLETED -> "Completado"
        TripStatus.CANCELLED -> "Cancelado"
        TripStatus.INTERRUPTED -> if (TripOutcome.endedAt(t) != null) "Cerrado sin confirmar" else "Interrumpido"
    }

    /** «Pasajero» or the name, as the privacy switch says. */
    fun riderShown(t: Trip, hideNames: Boolean): String? = t.riderName?.trim()?.takeIf { it.isNotEmpty() }?.let { if (hideNames) "Pasajero" else it }
}

/** Score when the offer was seen, and the same offer judged with today's settings. */
data class TripScores(
    val atDetection: Int,
    val detectionSettings: TripDecisionSnapshot?,
    val today: Int,
    val todaySettings: EngineSettings
)

/** What a cancelled trip cost: time, kilometres and a confirmed cancellation fee, if any. */
data class CancelledCost(val lostMs: Long?, val measuredMm: Long?, val feeMinor: Long, val feeConfirmed: Boolean)

/** The wait at the pickup point, from «Llegué» to the start (or the cancellation). */
data class TripWaitTime(val ms: Long, val endedByCancellation: Boolean)

object TripDetails {

    /** Null without the original offer: the score can only be judged on what Uber offered. */
    fun scores(t: Trip, current: EngineSettings, engine: ProfitEngine = ProfitEngine()): TripScores? {
        val o = t.offer ?: return null
        val today = engine.analyze(UberOffer(o.offeredAmount, o.pickupKm, o.pickupMinutes, o.tripKm, o.tripMinutes, detectedAt = o.offerSeenAt), current).score
        return TripScores(o.scoreAtDetection, t.decision, today, current)
    }

    fun cancelled(t: Trip, measuredMm: Long?, summary: TripFinancialSummary?): CancelledCost? {
        if (t.status != TripStatus.CANCELLED) return null
        val from = t.acceptedAt ?: t.pickupNavigationAt ?: t.createdAt
        val lost = t.cancelledAt?.let { it - from }?.takeIf { it >= 0 }
        val fee = summary?.cancellationFee ?: 0L
        return CancelledCost(lost, measuredMm, fee, fee > 0 && summary?.completeness == FinancialCompleteness.CONFIRMED)
    }

    fun waitAtPickup(t: Trip): TripWaitTime? {
        val arrived = t.arrivedAt ?: return null
        t.tripStartedAt?.let { return TripWaitTime(it - arrived, false).takeIf { w -> w.ms >= 0 } }
        t.cancelledAt?.let { return TripWaitTime(it - arrived, true).takeIf { w -> w.ms >= 0 } }
        return null
    }

    /** Timeline with every ending, cancellation included (Ganancias only shows done trips). */
    fun timeline(t: Trip): List<Pair<String, Long>> = listOfNotNull(
        t.offer?.offerSeenAt?.let { "Oferta vista" to it },
        t.acceptedAt?.let { "Aceptado" to it },
        t.pickupNavigationAt?.let { "Rumbo a recoger" to it },
        t.arrivedAt?.let { "Llegaste al punto" to it },
        t.tripStartedAt?.let { "Viaje iniciado" to it },
        t.completedAt?.let { "Viaje terminado" to it },
        t.cancelledAt?.let { "Viaje cancelado" to it },
        t.interruptedAt?.takeIf { t.completedAt == null && t.cancelledAt == null }?.let {
            (if (TripOutcome.endedAt(t) != null) "Uber volvió a buscando (cierre sin confirmar)" else "Observación cerrada sin final visto") to it
        }
    ).sortedBy { it.second }

    fun eventLabel(type: TripEventType): String = when (type) {
        TripEventType.ACCEPTED -> "Aceptado"
        TripEventType.PICKUP_NAVIGATION -> "Rumbo a recoger"
        TripEventType.ARRIVED -> "Llegada al punto"
        TripEventType.TRIP_STARTED -> "Viaje iniciado"
        TripEventType.TRIP_COMPLETED -> "Viaje terminado"
        TripEventType.TRIP_CANCELLED -> "Viaje cancelado"
        TripEventType.LIFECYCLE_RECOVERED -> "Observación recuperada"
        TripEventType.LIFECYCLE_LOST -> "Observación perdida"
        TripEventType.INTERRUPTED -> "Cerrado sin final visto"
    }

    fun anomalyLabel(code: TripAnomalyCode): String = when (code) {
        TripAnomalyCode.INVALID_TRANSITION -> "Cambio de estado imposible"
        TripAnomalyCode.EVENT_OUT_OF_ORDER -> "Evento fuera de orden"
        TripAnomalyCode.MULTIPLE_ACTIVE_TRIPS -> "Dos viajes abiertos a la vez"
        TripAnomalyCode.TERMINAL_STATE_CONFLICT -> "Final en conflicto"
        TripAnomalyCode.ORPHAN_EVENT -> "Evento sin viaje"
    }
}

// ---- Corrections (1.40.0): every change by hand or from Uber leaves a record ----

enum class CorrectionField(val storageKey: String, val label: String) {
    RIDER_NAME("RIDER_NAME", "Nombre del pasajero"),
    STATUS("STATUS", "Estado");

    companion object { fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } }
}

enum class CorrectionOrigin(val storageKey: String, val label: String) {
    /** Read from Uber's screen by the service. */
    UBER_SCREEN("UBER_SCREEN", "Pantalla de Uber"),
    /** Changed by the driver in Centro de Viajes. */
    DRIVER("DRIVER", "Tú"),
    /** Undone by the driver: the previous value is back. */
    DRIVER_UNDO("DRIVER_UNDO", "Tú (deshacer)");

    companion object { fun fromStorage(key: String?) = entries.firstOrNull { it.storageKey == key } }
}

/** One stored correction. Never edited or deleted: an undo is another correction. */
data class TripCorrection(
    val id: String,
    val tripId: String,
    val atMs: Long,
    val field: CorrectionField,
    val oldValue: String?,
    val newValue: String?,
    val origin: CorrectionOrigin
)

object TripCorrections {

    /** Closed trips can be re-labelled among the three endings; open trips are left to the lifecycle. */
    val CORRECTABLE = listOf(TripStatus.COMPLETED, TripStatus.CANCELLED, TripStatus.INTERRUPTED)

    fun canCorrectStatus(t: Trip, to: TripStatus): Boolean = t.status in CORRECTABLE && to in CORRECTABLE && to != t.status

    /** «STATUS;completedAt;cancelledAt»: what an undo needs to put the trip back exactly. */
    fun encodeStatus(t: Trip): String = "${t.status.storageKey};${t.completedAt ?: ""};${t.cancelledAt ?: ""}"

    data class StatusValue(val status: TripStatus, val completedAt: Long?, val cancelledAt: Long?)

    fun decodeStatus(v: String?): StatusValue? {
        val p = v?.split(';') ?: return null
        if (p.size != 3) return null
        val status = TripStatus.entries.firstOrNull { it.storageKey == p[0] } ?: return null
        return StatusValue(status, p[1].toLongOrNull(), p[2].toLongOrNull())
    }

    /**
     * The trip relabelled as [to]. The end time is the one Observador already had (the seen end,
     * or the real end estimated as Ganancias does); «Uber volvió a buscando» stays recorded.
     */
    fun correctStatus(t: Trip, to: TripStatus, nowMs: Long, routeEndAt: Long? = null): Trip {
        require(canCorrectStatus(t, to)) { "status ${t.status} cannot become $to" }
        val end = TripOutcome.realEndAt(t, routeEndAt) ?: t.interruptedAt ?: t.updatedAt
        return when (to) {
            TripStatus.COMPLETED -> t.copy(status = to, completedAt = t.completedAt ?: end, cancelledAt = null, updatedAt = nowMs)
            TripStatus.CANCELLED -> t.copy(status = to, cancelledAt = t.cancelledAt ?: end, completedAt = null, updatedAt = nowMs)
            else -> t.copy(status = to, completedAt = null, cancelledAt = null, interruptedAt = t.interruptedAt ?: end, updatedAt = nowMs)
        }
    }

    /** Puts back what [old] recorded. */
    fun restoreStatus(t: Trip, old: StatusValue, nowMs: Long): Trip =
        t.copy(status = old.status, completedAt = old.completedAt, cancelledAt = old.cancelledAt, updatedAt = nowMs)

    /** A name as stored: trimmed, at most 60 characters, null when empty. */
    fun cleanName(name: String?): String? = name?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
}
