package com.ubers.observador.domain.gps

import com.ubers.observador.domain.distance.DistanceBucket
import java.util.Locale

/**
 * 1.45.0, «Batería medida»: one reading of the phone's battery, taken once a minute while the
 * Observador service runs, into filesDir/bateria/AAAA-MM-DD.txt. Android does not tell an app how
 * much each other app uses, so this is the whole phone: Uber, Observador and everything else
 * together, which is exactly what matters on the road. Comparing settings only makes sense on
 * equivalent drives (same kind of route and hours, screen use and not charging).
 */
data class BatterySample(
    val atMs: Long,
    /** 0–100, as Android reports it. */
    val levelPct: Int,
    /** Remaining charge in µAh when the phone reports it (finer than the percentage); null if not. */
    val chargeUah: Long?,
    val charging: Boolean,
    val screenOn: Boolean,
    val gpsOn: Boolean,
    val frequency: GpsFrequency,
    val phase: DistanceBucket
) {
    /** What is compared: GPS off, or on with each frequency. */
    val condition: BatteryCondition
        get() = when {
            !gpsOn -> BatteryCondition.GPS_OFF
            frequency == GpsFrequency.ADAPTIVE -> BatteryCondition.GPS_ADAPTIVE
            else -> BatteryCondition.GPS_FIXED
        }
}

enum class BatteryCondition(val label: String) {
    GPS_OFF("GPS apagado"),
    GPS_FIXED("GPS fija (5 s)"),
    GPS_ADAPTIVE("GPS adaptativa")
}

object BatteryCodec {
    private const val TAG = "b1"

    fun encode(s: BatterySample): String = listOf(
        TAG, s.atMs, s.levelPct, s.chargeUah ?: "", if (s.charging) 1 else 0, if (s.screenOn) 1 else 0,
        if (s.gpsOn) 1 else 0, s.frequency.storageKey, s.phase.name
    ).joinToString(";")

    fun decode(line: String): BatterySample? {
        val p = line.trim().split(';')
        if (p.size != 9 || p[0] != TAG) return null
        val level = p[2].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val charge = if (p[3].isEmpty()) null else (p[3].toLongOrNull()?.takeIf { it > 0 } ?: return null)
        fun bit(s: String) = when (s) { "1" -> true; "0" -> false; else -> null }
        return BatterySample(
            atMs = p[1].toLongOrNull() ?: return null,
            levelPct = level,
            chargeUah = charge,
            charging = bit(p[4]) ?: return null,
            screenOn = bit(p[5]) ?: return null,
            gpsOn = bit(p[6]) ?: return null,
            frequency = GpsFrequency.entries.firstOrNull { it.storageKey == p[7] } ?: return null,
            phase = DistanceBucket.entries.firstOrNull { it.name == p[8] } ?: return null
        )
    }

    fun decodeAll(text: String): List<BatterySample> = text.lineSequence().mapNotNull { decode(it) }.toList()
}

/** The drain measured under one condition. */
data class BatteryRate(
    val condition: BatteryCondition,
    /** Time observed on battery under this condition. */
    val observedMs: Long,
    val percentUsed: Int,
    /** mAh used, when the phone reports its charge in µAh. */
    val mahUsed: Double?,
    val screenOnShare: Int
) {
    val enough: Boolean get() = observedMs >= BatteryStats.MIN_OBSERVED_MS
    val percentPerHour: Double? get() = if (observedMs <= 0) null else percentUsed * 3_600_000.0 / observedMs
    val mahPerHour: Double? get() = mahUsed?.let { if (observedMs <= 0) null else it * 3_600_000.0 / observedMs }
}

object BatteryStats {
    /** A rate is shown only after this much time on battery under one condition. */
    const val MIN_OBSERVED_MS = 30 * 60_000L
    /** Two readings further apart than this (service stopped, phone off) are not joined. */
    const val MAX_STEP_MS = 3 * 60_000L

    /**
     * Adds up the steps between consecutive readings that are both on battery, close in time and
     * under the same condition. A step where the level went up (charger plugged and unplugged in
     * between) is left out.
     */
    fun rates(samples: List<BatterySample>): List<BatteryRate> {
        data class Acc(var ms: Long = 0, var pct: Int = 0, var uah: Long = 0, var uahSteps: Int = 0, var steps: Int = 0, var screen: Long = 0)
        val acc = LinkedHashMap<BatteryCondition, Acc>()
        val sorted = samples.sortedBy { it.atMs }
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val dt = b.atMs - a.atMs
            if (dt <= 0 || dt > MAX_STEP_MS || a.charging || b.charging || a.condition != b.condition) continue
            if (b.levelPct > a.levelPct) continue
            val x = acc.getOrPut(a.condition) { Acc() }
            x.ms += dt
            x.pct += a.levelPct - b.levelPct
            x.steps++
            if (a.screenOn) x.screen += dt
            if (a.chargeUah != null && b.chargeUah != null && b.chargeUah <= a.chargeUah) {
                x.uah += a.chargeUah - b.chargeUah
                x.uahSteps++
            }
        }
        return BatteryCondition.entries.mapNotNull { c ->
            val x = acc[c] ?: return@mapNotNull null
            BatteryRate(
                condition = c,
                observedMs = x.ms,
                percentUsed = x.pct,
                mahUsed = if (x.uahSteps == x.steps && x.steps > 0) x.uah / 1000.0 else null,
                screenOnShare = if (x.ms == 0L) 0 else (x.screen * 100 / x.ms).toInt()
            )
        }
    }

    fun line(r: BatteryRate): String {
        val time = SignalSummary.duration(r.observedMs)
        if (!r.enough) return "${r.condition.label}: muestra insuficiente ($time de ${MIN_OBSERVED_MS / 60_000} min necesarios)"
        val pct = String.format(Locale.US, "%.1f %%/h", r.percentPerHour)
        val mah = r.mahPerHour?.let { String.format(Locale.US, " · %.0f mAh/h", it) } ?: ""
        return "${r.condition.label}: $pct$mah · $time medidos · pantalla encendida ${r.screenOnShare} %"
    }
}
