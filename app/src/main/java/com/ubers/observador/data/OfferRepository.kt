package com.ubers.observador.data

import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.DaySummary
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.models.OfferAnalysis
import kotlinx.coroutines.flow.Flow

class OfferRepository(db: AppDatabase) {

    private val dao = db.offerDao()

    suspend fun save(analysis: OfferAnalysis) {
        dao.insert(analysis.toEntity())
    }

    /** Returns true if inserted, false if the same offer was already saved within [dedupWindowMs]. */
    suspend fun saveOrSkip(analysis: OfferAnalysis, dedupWindowMs: Long): Boolean =
        dao.insertIfNotRecent(analysis.toEntity(), dedupWindowMs)

    /** Replaces every offer tagged with [prefix] by [offers] (used for debug test data). */
    suspend fun replaceByHashPrefix(prefix: String, offers: List<OfferEntity>) =
        dao.replaceByHashPrefix(prefix, offers)

    /** Deletes every offer tagged with [prefix]; returns how many were deleted. */
    suspend fun deleteByHashPrefix(prefix: String): Int = dao.deleteByHashPrefix(prefix)

    fun observeRecent(n: Int = 20): Flow<List<OfferEntity>> = dao.observeRecent(n)
    suspend fun getOffersSince(since: Long): List<OfferEntity> = dao.getSince(since)
    fun observeDaySummaries(): Flow<List<DaySummary>> = dao.observeDaySummaries()
    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()
    fun observeAvgRdpPerKm(): Flow<Double?> = dao.observeAvgRdpPerKm()
    fun observeAvgRdpPerHour(): Flow<Double?> = dao.observeAvgRdpPerHour()
    fun observeAvgScore(): Flow<Double?> = dao.observeAvgScore()
    fun observeCountAboveThreshold(threshold: Int): Flow<Int> = dao.observeCountAboveThreshold(threshold)
}
