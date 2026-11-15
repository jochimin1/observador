package com.ubers.observador.domain.vehicle

/**
 * 1.54.0 «TPMS compatible», first stage: tyre pressure sensors that send Bluetooth LE
 * advertisements (no connection). Observador records what it receives, associates each sensor
 * with its wheel and reads the frames with a PROVISIONAL parser taken from the study of the
 * MotorCare app. Until real frames from the driver's own sensors confirm it ([TpmsParser.VALIDATED]),
 * a parsed pressure is shown as provisional and never decides a wheel's state or an alert.
 */

/** A sensor ID as printed on the kit card («13AE32»): exactly 6 hex digits, kept in lower case. */
object SensorIds {
    private val ID = Regex("[0-9a-fA-F]{6}")

    fun normalize(text: String): String? = text.trim().takeIf { ID.matches(it) }?.lowercase()

    /** What the card's codes say: «13AE32» (one wheel, Code 128) or «13AE32&23A434» (front & rear, QR). */
    sealed interface Card {
        data class Single(val id: String) : Card
        data class Pair(val front: String, val rear: String) : Card
    }

    fun parseCard(text: String): Result<Card> {
        val t = text.trim()
        val parts = t.split('&')
        return when (parts.size) {
            1 -> normalize(t)?.let { Result.success(Card.Single(it)) }
                ?: Result.failure(IllegalArgumentException("El ID del sensor son 6 caracteres, por ejemplo 13AE32"))
            2 -> {
                val f = normalize(parts[0]); val r = normalize(parts[1])
                when {
                    f == null || r == null -> Result.failure(IllegalArgumentException("Código doble no válido: debe ser ID&ID, por ejemplo 13AE32&23A434"))
                    f == r -> Result.failure(IllegalArgumentException("El mismo sensor no puede ir en las dos ruedas"))
                    else -> Result.success(Card.Pair(f, r))
                }
            }
            else -> Result.failure(IllegalArgumentException("Código no reconocido"))
        }
    }

    /** «13AE32» for display (the card prints upper case). */
    fun shown(id: String): String = id.uppercase()
}

/** One sensor on one wheel, from when it was associated until it was removed. Kept forever. */
data class SensorBinding(
    val id: String,
    val vehicleId: String,
    val position: WheelPosition,
    val sensorId: String,
    val family: String = TpmsParser.FAMILY,
    val boundAt: Long,
    val unboundAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    val active: Boolean get() = unboundAt == null
}

object SensorBindings {
    fun active(bindings: List<SensorBinding>, vehicleId: String, position: WheelPosition): SensorBinding? =
        bindings.filter { it.vehicleId == vehicleId && it.position == position && it.active }.maxByOrNull { it.boundAt }

    /**
     * Associating [sensorId] with [position]: refused if it is already on the other wheel; the
     * wheel's previous sensor is closed and kept. Returns what to write.
     */
    fun bind(bindings: List<SensorBinding>, new: SensorBinding): Result<List<SensorBinding>> {
        val elsewhere = bindings.firstOrNull { it.active && it.sensorId == new.sensorId && !(it.vehicleId == new.vehicleId && it.position == new.position) }
        if (elsewhere != null) return Result.failure(IllegalArgumentException(
            "El sensor ${SensorIds.shown(new.sensorId)} ya está en la rueda ${elsewhere.position.label.lowercase()}. Desvincúlalo primero."))
        val current = active(bindings, new.vehicleId, new.position)
        if (current?.sensorId == new.sensorId) return Result.success(emptyList())
        val closed = current?.copy(unboundAt = new.boundAt, updatedAt = new.updatedAt)
        return Result.success(listOfNotNull(closed, new))
    }
}

/** One Bluetooth LE advertisement as received, before any interpretation. */
data class BleFrame(
    val receivedAt: Long,
    val address: String,
    val name: String?,
    val rssi: Int,
    /** The whole advertisement (scan record), as received. */
    val raw: ByteArray
) {
    val hex: String get() = Hex.of(raw)
    override fun equals(other: Any?) = other is BleFrame && receivedAt == other.receivedAt && address == other.address &&
        name == other.name && rssi == other.rssi && raw.contentEquals(other.raw)
    override fun hashCode() = 31 * address.hashCode() + raw.contentHashCode()
}

