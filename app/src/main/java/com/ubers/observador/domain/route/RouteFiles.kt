package com.ubers.observador.domain.route

import com.ubers.observador.domain.reports.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One trip's route as it goes into a file. The rider's name always goes (Plan 8K, decision 9). */
data class RouteTrack(
    val tripId: String,
    val riderName: String?,
    /** "Stefany · 6:42 → 7:01". */
    val title: String,
    /** "Recogida (Uber): … · Destino (Uber): …", or null. */
    val description: String?,
    val summary: TripRouteSummary,
    val points: List<RoutePoint>
)

/**
 * The route files (Fase 8K): GPX 1.1, which Google Maps (My Maps), Google Earth and most map apps
 * open, and GeoJSON. They are the only files with coordinates; the other exports still have
 * none. Coordinates are written from whole 1e-7 degrees, never through a floating point.
 */
object RouteFiles {

    const val GPX_MIME = "application/gpx+xml"
    const val GEOJSON_MIME = "application/geo+json"

    private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    /** observador_recorrido_2026-10-17_0642.gpx */
    fun fileName(startMs: Long, zone: ZoneId, extension: String): String =
        "observador_recorrido_${LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), zone).format(NAME_TIME)}.$extension"

    /** One trip as a track: «Stefany · 6:42 → 7:01» and Uber's addresses. */
    fun track(trip: com.ubers.observador.domain.trips.Trip, summary: TripRouteSummary, points: List<RoutePoint>, zone: ZoneId): RouteTrack {
        val sheet = TripSheets.of(trip, summary, null)
        val times = listOfNotNull(sheet.startAt?.let { TripSheets.hourShort(it, zone) }, sheet.endAt?.let { TripSheets.hourShort(it, zone) })
            .joinToString(" → ")
        val day = (sheet.startAt ?: trip.createdAt).let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone).toLocalDate() }
        val title = listOfNotNull(trip.riderName?.trim()?.takeIf { it.isNotEmpty() } ?: "Viaje", "$day", times.ifEmpty { null }).joinToString(" · ")
        val description = listOfNotNull(
            trip.offer?.pickupAddress?.let { "Recogida (Uber): $it" },
            trip.offer?.dropoffAddress?.let { "Destino (Uber): $it" },
            summary.startPlace?.let { "Inicio: $it" },
            summary.endPlace?.let { "Fin: $it" }
        ).joinToString(" · ").ifEmpty { null }
        return RouteTrack(trip.id, trip.riderName, title, description, summary, points)
    }

    /** UTC, whole seconds: 2026-10-17T10:42:05Z. */
    fun utc(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    fun gpx(tracks: List<RouteTrack>, exportedAtMs: Long): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Observador\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(xml(if (tracks.size == 1) tracks[0].title else "Recorridos de Observador")).append("</name>\n")
        sb.append("    <time>").append(utc(exportedAtMs)).append("</time>\n")
        sb.append("  </metadata>\n")
        tracks.filter { it.points.isNotEmpty() }.forEach { t ->
            // Start and end as waypoints, so map apps show both ends with their names
            point(sb, "wpt", t.points.first(), "Inicio · ${t.title}")
            point(sb, "wpt", t.points.last(), "Fin · ${t.title}")
        }
        tracks.filter { it.points.isNotEmpty() }.forEach { t ->
            sb.append("  <trk>\n")
            sb.append("    <name>").append(xml(t.title)).append("</name>\n")
            val desc = listOfNotNull(t.riderName?.let { "Pasajero: $it" }, t.description).joinToString(" · ")
            if (desc.isNotEmpty()) sb.append("    <desc>").append(xml(desc)).append("</desc>\n")
            // One segment per phase: pickup, with passenger, or not told apart
            RouteSimplifier.runsOf(t.points).forEach { run ->
                sb.append("    <trkseg>\n")
                run.forEach { p ->
                    sb.append("      <trkpt lat=\"").append(E7.text(p.latE7)).append("\" lon=\"").append(E7.text(p.lngE7)).append("\">")
                    sb.append("<time>").append(utc(p.atMs)).append("</time>")
                    sb.append("<type>").append(p.phase.storageKey).append("</type>")
                    sb.append("</trkpt>\n")
                }
                sb.append("    </trkseg>\n")
            }
            sb.append("  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun point(sb: StringBuilder, tag: String, p: RoutePoint, name: String) {
        sb.append("  <").append(tag).append(" lat=\"").append(E7.text(p.latE7)).append("\" lon=\"").append(E7.text(p.lngE7)).append("\">")
        sb.append("<time>").append(utc(p.atMs)).append("</time>")
        sb.append("<name>").append(xml(name)).append("</name>")
        sb.append("</").append(tag).append(">\n")
    }

    /** A FeatureCollection: per trip one line per phase, and a point for the start and the end. */
    fun geoJson(tracks: List<RouteTrack>, exportedAtMs: Long): String {
        val features = ArrayList<String>()
        tracks.filter { it.points.isNotEmpty() }.forEach { t ->
            fun props(vararg extra: Pair<String, String>) = Json.obj(
                "tripId" to Json.str(t.tripId),
                "title" to Json.str(t.title),
                "riderName" to Json.str(t.riderName),
                "description" to Json.str(t.description),
                *extra
            )
            RouteSimplifier.runsOf(t.points).forEach { run ->
                // A line needs two points; a lone point of a phase goes as a point
                val geometry = if (run.size >= 2) {
                    Json.obj("type" to Json.str("LineString"), "coordinates" to run.joinToString(",", "[", "]") { position(it) })
                } else {
                    Json.obj("type" to Json.str("Point"), "coordinates" to position(run[0]))
                }
                features += Json.obj(
                    "type" to Json.str("Feature"),
                    "geometry" to geometry,
                    "properties" to props(
                        "kind" to Json.str("route"),
                        "phase" to Json.str(run[0].phase.storageKey),
                        "from" to Json.str(utc(run.first().atMs)),
                        "to" to Json.str(utc(run.last().atMs)),
                        "times" to run.joinToString(",", "[", "]") { Json.str(utc(it.atMs)) }
                    )
                )
            }
            listOf("start" to t.points.first(), "end" to t.points.last()).forEach { (kind, p) ->
                features += Json.obj(
                    "type" to Json.str("Feature"),
                    "geometry" to Json.obj("type" to Json.str("Point"), "coordinates" to position(p)),
                    "properties" to props(
                        "kind" to Json.str(kind),
                        "at" to Json.str(utc(p.atMs)),
                        "place" to Json.str(if (kind == "start") t.summary.startPlace else t.summary.endPlace)
                    )
                )
            }
        }
        return Json.obj(
            "type" to Json.str("FeatureCollection"),
            "generator" to Json.str("Observador"),
            "exportedAt" to Json.str(utc(exportedAtMs)),
            "features" to Json.arr(features)
        ) + "\n"
    }

    /** GeoJSON order: longitude first. */
    private fun position(p: RoutePoint): String = "[${E7.text(p.lngE7)},${E7.text(p.latE7)}]"

    fun xml(text: String): String {
        val sb = StringBuilder(text.length)
        text.forEach { c ->
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                // Control characters are not allowed in XML 1.0
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
