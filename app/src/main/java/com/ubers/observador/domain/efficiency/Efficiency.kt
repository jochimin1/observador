package com.ubers.observador.domain.efficiency

import com.ubers.observador.domain.distance.DistanceSegment
import com.ubers.observador.domain.online.OnlineMetrics
import com.ubers.observador.domain.online.OnlinePolicy
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.domain.online.TimeSpan
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.48.0, Horarios y eficiencia: the uses of reading time (connected to Uber and reading). Only reading time is split into uses, and the uses add up to it exactly: pauses are not told
 * apart (the driver's choice, 2026-11-14), so time without a trip is «buscando».
 */
enum class TimeUse(val label: String) {
    SEARCHING("Buscando"),
    PICKUP("Yendo a recoger"),
    WAITING("Esperando al pasajero"),
    PASSENGER("Con pasajero")
}

/** Fixed shifts (the driver's choice): clock hours, end excluded. */
enum class Shift(val label: String, val fromHour: Int, val toHour: Int) {
    DAWN("Madrugada", 0, 6),
    MORNING("Mañana", 6, 12),
    AFTERNOON("Tarde", 12, 18),
    NIGHT("Noche", 18, 24);

    val range: String get() = "%02d–%02d".format(fromHour, toHour)

    companion object {
        fun of(hour: Int): Shift = entries.first { hour >= it.fromHour && hour < it.toHour }
    }
}

/** One offer seen, with what the card offered (never money earned). */
data class EffOffer(val atMs: Long, val rdpPerHour: Double, val rdpPerKm: Double, val pickupKm: Double, val product: String?)

/** One trip with its known moments. [endAt] closes its phases (end, cancellation or interruption). */
data class EffTrip(
    val id: String,
    val acceptedAt: Long?,
    val arrivedAt: Long?,
    val tripStartedAt: Long?,
    val endAt: Long?,
    /** It counts as a done trip in Ganancias. */
    val counted: Boolean,
    /** Taken from a known offer: when it was seen, and what it offered per hour. */
    val offerSeenAt: Long?,
    val offeredRdpPerHour: Double?,
    val product: String?,
    /** Confirmed money only, minor units. */
    val realMinor: Long?
)

/** What one slot, weekday or shift adds up. */
data class EffAcc(
    val readingMs: Long = 0,
    val unreadMs: Long = 0,
    val offlineMs: Long = 0,
    val uses: Map<TimeUse, Long> = emptyMap(),
    val offers: Int = 0,
    val taken: Int = 0,
    val counted: Int = 0,
    val confirmed: Int = 0,
    val realMinor: Long = 0,
    val offerRdpH: List<Double> = emptyList(),
    val offerRdpKm: List<Double> = emptyList(),
    val offerPickupKm: List<Double> = emptyList(),
    val takenRdpH: List<Double> = emptyList()
) {
    val readingHours: Double get() = readingMs / 3_600_000.0
    val enoughTime: Boolean get() = readingMs >= Efficiency.MIN_READING_MS
    val enoughOffers: Boolean get() = offers >= Efficiency.MIN_OFFERS

    fun use(u: TimeUse): Long = uses[u] ?: 0L
    fun share(u: TimeUse): Double? = if (readingMs > 0) use(u).toDouble() / readingMs else null

    /** Offers per hour connected and reading; null with too little time. */
    val offersPerHour: Double? get() = if (enoughTime) offers / readingHours else null

    /** Taken / seen; null with too few offers. */
    val takeRate: Double? get() = if (enoughOffers) taken.toDouble() / offers else null

    /** Confirmed money per hour connected and reading; null with too little time. */
    val realPerHour: Double? get() = if (enoughTime) realMinor / 100.0 / readingHours else null

    /** Done trips whose money is not confirmed yet: [realPerHour] is a minimum. */
    val pending: Int get() = counted - confirmed

    val medianOfferRdpH: Double? get() = if (enoughOffers) Efficiency.median(offerRdpH) else null
    val medianOfferRdpKm: Double? get() = if (enoughOffers) Efficiency.median(offerRdpKm) else null
    val medianPickupKm: Double? get() = if (enoughOffers) Efficiency.median(offerPickupKm) else null
    val medianTakenRdpH: Double? get() = if (taken >= Efficiency.MIN_OFFERS) Efficiency.median(takenRdpH) else null

    operator fun plus(o: EffAcc) = EffAcc(
        readingMs + o.readingMs, unreadMs + o.unreadMs, offlineMs + o.offlineMs,
        (uses.keys + o.uses.keys).associateWith { use(it) + o.use(it) },
        offers + o.offers, taken + o.taken, counted + o.counted, confirmed + o.confirmed, realMinor + o.realMinor,
        offerRdpH + o.offerRdpH, offerRdpKm + o.offerRdpKm, offerPickupKm + o.offerPickupKm, takenRdpH + o.takenRdpH
    )
}

/** One modality: offers and trips only (connected time is not per modality). */
data class ProductEff(val product: String, val offers: Int, val taken: Int, val confirmed: Int, val realMinor: Long, val offerRdpH: List<Double>) {
    val takeRate: Double? get() = if (offers >= Efficiency.MIN_OFFERS) taken.toDouble() / offers else null
    val medianOfferRdpH: Double? get() = if (offers >= Efficiency.MIN_OFFERS) Efficiency.median(offerRdpH) else null
    /** Confirmed money per confirmed trip. */
    val realPerTrip: Double? get() = if (confirmed >= Efficiency.MIN_TRIPS) realMinor / 100.0 / confirmed else null
}

data class KmSplit(val passengerMm: Long = 0, val pickupMm: Long = 0, val emptyMm: Long = 0, val gapMm: Long = 0) {
    val measuredMm: Long get() = passengerMm + pickupMm + emptyMm
}

data class Suggestion(val text: String, val basis: String, val lostMinutes: Double)

data class EfficiencyReport(
    val window: TimeSpan,
    val total: EffAcc,
    /** Always 12, slot k = hours 2k to 2k+2. */
    val slots: List<EffAcc>,
    /** ISO day 1..7 → figures (clock days, not the 4:00 workday). */
    val weekdays: Map<Int, EffAcc>,
    val shifts: Map<Shift, EffAcc>,
    val products: List<ProductEff>,
    val km: KmSplit,
    val suggestions: List<Suggestion>
)

object Efficiency {

    const val SLOT_HOURS = 2
    const val MIN_READING_MS = 2 * 3_600_000L
    /** Comparisons and suggestions need more than a cell. */
    const val MIN_COMPARE_MS = 3 * 3_600_000L
    const val MIN_OFFERS = 5
    const val MIN_TRIPS = 3
    const val MAX_SUGGESTIONS = 3

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Sorted, non-overlapping spans → is [t] inside one of them (start included, end excluded). */
    private fun covers(spans: List<TimeSpan>, t: Long): Boolean {
        var lo = 0
        var hi = spans.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = spans[mid]
            when {
                t < s.startMs -> hi = mid - 1
                t >= s.endMs -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** The phases of one trip, each from its first known moment to the next. */
    fun phases(t: EffTrip, nowMs: Long): Map<TimeUse, TimeSpan> {
        val end = t.endAt ?: nowMs
        val out = HashMap<TimeUse, TimeSpan>()
        val pickupFrom = t.acceptedAt
        val pickupTo = t.arrivedAt ?: t.tripStartedAt ?: end
        if (pickupFrom != null && pickupTo > pickupFrom) out[TimeUse.PICKUP] = TimeSpan(pickupFrom, pickupTo)
        val waitTo = t.tripStartedAt ?: end
        if (t.arrivedAt != null && waitTo > t.arrivedAt) out[TimeUse.WAITING] = TimeSpan(t.arrivedAt, waitTo)
        if (t.tripStartedAt != null && end > t.tripStartedAt) out[TimeUse.PASSENGER] = TimeSpan(t.tripStartedAt, end)
        return out
    }

    /** The clock boundaries every [SLOT_HOURS] inside [window], in [zone]. */
    private fun slotBoundaries(window: TimeSpan, zone: ZoneId): List<Long> {
        val out = ArrayList<Long>()
        var day = Instant.ofEpochMilli(window.startMs).atZone(zone).toLocalDate()
        while (true) {
            val dayStart = day.atStartOfDay(zone)
            if (dayStart.toInstant().toEpochMilli() >= window.endMs) break
            for (h in 0 until 24 step SLOT_HOURS) {
                val b = dayStart.plusHours(h.toLong()).toInstant().toEpochMilli()
                if (b > window.startMs && b < window.endMs) out += b
            }
            day = day.plusDays(1)
        }
        return out
    }

    fun report(
        window: TimeSpan,
        zone: ZoneId,
        nowMs: Long,
        sessions: List<OnlineSession>,
        offers: List<EffOffer>,
        trips: List<EffTrip>,
        segments: List<DistanceSegment>,
        policy: OnlinePolicy = OnlinePolicy(),
        withSuggestions: Boolean = true
    ): EfficiencyReport {
        val reading = OnlineMetrics.union(sessions.flatMap { it.confirmedSpans(nowMs, policy) }.mapNotNull { OnlineMetrics.clip(it, window) })
        val connected = OnlineMetrics.union(sessions.mapNotNull { OnlineMetrics.clip(TimeSpan(it.startedAt, it.possibleEnd(nowMs, policy)), window) })
        val phaseSpans = trips.map { phases(it, nowMs) }
        val byUse = TimeUse.entries.filter { it != TimeUse.SEARCHING }.associateWith { u ->
            OnlineMetrics.union(phaseSpans.mapNotNull { it[u] }.mapNotNull { OnlineMetrics.clip(it, window) })
        }

        val bounds = sortedSetOf(window.startMs, window.endMs)
        bounds += slotBoundaries(window, zone)
        (reading + connected + byUse.values.flatten()).forEach { s -> bounds += s.startMs; bounds += s.endMs }
        val points = bounds.filter { it in window.startMs..window.endMs }

        val slotAcc = Array(24 / SLOT_HOURS) { Mutable() }
        val dayAcc = HashMap<Int, Mutable>()
        val shiftAcc = HashMap<Shift, Mutable>()
        fun where(t: Long): ZonedDateTime = Instant.ofEpochMilli(t).atZone(zone)
        fun each(t: Long, f: (Mutable) -> Unit) {
            val z = where(t)
            f(slotAcc[z.hour / SLOT_HOURS])
            f(dayAcc.getOrPut(z.dayOfWeek.value) { Mutable() })
            f(shiftAcc.getOrPut(Shift.of(z.hour)) { Mutable() })
        }

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (b <= a) continue
            val len = b - a
            when {
                covers(reading, a) -> {
                    val use = when {
                        covers(byUse.getValue(TimeUse.PASSENGER), a) -> TimeUse.PASSENGER
                        covers(byUse.getValue(TimeUse.WAITING), a) -> TimeUse.WAITING
                        covers(byUse.getValue(TimeUse.PICKUP), a) -> TimeUse.PICKUP
                        else -> TimeUse.SEARCHING
                    }
                    each(a) { it.reading += len; it.uses[use] = (it.uses[use] ?: 0L) + len }
                }
                covers(connected, a) -> each(a) { it.unread += len }
                else -> each(a) { it.offline += len }
            }
        }

        offers.filter { it.atMs >= window.startMs && it.atMs < window.endMs }.forEach { o ->
            each(o.atMs) { it.offers++; it.offerRdpH += o.rdpPerHour; it.offerRdpKm += o.rdpPerKm; it.offerPickupKm += o.pickupKm }
        }
        trips.forEach { t ->
            val seen = t.offerSeenAt
            if (seen != null && seen >= window.startMs && seen < window.endMs) {
                each(seen) { it.taken++; t.offeredRdpPerHour?.let { v -> it.takenRdpH += v } }
            }
            val start = t.acceptedAt ?: seen ?: t.arrivedAt ?: t.tripStartedAt
            if (t.counted && start != null && start >= window.startMs && start < window.endMs) {
                each(start) { a ->
                    a.counted++
                    t.realMinor?.let { a.confirmed++; a.realMinor += it }
                }
            }
        }

        val slots = slotAcc.map { it.freeze() }
        val total = slots.fold(EffAcc()) { x, y -> x + y }
        val weekdays = (1..7).associateWith { (dayAcc[it] ?: Mutable()).freeze() }
        val shifts = Shift.entries.associateWith { (shiftAcc[it] ?: Mutable()).freeze() }

        val products = productsOf(offers.filter { it.atMs >= window.startMs && it.atMs < window.endMs }, trips.filter { t ->
            val start = t.offerSeenAt ?: t.acceptedAt
            start != null && start >= window.startMs && start < window.endMs
        })

        val km = segments.filter { it.startedAt >= window.startMs && it.startedAt < window.endMs }.fold(KmSplit()) { k, s ->
            KmSplit(k.passengerMm + s.passengerMm, k.pickupMm + s.pickupMm, k.emptyMm + s.emptyMm, k.gapMm + s.gapMm)
        }

        val suggestions = if (withSuggestions) suggestions(total, slots, weekdays) else emptyList()
        return EfficiencyReport(window, total, slots, weekdays, shifts, products, km, suggestions)
    }

    private fun productsOf(offers: List<EffOffer>, trips: List<EffTrip>): List<ProductEff> {
        val names = LinkedHashMap<String, String>()
        fun key(p: String?): String? = p?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        offers.forEach { o -> key(o.product)?.let { names.putIfAbsent(it, o.product!!.trim()) } }
        trips.forEach { t -> key(t.product)?.let { names.putIfAbsent(it, t.product!!.trim()) } }
        return names.map { (k, label) ->
            val os = offers.filter { key(it.product) == k }
            val ts = trips.filter { key(it.product) == k }
            val conf = ts.filter { it.counted && it.realMinor != null }
            ProductEff(label, os.size, ts.count { it.offerSeenAt != null }, conf.size, conf.sumOf { it.realMinor!! }, os.map { it.rdpPerHour })
        }.sortedByDescending { it.offers }
    }

    private fun pct(v: Double) = "${Math.round(v * 100)} %"

    private fun hours(ms: Long): String {
        val h = ms / 3_600_000.0
        return if (h >= 10) "${Math.round(h)} h" else String.format(java.util.Locale.US, "%.1f h", h)
    }

    private fun slotLabel(k: Int) = "%02d:00 a %02d:00".format(k * SLOT_HOURS, (k * SLOT_HOURS + SLOT_HOURS) % 24)

    private val DAY_NAMES = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábados", "domingos")

    /**
     * Up to three, each tied to a loss seen in this period and with its sample: a slot with much more
     * time searching than the driver's average, a slot with much more time going to and waiting for
     * pickups, and a weekday with much less confirmed money per hour. Ordered by minutes lost.
     */
    fun suggestions(total: EffAcc, slots: List<EffAcc>, weekdays: Map<Int, EffAcc>): List<Suggestion> {
        if (total.readingMs < MIN_COMPARE_MS) return emptyList()
        val out = ArrayList<Suggestion>()
        val avgSearch = total.share(TimeUse.SEARCHING) ?: 0.0
        val avgPickup = (total.use(TimeUse.PICKUP) + total.use(TimeUse.WAITING)).toDouble() / total.readingMs
        slots.forEachIndexed { k, s ->
            if (s.readingMs < MIN_COMPARE_MS) return@forEachIndexed
            val search = s.share(TimeUse.SEARCHING) ?: return@forEachIndexed
            if (search - avgSearch >= 0.10) {
                out += Suggestion(
                    "De ${slotLabel(k)} pasaste el ${pct(search)} del tiempo buscando (tu promedio: ${pct(avgSearch)}).",
                    "${hours(s.readingMs)} conectado y leyendo en esa franja",
                    (search - avgSearch) * s.readingMs / 60_000.0
                )
            }
            val pickup = (s.use(TimeUse.PICKUP) + s.use(TimeUse.WAITING)).toDouble() / s.readingMs
            if (pickup - avgPickup >= 0.10) {
                out += Suggestion(
                    "De ${slotLabel(k)} yendo a recoger y esperando fue el ${pct(pickup)} del tiempo (tu promedio: ${pct(avgPickup)}).",
                    "${hours(s.readingMs)} conectado y leyendo en esa franja",
                    (pickup - avgPickup) * s.readingMs / 60_000.0
                )
            }
        }
        val avgRate = if (total.confirmed >= MIN_TRIPS) total.realMinor / 100.0 / total.readingHours else null
        if (avgRate != null && avgRate > 0) {
            weekdays.forEach { (d, a) ->
                if (a.readingMs < MIN_COMPARE_MS || a.confirmed < MIN_TRIPS) return@forEach
                val rate = a.realMinor / 100.0 / a.readingHours
                if (rate <= avgRate * 0.8) {
                    out += Suggestion(
                        "Los ${DAY_NAMES[d - 1]} ganaste RD$${Math.round(rate)} por hora conectada, contra RD$${Math.round(avgRate)} de tu promedio.",
                        "${hours(a.readingMs)} conectado y leyendo, ${a.confirmed} viajes con dinero confirmado",
                        (1 - rate / avgRate) * a.readingMs / 60_000.0
                    )
                }
            }
        }
        return out.sortedByDescending { it.lostMinutes }.take(MAX_SUGGESTIONS)
    }

    /** Mutable accumulator for one pass. */
    private class Mutable {
        var reading = 0L
        var unread = 0L
        var offline = 0L
        val uses = HashMap<TimeUse, Long>()
        var offers = 0
        var taken = 0
        var counted = 0
        var confirmed = 0
        var realMinor = 0L
        val offerRdpH = ArrayList<Double>()
        val offerRdpKm = ArrayList<Double>()
        val offerPickupKm = ArrayList<Double>()
        val takenRdpH = ArrayList<Double>()

        fun freeze() = EffAcc(
            reading, unread, offline, HashMap(uses), offers, taken, counted, confirmed, realMinor,
            offerRdpH.toList(), offerRdpKm.toList(), offerPickupKm.toList(), takenRdpH.toList()
        )
    }
}
