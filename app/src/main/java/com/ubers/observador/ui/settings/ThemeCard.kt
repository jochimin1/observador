package com.ubers.observador.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ubers.observador.domain.AppTheme
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.Palette
import com.ubers.observador.domain.ThemePalettes

/**
 * Fase 8G: the five themes, each with a small preview drawn with its own colors (a card, a line
 * of text, the accent and a BUENO badge, which looks the same in all of them).
 */
@Composable
fun ThemeCard(selected: AppTheme, onSelect: (AppTheme) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tema", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                "Noche es negro puro: menos brillo y menos batería de noche. Sol sube el contraste para leer a pleno sol. " +
                    "Los colores de MALO, BUENO y EXCELENTE no cambian con el tema.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(AppTheme.entries.take(3), AppTheme.entries.drop(3)).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { theme ->
                        ThemeTile(theme, theme == selected, Modifier.weight(1f)) { onSelect(theme) }
                    }
                    // Keep the two tiles of the second row the same width as the first
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            Text(
                AppTheme.entries.first { it == selected }.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeTile(theme: AppTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (theme == AppTheme.SYSTEM) {
                Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp))) {
                    Preview(ThemePalettes.LIGHT, Modifier.weight(1f))
                    Preview(ThemePalettes.DARK, Modifier.weight(1f))
                }
            } else {
                val p = when (theme) {
                    AppTheme.LIGHT -> ThemePalettes.LIGHT
                    AppTheme.DARK -> ThemePalettes.DARK
                    AppTheme.NIGHT -> ThemePalettes.NIGHT
                    else -> ThemePalettes.SUN
                }
                Preview(p, Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)))
            }
            Text(
                theme.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Preview(p: Palette, modifier: Modifier) {
    Box(modifier.background(Color(p.background)).padding(6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(Color(p.surfaceContainerHigh))) {
                Box(Modifier.width(18.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color(p.onSurface)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(p.primary)))
                Box(Modifier.size(width = 16.dp, height = 8.dp).clip(RoundedCornerShape(3.dp)).background(Color(OfferQuality.GOOD.backgroundArgb)))
            }
        }
    }
}
