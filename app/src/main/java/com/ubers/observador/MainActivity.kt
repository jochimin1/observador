package com.ubers.observador

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
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
        handleOpen(intent)
        setContent {
            val themeFlow = remember { com.ubers.observador.data.AppSettings(applicationContext).themeSettings }
            // Read once before the first frame, so a "Noche" driver never sees a light flash
            val initialTheme = remember { kotlinx.coroutines.runBlocking { themeFlow.first() } }
            val themeSettings by themeFlow.collectAsState(initial = initialTheme)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            // "Noche" by the hour: the hour is checked every minute
            val hour by androidx.compose.runtime.produceState(java.time.LocalTime.now().hour) {
                while (true) {
                    value = java.time.LocalTime.now().hour
                    kotlinx.coroutines.delay(60_000)
                }
            }
            val resolved = com.ubers.observador.domain.ThemeResolver.resolve(
                themeSettings, systemDark, hour
            )
            // Status and navigation bar icons follow the theme drawn, not the phone's setting
            androidx.compose.runtime.LaunchedEffect(resolved.isDark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (resolved.isDark) androidx.activity.SystemBarStyle.dark(transparent)
                    else androidx.activity.SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (resolved.isDark) androidx.activity.SystemBarStyle.dark(transparent)
                    else androidx.activity.SystemBarStyle.light(transparent, transparent)
                )
            }
            ObservadorTheme(resolved) {
                ObservadorNavHost(accessibilityEnabled = accessibilityEnabled)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** A notification asked to open a screen (Fase 8D: the weekly summary). */
    private fun handleOpen(intent: Intent?) {
        intent?.getStringExtra(com.ubers.observador.ui.AppNavigation.EXTRA_OPEN)?.let {
            com.ubers.observador.ui.AppNavigation.pending.value = it
            intent.removeExtra(com.ubers.observador.ui.AppNavigation.EXTRA_OPEN)
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = UberAccessibilityService.isEnabled(this)
        // The driver may have changed the location permission in system settings
        com.ubers.observador.ui.AppPermissions.refresh(this)
        // Oil and other reminders (Fase 8B): checked when the app opens, notified once per level
        lifecycleScope.launch { com.ubers.observador.service.MaintenanceNotifier.check(this@MainActivity) }
        // "Meta alcanzada" (Fase 8C), only if the driver turned it on
        lifecycleScope.launch { com.ubers.observador.service.GoalNotifier.check(this@MainActivity) }
        // The weekly summary alarm (Fase 8D), set again in case the system dropped it
        lifecycleScope.launch { com.ubers.observador.service.WeeklyReportNotifier.reschedule(this@MainActivity) }
    }

    private fun requestPermissionsIfNeeded() {
        // Needed for the Assisted-mode alerts. The overlay itself is an accessibility window
        // and does not need SYSTEM_ALERT_WINDOW.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** The main destinations, one per tab of the bottom bar (decision 7: five tabs at the bottom). */
private enum class Tab(val route: String, val label: String, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int) {
    HOME("dashboard", "Inicio", R.drawable.ic_home, R.drawable.ic_home_fill),
    EARNINGS("earnings", "Ganancias", R.drawable.ic_savings, R.drawable.ic_savings_fill),
    EXPENSES("expenses", "Gastos", R.drawable.ic_receipt_long, R.drawable.ic_receipt_long_fill),
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
    // A notification asked for a screen: switch tab; the screen itself consumes the request
    val pendingOpen by com.ubers.observador.ui.AppNavigation.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingOpen) {
        if (pendingOpen == com.ubers.observador.ui.AppNavigation.OPEN_LAST_WEEK && current != Tab.EARNINGS.route) open(Tab.EARNINGS)
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
                DashboardScreen(accessibilityEnabled = accessibilityEnabled, onOpenEarnings = { open(Tab.EARNINGS) })
            }
            composable(Tab.EARNINGS.route) {
                com.ubers.observador.ui.earnings.EarningsScreen()
            }
            composable(Tab.EXPENSES.route) {
                com.ubers.observador.ui.expenses.ExpensesScreen()
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
