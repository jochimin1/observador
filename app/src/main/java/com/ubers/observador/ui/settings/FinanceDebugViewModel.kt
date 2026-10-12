package com.ubers.observador.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.FinancialTestSeeder
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.finance.EarningsPeriodSummary
import com.ubers.observador.domain.finance.FinancialMetrics
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.PlatformEarningsSnapshot
import com.ubers.observador.domain.finance.PlatformPeriodType
import com.ubers.observador.domain.finance.Reconciler
import com.ubers.observador.domain.finance.Reconciliation
import com.ubers.observador.service.RideLifecycleMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The working day's real money and Uber's figure for the same limits, when known. */
data class WorkdayMoney(
    val periodStartMs: Long,
    val periodEndMs: Long,
    val summary: EarningsPeriodSummary,
    val latestUberDay: PlatformEarningsSnapshot?,
    val reconciliation: Reconciliation
)

data class FinanceDebugUiState(
    val metrics: FinancialMetrics = FinancialMetrics(),
    val recent: List<FinancialObservation> = emptyList(),
    val unassigned: List<FinancialObservation> = emptyList(),
    val workday: WorkdayMoney? = null,
    val message: String? = null
)

/** Debug builds only (Fase 7C): reads stored money; writes only TEST data, through the repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinanceDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val money = FinancialRepository(db)
    private val trips = TripRepository(db)
    private val settings = AppSettings(app)
    private val message = MutableStateFlow<String?>(null)

    /** Recomputed whenever trips, observations, snapshots or the working-day hour change. */
    private val workday = combine(
        trips.observeCounts(),
        money.observeRecentObservations(1),
        money.observeLatestPlatformSnapshot(PlatformPeriodType.DAY),
        settings.workdayStartHour
    ) { _, _, uberDay, hour -> uberDay to hour }
        .mapLatest { (uberDay, hour) -> computeWorkday(uberDay, hour) }

    val state: StateFlow<FinanceDebugUiState> = combine(
        RideLifecycleMonitor.financialMetrics,
        money.observeRecentObservations(RECENT_LIMIT),
        money.observeUnassigned(),
        workday,
        message
    ) { metrics, recent, unassigned, day, msg ->
        FinanceDebugUiState(metrics, recent, unassigned, day, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceDebugUiState())

    private suspend fun computeWorkday(uberDay: PlatformEarningsSnapshot?, hour: Int): WorkdayMoney? = try {
        val now = System.currentTimeMillis()
        val clock = WorkdayClock(hour, ZoneId.systemDefault())
        val start = clock.startOf(clock.workdayOf(now))
        val end = clock.startOf(clock.workdayOf(now).plusDays(1))
        val summary = money.periodEarnings(start, end)
        WorkdayMoney(start, end, summary, uberDay, Reconciler.reconcile(summary, start, end, uberDay))
    } catch (e: Exception) {
        Log.e(TAG, "Workday money failed", e)
        null
    }

    /** Loads the TEST trips first (money needs its trips), then the TEST money on top. */
    fun loadTestMoney() = viewModelScope.launch {
        message.value = try {
            val now = System.currentTimeMillis()
            val tripSeed = TripTestSeeder.generate(now)
            trips.replaceTestTrips(TripTestSeeder.PREFIX, tripSeed.trips, tripSeed.events)
            val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
            val start = clock.startOf(clock.workdayOf(now))
            val end = clock.startOf(clock.workdayOf(now).plusDays(1))
            val seed = FinancialTestSeeder.generate(tripSeed.trips, now, start, end)
            money.loadTestData(TripTestSeeder.PREFIX, seed.observations, seed.snapshots, now)
            val expected = FinancialTestSeeder.expectedSummaries(tripSeed.trips, seed)
            val mismatches = expected.count { (id, summary) -> money.getStoredSummary(id)?.effectiveEarnings != summary.effectiveEarnings }
            "${tripSeed.trips.size} viajes y ${seed.observations.size} cantidades de prueba cargados" +
                if (mismatches == 0) ". Resúmenes correctos." else ". ⚠ $mismatches resúmenes distintos de lo esperado."
        } catch (e: Exception) {
            Log.e(TAG, "Loading test money failed", e)
            "No se pudo cargar el dinero de prueba."
        }
    }

    fun clearTestMoney() = viewModelScope.launch {
        message.value = try {
            money.deleteTestData(TripTestSeeder.PREFIX)
            "Dinero de prueba borrado. El real no se toca."
        } catch (e: Exception) {
            Log.e(TAG, "Deleting test money failed", e)
            "No se pudo borrar el dinero de prueba."
        }
    }

    /** Rebuilds every stored summary from the ledger and reports whether any had drifted. */
    fun rebuildSummaries() = viewModelScope.launch {
        message.value = try {
            val drifted = money.driftedSummaries()
            val count = money.rebuildAll(System.currentTimeMillis())
            "$count resúmenes reconstruidos desde las observaciones; $drifted no coincidían antes."
        } catch (e: Exception) {
            Log.e(TAG, "Rebuild failed", e)
            "No se pudieron reconstruir los resúmenes."
        }
    }

    private companion object {
        const val TAG = "FinanceDebug"
        const val RECENT_LIMIT = 20
    }
}
