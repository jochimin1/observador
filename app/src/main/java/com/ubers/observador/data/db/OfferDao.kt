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

    @Query("SELECT * FROM detected_offers ORDER BY detectedAt DESC LIMIT :n")
    fun observeRecent(n: Int): Flow<List<OfferEntity>>

    @Query("SELECT * FROM detected_offers WHERE detectedAt >= :since ORDER BY detectedAt ASC")
    suspend fun getSince(since: Long): List<OfferEntity>

    @Query(
        """SELECT date(detectedAt / 1000, 'unixepoch', 'localtime') AS day,
                  COUNT(*) AS count,
                  AVG(rdpPerKm) AS avgRdpPerKm,
                  AVG(score) AS avgScore
           FROM detected_offers
           GROUP BY day
           ORDER BY day DESC"""
    )
    fun observeDaySummaries(): Flow<List<DaySummary>>

    @Query("SELECT COUNT(*) FROM detected_offers")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT AVG(rdpPerKm) FROM detected_offers")
    fun observeAvgRdpPerKm(): Flow<Double?>

    @Query("SELECT AVG(rdpPerHour) FROM detected_offers")
    fun observeAvgRdpPerHour(): Flow<Double?>

    @Query("SELECT AVG(score) FROM detected_offers")
    fun observeAvgScore(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM detected_offers WHERE score >= :threshold")
    fun observeCountAboveThreshold(threshold: Int): Flow<Int>
}
