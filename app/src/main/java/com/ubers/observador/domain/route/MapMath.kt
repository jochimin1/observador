package com.ubers.observador.domain.route

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.tan

/**
 * The street map of Fase 8K, as numbers: Web Mercator (the projection of OpenStreetMap tiles),
 * fitting a route on screen and which 256-pixel tiles cover it. Pure, so it is tested on the JVM;
 * the screen only draws what this says.
 */
object WebMercator {
    const val MAX_LAT = 85.05112878

    /** 0 at 180° W, 1 at 180° E. */
    fun x(longitude: Double): Double = (longitude + 180.0) / 360.0

    /** 0 at the top (85° N), 1 at the bottom (85° S). */
    fun y(latitude: Double): Double {
        val lat = Math.toRadians(latitude.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(lat) + 1.0 / kotlin.math.cos(lat)) / PI) / 2.0
    }
}

/** What part of the world the map shows: [zoom] may be fractional; the centre in 0..1 units. */
data class MapView(val zoom: Double, val centerX: Double, val centerY: Double)

/** One tile to draw, already placed on screen (pixels from the top-left of the map). */
data class TilePlacement(val z: Int, val x: Int, val y: Int, val left: Double, val top: Double, val size: Double) {
    val key: String get() = "$z/$x/$y"
}

object MapMath {
    const val MIN_ZOOM = 2.0
    const val MAX_ZOOM = 19.0

    /** Zoom and centre that fit [points] in a [width]×[height] map with [padding] on each side. */
    fun fit(points: List<RoutePoint>, width: Double, height: Double, tileSize: Double, padding: Double, maxZoom: Double = 17.0): MapView? {
        if (points.isEmpty() || width <= 0 || height <= 0) return null
        val xs = points.map { WebMercator.x(it.longitude) }
        val ys = points.map { WebMercator.y(it.latitude) }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val w = (width - 2 * padding).coerceAtLeast(1.0)
        val h = (height - 2 * padding).coerceAtLeast(1.0)
        val dx = maxX - minX
        val dy = maxY - minY
        val zx = if (dx > 0) log2(w / (dx * tileSize)) else maxZoom
        val zy = if (dy > 0) log2(h / (dy * tileSize)) else maxZoom
        val zoom = minOf(zx, zy, maxZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return MapView(zoom, (minX + maxX) / 2, (minY + maxY) / 2)
    }

    /** Size of the whole world in pixels at [zoom]. */
    fun worldSize(zoom: Double, tileSize: Double): Double = tileSize * 2.0.pow(zoom)

    /** Where a point falls on screen. */
    fun toScreen(longitude: Double, latitude: Double, view: MapView, width: Double, height: Double, tileSize: Double): Pair<Double, Double> {
        val world = worldSize(view.zoom, tileSize)
        return Pair(
            (WebMercator.x(longitude) - view.centerX) * world + width / 2,
            (WebMercator.y(latitude) - view.centerY) * world + height / 2
        )
    }

    /** Moves the map by a finger drag of ([dx], [dy]) pixels. */
    fun pan(view: MapView, dx: Double, dy: Double, tileSize: Double): MapView {
        val world = worldSize(view.zoom, tileSize)
        return view.copy(centerX = (view.centerX - dx / world).coerceIn(0.0, 1.0), centerY = (view.centerY - dy / world).coerceIn(0.0, 1.0))
    }

    fun zoomBy(view: MapView, factor: Double): MapView =
        view.copy(zoom = (view.zoom + log2(factor)).coerceIn(MIN_ZOOM, MAX_ZOOM))

    /**
     * The tiles that cover the map: of the whole zoom level below [MapView.zoom], scaled up by the
     * fraction. Columns wrap around the world; rows outside it are left out.
     */
    fun tiles(view: MapView, width: Double, height: Double, tileSize: Double): List<TilePlacement> {
        val z = floor(view.zoom).toInt().coerceIn(0, MAX_ZOOM.toInt())
        val size = tileSize * 2.0.pow(view.zoom - z)
        val count = 1 shl z
        val world = size * count
        val left = view.centerX * world - width / 2
        val top = view.centerY * world - height / 2
        val firstX = floor(left / size).toInt()
        val lastX = floor((left + width) / size).toInt()
        val firstY = floor(top / size).toInt().coerceAtLeast(0)
        val lastY = floor((top + height) / size).toInt().coerceAtMost(count - 1)
        val out = ArrayList<TilePlacement>()
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                val wrapped = ((tx % count) + count) % count
                out += TilePlacement(z, wrapped, ty, tx * size - left, ty * size - top, size)
            }
        }
        return out
    }
}
