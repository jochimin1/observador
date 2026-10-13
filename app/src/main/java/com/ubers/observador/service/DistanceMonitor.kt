package com.ubers.observador.service

import android.content.Context
import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.WorkdayClock
import com.ubers.observador.domain.distance.DistanceConditions
import com.ubers.observador.domain.distance.DistanceDiagnostics
import com.ubers.observador.domain.distance.DistanceGate
import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.distance.DistanceTracker
import com.ubers.observador.domain.distance.DistanceUpdate
import com.ubers.observador.domain.distance.LocationFix
import com.ubers.observador.domain.distance.RideContext
import com.ubers.observador.domain.distance.SegmentOrigin
import com.ubers.observador.domain.distance.TripStage
import com.ubers.observador.domain.lifecycle.RideLifecycleContext
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.online.OnlineSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

/** What the screens show about the GPS right now. Memory only; no positions. */
data class DistanceLiveState(
    /** How it is measuring, or null when the GPS is off. */
    val measuring: SegmentOrigin? = null,
    val open: DistanceSegment? = null,
    val lastFixAt: Long? = null,
    /** The foreground service could not take the "location" type (started in the background): open the app once. */
    val backgroundLimited: Boolean = false,
    val diagnostics: DistanceDiagnostics = DistanceDiagnostics(),
    val restored: Boolean = false
)

/**
 * Holds the one kilometre tracker of the app process (Fase 8E). The foreground service decides
 * when to measure ([apply]) and hands it positions ([onFix]); the ride lifecycle tells it which
 * trip is under way ([onRide]). Positions go no further than [DistanceTracker]'s memory: only
 * totals are written, through the same queue as trips, money and online sessions.
 */
object DistanceMonitor {

    private const val TAG = "DISTANCE"

    private val tracker = DistanceTracker()
    private var repository: DistanceRepository? = null
    private var restored = false
    private var clock = WorkdayClock(4, ZoneId.systemDefault())
    private var ride = RideContext()
    private var lastConditions: DistanceConditions? = null
    private var backgroundLimited = false
    private var lastFixAt: Long? = null

    private val _state = MutableStateFlow(DistanceLiveState())
    val state: StateFlow<DistanceLiveState> = _state.asStateFlow()

    /** Creates the writer once and closes stretches a previous process left open. */
    @Synchronized
    fun start(context: Context) {
        if (repository != null) return
        val repo = DistanceRepository(AppDatabase.getInstance(context.applicationContext))
        repository = repo
        PersistenceQueue.shared().enqueue {
            val open = try {
                repo.getOpenSegments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not read open stretches", e)
                emptyList()
            }
            save(tracker.restore(open))
            onRestored()
        }
    }

    @Synchronized
    private fun onRestored() {
        restored = true
        lastConditions?.let { apply(it, System.currentTimeMillis()) }
        publish()
    }

    @Synchronized
    fun setClock(workdayClock: WorkdayClock) {
        clock = workdayClock
        tracker.setClock(workdayClock)
    }

    @Synchronized
    fun setBackgroundLimited(limited: Boolean) {
        backgroundLimited = limited
        publish()
    }

    /** Starts, switches or stops measuring to match [c]. Returns whether the GPS should be on. */
    @Synchronized
    fun apply(c: DistanceConditions, nowMs: Long): Boolean {
        lastConditions = c
        if (!restored) return false
        val desired = DistanceGate.desired(c)
        val current = tracker.open?.origin
        if (desired != current) {
            if (current != null) save(tracker.stop(DistanceGate.endReason(current, c), nowMs))
            if (desired != null) {
                save(tracker.start(desired, nowMs, clock))
                save(tracker.onContext(ride, nowMs))
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "measuring=${desired ?: "off"}")
        }
        publish()
        return desired != null
    }

    @Synchronized
    fun onFix(fix: LocationFix) {
        lastFixAt = fix.timeMs
        save(tracker.onFix(fix, ride))
        publish()
    }

    /** The ride lifecycle or the online session changed. */
    @Synchronized
    fun onRide(lifecycle: RideLifecycleContext, online: OnlineSession?, nowMs: Long) {
        ride = rideContextOf(lifecycle, online, nowMs)
        save(tracker.onContext(ride, nowMs))
    }

    private fun save(update: DistanceUpdate) {
        if (update.isEmpty) return
        val repo = repository ?: return
        PersistenceQueue.shared().enqueue {
            repeat(3) { attempt ->
                try {
                    repo.save(update)
                    return@enqueue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Saving kilometres failed (attempt ${attempt + 1})", e)
                    delay(500L * (attempt + 1))
                }
            }
        }
    }

    private fun publish() {
        _state.value = DistanceLiveState(
            measuring = tracker.open?.origin,
            open = tracker.open,
            lastFixAt = lastFixAt,
            backgroundLimited = backgroundLimited,
            diagnostics = tracker.diagnostics,
            restored = restored
        )
    }

    /** Pickup while going to the rider or waiting, on trip while riding; online from the 8A session. */
    fun rideContextOf(lifecycle: RideLifecycleContext, online: OnlineSession?, nowMs: Long): RideContext {
        val stage = when (lifecycle.state) {
            RideLifecycleState.TO_PICKUP, RideLifecycleState.ARRIVED_OR_WAITING -> TripStage.PICKUP
            RideLifecycleState.IN_TRIP -> TripStage.ON_TRIP
            else -> null
        }
        val isOnline = online != null && online.isOpen && !online.isStale(nowMs)
        return RideContext(
            online = isOnline || stage != null,
            tripSessionId = if (stage != null) lifecycle.sessionId else null,
            stage = stage
        )
    }
}
