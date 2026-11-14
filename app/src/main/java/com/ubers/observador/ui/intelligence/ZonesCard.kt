package com.ubers.observador.ui.intelligence

import android.app.Application
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.RouteRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.route.E7
import com.ubers.observador.domain.route.MapMath
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.trips.TripCenter
import com.ubers.observador.domain.trips.TripOutcome
import com.ubers.observador.domain.zones.ZoneCell
import com.ubers.observador.domain.zones.ZonePoints
import com.ubers.observador.domain.zones.ZoneRating
import com.ubers.observador.domain.zones.ZoneReport
import com.ubers.observador.domain.zones.ZoneSettings
import com.ubers.observador.domain.zones.ZoneStats
import com.ubers.observador.domain.zones.ZoneSummary
import com.ubers.observador.domain.zones.ZoneTrip
import com.ubers.observador.domain.zones.ZoneGrid
import com.ubers.observador.ui.components.AppFilterChip
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.ui.route.TileStore
import com.ubers.observador.ui.theme.appFieldColors
import com.ubers.observador.ui.theme.appFieldShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class ZonePeriod(val label: String, val days: Int) { WEEK("7 días", 7), MONTH("30 días", 30), QUARTER("90 días", 90) }

data class ZonesState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val period: ZonePeriod = ZonePeriod.MONTH,
    val report: ZoneReport? = null,
    val settings: ZoneSettings = ZoneSettings()
)

