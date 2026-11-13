package com.ubers.observador.domain.health

import com.ubers.observador.domain.TimeAgo

/**
 * 1.39.0: whether Observador is really watching and saving, from evidence (events, readings,
 * positions, writes) and never from a permission alone. Pure: the service side gathers
 * [HealthSignals], this decides what the driver sees.
 */
enum class HealthLevel(val rank: Int) {
    /** Turned off on purpose (paused, GPS not needed): nothing to fix. */
    OFF(0),
    OK(1),
    /** Nothing wrong, but nothing proven yet either (no Uber screen since connecting). */
    INFO(2),
    WARN(3),
    FAIL(4);

    val isProblem: Boolean get() = this == WARN || this == FAIL
}

enum class HealthArea(val label: String) {
    ACCESSIBILITY("Accesibilidad"),
    READING("Lectura de Uber"),
    OFFERS("Ofertas"),
    GPS("GPS"),
    WRITES("Guardado")
}

data class HealthCheck(
    val area: HealthArea,
    val level: HealthLevel,
    val title: String,
    val detail: String,
    /** What helps, in the driver's words, or null when nothing needs doing. */
    val action: String? = null
)

data class HealthReport(val atMs: Long, val checks: List<HealthCheck>) {
    val overall: HealthLevel get() = checks.maxByOrNull { it.level.rank }?.level ?: HealthLevel.OK
    /** The most serious problem, for the one-line status on Inicio. */
    val worst: HealthCheck? get() = checks.filter { it.level.isProblem }.maxByOrNull { it.level.rank }
    fun of(area: HealthArea): HealthCheck? = checks.firstOrNull { it.area == area }
}

/** The shared write queue, as the queue itself counts it. */
data class QueueHealth(
    val pending: Int = 0,
    val lastWriteAt: Long? = null,
    /** When the oldest job still waiting was submitted. */
    val oldestPendingAt: Long? = null,
    val failures: Int = 0,
    val lastFailureAt: Long? = null,
    /** Only the kind of error (class name), never its message: messages can carry data. */
    val lastFailure: String? = null,
    /** False once the consumer stopped: nothing submitted afterwards is saved. */
    val alive: Boolean = true
)

data class HealthSignals(
    val observerOn: Boolean = true,
    /** The system setting: the driver enabled the service. Not proof that it runs. */
    val accessibilityEnabled: Boolean = false,
    /** Set by the service itself on connect; null while it is not connected. */
    val serviceConnectedAt: Long? = null,
    /** When the app process began watching: Android connects the service a moment after it starts. */
    val watchingSince: Long? = null,
    val lastUberEventAt: Long? = null,
    /** Last Uber screen read (any). */
    val lastReadingAt: Long? = null,
    /** Last Uber screen whose type was recognised. */
    val lastRecognizedAt: Long? = null,
    /** Unrecognised readings in a row since the last recognised one, and when the run began. */
    val unrecognizedRun: Int = 0,
    val unrecognizedSince: Long? = null,
    val lastOfferAt: Long? = null,
    val lastUnreadableAt: Long? = null,
    val lastUnreadableReason: String? = null,
    val locationPermitted: Boolean = false,
    /** The app asked the system for positions (it only does while measuring km). */
    val gpsRequestedAt: Long? = null,
    val lastFixAt: Long? = null,
    /** Android refused the location type to the foreground service (started from the background). */
    val gpsBackgroundLimited: Boolean = false,
    val queue: QueueHealth = QueueHealth()
)

object HealthRules {
    /** Time Android gets to connect the accessibility service after the app starts. */
    const val CONNECT_GRACE_MS = 30_000L
    /** Unrecognised Uber screens needed, and for how long, before the reading counts as broken. */
    const val UNRECOGNIZED_RUN = 5
    const val UNRECOGNIZED_MS = 10 * 60_000L
    /** An unreadable card newer than the last offer is reported for this long. */
    const val UNREADABLE_RECENT_MS = 30 * 60_000L
    /** Time the GPS gets for a first position, and the longest silence once positions flow. */
    const val GPS_FIRST_FIX_MS = 2 * 60_000L
    const val GPS_SILENCE_MS = 2 * 60_000L
    /** A job waiting this long, or this many jobs waiting, means the saving is behind. */
    const val QUEUE_STUCK_MS = 60_000L
    const val QUEUE_BACKLOG = 50
    /** A failed write is shown for this long after it happened. */
    const val FAILURE_RECENT_MS = 30 * 60_000L

