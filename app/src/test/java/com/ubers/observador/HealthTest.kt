package com.ubers.observador

import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.backup.MaintenanceGate
import com.ubers.observador.domain.health.HealthArea
import com.ubers.observador.domain.health.HealthExport
import com.ubers.observador.domain.health.HealthLevel
import com.ubers.observador.domain.health.HealthRules
import com.ubers.observador.domain.health.HealthSignals
import com.ubers.observador.domain.health.IncidentLog
import com.ubers.observador.domain.health.IncidentTracker
import com.ubers.observador.domain.health.QueueHealth
import com.ubers.observador.service.PersistenceQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 1.39.0: every simulated fault changes what the driver sees, a lasting fault is recorded once,
 * and no indicator claims «working» from a permission alone.
 */
class HealthTest {

    private val now = 10_000_000L
    private val min = 60_000L

    /** Connected, reading recognised screens a minute ago, everything saving. */
    private val healthy = HealthSignals(
        observerOn = true, accessibilityEnabled = true, serviceConnectedAt = now - 30 * min,
        lastUberEventAt = now - min, lastReadingAt = now - min, lastRecognizedAt = now - min,
        lastOfferAt = now - 5 * min, locationPermitted = true, gpsRequestedAt = now - 10 * min, lastFixAt = now - 10_000,
        queue = QueueHealth(lastWriteAt = now - min)
    )

    private fun level(s: HealthSignals, area: HealthArea) = HealthRules.evaluate(s, now).of(area)!!.level

    @Test fun aHealthyPhoneIsAllOk() {
        val report = HealthRules.evaluate(healthy, now)
        assertEquals(HealthLevel.OK, report.overall)
        assertEquals(null, report.worst)
    }

    @Test fun thePermissionAloneIsNeverReportedAsWorking() {
        val notConnected = healthy.copy(serviceConnectedAt = null)
        val report = HealthRules.evaluate(notConnected, now)
        assertEquals(HealthLevel.FAIL, report.of(HealthArea.ACCESSIBILITY)!!.level)
        assertEquals("Permiso activo, servicio desconectado", report.worst!!.title)
        // Without a connected service nothing downstream says it works
        assertEquals(HealthLevel.OFF, report.of(HealthArea.READING)!!.level)
        assertEquals(HealthLevel.OFF, report.of(HealthArea.OFFERS)!!.level)
        assertEquals(HealthLevel.FAIL, level(healthy.copy(accessibilityEnabled = false), HealthArea.ACCESSIBILITY))
        // Right after the app starts Android is still connecting the service: no false incident
        val starting = notConnected.copy(watchingSince = now - 2_000)
        assertEquals(HealthLevel.INFO, level(starting, HealthArea.ACCESSIBILITY))
        assertTrue(IncidentTracker().update(HealthRules.evaluate(starting, now)).isEmpty())
        assertEquals(HealthLevel.FAIL, level(starting.copy(watchingSince = now - 31_000), HealthArea.ACCESSIBILITY))
        // Paused on purpose is not a fault
        assertEquals(HealthLevel.OFF, level(healthy.copy(observerOn = false), HealthArea.ACCESSIBILITY))
        assertFalse(HealthRules.evaluate(healthy.copy(observerOn = false, gpsRequestedAt = null), now).overall.isProblem)
    }

    @Test fun noOffersIsNotTheSameAsBrokenReading() {
        val quiet = healthy.copy(lastOfferAt = null)
        val q = HealthRules.evaluate(quiet, now)
        assertEquals(HealthLevel.OK, q.of(HealthArea.READING)!!.level)
        assertEquals("No han llegado ofertas", q.of(HealthArea.OFFERS)!!.title)
        assertFalse(q.overall.isProblem)

        val broken = healthy.copy(lastRecognizedAt = now - 40 * min, unrecognizedRun = 12, unrecognizedSince = now - 30 * min, lastOfferAt = null)
        val b = HealthRules.evaluate(broken, now)
        assertEquals(HealthLevel.WARN, b.of(HealthArea.READING)!!.level)
        assertNotEquals("No han llegado ofertas", b.of(HealthArea.OFFERS)!!.title)

        // A short run of unknown screens (a menu) is not yet a broken reading
        assertEquals(HealthLevel.OK, level(broken.copy(unrecognizedSince = now - 2 * min), HealthArea.READING))
        assertEquals(HealthLevel.OK, level(broken.copy(unrecognizedRun = 3), HealthArea.READING))
        // Uber closed since connecting: waiting, not failing
        val waiting = healthy.copy(lastUberEventAt = null, lastReadingAt = null, lastRecognizedAt = null, lastOfferAt = null)
        assertEquals(HealthLevel.INFO, level(waiting, HealthArea.READING))
    }

