package com.ubers.observador.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ubers.observador.BuildConfig
import com.ubers.observador.data.AppSettings
import com.ubers.observador.service.HealthMonitor
import com.ubers.observador.ui.settings.DistanceDebugCard
import com.ubers.observador.ui.settings.ExpenseDebugCard
import com.ubers.observador.ui.settings.FinanceDebugCard
import com.ubers.observador.ui.settings.LifecycleDebugCard
import com.ubers.observador.ui.settings.OfferDebugCard
import com.ubers.observador.ui.settings.OnlineDebugCard
import com.ubers.observador.ui.settings.ReadingAuditSection
import com.ubers.observador.ui.settings.SettingsViewModel
import com.ubers.observador.ui.settings.TripsDebugCard
import kotlinx.coroutines.launch

/**
 * 1.39.0: the technical tools in one explicit place, away from the settings that change how the
 * app works. Captures and the reading register exist in every build; test data, simulated faults
 * and the detector panels only in the development build.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    Scaffold(
        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        topBar = {
            TopAppBar(
                colors = com.ubers.observador.ui.theme.topBarColors(),
                title = { Text("Laboratorio") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Volver") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Herramientas técnicas para revisar cómo lee Observador. No cambian tus cifras.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OfferDebugCard()
            ReadingAuditSection()

            if (BuildConfig.DEBUG) {
                Text("Versión de desarrollo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                ShowTestDataCard()
                val testMessage by viewModel.testDataMessage.collectAsState()
                TestDataCard(message = testMessage, onLoad = { viewModel.loadTestOffers() }, onClear = { viewModel.clearTestOffers() })
                SimulatedFaultCard()
                LifecycleDebugCard()
                OnlineDebugCard()
                DistanceDebugCard()
                ExpenseDebugCard()
                TripsDebugCard()
                FinanceDebugCard()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One switch for every screen: TEST- rows are real figures only while this is on. */
@Composable
private fun ShowTestDataCard() {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context.applicationContext) }
    val on by settings.showTestData.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mostrar datos de prueba", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    "Incluye las filas TEST- en Inicio, Ganancias, Gastos y sus exportaciones. Apagado, solo cuentan los datos reales.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = on, onCheckedChange = { v -> scope.launch { settings.updateShowTestData(v) } })
        }
    }
}

/** Loads or removes the known set of fake offers. */
@Composable
private fun TestDataCard(message: String?, onLoad: () -> Unit, onClear: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Ofertas de prueba", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                text = "Carga 160 ofertas: 60 en hoy, ayer y hace 6 días para comprobar el resumen y la lista, y 100 en " +
                    "otros 6 días con un patrón conocido para Mejores horas. Van marcadas como prueba (TEST-): solo se " +
                    "ven con «Mostrar datos de prueba» y al borrarlas tus ofertas reales no se tocan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad) { Text("Cargar") }
                OutlinedButton(onClick = onClear) { Text("Borrar") }
            }
            if (message != null) Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A write that fails on purpose: «Guardado» in Salud must react, and nothing is written. */
@Composable
private fun SimulatedFaultCard() {
    var message by remember { mutableStateOf<String?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Simular un fallo de guardado", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                "Envía a la cola una escritura que falla sin escribir nada. «Guardado» pasa a «Revisar» en Salud y queda un incidente; las siguientes escrituras siguen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = {
                message = if (HealthMonitor.simulateWriteFailure()) "Fallo simulado enviado." else "La cola no aceptó el trabajo."
            }) { Text("Simular") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