    fun evaluate(s: HealthSignals, now: Long): HealthReport =
        HealthReport(now, listOf(accessibility(s, now), reading(s, now), offers(s, now), gps(s, now), writes(s, now)))

    private fun ago(now: Long, at: Long) = TimeAgo.format(now, at)

    private fun connected(s: HealthSignals) = s.observerOn && s.accessibilityEnabled && s.serviceConnectedAt != null

    fun accessibility(s: HealthSignals, now: Long): HealthCheck = when {
        !s.observerOn -> HealthCheck(HealthArea.ACCESSIBILITY, HealthLevel.OFF, "Observador en pausa",
            "No se lee Uber hasta que lo reanudes.", "Reanúdalo en Inicio o en Ajustes.")
        !s.accessibilityEnabled -> HealthCheck(HealthArea.ACCESSIBILITY, HealthLevel.FAIL, "Accesibilidad desactivada",
            "Android no deja que Observador lea Uber.", "Actívala en Ajustes de Android › Accesibilidad › Observador.")
        s.serviceConnectedAt == null && s.watchingSince != null && now - s.watchingSince < CONNECT_GRACE_MS ->
            HealthCheck(HealthArea.ACCESSIBILITY, HealthLevel.INFO, "Conectando", "Android está conectando el servicio de accesibilidad.")
        s.serviceConnectedAt == null -> HealthCheck(HealthArea.ACCESSIBILITY, HealthLevel.FAIL,
            "Permiso activo, servicio desconectado",
            "El permiso está concedido, pero Android no tiene el servicio en marcha. No se está leyendo nada.",
            "Desactiva y vuelve a activar Observador en Accesibilidad; si sigue igual, reinicia el teléfono.")
        else -> HealthCheck(HealthArea.ACCESSIBILITY, HealthLevel.OK, "Servicio conectado",
            "Conectado ${ago(now, s.serviceConnectedAt)}.")
    }

    fun reading(s: HealthSignals, now: Long): HealthCheck {
        if (!connected(s)) return HealthCheck(HealthArea.READING, HealthLevel.OFF, "Sin lectura",
            "No hay lectura mientras el servicio no esté conectado y activo.")
        val since = s.unrecognizedSince
        val broken = since != null && s.unrecognizedRun >= UNRECOGNIZED_RUN && now - since >= UNRECOGNIZED_MS &&
            (s.lastRecognizedAt == null || s.lastRecognizedAt < since)
        return when {
            broken -> HealthCheck(HealthArea.READING, HealthLevel.WARN, "Uber se ve, pero no lo entiendo",
                "${s.unrecognizedRun} pantallas seguidas sin reconocer desde ${ago(now, since!!)}. " +
                    "La lectura pudo dejar de funcionar, por ejemplo tras una actualización de Uber.",
                "Activa «Guardar capturas» en Laboratorio y comparte el archivo para ajustar el lector.")
            s.lastRecognizedAt != null -> HealthCheck(HealthArea.READING, HealthLevel.OK, "Lectura funcionando",
                "Última pantalla de Uber reconocida ${ago(now, s.lastRecognizedAt)}.")
            s.lastUberEventAt == null -> HealthCheck(HealthArea.READING, HealthLevel.INFO, "Esperando a Uber",
                "Uber no ha mostrado pantallas desde que se conectó el servicio. Es normal si Uber está cerrado.")
            else -> HealthCheck(HealthArea.READING, HealthLevel.INFO, "Aún sin pantallas reconocidas",
                "Uber envió pantallas, pero ninguna se ha reconocido todavía.")
        }
    }

