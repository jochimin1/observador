package com.ubers.observador.ui.intelligence

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.R
import com.ubers.observador.data.AppSettings
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.domain.sectors.SectorCount
import com.ubers.observador.domain.sectors.SectorStats
import com.ubers.observador.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 1.36.0: offers of the last 7 days in each care sector. [sectors] = how many the driver has listed. */
data class SectorWeekState(val sectors: Int = 0, val offers: Int = 0, val counts: List<SectorCount> = emptyList())

class SectorWeekViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<SectorWeekState> = AppSettings(app).careSectors.map { sectors ->
        if (sectors.isEmpty()) return@map SectorWeekState()
        val since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val pairs = ArrayList<Pair<String?, String?>>()
        try {
            AppDatabase.getInstance(getApplication()).openHelper.readableDatabase.query(
                "SELECT pickup_address, dropoff_address FROM detected_offers WHERE detectedAt >= ?",
                arrayOf<Any?>(since)
            ).use { c ->
                while (c.moveToNext()) {
                    pairs += (if (c.isNull(0)) null else c.getString(0)) to (if (c.isNull(1)) null else c.getString(1))
                }
            }
        } catch (e: Exception) {
            Log.w("SectorWeekViewModel", "Could not read the week's offers", e)
        }
        SectorWeekState(sectors.size, pairs.size, SectorStats.countsOf(pairs, sectors))
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectorWeekState())
}

/** Shown in Análisis once the driver has listed at least one care sector. */
@Composable
fun SectorWeekCard(viewModel: SectorWeekViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    if (state.sectors == 0) return
    val flagged = state.counts.sumOf { it.total }
    SectionCard(
        title = "Sectores de cuidado esta semana",
        icon = R.drawable.ic_warning,
        subtitle = "${state.offers} ofertas en 7 días"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (flagged == 0) {
                Text("Ninguna oferta de estos 7 días cayó en tus sectores.", style = MaterialTheme.typography.bodyMedium)
            }
            state.counts.forEach { c ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        listOfNotNull(
                            c.pickup.takeIf { it > 0 }?.let { "recogida $it" },
                            c.dropoff.takeIf { it > 0 }?.let { "destino $it" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Se cuenta con tu lista de sectores de hoy y las direcciones guardadas de cada oferta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
