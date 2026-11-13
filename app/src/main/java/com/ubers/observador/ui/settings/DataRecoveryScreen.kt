package com.ubers.observador.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ubers.observador.data.backup.AppRestart
import com.ubers.observador.data.backup.DataRecovery
import kotlinx.coroutines.launch

/** Failed recovery has its own screen; it must not construct ViewModels using an uncertain DB. */
@Composable
fun DataRecoveryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = try {
                val out = context.contentResolver.openOutputStream(uri) ?: error("No destination")
                DataRecovery.exportPrevious(context, out)
                "Copia anterior guardada y verificada."
            } catch (_: Exception) { "No se pudo guardar la copia. Los archivos locales se conservan." }
            busy = false
        }
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Recuperación pendiente", style = MaterialTheme.typography.headlineSmall)
                Text(DataRecovery.failure.orEmpty())
                OutlinedButton(onClick = { save.launch("observador-recuperacion.zip") }, enabled = !busy) { Text("Guardar copia anterior") }
                Button(onClick = { AppRestart.now(context) }, enabled = !busy) { Text("Reintentar recuperación") }
                message?.let { Text(it) }
            }
        }
    }
}
