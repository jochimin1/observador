package com.ubers.observador.ui.efficiency

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.DistanceRepository
import com.ubers.observador.data.OfferRepository
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.TestData
import com.ubers.observador.domain.efficiency.EffAcc
import com.ubers.observador.domain.efficiency.EffOffer
import com.ubers.observador.domain.efficiency.EffTrip
import com.ubers.observador.domain.efficiency.Efficiency
import com.ubers.observador.domain.efficiency.EfficiencyReport
import com.ubers.observador.domain.efficiency.TimeUse
import com.ubers.observador.domain.money.Currencies
import com.ubers.observador.domain.online.TimeSpan
import com.ubers.observador.domain.trips.TripCenter
import com.ubers.observador.domain.trips.TripOutcome
import com.ubers.observador.ui.components.AppFilterChip
import com.ubers.observador.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.util.Locale

/** Whole weeks, so a period and the one before it have the same weekdays. */
enum class EffPeriod(val label: String, val days: Int) { WEEK("7 días", 7), FOUR_WEEKS("4 semanas", 28), TWELVE_WEEKS("12 semanas", 84) }

enum class EffTab(val label: String) { MARKET("Mercado"), TAKEN("Tomadas"), RESULT("Resultado"), TIME("Tiempo") }

data class EfficiencyState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val period: EffPeriod = EffPeriod.FOUR_WEEKS,
    val current: EfficiencyReport? = null,
    /** The same number of days just before, for the totals only. */
    val previous: EfficiencyReport? = null
)

/** 1.48.0: reads sessions, offers, trips, money and km of the period and the one before; stores nothing. */
@OptIn(ExperimentalCoroutinesApi::class)
class EfficiencyViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val online = OnlineRepository(db)
    private val offers = OfferRepository(db)
    private val distances = DistanceRepository(db)
    private val settings = AppSettings(app)
    private val period = MutableStateFlow(EffPeriod.FOUR_WEEKS)

    val state: StateFlow<EfficiencyState> = combine(period, settings.showTestData) { p, test -> p to test }
        .mapLatest { (p, test) ->
            try {
                load(p, test)
            } catch (e: Exception) {
                Log.e("EfficiencyViewModel", "Efficiency not computed", e)
                EfficiencyState(loading = false, failed = true, period = p)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EfficiencyState())

    fun setPeriod(p: EffPeriod) { period.value = p }

    private suspend fun load(p: EffPeriod, test: Boolean): EfficiencyState {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val len = p.days * 24L * 3_600_000L
        val from = now - len
        val prevFrom = from - len
        val sessions = TestData.filter(online.getBetween(prevFrom, now), test) { it.id }
        val inputs = online.metricsInputs(prevFrom, now)
        val trips = TestData.filter(inputs.trips, test) { it.id }
        val rows = TripCenter.rows(trips, inputs.money, emptySet(), Currencies.DOP).associateBy { it.id }
        val effTrips = trips.map { t ->
            val o = t.offer
            val minutes = o?.let { it.pickupMinutes + it.tripMinutes } ?: 0
            EffTrip(
                id = t.id,
                acceptedAt = t.acceptedAt,
                arrivedAt = t.arrivedAt,
                tripStartedAt = t.tripStartedAt,
                endAt = TripOutcome.endedAt(t) ?: t.cancelledAt ?: t.interruptedAt,
                counted = TripOutcome.endedAt(t) != null,
                offerSeenAt = o?.offerSeenAt,
                offeredRdpPerHour = if (o != null && minutes > 0) o.offeredAmount / (minutes / 60.0) else null,
                product = o?.product,
                realMinor = rows[t.id]?.real?.minorUnits
            )
        }
        val effOffers = TestData.filter(offers.getOffersSince(prevFrom), test) { it.offerHash }
            .filterNot { it.excludedFromAnalysis }
            .map { EffOffer(it.detectedAt, it.rdpPerHour, it.rdpPerKm, it.pickupKm, it.product) }
        val segments = TestData.filter(distances.getSince(prevFrom), test) { it.id }
        val current = Efficiency.report(TimeSpan(from, now), zone, now, sessions, effOffers, effTrips, segments)
        val previous = Efficiency.report(TimeSpan(prevFrom, from), zone, now, sessions, effOffers, effTrips, segments, withSuggestions = false)
        return EfficiencyState(false, false, p, current, previous)
    }
}

