package com.ubers.observador.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 1.50.0: the same four states everywhere, told apart in words (never by colour alone) and
 * announced by TalkBack when they appear.
 *
 * - Cargando: the figures are being read; nothing is shown as zero meanwhile.
 * - Vacío: there is nothing in this period, and what to do about it.
 * - Error: it could not be computed; the data were not touched.
 * - Parcial: what is shown is real but incomplete, with how much is covered.
 */
@Composable
fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun ErrorState(text: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Assertive }) {
        Text("Error: $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        Text("Tus datos no se tocaron.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onRetry?.let { TextButton(onClick = it) { Text("Reintentar") } }
    }
}

/** A one-line warning above figures that are real but not complete («Parcial: 6 de 9 viajes con dinero confirmado»). */
@Composable
fun PartialNote(text: String, modifier: Modifier = Modifier) {
    Text(
        "Parcial: $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()
    )
}
