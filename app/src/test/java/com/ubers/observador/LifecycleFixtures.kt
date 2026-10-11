package com.ubers.observador

import com.ubers.observador.domain.lifecycle.ReplayStep
import com.ubers.observador.domain.lifecycle.ScreenNode
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.UberScreenClassifier
import com.ubers.observador.domain.models.UberOffer

/**
 * Synthetic Uber Driver screens built in Kotlin. Every name, address and amount is made up.
 * They follow the candidate Spanish signatures, so they prove the machinery, not that Uber
 * really shows these words (that needs captures from a real trip).
 */
object LifecycleFixtures {

    const val UBER = "com.ubercab.driver"
    val classifier = UberScreenClassifier.spanish(UBER)

    /** The hash the service computes for the default offer card below. */
    val OFFER_HASH: String = UberOffer(136.06, 4.1, 7, 7.2, 11).offerHash()
    val OTHER_OFFER_HASH: String = UberOffer(98.50, 2.0, 5, 3.4, 9).offerHash()

    fun text(value: String, id: String? = null) =
        ScreenNode(text = value, viewId = id?.let { "$UBER:id/$it" }, className = "android.widget.TextView")

    fun button(value: String, id: String? = null) = ScreenNode(
        text = value,
        viewId = id?.let { "$UBER:id/$it" },
        className = "android.widget.Button",
        clickable = true
    )

    fun screen(vararg nodes: ScreenNode, pkg: String = UBER) = ScreenSnapshot(0L, pkg, nodes.toList())

    fun searching() = screen(text("Buscando viajes"), button("Desconectarte"))

    fun offer(price: String = "DOP136.06", pickup: String = "A 7 min (4.1 km)", trip: String = "Viaje: 11 min (7.2 km)") =
        screen(text(price), text(pickup), text(trip), text("Calle Ficticia 123"), button("Aceptar", "accept"))

    fun pickup(eta: String = "3 min") = screen(text("Recoger a Ana Ficticia"), text(eta), button("Llegué"))

    fun arrived() = screen(text("Esperando a Ana Ficticia"), button("Iniciar viaje"))

    fun inTrip(eta: String = "12 min") =
        screen(text("Dejar a Ana Ficticia"), text("Av. Imaginaria 45"), text(eta), button("Finalizar viaje"))

    fun summary() = screen(text("Viaje completado"), text("DOP136.06"), button("Califica a Ana Ficticia"))

    fun cancelled() = screen(text("Viaje cancelado"), button("Entendido"))

    fun unknown() = screen(text("Mapa"), button("Menú"))

    fun otherApp() = screen(text("Hola, ¿ya llegaste?"), pkg = "com.whatsapp")

    /** A full ride, one screen every 10 s, as a replay. */
    fun normalRide(start: Long = 1_000_000L): List<ReplayStep> = listOf(
        ReplayStep(start, searching()),
        ReplayStep(start + 10_000, offer(), OFFER_HASH),
        ReplayStep(start + 20_000, pickup()),
        ReplayStep(start + 30_000, arrived()),
        ReplayStep(start + 40_000, inTrip()),
        ReplayStep(start + 50_000, summary())
    )
}
