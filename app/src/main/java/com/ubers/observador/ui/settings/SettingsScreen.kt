package com.ubers.observador.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import com.ubers.observador.R
import com.ubers.observador.ui.components.IconSectionHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.EngineSettings
import com.ubers.observador.domain.FieldResult
import com.ubers.observador.domain.FieldValidation
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.SettingsLimits
import com.ubers.observador.domain.formatNumber
import com.ubers.observador.domain.models.UberOffer
import com.ubers.observador.ui.components.QualityBar
import kotlin.math.roundToInt

/** Fixed offer used by the "try it" card so the driver can see how thresholds move the score. */
private val ExampleOffer = UberOffer(price = 75.50, pickupKm = 1.8, pickupMin = 4, tripKm = 3.5, tripMin = 9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: (() -> Unit)?,
    onOpenReports: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val engine = state.engineSettings
    val thresholds = state.thresholds
    val fuelSet = engine.fuelPricePerGallon > 0 && engine.kmPerGallon > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    onNavigateBack?.let { back -> TextButton(onClick = back) { Text("← Volver") } }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            val observing by viewModel.observerEnabled.collectAsState()
            ObserverSwitchCard(enabled = observing, onChange = { viewModel.setObserverEnabled(it) })
            // Fase 8L: close everything with one button
            var closing by remember { mutableStateOf(false) }
            if (closing) com.ubers.observador.ui.components.CloseAppDialog(onDismiss = { closing = false })
            OutlinedButton(onClick = { closing = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_power), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Apagar Observador", modifier = Modifier.padding(start = 8.dp))
            }

            val profiles by viewModel.profiles.collectAsState()
            val profileMessage by viewModel.profileMessage.collectAsState()
            ProfilesCard(
                state = profiles,
                message = profileMessage,
                onClearMessage = { viewModel.clearProfileMessage() },
                onSaveNew = { viewModel.saveNewProfile(it) },
                onApply = { viewModel.applyProfile(it) },
                onOverwrite = { viewModel.overwriteProfile(it) },
                onRename = { id, name -> viewModel.renameProfile(id, name) },
                onDelete = { viewModel.deleteProfile(it) }
            )

            // Fase 8J: every export in one place
            if (onOpenReports != null) {
                OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Abrir", onClick = onOpenReports)) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Reportes y exportación", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text(
                                "Ofertas y viajes con categorías, modalidad y totales del periodo; viajes, gastos, jornadas y PDF.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionHeader("Apariencia", R.drawable.ic_contrast)
            val theme by viewModel.themeSettings.collectAsState()
            ThemeCard(selected = theme.theme, onSelect = { viewModel.setTheme(it) })
            SwitchSettingCard(
                title = "Noche automática",
                description = "Pasa a Noche entre las horas que elijas, aunque el teléfono esté en modo claro. Al terminar vuelve a tu tema.",
                checked = theme.autoNight,
                onChange = { viewModel.setAutoNight(it) }
            )
            if (theme.autoNight) {
                IntSettingCard(
                    title = "Noche desde",
                    description = "Hora en formato de 24 horas (19 = 7 pm).",
                    value = theme.nightFromHour,
                    min = 0,
                    max = 23,
                    suffix = "h",
                    onValid = { viewModel.setNightHours(it, theme.nightToHour) }
                )
                IntSettingCard(
                    title = "Noche hasta",
                    description = "Hora en que vuelve tu tema (6 = 6 am).",
                    value = theme.nightToHour,
                    min = 0,
                    max = 23,
                    suffix = "h",
                    onValid = { viewModel.setNightHours(theme.nightFromHour, it) }
                )
            }

            SectionHeader("Costos operativos", R.drawable.ic_local_gas_station)
            DecimalSettingCard(
                title = "Precio del combustible",
                description = "Lo que pagas por galón de gasolina. Junto con el rendimiento, la app calcula " +
                    "sola cuánto te cuesta cada kilómetro. Pon 0 si prefieres escribir el costo por km a mano.",
                value = engine.fuelPricePerGallon,
                min = 0.0,
                max = SettingsLimits.FUEL_PRICE_MAX,
                suffix = "RD$/gal",
                onValid = { viewModel.updateFuelPricePerGallon(it) }
            )
            DecimalSettingCard(
                title = "Rendimiento de la moto",
                description = "Cuántos kilómetros recorre tu moto con un galón. Pon 0 si no lo sabes.",
                value = engine.kmPerGallon,
                min = 0.0,
                max = SettingsLimits.KM_PER_GALLON_MAX,
                suffix = "km/gal",
                onValid = { viewModel.updateKmPerGallon(it) }
            )
            if (fuelSet) {
                SettingCard(
                    title = "Costo por km",
                    description = "Calculado solo: precio del combustible ÷ rendimiento. La app lo resta " +
                        "para mostrarte cuánto ganas de verdad.",
                    hint = costHint(engine.costPerKm)
                )
            } else {
                DecimalSettingCard(
                    title = "Costo por km",
                    description = "Lo que te cuesta mover la moto cada kilómetro: gasolina, aceite, llantas y " +
                        "desgaste. La app lo resta para mostrarte cuánto ganas de verdad.",
                    hint = costHint(engine.costPerKm),
                    value = engine.costPerKm,
                    min = SettingsLimits.COST_PER_KM_MIN,
                    max = SettingsLimits.COST_PER_KM_MAX,
                    suffix = "RD$/km",
                    onValid = { viewModel.updateCostPerKm(it) }
                )
            }

            SectionHeader("Umbrales mínimos", R.drawable.ic_tune)
            Text(
                text = "Dile a la app qué es un buen viaje para ti. No bloquean ofertas: solo cambian " +
                    "el score de 0 a 100.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DecimalSettingCard(
                title = "RD$/km mínimo",
                weightPct = engine.weights.kmPct,
                description = "Lo mínimo que quieres cobrar por cada kilómetro, sumando el trayecto hasta " +
                    "el pasajero y el viaje. Se calcula como precio ÷ km totales.",
                hint = "RD$${formatNumber(engine.minRdpPerKm)}/km = 50 pts · " +
                    "RD$${formatNumber(engine.minRdpPerKm * 2)}/km o más = 100 pts.",
                value = engine.minRdpPerKm,
                min = SettingsLimits.MIN_RDP_PER_KM_MIN,
                max = SettingsLimits.MIN_RDP_PER_KM_MAX,
                suffix = "RD$/km",
                onValid = { viewModel.updateMinRdpPerKm(it) }
            )
            DecimalSettingCard(
                title = "RD$/hora mínimo",
                weightPct = engine.weights.hourPct,
                description = "Lo mínimo que quieres ganar por cada hora de tu tiempo, contando recogida y " +
                    "viaje. Un viaje corto pero lento en tráfico puede salir mal aquí.",
                hint = "RD$${formatNumber(engine.minRdpPerHour)}/h = 50 pts · " +
                    "RD$${formatNumber(engine.minRdpPerHour * 2)}/h o más = 100 pts.",
                value = engine.minRdpPerHour,
                min = SettingsLimits.MIN_RDP_PER_HOUR_MIN,
                max = SettingsLimits.MIN_RDP_PER_HOUR_MAX,
                suffix = "RD$/h",
                onValid = { viewModel.updateMinRdpPerHour(it) }
            )
            DecimalSettingCard(
                title = "Recogida máx. km",
                weightPct = engine.weights.pickupKmPct,
                description = "Qué tan lejos aceptas ir a buscar al pasajero. Cuanto más cerca esté, mejor " +
                    "puntúa; en este valor o más lejos, esa parte da 0.",
                hint = "0 km = 100 pts · ${formatNumber(engine.maxPickupKm / 2)} km = 50 pts · " +
                    "${formatNumber(engine.maxPickupKm)} km o más = 0 pts.",
                value = engine.maxPickupKm,
                min = SettingsLimits.PICKUP_KM_MIN,
                max = SettingsLimits.PICKUP_KM_MAX,
                suffix = "km",
                onValid = { viewModel.updateMaxPickupKm(it) }
            )
            IntSettingCard(
                title = "Recogida máx. min",
                weightPct = engine.weights.pickupMinPct,
                description = "Cuántos minutos estás dispuesto a manejar hasta el pasajero. Funciona igual " +
                    "que los km: poco tiempo suma, y en este valor o más da 0.",
                hint = "0 min = 100 pts · ${formatNumber(engine.maxPickupMin / 2.0)} min = 50 pts · " +
                    "${engine.maxPickupMin} min o más = 0 pts.",
                value = engine.maxPickupMin,
                min = SettingsLimits.PICKUP_MIN_MIN,
                max = SettingsLimits.PICKUP_MIN_MAX,
                suffix = "min",
                onValid = { viewModel.updateMaxPickupMin(it) }
            )
            ExampleCard(settings = engine, thresholds = thresholds)

            SectionHeader("Categorías de score", R.drawable.ic_category)
            SettingCard(
                title = "Colores del viaje",
                description = "El score se traduce en tres categorías para que decidas de un vistazo, " +
                    "sin leer números."
            )
            CutsBar(thresholds)
            IntSettingCard(
                title = "BUENO desde",
                description = "Score a partir del cual un viaje deja de ser MALO y pasa a BUENO (amarillo).",
                hint = "MALO: 0 a ${thresholds.goodFrom - 1} · BUENO: ${thresholds.goodFrom} a ${thresholds.excellentFrom - 1}",
                value = state.minScore,
                min = SettingsLimits.GOOD_FROM_MIN,
                max = SettingsLimits.GOOD_FROM_MAX,
                suffix = "pts",
                onValid = { viewModel.updateMinScore(it) }
            )
            IntSettingCard(
                title = "EXCELENTE desde",
                description = "Score a partir del cual un viaje es EXCELENTE (verde). Siempre tiene que ser " +
                    "mayor que BUENO.",
                hint = if (state.excellentScore <= state.minScore) {
                    "Debe superar a BUENO: se usará ${thresholds.excellentFrom}."
                } else {
                    "EXCELENTE: ${thresholds.excellentFrom} a 100"
                },
                value = state.excellentScore,
                min = SettingsLimits.EXCELLENT_FROM_MIN,
                max = SettingsLimits.EXCELLENT_FROM_MAX,
                suffix = "pts",
                onValid = { viewModel.updateExcellentScore(it) }
            )

            SectionHeader("Kilómetros", R.drawable.ic_route)
            val distanceEnabled by viewModel.distanceEnabled.collectAsState()
            val distanceMode by viewModel.distanceMode.collectAsState()
            val locationPermitted by com.ubers.observador.ui.AppPermissions.location.collectAsState()
            DistanceSettingsCard(
                enabled = distanceEnabled,
                permitted = locationPermitted,
                mode = distanceMode,
                onEnabled = { viewModel.setDistanceEnabled(it) },
                onMode = { viewModel.setDistanceMode(it) }
            )
            // Fase 8K: the route of the taken trips
            val routeEnabled by viewModel.routeEnabled.collectAsState()
            val routeCounts by viewModel.routeCounts.collectAsState()
            RouteSettingsCard(
                enabled = routeEnabled,
                gpsReady = distanceEnabled && locationPermitted,
                routes = routeCounts.first,
                points = routeCounts.second,
                onEnabled = { viewModel.setRouteEnabled(it) },
                onDeleteAll = { viewModel.deleteAllRoutes() }
            )

            SectionHeader("Ganancias y metas", R.drawable.ic_flag)
            val goals by viewModel.goals.collectAsState()
            IntSettingCard(
                title = "Meta diaria",
                description = "En pesos, sobre el ingreso bruto real confirmado de la jornada. Pon 0 para no usarla. " +
                    "Un perfil no la cambia.",
                value = (goals.dailyMinor / 100).toInt(),
                min = 0,
                max = (com.ubers.observador.domain.earnings.Goals.MAX_GOAL_MINOR / 100).toInt(),
                suffix = "RD$",
                onValid = { viewModel.setDailyGoalPesos(it) }
            )
            IntSettingCard(
                title = "Meta semanal",
                description = "En pesos, para la semana completa. Ganancias te dice cuánto sería por jornada en los días que quedan. Pon 0 para no usarla.",
                value = (goals.weeklyMinor / 100).toInt(),
                min = 0,
                max = (com.ubers.observador.domain.earnings.Goals.MAX_GOAL_MINOR / 100).toInt(),
                suffix = "RD$",
                onValid = { viewModel.setWeeklyGoalPesos(it) }
            )
            SettingCard(
                title = "La semana empieza el",
                description = "A tu hora de inicio de jornada. Cambia la semana de Ganancias y la meta semanal."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(java.time.DayOfWeek.MONDAY to "Lunes", java.time.DayOfWeek.SUNDAY to "Domingo").forEach { (day, label) ->
                        FilterChip(selected = goals.weekStart == day, onClick = { viewModel.setWeekStart(day) }, label = { Text(label) })
                    }
                }
            }
            SwitchSettingCard(
                title = "Aviso de meta alcanzada",
                description = "Una notificación cuando llegas a tu meta del día o de la semana, una vez. Apagado hasta que lo actives.",
                checked = goals.notify,
                onChange = { viewModel.setGoalNotification(it) }
            )
            val weeklyNotice by viewModel.weeklyReportNotification.collectAsState()
            SwitchSettingCard(
                title = "Aviso del resumen semanal",
                description = "«Tu resumen de la semana está listo» al empezar la semana nueva, con el ingreso real de la que terminó. " +
                    "Apagado hasta que lo actives.",
                checked = weeklyNotice,
                onChange = { viewModel.setWeeklyReportNotification(it) }
            )

            SectionHeader("Gastos y mantenimiento", R.drawable.ic_receipt_long)
            val workShare by viewModel.workSharePct.collectAsState()
            val intervals by viewModel.maintenanceIntervals.collectAsState()
            val maintenanceNotice by viewModel.maintenanceNotification.collectAsState()
            IntSettingCard(
                title = "Parte de trabajo",
                description = "Qué parte de los gastos de la moto es por trabajar con Uber. Si también la usas para " +
                    "lo tuyo, baja este número: la ganancia neta resta solo esa parte, y siempre verás el total al lado.",
                value = workShare,
                min = com.ubers.observador.domain.expenses.ExpenseLimits.WORK_SHARE_MIN,
                max = com.ubers.observador.domain.expenses.ExpenseLimits.WORK_SHARE_MAX,
                suffix = "%",
                onValid = { viewModel.setWorkSharePct(it) }
            )
            com.ubers.observador.domain.expenses.ExpenseType.REMINDABLE.forEach { type ->
                IntSettingCard(
                    title = "Cambio de ${type.label.lowercase()} cada",
                    description = if (type == com.ubers.observador.domain.expenses.ExpenseType.OIL) {
                        "Cada cuántos km cambias el aceite, según tu motor y el aceite que usas. Te avisamos cuando " +
                            "falte el 10 % o menos. Pon 0 para no recibir aviso."
                    } else {
                        "Opcional. Pon 0 para no recibir aviso."
                    },
                    value = (intervals[type] ?: 0L).toInt(),
                    min = 0,
                    max = com.ubers.observador.domain.expenses.ExpenseLimits.INTERVAL_MAX_KM.toInt(),
                    suffix = "km",
                    onValid = { viewModel.setMaintenanceInterval(type, it) }
                )
            }
            SwitchSettingCard(
                title = "Aviso de mantenimiento",
                description = "Una notificación cuando se acerca o pasa un cambio. Se revisa al abrir la app y al anotar un odómetro.",
                checked = maintenanceNotice,
                onChange = { viewModel.setMaintenanceNotification(it) }
            )

            SectionHeader("Jornada", R.drawable.ic_calendar_month)
            IntSettingCard(
                title = "Inicio de la jornada",
                description = "Hora en que empieza tu día de trabajo, en formato de 24 horas (16 = 4 pm). " +
                    "En el dashboard, \"Hoy\" va de esa hora a la misma hora del día siguiente: una " +
                    "oferta de la 1:30 am sigue contando en la jornada que empezó la tarde anterior.",
                hint = workdayHint(state.workdayStartHour),
                value = state.workdayStartHour,
                min = SettingsLimits.WORKDAY_START_MIN,
                max = SettingsLimits.WORKDAY_START_MAX,
                suffix = "h",
                onValid = { viewModel.updateWorkdayStartHour(it) }
            )

            SectionHeader("Overlay", R.drawable.ic_layers)
            SettingCard(
                title = "Mostrar tarjeta sobre Uber",
                description = "Muestra una tarjeta pequeña encima de la app de Uber con el precio, los km, " +
                    "el tiempo, el RD\$/km y el score, para que no tengas que hacer cuentas."
            ) {
                Switch(
                    checked = state.showOverlay,
                    onCheckedChange = { viewModel.updateShowOverlay(it) }
                )
            }
            if (state.showOverlay) {
                IntSettingCard(
                    title = "Duración en pantalla",
                    description = "La tarjeta se oculta sola después de este tiempo, o antes si la oferta " +
                        "desaparece.",
                    value = state.overlayDuration,
                    min = SettingsLimits.OVERLAY_SECONDS_MIN,
                    max = SettingsLimits.OVERLAY_SECONDS_MAX,
                    suffix = "seg",
                    onValid = { viewModel.updateOverlayDuration(it) }
                )
            }

            val overlayMessage by viewModel.overlayTestMessage.collectAsState()
            OverlayPreviewCard(
                message = overlayMessage,
                onTest = { viewModel.showTestOverlay() },
                onTestNotification = { viewModel.showTestNotification() }
            )
            OfferDebugCard()

            SectionHeader("Detección", R.drawable.ic_filter_alt)
            IntSettingCard(
                title = "Ventana anti-duplicados",
                description = "Uber a veces redibuja la misma oferta varias veces. Si llega una idéntica " +
                    "dentro de esta ventana, se cuenta como la misma y no se guarda otra vez.",
                value = state.graceWindow,
                min = SettingsLimits.GRACE_SECONDS_MIN,
                max = SettingsLimits.GRACE_SECONDS_MAX,
                suffix = "seg",
                onValid = { viewModel.updateGraceWindow(it) }
            )

            SectionHeader("Modo de operación", R.drawable.ic_toggle_on)
            ModeSelector(
                current = state.mode,
                goodFrom = thresholds.goodFrom,
                onSelect = { viewModel.updateMode(it) }
            )
            val alerts by viewModel.offerAlerts.collectAsState()
            OfferAlertsCard(alerts, onSelect = { viewModel.updateOfferAlerts(it) })

            if (BuildConfig.DEBUG) {
                val testMessage by viewModel.testDataMessage.collectAsState()
                SectionHeader("Herramientas de prueba", R.drawable.ic_science)
                TestDataCard(
                    message = testMessage,
                    onLoad = { viewModel.loadTestOffers() },
                    onClear = { viewModel.clearTestOffers() }
                )
                LifecycleDebugCard()
                OnlineDebugCard()
                DistanceDebugCard()
                ExpenseDebugCard()
                TripsDebugCard()
                FinanceDebugCard()
            }

            // Which build is installed, to match a bug or a screenshot with CHANGELOG.md
            Text(
                text = "Observador ${BuildConfig.VERSION_NAME} · compilación ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Shows the floating card with a sample offer, so its size and colours can be checked. */
@Composable
private fun OverlayPreviewCard(message: String?, onTest: () -> Unit, onTestNotification: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Probar la tarjeta",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Muestra la tarjeta flotante con una oferta de ejemplo (con recogida y destino). Cada toque alterna MALO, " +
                    "BUENO y EXCELENTE. Necesita el servicio de accesibilidad activo. «Probar notificación» envía el aviso " +
                    "de oferta; con ofertas reales llega según «Avisos de ofertas».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest) { Text("Probar tarjeta") }
                OutlinedButton(onClick = onTestNotification) { Text("Probar notificación") }
            }
            if (message != null) {
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Debug builds only: loads or removes the known set of fake offers. */
@Composable
private fun TestDataCard(message: String?, onLoad: () -> Unit, onClear: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Ofertas de prueba",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Solo aparece en la versión de desarrollo. Carga 160 ofertas: 60 en hoy, ayer y " +
                    "hace 6 días para comprobar el resumen y la lista, y 100 en otros 6 días con un " +
                    "patrón conocido para Mejores horas. Van marcadas como prueba: al borrarlas, tus " +
                    "ofertas reales no se tocan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad) { Text("Cargar") }
                OutlinedButton(onClick = onClear) { Text("Borrar") }
            }
            if (message != null) {
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 16, 0 -> "4:00 pm". */
private fun clock12(hour: Int, minute: Int): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h, minute, if (hour < 12) "am" else "pm")
}

private fun workdayHint(startHour: Int): String {
    val endHour = (startHour + 23) % 24
    val endDay = if (startHour == 0) "del mismo día" else "del día siguiente"
    // "la 1:00", but "las 2:00"
    fun article(hour: Int) = if (hour % 12 == 1) "la" else "las"
    return "Tu jornada va de ${article(startHour)} ${clock12(startHour, 0)} a " +
        "${article(endHour)} ${clock12(endHour, 59)} $endDay."
}

private fun costHint(costPerKm: Double): String {
    val km = ExampleOffer.pickupKm + ExampleOffer.tripKm
    val net = ExampleOffer.price - costPerKm * km
    return "Ejemplo: en ${formatNumber(km)} km gastas RD$%.2f, así que de RD$%.2f te quedan RD$%.2f."
        .format(costPerKm * km, ExampleOffer.price, net)
}

@Composable
private fun SectionHeader(text: String, @DrawableRes icon: Int) {
    IconSectionHeader(text = text, icon = icon)
}

/**
 * Card with title, optional score weight, plain-language description and a live "with your value"
 * line. [field] sits on the right of the title and can report a validation error, which the card
 * shows under the title.
 */
@Composable
private fun SettingCard(
    title: String,
    description: String,
    weightPct: Int? = null,
    hint: String? = null,
    field: (@Composable (onError: (String?) -> Unit) -> Unit)? = null
) {
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                field?.invoke { error = it }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (weightPct != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "Pesa $weightPct% del score",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hint != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DecimalSettingCard(
    title: String,
    description: String,
    value: Double,
    min: Double,
    max: Double,
    suffix: String,
    onValid: (Double) -> Unit,
    weightPct: Int? = null,
    hint: String? = null
) {
    SettingCard(title, description, weightPct, hint) { onError ->
        ValidatedField(
            value = value,
            toText = ::formatNumber,
            parse = { FieldValidation.parseDecimal(it, min, max) },
            keyboardType = KeyboardType.Decimal,
            suffix = suffix,
            onValid = onValid,
            onError = onError
        )
    }
}

@Composable
private fun IntSettingCard(
    title: String,
    description: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String,
    onValid: (Int) -> Unit,
    weightPct: Int? = null,
    hint: String? = null
) {
    SettingCard(title, description, weightPct, hint) { onError ->
        ValidatedField(
            value = value,
            toText = { it.toString() },
            parse = { FieldValidation.parseInt(it, min, max) },
            keyboardType = KeyboardType.Number,
            suffix = suffix,
            onValid = onValid,
            onError = onError
        )
    }
}

/**
 * Numeric field that keeps its own text while the driver types, saves only valid values and
 * explains what is wrong otherwise. When it loses focus it goes back to the saved value, so an
 * invalid entry is never left looking like a setting.
 */
@Composable
private fun <T> ValidatedField(
    value: T,
    toText: (T) -> String,
    parse: (String) -> FieldResult<T>,
    keyboardType: KeyboardType,
    suffix: String,
    onValid: (T) -> Unit,
    onError: (String?) -> Unit
) {
    var text by remember { mutableStateOf(toText(value)) }
    var focused by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!focused) text = toText(value)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            when (val result = parse(input)) {
                is FieldResult.Valid -> {
                    invalid = false
                    onError(null)
                    onValid(result.value)
                }
                is FieldResult.Invalid -> {
                    invalid = true
                    onError(result.message)
                }
            }
        },
        isError = invalid,
        suffix = { Text(suffix, style = MaterialTheme.typography.bodySmall) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier
            .width(150.dp)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (!focusState.isFocused) {
                    text = toText(value)
                    invalid = false
                    onError(null)
                }
            }
    )
}

@Composable
private fun ExampleCard(settings: EngineSettings, thresholds: QualityThresholds) {
    val engine = remember { ProfitEngine() }
    val analysis = engine.analyze(ExampleOffer, settings)
    val parts = engine.breakdown(analysis, settings)
    val quality = thresholds.classify(analysis.score)

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Prueba con una oferta de ejemplo",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "RD$75.50 · recogida 1.8 km / 4 min · viaje 3.5 km / 9 min. Cambia los umbrales " +
                    "de arriba y mira cómo se mueve el score.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ScorePartRow("RD\$/km", "RD$%.2f/km".format(analysis.rdpPerKm), parts.km)
            ScorePartRow("RD\$/hora", "RD$%.0f/h".format(analysis.rdpPerHour), parts.hour)
            ScorePartRow("Recogida km", "${formatNumber(ExampleOffer.pickupKm)} km", parts.pickupKm)
            ScorePartRow("Recogida min", "${ExampleOffer.pickupMin} min", parts.pickupMin)
            Surface(
                color = Color(quality.backgroundArgb),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Score final",
                        color = Color(quality.foregroundArgb),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${analysis.score} · ${quality.label}",
                        color = Color(quality.foregroundArgb),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ScorePartRow(label: String, value: String, points: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "$label ($value)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "${points.roundToInt()} pts",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        LinearProgressIndicator(
            progress = { (points / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CutsBar(thresholds: QualityThresholds) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        QualityBar(
            bad = thresholds.goodFrom,
            good = thresholds.excellentFrom - thresholds.goodFrom,
            excellent = 100 - thresholds.excellentFrom,
            description = "Cortes: MALO de 0 a ${thresholds.goodFrom - 1}, BUENO de ${thresholds.goodFrom} " +
                "a ${thresholds.excellentFrom - 1}, EXCELENTE de ${thresholds.excellentFrom} a 100"
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.labelSmall
            Text("0", style = style)
            Text("${thresholds.goodFrom} · ${thresholds.excellentFrom}", style = style)
            Text("100", style = style)
        }
    }
}

/** Fase 8I: which offers send a notification, whatever the mode. */
@Composable
private fun OfferAlertsCard(current: com.ubers.observador.domain.OfferAlerts, onSelect: (com.ubers.observador.domain.OfferAlerts) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Avisos de ofertas", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                "Una notificación por cada oferta nueva que Observador lee, con su veredicto, recogida y destino. " +
                    "En el Radar de Uber cada tarjeta avisa por separado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.ubers.observador.domain.OfferAlerts.entries.forEach { option ->
                    FilterChip(selected = option == current, onClick = { onSelect(option) }, label = { Text(option.label) })
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(current: String, goodFrom: Int, onSelect: (String) -> Unit) {
    val modes = listOf(
        ModeOption(
            AppSettings.MODE_OBSERVER, "Observador",
            "Lee y guarda las ofertas. Tú decides todo; los avisos siguen «Avisos de ofertas».", true
        ),
        ModeOption(
            AppSettings.MODE_ASSISTED, "Asistido",
            "Además te avisa siempre de los viajes BUENOS o EXCELENTES (score de $goodFrom o más), " +
                "aunque «Avisos de ofertas» esté en «Ninguna». Tú sigues aceptando a mano.", true
        ),
        ModeOption(
            AppSettings.MODE_AUTO, "Automático · próximamente",
            "Aceptaría viajes por ti según tus reglas. Aún no está disponible.", false
        )
    )
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            modes.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = current == option.mode,
                            enabled = option.enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(option.mode) }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RadioButton(
                        selected = current == option.mode,
                        onClick = null,
                        enabled = option.enabled
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (option.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class ModeOption(
    val mode: String,
    val title: String,
    val description: String,
    val enabled: Boolean
)

@Composable
private fun SwitchSettingCard(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
