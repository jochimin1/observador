package com.ubers.observador.ui.intelligence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.BuildConfig
import com.ubers.observador.R
import com.ubers.observador.ui.components.SectionCard
import com.ubers.observador.data.debug.IntelligenceTestSeeder
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.intelligence.GroupPerformance
import com.ubers.observador.domain.intelligence.IntelligenceAnomaly
import com.ubers.observador.domain.intelligence.IntelligencePolicy
import com.ubers.observador.domain.intelligence.IntelligenceReport
import com.ubers.observador.domain.intelligence.ModelVersionRules
import com.ubers.observador.domain.intelligence.Monotonicity
import com.ubers.observador.domain.intelligence.OfferDecisionAnalyzer
import com.ubers.observador.domain.intelligence.OutcomeLabel
import com.ubers.observador.domain.intelligence.RecommendationStatus
import com.ubers.observador.domain.intelligence.SampleConfidence
import com.ubers.observador.domain.intelligence.ShadowReport
import com.ubers.observador.ui.intelligence.IntelligenceTexts.confidence
import com.ubers.observador.ui.intelligence.IntelligenceTexts.pct
import com.ubers.observador.ui.intelligence.IntelligenceTexts.rdHour
import com.ubers.observador.ui.intelligence.IntelligenceTexts.rdMinor
import com.ubers.observador.ui.intelligence.IntelligenceTexts.rho
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun km(value: Double) = String.format(java.util.Locale.US, "%.1f", value)
private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE)

/**
 * "Análisis" (Fase 7D): does the score really bring better trips? Data quality first, then the
 * model, the evidence, and only then a recommendation, which can at most start a shadow test.
 * Nothing here controls Uber, and nothing changes the active model without the user's confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IntelligenceScreen(onNavigateBack: (() -> Unit)?, viewModel: IntelligenceViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    var confirmPromote by remember { mutableStateOf(false) }
    var confirmRollback by remember { mutableStateOf(false) }

    if (confirmPromote) {
        AlertDialog(
            onDismissRequest = { confirmPromote = false },
            title = { Text("¿Aplicar el modelo candidato?") },
            text = { Text("Esta acción cambiará cómo Observador puntúa las ofertas nuevas. Los scores ya guardados no cambian. Puedes volver al modelo anterior.") },
            confirmButton = { TextButton(onClick = { confirmPromote = false; viewModel.promote() }) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = { confirmPromote = false }) { Text("Cancelar") } }
        )
    }
    if (confirmRollback) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text("¿Restaurar el modelo anterior?") },
            text = { Text("Se vuelven a usar exactamente los valores del modelo v${state.previous?.number}. El historial se conserva.") },
            confirmButton = { TextButton(onClick = { confirmRollback = false; viewModel.rollback() }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text("Cancelar") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Análisis") },
                navigationIcon = { onNavigateBack?.let { back -> TextButton(onClick = back) { Text("← Volver") } } }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntelligencePeriod.entries.forEach { p ->
                    FilterChip(selected = state.period == p, onClick = { viewModel.selectPeriod(p) }, label = { Text(p.label) })
                }
                // Since 1.25.0: the trips closed by «Buscando» count too, unless switched off here
                FilterChip(
                    selected = state.includeUnconfirmed,
                    onClick = { viewModel.setIncludeUnconfirmed(!state.includeUnconfirmed) },
                    label = { Text("Incluir sin confirmar") }
                )
            }
            message?.let {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearMessage() }) { Text("OK") }
                    }
                }
            }
            Limitations()
            val report = state.report
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.failed || report == null -> Body("No se pudo calcular el análisis. Tus datos no se tocaron.")
                else -> {
                    state.waits?.let { WaitsCard(it) }
                    DataQuality(report, state.usesTestData)
                    ModelState(report, state.active)
                    if (report.quality.usableForHourly == 0) {
                        Card("¿El score funciona?") {
                            Body(
                                when {
                                    report.quality.completedTrips == 0 -> "Todavía no hay viajes terminados en este periodo."
                                    report.quality.withConfirmedMoney > 0 ->
                                        "Tenemos ingresos reales, pero faltan tiempos suficientes para calcular RD$/hora."
                                    else -> "Hay ${report.quality.completedTrips} viajes terminados, pero ninguno con el dinero real del viaje. " +
                                        "Para medir si el score acierta hace falta lo que cobraste en cada viaje: llega cuando Observador lee " +
                                        "la pantalla de ganancias del viaje (aún por validar con capturas) o cuando lo asignas en Ganancias."
                                }
                            )
                        }
                        // Times and kilometres need no money: they are shown anyway
                        if (report.prediction.time != null || report.prediction.distance != null) PredictionVsReality(report)
                    } else {
                        ScoreWorks(report)
                        PredictionVsReality(report)
                        Categories(report)
                        RealHours(report)
                        Factors(report)
                    }
                    Decisions(report)
                    Recommendation(report, state.shadowCandidate != null, onShadow = { viewModel.startShadow() })
                    state.shadowCandidate?.let { candidate ->
                        Shadow(
                            report.shadow, candidate, state.active,
                            onPromote = { confirmPromote = true },
                            onStop = { viewModel.stopShadow() },
                            onSimulate = if (BuildConfig.DEBUG && state.usesTestData) {
                                { viewModel.simulateTestShadow(); Unit }
                            } else {
                                null
                            }
                        )
                    }
                    History(state.versions, state.active, state.previous, onRollback = { confirmRollback = true })
                }
            }
            if (BuildConfig.DEBUG) {
                Card("Datos de prueba (solo desarrollo)") {
                    Body("Cargan 100 viajes TEST- con un patrón conocido. Reemplazan los viajes de prueba de otras tarjetas. Nunca tocan los reales.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntelligenceTestSeeder.Scenario.entries.forEach { s ->
                            OutlinedButton(onClick = { viewModel.loadTestScenario(s) }) { Text(IntelligenceTexts.scenario(s)) }
                        }
                    }
                    TextButton(onClick = { viewModel.clearTestData() }) { Text("Borrar datos de prueba") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Each card's icon, by title: the same kit as the home screen (SectionCard). */
