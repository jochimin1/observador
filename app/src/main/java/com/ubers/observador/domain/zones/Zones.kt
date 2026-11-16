package com.ubers.observador.domain.zones

import com.ubers.observador.domain.backup.MiniJson
import com.ubers.observador.domain.distance.Geo
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.sectors.SectorMatcher
import kotlin.math.floor

/**
 * 1.47.0: a square of the fixed grid, about 1.5 km a side in Santo Domingo. The cell comes from the
 * coordinate alone, so it never moves and needs nothing drawn. Rows go north, columns east.
 */
data class ZoneCell(val row: Int, val col: Int) {
    val id: String get() = "$row:$col"
    val south: Double get() = row * ZoneGrid.LAT_STEP
    val north: Double get() = (row + 1) * ZoneGrid.LAT_STEP
    val west: Double get() = col * ZoneGrid.LNG_STEP
    val east: Double get() = (col + 1) * ZoneGrid.LNG_STEP
    val centerLat: Double get() = (south + north) / 2
    val centerLng: Double get() = (west + east) / 2

    /** «18.480, -69.950»: where it is, for a cell without a name. */
    val label: String get() = String.format(java.util.Locale.US, "%.3f, %.3f", centerLat, centerLng)
}

object ZoneGrid {
    /** 1.5 km of latitude. */
    const val LAT_STEP = 0.0135
    /** 1.5 km of longitude at 18.5° N. */
    const val LNG_STEP = 0.0142

    fun cellOf(latitude: Double, longitude: Double) = ZoneCell(floor(latitude / LAT_STEP).toInt(), floor(longitude / LNG_STEP).toInt())

    fun cellOf(p: RoutePoint) = cellOf(p.latitude, p.longitude)

    fun parse(id: String): ZoneCell? {
        val parts = id.split(':')
        if (parts.size != 2) return null
        val r = parts[0].toIntOrNull() ?: return null
        val c = parts[1].toIntOrNull() ?: return null
        return ZoneCell(r, c)
    }
}

/** Where a trip picked up and dropped off, read from its recorded route (never from Uber's text). */
object ZonePoints {

    /**
     * The pickup: the first point with the passenger; without one, the last point on the way to the
     * pickup. A route whose phases were never told apart has no pickup point.
     */
    fun pickup(points: List<RoutePoint>): RoutePoint? =
        points.firstOrNull { it.phase == RoutePhase.PASSENGER } ?: points.lastOrNull { it.phase == RoutePhase.PICKUP }

    /** The drop-off: the last point, only when the route reached the passenger phase. */
    fun dropoff(points: List<RoutePoint>): RoutePoint? =
        if (points.any { it.phase == RoutePhase.PASSENGER }) points.last() else null
}

/** One trip as the zone figures need it. Money is only the confirmed total, in minor units. */
data class ZoneTrip(
    val tripId: String,
    /** Acceptance, or the first moment it was seen. */
    val startAt: Long,
    /** The end Ganancias counts; null for trips that did not end. */
    val endAt: Long?,
    val acceptedAt: Long?,
    val arrivedAt: Long?,
    val tripStartedAt: Long?,
    val pickup: RoutePoint?,
    val dropoff: RoutePoint?,
    /** Where the route began (where the trip was accepted). */
    val routeStart: RoutePoint?,
    val realMinor: Long?,
    /** Uber's pickup address, to suggest a name for a cell. */
    val pickupAddress: String?
)

/** The driver's names and private cells (DataStore `zone_names`, `zone_private`). */
data class ZoneSettings(val names: Map<String, String> = emptyMap(), val privateCells: Set<String> = emptySet()) {
    fun nameOf(cell: ZoneCell): String? = names[cell.id]
    fun isPrivate(cell: ZoneCell): Boolean = cell.id in privateCells

    companion object {
        const val MAX_NAMES = 200
        const val MAX_PRIVATE = 50
        const val MAX_NAME = 30
    }
}

object ZoneSettingsCodec {

    fun encodeNames(names: Map<String, String>): String = MiniJson.write(names.toSortedMap())

    fun encodePrivate(cells: Set<String>): String = MiniJson.write(cells.sorted())

