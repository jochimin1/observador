package com.ubers.observador

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
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
        // The driver may have changed the location permission in system settings
        com.ubers.observador.ui.AppPermissions.refresh(this)
    }

    private fun requestPermissionsIfNeeded() {
        // Needed for the Assisted-mode alerts. The overlay itself is an accessibility window
        // and does not need SYSTEM_ALERT_WINDOW.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** The main destinations, one per tab of the bottom bar. Ganancias and Gastos arrive with Fase 8C and 8B. */
private enum class Tab(val route: String, val label: String, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int) {
    HOME("dashboard", "Inicio", R.drawable.ic_home, R.drawable.ic_home_fill),
    ANALYSIS("intelligence", "Análisis", R.drawable.ic_analytics, R.drawable.ic_analytics_fill),
    SETTINGS("settings", "Ajustes", R.drawable.ic_settings, R.drawable.ic_settings_fill)
}

@Composable
private fun ObservadorNavHost(accessibilityEnabled: Boolean) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    fun open(tab: Tab) = navController.navigate(tab.route) {
        // One copy of each tab; coming back keeps its scroll and state
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = current == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { open(tab) },
                        icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.HOME.route,
            // Only the bottom bar's height: each screen draws its own top bar
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable(Tab.HOME.route) {
                DashboardScreen(accessibilityEnabled = accessibilityEnabled)
            }
            composable(Tab.ANALYSIS.route) {
                IntelligenceScreen(onNavigateBack = null)
            }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(onNavigateBack = null)
            }
        }
    }
}
