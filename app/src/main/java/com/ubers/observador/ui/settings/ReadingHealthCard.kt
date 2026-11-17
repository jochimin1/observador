package com.ubers.observador.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.health.UberWatch
import com.ubers.observador.domain.health.VersionHealth
import com.ubers.observador.domain.sectors.LearnedX
import com.ubers.observador.service.LearnedXStore
import com.ubers.observador.service.UberWatchStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 1.61.0: «Salud de lectura por versión de Uber»: how each Uber version installed on this phone is read. */
@Composable
fun ReadingHealthCard() {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<Pair<VersionHealth, String?>>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        rows = withContext(Dispatchers.IO) {
            val file = java.io.File(java.io.File(context.filesDir, "cierres"), LearnedX.FILE)
            val text = try { file.takeIf { it.isFile }?.readText().orEmpty() } catch (e: Exception) { "" }
            UberWatch.health(UberWatchStore.all(context)).map { h ->
                val x = when (val s = LearnedX.state(text, h.version)) {
                    is LearnedX.State.Learned -> "aprendida"
                    is LearnedX.State.Learning -> if (s.agreeing == 0) "sin aprender" else "aprendiendo (${s.agreeing} de ${LearnedX.REQUIRED})"
                }
                h to x
            }
        }
    }
    val installed = remember(refresh) { LearnedXStore.uberVersion(context) }
    val fmt = DateTimeFormatter.ofPattern("d/M/yyyy")
    com.ubers.observador.ui.components.SectionCard(title = "Salud de lectura por versión de Uber", icon = R.drawable.ic_monitor_heart,
        subtitle = installed?.let { "Uber instalada: $it" } ?: "Uber no está instalada o no se puede leer su versión") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cada oferta leída, cada tarjeta que no se pudo leer y cada total de dinero se anotan con la versión de Uber de ese momento. " +
                "Cuando Uber se actualiza, Observador te avisa, vigila las primeras ${UberWatch.WATCH_OFFERS} ofertas y vuelve a confirmar la X y el dinero.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val list = rows
            when {
                list == null -> Text("Calculando…", style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text("Aún no hay lecturas anotadas con esta versión de Observador.", style = MaterialTheme.typography.bodySmall)
                else -> list.forEach { (h, x) ->
                    val since = fmt.format(Instant.ofEpochMilli(h.sinceMs).atZone(ZoneId.systemDefault()))
                    val bad = (h.unreadablePct ?: 0) >= UberWatch.BAD_PCT && h.unreadable >= UberWatch.MIN_BAD
                    Text("Desde $since · " + UberWatch.describe(h, x), style = MaterialTheme.typography.bodySmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
            TextButton(onClick = { refresh++ }) { Text("Actualizar") }
        }
    }
}

/** 1.61.0: «Revisión de la semana»: on/off, the day, and «Ver ahora». */
@Composable
fun WeeklyReviewCard() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var on by remember { mutableStateOf(com.ubers.observador.service.WeeklyReview.enabled(context)) }
    var day by remember { mutableStateOf(com.ubers.observador.service.WeeklyReview.day(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    com.ubers.observador.ui.components.SectionCard(title = "Revisión de la semana", icon = R.drawable.ic_flag,
        subtitle = if (on) "Cada ${dayName(day)} desde las ${com.ubers.observador.domain.reports.WeekReview.HOUR}:00" else "Apagada",
        action = { androidx.compose.material3.Switch(checked = on, onCheckedChange = {
            on = it; com.ubers.observador.service.WeeklyReview.setEnabled(context, it)
        }) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Una notificación con la ganancia de la semana, las horas en línea, los cierres de sectores, el estado de las gomas " +
                "y lo que quede por revisar. Solo resume: no cambia ni borra nada.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (on) androidx.compose.foundation.layout.Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                java.time.DayOfWeek.entries.forEach { d ->
                    com.ubers.observador.ui.components.AppFilterChip(selected = d == day, onClick = {
                        day = d; com.ubers.observador.service.WeeklyReview.setDay(context, d)
                    }, label = { Text(dayName(d).take(3)) })
                }
            }
            TextButton(onClick = {
                scope.launch {
                    message = withContext(Dispatchers.IO) {
                        runCatching {
                            val (_, f) = com.ubers.observador.service.WeeklyReview.figures(context)
                            if (com.ubers.observador.service.WeeklyReview.notify(context.applicationContext, f)) "Revisión enviada a las notificaciones."
                            else "Las notificaciones de Observador están desactivadas."
                        }.getOrElse { "No se pudo calcular: ${it.message}" }
                    }
                }
            }) { Text("Ver ahora") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun dayName(d: java.time.DayOfWeek): String =
    d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("es-DO"))
