package com.ubers.observador.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.domain.ProfileSuggestion
import com.ubers.observador.domain.SettingsProfile

/**
 * 1.44.0: which profile is in use and, when another one is made for this moment, a suggestion.
 * Nothing changes until the driver taps «Usar»; «Ahora no» hides it until the moment changes.
 */
@Composable
fun ProfileSuggestionCard(
    inUse: SettingsProfile?,
    suggestion: ProfileSuggestion?,
    onUse: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestion == null && inUse == null) return
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (suggestion != null) scheme.tertiaryContainer else scheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Perfil en uso: ${inUse?.name ?: "ninguno guardado"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (suggestion != null) scheme.onTertiaryContainer else scheme.onSurfaceVariant
            )
            suggestion?.let { s ->
                Text(
                    "${s.reason}: ¿usar «${s.profile.name}»? Cambia tus mínimos, pesos y cortes de una vez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onTertiaryContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onUse(s.profile.id) }) { Text("Usar «${s.profile.name}»") }
                    TextButton(onClick = { onDismiss(s.profile.id) }) { Text("Ahora no") }
                }
            }
        }
    }
}
