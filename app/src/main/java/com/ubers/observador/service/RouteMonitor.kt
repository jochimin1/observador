package com.ubers.observador.service

import android.content.Context
import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.RouteRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.distance.LocationFix
import com.ubers.observador.domain.lifecycle.RideLifecycleContext
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.route.RouteRecorder
import com.ubers.observador.domain.route.RouteUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the one route recorder of the app process (Fase 8K). The foreground service hands it the
 * same GPS positions the kilometres get ([onFix]) and the ride lifecycle ([onRide]); points are
 * written through the same queue as trips, money and kilometres. When a trip's route closes, the
 * names of its start and end are asked of the map service (with internet) in the background.
 */
object RouteMonitor {

    private const val TAG = "ROUTE"

    // Off until «Grabar recorrido» is read from Ajustes
    private val recorder = RouteRecorder(startEnabled = false)
    private var repository: RouteRepository? = null
    private var namer: PlaceNamer? = null
    private var lastSession: String? = null
    private var lastState = RideLifecycleState.IDLE
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val naming = AtomicBoolean(false)

    /** Creates the writer once and closes the routes a previous process left open. */
    @Synchronized
    fun start(context: Context) {
        if (repository != null) return
        val app = context.applicationContext
        val repo = RouteRepository(AppDatabase.getInstance(app))
        repository = repo
        namer = PlaceNamer(app)
        PersistenceQueue.shared().enqueue {
            try {
                val closed = repo.closeLeftOpen(System.currentTimeMillis())
                if (closed.isNotEmpty()) Log.i(TAG, "Closed ${closed.size} route(s) left open")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not close the routes left open", e)
            }
            nameMissing()
        }
    }

    /** «Grabar recorrido» in Ajustes. On: a trip already under way starts now. */
    @Synchronized
    fun setEnabled(on: Boolean) {
        val now = System.currentTimeMillis()
        save(recorder.setEnabled(on, now))
        if (on) save(recorder.onRide(lastSession, lastState, now))
    }

    /** The service is going away (Apagar, or Android): the route under way is saved with what it has. */
    @Synchronized
    fun stop() {
        save(recorder.setEnabled(false, System.currentTimeMillis()))
    }

    @Synchronized
    fun onFix(fix: LocationFix) {
        save(recorder.onFix(fix))
    }

    @Synchronized
    fun onRide(lifecycle: RideLifecycleContext, nowMs: Long) {
        lastSession = lifecycle.sessionId
        lastState = lifecycle.state
        save(recorder.onRide(lifecycle.sessionId, lifecycle.state, nowMs))
    }

    private fun save(update: RouteUpdate) {
        if (update.isEmpty) return
        val repo = repository ?: return
        val closes = update.writes.any { it.close != null }
        PersistenceQueue.shared().enqueue {
            var saved = false
            for (attempt in 0 until 3) {
                try {
                    repo.save(update)
                    saved = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Saving the route failed (attempt ${attempt + 1})", e)
                    delay(500L * (attempt + 1))
                }
            }
            if (BuildConfig.DEBUG && saved) Log.d(TAG, "saved ${update.writes.sumOf { it.points.size }} point(s)")
            if (saved && closes) nameMissing()
        }
    }

    /**
     * Names the start and end of closed routes that have none yet. Runs in the background, one
     * batch at a time; with no internet it stops and tries again with the next route closed or
     * when a trip's detail is opened.
     */
    fun nameMissing(context: Context? = null) {
        if (repository == null && context != null) start(context)
        val repo = repository ?: return
        val places = namer ?: return
        if (!naming.compareAndSet(false, true)) return
        io.launch {
            try {
                for (r in repo.unnamed(BATCH)) {
                    val start = r.startPlace ?: r.startLatE7?.let { lat -> r.startLngE7?.let { places.nameOf(lat, it) } }
                    val end = r.endPlace ?: r.endLatE7?.let { lat -> r.endLngE7?.let { places.nameOf(lat, it) } }
                    if (start == r.startPlace && end == r.endPlace) break // no answer: probably no internet
                    PersistenceQueue.shared().enqueue {
                        try {
                            repo.setPlaces(r.tripId, start, end)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not save place names", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Naming places failed", e)
            } finally {
                naming.set(false)
            }
        }
    }

    private const val BATCH = 10
}