    @Test fun anUnreadableOfferIsReportedUntilAGoodOneArrives() {
        val s = healthy.copy(lastUnreadableAt = now - min, lastUnreadableReason = "Hay varios precios posibles")
        val check = HealthRules.evaluate(s, now).of(HealthArea.OFFERS)!!
        assertEquals(HealthLevel.WARN, check.level)
        assertTrue(check.detail.contains("Hay varios precios posibles"))
        assertEquals(HealthLevel.OK, level(s.copy(lastOfferAt = now - 10_000), HealthArea.OFFERS))
        assertEquals(HealthLevel.OK, level(s.copy(lastUnreadableAt = now - 40 * min, lastOfferAt = now - 50 * min), HealthArea.OFFERS))
    }

    @Test fun gpsAuthorisedIsNotGpsDelivering() {
        val asked = healthy.copy(gpsRequestedAt = now - 30_000, lastFixAt = null)
        assertEquals(HealthLevel.INFO, level(asked, HealthArea.GPS))
        val silent = healthy.copy(gpsRequestedAt = now - 5 * min, lastFixAt = null)
        val check = HealthRules.evaluate(silent, now).of(HealthArea.GPS)!!
        assertEquals(HealthLevel.FAIL, check.level)
        assertEquals("GPS autorizado, pero sin posiciones", check.title)
        // A fix from before this request does not count
        assertEquals(HealthLevel.FAIL, level(silent.copy(lastFixAt = now - 6 * min), HealthArea.GPS))
        assertEquals(HealthLevel.WARN, level(healthy.copy(lastFixAt = now - 3 * min), HealthArea.GPS))
        assertEquals(HealthLevel.WARN, level(healthy.copy(gpsBackgroundLimited = true), HealthArea.GPS))
        assertTrue(HealthRules.evaluate(healthy.copy(gpsBackgroundLimited = true), now).of(HealthArea.GPS)!!.action!!.contains("Abre Observador"))
        assertEquals(HealthLevel.FAIL, level(healthy.copy(locationPermitted = false), HealthArea.GPS))
        // Not measuring: resting, whatever the permission
        assertEquals(HealthLevel.OFF, level(healthy.copy(gpsRequestedAt = null), HealthArea.GPS))
        assertEquals(HealthLevel.OFF, level(healthy.copy(gpsRequestedAt = null, locationPermitted = false), HealthArea.GPS))
    }

    @Test fun writeFaultsAreVisible() {
        assertEquals(HealthLevel.FAIL, level(healthy.copy(queue = QueueHealth(alive = false)), HealthArea.WRITES))
        assertEquals(HealthLevel.WARN, level(healthy.copy(queue = QueueHealth(pending = 3, oldestPendingAt = now - 2 * min)), HealthArea.WRITES))
        assertEquals(HealthLevel.WARN, level(healthy.copy(queue = QueueHealth(pending = 60, oldestPendingAt = now - 1_000)), HealthArea.WRITES))
        val failed = healthy.copy(queue = QueueHealth(failures = 1, lastFailureAt = now - min, lastFailure = "IllegalStateException", lastWriteAt = now))
        val check = HealthRules.evaluate(failed, now).of(HealthArea.WRITES)!!
        assertEquals(HealthLevel.WARN, check.level)
        assertTrue(check.detail.contains("IllegalStateException"))
        // An old failure no longer alarms
        assertEquals(HealthLevel.OK, level(failed.copy(queue = failed.queue.copy(lastFailureAt = now - 2 * 60 * min)), HealthArea.WRITES))
    }

