package com.ubers.observador.data

import com.ubers.observador.data.db.AppDatabase
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

    /** Every offer, newest first. */
    fun observeAll(): Flow<List<OfferEntity>> = dao.observeAll()

    /** Offers detected at or after [since], newest first. */
    fun observeSince(since: Long): Flow<List<OfferEntity>> = dao.observeSince(since)

    /** When the newest offer not tagged with [testPrefix] was detected; null if there is none. */
    fun observeLatestRealOfferAt(testPrefix: String): Flow<Long?> =
        dao.observeLatestDetectedAtExcluding(testPrefix)

    suspend fun getOffersSince(since: Long): List<OfferEntity> = dao.getSince(since)
}
