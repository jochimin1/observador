package com.ubers.observador.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.sectors.CareSector
import com.ubers.observador.domain.sectors.CareSectorCodec
import com.ubers.observador.domain.sectors.SectorMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SectorsUiState(
    val enabled: Boolean = true,
    val sectors: List<CareSector> = emptyList(),
    /** Sectors seen in the offers of the last 90 days and not in the list yet, most frequent first. */
    val suggestions: List<String> = emptyList(),
    /** 1.37.0: today's closing log («14:03:12 · SIMULADA · Destino: Engombe · RD$294.67»). */
    val closeLog: List<String> = emptyList(),
    /** 1.47.0: modalities seen in the offers («Moto»), for the rule and the tester. */
    val products: List<String> = emptyList(),
    /** 1.47.0: the latest distinct addresses, to try the rules on real ones. */
    val recentAddresses: List<String> = emptyList()
)

private data class PastOffers(val suggestions: List<String> = emptyList(), val products: List<String> = emptyList(), val recent: List<String> = emptyList())

/** 1.35.0: Ajustes › Sectores de cuidado. */
class SectorsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val seen = MutableStateFlow(PastOffers())
    private val closeLog = MutableStateFlow<List<String>>(emptyList())

    val state: StateFlow<SectorsUiState> =
        combine(settings.careSectorsEnabled, settings.careSectors, seen, closeLog) { enabled, sectors, past, log ->
            val taken = sectors.flatMap { it.names }.map { SectorMatcher.words(it) }.toSet()
            SectorsUiState(enabled, sectors, past.suggestions.filter { SectorMatcher.words(it) !in taken }.take(12), log, past.products, past.recent)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectorsUiState())

    /** 1.37.0: reads today's closing log again. */
    fun refreshCloseLog() {
        viewModelScope.launch(Dispatchers.IO) {
            closeLog.value = com.ubers.observador.service.CloseLogStore.todayLines(getApplication()).takeLast(30).reversed()
        }
    }

    init {
        refreshCloseLog()
        viewModelScope.launch(Dispatchers.IO) {
            seen.value = try {
                com.ubers.observador.domain.backup.MaintenanceGate.shared.access {
                val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                val addresses = ArrayList<String?>()
                val products = LinkedHashMap<String, Int>()
                AppDatabase.getInstance(getApplication()).openHelper.readableDatabase.query(
                    "SELECT pickup_address, dropoff_address, product FROM detected_offers WHERE detectedAt >= ? ORDER BY detectedAt DESC LIMIT 3000",
                    arrayOf<Any?>(since)
                ).use { c ->
                    while (c.moveToNext()) {
                        addresses += if (c.isNull(0)) null else c.getString(0)
                        addresses += if (c.isNull(1)) null else c.getString(1)
                        if (!c.isNull(2)) c.getString(2).trim().takeIf { it.isNotEmpty() }?.let { products[it] = (products[it] ?: 0) + 1 }
                    }
                }
                PastOffers(
                    SectorMatcher.suggestions(addresses, emptyList(), limit = 30),
                    products.entries.sortedByDescending { it.value }.map { it.key }.take(8),
                    addresses.filterNotNull().filter { it.isNotBlank() }.distinct().take(20)
                )
                }
            } catch (e: Exception) {
                Log.w("SectorsViewModel", "Could not read past addresses", e)
                PastOffers()
            }
        }
    }

    fun setEnabled(on: Boolean) {
        com.ubers.observador.service.UberAccessibilityService.instance?.cancelPendingSectorCloses()
        viewModelScope.launch { settings.updateCareSectorsEnabled(on) }
    }

    /** Saves [sector]; when editing, [replacing] is the one it replaces (its name may have changed). */
    fun save(sector: CareSector, replacing: CareSector?) {
        val clean = sector.copy(
            name = CareSectorCodec.cleanName(sector.name).take(CareSector.MAX_NAME),
            aliases = sector.aliases.filterNot { SectorMatcher.words(it) == SectorMatcher.words(sector.name) }
        )
        if (clean.name.isEmpty()) return
        com.ubers.observador.service.UberAccessibilityService.instance?.cancelPendingSectorCloses()
        viewModelScope.launch {
            var list = settings.careSectors.first()
            if (replacing != null) list = list.filterNot { SectorMatcher.words(it.name) == SectorMatcher.words(replacing.name) }
            settings.updateCareSectors(CareSectorCodec.upsert(list, clean))
        }
    }

    /** 1.47.0: Probar reglas. With [product] null the modality rules count as «otra modalidad». */
    fun explain(address: String, place: com.ubers.observador.domain.sectors.OfferPlaceKind, product: String?): List<SectorMatcher.Explanation> =
        SectorMatcher.explain(
            address, place, state.value.sectors,
            com.ubers.observador.domain.sectors.SectorContext(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), product)
        )

    fun remove(sector: CareSector) {
        com.ubers.observador.service.UberAccessibilityService.instance?.cancelPendingSectorCloses()
        viewModelScope.launch {
            val key = SectorMatcher.words(sector.name)
            settings.updateCareSectors(settings.careSectors.first().filterNot { SectorMatcher.words(it.name) == key })
        }
    }
}
