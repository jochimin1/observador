package com.ubers.observador.domain.vehicle

import kotlin.math.abs

/**
 * 1.60.0: whether the sensors send ABSOLUTE pressure (air + atmosphere) or GAUGE pressure (what a
 * tyre gauge says), settled on the phone with the driver's own gauge. Each «Revisar presión»
 * written while the wheel's sensor was heard (a frame within [TpmsCalibrations.WINDOW_MS]) is a
 * calibration: the sensor minus the gauge is about one atmosphere when absolute and about zero
 * when gauge. With [TpmsCalibrations.REQUIRED] that agree and none that says the opposite, the
 * sensor pressure is confirmed and its alerts start. Nothing extra is stored: calibrations are
 * read from the checks and frames already kept, so editing or deleting a wrong check fixes it.
 */
enum class PressureReference(val label: String) {
    ABSOLUTE("absoluta (el sensor suma la atmósfera)"),
    GAUGE("manométrica (como el manómetro)")
}

/** One reading of a sensor's pressure as it arrived. */
data class SensorPressure(val sensorId: String, val atMs: Long, val pressurePa: Int, val temperatureCenti: Int?)

data class TpmsCalibration(
    val eventId: String,
    val atMs: Long,
    val position: WheelPosition,
    val sensorId: String,
    /** What the gauge said, written by the driver. */
    val gaugePa: Int,
    /** What the sensor sent, as received. */
    val sensorPa: Int,
    val frameAtMs: Long,
    val temperatureCenti: Int?
) {
    val reference: PressureReference? get() = TpmsCalibrations.reference(sensorPa, gaugePa)
    /** The sensor, read as [ref], minus the gauge (Pa). */
    fun errorPa(ref: PressureReference): Int = TpmsReference.gauge(sensorPa, ref) - gaugePa
}

sealed interface CalibrationState {
    data class Confirmed(val reference: PressureReference, val count: Int, val meanErrorPa: Int) : CalibrationState
    /** [count] that agree on [reference] (null when none fits either way yet). */
    data class Pending(val count: Int, val reference: PressureReference?, val notFitting: Int) : CalibrationState
    data class Conflict(val absolute: Int, val gauge: Int) : CalibrationState
}

object TpmsCalibrations {
    /** The gauge and the sensor must be read this close in time. */
    const val WINDOW_MS = 10 * 60_000L
    /** ±25 kPa (3.6 psi): far wider than a gauge's error, far narrower than an atmosphere (101 kPa). */
    const val TOLERANCE_PA = 25_000
    const val REQUIRED = 2

    fun reference(sensorPa: Int, gaugePa: Int): PressureReference? {
        if (gaugePa <= 0) return null
        val d = sensorPa - gaugePa
        return when {
            abs(d - TpmsParser.ATMOSPHERE_PA) <= TOLERANCE_PA -> PressureReference.ABSOLUTE
            abs(d) <= TOLERANCE_PA -> PressureReference.GAUGE
            else -> null
        }
    }

    /** Each pressure check paired with the nearest frame of the sensor on that wheel at that moment. */
    fun pair(data: TireData, bindings: List<SensorBinding>, samples: List<SensorPressure>): List<TpmsCalibration> {
        val bySensor = samples.groupBy { it.sensorId }
        return data.events.filter { it.kind == TireEventKind.CHECK && it.pressure != null }.mapNotNull { e ->
            val tire = data.tires.firstOrNull { it.id == e.tireId } ?: return@mapNotNull null
            val b = bindings.firstOrNull {
                it.vehicleId == tire.vehicleId && it.position == tire.position && e.atMs >= it.boundAt - WINDOW_MS &&
                    (it.unboundAt == null || e.atMs <= it.unboundAt)
            } ?: return@mapNotNull null
            val s = bySensor[b.sensorId].orEmpty()
                .filter { abs(it.atMs - e.atMs) <= WINDOW_MS && it.atMs >= b.boundAt && (b.unboundAt == null || it.atMs <= b.unboundAt) }
                .minByOrNull { abs(it.atMs - e.atMs) } ?: return@mapNotNull null
            TpmsCalibration(e.id, e.atMs, tire.position, b.sensorId, e.pressure!!.pascals, s.pressurePa, s.atMs, s.temperatureCenti)
        }.sortedBy { it.atMs }
    }

    fun state(list: List<TpmsCalibration>): CalibrationState {
        val absolute = list.filter { it.reference == PressureReference.ABSOLUTE }
        val gauge = list.filter { it.reference == PressureReference.GAUGE }
        val notFitting = list.size - absolute.size - gauge.size
        if (absolute.isNotEmpty() && gauge.isNotEmpty()) return CalibrationState.Conflict(absolute.size, gauge.size)
        val (ref, agree) = when {
            absolute.isNotEmpty() -> PressureReference.ABSOLUTE to absolute
            gauge.isNotEmpty() -> PressureReference.GAUGE to gauge
            else -> null to emptyList()
        }
        return if (ref != null && agree.size >= REQUIRED) CalibrationState.Confirmed(ref, agree.size, agree.sumOf { it.errorPa(ref) } / agree.size)
        else CalibrationState.Pending(agree.size, ref, notFitting)
    }

