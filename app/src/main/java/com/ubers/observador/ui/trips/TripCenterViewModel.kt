package com.ubers.observador.ui.trips

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.RouteRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.TripDistance
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.sectors.CareSector
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripCenter
import com.ubers.observador.domain.trips.TripCorrection
import com.ubers.observador.domain.trips.TripEvent
import com.ubers.observador.domain.trips.TripQuery
import com.ubers.observador.domain.trips.TripRow
import com.ubers.observador.domain.trips.TripStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

data class TripListState(
    val loading: Boolean = true,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val query: TripQuery = TripQuery(),
    /** Every row of the period, before the filters (for counts and chips). */
    val all: List<TripRow> = emptyList(),
    val shown: List<TripRow> = emptyList(),
    val products: List<String> = emptyList(),
    val sectors: List<CareSector> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val error: Boolean = false
)

/** Everything about one trip; loaded only when it is opened. */
data class TripDetailState(
    val trip: Trip,
    val events: List<TripEvent>,
    val summary: TripFinancialSummary,
    val observations: List<FinancialObservation>,
    val distance: TripDistance?,
    val corrections: List<TripCorrection>,
    val engine: EngineSettings,
    val hideNames: Boolean,
    val routeEndAt: Long?
)

/** 1.40.0: the list (summaries) and the one detail open, from the same stores Ganancias reads. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripCenterViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val trips = TripRepository(db)
    private val money = FinancialRepository(db)
    private val distances = DistanceRepository(db)
    private val routes = RouteRepository(db)
    private val settings = AppSettings(app)

    private val period = MutableStateFlow(StatsPeriod.WEEK)
    private val query = MutableStateFlow(TripQuery())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val hideNames: StateFlow<Boolean> = settings.hideRiderNames.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val periodRows = combine(period, settings.workdayStartHour) { p, hour -> p to WorkdayClock(hour, ZoneId.systemDefault()) }
        .flatMapLatest { (p, clock) ->
            val since = clock.periodStart(p, System.currentTimeMillis()) ?: 0L
            combine(
                trips.observeCreatedSince(since), trips.observeCorrectedTripIds(),
                money.observeRecentObservations(1), settings.showTestData
            ) { list, corrected, _, test -> Triple(TestData.filter(list, test) { it.id }, corrected, p) }
        }
        .mapLatest { (list, corrected, p) ->
            // One query for the money of the whole list: summaries only, no observations
            runCatching { p to TripCenter.rows(list, money.summariesFor(list.map { it.id }), corrected, Currencies.DOP) }
                .onFailure { Log.e(TAG, "Trip list not loaded", it) }
        }

    val list: StateFlow<TripListState> = combine(periodRows, query, hideNames, settings.careSectors) { rows, q, hide, sectors ->
        rows.fold(
            onSuccess = { (p, all) ->
                val effective = q.copy(hideNames = hide)
                TripListState(false, p, effective, all, TripCenter.filter(all, effective), TripCenter.products(all), sectors)
            },
            onFailure = { TripListState(loading = false, error = true) }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripListState())

    fun setPeriod(p: StatsPeriod) { period.value = p }
    fun setQuery(q: TripQuery) { query.value = q }
    fun setHideNames(v: Boolean) = viewModelScope.launch { settings.updateHideRiderNames(v) }

    // ---- Detail ----

    private val openId = MutableStateFlow<String?>(null)

    val detail: StateFlow<TripDetailState?> = openId.flatMapLatest { id ->
        if (id == null) flowOf(null) else combine(
            trips.observeTripWithEvents(id), money.observeSummary(id), distances.observeTripDistance(id),
            trips.observeCorrections(id), combine(settings.engineSettings, hideNames, routes.observeRoute(id)) { e, h, r -> Triple(e, h, r) }
        ) { tw, summary, distance, corrections, (engine, hide, route) ->
            tw?.let {
                val observations = try { money.tripObservations(id).sortedBy { o -> o.observedAtMs } } catch (e: Exception) {
                    Log.e(TAG, "Trip money not loaded", e); emptyList()
                }
                TripDetailState(
                    it.trip, it.events,
                    summary ?: TripCenter.summaryOf(it.trip, emptyMap(), Currencies.DOP),
                    observations, distance, corrections, engine, hide, route?.endAt
                )
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(id: String) { openId.value = id }

    fun correctName(name: String?) = act("Nombre corregido.", "El nombre no cambió.") { id, now -> trips.correctRiderName(id, name, now) }

    fun correctStatus(to: TripStatus) = act("Estado corregido. Ganancias lo tendrá en cuenta.", "Ese cambio de estado no es posible.") { id, now ->
        trips.correctStatus(id, to, now, detail.value?.routeEndAt)
    }

    fun undoStatus() = act("Corrección de estado deshecha.", "No hay una corrección de estado que deshacer.") { id, now ->
        trips.undoStatusCorrection(id, now)
    }

    private fun act(ok: String, no: String, block: suspend (String, Long) -> Boolean) = viewModelScope.launch {
        val id = openId.value ?: return@launch
        val done = try { block(id, System.currentTimeMillis()) } catch (e: Exception) {
            Log.e(TAG, "Correction failed", e); _messages.tryEmit("No se pudo guardar la corrección."); return@launch
        }
        _messages.tryEmit(if (done) ok else no)
    }

    private companion object { const val TAG = "TripCenter" }
}