    /** Strict like every restored setting: anything out of shape rejects the whole value (null). */
    fun decodeNames(text: String?): Map<String, String>? {
        if (text.isNullOrBlank()) return emptyMap()
        val m = MiniJson.parseOrNull(text) as? Map<*, *> ?: return null
        if (m.size > ZoneSettings.MAX_NAMES) return null
        val out = LinkedHashMap<String, String>()
        for ((k, v) in m) {
            val id = (k as? String)?.takeIf { ZoneGrid.parse(it) != null } ?: return null
            val name = (v as? String)?.let(::cleanName)?.takeIf { it.isNotEmpty() && it.length <= ZoneSettings.MAX_NAME } ?: return null
            out[id] = name
        }
        return out
    }

    fun decodePrivate(text: String?): Set<String>? {
        if (text.isNullOrBlank()) return emptySet()
        val l = MiniJson.parseOrNull(text) as? List<*> ?: return null
        if (l.size > ZoneSettings.MAX_PRIVATE) return null
        return l.map { (it as? String)?.takeIf { id -> ZoneGrid.parse(id) != null } ?: return null }.toSet()
    }

    fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(ZoneSettings.MAX_NAME)
}

/** Against the driver's own zones, by money per hour (the driver's choice, 2026-11-14). */
enum class ZoneRating { HIGH, MID, LOW, FEW }

/**
 * One zone: a named group of cells or a single unnamed cell. «Recogida» figures are of the trips
 * picked up here; «destino» figures are of what happened after a trip ended here.
 */
data class ZoneSummary(
    val key: String,
    val name: String?,
    val cells: List<ZoneCell>,
    val pickups: Int,
    /** Trips picked up here whose money is confirmed: the only ones in [perHour]. */
    val confirmed: Int,
    val realMinor: Long,
    /** Acceptance to end of the confirmed trips. */
    val paidMs: Long,
    val avgPickupMin: Double?,
    val avgWaitMin: Double?,
    val dropoffs: Int,
    /** After a drop-off here, how long until the next accepted trip (gaps of up to an hour). */
    val avgNextOfferMin: Double?,
    /** Straight line from the drop-off to where the next trip was accepted. */
    val avgEmptyKm: Double?,
    val nextOffers: Int,
    val lastAt: Long,
    /** Uber's most frequent sector text for pickups here, as a hint for naming the cell. */
    val uberSays: String?,
    val rating: ZoneRating
) {
    val perHour: Double? get() = if (paidMs > 0 && confirmed > 0) realMinor / 100.0 / (paidMs / 3_600_000.0) else null
    val enough: Boolean get() = confirmed >= ZoneStats.MIN_TRIPS
    val label: String get() = name ?: cells.first().label
}

data class ZoneReport(
    val zones: List<ZoneSummary>,
    /** Trips of the period with a usable pickup point. */
    val tripsWithGps: Int,
    /** Trips of the period without one (no route, or phases never told apart). */
    val tripsWithoutGps: Int,
    val hiddenPrivate: Int,
    val medianPerHour: Double?
)

object ZoneStats {

    const val MIN_TRIPS = 5
    const val NEXT_GAP_MAX_MS = 60 * 60_000L

    private fun keyOf(cell: ZoneCell, settings: ZoneSettings): String = settings.nameOf(cell)?.let { "n:" + SectorMatcher.words(it).joinToString(" ") } ?: "c:${cell.id}"

    /** Uber's sector for a pickup: the first part of the address after the street (or the only one). */
    private fun uberSector(address: String?): String? = SectorMatcher.displayParts(address).firstOrNull { !SectorMatcher.isStreet(it) }

