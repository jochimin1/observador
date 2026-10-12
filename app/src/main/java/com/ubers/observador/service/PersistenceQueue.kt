package com.ubers.observador.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One queue, one consumer: every database write of trips and money runs here, one after the
 * other, in submission order. So the money read on a trip's summary screen is always written
 * after that trip's completion, and no two writes ever interleave.
 */
class PersistenceQueue(scope: CoroutineScope) {

    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in jobs) job()
        }
    }

    fun enqueue(job: suspend () -> Unit) {
        jobs.trySend(job)
    }
}
