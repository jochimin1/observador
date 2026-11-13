package com.ubers.observador.domain.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Process-wide admission barrier, including work submitted to queues before it starts running. */
class MaintenanceGate {
    private val lock = Any()
    private val maintenance = Mutex()
    private val current = ThreadLocal<Permit?>()
    private var closed = false
    private var sealed = false
    private var active = 0
    private var drained = CompletableDeferred<Unit>()
    private val pending = linkedSetOf<Permit>()

    class Permit internal constructor(internal val gate: MaintenanceGate) {
        internal val ready = CompletableDeferred<Unit>()
        internal var admitted = false
        internal var released = false
        internal var owner: Session? = null
    }

    class Session internal constructor() {
        internal var retain = false
        /** A closed Room instance must never be used again in this process, even after rollback. */
        fun requireRestart() { retain = true }
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Reserve at submission, not execution: an exclusive operation also waits for queued writes. */
    fun reserve(): Permit = synchronized(lock) {
        Permit(this).also {
            if (closed) pending += it else admit(it)
        }
    }

    private fun admit(permit: Permit) {
        permit.admitted = true
        active++
        permit.ready.complete(Unit)
    }

    fun release(permit: Permit) = synchronized(lock) {
        require(permit.gate === this)
        if (!permit.released) {
            permit.released = true
            if (permit.admitted) active-- else pending.remove(permit)
            if (closed && active == 0) drained.complete(Unit)
        }
    }

    suspend fun <T> runReserved(permit: Permit, block: suspend () -> T): T {
        require(permit.gate === this)
        try {
            permit.ready.await()
            return withContext(current.asContextElement(permit)) { block() }
        } finally { release(permit) }
    }

    private fun inherited(): Permit? = current.get()?.takeIf {
        synchronized(lock) { it.gate === this && !it.released && (it.admitted || it.owner != null) }
    }

    suspend fun <T> access(block: suspend () -> T): T =
        if (inherited() != null) block() else runReserved(reserve(), block)

    /** For synchronous log writers: refuse new work during maintenance without blocking Android's UI. */
    fun <T> tryAccess(block: () -> T): T? {
        if (inherited() != null) return block()
        val permit = synchronized(lock) {
            if (closed) null else Permit(this).also { admit(it) }
        } ?: return null
        val previous = current.get()
        current.set(permit)
        try { return block() } finally { current.set(previous); release(permit) }
    }

    suspend fun <T> exclusive(timeoutMs: Long = 5_000, block: suspend (Session) -> T): T {
        inherited()?.owner?.let { return block(it) }
        check(inherited() == null) { "Cannot upgrade an admitted data operation to maintenance" }
        return maintenance.withLock {
            val session = Session()
            val owner = Permit(this).also { it.owner = session }
            synchronized(lock) {
                check(!sealed) { "Data maintenance requires a process restart" }
                closed = true
                drained = CompletableDeferred()
                if (active == 0) drained.complete(Unit)
            }
            try {
                withTimeout(timeoutMs) { drained.await() }
                withContext(current.asContextElement(owner)) { block(session) }
            } finally {
                synchronized(lock) {
                    owner.released = true
                    sealed = session.retain
                    if (!sealed) {
                        closed = false
                        // Promote all waiting reservations atomically. A following maintenance must
                        // drain them too, including a queued job that has not resumed its consumer.
                        val waiting = pending.toList()
                        pending.clear()
                        waiting.forEach(::admit)
                    }
                }
            }
        }
    }

    companion object { val shared = MaintenanceGate() }
}
