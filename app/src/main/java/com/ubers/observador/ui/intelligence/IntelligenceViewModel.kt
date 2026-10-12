package com.ubers.observador.ui.intelligence

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.FinancialRepository
import com.ubers.observador.data.IntelligenceRepository
import com.ubers.observador.data.TripRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.IntelligenceTestSeeder
import com.ubers.observador.data.debug.TripTestSeeder
import com.ubers.observador.domain.ProfitModelVersion
import com.ubers.observador.domain.intelligence.IntelligenceEngine
import com.ubers.observador.domain.intelligence.IntelligenceInput
import com.ubers.observador.domain.intelligence.IntelligenceReport
import com.ubers.observador.domain.intelligence.ModelVersionRules
import com.ubers.observador.domain.intelligence.OfferDecisionInput
import com.ubers.observador.domain.intelligence.OfferDecisionOutcome
import com.ubers.observador.domain.intelligence.ShadowScoring
import com.ubers.observador.domain.intelligence.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class IntelligencePeriod(val label: String, val days: Int?) {
    D30("30 días", 30), D90("90 días", 90), ALL("Todo", null)
}

data class IntelligenceUiState(
    val loading: Boolean = true,
    val period: IntelligencePeriod = IntelligencePeriod.D90,
    val report: IntelligenceReport? = null,
    val versions: List<ProfitModelVersion> = emptyList(),
    val active: ProfitModelVersion? = null,
    val previous: ProfitModelVersion? = null,
    val shadowCandidate: ProfitModelVersion? = null,
    val targetHourly: Double? = null,
    /** TEST- trips are in the period: candidates built from them can never be applied. */
    val usesTestData: Boolean = false,
    val failed: Boolean = false
)

