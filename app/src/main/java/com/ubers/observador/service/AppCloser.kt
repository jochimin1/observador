package com.ubers.observador.service

import android.content.Context
import com.ubers.observador.data.AppSettings
import com.ubers.observador.ui.AppNavigation

/**
 * Fase 8L: closes Observador from the app (Inicio, Ajustes) or from its notification. Reading
 * stops first (the accessibility service sees the pause and ends what was open, as a pause
 * does), then the service with its GPS and notification, then the screen.
 */
object AppCloser {

    suspend fun close(context: Context) {
        val app = context.applicationContext
        AppSettings(app).closeByUser()
        AppNavigation.closeRequested.value = true
        ObservadorForegroundService.stop(app)
    }
}
