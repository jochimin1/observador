package com.ubers.observador.ui.dashboard

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.domain.distance.DayDistance
import com.ubers.observador.domain.distance.DistanceMode
import com.ubers.observador.service.DistanceLiveState
import com.ubers.observador.service.DistanceMonitor
import com.ubers.observador.ui.AppPermissions
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.data.export.ExportFormat
import com.ubers.observador.data.export.ExportRange
import com.ubers.observador.data.export.OfferExporter
import com.ubers.observador.domain.BestHours
import com.ubers.observador.domain.BestHoursStats
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.DayGroup
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.OfferLedger
import com.ubers.observador.domain.OfferListOptions
import com.ubers.observador.domain.TakenCounts
import com.ubers.observador.domain.TakenFilter
import com.ubers.observador.domain.trips.TripStatus
import com.ubers.observador.domain.OfferSort
import com.ubers.observador.domain.PeriodSummary
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.online.OnlineFormat
import com.ubers.observador.domain.online.OnlineStatusLine
import com.ubers.observador.domain.online.OnlineSummary
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

data class DashboardUiState(
    val period: StatsPeriod = StatsPeriod.TODAY,
    val summary: PeriodSummary = PeriodSummary(),
    val days: List<DayGroup> = emptyList(),
    val thresholds: QualityThresholds = QualityThresholds(),
    /** The working day in progress, so day headers can say "Hoy" and "Ayer". */
    val currentWorkday: LocalDate? = null,
    /** True until the first query for the period answers, so "no offers" is not flashed. */
    val loading: Boolean = true,
    /** Current settings: minimums for the summary arrows, and the rule for the detail panel. */
    val engineSettings: EngineSettings = EngineSettings(),
    /** Newest real (not test) offer, for the status bar; null if none yet. */
    val lastRealOfferAt: Long? = null,
    /** Refreshed every minute so "hace 3 min" keeps moving. */
    val nowMillis: Long = 0L,
    /** Which offers the list shows and in what order; [days] already reflects both. */
    val filter: CategoryFilter = CategoryFilter.ALL,
    val sort: OfferSort = OfferSort.TIME,
    /** Fase 8H: Todo / Ofertados / Tomados. [summary] and [days] already reflect it. */
    val origin: TakenFilter = TakenFilter.ALL,
    /** How many offers each origin chip would show in this period. */
    val originCounts: TakenCounts = TakenCounts(),
    /** Offer row id -> status of the trip it became; absent = only offered. */
    val tripStatus: Map<Long, TripStatus> = emptyMap(),
    /** Fase 8J: cards of the period with «Me interesa» and with «Aceptar». */
    val interestCount: Int = 0,
    val acceptCount: Int = 0,
    /** Fase 8K: offer row id -> its trip, for the trip's card in the detail panel. */
    val trips: Map<Long, com.ubers.observador.domain.trips.Trip> = emptyMap(),
    /** Fase 8K: offer row id -> «Stefany · 6:42 → 7:01 · Los Ríos → Los Prados». */
    val tripLines: Map<Long, String> = emptyMap()
)

/** "Mejores horas para salir": its own 7 / 30-day window, separate from the summary chips. */
data class BestHoursUiState(
    /** Period the [stats] belong to; the chip may already show the next one while it loads. */
    val period: StatsPeriod = BestHours.DEFAULT_PERIOD,
    val stats: BestHoursStats = BestHours.emptyStats(),
    /** Driver's RD$/h minimum, drawn as a reference line on the chart. */
    val minRdpPerHour: Double = EngineSettings().minRdpPerHour,
    val loading: Boolean = true
)

/** Fase 8A: time online in the period of the summary chips, and the open session for the status bar. */
data class OnlineUiState(
    val period: StatsPeriod = StatsPeriod.TODAY,
    val summary: OnlineSummary = OnlineSummary(),
    /** Null until the first answer, so "Desconectado" is not flashed while loading. */
    val status: OnlineStatusLine? = null,
    val loading: Boolean = true
)

/** Fase 8E: today's kilometres, live while measuring, and what is needed to measure. */
data class DistanceUiState(
    val enabled: Boolean = false,
    val permitted: Boolean = false,
    val observerOn: Boolean = true,
    val mode: DistanceMode = DistanceMode.AUTO_AND_MANUAL,
    val manualRequested: Boolean = false,
    /** Today's stretches, the open one taken live from the tracker (the database lags up to a minute). */
    val day: DayDistance? = null,
    val live: DistanceLiveState = DistanceLiveState(),
    val loading: Boolean = true
)