private val CARD_ICONS = mapOf(
    "Qué puede y qué no puede saber" to R.drawable.ic_info,
    "Calidad de datos" to R.drawable.ic_fact_check,
    "Estado del modelo" to R.drawable.ic_monitor_heart,
    "¿El score funciona?" to R.drawable.ic_leaderboard,
    "Predicción vs realidad" to R.drawable.ic_compare_arrows,
    "Categorías (reglas de aquel momento)" to R.drawable.ic_category,
    "Mejores horas reales" to R.drawable.ic_schedule,
    "Factores y su relación con el resultado" to R.drawable.ic_analytics,
    "Ofertas y decisiones" to R.drawable.ic_swap_horiz,
    "Recomendación" to R.drawable.ic_lightbulb,
    "Modelo en sombra" to R.drawable.ic_contrast,
    "Historial de modelos" to R.drawable.ic_history,
    "Datos de prueba (solo desarrollo)" to R.drawable.ic_science
)

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    SectionCard(title = title, icon = CARD_ICONS[title] ?: R.drawable.ic_analytics) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Limitations() {
    var open by remember { mutableStateOf(false) }
    Card("Qué puede y qué no puede saber") {
        Body(IntelligenceTexts.LIMITATIONS[1])
        if (open) IntelligenceTexts.LIMITATIONS.filterIndexed { i, _ -> i != 1 }.forEach { Muted("• $it") }
        TextButton(onClick = { open = !open }) { Text(if (open) "Ver menos" else "Ver todos los límites") }
    }
}

