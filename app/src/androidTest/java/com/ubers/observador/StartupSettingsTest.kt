package com.ubers.observador

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.AppTheme
import com.ubers.observador.ui.StartupState
import com.ubers.observador.ui.StartupViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** QA emulator only. Confirms the first ready state already carries the saved theme. */
class StartupSettingsTest {
    @Test fun readyContainsSavedThemeAndWelcomeWithoutADefaultReadyFrame() = runBlocking {
        val app: Application = ApplicationProvider.getApplicationContext()
        val settings = AppSettings(app)
        val previous = settings.themeSettings.first()
        val done = settings.onboardingDone.first()
        var vm: StartupViewModel? = null
        try {
            settings.updateTheme(AppTheme.NIGHT)
            vm = withContext(Dispatchers.Main) { StartupViewModel(app) }
            val ready = withTimeout(10_000) { vm.state.first { it is StartupState.Ready } } as StartupState.Ready
            assertEquals(AppTheme.NIGHT, ready.theme.theme)
            assertEquals(done, ready.onboardingDone)
            withContext(Dispatchers.Main) { vm.retry() }
            val next = withTimeout(10_000) { vm.state.first { it is StartupState.Ready } } as StartupState.Ready
            assertEquals(ready, next)
            settings.updateTheme(AppTheme.SUN)
            val updated = withTimeout(10_000) { vm.state.first { it is StartupState.Ready && it.theme.theme == AppTheme.SUN } } as StartupState.Ready
            assertEquals(AppTheme.SUN, updated.theme.theme)
        } finally {
            vm?.viewModelScope?.cancel()
            settings.updateTheme(previous.theme)
        }
    }
}
