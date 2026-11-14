package com.ubers.observador.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.BuildConfig

/** Asset generated at build time from docs/releases/changelogs/<versionName>.md (see app/build.gradle.kts). */
private const val RELEASE_NOTES_ASSET = "novedades.txt"

/** "Novedades de esta versión": what the installed build brought, below the version line in Ajustes. */
@Composable
fun ReleaseNotesCard() {
    val context = LocalContext.current
    val items = remember {
        runCatching {
            context.assets.open(RELEASE_NOTES_ASSET).bufferedReader(Charsets.UTF_8).use { r ->
                r.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }
    if (items.isEmpty()) return
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Novedades de la versión ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(item, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
