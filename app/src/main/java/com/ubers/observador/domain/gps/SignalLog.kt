package com.ubers.observador.domain.gps

import com.ubers.observador.domain.distance.DistanceBucket
import com.ubers.observador.domain.distance.DistancePolicy
import com.ubers.observador.domain.distance.Geo
import com.ubers.observador.domain.distance.LocationFix
import java.util.Locale

/** Why a position did or did not count, with the kilometres' own filters (DistancePolicy). */
enum class FixVerdict { KEPT, ACCURACY, JUMP, JITTER }

/**
 * 1.45.0: one stretch of signal accounting for one phase, as it goes into the daily file
 * (filesDir/gps/senal-AAAA-MM-DD.txt). Counts and times only: the positions themselves are already
 * in the routes; the kilometres keep their own totals. [requestedMs] is the time the GPS was asked
 * for positions; [gapMs] the part of it with no position for more than a minute.
 */
data class SignalRecord(
    val atMs: Long,
    val phase: DistanceBucket,
    val requestedMs: Long = 0,
    val gapMs: Long = 0,
    val gaps: Int = 0,
    val received: Int = 0,
    val kept: Int = 0,
    val accuracy: Int = 0,
    val jump: Int = 0,
    val jitter: Int = 0
) {
    val isEmpty: Boolean get() = requestedMs == 0L && gapMs == 0L && gaps == 0 && received == 0

    operator fun plus(o: SignalRecord) = copy(
        atMs = maxOf(atMs, o.atMs),
        requestedMs = requestedMs + o.requestedMs, gapMs = gapMs + o.gapMs, gaps = gaps + o.gaps,
        received = received + o.received, kept = kept + o.kept,
        accuracy = accuracy + o.accuracy, jump = jump + o.jump, jitter = jitter + o.jitter
    )
}

object SignalCodec {
    private const val TAG = "s1"

    fun encode(r: SignalRecord): String = listOf(
        TAG, r.atMs, r.phase.name, r.requestedMs, r.gapMs, r.gaps, r.received, r.kept, r.accuracy, r.jump, r.jitter
    ).joinToString(";")

    /** Null for a line that is not a signal record (a damaged line never stops the rest). */
    fun decode(line: String): SignalRecord? {
        val p = line.trim().split(';')
        if (p.size != 11 || p[0] != TAG) return null
        val phase = DistanceBucket.entries.firstOrNull { it.name == p[2] } ?: return null
        val n = p.drop(3).map { it.toLongOrNull() ?: return null }
        if (n.any { it < 0 }) return null
        val at = p[1].toLongOrNull() ?: return null
        return SignalRecord(at, phase, n[0], n[1], n[2].toInt(), n[3].toInt(), n[4].toInt(), n[5].toInt(), n[6].toInt(), n[7].toInt())
    }

    fun decodeAll(text: String): List<SignalRecord> = text.lineSequence().mapNotNull { decode(it) }.toList()
}

/**
 * Counts, as positions arrive, what happened to the signal in each phase. Pure and testable; not
 * thread-safe (called from the main thread, like the kilometres' tracker).
 */
class SignalTracker(private val policy: DistancePolicy = DistancePolicy()) {

    private var requestedSince: Long? = null
    private var phase = DistanceBucket.OFFLINE
    /** Last position received (any), to measure silences. */
    private var lastReceivedAt: Long? = null
    /** Last position that counted, the kilometres' reference point. */
    private var reference: LocationFix? = null
    /** The silence under way was already partly written by a flush. */
    private var openGapWrittenMs = 0L
    private var openGapCounted = false
    private val pending = LinkedHashMap<DistanceBucket, SignalRecord>()

    val requested: Boolean get() = requestedSince != null

    private fun add(at: Long, p: DistanceBucket, r: SignalRecord) {
        pending[p] = (pending[p] ?: SignalRecord(at, p)) + r.copy(atMs = at, phase = p)
    }

    private fun accrue(nowMs: Long) {
        val since = requestedSince ?: return
        if (nowMs > since) add(nowMs, phase, SignalRecord(nowMs, phase, requestedMs = nowMs - since))
        requestedSince = nowMs
    }

    /** The GPS was asked for positions ([on]) or released. */
    fun onRequest(on: Boolean, nowMs: Long) {
        if (on == requested) return
        if (on) {
            requestedSince = nowMs
            lastReceivedAt = nowMs
            reference = null
            openGapWrittenMs = 0; openGapCounted = false
        } else {
            accrue(nowMs)
            closeSilence(nowMs)
            requestedSince = null
            lastReceivedAt = null
            reference = null
        }
    }

    fun onPhase(p: DistanceBucket, nowMs: Long) {
        if (p == phase) return
        accrue(nowMs)
        phase = p
    }

