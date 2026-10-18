package com.ubers.observador

import com.ubers.observador.domain.backup.ResetRules
import com.ubers.observador.domain.backup.ResetStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.33.0: «Borrar todos los datos». The erase itself (Room, files, restart) is checked on the phone. */
class ResetRulesTest {

    @Test fun onlyTheWordConfirms() {
        assertTrue(ResetRules.isConfirmed("BORRAR"))
        assertTrue(ResetRules.isConfirmed("  borrar "))
        assertTrue(ResetRules.isConfirmed("Borrar"))
        assertFalse(ResetRules.isConfirmed(""))
        assertFalse(ResetRules.isConfirmed("BORRA"))
        assertFalse(ResetRules.isConfirmed("BORRAR TODO"))
        assertFalse(ResetRules.isConfirmed("borrar\nahora"))
    }

    @Test fun warningNamesWhatDisappearsAndKeepsAccessibility() {
        val t = ResetRules.warningText(mapOf("detected_offers" to 1284, "trips" to 96, "expense_entries" to 12))
        assertTrue(t.contains("1,284 ofertas, 96 viajes, 12 gastos"))
        assertTrue(t.contains("No se puede deshacer"))
        assertTrue(t.contains("accesibilidad no se toca"))
    }

    @Test fun erasedTextStartsWithTheMarkerTheNoticeUses() {
        val t = ResetRules.erasedText(mapOf("detected_offers" to 2, "trips" to 1))
        assertTrue(t.startsWith(ResetRules.ERASED_PREFIX))
        assertEquals(ResetRules.NoticeKind.ERASED, ResetRules.noticeKind(t))
        assertEquals("Datos borrados", ResetRules.noticeTitle(t))
        assertTrue(ResetRules.noticeBody(t).startsWith("Se eliminaron 2 ofertas, 1 viaje, 0 gastos."))
    }

    @Test fun noticesAreToldApart() {
        assertEquals(ResetRules.NoticeKind.RESTORED, ResetRules.noticeKind("Respaldo restaurado: 1 oferta, 0 viajes, 0 gastos."))
        assertEquals(ResetRules.NoticeKind.RESTORE_FAILED, ResetRules.noticeKind("No se restauró; tus datos están como antes."))
        assertEquals(ResetRules.NoticeKind.ERASE_FAILED, ResetRules.noticeKind(ResetRules.ROLLED_BACK))
        assertEquals("No se borró", ResetRules.noticeTitle(ResetRules.ROLLED_BACK))
        assertTrue(ResetRules.noticeBody("Respaldo restaurado: 2 ofertas, 1 viaje, 0 gastos.").startsWith("Ahora tienes: 2 ofertas"))
    }

    @Test fun stepsPauseBeforeClosingAndDeleteBeforeResettingSettings() {
        val o = ResetStep.entries
        assertTrue(o.indexOf(ResetStep.PAUSE_READING) < o.indexOf(ResetStep.DRAIN_WRITES))
        assertTrue(o.indexOf(ResetStep.DRAIN_WRITES) < o.indexOf(ResetStep.CLOSE_DATABASE))
        assertTrue(o.indexOf(ResetStep.CLOSE_DATABASE) < o.indexOf(ResetStep.DELETE_DATA))
        assertTrue(o.indexOf(ResetStep.DELETE_DATA) < o.indexOf(ResetStep.RESET_SETTINGS))
        assertEquals(ResetStep.RESTART, o.last())
        assertTrue(ResetStep.SHOWN.all { it in o })
    }
}
