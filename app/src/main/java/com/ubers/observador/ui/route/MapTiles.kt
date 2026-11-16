package com.ubers.observador.ui.route

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ubers.observador.domain.route.OfflineMap
import com.ubers.observador.domain.route.TilePlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 1.58.0: the tiles of a map view, loaded once each; a missing one is taken from a wider saved tile. */
class MapTileState(val tiles: SnapshotStateMap<String, TileStore.Drawn>) {
    var missing by mutableIntStateOf(0)
    val wider: Boolean get() = tiles.values.any { it.wider }
}

@Composable
fun rememberMapTiles(placements: List<TilePlacement>): MapTileState {
    val context = LocalContext.current
    val state = remember { MapTileState(mutableStateMapOf()) }
    val scope = rememberCoroutineScope()
    val requested = remember { mutableSetOf<String>() }
    val keys = placements.map { it.key }.toSet()
    LaunchedEffect(keys) {
        if (state.tiles.size > 40) state.tiles.keys.filter { it !in keys }.forEach { state.tiles.remove(it); requested.remove(it) }
        placements.forEach { t ->
            val have = state.tiles[t.key]
            if ((have != null && !have.wider) || !requested.add(t.key)) return@forEach
            TileStore.cached(t.key)?.let { state.tiles[t.key] = TileStore.Drawn(it); return@forEach }
            scope.launch {
                val drawn = TileStore.loadOrWider(context, t.z, t.x, t.y)
                if (drawn != null) state.tiles[t.key] = drawn else state.missing++
                // A wider stand-in is asked for again next time the view changes (internet may be back)
                if (drawn == null || drawn.wider) requested.remove(t.key)
            }
        }
    }
    return state
}

fun DrawScope.drawMapTiles(placements: List<TilePlacement>, state: MapTileState) {
    placements.forEach { t ->
        val d = state.tiles[t.key] ?: return@forEach
        drawImage(
            image = d.image,
            srcOffset = IntOffset(d.srcLeft, d.srcTop),
            srcSize = IntSize(d.srcSize, d.srcSize),
            dstOffset = IntOffset(t.left.roundToInt(), t.top.roundToInt()),
            // One pixel more, so no seam shows between scaled tiles
            dstSize = IntSize(t.size.roundToInt() + 1, t.size.roundToInt() + 1)
        )
    }
}

/**
 * «Guardar esta zona»: the visible area at this zoom and up to two closer ones, for use without
 * internet. Refused (not cut down) when it would pass OpenStreetMap's 250-tile limit.
 */
@Composable
fun SaveZoneButton(placements: List<TilePlacement>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<OfflineMap.Plan?>(null) }
    var done by remember { mutableIntStateOf(-1) }
    var result by remember { mutableStateOf<String?>(null) }
    Surface(onClick = {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { TileStore.savedKeys(context) }
            plan = OfflineMap.prefetchPlan(placements.map { OfflineMap.Tile(it.z, it.x, it.y) }.distinct(), saved)
            done = -1; result = null
        }
    }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Text("Guardar esta zona", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
    val p = plan ?: return
    AlertDialog(onDismissRequest = { if (done < 0 || result != null) plan = null }, title = { Text("Guardar esta zona") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (p) {
                    is OfflineMap.Plan.TooBig -> Text("La zona visible necesita ${p.needed} partes del mapa y OpenStreetMap no permite descargar más de ${OfflineMap.MAX_PREFETCH} de una vez. Acerca el mapa y vuelve a intentarlo.")
                    is OfflineMap.Plan.Ok -> if (p.tiles.isEmpty()) Text("Esta zona ya está guardada.") else {
                        Text("Se descargan ${p.tiles.size} partes del mapa (zoom ${p.zooms.first}–${p.zooms.last}) para verlas sin internet. Usa datos o Wi-Fi una sola vez.")
                        if (done >= 0) LinearProgressIndicator(progress = { done.toFloat() / p.tiles.size }, modifier = Modifier.fillMaxWidth())
                    }
                }
                result?.let { Text(it) }
            }
        },
        confirmButton = {
            if (p is OfflineMap.Plan.Ok && p.tiles.isNotEmpty() && done < 0) TextButton(onClick = {
                done = 0
                scope.launch {
                    val ok = TileStore.prefetch(context, p.tiles) { done = it }
                    result = if (ok == p.tiles.size) "Zona guardada." else "Se guardaron $ok de ${p.tiles.size}; sin internet no se pudo el resto."
                }
            }) { Text("Descargar") }
            else TextButton(enabled = done < 0 || result != null, onClick = { plan = null }) { Text("Cerrar") }
        },
        dismissButton = { if (p is OfflineMap.Plan.Ok && p.tiles.isNotEmpty() && done < 0) TextButton(onClick = { plan = null }) { Text("Cancelar") } })
}

/** 1.58.0: Ajustes › «Mapa sin internet»: how much map to keep, what is kept, and emptying it. */
@Composable
fun OfflineMapCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var budget by remember { mutableIntStateOf(TileStore.budgetMb(context)) }
    var stats by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { stats = withContext(Dispatchers.IO) { TileStore.stats(context) } }
    com.ubers.observador.ui.components.SectionCard(title = "Mapa sin internet", icon = com.ubers.observador.R.drawable.ic_two_wheeler,
        subtitle = stats?.let { OfflineMap.status(it.first, it.second, budget) } ?: "Calculando…") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Las partes del mapa que ves se guardan en el teléfono y se usan sin datos. Cuando se llena el espacio, salen primero las que menos usas. " +
                "Si falta una parte, se muestra ampliada la de un zoom más lejano. En cualquier mapa, «Guardar esta zona» descarga la zona visible.",
                style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfflineMap.BUDGETS_MB.forEach { mb ->
                    com.ubers.observador.ui.components.AppFilterChip(selected = budget == mb, onClick = {
                        budget = mb; TileStore.setBudgetMb(context, mb)
                    }, label = { Text("$mb MB") })
                }
            }
            Text("El mapa no es parte de tus datos: no va en los respaldos y se puede volver a descargar. Tus recorridos se dibujan siempre, con o sin mapa.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirmClear = true }) { Text("Vaciar mapa guardado") }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Vaciar mapa guardado") },
        text = { Text("Se borran solo las partes del mapa guardadas (no tus viajes ni tus recorridos). Se volverán a descargar al ver los mapas con internet.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch { withContext(Dispatchers.IO) { TileStore.clear(context) }; version++ }
        }) { Text("Vaciar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
}