    @Test fun aLastingProblemIsRecordedOnceAndItsRecoveryOnce() {
        val tracker = IncidentTracker()
        assertTrue(tracker.update(HealthRules.evaluate(healthy, now)).isEmpty())
        val broken = healthy.copy(queue = QueueHealth(alive = false))
        val first = tracker.update(HealthRules.evaluate(broken, now))
        assertEquals(listOf(HealthArea.WRITES), first.map { it.area })
        // The same fault seen again (every 15 s in the app) adds nothing
        repeat(10) { assertTrue(tracker.update(HealthRules.evaluate(broken, now)).isEmpty()) }
        val back = tracker.update(HealthRules.evaluate(healthy, now))
        assertEquals(1, back.size)
        assertTrue(back.single().resolved)
        assertTrue(back.single().title.startsWith("Resuelto: "))
    }

    @Test fun theIncidentHistoryIsBoundedAndSurvivesDamage() {
        val tracker = IncidentTracker()
        var log = emptyList<com.ubers.observador.domain.health.HealthIncident>()
        repeat(40) { i ->
            log = IncidentLog.add(log, tracker.update(HealthRules.evaluate(healthy.copy(serviceConnectedAt = null), now + i * 2 * min)))
            log = IncidentLog.add(log, tracker.update(HealthRules.evaluate(healthy, now + i * 2 * min + min)))
        }
        assertEquals(IncidentLog.MAX, log.size)
        assertTrue(log.zipWithNext().all { (a, b) -> a.atMs >= b.atMs })
        val text = IncidentLog.encode(log)
        assertEquals(log, IncidentLog.decode(text))
        assertEquals(1, IncidentLog.decode("roto\n123\tWRITES\tWARN\tbien\n1\tNADA\tWARN\tx").size)
        assertTrue(IncidentLog.decode(null).isEmpty())
    }

    @Test fun theDiagnosisCarriesVersionsAndStatesOnly() {
        val report = HealthRules.evaluate(healthy.copy(serviceConnectedAt = null), now)
        val text = HealthExport.text(report, emptyList(), listOf("Observador" to "1.39.0 (50)"), ZoneId.of("UTC"))
        assertTrue(text.contains("Observador: 1.39.0 (50)"))
        assertTrue(text.contains("[FAIL] Accesibilidad · Permiso activo, servicio desconectado"))
        assertTrue(text.contains("Incidentes recientes (0)"))
    }

    @Test fun oneRuleForTestRows() {
        val ids = listOf("TEST-1", "a", "TEST-x", "b")
        assertEquals(listOf("a", "b"), TestData.filter(ids, includeTest = false) { it })
        assertEquals(ids, TestData.filter(ids, includeTest = true) { it })
        assertTrue(TestData.isTest("TEST-abc"))
        assertFalse(TestData.isTest("test-abc"))
    }

    @Test fun theQueueCountsFailuresAndKeepsSaving() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var clock = 1_000L
        val queue = PersistenceQueue(scope, MaintenanceGate(), now = { clock })
        try {
            val gate = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Unit>()
            assertTrue(queue.enqueue { gate.await() })
            assertTrue(queue.enqueue { throw IllegalStateException("simulated") })
            assertTrue(queue.enqueue { saved.complete(Unit) })
            assertEquals(3, queue.health.value.pending)
            assertEquals(1_000L, queue.health.value.oldestPendingAt)
            clock = 5_000L
            gate.complete(Unit)
            saved.await()
            val h = withTimeout(2_000) { queue.health.first { it.pending == 0 && it.lastWriteAt == 5_000L } }
            assertEquals(1, h.failures)
            assertEquals("IllegalStateException", h.lastFailure)
            assertEquals(null, h.oldestPendingAt)
            assertTrue(h.alive)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        assertFalse(queue.health.value.alive)
        assertFalse(queue.enqueue { })
        assertEquals(0, queue.health.value.pending)
    }
}
