package com.ubers.observador.service

import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.domain.lifecycle.LifecycleDiagnostics
import com.ubers.observador.domain.lifecycle.LifecycleLogEntry
import com.ubers.observador.domain.lifecycle.PipelineResult
import com.ubers.observador.domain.lifecycle.RideLifecycleContext
import com.ubers.observador.domain.lifecycle.RideLifecyclePipeline
import com.ubers.observador.domain.lifecycle.ScreenClassification
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.SnapshotSanitizer
import com.ubers.observador.domain.lifecycle.TransitionResult
import com.ubers.observador.domain.lifecycle.UberScreenClassifier
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
    val serviceConnected: Boolean = false
)

/**
 * Holds the one ride-lifecycle pipeline of the app process. It is an object because the
 * accessibility service writes to it and the Settings screen reads it, and they share nothing
 * else. Fase 7A keeps everything in memory: it is gone when the process dies.
 */
object RideLifecycleMonitor {

    const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"
    private const val TAG = "LIFECYCLE"

    private val classifier = UberScreenClassifier.spanish(UBER_DRIVER_PACKAGE)
    private val pipeline = RideLifecyclePipeline(classifier, clock = System::currentTimeMillis)

    /** Kept only in debug builds, for the sanitized copy; it can contain the rider's name. */
    private var lastSnapshot: ScreenSnapshot? = null
    private var connected = false

    private val _state = MutableStateFlow(LifecycleDebugState())
    val state: StateFlow<LifecycleDebugState> = _state.asStateFlow()

    @Synchronized
    fun onSnapshot(snapshot: ScreenSnapshot, offerHash: String?): PipelineResult {
        val result = pipeline.onSnapshot(snapshot, offerHash)
        if (BuildConfig.DEBUG) lastSnapshot = snapshot
        log(result)
        publish(snapshot.timestampMs)
        return result
    }

    @Synchronized
    fun onServiceConnected() {
        connected = true
        // A new connection may follow missed events: whatever was in progress is no longer certain
        log(pipeline.onConnectionLost())
        publish(_state.value.lastSnapshotAt)
    }

    @Synchronized
    fun onServiceLost() {
        connected = false
        log(pipeline.onConnectionLost())
        publish(_state.value.lastSnapshotAt)
    }

    /** Debug button: forgets the current ride. Offers, settings and history in Room are untouched. */
    @Synchronized
    fun reset() {
        pipeline.reset()
        publish(_state.value.lastSnapshotAt)
    }

    /** The last read with names and addresses hidden, to paste when building detection rules. */
    @Synchronized
    fun sanitizedLastSnapshot(): String? = lastSnapshot?.let { SnapshotSanitizer.describe(it, classifier.signatures) }

    private fun publish(lastSnapshotAt: Long?) {
        _state.value = LifecycleDebugState(
            context = pipeline.context,
            classification = pipeline.lastClassification,
            diagnostics = pipeline.diagnostics,
            history = pipeline.recentHistory,
            lastSnapshotAt = lastSnapshotAt,
            serviceConnected = connected
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
