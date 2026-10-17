package com.ubers.observador.domain

/**
 * Fase 8L: «Apagar Observador» (the power icon in Inicio). Closing is a stronger pause: nothing is read from Uber, the GPS
 * and the service notification go away and the app leaves the screen. Opening the app again
 * turns reading back on by itself, so a closed app never stays paused by mistake.
 */
object AppClose {

    const val TITLE = "¿Apagar Observador?"

    const val BASE = "Observador deja de leer Uber, apaga el GPS, quita su notificación y se cierra. " +
        "Al abrirla de nuevo vuelve a leer sola."

    const val RIDE = "Tienes un viaje en curso: si cierras ahora, Observador no verá cómo termina y " +
        "quedará sin cierre confirmado."

    const val MEASURING = "Los km medidos hoy quedan guardados hasta este momento."

    /** What the confirmation says, from what closing now would cut short. */
    fun warning(rideActive: Boolean, measuring: Boolean): List<String> = listOfNotNull(
        RIDE.takeIf { rideActive },
        BASE,
        MEASURING.takeIf { measuring && !rideActive }
    )

    /** The flag written when closing: the next opening resumes reading and clears it. */
    data class Flags(val observerEnabled: Boolean, val closedByUser: Boolean)

    fun onClose(): Flags = Flags(observerEnabled = false, closedByUser = true)

    /** On opening: resume only if the driver closed it with the button; a pause chosen in Ajustes stays. */
    fun onOpen(current: Flags): Flags = if (current.closedByUser) Flags(observerEnabled = true, closedByUser = false) else current
}