    fun offers(s: HealthSignals, now: Long): HealthCheck {
        if (!connected(s)) return HealthCheck(HealthArea.OFFERS, HealthLevel.OFF, "Sin lectura", "No se reciben ofertas sin lectura.")
        val unreadable = s.lastUnreadableAt
        if (unreadable != null && now - unreadable <= UNREADABLE_RECENT_MS && (s.lastOfferAt == null || s.lastOfferAt < unreadable)) {
            return HealthCheck(HealthArea.OFFERS, HealthLevel.WARN, "La última oferta no se pudo leer",
                "${ago(now, unreadable).replaceFirstChar { it.uppercase() }}: ${s.lastUnreadableReason ?: "motivo no disponible"}. No se le dio puntaje.",
                "Si se repite, activa «Guardar capturas» en Laboratorio y comparte el archivo.")
        }
        val readingWorks = reading(s, now).level == HealthLevel.OK
        return when {
            s.lastOfferAt != null -> HealthCheck(HealthArea.OFFERS, HealthLevel.OK, "Ofertas leídas",
                "Última oferta ${ago(now, s.lastOfferAt)}.")
            readingWorks -> HealthCheck(HealthArea.OFFERS, HealthLevel.INFO, "No han llegado ofertas",
                "La lectura funciona; simplemente Uber no ha mostrado ofertas desde que se conectó.")
            else -> HealthCheck(HealthArea.OFFERS, HealthLevel.INFO, "Sin ofertas todavía",
                "No se puede distinguir aún entre falta de ofertas y falta de lectura.")
        }
    }

    fun gps(s: HealthSignals, now: Long): HealthCheck {
        val requested = s.gpsRequestedAt
        return when {
            requested == null && !s.locationPermitted -> HealthCheck(HealthArea.GPS, HealthLevel.OFF, "Sin permiso de ubicación",
                "Los km medidos con GPS no están disponibles.", "Concede la ubicación precisa si quieres medir km.")
            requested == null -> HealthCheck(HealthArea.GPS, HealthLevel.OFF, "GPS en reposo",
                "Solo se enciende mientras se miden km.")
            !s.locationPermitted -> HealthCheck(HealthArea.GPS, HealthLevel.FAIL, "Se retiró el permiso de ubicación",
                "Se pidieron posiciones, pero el permiso ya no está concedido.", "Concede la ubicación precisa a Observador.")
            s.gpsBackgroundLimited -> HealthCheck(HealthArea.GPS, HealthLevel.WARN, "Ubicación limitada en segundo plano",
                "Android arrancó Observador sin acceso a la ubicación; puede que no lleguen posiciones.",
                "Abre Observador una vez para recuperar la ubicación.")
            s.lastFixAt == null || s.lastFixAt < requested -> if (now - requested >= GPS_FIRST_FIX_MS) {
                HealthCheck(HealthArea.GPS, HealthLevel.FAIL, "GPS autorizado, pero sin posiciones",
                    "Se pidieron posiciones ${ago(now, requested)} y no ha llegado ninguna. Los km no se están midiendo.",
                    "Comprueba que la ubicación del teléfono esté activada y abre Observador una vez.")
            } else HealthCheck(HealthArea.GPS, HealthLevel.INFO, "Buscando señal", "Esperando la primera posición.")
            now - s.lastFixAt >= GPS_SILENCE_MS -> HealthCheck(HealthArea.GPS, HealthLevel.WARN, "Posiciones interrumpidas",
                "La última posición llegó ${ago(now, s.lastFixAt)}.",
                "Comprueba que la ubicación esté activada; en lugares cerrados es normal perder señal.")
            else -> HealthCheck(HealthArea.GPS, HealthLevel.OK, "GPS entregando posiciones",
                "Última posición ${ago(now, s.lastFixAt)}.")
        }
    }

    fun writes(s: HealthSignals, now: Long): HealthCheck {
        val q = s.queue
        val oldest = q.oldestPendingAt
        val failed = q.lastFailureAt
        return when {
            !q.alive -> HealthCheck(HealthArea.WRITES, HealthLevel.FAIL, "El guardado se detuvo",
                "La cola de escritura dejó de funcionar: lo nuevo no se está guardando.",
                "Cierra Observador con «Apagar» y vuelve a abrirlo; exporta el diagnóstico si se repite.")
            q.pending >= QUEUE_BACKLOG || (oldest != null && now - oldest >= QUEUE_STUCK_MS) ->
                HealthCheck(HealthArea.WRITES, HealthLevel.WARN, "Guardado atrasado",
                    "${q.pending} ${if (q.pending == 1) "escritura pendiente" else "escrituras pendientes"}" +
                        (oldest?.let { ", la más antigua ${ago(now, it)}" } ?: "") + ".",
                    "Espera unos segundos; si no baja, exporta el diagnóstico.")
            failed != null && now - failed <= FAILURE_RECENT_MS -> HealthCheck(HealthArea.WRITES, HealthLevel.WARN,
                "Falló un guardado",
                "${ago(now, failed).replaceFirstChar { it.uppercase() }} (${q.lastFailure ?: "error"}). " +
                    "${q.failures} ${if (q.failures == 1) "fallo" else "fallos"} desde que se abrió la app; el resto sigue guardándose.",
                "Si se repite, exporta el diagnóstico.")
            else -> HealthCheck(HealthArea.WRITES, HealthLevel.OK, "Guardando",
                (if (q.pending == 0) "Nada pendiente" else "${q.pending} pendientes") +
                    (q.lastWriteAt?.let { " · última escritura ${ago(now, it)}" } ?: " · sin escrituras desde que se abrió la app") + ".")
        }
    }
}