private object EffColors {
    val searching = Color(0xFF9E9E9E)
    val pickup = Color(0xFFF57C00)
    val waiting = Color(0xFFFBC02D)
    val passenger = Color(0xFF1565C0)
    val unread = Color(0xFFCE93D8)
    val offline = Color(0xFFE0E0E0)

    fun of(u: TimeUse): Color = when (u) {
        TimeUse.SEARCHING -> searching
        TimeUse.PICKUP -> pickup
        TimeUse.WAITING -> waiting
        TimeUse.PASSENGER -> passenger
    }
}

private fun hours(ms: Long): String {
    val h = ms / 3_600_000.0
    return if (h >= 10) "${Math.round(h)} h" else String.format(Locale.US, "%.1f h", h)
}

private fun rd(v: Double): String = "RD$" + String.format(Locale.US, "%,.0f", v)
private fun pct(v: Double): String = "${Math.round(v * 100)} %"
private fun km(mm: Long): String = String.format(Locale.US, "%.1f km", mm / 1_000_000.0)
private fun slotLabel(k: Int): String = "%02d–%02d".format(k * Efficiency.SLOT_HOURS, k * Efficiency.SLOT_HOURS + Efficiency.SLOT_HOURS)

private val SHORT = mapOf(TimeUse.SEARCHING to "buscando", TimeUse.PICKUP to "recogida", TimeUse.WAITING to "espera", TimeUse.PASSENGER to "con pasajero")

private val DAY_LABELS = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

/** 1.48.0: «Horarios y eficiencia», opened from Análisis and from «Mejores horas» in Inicio. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EfficiencyScreen(onBack: () -> Unit, viewModel: EfficiencyViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(EffTab.MARKET) }

    Scaffold(
        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        topBar = {
            TopAppBar(
                colors = com.ubers.observador.ui.theme.topBarColors(),
                title = { Text("Horarios y eficiencia") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Volver") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EffPeriod.entries.forEach { p ->
                    AppFilterChip(selected = state.period == p, onClick = { viewModel.setPeriod(p) }, label = { Text(p.label) })
                }
            }
            val r = state.current
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.failed || r == null -> Text("No se pudo calcular. Tus datos no se tocaron.")
                else -> {
                    ObservedCard(r)
                    if (r.suggestions.isNotEmpty()) SuggestionsCard(r)
                    SectionCard(title = "Por franja de 2 horas", icon = R.drawable.ic_schedule) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                EffTab.entries.forEach { t ->
                                    AppFilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                                }
                            }
                            Text(tabNote(tab), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (tab == EffTab.TIME) UsesLegend()
                            r.slots.forEachIndexed { k, s ->
                                if (k > 0) HorizontalDivider()
                                SlotRow(k, s, tab)
                            }
                        }
                    }
                    if (tab == EffTab.TIME) KmCard(r)
                    ComparisonsCard(r)
                    state.previous?.let { PreviousCard(r, it, state.period) }
                    Text(
                        "Es lo que Observador vio en este período, no una promesa de que una franja o un día seguirá rindiendo igual. " +
                            "El precio ofertado nunca se cuenta como ganancia: el resultado usa solo el dinero confirmado. " +
                            "Las franjas son horas del reloj; los días, de medianoche a medianoche.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

private fun tabNote(tab: EffTab): String = when (tab) {
    EffTab.MARKET -> "Todas las ofertas vistas, por hora conectado y leyendo. Precios ofertados, no ganancias."
    EffTab.TAKEN -> "De las ofertas vistas, cuántas tomaste y qué ofrecían."
    EffTab.RESULT -> "Dinero confirmado de los viajes terminados, por hora conectado y leyendo."
    EffTab.TIME -> "En qué se fue el tiempo conectado y leyendo. Las partes suman el total; las pausas cuentan como buscando."
}

@Composable
private fun ObservedCard(r: EfficiencyReport) {
    val t = r.total
    SectionCard(title = "Tiempo observado", icon = R.drawable.ic_timer) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Bar(listOf(t.readingMs to EffColors.passenger, t.unreadMs to EffColors.unread, t.offlineMs to EffColors.offline))
            Legend(EffColors.passenger, "Conectado y leyendo ${hours(t.readingMs)}")
            Legend(EffColors.unread, "Conectado sin lectura ${hours(t.unreadMs)}")
            Legend(EffColors.offline, "Desconectado ${hours(t.offlineMs)}")
            Text(
                "«0 ofertas» solo se dice en horas conectado y leyendo. Sin lectura o desconectado es «no observado».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SuggestionsCard(r: EfficiencyReport) {
    SectionCard(title = "Dónde se perdió tiempo o dinero", icon = R.drawable.ic_lightbulb) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.suggestions.forEach { s ->
                Column {
                    Text(s.text, style = MaterialTheme.typography.bodyMedium)
                    Text(s.basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Solo lo observado en este período. No cambia ningún ajuste.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Bar(parts: List<Pair<Long, Color>>) {
    val shown = parts.filter { it.first > 0 }
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(EffColors.offline)) {
        shown.forEach { (v, c) -> Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(c)) }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsesLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TimeUse.entries.forEach { Legend(EffColors.of(it), it.label) }
    }
}

/** «no observado» when nothing was read, «pocos datos» under the minimum, else the figure of [tab]. */
@Composable
private fun SlotRow(k: Int, s: EffAcc, tab: EffTab) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(slotLabel(k), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.width(56.dp))
            Text(slotHeadline(s, tab), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        val detail = slotDetail(s, tab)
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp))
        }
        if (tab == EffTab.TIME && s.readingMs > 0) {
            Box(Modifier.padding(start = 56.dp, top = 2.dp)) { Bar(TimeUse.entries.map { s.use(it) to EffColors.of(it) }) }
        }
    }
}

