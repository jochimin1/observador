package com.ubers.observador.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.ubers.observador.domain.backup.MaintenanceGate

/**
 * One queue, one consumer: every database write of trips and money runs here, one after the
 * other, in submission order. So the money read on a trip's summary screen is always written
 * after that trip's completion, and no two writes ever interleave.
 */
class PersistenceQueue(
    scope: CoroutineScope,
    private val gate: MaintenanceGate = MaintenanceGate.shared,
    private val onFailure: (Throwable) -> Unit = {}
) {

    companion object {
        @Volatile private var shared: PersistenceQueue? = null

        /**
         * The one queue of the process (trips, money, online sessions and kilometres), created by
         * whichever service starts first: the accessibility service or the foreground service.
         */
        fun shared(): PersistenceQueue = shared ?: synchronized(this) {
            shared ?: PersistenceQueue(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
                onFailure = { android.util.Log.e("PersistenceQueue", "Data operation failed", it) }
            ).also { shared = it }
        }
    }

    private data class Work(val permit: MaintenanceGate.Permit, val block: suspend () -> Unit)
    private val jobs = Channel<Work>(Channel.UNLIMITED, onUndeliveredElement = { gate.release(it.permit) })

    init {
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { jobs.cancel() }
        scope.launch {
            try {
                for (job in jobs) {
                    try { gate.runReserved(job.permit, job.block) }
                    catch (e: Exception) {
                        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                        onFailure(e)
                    }
                }
            } finally { jobs.cancel() }
        }
    }

    fun enqueue(job: suspend () -> Unit): Boolean {
        val work = Work(gate.reserve(), job)
        val accepted = jobs.trySend(work).isSuccess
        if (!accepted) gate.release(work.permit)
        return accepted
    }
}