/** Offers just deleted, kept exactly as stored so "Deshacer" can put them back. */
data class DeletedOffers(val message: String, val offers: List<OfferEntity>)

sealed interface ExportEvent {
    data class ReadyToSave(val fileName: String, val format: ExportFormat) : ExportEvent
    data class ReadyToShare(val uri: Uri, val mimeType: String) : ExportEvent
    data class Saved(val count: Int) : ExportEvent
    data object Empty : ExportEvent
    data object Failed : ExportEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val repository = OfferRepository(db)
    private val settings = AppSettings(app)
    private val onlineRepository = OnlineRepository(db)
    private val tripRepository = com.ubers.observador.data.TripRepository(db)
    private val routeRepository = com.ubers.observador.data.RouteRepository(db)

    /** Fase 8H: every offer seen since [since], each with the trip it became (if any). */
    private fun ledgerSince(since: Long?): Flow<OfferLedger> =
        combine(repository.observeSince(since ?: 0L), tripRepository.observeCreatedSince(since ?: 0L)) { offers, trips ->
            OfferLedger.of(offers, trips)
        }

    /** The offers the driver took, for "Mejores horas para salir". */
    private fun takenRowsSince(since: Long?): Flow<List<OfferEntity>> =
        ledgerSince(since).map { it.filtered(TakenFilter.TAKEN) }
    private val distanceRepository = DistanceRepository(db)