private fun slotHeadline(s: EffAcc, tab: EffTab): String {
    if (s.readingMs == 0L) return "No observado"
    val few = "Pocos datos (${hours(s.readingMs)} leyendo)"
    return when (tab) {
        EffTab.MARKET -> s.offersPerHour?.let { String.format(Locale.US, "%.1f ofertas por hora", it) } ?: few
        EffTab.TAKEN -> s.takeRate?.let { "Tomaste ${s.taken} de ${s.offers} (${pct(it)})" } ?: "Pocas ofertas (${s.offers})"
        EffTab.RESULT -> s.realPerHour?.let { "${rd(it)} por hora conectada" } ?: few
        EffTab.TIME -> TimeUse.entries.joinToString(" · ") { u -> "${SHORT.getValue(u)} ${pct(s.share(u) ?: 0.0)}" }
    }
}

private fun slotDetail(s: EffAcc, tab: EffTab): String {
    if (s.readingMs == 0L) return ""
    return when (tab) {
        EffTab.MARKET -> listOfNotNull(
            "${s.offers} ofertas en ${hours(s.readingMs)}",
            s.medianOfferRdpH?.let { "mediana ${rd(it)}/h ofertado" },
            s.medianOfferRdpKm?.let { "${rd(it)}/km" },
            s.medianPickupKm?.let { String.format(Locale.US, "recogida %.1f km", it) }
        ).joinToString(" · ")
        EffTab.TAKEN -> listOfNotNull(
            s.medianTakenRdpH?.let { "las tomadas ofrecían mediana ${rd(it)}/h" },
            s.medianOfferRdpH?.let { "todas ${rd(it)}/h" }
        ).joinToString(" · ")
        EffTab.RESULT -> listOfNotNull(
            "${s.confirmed} viajes con dinero confirmado",
            s.pending.takeIf { it > 0 }?.let { "$it sin confirmar (la cifra es un mínimo)" },
            "${hours(s.readingMs)} leyendo"
        ).joinToString(" · ")
        EffTab.TIME -> "${hours(s.readingMs)} leyendo" + (if (s.unreadMs > 0) " · ${hours(s.unreadMs)} sin lectura" else "")
    }
}

