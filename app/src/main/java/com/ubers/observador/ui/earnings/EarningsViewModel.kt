package com.ubers.observador.ui.earnings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.ExpenseRepository
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.earnings.AssignCheck
import com.ubers.observador.domain.earnings.AssignPreview
import com.ubers.observador.domain.earnings.EarningsRange
import com.ubers.observador.domain.earnings.EarningsReport
import com.ubers.observador.domain.earnings.EarningsReports
import com.ubers.observador.domain.earnings.EarningsWindows
import com.ubers.observador.domain.earnings.GoalProgress
import com.ubers.observador.domain.earnings.GoalSettings
import com.ubers.observador.domain.earnings.Goals
import com.ubers.observador.domain.earnings.ManualAssignment
import com.ubers.observador.domain.earnings.ManualAssignmentRow
import com.ubers.observador.domain.earnings.TripMoneyRow
import com.ubers.observador.domain.expenses.ExpenseData
import com.ubers.observador.domain.expenses.ExpenseRules
import com.ubers.observador.domain.expenses.ExpenseSummary
import com.ubers.observador.domain.expenses.NetResult
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.PlatformEarningsSnapshot
import com.ubers.observador.domain.finance.PlatformPeriodType
import com.ubers.observador.domain.finance.Reconciler
import com.ubers.observador.domain.finance.Reconciliation
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.online.OnlineSummary
import com.ubers.observador.domain.trips.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Uber's money labels are confirmed with a real trip on the phone (Fase 7C validation). Until
 * then Ganancias says so at the top. Turned to true only after that check, never before.
 */
const val MONEY_LABELS_VALIDATED = false

/** The goals as shown: always today and this week, whatever period is being browsed. */
data class GoalsUi(
    val settings: GoalSettings = GoalSettings(),
    val daily: GoalProgress? = null,
    val weekly: GoalProgress? = null
)

data class EarningsUiState(
    val loading: Boolean = true,
    val range: EarningsRange = EarningsRange.DAY,
    val offset: Int = 0,
    val report: EarningsReport? = null,
    val online: OnlineSummary? = null,
    val expenses: ExpenseSummary? = null,
    /** Only for week and month (decision 4: no daily net; a fill-up would make a good day look bad). */
    val net: NetResult? = null,
    /** GPS km of the period (aprox.); null without measurements. */
    val km: Long? = null,
    val reconciliation: Reconciliation? = null,
    val uberCapturedAt: Long? = null,
    val goals: GoalsUi = GoalsUi(),
    val manual: List<ManualAssignmentRow> = emptyList(),
    /** Only for a week (Fase 8D), compared with the one before when both have 80 % coverage. */
    val weekly: com.ubers.observador.domain.reports.WeeklyReport? = null,
    val clock: WorkdayClock = WorkdayClock(AppSettings.DEFAULT_WORKDAY_START_HOUR, ZoneId.systemDefault()),
    val currency: String = Currencies.DOP
)

/** Choosing the trip for an unassigned amount. */
data class AssignSheet(
    val amount: FinancialObservation,
    val candidates: List<Pair<Trip, TripFinancialSummary?>>,
    val loading: Boolean = false
)

/** The confirmation dialog: the amount, the trip and what its money would become. */
data class AssignConfirm(val amount: FinancialObservation, val trip: Trip, val preview: AssignPreview)

/** A trip's timeline and money history. */
data class TripDetail(val row: TripMoneyRow, val trip: Trip?, val observations: List<FinancialObservation>)