object Hex {
    fun of(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun bytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** An AD structure of an advertisement: type and data. */
data class AdStructure(val type: Int, val data: ByteArray) {
    override fun equals(other: Any?) = other is AdStructure && type == other.type && data.contentEquals(other.data)
    override fun hashCode() = 31 * type + data.contentHashCode()
}

object Advertisement {
    /** Splits a scan record into its AD structures; null if a length runs past the end (a damaged record). Zero padding ends it. */
    fun structures(raw: ByteArray): List<AdStructure>? {
        val out = ArrayList<AdStructure>()
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xff
            if (len == 0) break
            if (i + 1 + len > raw.size) return null
            out += AdStructure(raw[i + 1].toInt() and 0xff, raw.copyOfRange(i + 2, i + 1 + len))
            i += 1 + len
        }
        return out
    }

    /** Whether the frame mentions [sensorId] anywhere (the reception test that needs no protocol knowledge). */
    fun mentions(frame: BleFrame, sensorId: String): Boolean = frame.hex.contains(sensorId.lowercase())
}

/** What a frame says, once read. Nulls are «not in the frame», never zero. */
data class TpmsReading(
    val sensorId: String,
    /** Pressure in pascals, as read: the scale and whether it is absolute or gauge are NOT validated yet. */
    val pressurePa: Int?,
    /** Temperature in hundredths of °C. */
    val temperatureCenti: Int?,
    val batteryPct: Int?,
    val leak: Boolean?,
    val parserVersion: String,
    val validated: Boolean
)

sealed interface TpmsParse {
    data class Ok(val reading: TpmsReading) : TpmsParse
    data class Rejected(val reason: String) : TpmsParse
}

/**
 * Provisional reader for the LEEPEE / MotorCare family, from the APK study (REVISION_Y_PROPUESTA.md
 * §5): over the hex of the whole advertisement, pattern «0001» at 18–21, ID at 28–33, pressure at
 * 34–41 (little endian, ÷1000), temperature at 42–49 (little endian, signed, ÷100) and battery at
 * 50–51. These are observations of one app, not a specification: every guard below refuses a
 * frame rather than guess, and nothing is marked validated until real frames confirm it.
 */
object TpmsParser {
    const val FAMILY = "LEEPEE_MOTORCARE"
    const val VERSION = "motorcare-hex-v1-provisional"
    /** Turned true only after the driver's own sensors were recorded and compared with a gauge. */
    const val VALIDATED = false
    /** Pressures outside this are impossible for a motorcycle tyre sensor: refused. 0 is kept (flat tyre). */
    const val MAX_PA = 1_000_000

    private fun le(hex: String): Long {
        var v = 0L
        for (i in hex.length / 2 - 1 downTo 0) v = (v shl 8) or hex.substring(i * 2, i * 2 + 2).toLong(16)
        return v
    }

    fun parse(frame: BleFrame): TpmsParse {
        if (Advertisement.structures(frame.raw) == null) return TpmsParse.Rejected("Anuncio dañado: una longitud no cuadra")
        val h = frame.hex
        if (h.length < 52) return TpmsParse.Rejected("Anuncio corto (${h.length / 2} bytes)")
        if (h.substring(18, 22) != "0001") return TpmsParse.Rejected("Sin el patrón de esta familia")
        val id = h.substring(28, 34)
        val pressure = le(h.substring(34, 42))
        val tempRaw = le(h.substring(42, 50)).toInt() // two's complement over 32 bits
        val battery = h.substring(50, 52).toInt(16)
        val pa = pressure // value ÷ 1000 = kPa ⇒ the raw value is in pascals
        if (pa > MAX_PA) return TpmsParse.Rejected("Presión imposible ($pa Pa)")
        if (tempRaw !in -4_000..15_000) return TpmsParse.Rejected("Temperatura imposible (${tempRaw / 100.0} °C)")
        val leak = if (h.length >= 54) when (h.substring(52, 54)) { "00" -> false; "01" -> true; else -> null } else null
        return TpmsParse.Ok(TpmsReading(id, pa.toInt(), tempRaw, battery.takeIf { it in 0..100 }, leak, VERSION, VALIDATED))
    }
}

/** How fresh the sensor of a wheel is, from when its frames arrived (never from when it measured). */
enum class SensorState(val label: String) {
    NO_SENSOR("Sin sensor asociado"),
    NOT_LISTENING("Sin escuchar: abre la grabadora de sensores"),
    WAITING("Esperando emisión"),
    RECEIVING("Recibiendo"),
    STALE("Lectura antigua")
}

object SensorFreshness {
    /** A frame younger than this is «recibiendo». The kit warns that sensors may wait to transmit. */
    const val RECEIVING_MS = 2 * 60_000L

    fun state(binding: SensorBinding?, lastFrameAt: Long?, listening: Boolean, now: Long): SensorState = when {
        binding == null -> SensorState.NO_SENSOR
        lastFrameAt != null && lastFrameAt >= binding.boundAt && now - lastFrameAt in 0..RECEIVING_MS -> SensorState.RECEIVING
        lastFrameAt != null && lastFrameAt >= binding.boundAt -> SensorState.STALE
        listening -> SensorState.WAITING
        else -> SensorState.NOT_LISTENING
    }

    fun ago(now: Long, at: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "hace $s s"
            s < 3600 -> "hace ${s / 60} min"
            s < 86_400 -> "hace ${s / 3600} h"
            else -> "hace ${s / 86_400} días"
        }
    }
}

/** Recording: the same advertisement repeated by the same device within [windowMs] is kept once. */
class FrameDeduper(private val windowMs: Long = 2_000L) {
    private val last = HashMap<String, Pair<Long, String>>()

    fun accept(frame: BleFrame): Boolean {
        val hex = frame.hex
        val prev = last[frame.address]
        if (prev != null && prev.second == hex && frame.receivedAt - prev.first < windowMs) return false
        last[frame.address] = frame.receivedAt to hex
        return true
    }

    fun reset() = last.clear()
}
