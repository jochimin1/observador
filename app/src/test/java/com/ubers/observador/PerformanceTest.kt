package com.ubers.observador

import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.health.TimingStats
import com.ubers.observador.service.PersistenceQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PerformanceTest {
    @Test fun timingsKeepExactAggregatesWithoutKeepingSamples() {
        val result = (1..100_000).fold(TimingStats()) { s, _ -> s.add(2_000_000) }
        assertEquals(100_000L, result.count)
        assertEquals(2.0, result.averageMs!!, 0.000001)
        assertEquals(2_000_000L, result.maxNanos)
        assertNull(TimingStats().averageMs)
        assertTrue(runCatching { result.add(-1) }.isFailure)
    }

    @Test fun burstPreservesOrderAndMeasuresWaitingEvenWithDuplicateWallTimes() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val nanos = AtomicLong()
        val queue = PersistenceQueue(scope, MaintenanceGate(), now = { 1L }, nanoTime = nanos::get)
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Int>()
        try {
            queue.enqueue { release.await(); nanos.addAndGet(10) }
            repeat(2_000) { i -> assertTrue(queue.enqueue { writes += i; nanos.incrementAndGet() }) }
            assertEquals(2_001, queue.health.value.pending)
            release.complete(Unit)
            withTimeout(15_000) { queue.health.first { it.pending == 0 && it.completed == 2_001L } }
            assertEquals((0 until 2_000).toList(), writes)
            assertEquals(2_001, queue.health.value.peakPending)
            assertTrue(queue.health.value.maxWaitNanos >= 2_000L)
            assertTrue(queue.health.value.maxRunNanos >= 10L)
            assertNull(queue.health.value.oldestPendingAt)
        } finally { parent.cancelAndJoin() }
        assertFalse(queue.enqueue {})
        assertEquals(1L, queue.health.value.rejected)
        assertEquals(0, queue.health.value.pending)
    }

    @Test fun failureIsCountedAndNextJobStillRuns() = runBlocking {
        val parent = SupervisorJob()
        val queue = PersistenceQueue(CoroutineScope(parent + Dispatchers.Default), MaintenanceGate())
        try {
            queue.enqueue { throw IllegalStateException() }
            queue.enqueue { }
            withTimeout(5_000) { queue.health.first { it.completed == 1L && it.pending == 0 } }
            assertEquals(1, queue.health.value.failures)
            assertTrue(queue.health.value.alive)
        } finally { parent.cancelAndJoin() }
    }
}
