package com.ubers.observador.domain.sectors

/** Which address of the offer a sector is looked for in. */
enum class SectorWhere(val label: String) {
    PICKUP("Recogida"), DROPOFF("Destino"), BOTH("Ambos");

    fun covers(place: OfferPlaceKind): Boolean = this == BOTH || (this == PICKUP) == (place == OfferPlaceKind.PICKUP)
}

/**
 * How loud the warning is. [CLOSE] (v1.37.0) only closes the card in the «Automático» operating mode;
 * in «Observador» and «Asistido» it behaves as [AVOID].
 */
enum class SectorLevel(val label: String) { WARN("Aviso"), AVOID("Evitar"), CLOSE("Cerrar") }

enum class OfferPlaceKind(val label: String) { PICKUP("Recogida"), DROPOFF("Destino") }

/** A sector the driver wants to be warned about, written as Uber writes it («Los Alcarrizos»). */
data class CareSector(
    val name: String,
    val where: SectorWhere = SectorWhere.BOTH,
    val level: SectorLevel = SectorLevel.WARN
) {
    companion object {
        const val MAX_NAME = 40
        const val MAX_SECTORS = 50
    }
}

/** One sector found in one address of the card. */
data class SectorHit(val sector: CareSector, val place: OfferPlaceKind)

/** Every sector found on one card; empty when none. */
data class SectorMatch(val hits: List<SectorHit>) {
    val isEmpty: Boolean get() = hits.isEmpty()

    /** The loudest level among the hits, or null. */
    val level: SectorLevel? get() = hits.maxOfOrNull { it.sector.level }

    companion object {
        val NONE = SectorMatch(emptyList())
    }
}
