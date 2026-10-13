package com.ubers.observador.ui.expenses

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DeletedExpense
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.expenses.ExpenseData
import com.ubers.observador.domain.expenses.ExpenseEntry
import com.ubers.observador.domain.expenses.ExpenseGroup
import com.ubers.observador.domain.expenses.ExpenseOverview
import com.ubers.observador.domain.expenses.ExpenseOverviews
import com.ubers.observador.domain.expenses.ExpenseRules
import com.ubers.observador.domain.expenses.ExpenseSummary
import com.ubers.observador.domain.expenses.FuelEntry
import com.ubers.observador.domain.expenses.NetResult
import com.ubers.observador.domain.expenses.OdometerReading
import com.ubers.observador.domain.expenses.RecurringCost
import com.ubers.observador.domain.expenses.SettingsSuggestion
import com.ubers.observador.service.MaintenanceNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class ExpensePeriod(val label: String) { THIS_MONTH("Este mes"), LAST_MONTH("Mes pasado") }

/** What the list can show. */
enum class ExpenseFilter(val label: String) { ALL("Todo"), FUEL("Combustible"), MAINTENANCE("Mantenimiento"), LOOSE("Sueltos"), ODOMETER("Odómetro") }

/** One line of the list. */
sealed interface ExpenseItem {
    val id: String
    val atMs: Long
    data class Fuel(val entry: FuelEntry) : ExpenseItem { override val id get() = entry.id; override val atMs get() = entry.atMs }
    data class Expense(val entry: ExpenseEntry) : ExpenseItem { override val id get() = entry.id; override val atMs get() = entry.atMs }
    data class Odometer(val entry: OdometerReading) : ExpenseItem { override val id get() = entry.id; override val atMs get() = entry.atMs }
}

data class ExpensesUiState(
    val loading: Boolean = true,
    val period: ExpensePeriod = ExpensePeriod.THIS_MONTH,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val summary: ExpenseSummary? = null,
    val net: NetResult? = null,
    val overview: ExpenseOverview = ExpenseOverview(),
    val recurring: List<RecurringCost> = emptyList(),
    val items: List<ExpenseItem> = emptyList(),
    val filter: ExpenseFilter = ExpenseFilter.ALL,
    val hasIntervals: Boolean = false,
    val clock: WorkdayClock = WorkdayClock(4, ZoneId.systemDefault())
)

