package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OfferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(offer: OfferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(offers: List<OfferEntity>)

    /** Removes every offer whose hash starts with [prefix]; returns how many were removed. */
    @Query("DELETE FROM detected_offers WHERE offer_hash LIKE :prefix || '%'")
    suspend fun deleteByHashPrefix(prefix: String): Int

    /** Swaps the offers tagged with [prefix] for [offers] in one transaction. */
    @Transaction
    suspend fun replaceByHashPrefix(prefix: String, offers: List<OfferEntity>) {
        deleteByHashPrefix(prefix)
        insertAll(offers)
    }

    @Query("SELECT COUNT(*) FROM detected_offers WHERE offer_hash = :hash AND detectedAt >= :since")
    suspend fun countByHashSince(hash: String, since: Long): Int

    /** Returns true if inserted, false if the same offer was already recorded within [windowMs]. */
    @Transaction
    suspend fun insertIfNotRecent(offer: OfferEntity, windowMs: Long): Boolean {
        if (countByHashSince(offer.offerHash, offer.detectedAt - windowMs) > 0) return false
        insert(offer)
        return true
    }

    @Query("SELECT * FROM detected_offers ORDER BY detectedAt DESC")
    fun observeAll(): Flow<List<OfferEntity>>

    /** Offers detected at or after [since], newest first. */
    @Query("SELECT * FROM detected_offers WHERE detectedAt >= :since ORDER BY detectedAt DESC")
    fun observeSince(since: Long): Flow<List<OfferEntity>>

    /** Detection time of the newest offer whose hash does not start with [excludePrefix]. */
    @Query("SELECT MAX(detectedAt) FROM detected_offers WHERE offer_hash NOT LIKE :excludePrefix || '%'")
    fun observeLatestDetectedAtExcluding(excludePrefix: String): Flow<Long?>

    @Query("SELECT * FROM detected_offers WHERE detectedAt >= :since ORDER BY detectedAt ASC")
    suspend fun getSince(since: Long): List<OfferEntity>
}
