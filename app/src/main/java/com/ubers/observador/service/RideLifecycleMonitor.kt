package com.ubers.observador.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.FinancialRepository
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
import com.ubers.observador.domain.finance.FinancialScreenType
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

/**
 * Holds the one ride-lifecycle pipeline of the app process. It is an object because the
 * accessibility service writes to it and the Settings screen reads it, and they share nothing
 * else. The lifecycle lives in memory; since Fase 7B its events are also handed to the single
 * trip writer, and at start-up the trip still open in the database is handed back to it.
 */
object RideLifecycleMonitor {

    const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"
    private const val TAG = "LIFECYCLE"

    private val classifier = UberScreenClassifier.spanish(UBER_DRIVER_PACKAGE)
    private val pipeline = RideLifecyclePipeline(classifier, clock = System::currentTimeMillis)

    /** Kept only in debug builds, for the sanitized copy; it can contain the rider's name. */
    private var lastSnapshot: ScreenSnapshot? = null
    private var connected = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var coordinator: TripPersistenceCoordinator? = null
    private var money: FinancialPersistenceCoordinator? = null
    private val financialDetector = FinancialDetector()
    private var lastMoneyTextsKey: Int? = null
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
        coordinator = writer
        money = moneyWriter
        scope.launch { writer.metrics.collect { _tripMetrics.value = it } }
        scope.launch { moneyWriter.metrics.collect { _financialMetrics.value = it } }
        writer.restore { trip -> mainHandler.post { onRestored(trip) } }
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
        publish(snapshot.timestampMs)
        return result
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
        val detection = financialDetector.detect(snapshot, classification.type, pipeline.context, snapshot.timestampMs, java.time.ZoneId.systemDefault())
        if (detection.classification.type == FinancialScreenType.UNKNOWN) {
            writer.countUnknownScreen()
        } else {
            writer.submit(detection)
        }
    }

    @Synchronized
    fun onServiceConnected() {
        connected = true
        // A new connection may follow missed events: whatever was in progress is no longer certain
        handOver(pipeline.onConnectionLost())
        publish(_state.value.lastSnapshotAt)
    }

    @Synchronized
    fun onServiceLost() {
        connected = false
        handOver(pipeline.onConnectionLost())
        publish(_state.value.lastSnapshotAt)
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