/**
 * Gastos (Fase 8B). Everything is recomputed from what was written whenever it changes; the
 * only writes are the driver's own entries, through the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val repository = ExpenseRepository(db)
    private val distances = DistanceRepository(db)
    private val money = FinancialRepository(db)
    private val settings = AppSettings(app)

    private val reports = com.ubers.observador.data.export.ReportData(db)

    /** Save or share the month's expenses (Fase 8D). */
    val exporter = com.ubers.observador.ui.components.ReportExporter(app, viewModelScope)

    fun saveFile(file: com.ubers.observador.data.export.ReportFile) = exporter.save { output(file) }

    fun shareFile(file: com.ubers.observador.data.export.ReportFile) = exporter.share { output(file) }

    private suspend fun output(file: com.ubers.observador.data.export.ReportFile): com.ubers.observador.data.export.ReportOutput {
        val st = state.value
        val now = System.currentTimeMillis()
        val window = com.ubers.observador.domain.earnings.EarningsWindows.of(
            com.ubers.observador.domain.earnings.EarningsRange.MONTH,
            if (st.period == ExpensePeriod.THIS_MONTH) 0 else -1,
            now, st.clock
        )
        return reports.build(file, window, st.clock, now, settings.workSharePct.first())
    }

    private val period = MutableStateFlow(ExpensePeriod.THIS_MONTH)
    private val filter = MutableStateFlow(ExpenseFilter.ALL)

    private val _deleted = MutableSharedFlow<DeletedExpense>(extraBufferCapacity = 1)
    val deletedEvents: SharedFlow<DeletedExpense> = _deleted.asSharedFlow()

    private val config = combine(
        settings.workdayStartHour, settings.workSharePct, settings.maintenanceIntervals, settings.engineSettings
    ) { hour, share, intervals, engine -> Config(WorkdayClock(hour, ZoneId.systemDefault()), share, intervals, engine.kmPerGallon, engine.fuelPricePerGallon) }

    private data class Config(
        val clock: WorkdayClock,
        val workShare: Int,
        val intervals: Map<com.ubers.observador.domain.expenses.ExpenseType, Long>,
        val kmPerGallon: Double,
        val pricePerGallon: Double
    )

    val state: StateFlow<ExpensesUiState> = combine(
        repository.observeAll(), distances.observeAll(), config, period, filter
    ) { data, segments, cfg, p, f -> Inputs(data, segments, cfg, p, f) }
        .mapLatest { compute(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUiState())

    private data class Inputs(
        val data: ExpenseData,
        val segments: List<com.ubers.observador.domain.distance.DistanceSegment>,
        val cfg: Config,
        val period: ExpensePeriod,
        val filter: ExpenseFilter
    )

    private suspend fun compute(i: Inputs): ExpensesUiState {
        val now = System.currentTimeMillis()
        val clock = i.cfg.clock
        val today = clock.workdayOf(now)
        val month = if (i.period == ExpensePeriod.THIS_MONTH) today.withDayOfMonth(1) else today.withDayOfMonth(1).minusMonths(1)
        val from = month
        val to = month.withDayOfMonth(month.lengthOfMonth())
        val summary = ExpenseRules.summarize(from, to, clock, i.data.fuel, i.data.expenses, i.data.recurring, i.cfg.workShare)
        val income = try {
            money.periodEarnings(clock.startOf(from), clock.startOf(to.plusDays(1)))
        } catch (e: Exception) {
            Log.e(TAG, "Income for the period failed", e)
            null
        }
        val overview = ExpenseOverviews.build(
            i.data.fuel, i.data.expenses, i.data.readings, i.segments, i.cfg.intervals, i.cfg.kmPerGallon, i.cfg.pricePerGallon, now
        )
        val all = i.data.fuel.map { ExpenseItem.Fuel(it) } + i.data.expenses.map { ExpenseItem.Expense(it) } +
            i.data.readings.map { ExpenseItem.Odometer(it) }
        val items = all.filter { item ->
            when (i.filter) {
                ExpenseFilter.ALL -> true
                ExpenseFilter.FUEL -> item is ExpenseItem.Fuel
                ExpenseFilter.MAINTENANCE -> item is ExpenseItem.Expense && item.entry.type.group == ExpenseGroup.MAINTENANCE
                ExpenseFilter.LOOSE -> item is ExpenseItem.Expense && item.entry.type.group == ExpenseGroup.LOOSE
                ExpenseFilter.ODOMETER -> item is ExpenseItem.Odometer
            }
        }.sortedByDescending { it.atMs }
        return ExpensesUiState(
            loading = false,
            period = i.period,
            from = from,
            to = to,
            summary = summary,
            net = ExpenseRules.net(income, summary),
            overview = overview,
            recurring = i.data.recurring.sortedBy { it.startsOn },
            items = items,
            filter = i.filter,
            hasIntervals = i.cfg.intervals.values.any { it > 0 },
            clock = clock
        )
    }

    fun selectPeriod(p: ExpensePeriod) { period.value = p }
    fun selectFilter(f: ExpenseFilter) { filter.value = f }

    fun saveFuel(entry: FuelEntry, resetAtKm: Long?) = write { repository.saveFuel(entry, resetAtKm) }
    fun saveExpense(entry: ExpenseEntry, resetAtKm: Long?) = write { repository.saveExpense(entry, resetAtKm) }
    fun saveRecurring(entry: RecurringCost) = write { repository.saveRecurring(entry) }
    fun saveOdometer(entry: OdometerReading) = write { repository.saveOdometer(entry) }

    fun delete(item: DeletedExpense) = write {
        repository.delete(item)
        _deleted.emit(item)
    }

    fun undo(item: DeletedExpense) = write { repository.restore(item) }

    /** "Usar en Ajustes", after the driver confirmed: only the fuel cost of each offer changes, never the score. */
    fun applySuggestion(s: SettingsSuggestion) = viewModelScope.launch {
        settings.updateKmPerGallon(s.kmPerGallonTenths / 10.0)
        settings.updateFuelPricePerGallon(s.pricePerGallonMinor / 100.0)
    }

    private fun write(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            MaintenanceNotifier.check(getApplication())
        } catch (e: Exception) {
            Log.e(TAG, "Writing an expense failed", e)
        }
    }

    private companion object {
        const val TAG = "Expenses"
    }
}
