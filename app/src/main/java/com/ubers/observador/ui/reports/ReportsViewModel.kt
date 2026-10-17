package com.ubers.observador.ui.reports

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.export.ReportData
import com.ubers.observador.data.export.ReportFile
import com.ubers.observador.data.export.ReportOutput
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.TakenFilter
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.earnings.EarningsFormat
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsWindow
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.expenses.ExpenseRules
import com.ubers.observador.domain.reports.ButtonFilter
import com.ubers.observador.domain.reports.ExpenseExport
import com.ubers.observador.domain.reports.ExportHistory
import com.ubers.observador.domain.reports.ExportNames
import com.ubers.observador.domain.reports.ExportRecord
import com.ubers.observador.domain.reports.OfferOrigin
import com.ubers.observador.domain.reports.OfferReport
import com.ubers.observador.domain.reports.OfferReportRow
import com.ubers.observador.domain.reports.PeriodTotals
import com.ubers.observador.domain.reports.PeriodTotalsBuilder
import com.ubers.observador.domain.reports.PeriodTotalsText
import com.ubers.observador.domain.reports.ReportFilters
import com.ubers.observador.domain.reports.ReportPeriod
import com.ubers.observador.domain.reports.ReportPeriods
import com.ubers.observador.domain.reports.ReportPreset
import com.ubers.observador.domain.reports.ZipPack
import com.ubers.observador.ui.components.ReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** What can be exported. [formats] false: one fixed format (CSV for the days, PDF for the summary). */
enum class ReportKind(val label: String, val formats: Boolean, val filtered: Boolean) {
    OFFERS("Ofertas y viajes (detalle)", true, true),
    TOTALS("Totales del periodo", true, true),
    TRIPS("Viajes y ganancias", true, true),
    EXPENSES("Gastos", true, false),
    DAILY("Resumen por jornada (CSV)", false, false),
    SUMMARY_PDF("Resumen del periodo (PDF)", false, true),
    /** Fase 8K: GPX and GeoJSON of the taken trips that pass the filters. */
    ROUTES("Recorridos (GPX y GeoJSON)", false, true)
}

enum class ReportFormat(val label: String) { CSV("CSV"), JSON("JSON") }

/** Ganancias, Gastos and Inicio open the export screen with the period they show. */
object ReportsNavigation {
    const val ROUTE = "reports"
    val preset = MutableStateFlow<ReportPreset?>(null)

    fun forWindow(w: EarningsWindow): ReportPreset = ReportPeriods.forWindow(w)

    fun forStats(p: com.ubers.observador.domain.StatsPeriod): ReportPreset = ReportPeriods.forStats(p, LocalDate.now())
}

/** How many of the period's offers each chip of a filter would keep (with no other filter). */
data class FilterCounts(
    val origin: Map<TakenFilter, Int> = emptyMap(),
    val quality: Map<CategoryFilter, Int> = emptyMap(),
    val button: Map<ButtonFilter, Int> = emptyMap(),
    val products: List<Pair<String, Int>> = emptyList()
)

data class ReportsUiState(
    val loading: Boolean = true,
    val period: ReportPeriod = ReportPeriod.THIS_WEEK,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val window: EarningsWindow? = null,
    val periodLabel: String = "",
    val filters: ReportFilters = ReportFilters(),
    val filterCounts: FilterCounts = FilterCounts(),
    /** Rows of each file with the current filters; 0 = "Sin datos en este periodo". */
    val counts: Map<ReportKind, Int> = emptyMap(),
    val selected: Set<ReportKind> = setOf(ReportKind.OFFERS, ReportKind.TOTALS),
    val format: ReportFormat = ReportFormat.CSV,
    val totals: PeriodTotals? = null,
    val history: List<ExportRecord> = emptyList(),
    val includeTest: Boolean = false,
    val failed: Boolean = false
) {
    /** Files that would really be written: chosen and with data. */
    val ready: List<ReportKind> get() = ReportKind.entries.filter { it in selected && (counts[it] ?: 0) > 0 }
}

