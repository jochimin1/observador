package com.ubers.observador.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubers.observador.data.OnlineRepository
import com.ubers.observador.data.db.AppDatabase
import com.ubers.observador.data.debug.OnlineTestSeeder
import com.ubers.observador.domain.online.OnlineSession
import com.ubers.observador.service.OnlineDebugState
import com.ubers.observador.service.RideLifecycleMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnlineDebugUiState(
    val live: OnlineDebugState = OnlineDebugState(),
    val recent: List<OnlineSession> = emptyList(),
    val stored: Int = 0,
    val message: String? = null
)

/** Debug builds only (Fase 8A): reads sessions; writes only TEST sessions, through the repository. */
class OnlineDebugViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = OnlineRepository(AppDatabase.getInstance(app))
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<OnlineDebugUiState> = combine(
        RideLifecycleMonitor.online,
        repository.observeRecent(RECENT),
        repository.observeCount(),
        message
    ) { live, recent, count, text -> OnlineDebugUiState(live, recent, count, text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnlineDebugUiState())

    fun loadTestSessions() = viewModelScope.launch {
        message.value = try {
            val sessions = OnlineTestSeeder.generate(System.currentTimeMillis())
            repository.replaceTestSessions(OnlineTestSeeder.PREFIX, sessions)
            "${sessions.size} sesiones TEST cargadas. Junto con los viajes TEST se ven en «Tiempo en línea»."
        } catch (e: Exception) {
            Log.e(TAG, "Could not load TEST sessions", e)
            "No se pudieron cargar las sesiones TEST."
        }
    }

    fun clearTestSessions() = viewModelScope.launch {
        message.value = try {
            val n = repository.deleteTestSessions(OnlineTestSeeder.PREFIX)
            "$n sesiones TEST borradas. Las reales no se tocan."
        } catch (e: Exception) {
            Log.e(TAG, "Could not delete TEST sessions", e)
            "No se pudieron borrar las sesiones TEST."
        }
    }

    private companion object {
        const val TAG = "OnlineDebug"
        const val RECENT = 8
    }
}
