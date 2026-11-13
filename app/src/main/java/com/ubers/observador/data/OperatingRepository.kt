package com.ubers.observador.data

import androidx.room.withTransaction
import com.ubers.observador.data.db.*
import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.earnings.*
import com.ubers.observador.domain.finance.*
import com.ubers.observador.domain.operating.*
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

class OperatingRepository(private val db: AppDatabase) {
    fun observeModels() = db.operatingCostDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    /** First setup may cover today explicitly. Revisions only start on later, future workdays. */
    suspend fun save(input: OperatingCostInput, clock: WorkdayClock, now: Long): String? = MaintenanceGate.shared.access {
        db.withTransaction {
            val values = input.values() ?: return@withTransaction "Revisa los importes: sin miles, hasta dos decimales; rendimiento hasta tres."
            val day = runCatching { LocalDate.parse(input.effectiveDay.trim()) }.getOrNull()
                ?: return@withTransaction "Escribe la fecha como AAAA-MM-DD."
            val existing = db.operatingCostDao().getAll().map { it.toDomain() }
            val today = clock.workdayOf(now)
            val last = existing.maxOfOrNull { it.effectiveAtMs }
            if (day < today || (existing.isNotEmpty() && day <= today) || (last != null && clock.startOf(day) <= last))
                return@withTransaction "La primera configuración puede empezar hoy. Una revisión debe empezar en una jornada futura, posterior a la última vigencia."
            if (day > today.plusYears(5)) return@withTransaction "La fecha debe estar dentro de los próximos cinco años."
            val model = OperatingCostModel(UUID.randomUUID().toString(), (existing.maxOfOrNull { it.revision } ?: 0) + 1,
                day.toString(), clock.startOf(day), now, clock.zone.id, clock.startHour,
                values[0], values[1], values[2], values[3], values[4], input.basis, values[5].toInt())
            if (!model.valid() || values[5] !in 0..100) return@withTransaction "Precio y rendimiento deben ser positivos; costos de 0 a RD$100,000; uso laboral de 0 a 100 %."
            db.operatingCostDao().insert(model.toEntity())
            null
        }
    }

    suspend fun report(window: EarningsWindow, clock: WorkdayClock, now: Long, includeTest: Boolean): OperatingReport = MaintenanceGate.shared.access {
        db.withTransaction {
            val trips = TestData.filter(db.tripDao().getTripsTouching(window.startMs, minOf(now, window.endMs)), includeTest) { it.id }.map { it.toDomain() }
            val ids = trips.map { it.id }
            val money = ids.chunked(500).flatMap { db.financialDao().getSummaries(it) }.associate { it.tripId to it.toDomain("DOP") }
            val distance = ids.chunked(500).flatMap { db.distanceDao().getTripDistances(it) }.associate { it.tripId to it.toDomain("") }
            val input = FinancialRepository(db).earningsInputs(window.startMs, window.endMs)
            val income = EarningsReports.build(window, clock, TestData.filter(input.completedTrips, includeTest) { it.id },
                input.summaries, TestData.filter(input.unassigned, includeTest) { it.observationId }).summary
            OperatingCosts.report(window, clock, now, db.operatingCostDao().getAll().map { it.toDomain() }, trips, money, distance,
                TestData.filter(db.distanceDao().getAllSegments(), includeTest) { it.id }.map { it.toDomain() },
                TestData.filter(db.onlineDao().getBetween(window.startMs, window.endMs), includeTest) { it.id }.map { it.toDomain() },
                TestData.filter(db.expenseDao().getExpenses(), includeTest) { it.id }.map { it.toDomain() }, income)
        }
    }
}
