package com.ubers.observador

import com.ubers.observador.domain.reports.ReportPeriod
import com.ubers.observador.ui.reports.ReportsNavigation
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
    ) { notificationsGranted = hasNotificationPermission() /* start service regardless */ }

    // Refreshed on every resume so the dashboard reflects the system accessibility toggle
    private var accessibilityEnabled by mutableStateOf(false)

    // 1.29.0: shown on the welcome's «Avisos» page, read again on every resume
    private var notificationsGranted by mutableStateOf(true)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appSettings = com.ubers.observador.data.AppSettings(applicationContext)
        accessibilityEnabled = UberAccessibilityService.isEnabled(this)
        notificationsGranted = hasNotificationPermission()
        // 1.29.0: the welcome asks for the permissions itself; a driver updating from an older
        // version (reading already on) skips it and is never asked again
        val onboardingDone = kotlinx.coroutines.runBlocking { appSettings.onboardingDone.first() }
        val showOnboarding = com.ubers.observador.domain.Onboarding.shouldShow(onboardingDone, accessibilityEnabled)
        if (!showOnboarding) {
            if (onboardingDone == null) lifecycleScope.launch { appSettings.updateOnboardingDone(true) }
            requestPermissionsIfNeeded()
        }
        // Fase 8L: opening the app after «Cerrar Observador» turns reading back on
        com.ubers.observador.ui.AppNavigation.closeRequested.value = false
        lifecycleScope.launch { com.ubers.observador.data.AppSettings(applicationContext).reopenAfterClose() }
        lifecycleScope.launch {
            com.ubers.observador.ui.AppNavigation.closeRequested.collect { close ->
                if (close) {
                    com.ubers.observador.ui.AppNavigation.closeRequested.value = false
                    finishAndRemoveTask()
                }
            }
        }
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
            // Latched: turning reading on mid-welcome must not close it; «Ver bienvenida» opens it again
            var onboarding by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(showOnboarding) }
            val done by appSettings.onboardingDone.collectAsState(initial = onboardingDone)
            androidx.compose.runtime.LaunchedEffect(done) { if (done == false) onboarding = true }
            ObservadorTheme(resolved) {
                com.ubers.observador.ui.settings.RestoreResultDialog()
                if (onboarding) {
                    com.ubers.observador.ui.onboarding.OnboardingScreen(
                        accessibilityOn = accessibilityEnabled,
                        notificationsOn = notificationsGranted,
                        onRequestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onFinish = {
                            onboarding = false
                            lifecycleScope.launch { appSettings.updateOnboardingDone(true) }
                        }
                    )
                } else {
                    ObservadorNavHost(accessibilityEnabled = accessibilityEnabled)
                }
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
        notificationsGranted = hasNotificationPermission()
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
    fun openReports(preset: com.ubers.observador.domain.reports.ReportPreset?) {
        ReportsNavigation.preset.value = preset
        navController.navigate(ReportsNavigation.ROUTE) { launchSingleTop = true }
    }
    // A notification asked for a screen: switch tab; the screen itself consumes the request
    val pendingOpen by com.ubers.observador.ui.AppNavigation.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(pendingOpen) {
        if (pendingOpen == com.ubers.observador.ui.AppNavigation.OPEN_LAST_WEEK && current != Tab.EARNINGS.route) open(Tab.EARNINGS)
    }
    Scaffold(
        containerColor = com.ubers.observador.ui.theme.screenBackground(),
        // 1.27.0, Violeta: the + in the middle opens the quick actions
        floatingActionButton = {
            if (com.ubers.observador.ui.theme.LocalPastelBackground.current) {
                QuickActionsButton(onExpenses = { open(Tab.EXPENSES) }, onReports = { openReports(null) })
            }
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
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
                DashboardScreen(
                    accessibilityEnabled = accessibilityEnabled,
                    onOpenEarnings = { open(Tab.EARNINGS) },
                    onOpenReports = { p -> openReports(ReportsNavigation.forStats(p)) }
                )
            }
            composable(Tab.EARNINGS.route) {
                com.ubers.observador.ui.earnings.EarningsScreen(
                    onOpenReports = { w -> openReports(w?.let(ReportsNavigation::forWindow)) }
                )
            }
            composable(Tab.EXPENSES.route) {
                com.ubers.observador.ui.expenses.ExpensesScreen(
                    onOpenReports = { thisMonth ->
                        openReports(com.ubers.observador.domain.reports.ReportPreset(if (thisMonth) ReportPeriod.THIS_MONTH else ReportPeriod.LAST_MONTH))
                    }
                )
            }
            // Fase 8J: not a tab; opened from Ajustes, Inicio, Ganancias and Gastos
            composable(ReportsNavigation.ROUTE) {
                com.ubers.observador.ui.reports.ReportsScreen(onBack = { navController.popBackStack() })
            }
            composable(Tab.ANALYSIS.route) {
                IntelligenceScreen(onNavigateBack = null)
            }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(onNavigateBack = null, onOpenReports = { openReports(null) })
            }
        }
    }
}

/**
 * The + of the Violeta bottom bar (1.27.0): start or stop measuring km by hand, add a fill-up or
 * an expense (Gastos), or open «Reportes y exportación».
 */
@Composable
private fun QuickActionsButton(onExpenses: () -> Unit, onReports: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = androidx.compose.runtime.remember { com.ubers.observador.data.AppSettings(context.applicationContext) }
    val manual by settings.distanceManual.collectAsState(initial = false)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.FloatingActionButton(
            onClick = { open = true },
            shape = androidx.compose.foundation.shape.CircleShape,
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = "Acciones rápidas")
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(if (manual) "Detener recorrido de km" else "Iniciar recorrido de km") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_route), contentDescription = null) },
                onClick = {
                    open = false
                    scope.launch { settings.updateDistanceManual(!manual) }
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Cargar combustible o gasto") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_local_gas_station), contentDescription = null) },
                onClick = { open = false; onExpenses() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Reportes y exportación") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                onClick = { open = false; onReports() }
            )
        }
    }
}
