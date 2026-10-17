package com.ubers.observador.domain

/**
 * Fase 8I: which offers send a notification, on its own setting ("Avisos de ofertas"). Before,
 * only Asistido mode notified, and Observador (the default) never did. Asistido still always
 * notifies BUENO and EXCELENTE, whatever this says.
 */
enum class OfferAlerts(val storageKey: String, val label: String) {
    ALL("ALL", "Todas"),
    GOOD("GOOD", "Buenas y excelentes"),
    OFF("OFF", "Ninguna");

    companion object {
        /** Every offer notifies until the driver chooses otherwise (request of 17/10). */
        val DEFAULT = ALL

        fun fromStorage(key: String?): OfferAlerts = entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /** Only a newly recorded offer notifies, so a flicker or a repaint never alerts twice. */
        fun shouldNotify(newlySaved: Boolean, alerts: OfferAlerts, assisted: Boolean, quality: OfferQuality): Boolean = when {
            !newlySaved -> false
            alerts == ALL -> true
            quality == OfferQuality.BAD -> false
            else -> alerts == GOOD || assisted
        }
    }
}