    fun describe(state: CalibrationState, unit: PressureUnit): String = when (state) {
        is CalibrationState.Confirmed -> "Presión del sensor confirmada: ${state.reference.label}, con ${state.count} mediciones de manómetro. " +
            "Diferencia media con el manómetro: ${signed(state.meanErrorPa, unit)}. Las alertas de presión están activas."
        is CalibrationState.Conflict -> "Las mediciones no coinciden: ${state.absolute} dicen presión absoluta y ${state.gauge} manométrica. " +
            "Revisa en el historial la que esté mal escrita y corrígela o bórrala. Hasta entonces la presión del sensor no decide nada."
        is CalibrationState.Pending -> buildString {
            append("Presión del sensor sin confirmar: ${state.count} de $REQUIRED mediciones con manómetro")
            state.reference?.let { append(" (apuntan a ${it.label})") }
            append(". Toca «Calibrar con manómetro» con el sensor recibiendo y escribe lo que marca el manómetro.")
            if (state.notFitting > 0) append(" ${state.notFitting} no cuadran con ninguna de las dos (diferencia de más de ${TirePressure.format(TOLERANCE_PA, unit)}): revisa el manómetro.")
        }
    }

    fun line(c: TpmsCalibration, unit: PressureUnit): String =
        "${c.position.label}: manómetro ${TirePressure.format(c.gaugePa, unit)}, sensor ${TirePressure.format(c.sensorPa, unit)} sin corregir → " +
            (c.reference?.label ?: "no cuadra")

    private fun signed(pa: Int, unit: PressureUnit): String = (if (pa >= 0) "+" else "−") + TirePressure.format(abs(pa), unit)
}

/** The reference confirmed on this phone, set by whoever loads the calibrations (null: not confirmed). */
object TpmsReference {
    @Volatile var current: PressureReference? = null

    /** What a gauge would say for a sensor reading of [pa]. Unknown reference: the reading as sent. */
    fun gauge(pa: Int, ref: PressureReference? = current): Int =
        if (ref == PressureReference.ABSOLUTE) TpmsParser.gaugeIfAbsolute(pa) else pa
}

/** One frame as the diagnosis needs it. */
data class FrameSeen(val atMs: Long, val rssi: Int, val name: String?, val parsedId: String?, val sensorId: String?, val batteryPct: Int?)

data class SensorReport(
    val position: WheelPosition,
    val sensorId: String,
    val lastAtMs: Long?,
    val lastRssi: Int?,
    val count24h: Int,
    val medianGapMs: Long?,
    val batteryPct: Int?,
    val verdict: String
)

/** A TPMS heard nearby that is not associated with any wheel. */
data class StrangerSensor(val id: String, val wheel: Int?, val count: Int, val lastAtMs: Long, val rssi: Int)

/**
 * 1.60.0: why a sensor is or is not being received — when it was last heard, how strong, how often
 * and its battery — and which TPMS sensors are heard that are not associated (an ID written wrong).
 */
object TpmsDiagnosis {
    const val DAY_MS = 24 * 3_600_000L

    fun signal(rssi: Int): String = when {
        rssi >= -70 -> "buena"
        rssi >= -85 -> "débil"
        else -> "muy débil"
    }

    /** [frames]: every frame of the last day; [last]: the sensor's newest frame ever, if any. */
    fun report(binding: SensorBinding, frames: List<FrameSeen>, last: FrameSeen?, now: Long): SensorReport {
        val mine = frames.filter { (it.sensorId == binding.sensorId || it.parsedId == binding.sensorId) && it.atMs >= binding.boundAt && now - it.atMs in 0..DAY_MS }
            .sortedBy { it.atMs }
        val newest = listOfNotNull(last?.takeIf { it.atMs >= binding.boundAt }, mine.lastOrNull()).maxByOrNull { it.atMs }
        val gaps = mine.zipWithNext { a, b -> b.atMs - a.atMs }.filter { it > 5_000 }.sorted()
        val median = gaps.takeIf { it.isNotEmpty() }?.get(gaps.size / 2)
        val battery = newest?.batteryPct
        val shown = SensorIds.shown(binding.sensorId)
        val verdict = when {
            newest == null -> "No se ha recibido nada del sensor $shown desde que lo asociaste. Comprueba que el ID es el de la tarjeta, que la válvula esté " +
                "bien enroscada y rueda unos metros: estos sensores pueden emitir solo con la rueda girando o al cambiar la presión."
            now - newest.atMs > DAY_MS -> "Hace ${SensorFreshness.ago(now, newest.atMs).removePrefix("hace ")} que no se recibe. Puede ser la distancia o la batería del sensor" +
                (battery?.let { " (última vez $it %)" } ?: "") + "."
            newest.rssi < -85 -> "Llega con señal muy débil (${newest.rssi} dBm): acerca el teléfono a esa rueda."
            battery != null && battery <= 20 -> "Se recibe, pero la batería del sensor está baja ($battery %)."
            else -> "Se recibe bien: señal ${signal(newest.rssi)} (${newest.rssi} dBm)" +
                (if (mine.isNotEmpty()) ", ${mine.size} anuncios en 24 h" else "") +
                (median?.let { ", uno cada ${minutes(it)}" } ?: "") + "."
        }
        return SensorReport(binding.position, binding.sensorId, newest?.atMs, newest?.rssi, mine.size, median, battery, verdict)
    }

    /** TPMS sensors heard in [frames] whose ID is none of [associated]. */
    fun strangers(associated: Set<String>, frames: List<FrameSeen>): List<StrangerSensor> =
        frames.mapNotNull { f ->
            val named = TpmsParser.nameParts(f.name)
            val id = f.parsedId ?: named?.second ?: return@mapNotNull null
            if (id in associated) null else Triple(id, named?.first, f)
        }.groupBy { it.first }.map { (id, l) ->
            val newest = l.maxBy { it.third.atMs }.third
            StrangerSensor(id, l.firstNotNullOfOrNull { it.second }, l.size, newest.atMs, newest.rssi)
        }.sortedByDescending { it.lastAtMs }

    private fun minutes(ms: Long): String = if (ms < 90_000) "${ms / 1000} s" else "${ms / 60_000} min"
}
