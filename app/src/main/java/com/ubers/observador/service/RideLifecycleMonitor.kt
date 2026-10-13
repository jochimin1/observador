package com.ubers.observador.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.lifecycle.LifecycleDiagnostics
import com.ubers.observador.domain.lifecycle.LifecycleLogEntry
import com.ubers.observador.domain.lifecycle.PipelineResult
import com.ubers.observador.domain.lifecycle.RideLifecycleContext
import com.ubers.observador.domain.lifecycle.RideLifecyclePipeline
import com.ubers.observador.domain.lifecycle.ScreenClassification
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.SkipReason
import com.ubers.observador.domain.lifecycle.SnapshotSanitizer
import com.ubers.observador.domain.lifecycle.TransitionResult
import com.ubers.observador.domain.lifecycle.UberScreenClassifier
import com.ubers.observador.domain.finance.FinancialDetector
import com.ubers.observador.domain.finance.FinancialMetrics
import com.ubers.observador.domain.finance.FinancialDetection
import com.ubers.observador.domain.finance.FinancialScreenClassification
import com.ubers.observador.domain.finance.FinancialScreenType
import com.ubers.observador.domain.finance.UberTodayPillDetector
import com.ubers.observador.domain.online.OnlineDiagnostics
import com.ubers.observador.domain.online.OnlineEvidence
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.OnlineSessionRules
import com.ubers.observador.domain.online.OnlineSignal
import com.ubers.observador.domain.online.OnlineStateDetector
import com.ubers.observador.domain.online.OnlineTransition
import com.ubers.observador.domain.online.OnlineUpdate
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripPersistenceMetrics
import com.ubers.observador.domain.trips.TripRestorePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the debug panel shows. Plain data, refreshed after every snapshot. */
data class LifecycleDebugState(
    val context: RideLifecycleContext = RideLifecycleContext(),
    val classification: ScreenClassification? = null,
    val diagnostics: LifecycleDiagnostics = LifecycleDiagnostics(),
    val history: List<LifecycleLogEntry> = emptyList(),
    val lastSnapshotAt: Long? = null,
    val serviceConnected: Boolean = false,
    /** False until the open trip in the database (if any) has been handed back to the lifecycle. */
    val restored: Boolean = false
)

/** One line of the online history: a code and when. */
data class OnlineLogEntry(val atMs: Long, val transition: OnlineTransition)

/** What the "Sesiones en línea" debug panel shows. Memory only, codes only. */
data class OnlineDebugState(
    val current: OnlineSession? = null,
    val confirmed: OnlineSignal? = null,
    val lastEvidence: Set<OnlineEvidence> = emptySet(),
    val lastReadingAt: Long? = null,
    val diagnostics: OnlineDiagnostics = OnlineDiagnostics(),
    val history: List<OnlineLogEntry> = emptyList(),
    /** False until the session left open in the database (if any) has been handed back. */
    val restored: Boolean = false
)

/**
 * Holds the one ride-lifecycle pipeline of the app process. It is an object because the
 * accessibility service writes to it and the Settings screen reads it, and they share nothing
 * else. The lifecycle lives in memory; since Fase 7B its events are also handed to the single
 * trip writer, and at start-up the trip still open in the database is handed back to it.
 */
object RideLifecycleMonitor {

    const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"
    private const val TAG = "LIFECYCLE"
    private const val ONLINE_HISTORY = 30

    private val classifier = UberScreenClassifier.spanish(UBER_DRIVER_PACKAGE)
    private val pipeline = RideLifecyclePipeline(classifier, clock = System::currentTimeMillis)

    /** Kept only in debug builds, for the sanitized copy; it can contain the rider's name. */
    private var lastSnapshot: ScreenSnapshot? = null
    private var connected = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var coordinator: TripPersistenceCoordinator? = null
    private var money: FinancialPersistenceCoordinator? = null
    private val financialDetector = FinancialDetector()
    private val todayPillDetector = UberTodayPillDetector()
    private var lastMoneyTextsKey: Int? = null
    private var lastTodayPillId: String? = null

    // Fase 8A: online sessions, read from the same snapshot
    private val onlineDetector = OnlineStateDetector(UBER_DRIVER_PACKAGE)
    private val onlineRules = OnlineSessionRules()
    private var onlineWriter: OnlineSessionCoordinator? = null
    private var onlineRestored = false
    /** A pause that came before the open session was restored; applied right after the restore. */
    private var pauseBeforeRestoreAt: Long? = null
    private val onlineHistory = ArrayDeque<OnlineLogEntry>()
    private val _online = MutableStateFlow(OnlineDebugState())

    /** Online sessions as the service sees them, for the debug panel. */
    val online: StateFlow<OnlineDebugState> = _online.asStateFlow()

    /** True while an online session is open: the service then re-reads Uber now and then (see KEEP_ALIVE_MS). */
    val hasOpenOnlineSession: Boolean
        @Synchronized get() = onlineRules.current != null
    private val _financialMetrics = MutableStateFlow(FinancialMetrics())

