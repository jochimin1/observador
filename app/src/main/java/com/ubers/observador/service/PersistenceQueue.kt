package com.ubers.observador.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.health.QueueHealth

/**
 * One queue, one consumer: every database write of trips and money runs here, one after the
 * other, in submission order. So the money read on a trip's summary screen is always written
 * after that trip's completion, and no two writes ever interleave.
 *
 * 1.39.0: the queue counts what it does ([health]): jobs waiting, the oldest one, the last
 * write, failures and whether the consumer is still running. A recoverable failure (an
 * [Exception]) is contained, counted and the next job runs; cancellation of the queue's scope
 * still ends it; an [Error] is not swallowed: it is counted, the queue is marked stopped and the
 * error propagates.
 */
class PersistenceQueue(
    scope: CoroutineScope,
    private val gate: MaintenanceGate = MaintenanceGate.shared,
    private val now: () -> Long = System::currentTimeMillis,
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

        /** The shared queue's counters, or a fresh (empty, running) state when none exists yet. */
        fun sharedHealth(): StateFlow<QueueHealth> = shared().health
    }

    private data class Work(val permit: MaintenanceGate.Permit, val submittedAt: Long, val block: suspend () -> Unit)
    private val jobs = Channel<Work>(Channel.UNLIMITED, onUndeliveredElement = { gate.release(it.permit); finished(it) })

    /** Submission times of the jobs not finished yet, oldest first. */
    private val waiting = ArrayDeque<Long>()
    private val _health = MutableStateFlow(QueueHealth())
    val health: StateFlow<QueueHealth> = _health.asStateFlow()

    init {
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { jobs.cancel() }
        scope.launch {
            try {
                for (job in jobs) {
                    try {
                        gate.runReserved(job.permit, job.block)
                        finished(job)
                        _health.update { it.copy(lastWriteAt = now()) }
                    } catch (e: Exception) {
                        finished(job)
                        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                        val at = now()
                        _health.update { it.copy(failures = it.failures + 1, lastFailureAt = at, lastFailure = e.javaClass.simpleName) }
                        onFailure(e)
                    } catch (e: Error) {
                        finished(job)
                        val at = now()
                        _health.update { it.copy(failures = it.failures + 1, lastFailureAt = at, lastFailure = e.javaClass.simpleName) }
                        throw e
                    }
                }
            } finally {
                _health.update { it.copy(alive = false) }
                jobs.cancel()
            }
        }
    }

    private fun finished(work: Work) = synchronized(waiting) {
        waiting.remove(work.submittedAt)
        _health.update { it.copy(pending = waiting.size, oldestPendingAt = waiting.firstOrNull()) }
    }

    fun enqueue(job: suspend () -> Unit): Boolean {
        val work = Work(gate.reserve(), now(), job)
        synchronized(waiting) {
            waiting.addLast(work.submittedAt)
            _health.update { it.copy(pending = waiting.size, oldestPendingAt = waiting.firstOrNull()) }
        }
        val accepted = jobs.trySend(work).isSuccess
        if (!accepted) {
            gate.release(work.permit)
            finished(work)
        }
        return accepted
    }
}
