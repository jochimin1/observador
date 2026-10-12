package com.ubers.observador.ui.intelligence

import com.ubers.observador.data.debug.IntelligenceTestSeeder
import com.ubers.observador.domain.ModelVersionSource
import com.ubers.observador.domain.intelligence.CorrelationStrength
import com.ubers.observador.domain.intelligence.DriftStatus
import com.ubers.observador.domain.intelligence.Insight
import com.ubers.observador.domain.intelligence.InsightType
import com.ubers.observador.domain.intelligence.IntelligenceAnomaly
import com.ubers.observador.domain.intelligence.ModelHealth
import com.ubers.observador.domain.intelligence.RecommendationStatus
import com.ubers.observador.domain.intelligence.SampleConfidence
import com.ubers.observador.domain.intelligence.ScoreComponent
import com.ubers.observador.domain.intelligence.ShadowGateReason
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every sentence of the Análisis screen in one place. Careful wording on purpose: "en tu
 * historial", "tendencia", never "vas a ganar", never "IA", never a cause from a correlation.
 */
object IntelligenceTexts {

    /** Always visible: the limits of what this screen can know. */
    val LIMITATIONS = listOf(
        "Solo conocemos el resultado real de los viajes que aceptaste.",
        "No conocemos el resultado de las ofertas que no aceptaste.",
        "Un score no es una probabilidad: ordena ofertas, no predice certezas.",
        "Una correlación no demuestra una causa.",
        "Con pocos viajes, las conclusiones pueden cambiar mucho.",
        "El mercado y el tráfico cambian con el tiempo.",
        "Es ingreso bruto, todavía no beneficio neto.",
        "Sin GPS no hay RD$/km real: solo por km estimado.",
        "Sin sesión en línea fiable no hay RD$/hora en línea total."
    )

    fun rd(value: Double): String = "RD$" + String.format(Locale.US, "%,.0f", value)
    fun rdHour(value: Double): String = rd(value) + "/h"
    fun rdMinor(minor: Long, signed: Boolean = false): String {
        val sign = if (minor < 0) "-" else if (signed && minor > 0) "+" else ""
        return sign + "RD$" + String.format(Locale.US, "%,.2f", abs(minor) / 100.0)
    }
    fun pct(ratio: Double, signed: Boolean = true): String {
        val v = (ratio * 100).roundToInt()
        return (if (signed && v > 0) "+" else "") + "$v%"
    }
    fun rho(value: Double): String = "ρ = " + String.format(Locale.US, "%.2f", value)

    fun confidence(c: SampleConfidence): String = when (c) {
        SampleConfidence.INSUFFICIENT -> "datos insuficientes"
        SampleConfidence.EXPLORATORY -> "preliminar"
        SampleConfidence.MODERATE -> "muestra moderada"
        SampleConfidence.STRONG -> "muestra amplia"
    }

    fun health(h: ModelHealth): String = when (h) {
        ModelHealth.HEALTHY -> "Funciona como se espera"
        ModelHealth.NEEDS_MORE_DATA -> "Necesita más datos"
        ModelHealth.DEGRADED -> "Hay señales de desajuste"
        ModelHealth.CALIBRATION_RECOMMENDED -> "Conviene probar un ajuste"
    }

    fun anomaly(a: IntelligenceAnomaly): String = when (a) {
        IntelligenceAnomaly.INSUFFICIENT_DATA -> "Menos de 10 viajes con resultado completo."
        IntelligenceAnomaly.LOW_FINANCIAL_COVERAGE -> "A muchos viajes les falta el dinero real."
        IntelligenceAnomaly.LOW_TIME_COVERAGE -> "A muchos viajes les faltan los tiempos."
        IntelligenceAnomaly.NON_MONOTONIC_SCORE_BUCKETS -> "Un score más alto no siempre trae mejor resultado."
        IntelligenceAnomaly.WEAK_SCORE_CORRELATION -> "La relación entre score y resultado es débil."
        IntelligenceAnomaly.HIGH_TIME_BIAS -> "El tiempo real se aleja mucho de la estimación."
        IntelligenceAnomaly.HIGH_HOURLY_PREDICTION_ERROR -> "El RD$/hora real se aleja mucho del previsto."
        IntelligenceAnomaly.MODEL_OVERFIT -> "Un ajuste probado solo funcionaba con datos viejos."
        IntelligenceAnomaly.MARKET_DRIFT -> "Los resultados recientes cambiaron frente a las semanas anteriores."
        IntelligenceAnomaly.SHADOW_REGRESSION -> "En sombra, el candidato falla más en viajes 'excelentes'."
        IntelligenceAnomaly.DATA_CAPTURE_DROP -> "Se detectan menos viajes que antes: primero revisa la captura de datos."
    }

