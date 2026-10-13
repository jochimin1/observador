package com.ubers.observador.domain.online

import com.ubers.observador.domain.lifecycle.ClassifierPolicy
import com.ubers.observador.domain.lifecycle.ScreenSnapshot
import com.ubers.observador.domain.lifecycle.UberScreenType

enum class OnlineSignal { ONLINE, OFFLINE }

/** What a read showed, as codes: never screen text. */
enum class OnlineEvidence(val signal: OnlineSignal) {
    /** "Estás conectado", the bar below the map. */
    ONLINE_LABEL(OnlineSignal.ONLINE),
    /** "DESCONECTARSE", the red button of the raised bar. */
    GO_OFFLINE_BUTTON(OnlineSignal.ONLINE),
    /** An offer, pickup, waiting or in-trip screen of Fase 7A with medium or high confidence. */
    RIDE_SCREEN(OnlineSignal.ONLINE),
    /** "Estás desconectado", the grey bar below the map. */
    OFFLINE_LABEL(OnlineSignal.OFFLINE),
    /** "Conectarse", the blue button of the home screen. */
    GO_ONLINE_BUTTON(OnlineSignal.OFFLINE)
}

/**
 * One read. [signal] is null when the read says nothing, and also when it says both things
 * ([mixed]): during the animation of going offline both bars can be on screen, and such a read
 * does not count.
 */
data class OnlineReading(
    val signal: OnlineSignal?,
    val evidence: Set<OnlineEvidence> = emptySet(),
    val mixed: Boolean = false,
    /** A ride screen with high confidence confirms "online" at once, without the second read. */
    val immediate: Boolean = false
) {
    companion object {
        val NONE = OnlineReading(null)
    }
}

/**
 * The four labels, as seen on the driver's captures of 12/10. They are compared with a node's
 * whole text, normalized (no accents or capitals): "desconectarse" contains "conectarse" and
 * "estas desconectado" contains "conectado", so a substring match would read them backwards.
 */
object OnlineSignatures {
    const val ONLINE_LABEL = "estas conectado"
    const val GO_OFFLINE = "desconectarse"
    const val OFFLINE_LABEL = "estas desconectado"
    const val GO_ONLINE = "conectarse"

    /** Screens of Fase 7A that only exist while connected. */
    val RIDE_SCREENS = setOf(
        UberScreenType.OFFER,
        UberScreenType.PICKUP_NAVIGATION,
        UberScreenType.ARRIVED_OR_WAITING,
        UberScreenType.IN_TRIP
    )

    private val BY_LABEL = mapOf(
        ONLINE_LABEL to OnlineEvidence.ONLINE_LABEL,
        GO_OFFLINE to OnlineEvidence.GO_OFFLINE_BUTTON,
        OFFLINE_LABEL to OnlineEvidence.OFFLINE_LABEL,
        GO_ONLINE to OnlineEvidence.GO_ONLINE_BUTTON
    )

    /**
     * The evidence a normalized node text gives, or null. Only symbols around the words are
     * ignored ("Estás conectado." or "Conectarse ›"); a single extra word means no match.
     */
    fun evidenceOf(normalized: String): OnlineEvidence? = BY_LABEL[core(normalized)]

    /** The text without the symbols and spaces around it. */
    fun core(normalized: String): String = normalized.trim { !it.isLetterOrDigit() }
}

/**
 * Reads "connected or not" from the snapshot Fase 7A already built: the tree is never walked
 * again. Pure, so every rule runs in JVM tests.
 */
class OnlineStateDetector(private val targetPackage: String) {

    fun read(snapshot: ScreenSnapshot, screen: UberScreenType?, confidence: Double): OnlineReading {
        if (snapshot.packageName != targetPackage) return OnlineReading.NONE
        val evidence = LinkedHashSet<OnlineEvidence>()
        snapshot.normalizedTexts.forEach { text -> OnlineSignatures.evidenceOf(text)?.let { evidence += it } }
        val rideScreen = screen in OnlineSignatures.RIDE_SCREENS && confidence >= ClassifierPolicy.MEDIUM_FROM
        if (rideScreen) evidence += OnlineEvidence.RIDE_SCREEN
        val online = evidence.any { it.signal == OnlineSignal.ONLINE }
        val offline = evidence.any { it.signal == OnlineSignal.OFFLINE }
        return when {
            online && offline -> OnlineReading(null, evidence, mixed = true)
            online -> OnlineReading(
                OnlineSignal.ONLINE,
                evidence,
                immediate = rideScreen && confidence >= ClassifierPolicy.HIGH_FROM
            )
            offline -> OnlineReading(OnlineSignal.OFFLINE, evidence)
            else -> OnlineReading.NONE
        }
    }
}
