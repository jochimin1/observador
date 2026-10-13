package com.ubers.observador.data.export

import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DistanceTotals
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReport
import com.ubers.observador.domain.earnings.EarningsReports
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.expenses.ExpenseData
import com.ubers.observador.domain.expenses.ExpenseRules
import com.ubers.observador.domain.online.OnlineSummary
import com.ubers.observador.domain.reports.DailyExport
import com.ubers.observador.domain.reports.DayExportRow
import com.ubers.observador.domain.reports.ExpenseExport
import com.ubers.observador.domain.reports.ExportNames
import com.ubers.observador.domain.reports.PeriodSummary
import com.ubers.observador.domain.reports.TripExport
import com.ubers.observador.domain.reports.WeeklyReport
import com.ubers.observador.domain.reports.WeeklyReports
import kotlinx.coroutines.flow.first

/** The files Ganancias and Gastos can export (Fase 8D). */
enum class ReportFile(val label: String, val kind: String, val extension: String, val mimeType: String) {
    TRIPS_CSV("Viajes y ganancias (CSV)", "viajes", "csv", "text/csv"),
    TRIPS_JSON("Viajes y ganancias (JSON)", "viajes", "json", "application/json"),
    EXPENSES_CSV("Gastos (CSV)", "gastos", "csv", "text/csv"),
    EXPENSES_JSON("Gastos (JSON)", "gastos", "json", "application/json"),
    DAILY_CSV("Resumen por jornada (CSV)", "jornadas", "csv", "text/csv"),
    SUMMARY_PDF("Resumen del periodo (PDF)", "resumen", "pdf", "application/pdf")
}

/** A built file, ready to be saved or shared. [rows] is what it holds, for the confirmation. */
class ReportOutput(val fileName: String, val mimeType: String, val bytes: ByteArray, val rows: Int)

/**
 * Everything a report reads, with the same rules as Ganancias (Fase 8C): the report has no
 * formulas of its own. One instance per use; it only reads.
 */
class ReportData(private val db: AppDatabase) {

    private val money = FinancialRepository(db)
    private val trips = TripRepository(db)
    private val online = OnlineRepository(db)
    private val expenses = ExpenseRepository(db)
    private val distances = DistanceRepository(db)

    suspend fun report(window: EarningsWindow, clock: WorkdayClock): EarningsReport {
        val inputs = money.earningsInputs(window.startMs, window.endMs)
        return EarningsReports.build(window, clock, inputs.completedTrips, inputs.summaries, inputs.unassigned)
    }

    suspend fun online(window: EarningsWindow, nowMs: Long): OnlineSummary =
        online.summarize(online.getBetween(window.startMs, window.endMs), window.startMs, window.endMs, nowMs)

    /** The weekly summary of [window], compared with the week before. */
    suspend fun weekly(window: EarningsWindow, clock: WorkdayClock, nowMs: Long, weeklyGoalMinor: Long, workSharePct: Int): WeeklyReport {
        val current = report(window, clock)
        val previousWindow = window.copy(
            offset = window.offset - 1,
            firstDay = window.firstDay.minusDays(7),
            lastDay = window.lastDay.minusDays(7),
            startMs = clock.startOf(window.firstDay.minusDays(7)),
            endMs = window.startMs
        )
        val previous = report(previousWindow, clock)
        val o = online(window, nowMs)
        val data = expenses.getAll()
        val spent = ExpenseRules.summarize(window.firstDay, window.lastDay, clock, data.fuel, data.expenses, data.recurring, workSharePct)
        return WeeklyReports.build(
            current, previous,
            onlineMs = o.confirmedMs.takeIf { o.hasData },
            busyPercent = o.utilizationPercent,
            perHourMinor = o.earnings?.perHour?.minorUnits,
            expensesWorkMinor = spent.workMinor,
            goalMinor = weeklyGoalMinor
        )
    }

