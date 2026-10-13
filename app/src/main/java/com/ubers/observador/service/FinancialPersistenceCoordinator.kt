package com.ubers.observador.service

import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.domain.finance.FinancialDetection
import com.ubers.observador.domain.finance.FinancialMetrics
import com.ubers.observador.domain.finance.FinancialObservation
import com.ubers.observador.domain.finance.FinancialPersistResult
import com.ubers.observador.domain.finance.PlatformEarningsSnapshot
import com.ubers.observador.domain.money.MoneyFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single writer of money (Fase 7C), on the same [PersistenceQueue] as trips, so a trip's
 * money is written after the trip itself. Retries reuse the same observation id, so a retry can
 * never store an amount twice. Failures are logged and contained: the detector carries on.
 */
class FinancialPersistenceCoordinator(
    private val repository: FinancialRepository,
    private val queue: PersistenceQueue,
    /** Called after an amount is stored (the goal notification of Fase 8C). Failures are contained. */
    private val onStored: suspend () -> Unit = {}
) {

    private val _metrics = MutableStateFlow(FinancialMetrics())
    val metrics: StateFlow<FinancialMetrics> = _metrics.asStateFlow()

    fun submit(detection: FinancialDetection) {
        _metrics.value = _metrics.value.afterDetection(detection)
        detection.observations.forEach { observation -> queue.enqueue { persist(observation) } }
        detection.snapshots.forEach { snapshot -> queue.enqueue { persist(snapshot) } }
    }

    /** Counts a screen whose financial detector found no money screen. */
    fun countUnknownScreen() {
        _metrics.value = _metrics.value.copy(financialScreensUnknown = _metrics.value.financialScreensUnknown + 1)
    }

    private suspend fun persist(observation: FinancialObservation) {
        retrying("observation ${observation.observationId}") {
            val result = repository.persistObservation(observation, System.currentTimeMillis())
            _metrics.value = _metrics.value.afterPersist(result)
            if (result is FinancialPersistResult.Stored) {
                try {
                    onStored()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "After-store hook failed", e)
                }
            }
            if (BuildConfig.DEBUG) {
                // Amounts are sensitive: logged in debug builds only, with codes and ids, never screen text
                val trip = (result as? FinancialPersistResult.Stored)?.tripId ?: "unassigned"
                Log.d(TAG, "observation=${observation.type} amount=${MoneyFormat.format(observation.amount, signed = true)} trip=$trip result=${result::class.simpleName}")
            }
        }
    }

    private suspend fun persist(snapshot: PlatformEarningsSnapshot) {
        retrying("snapshot ${snapshot.snapshotId}") {
            if (repository.persistSnapshot(snapshot)) {
                _metrics.value = _metrics.value.copy(snapshotsCreated = _metrics.value.snapshotsCreated + 1)
            }
        }
    }

    private suspend fun retrying(what: String, block: suspend () -> Unit) {
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Room rolled the transaction back: nothing half-written
                Log.e(TAG, "Persisting $what failed (attempt ${attempt + 1})", e)
                _metrics.value = _metrics.value.copy(persistenceErrors = _metrics.value.persistenceErrors + 1)
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        Log.e(TAG, "Gave up on $what")
    }

    private companion object {
        const val TAG = "FINANCE"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
    }
}