/** One change of state worth remembering. Only our own texts: no screen text, names or places. */
data class HealthIncident(val atMs: Long, val area: HealthArea, val level: HealthLevel, val title: String) {
    val resolved: Boolean get() = !level.isProblem
}

/**
 * Turns reports into incidents: one when an area becomes a problem or changes problem, one when
 * it recovers. A problem that lasts is written once, so nothing repeats while it stays.
 */
class IncidentTracker(initial: Map<HealthArea, HealthCheck> = emptyMap()) {
    private val last = initial.toMutableMap()

    @Synchronized
    fun update(report: HealthReport): List<HealthIncident> = report.checks.mapNotNull { check ->
        val before = last[check.area]
        last[check.area] = check
        when {
            check.level.isProblem && (before == null || before.level != check.level || before.title != check.title) ->
                HealthIncident(report.atMs, check.area, check.level, check.title)
            !check.level.isProblem && before != null && before.level.isProblem ->
                HealthIncident(report.atMs, check.area, check.level, "Resuelto: ${before.title}")
            else -> null
        }
    }
}

/** The short local history: newest first, bounded, one line per incident. */
object IncidentLog {
    const val MAX = 50

    fun add(current: List<HealthIncident>, new: List<HealthIncident>): List<HealthIncident> =
        (new.sortedByDescending { it.atMs } + current).take(MAX)

    fun encode(list: List<HealthIncident>): String = list.joinToString("\n") {
        "${it.atMs}\t${it.area.name}\t${it.level.name}\t${it.title.replace('\t', ' ').replace('\n', ' ')}"
    }

    /** Unknown or damaged lines are skipped, never fatal. */
    fun decode(text: String?): List<HealthIncident> = text.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 4) return@mapNotNull null
        val at = p[0].toLongOrNull() ?: return@mapNotNull null
        val area = HealthArea.entries.firstOrNull { it.name == p[1] } ?: return@mapNotNull null
        val level = HealthLevel.entries.firstOrNull { it.name == p[2] } ?: return@mapNotNull null
        HealthIncident(at, area, level, p[3])
    }.take(MAX).toList()
}

/**
 * The diagnosis a driver can send: versions, the current checks and the incident history. Built
 * only from our own texts and times, so it never carries screen text, names, places or positions.
 */
object HealthExport {
    fun text(
        report: HealthReport,
        incidents: List<HealthIncident>,
        versions: List<Pair<String, String>>,
        zone: java.time.ZoneId
    ): String {
        val time = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).format(time)
        return buildString {
            appendLine("Diagnóstico de Observador · ${at(report.atMs)}")
            versions.forEach { (name, value) -> appendLine("$name: $value") }
            appendLine()
            appendLine("Estado general: ${report.overall.name}")
            report.checks.forEach { c ->
                appendLine("[${c.level.name}] ${c.area.label} · ${c.title}")
                appendLine("    ${c.detail}")
                c.action?.let { appendLine("    Qué hacer: $it") }
            }
            appendLine()
            appendLine("Incidentes recientes (${incidents.size}):")
            if (incidents.isEmpty()) appendLine("    ninguno")
            incidents.forEach { appendLine("    ${at(it.atMs)} · ${it.level.name} · ${it.area.label} · ${it.title}") }
            appendLine()
            appendLine("No contiene textos de pantalla, nombres, direcciones, importes de viajes ni coordenadas.")
        }
    }
}
