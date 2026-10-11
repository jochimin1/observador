package com.ubers.observador.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
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
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitEngine
import com.ubers.observador.domain.QualityThresholds
import com.ubers.observador.domain.ScoreBreakdown
import com.ubers.observador.domain.SettingsLimits
import com.ubers.observador.domain.formatNumber
import com.ubers.observador.domain.models.UberOffer
import kotlin.math.roundToInt

/** Fixed offer used by the "try it" card so the driver can see how thresholds move the score. */
private val ExampleOffer = UberOffer(price = 75.50, pickupKm = 1.8, pickupMin = 4, tripKm = 3.5, tripMin = 9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
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
                    TextButton(onClick = onNavigateBack) { Text("← Volver") }
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

            SectionHeader("Costos operativos")
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

            SectionHeader("Umbrales mínimos")
            Text(
                text = "Dile a la app qué es un buen viaje para ti. No bloquean ofertas: solo cambian " +
                    "el score de 0 a 100.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DecimalSettingCard(
                title = "RD$/km mínimo",
                weightPct = ScoreBreakdown.WEIGHT_KM_PCT,
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
                weightPct = ScoreBreakdown.WEIGHT_HOUR_PCT,
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
                weightPct = ScoreBreakdown.WEIGHT_PICKUP_KM_PCT,
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
                weightPct = ScoreBreakdown.WEIGHT_PICKUP_MIN_PCT,
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

            SectionHeader("Categorías de score")
            SettingCard(
                title = "Colores del viaje",
                description = "El score se traduce en tres categorías para que decidas de un vistazo, " +
                    "sin leer números."
            )
            QualityBar(thresholds)
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

            SectionHeader("Jornada")
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

            SectionHeader("Overlay")
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

            SectionHeader("Detección")
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

            SectionHeader("Modo de operación")
            ModeSelector(
                current = state.mode,
                goodFrom = thresholds.goodFrom,
                onSelect = { viewModel.updateMode(it) }
            )

            if (BuildConfig.DEBUG) {
                val testMessage by viewModel.testDataMessage.collectAsState()
                SectionHeader("Herramientas de prueba")
                TestDataCard(
                    message = testMessage,
                    onLoad = { viewModel.loadTestOffers() },
                    onClear = { viewModel.clearTestOffers() }
                )
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
                text = "Solo aparece en la versión de desarrollo. Carga 60 ofertas en hoy, ayer y " +
                    "hace 6 días para comprobar el dashboard sin salir a manejar. Van marcadas como " +
                    "prueba: al borrarlas, tus ofertas reales no se tocan.",
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
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp)
    )
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
private fun QualityBar(thresholds: QualityThresholds) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            QualitySegment(OfferQuality.BAD, thresholds.goodFrom)
            QualitySegment(OfferQuality.GOOD, thresholds.excellentFrom - thresholds.goodFrom)
            QualitySegment(OfferQuality.EXCELLENT, 100 - thresholds.excellentFrom)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.labelSmall
            Text("0", style = style)
            Text("${thresholds.goodFrom} · ${thresholds.excellentFrom}", style = style)
            Text("100", style = style)
        }
    }
}

@Composable
private fun RowScope.QualitySegment(quality: OfferQuality, size: Int) {
    if (size <= 0) return
    Box(
        modifier = Modifier
            .weight(size.toFloat())
            .fillMaxHeight()
            .background(Color(quality.backgroundArgb)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = quality.label,
            color = Color(quality.foregroundArgb),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ModeSelector(current: String, goodFrom: Int, onSelect: (String) -> Unit) {
    val modes = listOf(
        ModeOption(
            AppSettings.MODE_OBSERVER, "Observador",
            "Solo lee y guarda las ofertas. Tú decides todo y no recibes avisos.", true
        ),
        ModeOption(
            AppSettings.MODE_ASSISTED, "Asistido",
            "Te avisa con una notificación cuando aparece un viaje BUENO o EXCELENTE (score de $goodFrom " +
                "o más). Tú sigues aceptando a mano.", true
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