    private val _exportEvents = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 1)
    val exportEvents: SharedFlow<ExportEvent> = _exportEvents.asSharedFlow()

    // Content generated in requestSave, waiting for the user to pick a destination
    private var pendingSave: PendingExport? = null

    /** Builds the export and, if there is data, asks the UI to open the system file picker. */
    fun requestSave(format: ExportFormat, range: ExportRange) = viewModelScope.launch {
        val export = buildExport(format, range) ?: return@launch
        pendingSave = export
        _exportEvents.emit(ExportEvent.ReadyToSave(OfferExporter.fileName(format, range), format))
    }

    /** Writes the pending export to [uri]; a null uri means the user cancelled the picker. */
    fun savePendingTo(uri: Uri?) = viewModelScope.launch {
        val export = pendingSave
        pendingSave = null
        if (uri == null) return@launch
        if (export == null) {
            _exportEvents.emit(ExportEvent.Failed)
            return@launch
        }
        val event: ExportEvent = try {
            withContext(Dispatchers.IO) {
                val stream = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw IOException("No output stream for $uri")
                stream.use { it.write(export.content.toByteArray(Charsets.UTF_8)) }
            }
            ExportEvent.Saved(export.count)
        } catch (e: Exception) {
            Log.e(TAG, "Export save failed", e)
            ExportEvent.Failed
        }
        _exportEvents.emit(event)
    }

    /** Builds the export into the cache and asks the UI to open the share sheet. */
    fun requestShare(format: ExportFormat, range: ExportRange) = viewModelScope.launch {
        val export = buildExport(format, range) ?: return@launch
        val app = getApplication<Application>()
        val event: ExportEvent = try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, OfferExporter.fileName(format, range))
                file.writeText(export.content, Charsets.UTF_8)
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            }
            ExportEvent.ReadyToShare(uri, format.mimeType)
        } catch (e: Exception) {
            Log.e(TAG, "Export share failed", e)
            ExportEvent.Failed
        }
        _exportEvents.emit(event)
    }

    /** Returns null (after emitting the matching event) when there is nothing to export. */
    private suspend fun buildExport(format: ExportFormat, range: ExportRange): PendingExport? {
        return try {
            val offers = repository.getOffersSince(range.sinceMillis())
            if (offers.isEmpty()) {
                _exportEvents.emit(ExportEvent.Empty)
                null
            } else {
                val content = withContext(Dispatchers.Default) { OfferExporter.export(offers, format) }
                PendingExport(content, offers.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export build failed", e)
            _exportEvents.emit(ExportEvent.Failed)
            null
        }
    }

    // ---- Period summary ----

    private val period = MutableStateFlow(StatsPeriod.TODAY)

    /** The chip the driver picked; updates at once, before the new query answers. */
    val selectedPeriod: StateFlow<StatsPeriod> = period.asStateFlow()

    fun selectPeriod(newPeriod: StatsPeriod) {
        period.value = newPeriod
    }

    /** The working-day clock and "now", re-emitted every minute so "Hoy" rolls over on its own. */
    private val clockNow: Flow<Pair<WorkdayClock, Long>> = settings.workdayStartHour.flatMapLatest { hour ->
        val clock = WorkdayClock(hour, ZoneId.systemDefault())
        flow {
            while (true) {
                emit(clock to System.currentTimeMillis())
                delay(CLOCK_TICK_MS)
            }
        }
    }

    private data class Window(
        val period: StatsPeriod,
        val clock: WorkdayClock,
        val since: Long?,
        val currentWorkday: LocalDate
    )

    // Equal windows are dropped, so the minute tick only re-queries when a working day starts
    private val window: Flow<Window> = combine(period, clockNow) { p, (clock, now) ->
        Window(p, clock, clock.periodStart(p, now), clock.workdayOf(now))
    }.distinctUntilChanged()

    private val periodState: Flow<DashboardUiState> = window
        .flatMapLatest { w ->
            // The whole period is loaded, so no day is ever cut short. At ~50 offers a day,
            // even a year of history is a few MB; revisit with paging if that changes.
            // Fase 8H: every offer seen, with the trip it became; the origin chip picks which are shown
            val ledger = ledgerSince(w.since)
            val routes = routeRepository.observeRoutesSince(w.since ?: 0L)
            combine(ledger, origin, settings.engineSettings, settings.qualityThresholds, routes) { all, o, engine, cuts, routeOf ->
                // D1: judge the whole period with the current settings
                val scored = DashboardStats.rescore(all.filtered(o), engine)
                DashboardUiState(
                    period = w.period,
                    summary = DashboardStats.summarize(scored, cuts),
                    days = DashboardStats.groupByWorkday(scored, w.clock),
                    thresholds = cuts,
                    currentWorkday = w.currentWorkday,
                    loading = false,
                    engineSettings = engine,
                    origin = o,
                    originCounts = all.counts,
                    tripStatus = all.tripStatus,
                    interestCount = all.countOf(com.ubers.observador.domain.lifecycle.OfferCardAction.INTEREST),
                    acceptCount = all.countOf(com.ubers.observador.domain.lifecycle.OfferCardAction.ACCEPT),
                    trips = all.tripOf,
                    tripLines = all.tripOf.mapValues { (_, t) ->
                        com.ubers.observador.domain.route.TripSheets.oneLine(
                            com.ubers.observador.domain.route.TripSheets.of(t, routeOf[t.id], null), w.clock.zone
                        )
                    }
                )
            }
        }
        .flowOn(Dispatchers.Default)

    // ---- List filter and order (kept while the driver visits Settings) ----

    private val filter = MutableStateFlow(CategoryFilter.ALL)
    private val sort = MutableStateFlow(OfferSort.TIME)
    private val origin = MutableStateFlow(TakenFilter.ALL)

    fun selectOrigin(newOrigin: TakenFilter) {
        origin.value = newOrigin
    }

    fun selectFilter(newFilter: CategoryFilter) {
        filter.value = newFilter
    }

    fun selectSort(newSort: OfferSort) {
        sort.value = newSort
    }

    private val listOptions = combine(filter, sort) { f, s -> f to s }

    // The minute tick, the last offer and the list options are cheap: the period is not recomputed
    val uiState: StateFlow<DashboardUiState> = combine(
        periodState,
        clockNow,
        repository.observeLatestRealOfferAt(TestOfferSeeder.HASH_PREFIX),
        listOptions
    ) { state, (_, now), lastRealOfferAt, (f, s) ->
        state.copy(
            nowMillis = now,
            lastRealOfferAt = lastRealOfferAt,
            filter = f,
            sort = s,
            days = OfferListOptions.apply(state.days, f, s, state.thresholds)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DashboardUiState()
    )

    // ---- Best hours to go out ----

    private val bestHoursPeriod = MutableStateFlow(BestHours.DEFAULT_PERIOD)

    /** The 7 / 30-day chip; kept while the driver visits Settings, like the summary chips. */
    val selectedBestHoursPeriod: StateFlow<StatsPeriod> = bestHoursPeriod.asStateFlow()

    fun selectBestHoursPeriod(newPeriod: StatsPeriod) {
        if (newPeriod in BestHours.PERIODS) bestHoursPeriod.value = newPeriod
    }

    private data class BestHoursWindow(val period: StatsPeriod, val since: Long, val zone: ZoneId)

    // The window is counted in working days, like the "30 días" chip above, so both agree on
    // which offers are in. Inside it, slots use the real clock hour (BestHours ignores the
    // working-day start). Equal windows are dropped, so the minute tick does not re-query.
    val bestHours: StateFlow<BestHoursUiState> = combine(bestHoursPeriod, clockNow) { p, (clock, now) ->
        // PERIODS only holds bounded periods; 0 would mean "no lower bound" anyway
        BestHoursWindow(p, clock.periodStart(p, now) ?: 0L, clock.zone)
    }
        .distinctUntilChanged()
        .flatMapLatest { w ->
            combine(
                takenRowsSince(w.since),
                settings.engineSettings,
                settings.qualityThresholds
            ) { offers, engine, cuts ->
                BestHoursUiState(
                    period = w.period,
                    stats = DashboardStats.calculateBestHours(offers, engine, cuts, w.zone),
                    minRdpPerHour = engine.minRdpPerHour,
                    loading = false
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BestHoursUiState())

    // ---- Time online (Fase 8A) ----

    // Same window as the summary chips. Recomputed on every stored change (a sighting is written
    // at most once a minute) and every minute, so the open session keeps counting
    val online: StateFlow<OnlineUiState> = window
        .flatMapLatest { w ->
            val from = w.since ?: 0L
            combine(onlineRepository.observeBetween(from, Long.MAX_VALUE), onlineRepository.observeOpen(), clockNow) { sessions, open, _ ->
                sessions to open
            }.mapLatest { (sessions, open) ->
                val now = System.currentTimeMillis()
                OnlineUiState(
                    period = w.period,
                    summary = onlineRepository.summarize(sessions, from, now, now),
                    status = OnlineFormat.status(open, now),
                    loading = false
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnlineUiState())

    // ---- Kilometres with GPS (Fase 8E) ----

    private val todayDistance: Flow<DayDistance> = clockNow
        .mapLatest { (clock, now) -> clock.workdayOf(now) }
        .distinctUntilChanged()
        .flatMapLatest { distanceRepository.observeDay(it) }

    private val distanceSwitches = combine(
        settings.distanceEnabled, settings.distanceMode, settings.distanceManual, settings.observerEnabled, AppPermissions.location
    ) { enabled, mode, manual, observer, permitted -> DistanceUiState(enabled, permitted, observer, mode, manual) }

    val distance: StateFlow<DistanceUiState> = combine(distanceSwitches, todayDistance, DistanceMonitor.state) { sw, stored, live ->
        // The open stretch changes every few seconds in memory; the stored copy only every minute
        val open = live.open?.takeIf { it.workday == stored.workday }
        val segments = stored.segments.filter { it.id != open?.id } + listOfNotNull(open)
        sw.copy(day = DayDistance.of(stored.workday, segments), live = live, loading = false)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DistanceUiState())

    fun setDistanceEnabled(enabled: Boolean) = viewModelScope.launch { settings.updateDistanceEnabled(enabled) }

    /** "Iniciar recorrido" / "Detener recorrido". */
    fun setManualDistance(on: Boolean) = viewModelScope.launch { settings.updateDistanceManual(on) }

    // ---- Deleting, with undo ----

    private val _deleted = MutableSharedFlow<DeletedOffers>(extraBufferCapacity = 1)
    val deletedEvents: SharedFlow<DeletedOffers> = _deleted.asSharedFlow()

    /** The master switch (Observador activo / en pausa), shared with Ajustes and the notification. */
    val observerEnabled: StateFlow<Boolean> = settings.observerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setObserverEnabled(enabled: Boolean) = viewModelScope.launch { settings.updateObserverEnabled(enabled) }

    fun deleteOffer(id: Long) = viewModelScope.launch {
        // null when a repeated swipe callback already deleted it: no second notice
        val stored = repository.delete(id) ?: return@launch
        _deleted.emit(DeletedOffers("Oferta borrada", listOf(stored)))
    }

    /** Deletes every offer of the working day that started on [workday]. */
    fun deleteWorkday(workday: LocalDate) = viewModelScope.launch {
        val clock = WorkdayClock(settings.workdayStartHour.first(), ZoneId.systemDefault())
        val stored = repository.deleteBetween(clock.startOf(workday), clock.startOf(workday.plusDays(1)))
        if (stored.isNotEmpty()) {
            _deleted.emit(DeletedOffers("${stored.size} ofertas borradas", stored))
        }
    }

    fun undoDelete(offers: List<OfferEntity>) = viewModelScope.launch {
        repository.restore(offers)
    }

    private class PendingExport(val content: String, val count: Int)

    private companion object {
        const val TAG = "DashboardViewModel"
        const val CLOCK_TICK_MS = 60_000L
    }
}
