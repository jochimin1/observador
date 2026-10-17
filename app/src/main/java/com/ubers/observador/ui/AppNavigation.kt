package com.ubers.observador.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where a notification asked the app to go (Fase 8D: the weekly summary opens Ganancias on the
 * week that ended). The bottom bar switches tab; the screen consumes the request.
 */
object AppNavigation {
    const val EXTRA_OPEN = "com.ubers.observador.extra.OPEN"
    const val OPEN_LAST_WEEK = "earnings_last_week"

    val pending = MutableStateFlow<String?>(null)

    /** Fase 8L: «Cerrar Observador» was chosen (in the app or the notification): the screen closes. */
    val closeRequested = MutableStateFlow(false)
}
