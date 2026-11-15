package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.db.*
import com.ubers.observador.data.export.ReportData
import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.earnings.*
import com.ubers.observador.domain.lifecycle.ReadingVersions
import com.ubers.observador.domain.operating.OperatingCostModel
import com.ubers.observador.domain.reports.*
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ClosureRepository(private val db: AppDatabase) {
    fun observe() = db.closureDao().observe()

    /** One transaction: source reads and append share the same SQLite snapshot. Old rows never change. */
    suspend fun close(window: EarningsWindow, clock: WorkdayClock, includeTest: Boolean,
        profitGoal: Long?, timeGoal: Long?, previous: ClosureEntity? = null,
        now: Long = System.currentTimeMillis()): ClosureEntity = MaintenanceGate.shared.access {
        db.withTransaction {
            val end = previous?.endMs ?: minOf(window.endMs, now)
            val start = previous?.startMs ?: window.startMs
            require(end > start) { "El periodo todavía no ha empezado." }
            val c = previous?.let { WorkdayClock(it.startHour, ZoneId.of(it.zone)) } ?: clock
            val test = previous?.includeTest ?: includeTest
            val pg = if (previous == null) profitGoal else previous.profitGoal
            val tg = if (previous == null) timeGoal else previous.timeGoal
            val w = window.copy(startMs = start, endMs = end, firstDay = c.workdayOf(start), lastDay = c.workdayOf(end - 1))
            val scope = previous?.scope ?: UUID.randomUUID().toString()
            val revision = (db.closureDao().lastRevision(scope) ?: 0) + 1
            val reportData = ReportData(db)
            val earnings = reportData.report(w, c, test)
            val operating = OperatingRepository(db).report(w, c, end, test)
            val online = reportData.online(w, end, test)
            val expenses = reportData.expenseData(test)
            val fuel = expenses.fuel.filter { it.atMs >= start && it.atMs < end }
            val paid = expenses.expenses.filter { it.atMs >= start && it.atMs < end }
            val touching = db.tripDao().getAllTrips().filter { TestData.keep(it.id, test) }.map { it.toDomain() }
                .filter { it.createdAt < end && (it.status.isOpen || (it.completedAt ?: it.cancelledAt ?: it.interruptedAt ?: it.updatedAt) >= start) }
            val unfinished = touching.filter { it.status.isOpen || (it.completedAt ?: it.cancelledAt ?: it.interruptedAt ?: Long.MAX_VALUE) >= end }
            val profit = operating.profitMinor.takeIf { unfinished.isEmpty() }
            val lines = mutableListOf<ClosureLine>()
            fun add(section: String, label: String, value: Any?, unit: String = "", ref: String = "") {
                lines += ClosureLine(section, label, value?.toString(), unit, ref)
            }
            fun total(label: String, value: Any?, unit: String = "") = add("Resumen", label, value, unit)
            add("Identificación", "Cierre", scope)
            add("Identificación", "Revisión", revision)
            add("Identificación", "Revisión consultada como base", previous?.revision)
            add("Identificación", "Calculado", Instant.ofEpochMilli(now).atZone(c.zone))
            add("Identificación", "App / compilación", "${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
            add("Identificación", "Modelo / parser", "${ClosureText.ALGORITHM} / ${ReadingVersions.PARSER} / ${OperatingCostModel.ALGORITHM}")
            add("Periodo", "Inicio incluido", Instant.ofEpochMilli(start).atZone(c.zone))
            add("Periodo", "Fin excluido", Instant.ofEpochMilli(end).atZone(c.zone))
            add("Periodo", "Hora de jornada", c.startHour)
            add("Periodo", "Datos TEST", if (test) "Incluidos" else "Excluidos")
            add("Periodo", "Regla de ingresos", "Viajes por finalización; ingresos sin viaje por observación. Una corrección tardía revisa el mismo intervalo.")
            add("Periodo", "Regla de costos", "Kilómetros completos del viaje al cierre y kilómetros vacíos del intervalo. Tiempo en línea recortado al intervalo. Gastos pagados por fecha de registro.")
            add("Periodo", "Regla nocturna", "Un viaje que cruza el corte se cuenta al finalizar, aunque haya empezado antes. El fin del intervalo no se incluye.")
            add("Periodo", "Nota", "El gasto pagado se muestra aparte: no se resta otra vez del beneficio operativo. No es un documento oficial de Uber.")
            total("Viajes cerrados", earnings.trips.size)
            total("Viajes sin cierre al corte", unfinished.size)
            total("Viajes con ingreso confirmado", earnings.summary.confirmedTripCount)
            total("Ingresos confirmados", earnings.summary.totalObservedEarnings, "DOP_centavos")
            total("Ingresos de viajes", earnings.summary.tripEarnings, "DOP_centavos")
            total("Ingresos del periodo sin viaje", earnings.summary.unassignedEarnings, "DOP_centavos")
            total("Importes pendientes de revisar (no sumados)", earnings.pending.size)
            total("Viajes con dinero incompleto", earnings.summary.partialTripCount + earnings.summary.unknownTripCount)
            total("Gasto pagado registrado (sin prorrateo)", fuel.filter { it.currencyCode == "DOP" }.sumOf { it.amountMinor } + paid.filter { it.currencyCode == "DOP" }.sumOf { it.amountMinor }, "DOP_centavos")
            total("Costo operativo calculado (puede ser parcial)", operating.costs.total, "DOP_centavos")
            total("Beneficio operativo", profit, "DOP_centavos")
            total("Tiempo en línea confirmado", online.confirmedMs.takeIf { online.hasData }, "ms")
            total("Tiempo en línea posible", online.possibleMs.takeIf { online.hasData }, "ms")
            total("Kilómetros operativos registrados (pueden ser parciales)", operating.distanceMm.takeIf { operating.gpsCoveragePct != null || operating.trips.any { it.distanceMm != null } }, "mm")
            total("Cobertura GPS", operating.gpsCoveragePct, "%")
            total("Cobertura de ingresos", earnings.summary.coverage, "%")
            add("Metas", "Beneficio deseado", pg, "DOP_centavos")
            add("Metas", "Estado de beneficio", ClosureText.goal(profit, pg))
            add("Metas", "Tiempo máximo en línea", tg, "ms")
            add("Metas", "Estado de tiempo", ClosureText.goal(online.confirmedMs.takeIf { online.hasData && online.confirmedMs == online.possibleMs }, tg, true))
            operating.issues.sortedBy { it.name }.forEach { add("Incidencias", it.name, it.label) }
            unfinished.forEach { add("Incidencias", "Viaje sin cierre al corte", "Estado actual: ${it.status.name}", ref = it.id) }
            earnings.trips.forEach { t ->
                add("Viajes", "Ingreso confirmado", t.real?.minorUnits, "DOP_centavos", t.tripId)
                add("Viajes", "Finalización", Instant.ofEpochMilli(t.completedAt).atZone(c.zone), ref = t.tripId)
                add("Viajes", "Pasajero", t.riderName, ref = t.tripId)
                add("Viajes", "Origen", t.pickupAddress, ref = t.tripId)
                add("Viajes", "Destino", t.dropoffAddress, ref = t.tripId)
                if (t.real == null || t.hasAnomalies || t.pendingReviewCount > 0 || !t.endConfirmed)
                    add("Incidencias", "Revisar viaje y dinero", "${t.completeness}; final confirmado: ${t.endConfirmed}", ref = t.tripId)
            }
            val financialInputs = FinancialRepository(db).earningsInputs(start, end)
            val voided = financialInputs.unassigned.mapNotNull { it.voidsObservationId }.toSet()
            financialInputs.unassigned.filter { TestData.keep(it.observationId, test) && it.observationId !in voided && EarningsReports.isCountedPeriodIncome(it, "DOP") }.forEach {
                add("Ingresos sin viaje", it.type.name, it.amount.minorUnits, "DOP_centavos", it.observationId)
                add("Ingresos sin viaje", "Observado", Instant.ofEpochMilli(it.observedAtMs).atZone(c.zone), ref = it.observationId)
            }
            earnings.pending.forEach { p ->
                val o = p.observation
                add("Pendientes", "${o.type} / ${o.semantics}", o.amount.minorUnits, "${o.amount.currencyCode}_centavos", o.observationId)
                add("Pendientes", "Observado", Instant.ofEpochMilli(o.observedAtMs).atZone(c.zone), ref = o.observationId)
                add("Pendientes", "Evidencia", o.evidence.joinToString("; "), ref = o.observationId)
            }
            fuel.forEach { add("Gastos pagados", "Combustible", it.amountMinor, "${it.currencyCode}_centavos", it.id) }
            paid.forEach { add("Gastos pagados", it.type.label, it.amountMinor, "${it.currencyCode}_centavos", it.id) }
            operating.trips.forEach { t ->
                add("Costos por viaje", "Costo", t.cost.total, "DOP_centavos", t.tripId)
                add("Costos por viaje", "Kilómetros", t.distanceMm, "mm", t.tripId)
                add("Costos por viaje", "Revisiones variable / fija", "${t.modelRevision} / ${t.fixedRevision}", ref = t.tripId)
                t.issues.forEach { add("Incidencias", "Costo / GPS del viaje", it.label, ref = t.tripId) }
            }
            add("Costos", "Costos no asignados a viajes", operating.unallocated.total, "DOP_centavos")
            db.operatingCostDao().getAll().filter { it.toDomain().revision in operating.revisions }.forEach {
                add("Modelos de costos", "Parámetros conservados", it.toDomain(), ref = it.id)
            }
            db.offerDao().getBetween(start, end).filter { TestData.keep(it.offerHash, test) && it.excludedFromAnalysis }.forEach {
                add("Incidencias", "Lectura excluida del análisis", "${it.offerHash}: ${it.readingReasons}; lector ${it.parserVersion ?: "desconocido"}")
            }
            ClosureEntity(UUID.randomUUID().toString(), scope, revision, now, start, end, c.zone.id, c.startHour,
                test, pg, tg, ClosureText.json(lines)).also { db.closureDao().insert(it) }
        }
    }

    companion object {
        fun lines(entity: ClosureEntity): List<ClosureLine> {
            val array = JSONArray(entity.payload)
            return (0 until array.length()).map { i -> array.getJSONObject(i).let {
                ClosureLine(it.getString("section"), it.getString("label"), if (it.isNull("value")) null else it.getString("value"), it.getString("unit"), it.getString("reference"))
            } }
        }
    }
}