/**
 * Coordinates only: reads through repositories, runs the pure analysis on Dispatchers.Default and
 * exposes one state. Recomputed when trips, money, settings, model versions, shadow scores or
 * the period change — never per frame. Every change to the active model is an explicit user
 * action from this screen, after a confirmation dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntelligenceViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val repo = IntelligenceRepository(db)
    private val trips = TripRepository(db)
    private val money = FinancialRepository(db)
    private val settings = AppSettings(app)
    private val period = MutableStateFlow(IntelligencePeriod.D90)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val triggers = combine(
        trips.observeCounts(),
        db.financialDao().observeObservationCount(),
        settings.profitSettings,
        settings.shadowModelVersionId,
        repo.observeVersions()
    ) { _, _, profit, shadowId, versions -> Triple(profit, shadowId, versions) }

    val state: StateFlow<IntelligenceUiState> = combine(triggers, repo.observeShadowCount(), period) { t, _, p -> t to p }
        .mapLatest { (t, p) -> compute(t.first, t.second, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntelligenceUiState())

    fun selectPeriod(p: IntelligencePeriod) {
        period.value = p
    }

    fun clearMessage() {
        _message.value = null
    }

    private suspend fun compute(
        profit: com.ubers.observador.domain.ProfitSettingsSnapshot,
        shadowId: String?,
        p: IntelligencePeriod
    ): IntelligenceUiState = try {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val from = p.days?.let { now - it * DAY } ?: 0L
        val active = repo.ensureCurrent(profit, now)
        val versions = repo.versions()
        val dataset = repo.loadDataset(from, now + 1)
        val accepted = repo.acceptedOfferHashesSince(from)
        val offers = repo.offersSince(from)
        val candidate = shadowId?.let { repo.version(it) }
        val evaluations = candidate?.let { repo.shadowEvaluations(it.id) } ?: emptyList()
        val usesTest = dataset.samples.any { it.tripId.startsWith(TripTestSeeder.PREFIX) }
        val report = withContext(Dispatchers.Default) {
            // Fase 5's market (offers seen) by 2-hour slot, passed apart and only compared, never mixed
            val market = offers.groupBy { Instant.ofEpochMilli(it.detectedAt).atZone(zone).hour / 2 }
                .mapValues { (_, l) -> Stats.median(l.map { it.rdpPerHour }) ?: 0.0 }
            val decisions = offers.map {
                OfferDecisionInput(
                    it.offerHash, it.score, profit.thresholds.classify(it.score),
                    if (it.offerHash in accepted) OfferDecisionOutcome.ACCEPTED else OfferDecisionOutcome.UNKNOWN
                )
            }
            IntelligenceEngine.analyze(
                IntelligenceInput(
                    dataset = dataset,
                    activeSettings = profit,
                    activeVersion = active,
                    targetHourly = profit.minRdpPerHour,
                    offers = decisions,
                    shadowCandidate = candidate,
                    shadowEvaluations = evaluations,
                    observedMarketHourlyBySlot = market,
                    periodStartMs = p.days?.let { from },
                    nowMs = now,
                    zone = zone
                ),
                newId = { (if (usesTest) TripTestSeeder.PREFIX else "") + "cand-" + UUID.randomUUID() }
            )
        }
        IntelligenceUiState(
            loading = false,
            period = p,
            report = report,
            versions = versions,
            active = active,
            previous = ModelVersionRules.previous(versions),
            shadowCandidate = candidate,
            targetHourly = profit.minRdpPerHour,
            usesTestData = usesTest
        )
    } catch (e: Exception) {
        Log.e(TAG, "Analysis failed", e)
        IntelligenceUiState(loading = false, period = p, failed = true)
    }

    /** "Probar en sombra": stores the calibration and its candidate; the active model is untouched. */
    fun startShadow() = viewModelScope.launch {
        val s = state.value
        val report = s.report ?: return@launch
        if (report.calibration.candidate == null) return@launch
        _message.value = try {
            val prefix = if (s.usesTestData) TripTestSeeder.PREFIX else ""
            val start = s.period.days?.let { System.currentTimeMillis() - it * DAY }
            val candidate = repo.recordCandidate(report.calibration, start, report.quality.completedTrips, System.currentTimeMillis(), prefix)
            settings.updateShadowModel(candidate?.id)
            "Modelo candidato en sombra. Tu score no cambia: el candidato solo se calcula en silencio."
        } catch (e: Exception) {
            Log.e(TAG, "Start shadow failed", e)
            "No se pudo iniciar la prueba en sombra."
        }
    }

    fun stopShadow() = viewModelScope.launch {
        settings.updateShadowModel(null)
        _message.value = "Prueba en sombra detenida. Lo evaluado se conserva."
    }

    /** Only after the dialog, and only when the shadow gate is open. */
    fun promote() = viewModelScope.launch {
        val s = state.value
        val candidate = s.shadowCandidate ?: return@launch
        val shadow = s.report?.shadow ?: return@launch
        if (candidate.id.startsWith(TripTestSeeder.PREFIX)) {
            _message.value = "Este candidato viene de datos de prueba: no se aplica."
            return@launch
        }
        _message.value = try {
            when (val r = ModelVersionRules.promote(repo.versions(), candidate, shadow, userConfirmed = true, nowMs = System.currentTimeMillis(), newId = ::newId)) {
                is ModelVersionRules.PromotionResult.Promoted -> {
                    repo.insertVersion(r.version)
                    settings.applyProfitSettings(r.version.settings)
                    settings.updateShadowModel(null)
                    "Modelo v${r.version.number} aplicado. Puedes volver al anterior desde el historial."
                }
                is ModelVersionRules.PromotionResult.Refused -> "Aún no: " + r.reasons.joinToString { IntelligenceTexts.gateReason(it) }
                ModelVersionRules.PromotionResult.NotConfirmed -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Promote failed", e)
            "No se pudo aplicar el modelo."
        }
    }

    fun rollback() = viewModelScope.launch {
        _message.value = try {
            val version = ModelVersionRules.rollback(repo.versions(), System.currentTimeMillis(), ::newId)
            if (version == null) {
                "No hay modelo anterior."
            } else {
                repo.insertVersion(version)
                settings.applyProfitSettings(version.settings)
                "Restaurado: ahora v${version.number}, con los valores exactos del modelo anterior."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed", e)
            "No se pudo restaurar el modelo anterior."
        }
    }

    // ---- Debug builds only ----

    fun loadTestScenario(scenario: IntelligenceTestSeeder.Scenario) = viewModelScope.launch {
        _message.value = try {
            val now = System.currentTimeMillis()
            val seed = IntelligenceTestSeeder.generate(scenario, now)
            clearTest()
            trips.replaceTestTrips(TripTestSeeder.PREFIX, seed.trips, seed.events)
            money.loadTestData(TripTestSeeder.PREFIX, seed.observations, emptyList(), now)
            "${seed.trips.size} viajes de prueba cargados (${IntelligenceTexts.scenario(scenario)})."
        } catch (e: Exception) {
            Log.e(TAG, "Loading test scenario failed", e)
            "No se pudieron cargar los datos de prueba."
        }
    }

    /** Scores the TEST trips' offers as if the TEST candidate had been in shadow when they appeared. */
    fun simulateTestShadow() = viewModelScope.launch {
        val s = state.value
        val candidate = s.shadowCandidate
        val active = s.active
        val report = s.report
        _message.value = if (candidate == null || active == null || report == null || !candidate.id.startsWith(TripTestSeeder.PREFIX)) {
            "Primero pulsa \"Probar en sombra\" con datos de prueba cargados."
        } else {
            val now = System.currentTimeMillis()
            val dataset = repo.loadDataset(0, now + 1)
            val evaluations = dataset.samples.filter { it.tripId.startsWith(TripTestSeeder.PREFIX) && it.features != null && it.offerHash != null }
                .map { x ->
                    val e = ShadowScoring.evaluate(x.offerHash!!, x.features!!, active, candidate, x.features.detectedAtMs)
                    e.copy(evaluationId = TripTestSeeder.PREFIX + e.evaluationId)
                }
            repo.saveShadowBatch(evaluations)
            "${evaluations.size} ofertas de prueba puntuadas en sombra."
        }
    }

    fun clearTestData() = viewModelScope.launch {
        _message.value = try {
            clearTest()
            "Datos de prueba borrados. Los reales no se tocan."
        } catch (e: Exception) {
            Log.e(TAG, "Clearing test data failed", e)
            "No se pudieron borrar los datos de prueba."
        }
    }

    private suspend fun clearTest() {
        if (settings.shadowModelVersionId.first()?.startsWith(TripTestSeeder.PREFIX) == true) settings.updateShadowModel(null)
        repo.deleteTestData(TripTestSeeder.PREFIX)
        money.deleteTestData(TripTestSeeder.PREFIX)
        trips.deleteTestTrips(TripTestSeeder.PREFIX)
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "Intelligence"
        const val DAY = 86_400_000L
    }
}
