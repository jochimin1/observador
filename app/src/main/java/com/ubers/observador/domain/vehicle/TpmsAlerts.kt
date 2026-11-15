package com.ubers.observador.domain.vehicle

/**
 * 1.54.0: alerts from sensor readings, ready for when the format is validated (TpmsParser.VALIDATED):
 * until then they are never fed and nothing is shown. Each alert needs [TpmsAlertRules.confirm]
 * readings in a row beyond its limit to start and must come back past a hysteresis margin to end,
 * so noise at the limit does not make it flicker. An alert never creates an expense, never changes
 * an offer's score and never claims to prevent a puncture.
 */
enum class TpmsAlertKind(val label: String) {
    LOW_PRESSURE("Presión baja"),
    HIGH_PRESSURE("Presión alta"),
    HIGH_TEMPERATURE("Temperatura alta"),
    LOW_BATTERY("Batería del sensor baja"),
    /** Reported by the sensor itself (never inferred from a pressure drop). */
    LEAK_REPORTED("Fuga informada por el sensor"),
    SIGNAL_LOST("Sin señal del sensor")
}

data class TpmsAlertRules(
    /** Below the tyre's target by more than this: low. */
    val lowPct: Int = 10,
    val highPct: Int = 10,
    /** To end, the reading must come back this much inside the limit. */
    val hysteresisPct: Int = 3,
    val maxTemperatureCenti: Int = 8_000,
    val temperatureHysteresisCenti: Int = 500,
    val lowBatteryPct: Int = 20,
    /** No frame for this long while listening: signal lost. */
    val signalLostMs: Long = 10 * 60_000L,
    /** Consecutive readings needed to start an alert. */
    val confirm: Int = 2
)

data class TpmsAlertEvent(
    val position: WheelPosition,
    val kind: TpmsAlertKind,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** The value that started it, in its unit (Pa, centi °C, %, ms). */
    val value: Long,
    /** The limit that applied when it started. */
    val limit: Long
)

/** One sensor reading for the alerts, already known to belong to [position]. */
data class TpmsSample(val position: WheelPosition, val receivedAt: Long, val pressurePa: Int?, val temperatureCenti: Int?, val batteryPct: Int?, val leak: Boolean?)

/** Per-wheel alert state; feed it readings in time order. Pure: the caller stores the events. */
class TpmsAlertTracker(private val rules: TpmsAlertRules = TpmsAlertRules()) {
    private val streak = HashMap<Pair<WheelPosition, TpmsAlertKind>, Int>()
    private val open = HashMap<Pair<WheelPosition, TpmsAlertKind>, TpmsAlertEvent>()
    private val lastAt = HashMap<WheelPosition, Long>()

    val active: List<TpmsAlertEvent> get() = open.values.sortedBy { it.startedAt }

    /** Returns the events that started or ended with this reading. */
    fun onSample(s: TpmsSample, target: Pressure?): List<TpmsAlertEvent> {
        val out = ArrayList<TpmsAlertEvent>()
        lastAt[s.position] = s.receivedAt
        close(s.position, TpmsAlertKind.SIGNAL_LOST, s.receivedAt)?.let { out += it }
        val t = target?.pascals?.toLong()
        val p = s.pressurePa?.toLong()
        if (t != null && p != null) {
            val low = t * (100 - rules.lowPct) / 100
            val lowEnd = t * (100 - rules.lowPct + rules.hysteresisPct) / 100
            val high = t * (100 + rules.highPct) / 100
            val highEnd = t * (100 + rules.highPct - rules.hysteresisPct) / 100
            out += check(s.position, TpmsAlertKind.LOW_PRESSURE, p < low, p >= lowEnd, s.receivedAt, p, low)
            out += check(s.position, TpmsAlertKind.HIGH_PRESSURE, p > high, p <= highEnd, s.receivedAt, p, high)
        }
        s.temperatureCenti?.let { c ->
            out += check(s.position, TpmsAlertKind.HIGH_TEMPERATURE, c > rules.maxTemperatureCenti,
                c <= rules.maxTemperatureCenti - rules.temperatureHysteresisCenti, s.receivedAt, c.toLong(), rules.maxTemperatureCenti.toLong())
        }
        s.batteryPct?.let { b ->
            out += check(s.position, TpmsAlertKind.LOW_BATTERY, b < rules.lowBatteryPct, b >= rules.lowBatteryPct + 5, s.receivedAt, b.toLong(), rules.lowBatteryPct.toLong())
        }
        s.leak?.let { l -> out += check(s.position, TpmsAlertKind.LEAK_REPORTED, l, !l, s.receivedAt, if (l) 1 else 0, 1, confirmNeeded = 1) }
        return out
    }

    /** While listening, called regularly: a wheel silent for too long gets «sin señal». */
    fun onTick(now: Long, listening: Boolean, wheels: Collection<WheelPosition>): List<TpmsAlertEvent> {
        if (!listening) return emptyList()
        return wheels.mapNotNull { w ->
            val last = lastAt[w] ?: return@mapNotNull null
            val key = w to TpmsAlertKind.SIGNAL_LOST
            if (now - last >= rules.signalLostMs && key !in open)
                TpmsAlertEvent(w, TpmsAlertKind.SIGNAL_LOST, now, value = now - last, limit = rules.signalLostMs).also { open[key] = it }
            else null
        }
    }

    private fun check(w: WheelPosition, kind: TpmsAlertKind, beyond: Boolean, recovered: Boolean, at: Long, value: Long, limit: Long,
        confirmNeeded: Int = rules.confirm): List<TpmsAlertEvent> {
        val key = w to kind
        if (key in open) {
            streak.remove(key)
            return if (recovered) listOfNotNull(close(w, kind, at)) else emptyList()
        }
        if (!beyond) { streak.remove(key); return emptyList() }
        val n = (streak[key] ?: 0) + 1
        streak[key] = n
        if (n < confirmNeeded) return emptyList()
        streak.remove(key)
        val e = TpmsAlertEvent(w, kind, at, value = value, limit = limit)
        open[key] = e
        return listOf(e)
    }

    private fun close(w: WheelPosition, kind: TpmsAlertKind, at: Long): TpmsAlertEvent? =
        open.remove(w to kind)?.copy(endedAt = at)
}

/** Notifications: one per alert start; a temporary silence still records, it only keeps quiet. */
object TpmsAlertNotice {
    fun shouldNotify(e: TpmsAlertEvent, silencedUntil: Long?): Boolean = e.endedAt == null && (silencedUntil == null || e.startedAt >= silencedUntil)

    fun text(e: TpmsAlertEvent, unit: PressureUnit): String = "${e.kind.label} · rueda ${e.position.label.lowercase()}: " + when (e.kind) {
        TpmsAlertKind.LOW_PRESSURE, TpmsAlertKind.HIGH_PRESSURE -> "${TirePressure.format(e.value.toInt(), unit)} (límite ${TirePressure.format(e.limit.toInt(), unit)})"
        TpmsAlertKind.HIGH_TEMPERATURE -> "${e.value / 100} °C (límite ${e.limit / 100} °C)"
        TpmsAlertKind.LOW_BATTERY -> "${e.value} %"
        TpmsAlertKind.LEAK_REPORTED -> "revísala cuanto antes"
        TpmsAlertKind.SIGNAL_LOST -> "nada recibido desde hace ${e.value / 60_000} min"
    }
}
