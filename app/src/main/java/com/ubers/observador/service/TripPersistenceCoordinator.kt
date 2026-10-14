package com.ubers.observador.service

import android.util.Log
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.TripRepository
import com.ubers.observador.domain.lifecycle.RideLifecycleEvent
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripDecisionSnapshot
import com.ubers.observador.domain.trips.TripEventInput
import com.ubers.observador.domain.trips.TripPersistenceMetrics
import com.ubers.observador.domain.trips.TripPersistenceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The single writer of trips (Fase 7B). Lifecycle events go into the shared [PersistenceQueue]
 * (also used for money, Fase 7C) and are written one
 * at a time, in order, so two quick events (arrived, then started) can never interleave. A
 * database error is logged and retried with the same eventId, which makes the retry safe: an
 * event already stored is ignored. Errors never reach the accessibility service, and the ride
 * lifecycle in memory carries on regardless.
 */
class TripPersistenceCoordinator(
    private val repository: TripRepository,
    private val settings: AppSettings,
    private val queue: PersistenceQueue
) {

    private val _metrics = MutableStateFlow(TripPersistenceMetrics())
    val metrics: StateFlow<TripPersistenceMetrics> = _metrics.asStateFlow()

    /** Queues the events that concern trips; offers and session-less events are skipped. */
    fun submit(events: List<RideLifecycleEvent>) {
        events.mapNotNull(TripEventInput::from).forEach { input -> queue.enqueue { persistWithRetry(input) } }
    }

    /**
     * Fase 8F: the rider's name, queued after the trip's own events so the trip already exists.
     * A failure is logged and dropped: the name is shown again on the next screen.
     */
    fun submitRiderName(sessionId: String, name: String) {
        queue.enqueue {
            try {
                repository.setRiderName(sessionId, name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TripWriter", "Rider name not stored", e)
            }
        }
    }

    /**
     * Queues the start-up recovery ahead of any event; [onDone] receives the trip still open
     * (already marked LIFECYCLE_LOST) or null. It is called even if the database fails, so the
     * lifecycle never waits forever.
     */
    fun restore(onDone: (Trip?) -> Unit) {
        queue.enqueue { runRestore(onDone) }
    }

    private suspend fun runRestore(onDone: (Trip?) -> Unit) {
        val active = try {
            val plan = repository.restoreOpenTrip(System.currentTimeMillis())
            _metrics.value = _metrics.value.after(plan.write)
            plan.active
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore the open trip; continuing without it", e)
            _metrics.value = _metrics.value.afterError()
            null
        }
        onDone(active)
    }

    private suspend fun persistWithRetry(input: TripEventInput) {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val write = repository.persist(input, decisionSnapshot())
                _metrics.value = _metrics.value.after(write)
                log(input, write.result)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Room rolled the transaction back: nothing half-written. Same eventId on retry.
                Log.e(TAG, "Persisting ${input.lifecycleType} (attempt ${attempt + 1}) failed", e)
                _metrics.value = _metrics.value.afterError()
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        Log.e(TAG, "Gave up on event ${input.eventId}; the trip in the database may lag behind")
    }

    private suspend fun decisionSnapshot(): TripDecisionSnapshot {
        val engine = settings.engineSettings.first()
        val cuts = settings.qualityThresholds.first()
        return TripDecisionSnapshot(
            minRdpPerKm = engine.minRdpPerKm,
            minRdpPerHour = engine.minRdpPerHour,
            maxPickupKm = engine.maxPickupKm,
            maxPickupMinutes = engine.maxPickupMin,
            goodFrom = cuts.goodFrom,
            excellentFrom = cuts.excellentFrom,
            weights = engine.weights
        )
    }

    /** Ids, types and results only: nothing from the screen. */
    private fun log(input: TripEventInput, result: TripPersistenceResult) {
        if (com.ubers.observador.BuildConfig.DEBUG) {
            Log.d(TAG, "event=${input.lifecycleType} id=${input.eventId} session=${input.lifecycleSessionId} result=$result")
        }
    }

    private companion object {
        const val TAG = "TRIPS"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
    }
}