    /** Counters of the money writer, for the debug panel. */
    val financialMetrics: StateFlow<FinancialMetrics> = _financialMetrics.asStateFlow()
    private val _tripMetrics = MutableStateFlow(TripPersistenceMetrics())

    // Screens are not interpreted until the open trip is restored, so a ride under way can never
    // start a second session (and a second trip) during the first moments after a restart
    private var restored = false

    private val _state = MutableStateFlow(LifecycleDebugState())
    val state: StateFlow<LifecycleDebugState> = _state.asStateFlow()

    /** Counters of the trip writer, for the debug panel. */
    val tripMetrics: StateFlow<TripPersistenceMetrics> = _tripMetrics.asStateFlow()

    /**
     * Creates the trip writer once per process and restores the open trip. Called by the
     * accessibility service when it connects; later calls do nothing.
     */
    @Synchronized
    fun start(context: Context) {
        if (coordinator != null) return
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val database = AppDatabase.getInstance(app)
        // Trips and money share one queue: a trip's money is always written after the trip
        val queue = PersistenceQueue(scope)
        val writer = TripPersistenceCoordinator(
            repository = TripRepository(database),
            settings = AppSettings(app),
            queue = queue
        )
        val moneyWriter = FinancialPersistenceCoordinator(FinancialRepository(database), queue)
        val sessions = OnlineSessionCoordinator(OnlineRepository(database), queue)
        coordinator = writer
        money = moneyWriter
        onlineWriter = sessions
        scope.launch { writer.metrics.collect { _tripMetrics.value = it } }
        scope.launch { moneyWriter.metrics.collect { _financialMetrics.value = it } }
        writer.restore { trip -> mainHandler.post { onRestored(trip) } }
        sessions.restore { open -> mainHandler.post { onOnlineRestored(open) } }
    }

    @Synchronized
    private fun onOnlineRestored(open: List<OnlineSession>) {
        val now = System.currentTimeMillis()
        applyOnline(onlineRules.restore(open, now), now)
        onlineRestored = true
        pauseBeforeRestoreAt?.let { at -> applyOnline(onlineRules.onPaused(at), at) }
        pauseBeforeRestoreAt = null
        if (BuildConfig.DEBUG) Log.d(TAG, "online restored open=${open.size}")
        publishOnline(null, emptySet())
    }

    @Synchronized
    private fun onRestored(trip: Trip?) {
        if (trip != null) {
            pipeline.restore(trip.lifecycleSessionId, trip.relatedOfferHash, TripRestorePolicy.lifecycleStageOf(trip))
            if (BuildConfig.DEBUG) Log.d(TAG, "restored trip=${trip.id} session=${trip.lifecycleSessionId} status=${trip.status}")
        }
        restored = true
        publish(_state.value.lastSnapshotAt)
    }

    @Synchronized
    fun onSnapshot(snapshot: ScreenSnapshot, offerHash: String?): PipelineResult {
        if (!restored) return PipelineResult(classification = null)
        val result = pipeline.onSnapshot(snapshot, offerHash)
        if (BuildConfig.DEBUG) lastSnapshot = snapshot
        log(result)
        coordinator?.submit(result.events)
        observeMoney(snapshot, result)
        val onlineRecheck = observeOnline(snapshot, result)
        publish(snapshot.timestampMs)
        // Both may ask to read again: the sooner wins (money above used the lifecycle's own answer)
        val recheck = listOfNotNull(result.recheckAfterMs, onlineRecheck).minOrNull()
        return if (recheck == result.recheckAfterMs) result else result.copy(recheckAfterMs = recheck)
    }

    /**
     * Fase 8A: connected or not, from the same snapshot. Repaints are read too: an unchanged map
     * with "Estás conectado" is what keeps "last seen online" moving. Returns a re-read delay when
     * a change waits for confirmation.
     */
    private fun observeOnline(snapshot: ScreenSnapshot, result: PipelineResult): Long? {
        if (!onlineRestored) return null
        val c = result.classification
        val reading = onlineDetector.read(snapshot, c?.type, c?.confidence ?: 0.0)
        val update = onlineRules.onReading(reading, snapshot.timestampMs)
        applyOnline(update, snapshot.timestampMs)
        publishOnline(if (reading.signal != null || reading.mixed) snapshot.timestampMs else null, reading.evidence)
        return update.recheckAfterMs
    }

    private fun applyOnline(update: OnlineUpdate, atMs: Long) {
        onlineWriter?.submit(update.saves)
        update.transitions.forEach { t ->
            onlineHistory.addLast(OnlineLogEntry(atMs, t))
            while (onlineHistory.size > ONLINE_HISTORY) onlineHistory.removeFirst()
            if (BuildConfig.DEBUG) Log.d(TAG, "online=$t")
        }
    }

    private fun publishOnline(readingAt: Long?, evidence: Set<OnlineEvidence>) {
        val previous = _online.value
        _online.value = OnlineDebugState(
            current = onlineRules.current,
            confirmed = onlineRules.confirmed,
            lastEvidence = if (readingAt != null) evidence else previous.lastEvidence,
            lastReadingAt = readingAt ?: previous.lastReadingAt,
            diagnostics = onlineRules.diagnostics,
            history = onlineHistory.toList(),
            restored = onlineRestored
        )
    }

