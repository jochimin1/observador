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
import com.ubers.observador.domain.expenses.*
import com.ubers.observador.domain.operating.*
import com.ubers.observador.data.VehicleRepository
import com.ubers.observador.data.OperatingRepository
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
    val care: VehicleCareData = VehicleCareData(),
    val components: List<ComponentStatus> = emptyList(),
    val plans: List<ComponentPlan> = emptyList(),
    val models: List<OperatingCostModel> = emptyList(),
    val entryTimelines: Map<String, List<OdometerPoint>> = emptyMap(),
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
    val clock: WorkdayClock = WorkdayClock(4, ZoneId.systemDefault()),
    /** 1.46.0: shifts of the active motorbike, newest first; the open one (if any) apart. */
    val shifts: List<WorkShift> = emptyList(),
    val openShift: WorkShift? = null,
    /** GPS km since the open shift started, only as a reference beside the odometer. */
    val openShiftGpsKm: Long? = null,
    val workShare: WorkShareMeasurement? = null,
    val manualShare: Int = 100,
    val shareSuggestion: WorkShareSuggestion? = null,
    val lastOdometerKm: Long? = null
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
    private val vehicles = VehicleRepository(db)
    private val operating = OperatingRepository(db)
    private val workShifts = com.ubers.observador.data.WorkShiftRepository(db)
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
        return reports.build(file, window, st.clock, now, settings.workSharePct.first(), settings.showTestData.first())
    }

    private val period = MutableStateFlow(ExpensePeriod.THIS_MONTH)
    private val filter = MutableStateFlow(ExpenseFilter.ALL)

    private val _deleted = MutableSharedFlow<DeletedExpense>(extraBufferCapacity = 1)
    val deletedEvents: SharedFlow<DeletedExpense> = _deleted.asSharedFlow()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

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

    // 1.39.0: TEST- rows only when Laboratorio asks for them (same rule as Inicio and Ganancias)
    private val realData = combine(repository.observeAll(), distances.observeAll(), settings.showTestData) { data, segments, test ->
        val keep = com.ubers.observador.domain.TestData
        data.copy(
            fuel = keep.filter(data.fuel, test) { it.id },
            expenses = keep.filter(data.expenses, test) { it.id },
            recurring = keep.filter(data.recurring, test) { it.id },
            readings = keep.filter(data.readings, test) { it.id }
        ) to keep.filter(segments, test) { it.id }
    }

    private val vehicleConfig = combine(config, vehicles.observe(), operating.observeModels()) { cfg, care, models -> Triple(cfg, care, models) }
    private val shiftInputs = combine(workShifts.observe(), settings.workShareDismissedPct) { s, d -> s to d }
    val state: StateFlow<ExpensesUiState> = combine(
        realData, vehicleConfig, period, filter, shiftInputs
    ) { (data, segments), vehicle, p, f, sh -> Inputs(data, segments, vehicle.first, p, f, vehicle.second, vehicle.third, sh.first, sh.second) }
        .mapLatest { compute(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUiState())

    private data class Inputs(
        val data: ExpenseData,
        val segments: List<com.ubers.observador.domain.distance.DistanceSegment>,
        val cfg: Config,
        val period: ExpensePeriod,
        val filter: ExpenseFilter,
        val care: VehicleCareData,
        val models: List<OperatingCostModel>,
        val shifts: List<WorkShift>,
        val dismissedShare: Int?
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
        val active = VehicleCare.scope(i.data, i.care.active)
        val segments = i.segments.filter { it.startedAt >= i.care.active.startedAt }
        val plans = VehicleCare.plansFor(i.care, i.cfg.intervals)
        val overview = ExpenseOverviews.build(
            active.fuel, active.expenses, active.readings, segments, plans.associate { it.type to it.intervalKm }, i.cfg.kmPerGallon, i.cfg.pricePerGallon, now
        )
        // Shift readings are shown and edited in «Jornadas», not as loose odometer lines
        val shiftReadings = i.shifts.flatMap { listOfNotNull(it.start.id, it.end?.id) }.toSet()
        val all = i.data.fuel.map { ExpenseItem.Fuel(it) } + i.data.expenses.map { ExpenseItem.Expense(it) } +
            i.data.readings.filter { it.id !in shiftReadings }.map { ExpenseItem.Odometer(it) }
        val activeShifts = i.shifts.filter { it.vehicleId == i.care.active.id }
        val activeTimeline = Odometer.timeline(active.fuel, active.expenses, active.readings)
        val (shareFrom, shareTo) = WorkShiftRules.lastPeriod(clock, now)
        val measurement = WorkShiftRules.measure(activeShifts, activeTimeline, clock, shareFrom, shareTo)
        val open = activeShifts.lastOrNull { it.isOpen }
        val openGps = open?.let { o -> com.ubers.observador.domain.distance.DistanceTotals.kmBetween(segments, o.start.atMs, now, now) }
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
            care = i.care, plans = plans, models = i.models,
            entryTimelines = (i.care.vehicles.map { it.id } + INITIAL_VEHICLE).distinct().associateWith { id ->
                Odometer.timeline(i.data.fuel.filter { it.vehicleId == id }, i.data.expenses.filter { it.vehicleId == id }, i.data.readings.filter { it.vehicleId == id })
            },
            components = VehicleCare.components(active, plans, segments, now, clock.zone),
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
            clock = clock,
            shifts = activeShifts.filter { !it.isOpen }.sortedByDescending { it.start.atMs },
            openShift = open,
            openShiftGpsKm = openGps,
            workShare = measurement,
            manualShare = i.cfg.workShare,
            shareSuggestion = WorkShiftRules.suggestion(measurement, i.cfg.workShare, i.dismissedShare),
            lastOdometerKm = activeTimeline.lastOrNull()?.km
        )
    }

    fun selectPeriod(p: ExpensePeriod) { period.value = p }
    fun selectFilter(f: ExpenseFilter) { filter.value = f }

    suspend fun saveVehicle(name: String, share: Int, replace: Boolean): String? {
        val problem = vehicles.save(name, share, replace, System.currentTimeMillis())
        if (problem == null) MaintenanceNotifier.check(getApplication())
        return problem
    }
    suspend fun savePlan(plan: ComponentPlan): String? {
        val problem = vehicles.savePlan(plan)
        if (problem == null) MaintenanceNotifier.check(getApplication())
        return problem
    }
    suspend fun saveModel(input: OperatingCostInput) = operating.save(input, state.value.clock, System.currentTimeMillis())

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

    // --- 1.46.0: work shifts ---

    suspend fun startShift(km: Long, atMs: Long, reset: Boolean): String? {
        val problem = workShifts.start(km, atMs, reset, System.currentTimeMillis())
        if (problem == null) MaintenanceNotifier.check(getApplication())
        return problem
    }

    suspend fun finishShift(id: String, km: Long, atMs: Long, confirmed: Boolean): ShiftCheck {
        val result = workShifts.finish(id, km, atMs, System.currentTimeMillis(), confirmed)
        if (result == ShiftCheck.Ok) MaintenanceNotifier.check(getApplication())
        return result
    }

    suspend fun editShiftStart(id: String, km: Long, atMs: Long, reset: Boolean): String? =
        workShifts.editStart(id, km, atMs, reset, System.currentTimeMillis())

    val shiftReminders: StateFlow<Boolean> = settings.shiftReminders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setShiftReminders(on: Boolean) = viewModelScope.launch { settings.updateShiftReminders(on) }

    private val _deletedShift = MutableSharedFlow<WorkShift>(extraBufferCapacity = 1)
    val deletedShifts: SharedFlow<WorkShift> = _deletedShift.asSharedFlow()

    fun deleteShift(id: String) = write { workShifts.delete(id)?.let { _deletedShift.emit(it) } }
    fun undoShift(shift: WorkShift) = write { workShifts.restore(shift) }

    /** «Usar N %»: one share for Ajustes (Ganancias, reports) and the motorbike. Cost models keep their own versions. */
    fun acceptShare(s: WorkShareSuggestion) = viewModelScope.launch {
        settings.updateWorkSharePct(s.measuredPct)
        vehicles.setActiveShare(s.measuredPct, System.currentTimeMillis())
        settings.updateWorkShareDismissedPct(null)
    }

    /** «Ahora no»: comes back when the measured % moves [WorkShiftRules.SUGGEST_AGAIN_POINTS] or more. */
    fun dismissShare(s: WorkShareSuggestion) = viewModelScope.launch { settings.updateWorkShareDismissedPct(s.measuredPct) }

    private fun write(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            MaintenanceNotifier.check(getApplication())
        } catch (e: Exception) {
            Log.e(TAG, "Writing an expense failed", e)
            _errors.emit(e.message ?: "No se pudo guardar. Revisa los datos e inténtalo otra vez.")
        }
    }

    private companion object {
        const val TAG = "Expenses"
    }
}
