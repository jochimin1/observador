package com.ubers.observador.service

import com.ubers.observador.domain.lifecycle.OfferParseStep
import com.ubers.observador.domain.lifecycle.OfferPlaces
import com.ubers.observador.domain.lifecycle.ScreenDump
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the offer reader did since the service started (memory only), for the debug panel. */
data class OfferDebugState(
    /** Screens with an "Aceptar" button. */
    val offerScreens: Int = 0,
    val parsed: Int = 0,
    val failures: Map<OfferParseStep, Int> = emptyMap(),
    /** Offers found in an Uber window that was not the active one. */
    val fromOtherWindow: Int = 0,
    val overlaysShown: Int = 0,
    val overlaysSkippedBySetting: Int = 0,
    val notifications: Int = 0,
    val lastStep: OfferParseStep? = null,
    val lastAt: Long? = null,
    val lastPlaces: OfferPlaces? = null,
    val lastRider: String? = null,
    val lastRiderAt: Long? = null
)

/**
 * Fase 8F diagnostics: why a card was or was not shown, and the last offer and pickup screens
 * kept in memory for "Copiar lectura completa" (they contain names and addresses: never logged,
 * never written to disk by Observador).
 */
object OfferDebug {

    private val _state = MutableStateFlow(OfferDebugState())
    val state: StateFlow<OfferDebugState> = _state.asStateFlow()

    @Volatile private var lastOffer: ScreenSnapshot? = null
    @Volatile private var lastRider: ScreenSnapshot? = null

    @Synchronized
    fun onOfferScreen(snapshot: ScreenSnapshot, step: OfferParseStep, places: OfferPlaces?, otherWindow: Boolean) {
        lastOffer = snapshot
        val s = _state.value
        _state.value = s.copy(
            offerScreens = s.offerScreens + 1,
            parsed = s.parsed + if (step == OfferParseStep.OK) 1 else 0,
            failures = if (step == OfferParseStep.OK) s.failures else s.failures + (step to ((s.failures[step] ?: 0) + 1)),
            fromOtherWindow = s.fromOtherWindow + if (otherWindow) 1 else 0,
            lastStep = step,
            lastAt = snapshot.timestampMs,
            lastPlaces = places ?: s.lastPlaces
        )
    }

    @Synchronized
    fun onRiderScreen(snapshot: ScreenSnapshot, name: String) {
        lastRider = snapshot
        _state.value = _state.value.copy(lastRider = name, lastRiderAt = snapshot.timestampMs)
    }

    @Synchronized
    fun onOverlay(shown: Boolean) {
        val s = _state.value
        _state.value = if (shown) s.copy(overlaysShown = s.overlaysShown + 1) else s.copy(overlaysSkippedBySetting = s.overlaysSkippedBySetting + 1)
    }

    @Synchronized
    fun onNotified() {
        _state.value = _state.value.copy(notifications = _state.value.notifications + 1)
    }

    fun fullOffer(): String? = lastOffer?.let { ScreenDump.full(it, "última pantalla de oferta") }

    fun fullRider(): String? = lastRider?.let { ScreenDump.full(it, "última pantalla de recogida") }
}
