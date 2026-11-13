package com.ubers.observador

import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.service.PersistenceQueue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MaintenanceGateTest {
    private suspend fun closed(gate: MaintenanceGate) = withTimeout(2_000) { while (!gate.isClosed) yield() }

    @Test fun admittedWorkFinishesBeforeExclusiveAndNewWritesWait() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val exclusiveEntered = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()
        val values = mutableListOf<String>()
        val first = launch { gate.access { entered.complete(Unit); finish.await(); values += "old" } }
        entered.await()
        val owner = launch { gate.exclusive { values += "snapshot"; exclusiveEntered.complete(Unit); releaseExclusive.await() } }
        closed(gate)
        val next = launch { gate.access { values += "new" } }
        yield()
        assertTrue(values.isEmpty())
        finish.complete(Unit)
        exclusiveEntered.await()
        assertEquals(listOf("old", "snapshot"), values)
        releaseExclusive.complete(Unit)
        joinAll(first, owner, next)
        assertEquals(listOf("old", "snapshot", "new"), values)
    }

    @Test fun aTimeoutDoesNotRunMaintenanceOrLosePendingWork() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        val queued = gate.reserve()
        var touched = false
        try { gate.exclusive(timeoutMs = 30) { touched = true }; fail("must time out") }
        catch (_: TimeoutCancellationException) { }
        assertFalse(touched)
        assertFalse(gate.isClosed)
        gate.runReserved(queued) { touched = true }
        assertTrue(touched)
        gate.exclusive { }
    }

    @Test fun nestedRepositoryCallsCanFinishAfterTheBarrierCloses() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        val ticket = gate.reserve()
        val owner = async { gate.exclusive { "done" } }
        closed(gate)
        withTimeout(2_000) { gate.runReserved(ticket) { withContext(Dispatchers.Default) { gate.access { assertTrue(gate.isClosed) } } } }
        assertEquals("done", owner.await())
    }

    @Test fun consecutiveMaintenanceDrainsPromotedReservations() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        val entered = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val first = launch { gate.exclusive { entered.complete(Unit); done.await() } }
        entered.await()
        val waiting = gate.reserve()
        done.complete(Unit)
        first.join()
        var secondEntered = false
        val second = launch { gate.exclusive { secondEntered = true } }
        closed(gate)
        assertFalse(secondEntered)
        gate.runReserved(waiting) { }
        withTimeout(2_000) { second.join() }
        assertTrue(secondEntered)
    }

    @Test fun cancellationReopensTheBarrierAndUnregistersWaiters() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        val entered = CompletableDeferred<Unit>()
        val owner = launch { gate.exclusive { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val pending = launch { gate.access { fail("cancelled work must not run") } }
        yield()
        pending.cancelAndJoin()
        owner.cancelAndJoin()
        assertFalse(gate.isClosed)
        withTimeout(2_000) { gate.exclusive { } }
    }

    @Test fun closingRoomRetainsTheBarrierUntilProcessRestart() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        gate.exclusive { session -> session.requireRestart(); gate.access { } }
        assertTrue(gate.isClosed)
        assertNull(gate.tryAccess { "must not write" })
        val blocked = launch { gate.access { fail("old references must not run") } }
        yield()
        assertTrue(blocked.isActive)
        blocked.cancelAndJoin()
        try { gate.exclusive { }; fail("must require restart") } catch (_: IllegalStateException) { }
    }

    @Test fun nestedMaintenanceUsesTheSameOwner() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        gate.exclusive { first ->
            withContext(Dispatchers.Default) { gate.exclusive { assertSame(first, it) } }
            assertEquals("allowed", gate.tryAccess { "allowed" })
        }
        assertEquals("open", gate.tryAccess { gate.tryAccess { "open" } })
    }

    @Test fun theQueueReservesWorkAtSubmissionAndSurvivesAFailedJob() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val queue = PersistenceQueue(scope, gate) { errors.incrementAndGet() }
        try {
            assertTrue(queue.enqueue { started.complete(Unit); unblock.await() })
            started.await()
            assertTrue(queue.enqueue { error("injected write failure") })
            assertTrue(queue.enqueue { gate.access { persisted.complete(Unit) } })
            val owner = async { gate.exclusive { assertTrue(persisted.isCompleted) } }
            closed(gate)
            unblock.complete(Unit)
            withTimeout(2_000) { owner.await() }
            assertEquals(1, errors.get())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        withTimeout(2_000) { gate.exclusive { } }
    }

    @Test fun cancellingAQueueReleasesItsUnexecutedReservations() = runBlocking<Unit> {
        val gate = MaintenanceGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val queue = PersistenceQueue(scope, gate)
        queue.enqueue { started.complete(Unit); awaitCancellation() }
        started.await()
        repeat(20) { queue.enqueue { fail("must remain unexecuted") } }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        withTimeout(2_000) { gate.exclusive { } }
    }
}
