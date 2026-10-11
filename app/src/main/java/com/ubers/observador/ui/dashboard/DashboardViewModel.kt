package com.ubers.observador.ui.dashboard

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.debug.TestOfferSeeder
import com.ubers.observador.data.export.ExportFormat
import com.ubers.observador.data.export.ExportRange
import com.ubers.observador.data.export.OfferExporter
import com.ubers.observador.domain.CategoryFilter
import com.ubers.observador.domain.DashboardStats
import com.ubers.observador.domain.DayGroup
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.OfferListOptions
import com.ubers.observador.domain.OfferSort
import com.ubers.observador.domain.PeriodSummary
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.StatsPeriod
import com.ubers.observador.domain.WorkdayClock
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
    val sort: OfferSort = OfferSort.TIME
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
            val rows = if (w.since == null) repository.observeAll() else repository.observeSince(w.since)
            combine(rows, settings.engineSettings, settings.qualityThresholds) { offers, engine, cuts ->
                // D1: judge the whole period with the current settings
                val scored = DashboardStats.rescore(offers, engine)
                DashboardUiState(
                    period = w.period,
                    summary = DashboardStats.summarize(scored, cuts),
                    days = DashboardStats.groupByWorkday(scored, w.clock),
                    thresholds = cuts,
                    currentWorkday = w.currentWorkday,
                    loading = false,
                    engineSettings = engine
                )
            }
        }
        .flowOn(Dispatchers.Default)

    // ---- List filter and order (kept while the driver visits Settings) ----

    private val filter = MutableStateFlow(CategoryFilter.ALL)
    private val sort = MutableStateFlow(OfferSort.TIME)

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

    // ---- Deleting, with undo ----

    private val _deleted = MutableSharedFlow<DeletedOffers>(extraBufferCapacity = 1)
    val deletedEvents: SharedFlow<DeletedOffers> = _deleted.asSharedFlow()

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