/**
 * 1.47.0: zones measured with the GPS of the trips taken. Read from the stored routes, money
 * summaries and trips each time; nothing new is stored but the names and the private cells.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZonesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val trips = TripRepository(db)
    private val money = FinancialRepository(db)
    private val routes = RouteRepository(db)
    private val settings = AppSettings(app)
    private val period = MutableStateFlow(ZonePeriod.MONTH)

    val state: StateFlow<ZonesState> = period.flatMapLatest { p ->
        val since = System.currentTimeMillis() - p.days * 24L * 60 * 60 * 1000
        combine(trips.observeCreatedSince(since), settings.zoneSettings, settings.showTestData) { list, zs, test ->
            Triple(p, TestData.filter(list, test) { it.id }, zs)
        }
    }.mapLatest { (p, list, zs) ->
        try {
            val done = list.filter { TripOutcome.endedAt(it) != null }
            val ids = done.map { it.id }
            val points = routes.pointsOf(ids)
            val rows = TripCenter.rows(done, money.summariesFor(ids), emptySet(), Currencies.DOP).associateBy { it.id }
            val zoneTrips = done.map { t ->
                val pts = points[t.id].orEmpty().sortedBy { it.seq }
                val row = rows.getValue(t.id)
                ZoneTrip(
                    tripId = t.id, startAt = row.startAt, endAt = TripOutcome.endedAt(t),
                    acceptedAt = t.acceptedAt, arrivedAt = t.arrivedAt, tripStartedAt = t.tripStartedAt,
                    pickup = ZonePoints.pickup(pts), dropoff = ZonePoints.dropoff(pts), routeStart = pts.firstOrNull(),
                    realMinor = row.real?.minorUnits, pickupAddress = t.offer?.pickupAddress
                )
            }
            ZonesState(false, false, p, ZoneStats.report(zoneTrips, zs), zs)
        } catch (e: Exception) {
            Log.e("ZonesViewModel", "Zones not computed", e)
            ZonesState(loading = false, failed = true, period = p, settings = zs)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ZonesState())

    fun setPeriod(p: ZonePeriod) { period.value = p }

    fun name(zone: ZoneSummary, name: String) = viewModelScope.launch { settings.nameZone(zone.cells.map { it.id }, name) }

    fun hide(zone: ZoneSummary) = viewModelScope.launch { settings.setZonePrivate(zone.cells.map { it.id }, true) }

    fun showAgain(cellIds: List<String>) = viewModelScope.launch { settings.setZonePrivate(cellIds, false) }
}

private object ZoneColors {
    val high = Color(0xFF2E7D32)
    val mid = Color(0xFFF9A825)
    val low = Color(0xFFC62828)
    val few = Color(0xFF9E9E9E)

    fun of(r: ZoneRating): Color = when (r) {
        ZoneRating.HIGH -> high
        ZoneRating.MID -> mid
        ZoneRating.LOW -> low
        ZoneRating.FEW -> few
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es-DO"))

private fun perHour(v: Double): String = "RD$" + String.format(Locale.US, "%,.0f", v) + "/h"

private fun minutes(v: Double): String = "${v.roundToInt()} min"

/** Shown in Análisis: the map and list of the zones where the driver picked up and dropped off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZonesCard(viewModel: ZonesViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var open by remember { mutableStateOf<ZoneSummary?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var privateList by remember { mutableStateOf(false) }
    val report = state.report

    SectionCard(
        title = "Zonas de trabajo (GPS)",
        icon = R.drawable.ic_layers,
        subtitle = report?.let { "${it.tripsWithGps} viajes con GPS · ${state.period.label}" }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZonePeriod.entries.forEach { p ->
                    AppFilterChip(selected = state.period == p, onClick = { viewModel.setPeriod(p) }, label = { Text(p.label) })
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.failed || report == null -> Text("No se pudieron calcular las zonas. Tus datos no se tocaron.", style = MaterialTheme.typography.bodyMedium)
                report.zones.isEmpty() -> Text(
                    "Todavía no hay viajes terminados con recorrido GPS en este período. Para medirlas, activa «Recorrido de los viajes» en Ajustes.",
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> {
                    ZoneMap(report.zones, selected = open?.key)
                    Legend(report.medianPerHour)
                    val shown = if (showAll) report.zones else report.zones.take(8)
                    shown.forEachIndexed { i, z ->
                        if (i > 0) HorizontalDivider()
                        ZoneRow(z, onClick = { open = z })
                    }
                    if (report.zones.size > 8) {
                        TextButton(onClick = { showAll = !showAll }) {
                            Text(if (showAll) "Ver menos" else "Ver las ${report.zones.size} zonas")
                        }
                    }
                }
            }
            if (state.settings.privateCells.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.settings.privateCells.size} " + (if (state.settings.privateCells.size == 1) "zona privada oculta" else "zonas privadas ocultas"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { privateList = true }) { Text("Revisar") }
                }
            }
            report?.takeIf { it.tripsWithoutGps > 0 }?.let {
                Text(
                    "${it.tripsWithoutGps} viajes del período no tienen punto de recogida GPS y no cuentan aquí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Es tu historial de viajes tomados, con el GPS de cada recorrido: no es una medición de seguridad ni una " +
                    "promesa de que la zona seguirá rindiendo igual. Las ofertas que no tomaste no tienen GPS. Una zona sin " +
                    "viajes no está observada, no es mala. Con menos de ${ZoneStats.MIN_TRIPS} viajes con dinero confirmado no se clasifica.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    open?.let { z ->
        ZoneDialog(
            zone = z,
            onName = { viewModel.name(z, it); open = null },
            onHide = { viewModel.hide(z); open = null },
            onDismiss = { open = null }
        )
    }
    if (privateList) {
        AlertDialog(
            onDismissRequest = { privateList = false },
            title = { Text("Zonas privadas") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "No aparecen en el mapa ni en la lista. Sus viajes siguen guardados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.settings.privateCells.sorted().forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.settings.names[id] ?: ZoneGrid.parse(id)?.label ?: id,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.showAgain(listOf(id)) }) { Text("Mostrar") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { privateList = false }) { Text("Cerrar") } }
        )
    }
}

@Composable
private fun Legend(median: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(ZoneColors.high); Text("Mejor", style = MaterialTheme.typography.labelSmall)
            Dot(ZoneColors.mid); Text("Media", style = MaterialTheme.typography.labelSmall)
            Dot(ZoneColors.low); Text("Peor", style = MaterialTheme.typography.labelSmall)
            Dot(ZoneColors.few); Text("Pocos datos", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            median?.let { "Comparado con tu mediana de ${perHour(it)} por zona (±15 %)." } ?: "Ninguna zona tiene todavía viajes suficientes para compararla.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ZoneRow(z: ZoneSummary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(ZoneColors.of(z.rating))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(z.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(headline(z), style = MaterialTheme.typography.bodySmall)
            details(z).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** «RD$412/h · 9 viajes» or «Pocos datos · 3 viajes (2 con dinero confirmado)». */
private fun headline(z: ZoneSummary): String {
    val trips = "${z.pickups} " + if (z.pickups == 1) "recogida" else "recogidas"
    val ph = z.perHour
    return if (z.enough && ph != null) "${perHour(ph)} · $trips"
    else "Pocos datos · $trips (${z.confirmed} con dinero confirmado)"
}

