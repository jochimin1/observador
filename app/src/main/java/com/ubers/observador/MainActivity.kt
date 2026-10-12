package com.ubers.observador

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ubers.observador.service.ObservadorForegroundService
import com.ubers.observador.service.UberAccessibilityService
import com.ubers.observador.ui.dashboard.DashboardScreen
import com.ubers.observador.ui.intelligence.IntelligenceScreen
import com.ubers.observador.ui.settings.SettingsScreen
import com.ubers.observador.ui.theme.ObservadorTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* start service regardless */ }

    // Refreshed on every resume so the dashboard reflects the system accessibility toggle
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        ObservadorForegroundService.start(this)
        setContent {
            ObservadorTheme {
                ObservadorNavHost(accessibilityEnabled = accessibilityEnabled)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = UberAccessibilityService.isEnabled(this)
    }

    private fun requestPermissionsIfNeeded() {
        // Needed for the Assisted-mode alerts. The overlay itself is an accessibility window
        // and does not need SYSTEM_ALERT_WINDOW.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun ObservadorNavHost(accessibilityEnabled: Boolean) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToIntelligence = { navController.navigate("intelligence") },
                accessibilityEnabled = accessibilityEnabled
            )
        }
        composable("intelligence") {
            IntelligenceScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
