package com.ubers.observador.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Fase 7D storage. Reads only; statistics happen in the domain, never in SQL. */
@Dao
interface IntelligenceDao {

    /** Versions are never replaced: a clash fails loudly instead of rewriting history. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: ProfitModelVersionEntity)

    @Query("SELECT * FROM profit_model_versions ORDER BY number ASC")
    suspend fun getVersions(): List<ProfitModelVersionEntity>

    @Query("SELECT * FROM profit_model_versions ORDER BY number ASC")
    fun observeVersions(): Flow<List<ProfitModelVersionEntity>>

    @Query("SELECT * FROM profit_model_versions WHERE id = :id")
    suspend fun getVersion(id: String): ProfitModelVersionEntity?

    /** The same offer read many times stays one evaluation per candidate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShadow(evaluation: ShadowEvaluationEntity): Long

    @Query("SELECT * FROM shadow_evaluations WHERE candidate_version_id = :candidateId")
    suspend fun getShadow(candidateId: String): List<ShadowEvaluationEntity>

    @Query("SELECT COUNT(*) FROM shadow_evaluations")
    fun observeShadowCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: CalibrationRunEntity)

    @Query("SELECT * FROM calibration_runs ORDER BY created_at DESC LIMIT :limit")
    fun observeRuns(limit: Int): Flow<List<CalibrationRunEntity>>

    @Query("DELETE FROM profit_model_versions WHERE id LIKE :prefix || '%'")
    suspend fun deleteVersionsByIdPrefix(prefix: String): Int

    @Query("DELETE FROM shadow_evaluations WHERE evaluation_id LIKE :prefix || '%'")
    suspend fun deleteShadowByIdPrefix(prefix: String): Int

    @Query("DELETE FROM calibration_runs WHERE run_id LIKE :prefix || '%'")
    suspend fun deleteRunsByIdPrefix(prefix: String): Int
}
