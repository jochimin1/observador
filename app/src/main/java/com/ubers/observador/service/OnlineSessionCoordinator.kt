package com.ubers.observador.service

import android.util.Log
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.domain.online.OnlineSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The single writer of online sessions (Fase 8A), on the same [PersistenceQueue] as trips and
 * money, so writes never interleave. A save is a whole row with a fixed id, so a retry can never
 * duplicate a session. Failures are logged and contained.
 */
class OnlineSessionCoordinator(
    private val repository: OnlineRepository,
    private val queue: PersistenceQueue
) {

    /** Reads the open sessions on the queue, then hands them back (on the queue's thread). */
    fun restore(onRestored: (List<OnlineSession>) -> Unit) {
        queue.enqueue {
            val open = try {
                repository.getOpen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not read the open online session; starting without it", e)
                emptyList()
            }
            onRestored(open)
        }
    }

    fun submit(saves: List<OnlineSession>) {
        if (saves.isEmpty()) return
        queue.enqueue {
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    repository.save(saves)
                    if (BuildConfig.DEBUG) {
                        saves.forEach { Log.d(TAG, "session=${it.id} kind=${it.startKind} open=${it.isOpen} end=${it.endReason} gaps=${it.gaps.size}") }
                    }
                    return@enqueue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Saving online session failed (attempt ${attempt + 1})", e)
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
            Log.e(TAG, "Gave up saving ${saves.size} online session(s)")
        }
    }

    private companion object {
        const val TAG = "ONLINE"
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
    }
}
