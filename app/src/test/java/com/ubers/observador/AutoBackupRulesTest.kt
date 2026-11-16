package com.ubers.observador

import com.ubers.observador.domain.backup.AutoBackupRules
import com.ubers.observador.domain.backup.AutoBackupRules.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 1.56.0: automatic backup names, folder list and status line. */
class AutoBackupRulesTest {
    private val t0 = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun namesAndListKeepEveryBackupNewestFirst() {
        assertEquals("observador-respaldo-auto-2027-01-15-0800.zip", AutoBackupRules.fileName(t0, ZoneOffset.UTC))
        val files = listOf(Entry("observador-respaldo-2027-01-10-0900.zip", 10, t0 - 5 * day), Entry("observador-respaldo-auto-2027-01-15-0800.zip", 20, t0),
            Entry("observador-respaldo-2027-01-12-0900.zip.cifrado", 30, t0 - 3 * day), Entry("foto.jpg", 999, t0), Entry("observador-respaldo-auto-2027-01-15-0800-1.zip", 5, t0 - 1))
        val list = AutoBackupRules.list(files)
        assertEquals(4, list.size)
        assertTrue(list.first().automatic)
        assertTrue(list.any { it.encrypted })
        assertEquals(65L, AutoBackupRules.totalBytes(list))
        assertFalse(AutoBackupRules.isBackup("observador-respaldo-.zip"))
    }

    @Test fun statusSaysWhatHappened() {
        assertEquals("Todavía no se ha hecho ningún respaldo automático.", AutoBackupRules.status(null, null, null, t0))
        assertEquals("Último respaldo automático hace 3 días · próximo en 4 días aprox.", AutoBackupRules.status(t0 - 3 * day, null, null, t0))
        assertTrue(AutoBackupRules.status(t0 - 8 * day, "Carpeta sin permiso", t0 - day, t0).endsWith("Falló el último intento: Carpeta sin permiso"))
        // An error older than the last success is not repeated
        assertFalse(AutoBackupRules.status(t0 - day, "viejo", t0 - 2 * day, t0).contains("viejo"))
    }
}
