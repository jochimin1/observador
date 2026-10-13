package com.ubers.observador.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ubers.observador.service.ObservadorForegroundService
import com.ubers.observador.ui.AppPermissions

/**
 * Asks for precise location (Fase 8E) and, when granted, lets the running service take the
 * "location" type at once. Returns the function that launches the request; [onResult] gets
 * whether precise location was granted.
 */
@Composable
fun rememberLocationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        AppPermissions.refresh(context)
        val granted = ObservadorForegroundService.hasLocationPermission(context)
        if (granted) ObservadorForegroundService.refresh(context)
        onResult(granted)
    }
    return {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