@Composable
private fun KmCard(r: EfficiencyReport) {
    val k = r.km
    SectionCard(title = "Km del período", icon = R.drawable.ic_route) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (k.measuredMm == 0L) {
                Text("Sin km medidos en este período.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Bar(listOf(k.passengerMm to EffColors.passenger, k.pickupMm to EffColors.pickup, k.emptyMm to EffColors.searching))
                Legend(EffColors.passenger, "Con pasajero ${km(k.passengerMm)} (${pct(k.passengerMm.toDouble() / k.measuredMm)})")
                Legend(EffColors.pickup, "De recogida ${km(k.pickupMm)} (${pct(k.pickupMm.toDouble() / k.measuredMm)})")
                Legend(EffColors.searching, "Sin pasajero ${km(k.emptyMm)} (${pct(k.emptyMm.toDouble() / k.measuredMm)})")
            }
            if (k.gapMm > 0) {
                Text(
                    "Además, ${km(k.gapMm)} estimados en huecos de señal: no se reparten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private enum class CompareBy(val label: String) { DAY("Día"), SHIFT("Turno"), PRODUCT("Modalidad") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComparisonsCard(r: EfficiencyReport) {
    var compare by remember { mutableStateOf(CompareBy.SHIFT) }
    SectionCard(title = "Comparar", icon = R.drawable.ic_compare_arrows) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompareBy.entries.forEach { c -> AppFilterChip(selected = compare == c, onClick = { compare = c }, label = { Text(c.label) }) }
            }
            when (compare) {
                CompareBy.DAY -> r.weekdays.forEach { (d, a) -> GroupRow(DAY_LABELS[d - 1], a) }
                CompareBy.SHIFT -> r.shifts.forEach { (s, a) -> GroupRow("${s.label} ${s.range}", a) }
                CompareBy.PRODUCT -> {
                    if (r.products.isEmpty()) Text("Sin modalidades vistas en este período.", style = MaterialTheme.typography.bodyMedium)
                    r.products.forEach { p ->
                        Column {
                            Text(p.product, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                listOfNotNull(
                                    "${p.offers} ofertas",
                                    p.takeRate?.let { "tomaste ${pct(it)}" },
                                    p.medianOfferRdpH?.let { "mediana ${rd(it)}/h ofertado" },
                                    p.realPerTrip?.let { "${rd(it)} confirmado por viaje" } ?: "pocos viajes confirmados"
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        "El tiempo conectado no es de una modalidad, así que aquí no hay cifras por hora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (compare != CompareBy.PRODUCT) {
                Text(
                    "Con menos de ${Efficiency.MIN_COMPARE_MS / 3_600_000} h conectado y leyendo no se compara.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GroupRow(label: String, a: EffAcc) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            when {
                a.readingMs == 0L -> "No observado"
                a.readingMs < Efficiency.MIN_COMPARE_MS -> "Pocos datos (${hours(a.readingMs)} leyendo)"
                else -> listOfNotNull(
                    a.offersPerHour?.let { String.format(Locale.US, "%.1f ofertas/h", it) },
                    a.takeRate?.let { "tomaste ${pct(it)}" },
                    a.realPerHour?.let { "${rd(it)} por hora conectada" + if (a.pending > 0) " (mínimo)" else "" },
                    a.share(TimeUse.SEARCHING)?.let { "buscando ${pct(it)}" },
                    "${hours(a.readingMs)} leyendo"
                ).joinToString(" · ")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PreviousCard(cur: EfficiencyReport, prev: EfficiencyReport, period: EffPeriod) {
    val a = cur.total
    val b = prev.total
    SectionCard(title = "Contra los ${period.label} anteriores", icon = R.drawable.ic_history) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (a.readingMs < Efficiency.MIN_COMPARE_MS || b.readingMs < Efficiency.MIN_COMPARE_MS) {
                Text(
                    "No se compara: uno de los dos períodos tiene menos de ${Efficiency.MIN_COMPARE_MS / 3_600_000} h conectado y leyendo " +
                        "(ahora ${hours(a.readingMs)}, antes ${hours(b.readingMs)}).",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("Mismos días de la semana en ambos períodos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompareLine("Conectado y leyendo", hours(a.readingMs), hours(b.readingMs))
                CompareLine("Ofertas por hora", a.offersPerHour?.let { String.format(Locale.US, "%.1f", it) }, b.offersPerHour?.let { String.format(Locale.US, "%.1f", it) })
                CompareLine("Tomaste", a.takeRate?.let(::pct), b.takeRate?.let(::pct))
                CompareLine("Dinero por hora conectada", a.realPerHour?.let(::rd), b.realPerHour?.let(::rd))
                CompareLine("Buscando", a.share(TimeUse.SEARCHING)?.let(::pct), b.share(TimeUse.SEARCHING)?.let(::pct))
            }
        }
    }
}

@Composable
private fun CompareLine(label: String, now: String?, before: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${now ?: "—"}  (antes ${before ?: "—"})", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Shown in Análisis: one line and the button to the screen. */
@Composable
fun EfficiencyEntryCard(onOpen: () -> Unit) {
    SectionCard(title = "Horarios y eficiencia", icon = R.drawable.ic_schedule, subtitle = "Mercado, tomadas, resultado y tiempo por franja") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Cuántas ofertas viste por hora conectado, cuántas tomaste, cuánto dinero confirmado ganaste y en qué se fue el tiempo, " +
                    "por franja de 2 horas, día, turno y modalidad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Abrir horarios y eficiencia") }
        }
    }
}