/**
 * Ganancias (Fase 8C). Everything is recomputed from the ledger whenever trips, money, sessions,
 * expenses or settings change, and every minute for the clock. The only writes are the driver's
 * manual corrections, through [FinancialRepository], each one a new row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarningsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val money = FinancialRepository(db)
    private val trips = TripRepository(db)
    private val online = OnlineRepository(db)
    private val expenses = ExpenseRepository(db)
    private val distances = DistanceRepository(db)
    private val settings = AppSettings(app)
    private val reports = com.ubers.observador.data.export.ReportData(db)

    /** Save or share the files of the period on screen (Fase 8D). */
    val exporter = com.ubers.observador.ui.components.ReportExporter(app, viewModelScope)

    private val selection = MutableStateFlow(EarningsRange.DAY to 0)

    private val _assign = MutableStateFlow<AssignSheet?>(null)
    val assign: StateFlow<AssignSheet?> = _assign.asStateFlow()
    private val _confirm = MutableStateFlow<AssignConfirm?>(null)
    val confirm: StateFlow<AssignConfirm?> = _confirm.asStateFlow()
    private val _detail = MutableStateFlow<TripDetail?>(null)
    val detail: StateFlow<TripDetail?> = _detail.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    private data class Changes(val trips: Any?, val money: Any?, val manual: List<FinancialObservation>, val sessions: Any?)
    private data class Extra(val expenses: ExpenseData, val segments: List<DistanceSegment>, val daySnap: PlatformEarningsSnapshot?, val weekSnap: PlatformEarningsSnapshot?)
    private data class Config(val hour: Int, val goals: GoalSettings, val workShare: Int)

    private val changes = combine(
        trips.observeCounts(), money.observeRecentObservations(1), money.observeManual(), online.observeRecent(1)
    ) { t, m, manual, s -> Changes(t, m, manual, s) }

    private val extra = combine(
        expenses.observeAll(), distances.observeAll(),
        money.observeLatestPlatformSnapshot(PlatformPeriodType.DAY), money.observeLatestPlatformSnapshot(PlatformPeriodType.WEEK)
    ) { e, d, ds, ws -> Extra(e, d, ds, ws) }

    private val config = combine(settings.workdayStartHour, settings.goals, settings.workSharePct) { h, g, w -> Config(h, g, w) }

    val state: StateFlow<EarningsUiState> = combine(changes, extra, config, selection, minuteTicker) { c, e, cfg, sel, now ->
        Inputs(c, e, cfg, sel.first, sel.second, now)
    }
        .mapLatest { compute(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EarningsUiState())

    private data class Inputs(val changes: Changes, val extra: Extra, val config: Config, val range: EarningsRange, val offset: Int, val now: Long)

    private suspend fun compute(i: Inputs): EarningsUiState {
        val clock = WorkdayClock(i.config.hour, ZoneId.systemDefault())
        val now = i.now
        return try {
            val window = EarningsWindows.of(i.range, i.offset, now, clock, i.config.goals.weekStart)
            val inputs = money.earningsInputs(window.startMs, window.endMs)
            val report = EarningsReports.build(window, clock, inputs.completedTrips, inputs.summaries, inputs.unassigned)
            val sessions = online.getBetween(window.startMs, window.endMs)
            val onlineSummary = online.summarize(sessions, window.startMs, window.endMs, now)
            val expenseSummary = ExpenseRules.summarize(
                window.firstDay, window.lastDay, clock, i.extra.expenses.fuel, i.extra.expenses.expenses, i.extra.expenses.recurring, i.config.workShare
            )
            val net = if (i.range == EarningsRange.DAY) null else ExpenseRules.net(report.summary, expenseSummary)
            val km = if (i.extra.segments.isEmpty()) null else com.ubers.observador.domain.distance.DistanceTotals.kmBetween(i.extra.segments, window.startMs, window.endMs, now)
            val snapshot = when (i.range) {
                EarningsRange.DAY -> i.extra.daySnap
                EarningsRange.WEEK -> i.extra.weekSnap
                EarningsRange.MONTH -> null
            }?.takeIf { it.capturedAtMs >= window.startMs && it.capturedAtMs < window.endMs }
            val reconciliation = snapshot?.let { Reconciler.reconcile(report.summary, window.startMs, window.endMs, it) }
            val tripIds = report.trips.map { it.tripId }.toSet()
            val weekly = if (i.range != EarningsRange.WEEK) null else try {
                reports.weekly(window, clock, now, i.config.goals.weeklyMinor, i.config.workShare)
            } catch (e: Exception) {
                Log.e(TAG, "Weekly summary failed", e)
                null
            }
            EarningsUiState(
                loading = false,
                range = i.range,
                offset = window.offset,
                report = report,
                online = onlineSummary.takeIf { it.hasData },
                expenses = expenseSummary,
                net = net,
                km = km,
                reconciliation = reconciliation,
                uberCapturedAt = snapshot?.capturedAtMs,
                goals = goals(i.config.goals, clock, now, if (i.range == EarningsRange.DAY && i.offset == 0) report to onlineSummary else null),
                manual = ManualAssignment.live(i.changes.manual).filter { it.tripId in tripIds },
                weekly = weekly,
                clock = clock
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ganancias failed", e)
            EarningsUiState(loading = false, range = i.range, offset = i.offset, clock = clock)
        }
    }

    /** Today's and this week's goals; [today] reuses what was already computed when browsing today. */
    private suspend fun goals(g: GoalSettings, clock: WorkdayClock, now: Long, today: Pair<EarningsReport, OnlineSummary>?): GoalsUi {
        if (!g.hasAny) return GoalsUi(g)
        val daily = if (g.dailyMinor <= 0) null else {
            val w = EarningsWindows.of(EarningsRange.DAY, 0, now, clock)
            val income = today?.first?.summary ?: money.periodEarnings(w.startMs, w.endMs)
            val onlineMs = today?.second?.confirmedMs ?: online.summarize(online.getBetween(w.startMs, w.endMs), w.startMs, w.endMs, now).confirmedMs
            Goals.daily(g.dailyMinor, income, onlineMs)
        }
        val weekly = if (g.weeklyMinor <= 0) null else {
            val w = EarningsWindows.of(EarningsRange.WEEK, 0, now, clock, g.weekStart)
            Goals.weekly(g.weeklyMinor, money.periodEarnings(w.startMs, w.endMs), EarningsWindows.daysLeft(w, now, clock))
        }
        return GoalsUi(g, daily, weekly)
    }

    fun select(range: EarningsRange) {
        selection.value = range to 0
    }

    fun previous() {
        selection.value = selection.value.let { it.first to it.second - 1 }
    }

    fun next() {
        selection.value = selection.value.let { it.first to minOf(0, it.second + 1) }
    }

    /** The weekly summary notification opens the week that ended. */
    fun showLastWeek() {
        selection.value = EarningsRange.WEEK to -1
    }

    // ---- Export (Fase 8D) ----

    private suspend fun output(file: com.ubers.observador.data.export.ReportFile): com.ubers.observador.data.export.ReportOutput {
        val st = state.value
        val window = st.report?.window ?: error("No period loaded")
        return reports.build(file, window, st.clock, System.currentTimeMillis(), settings.workSharePct.first())
    }

    fun saveFile(file: com.ubers.observador.data.export.ReportFile) = exporter.save { output(file) }

    fun shareFile(file: com.ubers.observador.data.export.ReportFile) = exporter.share { output(file) }

    fun setDailyGoal(minor: Long) = viewModelScope.launch { settings.updateDailyGoal(minor) }

    fun setWeeklyGoal(minor: Long) = viewModelScope.launch { settings.updateWeeklyGoal(minor) }

    // ---- Manual assignment ----

    fun openAssign(amount: FinancialObservation) = viewModelScope.launch {
        _assign.value = AssignSheet(amount, emptyList(), loading = true)
        val candidates = try {
            money.assignmentCandidates(amount.observationId, state.value.clock)
        } catch (e: Exception) {
            Log.e(TAG, "Candidates failed", e)
            emptyList()
        }
        if (_assign.value?.amount?.observationId == amount.observationId) _assign.value = AssignSheet(amount, candidates)
    }

    fun closeAssign() {
        _assign.value = null
    }

    fun choose(trip: Trip) = viewModelScope.launch {
        val amount = _assign.value?.amount ?: return@launch
        when (val check = money.previewAssignment(amount.observationId, trip.id)) {
            is AssignCheck.Ok -> _confirm.value = AssignConfirm(amount, trip, check.preview)
            is AssignCheck.Rejected -> _messages.tryEmit(check.reason.message)
        }
    }

    fun cancelConfirm() {
        _confirm.value = null
    }

    fun confirmAssign() = viewModelScope.launch {
        val c = _confirm.value ?: return@launch
        _confirm.value = null
        _assign.value = null
        val result = try {
            money.assignToTrip(c.amount.observationId, c.trip.id, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Assignment failed", e)
            null
        }
        _messages.tryEmit(
            when (result) {
                is AssignCheck.Ok -> "Cantidad asignada al viaje. Puedes deshacerlo en «Asignadas a mano»."
                is AssignCheck.Rejected -> result.reason.message
                null -> "No se pudo asignar. No se cambió nada."
            }
        )
    }

    fun reject(amount: FinancialObservation) = viewModelScope.launch {
        val ok = try {
            money.rejectObservation(amount.observationId, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Reject failed", e)
            false
        }
        _messages.tryEmit(if (ok) "Marcada como «no es mío». Queda en el historial." else "Esta cantidad ya no está pendiente.")
    }

    fun undo(row: ManualAssignmentRow) = viewModelScope.launch {
        val ok = try {
            money.undoAssignment(row.copy.observationId, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Undo failed", e)
            false
        }
        _messages.tryEmit(if (ok) "Asignación deshecha: la cantidad vuelve a pendientes." else "Esa asignación ya estaba deshecha.")
    }

    // ---- Trip detail ----

    fun openTrip(row: TripMoneyRow) = viewModelScope.launch {
        _detail.value = TripDetail(row, null, emptyList())
        val trip = try { trips.observeTripWithEvents(row.tripId).first()?.trip } catch (e: Exception) { null }
        val history = try { money.tripObservations(row.tripId) } catch (e: Exception) { emptyList() }
        if (_detail.value?.row?.tripId == row.tripId) _detail.value = TripDetail(row, trip, history.sortedBy { it.observedAtMs })
    }

    fun closeTrip() {
        _detail.value = null
    }

    private companion object {
        const val TAG = "Earnings"
    }
}
