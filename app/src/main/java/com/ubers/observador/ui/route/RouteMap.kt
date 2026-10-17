package com.ubers.observador.ui.route

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.route.MapMath
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.RouteSimplifier
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Colours of the route: pickup, with passenger, not told apart, and the two ends. */
object RouteColors {
    val pickup = Color(0xFFF57C00)
    val passenger = Color(0xFF1565C0)
    val unknown = Color(0xFF6D4C9F)
    val start = Color(0xFF2E7D32)
    val end = Color(0xFFC62828)

    fun of(phase: RoutePhase): Color = when (phase) {
        RoutePhase.PICKUP -> pickup
        RoutePhase.PASSENGER -> passenger
        RoutePhase.UNKNOWN -> unknown
    }
}

/**
 * The route on a street map (Fase 8K): OpenStreetMap tiles under the trace, pickup in orange and
 * the trip in blue, start in green and end in red. Drag to move, pinch or the buttons to zoom,
 * «Ver todo» to fit it again. Without internet the trace is drawn on a plain background.
 */
@Composable
fun RouteMap(points: List<RoutePoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // Tiles are 256 px; on dense screens they are drawn larger so street names stay readable
    val tilePx = 256.0 * maxOf(1.0, density / 1.5)
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val outline = Color.White
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(surface)
            .clipToBounds()
            .semantics { contentDescription = "Mapa del recorrido del viaje" }
    ) {
        val w = constraints.maxWidth.toDouble()
        val h = constraints.maxHeight.toDouble()
        val padding = 28.0 * density
        val fitted = remember(points, w, h) { MapMath.fit(points, w, h, tilePx, padding) }
        var view by remember(fitted) { mutableStateOf(fitted) }
        val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
        var missing by remember { mutableIntStateOf(0) }
        val current = view
        val placements = remember(current, w, h) { current?.let { MapMath.tiles(it, w, h, tilePx) }.orEmpty() }
        val keys = placements.map { it.key }.toSet()

        // Loads outlive a change of view (no tile is fetched twice); old tiles are let go
        val scope = rememberCoroutineScope()
        val requested = remember { mutableSetOf<String>() }
        LaunchedEffect(keys) {
            if (tiles.size > 40) tiles.keys.filter { it !in keys }.forEach { tiles.remove(it); requested.remove(it) }
            placements.forEach { t ->
                if (t.key in tiles || !requested.add(t.key)) return@forEach
                TileStore.cached(t.key)?.let { tiles[t.key] = it; return@forEach }
                scope.launch {
                    val bmp = TileStore.load(context, t.z, t.x, t.y)
                    if (bmp != null) {
                        tiles[t.key] = bmp
                    } else {
                        missing++
                        requested.remove(t.key)
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(fitted) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val v = view ?: return@detectTransformGestures
                        view = MapMath.zoomBy(MapMath.pan(v, pan.x.toDouble(), pan.y.toDouble(), tilePx), zoom.toDouble())
                    }
                }
        ) {
            placements.forEach { t ->
                val img = tiles[t.key] ?: return@forEach
                drawImage(
                    image = img,
                    dstOffset = IntOffset(t.left.roundToInt(), t.top.roundToInt()),
                    // One pixel more, so no seam shows between scaled tiles
                    dstSize = IntSize(t.size.roundToInt() + 1, t.size.roundToInt() + 1)
                )
            }
            val v = current ?: return@Canvas
            fun at(p: RoutePoint): Offset {
                val (x, y) = MapMath.toScreen(p.longitude, p.latitude, v, w, h, tilePx)
                return Offset(x.toFloat(), y.toFloat())
            }
            val width = 5.dp.toPx()
            RouteSimplifier.runsOf(points).forEach { run ->
                if (run.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(at(run[0]).x, at(run[0]).y)
                    run.drop(1).forEach { p -> at(p).let { lineTo(it.x, it.y) } }
                }
                drawPath(path, outline, style = Stroke(width + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, RouteColors.of(run[0].phase), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // Phases join where they change
            points.zipWithNext().filter { (a, b) -> a.phase != b.phase }.forEach { (a, b) ->
                drawLine(RouteColors.of(b.phase), at(a), at(b), width, StrokeCap.Round)
            }
            listOfNotNull(points.firstOrNull()?.let { it to RouteColors.start }, points.lastOrNull()?.let { it to RouteColors.end })
                .forEach { (p, color) ->
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = at(p))
                    drawCircle(color, radius = 6.5.dp.toPx(), center = at(p))
                }
        }

        Column(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalIconButton(onClick = { view = view?.let { MapMath.zoomBy(it, 2.0) } }, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Acercar")
            }
            FilledTonalIconButton(onClick = { view = view?.let { MapMath.zoomBy(it, 0.5) } }, modifier = Modifier.size(36.dp)) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = "Alejar")
            }
        }
        if (view != fitted) {
            Surface(
                onClick = { view = fitted },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Text("Ver todo", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
            if (missing > 0 && tiles.isEmpty()) {
                Text(
                    "Sin internet: solo el trazo",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color(0xFF333333)
                )
            }
            // Required by OpenStreetMap's tile usage policy
            Text(
                "© OpenStreetMap",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF333333),
                modifier = Modifier.background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
