package com.ubers.observador

import com.ubers.observador.domain.backup.*
import com.ubers.observador.domain.route.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class CompleteDataTest {
    private val password = "Prueba-completa-123".toCharArray()
    private fun encrypted(data: ByteArray): ByteArray = ByteArrayOutputStream().also {
        EncryptedBackup.encrypt(data.inputStream(), it, password)
    }.toByteArray()

    @Test fun encryptedBackupPreservesEveryByteAndUsesDifferentSalt() {
        val original = ByteArray(150_000) { (it % 251).toByte() }
        val first = encrypted(original)
        assertFalse(first.contentEquals(encrypted(original)))
        val result = ByteArrayOutputStream()
        EncryptedBackup.decrypt(first.inputStream(), result, password)
        assertArrayEquals(original, result.toByteArray())
    }

    @Test fun wrongPasswordAndTamperedArchiveAreRejected() {
        val bytes = encrypted("nombres, coordenadas, evidencia".toByteArray())
        assertTrue(runCatching { EncryptedBackup.decrypt(bytes.inputStream(), ByteArrayOutputStream(), "incorrecta".toCharArray()) }.isFailure)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { EncryptedBackup.decrypt(bytes.inputStream(), ByteArrayOutputStream(), password) }.isFailure)
    }

    @Test fun truncatedEnvelopeIsRejected() {
        val bytes = encrypted(ByteArray(20))
        for (length in listOf(0, 8, 30, bytes.size - 1)) {
            assertTrue(runCatching { EncryptedBackup.decrypt(bytes.copyOf(length).inputStream(), ByteArrayOutputStream(), password) }.isFailure)
        }
    }

    @Test fun allPreferencesTravelButVolatileStateIsNotRestoredAsConfiguration() {
        val input = mapOf("export_history" to "historial", "distance_manual" to true, "route_enabled" to true)
        val values = BackupFormat.selectAllSettings(input)
        assertEquals(input, BackupFormat.decodeSettings(BackupFormat.encodeSettings(values))!!.values)
        assertFalse(BackupFormat.selectSettings(values).containsKey("distance_manual"))
    }

    @Test fun defaultRoutePolicyPreservesCollinearPointsAndAllowsLongTrips() {
        val points = (0..10).map { RoutePoint.of(it * 5000L, 18.0 + it * 0.001, -69.0, 4f, RoutePhase.PASSENGER) }
        assertEquals(points, RouteSimplifier.simplify(points, RoutePolicy().toleranceM))
        assertTrue(RoutePolicy().maxTripMs > 24 * 3_600_000L)
        assertTrue(RouteSimplifier.simplify(points, 5.0).size < points.size)
    }

    @Test fun mapDoesNotBridgeSignalGapsButKeepsContinuousLongStraightLines() {
        val continuous = (0..30).map { RoutePoint.of(it * 5000L, 18.0 + it * 0.001, -69.0, 4f, RoutePhase.PASSENGER) }
        assertEquals(1, RouteGeometry.displayRuns(continuous).size)
        val afterGap = RoutePoint.of(300_000, 18.04, -69.0, 4f, RoutePhase.PASSENGER)
        val runs = RouteGeometry.displayRuns(continuous + afterGap)
        assertEquals(2, runs.size)
        assertEquals(listOf(afterGap), runs.last())
        assertEquals(continuous.first(), runs.first().first())
        assertEquals(continuous.last(), runs.first().last())
    }
}