private fun details(z: ZoneSummary): String = listOfNotNull(
    z.avgPickupMin?.let { "recogida ${minutes(it)}" },
    z.avgWaitMin?.let { "espera ${minutes(it)}" },
    z.avgNextOfferMin?.let { "tras dejar aquí, siguiente viaje en ${minutes(it)}" + (z.avgEmptyKm?.let { km -> String.format(Locale.US, " a %.1f km en línea recta", km) } ?: "") },
    "último ${DAY.format(Instant.ofEpochMilli(z.lastAt).atZone(ZoneId.systemDefault()))}"
).joinToString(" · ")

@Composable
private fun ZoneDialog(zone: ZoneSummary, onName: (String) -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(zone.name ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(zone.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(headline(zone), style = MaterialTheme.typography.bodyMedium)
                Text(details(zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (zone.dropoffs > 0) {
                    Text(
                        "${zone.dropoffs} viajes terminaron aquí; ${zone.nextOffers} siguieron con otro viaje antes de una hora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                zone.uberSays?.let {
                    Text("Uber suele escribir: «$it» (texto de la oferta, no GPS).", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (zone.cells.size == 1) "Una celda de unos 1,5 km." else "${zone.cells.size} celdas con el mismo nombre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ZoneSettings.MAX_NAME) },
                    label = { Text("Nombre de la zona") },
                    placeholder = { Text(zone.uberSays ?: "Ej.: Megacentro") },
                    supportingText = { Text("Varias celdas con el mismo nombre se suman como una zona.") },
                    singleLine = true,
                    colors = appFieldColors(),
                    shape = appFieldShape(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = onHide, modifier = Modifier.fillMaxWidth()) {
                    Text("Ocultar como zona privada (por ejemplo, tu casa)")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onName(name) }) { Text(if (name.isBlank() && zone.name != null) "Quitar nombre" else "Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** A corner of a cell as a point MapMath can fit. */
private fun corner(lat: Double, lng: Double) = RoutePoint(0, 0L, E7.of(lat), E7.of(lng), 0, RoutePhase.UNKNOWN)

/** The zones over the street map, coloured by their rating; same tiles and gestures as the route map. */
@Composable
private fun ZoneMap(zones: List<ZoneSummary>, selected: String?) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val tilePx = 256.0 * maxOf(1.0, density / 1.5)
    val cells: List<Pair<ZoneCell, ZoneSummary>> = remember(zones) { zones.flatMap { z -> z.cells.map { it to z } } }
    val corners = remember(cells) { cells.flatMap { (c, _) -> listOf(corner(c.south, c.west), corner(c.north, c.east)) } }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clipToBounds()
            .semantics { contentDescription = "Mapa de tus zonas de trabajo" }
    ) {
        val w = constraints.maxWidth.toDouble()
        val h = constraints.maxHeight.toDouble()
        val fitted = remember(corners, w, h) { MapMath.fit(corners, w, h, tilePx, 20.0 * density, maxZoom = 15.0) }
        var view by remember(fitted) { mutableStateOf(fitted) }
        val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
        var missing by remember { mutableIntStateOf(0) }
        val current = view
        val placements = remember(current, w, h) { current?.let { MapMath.tiles(it, w, h, tilePx) }.orEmpty() }
        val keys = placements.map { it.key }.toSet()
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
            Modifier.fillMaxSize().pointerInput(fitted) {
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
                    dstSize = IntSize(t.size.roundToInt() + 1, t.size.roundToInt() + 1)
                )
            }
            val v = current ?: return@Canvas
            cells.forEach { (c, z) ->
                val (x1, y1) = MapMath.toScreen(c.west, c.north, v, w, h, tilePx)
                val (x2, y2) = MapMath.toScreen(c.east, c.south, v, w, h, tilePx)
                val color = ZoneColors.of(z.rating)
                val topLeft = Offset(x1.toFloat(), y1.toFloat())
                val size = Size((x2 - x1).toFloat(), (y2 - y1).toFloat())
                drawRect(color.copy(alpha = 0.40f), topLeft, size)
                drawRect(
                    if (z.key == selected) Color.Black else color,
                    topLeft, size,
                    style = Stroke(if (z.key == selected) 3.dp.toPx() else 1.dp.toPx())
                )
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    "Sin internet: solo las zonas",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF333333),
                    modifier = Modifier.background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Text(
                "© OpenStreetMap",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF333333),
                modifier = Modifier.background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