    fun insight(i: Insight): String = when (i.type) {
        InsightType.TIME_UNDERESTIMATED ->
            "Los viajes tardan más de lo previsto: en ${i.sampleCount} viajes comparables, el tiempo real fue una mediana " +
                "${pct(i.metric)} sobre la estimación inicial. Eso reduce tu RD$/hora real frente al calculado."
        InsightType.TIME_OVERESTIMATED ->
            "Los viajes tardan menos de lo previsto: mediana ${pct(i.metric)} frente a la estimación (${i.sampleCount} viajes)."
        InsightType.SCORE_RANKS_WELL ->
            "El score está ordenando los resultados en la dirección esperada (${rho(i.metric)}, ${i.sampleCount} viajes)."
        InsightType.SCORE_NOT_MONOTONIC ->
            "Algunos tramos de score alto rindieron menos que tramos más bajos en ${i.sampleCount} viajes. Conviene revisar la calibración."
        InsightType.CATEGORIES_COLLAPSE ->
            "Dos categorías se comportan casi igual en esta muestra (${rdHour(i.comparison ?: 0.0)} y ${rdHour(i.metric)})."
        InsightType.EARNINGS_ABOVE_OFFER ->
            "El ingreso final suele quedar ${pct(i.metric)} sobre el precio ofertado (mediana, ${i.sampleCount} viajes)."
        InsightType.EARNINGS_BELOW_OFFER ->
            "El ingreso final suele quedar ${pct(i.metric)} bajo el precio ofertado (mediana, ${i.sampleCount} viajes)."
        InsightType.PICKUP_DISTANCE_HURTS ->
            "Entre tus viajes aceptados, las recogidas de más de 4 km rindieron una mediana de ${rdHour(i.metric)}, " +
                "frente a ${rdHour(i.comparison ?: 0.0)} con 4 km o menos (${i.sampleCount} viajes)."
    }

    fun strength(rho: Double): String {
        val s = when (CorrelationStrength.of(rho)) {
            CorrelationStrength.NONE -> "No se ve relación"
            CorrelationStrength.WEAK -> "Relación débil"
            CorrelationStrength.MODERATE -> "Relación moderada"
            CorrelationStrength.STRONG -> "Relación fuerte"
        }
        return when {
            CorrelationStrength.of(rho) == CorrelationStrength.NONE -> "$s en esta muestra."
            rho > 0 -> "$s y positiva en esta muestra: los scores más altos tienden a dar mejores resultados."
            else -> "$s pero negativa en esta muestra: los scores más altos tienden a dar peores resultados."
        }
    }

    fun component(c: ScoreComponent): String = when (c) {
        ScoreComponent.PREDICTED_HOURLY -> "RD$/hora previsto"
        ScoreComponent.EARNINGS_PER_ESTIMATED_KM -> "RD$ por km estimado"
        ScoreComponent.PICKUP_KM -> "Distancia de recogida"
        ScoreComponent.PICKUP_MINUTES -> "Tiempo de recogida"
    }

    fun recommendation(s: RecommendationStatus): String = when (s) {
        RecommendationStatus.NOT_ENOUGH_DATA -> "Hacen falta al menos 30 viajes con resultado completo para probar ajustes."
        RecommendationStatus.NO_CHANGE -> "Ningún ajuste de pesos mejoró de forma clara y confirmada en los viajes recientes. Sin cambios."
        RecommendationStatus.EXPERIMENTAL_CANDIDATE -> "Hay un ajuste que ordena mejor tus viajes, pero con pocos datos: experimental."
        RecommendationStatus.SHADOW_RECOMMENDED -> "Hay un ajuste que ordenó mejor tus viajes, también en los más recientes. Recomendado probarlo en sombra."
        RecommendationStatus.READY_FOR_USER_REVIEW -> "Listo para tu revisión."
        RecommendationStatus.REJECTED_OVERFIT -> "Un ajuste mejoraba en los viajes antiguos pero empeoraba en los recientes: descartado."
    }

    fun gateReason(r: ShadowGateReason): String = when (r) {
        ShadowGateReason.TOO_FEW_RESULTS -> "faltan viajes con resultado (mínimo 30)"
        ShadowGateReason.LOW_COVERAGE -> "a muchos viajes evaluados les falta el resultado"
        ShadowGateReason.NOT_BETTER -> "el candidato no ordena claramente mejor"
        ShadowGateReason.CRITICAL_REGRESSION -> "el candidato falla más en viajes 'excelentes'"
    }

    fun drift(s: DriftStatus): String? = when (s) {
        DriftStatus.INSUFFICIENT_DATA -> null
        DriftStatus.STABLE -> "Últimos 14 días: resultados parecidos a los 14 anteriores."
        DriftStatus.CHANGED -> "Últimos 14 días: el RD$/hora real cambió frente a los 14 anteriores."
        DriftStatus.CAPTURE_DROP -> "Últimos 14 días: se detectaron muchos menos viajes. Revisa la captura antes de sacar conclusiones."
    }

    fun source(s: ModelVersionSource): String = when (s) {
        ModelVersionSource.INITIAL -> "inicial"
        ModelVersionSource.USER_EDIT -> "editado en Ajustes"
        ModelVersionSource.CALIBRATION_RECOMMENDATION -> "candidato de calibración"
        ModelVersionSource.SHADOW_PROMOTED -> "aplicado tras prueba en sombra"
        ModelVersionSource.ROLLBACK -> "restaurado"
    }

    fun scenario(s: IntelligenceTestSeeder.Scenario): String = when (s) {
        IntelligenceTestSeeder.Scenario.GOOD_MODEL -> "modelo que funciona"
        IntelligenceTestSeeder.Scenario.BAD_MODEL -> "modelo que falla"
        IntelligenceTestSeeder.Scenario.PICKUP_MATTERS -> "la recogida pesa más"
        IntelligenceTestSeeder.Scenario.OVERFIT -> "sobreajuste"
    }
}
