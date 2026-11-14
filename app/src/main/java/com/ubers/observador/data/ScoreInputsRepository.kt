package com.ubers.observador.data

import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.toDomain
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.scoring.ScoreHistory
import com.ubers.observador.domain.scoring.ScoreV2Inputs
import com.ubers.observador.domain.scoring.WaitEstimate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 1.44.0: what V2 may know when an offer appears: every operating cost model (immutable and
 * dated, so an old offer is always scored with the model of its day) and the driver's own waits
 * at the pickup over the last 30 days. Cached briefly: shadow scoring runs on every new offer.
 */
class ScoreInputsRepository(private val db: AppDatabase) {

    private val mutex = Mutex()
    private var cached: Pair<Long, ScoreV2Inputs>? = null

    suspend fun inputs(nowMs: Long): ScoreV2Inputs = mutex.withLock {
        cached?.let { (at, value) -> if (nowMs - at in 0 until CACHE_MS) return@withLock value }
        val loaded = MaintenanceGate.shared.access {
            val models = db.operatingCostDao().getAll().map { it.toDomain() }
            val trips = db.tripDao().getTripsTouching(nowMs - ScoreHistory.WINDOW_MS, nowMs + 1).map { it.toDomain() }
            ScoreV2Inputs(models, WaitEstimate.of(ScoreHistory.pickupWaits(trips)))
        }
        cached = nowMs to loaded
        loaded
    }

    /** Time to the next trip after one ends (last 30 days), for the detail panel only. */
    suspend fun afterTripWait(nowMs: Long): WaitEstimate? {
        val from = nowMs - ScoreHistory.WINDOW_MS
        val trips = MaintenanceGate.shared.access { db.tripDao().getTripsTouching(from, nowMs + 1).map { it.toDomain() } }
        val sessions = OnlineRepository(db).getBetween(from, nowMs + 1)
        val routeEnds = IntelligenceRepository(db).routeEnds(trips.map { it.id })
        return WaitEstimate.of(ScoreHistory.afterTripWaits(trips, sessions, nowMs, routeEnds))
    }

    private companion object { const val CACHE_MS = 2 * 60_000L }
}