@Composable
private fun DataQuality(r: IntelligenceReport, usesTestData: Boolean) {
    val q = r.quality
    Card("Calidad de datos") {
        Body("${q.completedTrips} viajes terminados · ${q.withConfirmedMoney} con dinero · ${q.withFullTimes} con tiempos completos")
        if (q.unconfirmedEnds > 0) {
            Muted("${q.confirmedEnds} confirmados por Uber · ${q.unconfirmedEnds} sin confirmar (cerrados al volver a «Buscando»)")
        }
        Body("Con km reales del GPS (viaje medido completo): ${q.withRealDistance}" + (q.distance.ratio?.let { " · cobertura ${pct(it, signed = false)}" } ?: ""))
        Body("Usables para RD$/hora real: ${q.usableForHourly}" + (q.hourly.ratio?.let { " · cobertura ${pct(it, signed = false)}" } ?: ""))
        Muted("Confianza de la muestra: ${confidence(q.hourly.confidence)}")
        val gaps = listOfNotNull(
            "${q.recovered} recuperados a mitad".takeIf { q.recovered > 0 },
            "${q.missingAcceptedAt} sin hora de aceptación".takeIf { q.missingAcceptedAt > 0 },
            "${q.withoutMoney} sin dinero visto".takeIf { q.withoutMoney > 0 },
            "${q.withPartialMoney} con dinero parcial".takeIf { q.withPartialMoney > 0 },
            "${q.currencyMismatch} en otra moneda".takeIf { q.currencyMismatch > 0 },
            "${q.withoutOffer} sin oferta conocida".takeIf { q.withoutOffer > 0 }
        )
        if (gaps.isNotEmpty()) Muted(gaps.joinToString(" · "))
        if (q.invalidTimeline > 0) Muted("Algunos viajes fueron excluidos de los tiempos por datos inconsistentes: ${q.invalidTimeline}. No se borraron.")
        if (usesTestData) Text("Incluye datos de prueba (TEST-).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ModelState(r: IntelligenceReport, active: ProfitModelVersion?) {
    Card("Estado del modelo") {
        Body("Modelo actual: " + (active?.let { "v${it.number} · desde ${date(it.createdAtMs)}" } ?: "—"))
        Text(IntelligenceTexts.health(r.evaluation.health), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        // No alert fatigue: the three most important reasons only
        r.anomalies.take(3).forEach { Muted("• " + IntelligenceTexts.anomaly(it)) }
        IntelligenceTexts.drift(r.drift.status)?.let { Muted(it) }
        r.topInsight?.let {
            HorizontalDivider()
            Text("Principal hallazgo", style = MaterialTheme.typography.labelLarge)
            Body(IntelligenceTexts.insight(it))
        }
        if (r.scoreReproduction.total > 0 && r.scoreReproduction.valid < r.scoreReproduction.total) {
            Muted("${r.scoreReproduction.total - r.scoreReproduction.valid} scores guardados no se reproducen con sus ajustes de entonces.")
        }
    }
}

/** One labelled bar; TalkBack reads the whole row as one sentence. */
@Composable
private fun Bar(label: String, value: Double?, max: Double, detail: String, spoken: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            if (value != null && max > 0) {
                Box(
                    Modifier.fillMaxWidth((value / max).toFloat().coerceIn(0.02f, 1f)).height(14.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                )
            }
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

private fun groupDetail(g: GroupPerformance): String =
    (g.medianHourly?.let(::rdHour) ?: "—") + " · n=${g.hourlyN}" + if (g.hourlyN in 1 until IntelligencePolicy.CELL_MIN) " (pocos)" else ""

private fun groupSpoken(prefix: String, g: GroupPerformance): String =
    "$prefix. ${g.hourlyN} viajes con resultado." + (g.medianHourly?.let { " Mediana ${it.toInt()} pesos por hora." } ?: " Sin resultado.")

@Composable
private fun ScoreWorks(r: IntelligenceReport) {
    val s = r.score
    Card("¿El score funciona?") {
        if (s.spearman != null) {
            Body("Relación score → resultado: ${rho(s.spearman)} · ${s.spearmanN} viajes")
            Body(IntelligenceTexts.strength(s.spearman))
        } else {
            Body("Todavía no hay suficientes viajes para medir la relación.")
        }
        if (r.confidence == SampleConfidence.EXPLORATORY) Muted("Resultados preliminares.")
        val max = s.buckets.mapNotNull { it.medianHourly }.maxOrNull() ?: 0.0
        s.buckets.forEach { g -> Bar("Score ${g.label}", g.medianHourly, max, groupDetail(g), groupSpoken("Score de ${g.label}", g)) }
        when (s.bucketMonotonicity) {
            Monotonicity.INCREASING -> Muted("✓ A mayor score, mejor resultado, tramo a tramo.")
            Monotonicity.NON_MONOTONIC -> Muted("Un tramo de score más alto rindió menos que uno más bajo: conviene revisar la calibración.")
            Monotonicity.INSUFFICIENT_DATA -> Muted("Faltan viajes por tramo para comparar (mínimo ${IntelligencePolicy.MIN_BUCKET_N}).")
        }
        s.topBottomSpread?.let { Muted("25% de score más alto frente al 25% más bajo: ${if (it >= 0) "+" else ""}${rdHour(it)} (mediana real).") }
        Muted("El score ordena ofertas; no es una probabilidad.")
    }
}

@Composable
private fun PredictionVsReality(r: IntelligenceReport) {
    val p = r.prediction
    Card("Predicción vs realidad") {
        val h = p.hourly
        if (h != null) {
            Body("RD$/hora previsto (mediana): ${rdHour(p.predictedHourly!!.median)}")
            Body("RD$/hora real (mediana): ${rdHour(p.realizedHourly!!.median)}")
            Muted("Diferencia: mediana ${h.medianPercent?.let { pct(it) } ?: "—"}, P25–P75 ${rdHour(h.signed.p25)} a ${rdHour(h.signed.p75)} · n=${h.coverage.valid}")
        }
        p.time?.let { t ->
            Body("Tiempo estimado ${p.estimatedMinutes!!.median.toInt()} min · real ${p.actualEngagedMinutes!!.median.toInt()} min (medianas)")
            Muted("Sesgo de tiempo: ${t.medianPercent?.let { pct(it) } ?: "—"} · error típico ${t.medianAbsolute.toInt()} min · n=${t.coverage.valid}")
            Muted("Tiempo real = de aceptar a terminar (recogida + espera + viaje).")
        }
        p.money?.let { m ->
            Body("Ingreso final vs precio ofertado: mediana ${rdMinor(m.medianMinor, signed = true)}" + (m.medianPercent?.let { " (${pct(it)})" } ?: ""))
            Muted("P25–P75: ${rdMinor(m.p25Minor, true)} a ${rdMinor(m.p75Minor, true)} · n=${m.coverage.valid}")
        }
        // Since 1.25.0: the GPS kilometres of the whole trip (8E) against the offer's
        p.distance?.let { d ->
            Body("Distancia estimada ${km(p.estimatedKm!!.median)} km · real ${km(p.realKm!!.median)} km (medianas, GPS)")
            Muted("Sesgo de distancia: ${d.medianPercent?.let { pct(it) } ?: "—"} · error típico ${km(d.medianAbsolute)} km · n=${d.coverage.valid}")
            Muted("Distancia = recogida + viaje, de aceptar a terminar.")
        }
        p.perRealKm?.let { real ->
            Body("RD$/km real (mediana): RD$${km(real.median)}" + (p.perEstimatedKm?.let { " · por km estimado RD$${km(it.median)}" } ?: "") + " · n=${real.n}")
        }
        Muted("Un pago mayor no es mejor rentabilidad si el viaje dura más: por eso el resultado principal es RD$/hora real.")
    }
}

@Composable
private fun Categories(r: IntelligenceReport) {
    val s = r.score
    Card("Categorías (reglas de aquel momento)") {
        val max = s.categories.values.mapNotNull { it.medianHourly }.maxOrNull() ?: 0.0
        OfferQuality.entries.forEach { q ->
            val g = s.categories.getValue(q)
            Bar(q.label, g.medianHourly, max, groupDetail(g), groupSpoken(q.label, g))
        }
        if (s.categoryMonotonicity == Monotonicity.NON_MONOTONIC) Muted("Las categorías no quedan en orden MALO < BUENO < EXCELENTE: alerta de calibración.")
        s.collapsedCategories.forEach { (a, b) -> Muted("${a.label} y ${b.label} se comportan de manera muy similar en esta muestra.") }
        s.matrix?.let { m ->
            HorizontalDivider()
            Muted("Resultado frente a tu objetivo de ${rdHour(m.targetHourly)}:")
            OfferQuality.entries.forEach { q ->
                Body("${q.label}: cumple ${m.count(q, OutcomeLabel.MEETS_TARGET)} · por debajo ${m.count(q, OutcomeLabel.BELOW_TARGET)}")
            }
            if (m.favourableBelowTarget > 0) Muted("${m.favourableBelowTarget} predicciones favorables (EXCELENTE) no alcanzaron el objetivo.")
        }
    }
}

@Composable
private fun RealHours(r: IntelligenceReport) {
    val h = r.realHours
    Card("Mejores horas reales") {
        Muted("Viajes completados, por hora de aceptación. Las \"Mejores horas\" del inicio miden ofertas vistas: son cosas distintas y no se mezclan.")
        val slots = h.slots.filter { it.performance.tripCount > 0 }
        if (slots.isEmpty()) Body("Sin viajes con hora de aceptación en este periodo.")
        val max = slots.mapNotNull { it.performance.medianHourly }.maxOrNull() ?: 0.0
        slots.forEach { s ->
            val label = "%02d–%02d".format(s.startHour, s.startHour + 2)
            Bar(label, s.performance.medianHourly, max, groupDetail(s.performance), groupSpoken("De las $label", s.performance))
            val ratio = s.marketRealizationRatio
            if (ratio != null && !s.lowData) Muted("   Ofertas vistas: ${rdHour(s.observedMarketMedianHourly!!)} previsto · real ${pct(ratio - 1)} frente a eso")
        }
        Muted("Histórico, no una promesa de ingresos.")
    }
}

@Composable
private fun Factors(r: IntelligenceReport) {
    Card("Factores y su relación con el resultado") {
        r.score.components.forEach { c ->
            Body(IntelligenceTexts.component(c.component) + ": " + (c.rho?.let(::rho) ?: "—") + " · n=${c.n}")
        }
        Muted("Una correlación no demuestra una causa; sirve como evidencia para probar ajustes.")
        HorizontalDivider()
        Muted("Distancia de recogida (viajes aceptados):")
        val max = r.thresholds.pickupKm.mapNotNull { it.medianHourly }.maxOrNull() ?: 0.0
        r.thresholds.pickupKm.forEach { g -> Bar(g.label, g.medianHourly, max, groupDetail(g), groupSpoken("Recogida ${g.label}", g)) }
    }
}

@Composable
private fun Decisions(r: IntelligenceReport) {
    val d = r.decisions
    Card("Ofertas y decisiones") {
        Body("Ofertas vistas: ${d.offersSeen} · con viaje registrado: ${d.accepted}")
        Body(
            "Tasa de aceptación: " + (d.acceptanceRate?.let { pct(it, signed = false) }
                ?: "no disponible (Observador aún no distingue una oferta rechazada de una caducada)")
        )
        if (d.highScoreWithoutKnownDecision > 0) {
            Muted("${d.highScoreWithoutKnownDecision} ofertas EXCELENTES no tienen viaje registrado. No sabemos si caducaron, se rechazaron o no se detectó la aceptación.")
        }
        if (!OfferDecisionAnalyzer.DECLINE_DETECTION_AVAILABLE) Muted("Nunca se calcula cuánto \"habrías ganado\": un viaje que no ocurrió no tiene resultado.")
        val q = r.score.decisionQuality
        if (q.acceptedWithCategory > 0) {
            Muted(
                "Viajes aceptados: EXCELENTE ${q.acceptedExcellentRate?.let { pct(it, false) }} · BUENO o mejor ${q.acceptedGoodOrBetterRate?.let { pct(it, false) }}" +
                    (q.acceptedBelowTargetRate?.let { " · bajo tu objetivo ${pct(it, false)}" } ?: "")
            )
        }
    }
}

@Composable
private fun Recommendation(r: IntelligenceReport, shadowRunning: Boolean, onShadow: () -> Unit) {
    val c = r.calibration
    Card("Recomendación") {
        if (r.quality.usableForHourly < IntelligencePolicy.EXPLORATORY_FROM) {
            Body("Necesitamos más viajes con resultado completo para evaluar el ProfitEngine.")
            return@Card
        }
        Body(IntelligenceTexts.recommendation(c.status))
        val candidate = c.candidate
        if (candidate != null) {
            val current = c.currentValidation
            val w0 = candidate.settings.weights
            Body("Pesos propuestos: km ${w0.kmPct}% · hora ${w0.hourPct}% · km recogida ${w0.pickupKmPct}% · min recogida ${w0.pickupMinPct}%")
            Muted(
                "Viajes recientes (validación, n=${c.validationN}): actual ${current.spearman?.let(::rho) ?: "—"} · " +
                    "candidato ${c.candidateValidation.spearman?.let(::rho) ?: "—"}"
            )
            current.topBottomSpread?.let { a -> c.candidateValidation.topBottomSpread?.let { b -> Muted("Separación 25% alto vs bajo: ${rdHour(a)} → ${rdHour(b)}") } }
            Muted("Solo usa viajes que aceptaste. En tus viajes históricos este modelo los ordenó mejor; no es una promesa de ingresos.")
            if (!shadowRunning) Button(onClick = onShadow) { Text("Probar en sombra") }
        } else if (c.status != RecommendationStatus.NOT_ENOUGH_DATA) {
            Muted("Calibración con ${c.totalSamples} viajes (${c.trainingN} antiguos para ajustar, ${c.validationN} recientes para confirmar).")
        }
        if (IntelligenceAnomaly.MODEL_OVERFIT in r.anomalies) Muted("Se descartó un ajuste que solo funcionaba con datos antiguos.")
    }
}

@Composable
private fun Shadow(
    s: ShadowReport?,
    candidate: ProfitModelVersion,
    active: ProfitModelVersion?,
    onPromote: () -> Unit,
    onStop: () -> Unit,
    onSimulate: (() -> Unit)?
) {
    Card("Modelo en sombra") {
        Body("Candidato v${candidate.number} frente a actual v${active?.number ?: "—"}. Tu score visible no cambia.")
        if (s != null) {
            s.firstEvaluatedAtMs?.let { Muted("En sombra desde ${date(it)}") }
            Body("Ofertas evaluadas: ${s.offersEvaluated} · viajes con resultado: ${s.withOutcome}")
            s.disagreementRate?.let { Muted("Categoría distinta en ${pct(it, false)} de las ofertas.") }
            Body("Actual: ${s.activeSpearman?.let(::rho) ?: "—"} · Candidato: ${s.candidateSpearman?.let(::rho) ?: "—"}")
            s.disagreementOutcomes.forEach { d ->
                Muted("Actual ${d.activeCategory.label} / candidato ${d.candidateCategory.label}: n=${d.n}, mediana ${d.medianHourly?.let(::rdHour) ?: "—"}")
            }
            if (s.readyForReview) {
                Button(onClick = onPromote) { Text("Aplicar candidato") }
            } else {
                Muted("Aún no se puede aplicar: " + s.blockers.joinToString { IntelligenceTexts.gateReason(it) } + ".")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onStop) { Text("Detener prueba") }
            onSimulate?.let { TextButton(onClick = it) { Text("Simular con datos de prueba") } }
        }
    }
}

@Composable
private fun History(versions: List<ProfitModelVersion>, active: ProfitModelVersion?, previous: ProfitModelVersion?, onRollback: () -> Unit) {
    val history = ModelVersionRules.activeHistory(versions)
    if (history.isEmpty()) return
    Card("Historial de modelos") {
        history.reversed().forEachIndexed { i, v ->
            val until = history.getOrNull(history.size - i)?.createdAtMs
            Body(
                "v${v.number} · ${IntelligenceTexts.source(v.source)} · desde ${date(v.createdAtMs)}" +
                    (if (v.id == active?.id) " · actual" else until?.let { " hasta ${date(it)}" } ?: "")
            )
            val w = v.settings.weights
            Muted("Pesos ${w.kmPct}/${w.hourPct}/${w.pickupKmPct}/${w.pickupMinPct} · mín RD$/h ${v.settings.minRdpPerHour.toInt()} · BUENO ${v.settings.goodFrom} · EXCELENTE ${v.settings.excellentFrom}")
        }
        Muted("Los modelos de distintas fechas operaron en mercados distintos; la comparación justa es la prueba en sombra.")
        if (previous != null) OutlinedButton(onClick = onRollback) { Text("Restaurar v${previous.number}") }
    }
}