    fun onFix(fix: LocationFix, nowMs: Long = fix.timeMs): FixVerdict {
        accrue(nowMs)
        closeSilence(fix.timeMs)
        lastReceivedAt = fix.timeMs
        val verdict = classify(fix)
        add(nowMs, phase, SignalRecord(
            nowMs, phase, received = 1,
            kept = if (verdict == FixVerdict.KEPT) 1 else 0,
            accuracy = if (verdict == FixVerdict.ACCURACY) 1 else 0,
            jump = if (verdict == FixVerdict.JUMP) 1 else 0,
            jitter = if (verdict == FixVerdict.JITTER) 1 else 0
        ))
        if (verdict == FixVerdict.KEPT) reference = fix
        return verdict
    }

    private fun classify(fix: LocationFix): FixVerdict {
        if (fix.accuracyM.isNaN() || fix.accuracyM > policy.maxAccuracyM) return FixVerdict.ACCURACY
        val ref = reference ?: return FixVerdict.KEPT
        val dt = (fix.timeMs - ref.timeMs).coerceAtLeast(1L) / 1000.0
        val d = Geo.distanceM(ref.latitude, ref.longitude, fix.latitude, fix.longitude)
        if (d / dt > policy.maxSpeedMps) return FixVerdict.JUMP
        if (d < maxOf(ref.accuracyM, fix.accuracyM)) return FixVerdict.JITTER
        return FixVerdict.KEPT
    }

    /** A silence longer than [DistancePolicy.signalGapMs] that ends at [atMs] is a gap. */
    private fun closeSilence(atMs: Long) {
        val last = lastReceivedAt ?: return
        val silence = atMs - last
        if (silence > policy.signalGapMs) {
            add(atMs, phase, SignalRecord(atMs, phase, gapMs = silence - openGapWrittenMs, gaps = if (openGapCounted) 0 else 1))
        }
        openGapWrittenMs = 0; openGapCounted = false
    }

    /** The records gathered since the last call, one per phase; a silence under way is written so far. */
    fun flush(nowMs: Long): List<SignalRecord> {
        accrue(nowMs)
        val last = lastReceivedAt
        if (requested && last != null && nowMs - last > policy.signalGapMs) {
            val soFar = nowMs - last
            add(nowMs, phase, SignalRecord(nowMs, phase, gapMs = soFar - openGapWrittenMs, gaps = if (openGapCounted) 0 else 1))
            openGapWrittenMs = soFar
            openGapCounted = true
        }
        val out = pending.values.filter { !it.isEmpty }.map { it.copy(atMs = nowMs) }
        pending.clear()
        return out
    }
}

/** The signal of a period (a trip, a day), by phase. */
data class SignalSummary(val byPhase: Map<DistanceBucket, SignalRecord>) {

    val total: SignalRecord? get() = byPhase.values.reduceOrNull { a, b -> a + b }

    companion object {
        fun of(records: List<SignalRecord>, fromMs: Long = Long.MIN_VALUE, toMs: Long = Long.MAX_VALUE): SignalSummary =
            SignalSummary(records.filter { it.atMs in fromMs..toMs }.groupBy { it.phase }
                .mapValues { (p, l) -> l.reduce { a, b -> a + b }.copy(phase = p) }
                .toSortedMap(compareBy { it.ordinal }))

        fun label(p: DistanceBucket): String = when (p) {
            DistanceBucket.PICKUP -> "Recogida"
            DistanceBucket.PASSENGER -> "Con pasajero"
            DistanceBucket.EMPTY -> "En línea sin viaje"
            DistanceBucket.OFFLINE -> "Fuera de Uber"
        }

        /** Share of the requested time with signal, 0–100; null without requested time. */
        fun coveragePct(r: SignalRecord): Int? =
            if (r.requestedMs <= 0) null else (100.0 * (r.requestedMs - r.gapMs.coerceAtMost(r.requestedMs)) / r.requestedMs).toInt()

        fun duration(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "$s s"
                s < 3600 -> "${s / 60} min ${s % 60} s"
                else -> "${s / 3600} h ${(s % 3600) / 60} min"
            }
        }

        /** «Con pasajero: 96 % con señal · 1 hueco (1 min 40 s) · 212 posiciones, descartadas 9 por precisión, 1 salto». */
        fun line(r: SignalRecord): String {
            val parts = ArrayList<String>()
            parts += coveragePct(r)?.let { "$it % con señal" } ?: "sin tiempo medido"
            parts += if (r.gaps == 0) "sin huecos" else "${r.gaps} ${if (r.gaps == 1) "hueco" else "huecos"} (${duration(r.gapMs)})"
            val discarded = listOfNotNull(
                r.accuracy.takeIf { it > 0 }?.let { "$it por precisión" },
                r.jump.takeIf { it > 0 }?.let { "$it ${if (it == 1) "salto" else "saltos"}" },
                r.jitter.takeIf { it > 0 }?.let { "$it sin movimiento" }
            )
            parts += String.format(Locale.US, "%,d posiciones", r.received) +
                if (discarded.isEmpty()) "" else ", descartadas " + discarded.joinToString(", ")
            return "${label(r.phase)}: " + parts.joinToString(" · ")
        }
    }
}
