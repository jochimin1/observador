package com.ubers.observador.data

import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.db.OfferEntity
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.models.OfferAnalysis
import kotlinx.coroutines.flow.Flow

class OfferRepository(db: AppDatabase) {

    private val dao = db.offerDao()

    suspend fun setExcluded(id: Long, excluded: Boolean): Boolean = MaintenanceGate.shared.access {
        dao.setExcluded(id, excluded) == 1
    }

    suspend fun save(analysis: OfferAnalysis): Unit = MaintenanceGate.shared.access {
        dao.insert(analysis.toEntity())
    }

    /** Returns true if inserted, false if the same offer was already saved within [dedupWindowMs]. */
    suspend fun saveOrSkip(analysis: OfferAnalysis, dedupWindowMs: Long): Boolean = MaintenanceGate.shared.access {
        dao.insertIfNotRecent(analysis.toEntity(), dedupWindowMs)
    }

    /** Replaces every offer tagged with [prefix] by [offers] (used for debug test data). */
    suspend fun replaceByHashPrefix(prefix: String, offers: List<OfferEntity>) = MaintenanceGate.shared.access {
        dao.replaceByHashPrefix(prefix, offers)
    }

    /** Deletes every offer tagged with [prefix]; returns how many were deleted. */
    suspend fun deleteByHashPrefix(prefix: String): Int = MaintenanceGate.shared.access {
        dao.deleteByHashPrefix(prefix)
    }

    /**
     * Deletes one offer and returns the row exactly as stored, so it can be put back with
     * [restore]; null if it was already gone.
     */
    suspend fun delete(id: Long): OfferEntity? = MaintenanceGate.shared.access {
        val stored = dao.getById(id) ?: return@access null
        dao.deleteById(id)
        return@access stored
    }

    /** Deletes every offer detected in [from, to) and returns them as stored, for [restore]. */
    suspend fun deleteBetween(from: Long, to: Long): List<OfferEntity> = MaintenanceGate.shared.access {
        val stored = dao.getBetween(from, to)
        if (stored.isNotEmpty()) dao.deleteBetween(from, to)
        return@access stored
    }

    /** Puts deleted offers back with their original ids and values. */
    suspend fun restore(offers: List<OfferEntity>) = MaintenanceGate.shared.access {
        dao.insertAll(offers)
    }

    /** Every offer, newest first. */
    fun observeAll(): Flow<List<OfferEntity>> = dao.observeAll()

    /** Offers detected at or after [since], newest first. */
    fun observeSince(since: Long): Flow<List<OfferEntity>> = dao.observeSince(since)

    /** When the newest offer not tagged with [testPrefix] was detected; null if there is none. */
    fun observeLatestRealOfferAt(testPrefix: String): Flow<Long?> =
        dao.observeLatestDetectedAtExcluding(testPrefix)

    suspend fun getOffersSince(since: Long): List<OfferEntity> = MaintenanceGate.shared.access {
        dao.getSince(since)
    }
}
