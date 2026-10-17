package com.ubers.observador.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
import com.ubers.observador.domain.AppClose
import com.ubers.observador.service.AppCloser
import com.ubers.observador.service.DistanceMonitor
import com.ubers.observador.service.RideLifecycleMonitor
import kotlinx.coroutines.launch

/** Fase 8L: asks before closing, and says what an open trip or measurement would lose. */
@Composable
fun CloseAppDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ride by RideLifecycleMonitor.state.collectAsState()
    val distance by DistanceMonitor.state.collectAsState()
    val rideActive = ride.context.state.isActiveTrip
    val lines = AppClose.warning(rideActive = rideActive, measuring = distance.measuring != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_power), contentDescription = null) },
        title = { Text(AppClose.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (line == AppClose.RIDE) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (line == AppClose.RIDE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                scope.launch { AppCloser.close(context) }
            }) { Text(if (rideActive) "Cerrar de todos modos" else "Cerrar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
