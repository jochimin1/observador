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
import com.ubers.observador.data.RouteRepository
import com.ubers.observador.domain.route.RouteFiles
import com.ubers.observador.domain.route.TripRouteSummary
import kotlinx.coroutines.flow.first

/** The files Ganancias and Gastos can export (Fase 8D). */
enum class ReportFile(val label: String, val kind: String, val extension: String, val mimeType: String) {
    TRIPS_CSV("Viajes y ganancias (CSV)", "viajes", "csv", "text/csv"),
    TRIPS_JSON("Viajes y ganancias (JSON)", "viajes", "json", "application/json"),
    EXPENSES_CSV("Gastos (CSV)", "gastos", "csv", "text/csv"),
    EXPENSES_JSON("Gastos (JSON)", "gastos", "json", "application/json"),
    DAILY_CSV("Resumen por jornada (CSV)", "jornadas", "csv", "text/csv"),
    SUMMARY_PDF("Resumen del periodo (PDF)", "resumen", "pdf", "application/pdf"),
    // Fase 8K: the only files with coordinates (and the rider's name, decision 9)
    ROUTES_GPX("Recorridos de los viajes (GPX)", "recorridos", "gpx", RouteFiles.GPX_MIME),
    ROUTES_GEOJSON("Recorridos de los viajes (GeoJSON)", "recorridos", "geojson", RouteFiles.GEOJSON_MIME)
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
    private val routes = RouteRepository(db)

    /** Fase 8J: test rows (ids and hashes starting with TEST-) are left out unless asked for. */
    private fun keep(id: String, includeTest: Boolean) = includeTest || !id.startsWith(TEST_PREFIX)

    suspend fun report(window: EarningsWindow, clock: WorkdayClock, includeTest: Boolean = true): EarningsReport {
        val inputs = money.earningsInputs(window.startMs, window.endMs)
        val completed = inputs.completedTrips.filter { keep(it.id, includeTest) }
        val unassigned = inputs.unassigned.filter { keep(it.observationId, includeTest) }
        return EarningsReports.build(window, clock, completed, inputs.summaries, unassigned)
    }

    suspend fun online(window: EarningsWindow, nowMs: Long, includeTest: Boolean = true): OnlineSummary =
        online.summarize(
            online.getBetween(window.startMs, window.endMs).filter { keep(it.id, includeTest) },
            window.startMs, window.endMs, nowMs
        )

    /** Fill-ups, expenses and fixed costs, without test rows unless asked for. */
    suspend fun expenseData(includeTest: Boolean = true): ExpenseData {
        val all = expenses.getAll()
        return if (includeTest) all else all.copy(
            fuel = all.fuel.filter { keep(it.id, includeTest) },
            expenses = all.expenses.filter { keep(it.id, includeTest) },
            recurring = all.recurring.filter { keep(it.id, includeTest) }
        )
    }

    /** Fase 8J: what «Reportes y exportación» reads for a period, before any filter. */
    class OfferSource(
        val rows: List<com.ubers.observador.domain.reports.OfferReportRow>,
        val trips: List<com.ubers.observador.domain.trips.Trip>,
        val expenses: ExpenseData,
        val report: EarningsReport,
        /** Fase 8K: the route summaries of [trips], by trip id. */
        val routes: Map<String, TripRouteSummary> = emptyMap()
    )

    suspend fun offerSource(
        window: EarningsWindow,
        clock: WorkdayClock,
        engine: com.ubers.observador.domain.EngineSettings,
        cuts: com.ubers.observador.domain.QualityThresholds,
        includeTest: Boolean
    ): OfferSource {
        val offers = db.offerDao().getBetween(window.startMs, window.endMs).filter { keep(it.offerHash, includeTest) }
        val periodTrips = trips.observeTripsBetween(window.startMs, window.endMs).first().filter { keep(it.id, includeTest) }
        val summaries = money.summariesFor(periodTrips.map { it.id })
        val rows = com.ubers.observador.domain.reports.OfferReport.rows(offers, periodTrips, summaries, engine, cuts, clock)
        return OfferSource(rows, periodTrips, expenseData(includeTest), report(window, clock, includeTest), routes.routesOf(periodTrips.map { it.id }))
    }

    /** The trips file for a chosen set of trips (Fase 8J, filtered on the export screen). */
    suspend fun tripsFile(
        list: List<com.ubers.observador.domain.trips.Trip>,
        csv: Boolean,
        window: EarningsWindow,
        clock: WorkdayClock,
        nowMs: Long
    ): ReportOutput {
        val name = ExportNames.of("viajes", window.firstDay, window.lastDay, if (csv) "csv" else "json")
        val rows = TripExport.rows(list, money.summariesFor(list.map { it.id }), clock, routes = routes.routesOf(list.map { it.id }))
        val text = if (csv) TripExport.csv(rows, clock.zone) else TripExport.json(rows, clock.zone, nowMs, window.firstDay, window.lastDay)
        return ReportOutput(name, if (csv) "text/csv" else "application/json", text.toByteArray(Charsets.UTF_8), rows.size)
    }

