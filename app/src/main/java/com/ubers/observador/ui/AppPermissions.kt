package com.ubers.observador.ui

import android.content.Context
import com.ubers.observador.service.ObservadorForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Permissions the screens show, refreshed when the app comes to the front and after a request.
 * The services check the system themselves; this is only for what the driver sees.
 */
object AppPermissions {

    private val _location = MutableStateFlow(false)

    /** Precise location (Fase 8E). */
    val location: StateFlow<Boolean> = _location.asStateFlow()

    fun refresh(context: Context) {
        _location.value = ObservadorForegroundService.hasLocationPermission(context)
    }
}
