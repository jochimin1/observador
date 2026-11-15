package com.ubers.observador.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.AppSettings
import com.ubers.observador.domain.ThemeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface StartupState {
    data object Loading : StartupState
    data object Failed : StartupState
    data class Ready(val onboardingDone: Boolean?, val theme: ThemeSettings) : StartupState
}

/** Read settings asynchronously; keep the content gated until theme and welcome are known. */
class StartupViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = AppSettings(app)
    private val mutable = MutableStateFlow<StartupState>(StartupState.Loading)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init { retry() }
    fun retry() {
        if (job?.isActive == true) return
        mutable.value = StartupState.Loading
        job = viewModelScope.launch {
            try {
                combine(settings.onboardingDone, settings.themeSettings) { done, theme ->
                    StartupState.Ready(done, theme)
                }.collect { mutable.value = it }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.value = StartupState.Failed }
        }
    }
}