    /**
     * Fase 8K: the routes of [list] in one GPX or GeoJSON file, one track per trip with points.
     * [ReportOutput.rows] is the number of trips in it.
     */
    suspend fun routesFile(
        list: List<com.ubers.observador.domain.trips.Trip>,
        gpx: Boolean,
        firstDay: java.time.LocalDate,
        lastDay: java.time.LocalDate,
        zone: java.time.ZoneId,
        nowMs: Long
    ): ReportOutput {
        val ids = list.map { it.id }
        val summaries = routes.routesOf(ids)
        val points = routes.pointsOf(ids)
        val tracks = list.sortedBy { it.acceptedAt ?: it.createdAt }.mapNotNull { t ->
            val s = summaries[t.id]?.takeIf { it.hasPoints } ?: return@mapNotNull null
            val pts = points[t.id].orEmpty().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            RouteFiles.track(t, s, pts, zone)
        }
        val ext = if (gpx) "gpx" else "geojson"
        val text = if (gpx) RouteFiles.gpx(tracks, nowMs) else RouteFiles.geoJson(tracks, nowMs)
        return ReportOutput(
            ExportNames.of("recorridos", firstDay, lastDay, ext),
            if (gpx) RouteFiles.GPX_MIME else RouteFiles.GEOJSON_MIME,
            text.toByteArray(Charsets.UTF_8),
            tracks.size
        )
    }

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

    suspend fun build(
        file: ReportFile,
        window: EarningsWindow,
        clock: WorkdayClock,
        nowMs: Long,
        workSharePct: Int,
        includeTest: Boolean = true,
        extraPdfSections: List<com.ubers.observador.domain.reports.PeriodTotalsText.Section> = emptyList()
    ): ReportOutput {
        val name = ExportNames.of(file.kind, window.firstDay, window.lastDay, file.extension)
        val zone = clock.zone
        return when (file) {
            ReportFile.TRIPS_CSV, ReportFile.TRIPS_JSON -> {
                val all = trips.observeTripsBetween(window.startMs, window.endMs).first().filter { keep(it.id, includeTest) }
                val rows = TripExport.rows(all, money.summariesFor(all.map { it.id }), clock, routes = routes.routesOf(all.map { it.id }))
                val text = if (file == ReportFile.TRIPS_CSV) TripExport.csv(rows, zone)
                else TripExport.json(rows, zone, nowMs, window.firstDay, window.lastDay)
                ReportOutput(name, file.mimeType, text.toByteArray(Charsets.UTF_8), rows.size)
            }
            ReportFile.EXPENSES_CSV, ReportFile.EXPENSES_JSON -> {
                val data = expenseData(includeTest)
                val rows = ExpenseExport.rows(data.fuel, data.expenses, window.firstDay, window.lastDay, clock)
                val text = if (file == ReportFile.EXPENSES_CSV) ExpenseExport.csv(rows, zone)
                else ExpenseExport.json(rows, zone, nowMs, window.firstDay, window.lastDay)
                ReportOutput(name, file.mimeType, text.toByteArray(Charsets.UTF_8), rows.size)
            }
            ReportFile.DAILY_CSV -> {
                val report = report(window, clock, includeTest)
                val data = expenseData(includeTest)
                val sessions = online.getBetween(window.startMs, window.endMs).filter { keep(it.id, includeTest) }
                val rows = report.bars.map { bar ->
                    val dayStart = clock.startOf(bar.day)
                    val dayEnd = clock.startOf(bar.day.plusDays(1))
                    val o = if (sessions.isEmpty()) null else online.summarize(sessions, dayStart, dayEnd, nowMs).takeIf { it.hasData }
                    DayExportRow.of(bar, o?.confirmedMs, dayExpenses(data, bar.day, clock, workSharePct), report.summary.currencyCode)
                }
                ReportOutput(name, file.mimeType, DailyExport.csv(rows).toByteArray(Charsets.UTF_8), rows.size)
            }
            ReportFile.SUMMARY_PDF -> {
                val s = periodSummary(window, clock, nowMs, workSharePct, includeTest)
                ReportOutput(name, file.mimeType, SummaryPdf.render(s, extra = extraPdfSections), 1)
            }
            ReportFile.ROUTES_GPX, ReportFile.ROUTES_GEOJSON -> {
                val all = trips.observeTripsBetween(window.startMs, window.endMs).first().filter { keep(it.id, includeTest) }
                routesFile(all, file == ReportFile.ROUTES_GPX, window.firstDay, window.lastDay, zone, nowMs)
            }
        }
    }

    suspend fun periodSummary(window: EarningsWindow, clock: WorkdayClock, nowMs: Long, workSharePct: Int, includeTest: Boolean = true): PeriodSummary {
        val report = report(window, clock, includeTest)
        val o = online(window, nowMs, includeTest)
        val data = expenseData(includeTest)
        val spent = ExpenseRules.summarize(window.firstDay, window.lastDay, clock, data.fuel, data.expenses, data.recurring, workSharePct)
        val segments = distances.getAll().filter { keep(it.id, includeTest) }
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
        const val TEST_PREFIX = "TEST-"

        /** The week that just ended, for the Monday notification. */
        fun lastWeek(nowMs: Long, clock: WorkdayClock, weekStart: java.time.DayOfWeek): EarningsWindow =
            EarningsWindows.of(EarningsRange.WEEK, -1, nowMs, clock, weekStart)
    }
}