    suspend fun build(file: ReportFile, window: EarningsWindow, clock: WorkdayClock, nowMs: Long, workSharePct: Int): ReportOutput {
        val name = ExportNames.of(file.kind, window.firstDay, window.lastDay, file.extension)
        val zone = clock.zone
        return when (file) {
            ReportFile.TRIPS_CSV, ReportFile.TRIPS_JSON -> {
                val all = trips.observeTripsBetween(window.startMs, window.endMs).first()
                val rows = TripExport.rows(all, money.summariesFor(all.map { it.id }), clock)
                val text = if (file == ReportFile.TRIPS_CSV) TripExport.csv(rows, zone)
                else TripExport.json(rows, zone, nowMs, window.firstDay, window.lastDay)
                ReportOutput(name, file.mimeType, text.toByteArray(Charsets.UTF_8), rows.size)
            }
            ReportFile.EXPENSES_CSV, ReportFile.EXPENSES_JSON -> {
                val data = expenses.getAll()
                val rows = ExpenseExport.rows(data.fuel, data.expenses, window.firstDay, window.lastDay, clock)
                val text = if (file == ReportFile.EXPENSES_CSV) ExpenseExport.csv(rows, zone)
                else ExpenseExport.json(rows, zone, nowMs, window.firstDay, window.lastDay)
                ReportOutput(name, file.mimeType, text.toByteArray(Charsets.UTF_8), rows.size)
            }
            ReportFile.DAILY_CSV -> {
                val report = report(window, clock)
                val data = expenses.getAll()
                val sessions = online.getBetween(window.startMs, window.endMs)
                val rows = report.bars.map { bar ->
                    val dayStart = clock.startOf(bar.day)
                    val dayEnd = clock.startOf(bar.day.plusDays(1))
                    val o = if (sessions.isEmpty()) null else online.summarize(sessions, dayStart, dayEnd, nowMs).takeIf { it.hasData }
                    DayExportRow.of(bar, o?.confirmedMs, dayExpenses(data, bar.day, clock, workSharePct), report.summary.currencyCode)
                }
                ReportOutput(name, file.mimeType, DailyExport.csv(rows).toByteArray(Charsets.UTF_8), rows.size)
            }
            ReportFile.SUMMARY_PDF -> {
                val s = periodSummary(window, clock, nowMs, workSharePct)
                ReportOutput(name, file.mimeType, SummaryPdf.render(s), 1)
            }
        }
    }

    suspend fun periodSummary(window: EarningsWindow, clock: WorkdayClock, nowMs: Long, workSharePct: Int): PeriodSummary {
        val report = report(window, clock)
        val o = online(window, nowMs)
        val data = expenses.getAll()
        val spent = ExpenseRules.summarize(window.firstDay, window.lastDay, clock, data.fuel, data.expenses, data.recurring, workSharePct)
        val segments = distances.getAll()
        return PeriodSummary(
            window = window,
            income = report.summary,
            onlineMs = o.confirmedMs.takeIf { o.hasData },
            busyPercent = o.utilizationPercent,
            perHourMinor = o.earnings?.perHour?.minorUnits,
            km = if (segments.isEmpty()) null else DistanceTotals.kmBetween(segments, window.startMs, window.endMs, nowMs),
            expensesWorkMinor = spent.workMinor,
            expensesTotalMinor = spent.totalMinor,
            workSharePct = spent.workSharePct,
            bestDay = report.bestDay.takeIf { window.range != EarningsRange.DAY },
            generatedAtMs = nowMs
        )
    }

    private fun dayExpenses(data: ExpenseData, day: java.time.LocalDate, clock: WorkdayClock, workSharePct: Int): Long =
        ExpenseRules.summarize(day, day, clock, data.fuel, data.expenses, data.recurring, workSharePct).workMinor

    companion object {
        /** The week that just ended, for the Monday notification. */
        fun lastWeek(nowMs: Long, clock: WorkdayClock, weekStart: java.time.DayOfWeek): EarningsWindow =
            EarningsWindows.of(EarningsRange.WEEK, -1, nowMs, clock, weekStart)
    }
}