/**
 * «Reportes y exportación» (Fase 8J). Reads the period once (offers, trips, money, expenses), then
 * filters, counts and totals in memory, so changing a chip is instant. Files are built only when
 * the driver saves or shares, with the same builders as the rest of the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val data = ReportData(db)
    private val settings = AppSettings(app)

    val exporter = ReportExporter(app, viewModelScope)

    private data class PeriodChoice(val period: ReportPeriod, val from: LocalDate?, val to: LocalDate?)

    private val choice = MutableStateFlow(
        ReportsNavigation.preset.value?.let { PeriodChoice(it.period, it.from, it.to) } ?: PeriodChoice(ReportPeriod.THIS_WEEK, null, null)
    ).also { ReportsNavigation.preset.value = null }
    private val filters = MutableStateFlow(ReportFilters())
    private val selected = MutableStateFlow(setOf(ReportKind.OFFERS, ReportKind.TOTALS))
    private val format = MutableStateFlow(ReportFormat.CSV)
    private val includeTest = MutableStateFlow(false)
    private val reload = MutableStateFlow(0)

    private class Loaded(
        val choice: PeriodChoice,
        val window: EarningsWindow?,
        val label: String,
        val clock: WorkdayClock,
        val source: ReportData.OfferSource?,
        val workSharePct: Int,
        val failed: Boolean
    )

    private val config = combine(settings.workdayStartHour, settings.goals, settings.engineSettings, settings.qualityThresholds, settings.workSharePct) { h, g, e, c, w ->
        arrayOf<Any>(h, g.weekStart, e, c, w)
    }

    private val loaded = combine(choice, includeTest, config, reload) { c, test, cfg, _ -> Triple(c, test, cfg) }
        .mapLatest { (c, test, cfg) ->
            val clock = WorkdayClock(cfg[0] as Int, ZoneId.systemDefault())
            val weekStart = cfg[1] as java.time.DayOfWeek
            val window = ReportPeriods.windowOf(c.period, c.from, c.to, System.currentTimeMillis(), clock, weekStart)
            try {
                val source = window?.let {
                    data.offerSource(
                        it, clock, cfg[2] as com.ubers.observador.domain.EngineSettings,
                        cfg[3] as com.ubers.observador.domain.QualityThresholds, test
                    )
                }
                Loaded(c, window, window?.let { ReportPeriods.labelOf(c.period, it) } ?: "Elige las fechas", clock, source, cfg[4] as Int, false)
            } catch (e: Exception) {
                Log.e(TAG, "Report data failed", e)
                Loaded(c, window, window?.let { ReportPeriods.labelOf(c.period, it) } ?: "", clock, null, cfg[4] as Int, true)
            }
        }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<ReportsUiState> = combine(
        loaded, filters, selected, format, combine(settings.exportHistory, includeTest) { h, t -> h to t }
    ) { l, f, sel, fmt, (history, test) -> compute(l, f, sel, fmt, history, test) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    private fun compute(l: Loaded, f: ReportFilters, sel: Set<ReportKind>, fmt: ReportFormat, history: String?, test: Boolean): ReportsUiState {
        val src = l.source
        val all = src?.rows.orEmpty()
        // A modality chosen for another period may not exist in this one: it is dropped
        val products = OfferReport.products(all)
        val filtersNow = if (f.product != null && f.product !in products) f.copy(product = null) else f
        val rows = OfferReport.filter(all, filtersNow)
        val window = l.window
        val expensesWork = if (window == null || src == null || window.range == EarningsRange.DAY) null
        else ExpenseRules.summarize(window.firstDay, window.lastDay, l.clock, src.expenses.fuel, src.expenses.expenses, src.expenses.recurring, l.workSharePct).workMinor
        val totals = if (src == null) null else PeriodTotalsBuilder.of(rows, expensesWork)
        val counts = if (src == null || window == null) emptyMap() else mapOf(
            ReportKind.OFFERS to rows.size,
            ReportKind.TOTALS to if (rows.isEmpty()) 0 else 1,
            ReportKind.TRIPS to tripsFor(src, rows, filtersNow).size,
            ReportKind.EXPENSES to ExpenseExport.rows(src.expenses.fuel, src.expenses.expenses, window.firstDay, window.lastDay, l.clock).size,
            ReportKind.DAILY to if (src.report.trips.isEmpty() && src.report.summary.totalObservedEarnings == 0L && all.isEmpty()) 0 else window.days.size,
            ReportKind.SUMMARY_PDF to if (all.isEmpty() && src.report.trips.isEmpty()) 0 else 1,
            ReportKind.ROUTES to tripsFor(src, rows, filtersNow).count { src.routes[it.id]?.hasPoints == true }
        )
        return ReportsUiState(
            loading = false,
            period = l.choice.period,
            customFrom = l.choice.from,
            customTo = l.choice.to,
            window = window,
            periodLabel = l.label,
            filters = filtersNow,
            filterCounts = FilterCounts(
                origin = TakenFilter.entries.associateWith { o -> OfferReport.filter(all, ReportFilters(origin = o)).size },
                quality = CategoryFilter.entries.associateWith { q -> OfferReport.filter(all, ReportFilters(quality = q)).size },
                button = ButtonFilter.entries.associateWith { b -> OfferReport.filter(all, ReportFilters(button = b)).size },
                products = products.map { p -> p to all.count { it.product == p } }
            ),
            counts = counts,
            selected = sel,
            format = fmt,
            totals = totals,
            history = ExportHistory.decode(history),
            includeTest = test,
            failed = l.failed
        )
    }

    /** Trips of the taken rows that pass the filters; with no filter, also trips whose offer was never seen. */
    private fun tripsFor(src: ReportData.OfferSource, rows: List<OfferReportRow>, f: ReportFilters): List<com.ubers.observador.domain.trips.Trip> {
        val ids = rows.filter { it.origin == OfferOrigin.TAKEN }.mapNotNull { it.tripId }.toSet()
        return if (f.isDefault) src.trips else src.trips.filter { it.id in ids }
    }

    // ---- choices ----

    fun selectPeriod(p: ReportPeriod) {
        val c = choice.value
        choice.value = if (p == ReportPeriod.CUSTOM) c.copy(period = p) else PeriodChoice(p, c.from, c.to)
    }

    fun setCustomRange(from: LocalDate, to: LocalDate) {
        choice.value = PeriodChoice(ReportPeriod.CUSTOM, minOf(from, to), maxOf(from, to))
    }

    fun setOrigin(v: TakenFilter) { filters.value = filters.value.copy(origin = v) }
    fun setQuality(v: CategoryFilter) { filters.value = filters.value.copy(quality = v) }
    fun setButton(v: ButtonFilter) { filters.value = filters.value.copy(button = v) }
    fun setProduct(v: String?) { filters.value = filters.value.copy(product = v) }
    fun clearFilters() { filters.value = ReportFilters() }

    fun toggle(kind: ReportKind) {
        selected.value = selected.value.let { if (kind in it) it - kind else it + kind }
    }

    fun setFormat(v: ReportFormat) { format.value = v }
    fun setIncludeTest(v: Boolean) { includeTest.value = v }
    fun refresh() { reload.value++ }

    // ---- export ----

    fun save() = exporter.save { build() }
    fun share() = exporter.share { build() }

    /** The chosen files with data; one file as it is, several in a .zip. Records the export. */
    private suspend fun build(): ReportOutput {
        val st = state.value
        val window = st.window ?: error("No period")
        val l = loaded.first()
        val src = l.source ?: error("No data")
        val now = System.currentTimeMillis()
        val csv = st.format == ReportFormat.CSV
        val rows = OfferReport.filter(src.rows, st.filters)
        val totals = st.totals ?: PeriodTotalsBuilder.of(rows)
        val routeTrips = if (ReportKind.ROUTES in st.ready) tripsFor(src, rows, st.filters) else emptyList()
        val files = st.ready.flatMap { kind ->
            if (kind == ReportKind.ROUTES) {
                // Both formats, in the .zip with the rest
                return@flatMap listOf(true, false).map { gpx ->
                    data.routesFile(routeTrips, gpx, window.firstDay, window.lastDay, l.clock.zone, now)
                }
            }
            listOf(when (kind) {
                ReportKind.OFFERS -> {
                    val text = if (csv) OfferReport.csv(rows, l.clock.zone)
                    else OfferReport.json(rows, l.clock.zone, now, window.firstDay, window.lastDay, st.filters)
                    ReportOutput(ExportNames.of("ofertas", window.firstDay, window.lastDay, ext(csv)), mime(csv), text.toByteArray(Charsets.UTF_8), rows.size)
                }
                ReportKind.TOTALS -> {
                    val text = if (csv) PeriodTotalsText.csv(totals, window.firstDay, window.lastDay, st.filters)
                    else PeriodTotalsText.json(totals, window.firstDay, window.lastDay, st.filters)
                    ReportOutput(ExportNames.of("totales", window.firstDay, window.lastDay, ext(csv)), mime(csv), text.toByteArray(Charsets.UTF_8), 1)
                }
                ReportKind.TRIPS -> data.tripsFile(tripsFor(src, rows, st.filters), csv, window, l.clock, now)
                ReportKind.EXPENSES -> data.build(
                    if (csv) ReportFile.EXPENSES_CSV else ReportFile.EXPENSES_JSON, window, l.clock, now, l.workSharePct, st.includeTest
                )
                ReportKind.DAILY -> data.build(ReportFile.DAILY_CSV, window, l.clock, now, l.workSharePct, st.includeTest)
                ReportKind.SUMMARY_PDF -> data.build(
                    ReportFile.SUMMARY_PDF, window, l.clock, now, l.workSharePct, st.includeTest,
                    extraPdfSections = listOf(PeriodTotalsText.Section("Filtros", listOf("Aplicados" to st.filters.describe()))) +
                        PeriodTotalsText.sections(totals)
                )
                ReportKind.ROUTES -> error("handled above")
            })
        }.filter { it.rows > 0 }
        val out = when (files.size) {
            0 -> ReportOutput("vacio", "text/plain", ByteArray(0), 0)
            1 -> files.single()
            else -> ReportOutput(
                ExportNames.of("reportes", window.firstDay, window.lastDay, "zip"), "application/zip",
                ZipPack.of(files.map { it.fileName to it.bytes }), files.sumOf { it.rows }
            )
        }
        if (out.rows > 0) {
            settings.addExportRecord(ExportRecord(now, st.periodLabel, st.filters.describe(), files.joinToString(", ") { it.fileName }))
        }
        return out
    }

    private fun ext(csv: Boolean) = if (csv) "csv" else "json"
    private fun mime(csv: Boolean) = if (csv) "text/csv" else "application/json"

    companion object {
        private const val TAG = "Reports"
    }
}
