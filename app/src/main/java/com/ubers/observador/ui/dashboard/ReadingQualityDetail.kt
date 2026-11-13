package com.ubers.observador.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.ubers.observador.data.db.OfferEntity

@Composable
internal fun ReadingQualityDetail(offer: OfferEntity, onToggleExcluded: (() -> Unit)?) {
    var expanded by remember(offer.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Calidad de lectura", style = MaterialTheme.typography.titleSmall)
        Text(when {
            offer.excludedFromAnalysis -> "Lectura dudosa · excluida de los análisis"
            offer.parserVersion == null -> "Registro anterior: sin datos de lectura"
            offer.readingReasons.isNotEmpty() -> "Lectura con observaciones"
            else -> "Lectura completa"
        }, color = if (offer.excludedFromAnalysis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (offer.excludedFromAnalysis) Text("Se conserva en el historial y en las exportaciones. Sus cifras no participan en estadísticas de ofertas ni en la calibración; el dinero real no se modifica.", style = MaterialTheme.typography.bodySmall)
        onToggleExcluded?.let { toggle ->
            TextButton(onClick = toggle) { Text(if (offer.excludedFromAnalysis) "Volver a incluir en análisis" else "Marcar lectura como dudosa") }
        }
        if (offer.parserVersion != null) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar datos de lectura" else "Ver datos de lectura") }
            if (expanded) {
                Text("Uber observado: ${offer.uberVersion ?: "versión no disponible"}", style = MaterialTheme.typography.bodySmall)
                Text("Lector: ${offer.parserVersion}\nClasificador: ${offer.classifierVersion ?: "no disponible"}", style = MaterialTheme.typography.bodySmall)
                if (offer.readingReasons.isNotEmpty()) Text("Observaciones: ${offer.readingReasons}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
