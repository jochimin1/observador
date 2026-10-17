package com.ubers.observador.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.TripWithEvents
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.finance.TripFinancialSummary
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripIntegrityChecker
import com.ubers.observador.domain.trips.TripPersistenceMetrics
import com.ubers.observador.service.RideLifecycleMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TripsDebugUiState(
    /** Recent trips with their event counts. */
    val trips: List<Pair<Trip, Int>> = emptyList(),
    val tripCount: Int = 0,
    val eventCount: Int = 0,
    val active: Trip? = null,
    val metrics: TripPersistenceMetrics = TripPersistenceMetrics(),
    val message: String? = null
)

/** Debug builds only: reads stored trips; writes only TEST data, through the repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripsDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TripRepository(AppDatabase.getInstance(app))
    private val money = FinancialRepository(AppDatabase.getInstance(app))
    private val distances = com.ubers.observador.data.DistanceRepository(AppDatabase.getInstance(app))
    private val message = MutableStateFlow<String?>(null)
    private val selectedId = MutableStateFlow<String?>(null)

    val state: StateFlow<TripsDebugUiState> = combine(
        repository.observeRecentTrips(RECENT_LIMIT),
        repository.observeCounts(),
        repository.observeActiveTrip(),
        RideLifecycleMonitor.tripMetrics,
        message
    ) { trips, (tripCount, eventCount), active, metrics, msg ->
        TripsDebugUiState(trips, tripCount, eventCount, active, metrics, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripsDebugUiState())

    /** The trip whose timeline is open, with its events (loaded only for that one trip). */
    val selected: StateFlow<TripWithEvents?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeTripWithEvents(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The open trip's money (Fase 7C): offered vs real, never the offer as income. */
    val selectedMoney: StateFlow<TripFinancialSummary?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else money.observeSummary(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The open trip's GPS kilometres (Fase 8E), against what the offer estimated. */
    val selectedDistance: StateFlow<Pair<String, com.ubers.observador.domain.distance.TripDistance?>?> = selectedId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else distances.observeTripDistance(id).map { id to it }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(id: String?) {
        selectedId.value = id
    }

    fun loadTestTrips() = viewModelScope.launch {
        message.value = try {
            val seed = TripTestSeeder.generate(System.currentTimeMillis())
            repository.replaceTestTrips(TripTestSeeder.PREFIX, seed.trips, seed.events)
            // Fase 8K: a made-up route in Santo Domingo for each, to see the card and the map
            val stored = seed.trips.mapNotNull { t -> repository.observeTripWithEvents(t.id).first()?.trip }
            val routes = stored.mapNotNull { com.ubers.observador.data.debug.RouteTestSeeder.generate(it) }
            com.ubers.observador.data.RouteRepository(com.ubers.observador.data.db.AppDatabase.getInstance(getApplication<android.app.Application>())).putTestRoutes(routes)
            "${seed.trips.size} viajes de prueba cargados (${seed.events.size} eventos, ${routes.size} recorridos)."
        } catch (e: Exception) {
            Log.e(TAG, "Loading test trips failed", e)
            "No se pudieron cargar los viajes de prueba."
        }
    }

    fun clearTestTrips() = viewModelScope.launch {
        message.value = try {
            money.deleteTestData(TripTestSeeder.PREFIX) // their money would otherwise stay as unlinked
            "${repository.deleteTestTrips(TripTestSeeder.PREFIX)} viajes de prueba borrados. Los reales no se tocan."
        } catch (e: Exception) {
            Log.e(TAG, "Deleting test trips failed", e)
            "No se pudieron borrar los viajes de prueba."
        }
    }

    fun checkIntegrity() = viewModelScope.launch {
        message.value = try {
            val (trips, events) = repository.loadAll()
            val issues = TripIntegrityChecker.check(trips, events)
            if (issues.isEmpty()) {
                "Integridad correcta: ${trips.size} viajes, ${events.size} eventos."
            } else {
                "Problemas: " + issues.groupingBy { it.code }.eachCount().entries.joinToString { "${it.key} ×${it.value}" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check failed", e)
            "No se pudo comprobar la integridad."
        }
    }

    private companion object {
        const val TAG = "TripsDebug"
        const val RECENT_LIMIT = 30
    }
}
