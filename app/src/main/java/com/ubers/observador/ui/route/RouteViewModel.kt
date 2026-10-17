package com.ubers.observador.ui.route

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.RouteRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.export.ReportOutput
import com.ubers.observador.domain.distance.TripDistance
import com.ubers.observador.domain.route.RouteFiles
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.TripRouteSummary
import com.ubers.observador.domain.route.TripSheet
import com.ubers.observador.domain.route.TripSheets
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.service.PersistenceQueue
import com.ubers.observador.service.RouteMonitor
import com.ubers.observador.ui.components.ReportExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/** What the trip's card and its route show. */
data class RouteUi(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val summary: TripRouteSummary? = null,
    val points: List<RoutePoint> = emptyList(),
    val distance: TripDistance? = null,
    val recordingOn: Boolean = true
) {
    val sheet: TripSheet? get() = trip?.let { TripSheets.of(it, summary, distance) }
}

enum class RouteFormat(val label: String) { GPX("GPX"), GEOJSON("GeoJSON") }

/**
 * Fase 8K: one trip's card and route, for the detail panels of Inicio and Ganancias. Reads the
 * trip, its route, its points and its kilometres; «Borrar recorrido» goes through the single
 * writer; exports go through the same exporter as the reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val routes = RouteRepository(db)
    private val trips = TripRepository(db)
    private val distances = DistanceRepository(db)
    private val settings = AppSettings(app)
    private val tripId = MutableStateFlow<String?>(null)

    val exporter = ReportExporter(app, viewModelScope)

    val state: StateFlow<RouteUi> = tripId.filterNotNull().flatMapLatest { id ->
        combine(
            trips.observeTripWithEvents(id).map { it?.trip },
            routes.observeRoute(id),
            routes.observePoints(id),
            distances.observeTripDistance(id),
            settings.routeEnabled
        ) { trip, summary, points, distance, on ->
            RouteUi(loading = false, trip = trip, summary = summary, points = points, distance = distance, recordingOn = on)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteUi())

    fun load(id: String) {
        if (tripId.value != id) tripId.value = id
        // Names for the start and the end, if they still have none (needs internet)
        RouteMonitor.nameMissing(getApplication<Application>())
    }

    fun deleteRoute() {
        val id = tripId.value ?: return
        PersistenceQueue.shared().enqueue {
            try {
                routes.deleteRoute(id, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not delete the route", e)
            }
        }
    }

    fun share(format: RouteFormat) = exporter.share { build(format) }

    fun save(format: RouteFormat) = exporter.save { build(format) }

    private fun build(format: RouteFormat): ReportOutput {
        val s = state.value
        val trip = s.trip ?: error("No trip")
        val summary = s.summary?.takeIf { it.hasPoints && s.points.isNotEmpty() }
            ?: return ReportOutput("vacio", "text/plain", ByteArray(0), 0)
        val zone = ZoneId.systemDefault()
        val track = RouteFiles.track(trip, summary, s.points, zone)
        val now = System.currentTimeMillis()
        val gpx = format == RouteFormat.GPX
        val text = if (gpx) RouteFiles.gpx(listOf(track), now) else RouteFiles.geoJson(listOf(track), now)
        return ReportOutput(
            RouteFiles.fileName(summary.startAt ?: summary.openedAt, zone, if (gpx) "gpx" else "geojson"),
            if (gpx) RouteFiles.GPX_MIME else RouteFiles.GEOJSON_MIME,
            text.toByteArray(Charsets.UTF_8),
            1
        )
    }

    private companion object {
        const val TAG = "Route"
    }
}