    fun report(trips: List<ZoneTrip>, settings: ZoneSettings): ZoneReport {
        val sorted = trips.sortedBy { it.startAt }
        val cellsByKey = HashMap<String, MutableSet<ZoneCell>>()
        data class Acc(
            var pickups: Int = 0, var confirmed: Int = 0, var real: Long = 0, var paid: Long = 0,
            val pickupMin: MutableList<Double> = ArrayList(), val waitMin: MutableList<Double> = ArrayList(),
            var dropoffs: Int = 0, val nextMin: MutableList<Double> = ArrayList(), val emptyKm: MutableList<Double> = ArrayList(),
            var lastAt: Long = 0, val uber: HashMap<String, Pair<String, Int>> = HashMap()
        )
        val acc = LinkedHashMap<String, Acc>()
        val hiddenKeys = HashSet<String>()

        fun bucket(cell: ZoneCell): Acc? {
            if (settings.isPrivate(cell)) { hiddenKeys += cell.id; return null }
            val k = keyOf(cell, settings)
            cellsByKey.getOrPut(k) { LinkedHashSet() } += cell
            return acc.getOrPut(k) { Acc() }
        }

        var withGps = 0
        sorted.forEachIndexed { i, t ->
            t.pickup?.let { p ->
                withGps++
                bucket(ZoneGrid.cellOf(p))?.let { a ->
                    a.pickups++
                    a.lastAt = maxOf(a.lastAt, t.startAt)
                    val end = t.endAt
                    if (t.realMinor != null && end != null && end > t.startAt) {
                        a.confirmed++
                        a.real += t.realMinor
                        a.paid += end - (t.acceptedAt ?: t.startAt)
                    }
                    val accepted = t.acceptedAt
                    if (accepted != null && t.tripStartedAt != null && t.tripStartedAt >= accepted) {
                        a.pickupMin += (t.tripStartedAt - accepted) / 60_000.0
                    }
                    if (t.arrivedAt != null && t.tripStartedAt != null && t.tripStartedAt >= t.arrivedAt) {
                        a.waitMin += (t.tripStartedAt - t.arrivedAt) / 60_000.0
                    }
                    uberSector(t.pickupAddress)?.let { s ->
                        val key = SectorMatcher.words(s).joinToString(" ")
                        val prev = a.uber[key]
                        a.uber[key] = (prev?.first ?: s) to ((prev?.second ?: 0) + 1)
                    }
                }
            }
            val drop = t.dropoff
            val end = t.endAt
            if (drop != null && end != null) {
                bucket(ZoneGrid.cellOf(drop))?.let { a ->
                    a.dropoffs++
                    a.lastAt = maxOf(a.lastAt, end)
                    val next = sorted.getOrNull(i + 1)
                    if (next != null) {
                        val gap = next.startAt - end
                        if (gap in 0..NEXT_GAP_MAX_MS) {
                            a.nextMin += gap / 60_000.0
                            next.routeStart?.let { s -> a.emptyKm += Geo.distanceM(drop.latitude, drop.longitude, s.latitude, s.longitude) / 1000.0 }
                        }
                    }
                }
            }
        }
        val zones = acc.map { (k, a) ->
            val cells = cellsByKey[k].orEmpty().sortedWith(compareBy({ it.row }, { it.col }))
            ZoneSummary(
                key = k,
                name = cells.firstNotNullOfOrNull { settings.nameOf(it) },
                cells = cells,
                pickups = a.pickups,
                confirmed = a.confirmed,
                realMinor = a.real,
                paidMs = a.paid,
                avgPickupMin = a.pickupMin.takeIf { it.isNotEmpty() }?.average(),
                avgWaitMin = a.waitMin.takeIf { it.isNotEmpty() }?.average(),
                dropoffs = a.dropoffs,
                avgNextOfferMin = a.nextMin.takeIf { it.isNotEmpty() }?.average(),
                avgEmptyKm = a.emptyKm.takeIf { it.isNotEmpty() }?.average(),
                nextOffers = a.nextMin.size,
                lastAt = a.lastAt,
                uberSays = a.uber.values.maxByOrNull { it.second }?.first,
                rating = ZoneRating.FEW
            )
        }
        val median = median(zones.filter { it.enough }.mapNotNull { it.perHour })
        val rated = zones.map { z -> z.copy(rating = rate(z, median)) }
            .sortedWith(compareByDescending<ZoneSummary> { it.enough }.thenByDescending { it.perHour ?: -1.0 }.thenByDescending { it.pickups })
        return ZoneReport(rated, withGps, trips.size - withGps, hiddenKeys.size, median)
    }

    /** Against the driver's own zones: 15 % above or below their median. Few trips are never rated. */
    fun rate(z: ZoneSummary, median: Double?): ZoneRating {
        val v = z.perHour
        if (!z.enough || v == null || median == null || median <= 0) return ZoneRating.FEW
        return when {
            v >= median * 1.15 -> ZoneRating.HIGH
            v <= median * 0.85 -> ZoneRating.LOW
            else -> ZoneRating.MID
        }
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
