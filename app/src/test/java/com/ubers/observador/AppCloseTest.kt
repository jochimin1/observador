package com.ubers.observador

import com.ubers.observador.domain.AppClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 8L: «Cerrar Observador» and opening the app again. */
class AppCloseTest {

    @Test
    fun `closing pauses and marks it, opening resumes and clears the mark`() {
        val closed = AppClose.onClose()
        assertFalse(closed.observerEnabled)
        assertTrue(closed.closedByUser)
        assertEquals(AppClose.Flags(observerEnabled = true, closedByUser = false), AppClose.onOpen(closed))
    }

    @Test
    fun `a pause chosen in Ajustes is not undone by opening the app`() {
        val paused = AppClose.Flags(observerEnabled = false, closedByUser = false)
        assertEquals(paused, AppClose.onOpen(paused))
        val active = AppClose.Flags(observerEnabled = true, closedByUser = false)
        assertEquals(active, AppClose.onOpen(active))
    }

    @Test
    fun `the warning says what an open trip or measurement would lose`() {
        assertEquals(listOf(AppClose.BASE), AppClose.warning(rideActive = false, measuring = false))
        assertEquals(listOf(AppClose.BASE, AppClose.MEASURING), AppClose.warning(rideActive = false, measuring = true))
        // A trip under way goes first; the km note is then left out
        assertEquals(listOf(AppClose.RIDE, AppClose.BASE), AppClose.warning(rideActive = true, measuring = true))
    }
}
