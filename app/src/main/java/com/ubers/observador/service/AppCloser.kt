package com.ubers.observador.service

import android.content.Context
import com.ubers.observador.data.AppSettings
import com.ubers.observador.ui.AppNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fase 8L: closes Observador from the app (Inicio, Ajustes) or from its notification. Reading
 * stops first (the accessibility service sees the pause and ends what was open, as a pause
 * does), then the service with its GPS and notification, then the screen.
 */
object AppCloser {

    /**
     * Its own scope, alive for the whole process. The close used to run in the dialog's scope,
     * which Compose cancels as soon as the dialog goes away: the pause was never written and
     * nothing happened (fixed in 1.23.1).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Starts closing and returns at once; safe to call from a click or a dialog that is going away. */
    fun closeAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { close(app) }
    }

    suspend fun close(context: Context) {
        val app = context.applicationContext
        // Even if the setting cannot be written, the service and the screen still close
        try {
            AppSettings(app).closeByUser()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AppCloser", "Could not save the pause before closing", e)
        }
        AppNavigation.closeRequested.value = true
        ObservadorForegroundService.stop(app)
    }
}