    /**
     * Fase 7C: reads money from the same snapshot. Skipped for exact repaints (the same texts give
     * the same amounts) and while the lifecycle is still confirming a screen, so an earnings screen is
     * read once the trip is known to be COMPLETED and can be linked to it.
     */
    private fun observeMoney(snapshot: ScreenSnapshot, result: PipelineResult) {
        val writer = money ?: return
        val classification = result.classification ?: return
        if (result.recheckAfterMs != null) return
        // The lifecycle fingerprint masks digits, so an amount changing in place looks like a
        // repaint to it; money compares the exact texts instead (kept only as a hash, in memory)
        val textsKey = snapshot.texts.hashCode()
        if (result.skipped == SkipReason.DUPLICATE_SNAPSHOT && result.events.isEmpty() && textsKey == lastMoneyTextsKey) return
        lastMoneyTextsKey = textsKey
        val zone = java.time.ZoneId.systemDefault()
        val detection = financialDetector.detect(snapshot, classification.type, pipeline.context, snapshot.timestampMs, zone)
        if (detection.classification.type != FinancialScreenType.UNKNOWN) {
            writer.submit(detection)
            return
        }
        // Fase 8A: Uber's "DOP … · HOY" above the map, a reference only. Written once per figure and day
        val pill = todayPillDetector.detect(snapshot, classification.type, snapshot.timestampMs, zone)
        if (pill == null) {
            writer.countUnknownScreen()
        } else if (pill.snapshotId != lastTodayPillId) {
            lastTodayPillId = pill.snapshotId
            val type = FinancialScreenClassification(FinancialScreenType.UBER_TODAY_PILL, pill.confidence, pill.evidence)
            writer.submit(FinancialDetection(type, snapshots = listOf(pill)))
        }
    }

    @Synchronized
    fun onServiceConnected() {
        connected = true
        pauseBeforeRestoreAt = null
        // Events may have been missed: the open session goes on only if Uber comes back in time
        onlineRules.onConnectionLost()
        // A new connection may follow missed events: whatever was in progress is no longer certain
        handOver(pipeline.onConnectionLost())
        publish(_state.value.lastSnapshotAt)
    }

    @Synchronized
    fun onServiceLost() {
        connected = false
        onlineRules.onConnectionLost()
        handOver(pipeline.onConnectionLost())
        publish(_state.value.lastSnapshotAt)
    }

    /**
     * The driver paused Observador: as a lost service for the ride, and the online session closes
     * here (PAUSED), since nothing after this can be seen.
     */
    @Synchronized
    fun onObserverPaused() {
        val now = System.currentTimeMillis()
        if (onlineRestored) {
            applyOnline(onlineRules.onPaused(now), now)
            publishOnline(null, emptySet())
        } else {
            pauseBeforeRestoreAt = now
        }
        onServiceLost()
    }

    /**
     * Debug button: forgets the ride in memory only. Stored trips are untouched (an open one stays
     * open in the database until a later screen or restart resolves it).
     */
    @Synchronized
    fun reset() {
        pipeline.reset()
        publish(_state.value.lastSnapshotAt)
    }

    /** The last read with names and addresses hidden, to paste when building detection rules. */
    @Synchronized
    fun sanitizedLastSnapshot(): String? = lastSnapshot?.let { SnapshotSanitizer.describe(it, classifier.signatures) }

    private fun handOver(result: PipelineResult) {
        log(result)
        coordinator?.submit(result.events)
    }

    private fun publish(lastSnapshotAt: Long?) {
        _state.value = LifecycleDebugState(
            context = pipeline.context,
            classification = pipeline.lastClassification,
            diagnostics = pipeline.diagnostics,
            history = pipeline.recentHistory,
            lastSnapshotAt = lastSnapshotAt,
            serviceConnected = connected,
            restored = restored
        )
    }

    /** Structured debug log: codes, states and the offer hash only, never the screen's text. */
    private fun log(result: PipelineResult) {
        if (!BuildConfig.DEBUG) return
        val c = result.classification
        if (c != null && result.skipped == null) {
            Log.d(
                TAG,
                "screen=${c.type} confidence=${"%.2f".format(c.confidence)} level=${c.level} " +
                    "provisional=${c.provisional} evidence=${c.evidence.joinToString(",") { it.code.name }}"
            )
        }
        result.transitions.forEach { t ->
            when (t) {
                is TransitionResult.Applied -> Log.d(TAG, "transition=${t.previous} -> ${t.current} event=${t.event}")
                is TransitionResult.Recovered -> Log.d(TAG, "recovered=${t.previous} -> ${t.current} event=${t.event}")
                is TransitionResult.Invalid -> Log.d(TAG, "invalid event=${t.event} from=${t.from}")
                is TransitionResult.Ignored -> Unit
            }
        }
        result.events.forEach { e ->
            Log.d(TAG, "event=${e.type} id=${e.eventId} session=${e.sessionId} offer=${e.relatedOfferHash} recovered=${e.recovered}")
        }
    }
}
